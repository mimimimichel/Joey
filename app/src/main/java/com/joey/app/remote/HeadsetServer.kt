package com.joey.app.remote

import android.graphics.Bitmap
import android.util.Log
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Les yeux de Joey dans un casque (Meta Quest) ou tout autre navigateur du réseau local : ouvrir
 * https://<adresse du téléphone>:8443 (ou http://…:8080 sans VR). Repris du cockpit de Bebop IA.
 *
 * Vidéo en JPEG (~12 images par seconde), réalité virtuelle où la nacelle suit la tête, manettes,
 * et conversation avec Joey par le micro et le casque audio du Quest (WebSocket).
 */
class HeadsetServer(
    /** Image actuelle, capturée sur le thread de l'interface (null si pas d'image). */
    private val grab: () -> Bitmap?,
    /** Infos à afficher sur la page, en JSON. */
    private val state: () -> String,
    /** Orientation de la tête (pitch, yaw en degrés), suivi de la tête par la nacelle, recentrage. */
    private val onHead: (pitch: Double, yaw: Double, follow: Boolean, recenter: Boolean) -> Unit = { _, _, _, _ -> },
    /** Manettes du casque : manche gauche (x, y) et droit (x, y), de -1 à 1 (y vers le haut = négatif). */
    private val onSticks: (lx: Double, ly: Double, rx: Double, ry: Double) -> Unit = { _, _, _, _ -> },
    /** Boutons : « talk » (parler à Joey), « find_me » (chercher la personne)… */
    private val onButton: (String) -> Unit = {},
    /** Pour l'adresse sécurisée (https), obligatoire pour la réalité virtuelle dans le navigateur du casque. */
    private val tls: javax.net.ssl.SSLServerSocketFactory? = null,
    private val port: Int = 8080,
    private val tlsPort: Int = 8443,
    /** Son du micro du casque (PCM 16 bits, 16 kHz). */
    private val onMic: (ByteArray) -> Unit = {},
    /** Nombre de casques reliés en audio (0 = la voix repasse par le téléphone). */
    private val onAudioClients: (Int) -> Unit = {},
    /** Tuile de carte (z, x, y) en JPEG, téléchargée par le téléphone (null si indisponible). */
    private val onTile: (Int, Int, Int) -> ByteArray? = { _, _, _ -> null },
    /** Autres adresses (journal de garde…) : chemin -> (type, contenu), ou null. */
    private val extra: (String) -> Pair<String, ByteArray>? = { null },
) {
    companion object {
        private const val TAG = "HeadsetStream"
        private const val BOUNDARY = "joeyframe"

        /** Adresses IPv4 du téléphone sur ses réseaux locaux (Wi-Fi, partage de connexion). */
        /** Adresse Tailscale (100.64.0.0/10) : pour voir Joey depuis dehors. */
        fun isTailscale(a: Inet4Address): Boolean {
            val b = a.address
            return (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64
        }

        fun localAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                // Pas les interfaces 4G/5G (rmnet, ccmni…) : Wi-Fi, partage de connexion, et Tailscale (tun, 100.x)
                .filter { nif -> listOf("rmnet", "ccmni", "pdp", "v4-", "dummy").none { nif.name.startsWith(it) } }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { nif ->
                    nif.inetAddresses.toList().filterIsInstance<Inet4Address>()
                        .filter { a -> a.isSiteLocalAddress || isTailscale(a) }
                        .mapNotNull { it.hostAddress }
                }
                .distinct()
        }.getOrDefault(emptyList())
    }

    @Volatile var running = false
        private set
    private var server: ServerSocket? = null
    private var tlsServer: ServerSocket? = null
    @Volatile private var pollAt = 0L
    private val viewers = AtomicInteger(0)
    @Volatile private var latest: ByteArray? = null
    @Volatile private var latestAt = 0L
    private val frameLock = Object()

    val viewerCount: Int get() = viewers.get()

    /** Adresses à ouvrir dans le casque : https d'abord (réalité virtuelle et suivi de la tête). */
    fun urls(): List<String> = localAddresses().flatMap { ip ->
        listOfNotNull(if (tlsServer != null) "https://$ip:$tlsPort" else null, "http://$ip:$port")
    }

    fun start(): Boolean {
        if (running) return true
        return try {
            val s = ServerSocket(port)
            server = s
            running = true
            thread(name = "casque-serveur", isDaemon = true) { acceptLoop(s) }
            tls?.let { f ->
                runCatching { f.createServerSocket(tlsPort) }
                    .onSuccess { ts ->
                        tlsServer = ts
                        thread(name = "casque-serveur-https", isDaemon = true) { acceptLoop(ts) }
                    }
                    .onFailure { Log.e(TAG, "https", it) }
            }
            thread(name = "casque-images", isDaemon = true) { captureLoop() }
            true
        } catch (e: Exception) {
            Log.e(TAG, "démarrage", e)
            false
        }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        runCatching { tlsServer?.close() }
        server = null
        tlsServer = null
        synchronized(frameLock) { frameLock.notifyAll() }
    }

    // ---- images ------------------------------------------------------------------------------
    private fun captureOnMain(): Bitmap? = runCatching { grab() }.getOrNull()

    /** Capture des images seulement quand quelqu'un regarde. */
    private fun captureLoop() {
        while (running) {
            val t0 = System.currentTimeMillis()
            val watched = viewers.get() > 0 || System.currentTimeMillis() - pollAt < 2000
            if (watched) {
                val bmp = captureOnMain()
                if (bmp != null) {
                    // Image partagée avec la caméra : on la compresse sans la libérer.
                    val out = java.io.ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 75, out)
                    synchronized(frameLock) {
                        latest = out.toByteArray()
                        latestAt = System.currentTimeMillis()
                        frameLock.notifyAll()
                    }
                }
            }
            val wait = 80 - (System.currentTimeMillis() - t0)
            Thread.sleep(if (watched) maxOf(5, wait) else 300)
        }
    }

    // ---- serveur HTTP minimal ---------------------------------------------------------------
    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val client = try { s.accept() } catch (e: Exception) { break }
            thread(name = "casque-client", isDaemon = true) {
                try {
                    handle(client)
                } catch (e: Exception) {
                    // client parti
                } finally {
                    runCatching { client.close() }
                }
            }
        }
    }

    /** Lit une ligne d'en-tête HTTP octet par octet (sans tampon : le flux sert ensuite au WebSocket). */
    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun handle(c: Socket) {
        c.soTimeout = 15_000
        val input = c.getInputStream()
        val request = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val path = request.split(' ').getOrNull(1) ?: "/"
        val out = c.getOutputStream()
        when {
            path.startsWith("/audio") && headers["upgrade"]?.lowercase() == "websocket" -> audio(c, input, out, headers["sec-websocket-key"] ?: return)
            path.startsWith("/stream") -> stream(out)
            path.startsWith("/state") -> send(out, "application/json; charset=utf-8", state().toByteArray())
            path.startsWith("/head") -> {
                val q = query(path)
                val p = q["p"]?.toDoubleOrNull()
                val y = q["y"]?.toDoubleOrNull()
                if (p != null && y != null) onHead(p, y, q["f"] == "1", q["c"] == "1")
                val lx = q["lx"]?.toDoubleOrNull()
                if (lx != null) onSticks(lx, q["ly"]?.toDoubleOrNull() ?: 0.0, q["rx"]?.toDoubleOrNull() ?: 0.0, q["ry"]?.toDoubleOrNull() ?: 0.0)
                q["bt"]?.let { onButton(it) }
                send(out, "application/json; charset=utf-8", state().toByteArray())
            }
            path.startsWith("/tile") -> {
                val q = query(path)
                val z = q["z"]?.toIntOrNull(); val x = q["x"]?.toIntOrNull(); val y = q["y"]?.toIntOrNull()
                val img = if (z != null && x != null && y != null) runCatching { onTile(z, x, y) }.getOrNull() else null
                if (img == null) send(out, "text/plain", "pas de tuile".toByteArray(), "404 Not Found")
                else send(out, "image/jpeg", img)
            }
            path.startsWith("/frame") -> {
                pollAt = System.currentTimeMillis()
                val f = latest
                if (f == null) send(out, "text/plain", "pas d'image".toByteArray(), "404 Not Found")
                else send(out, "image/jpeg", f)
            }
            else -> {
                val x = runCatching { extra(path) }.getOrNull()
                if (x != null) send(out, x.first, x.second) else send(out, "text/html; charset=utf-8", PAGE.toByteArray())
            }
        }
    }

    // ---- audio du casque (WebSocket) ---------------------------------------------------------
    private class AudioClient(val out: OutputStream)
    private val audioClients = java.util.concurrent.CopyOnWriteArrayList<AudioClient>()

    /** Envoie la voix de l'IA à tous les casques reliés (PCM 16 bits, 24 kHz). */
    fun sendAudio(pcm: ByteArray) {
        for (cl in audioClients) runCatching { wsSend(cl, 0x2, pcm) }.onFailure { audioClients.remove(cl) }
    }

    /** Message de commande au casque (ex. « flush » : jeter le son en attente). */
    fun sendText(text: String) {
        for (cl in audioClients) runCatching { wsSend(cl, 0x1, text.toByteArray()) }.onFailure { audioClients.remove(cl) }
    }

    private fun wsSend(cl: AudioClient, opcode: Int, payload: ByteArray) {
        val n = payload.size
        val header = java.io.ByteArrayOutputStream()
        header.write(0x80 or opcode)
        when {
            n < 126 -> header.write(n)
            n < 65536 -> { header.write(126); header.write(n shr 8); header.write(n and 0xFF) }
            else -> { header.write(127); for (i in 7 downTo 0) header.write(((n.toLong() shr (8 * i)) and 0xFF).toInt()) }
        }
        synchronized(cl) {
            cl.out.write(header.toByteArray())
            cl.out.write(payload)
            cl.out.flush()
        }
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) throw java.io.EOFException()
            off += r
        }
    }

    private fun audio(c: Socket, input: java.io.InputStream, out: OutputStream, key: String) {
        val accept = android.util.Base64.encodeToString(
            java.security.MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
            android.util.Base64.NO_WRAP,
        )
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray())
        out.flush()
        c.soTimeout = 0
        val cl = AudioClient(out)
        audioClients.add(cl)
        onAudioClients(audioClients.size)
        try {
            val head = ByteArray(2)
            while (running) {
                readFully(input, head)
                val opcode = head[0].toInt() and 0x0F
                val masked = (head[1].toInt() and 0x80) != 0
                var len = (head[1].toInt() and 0x7F).toLong()
                if (len == 126L) { val e = ByteArray(2); readFully(input, e); len = ((e[0].toInt() and 0xFF) shl 8 or (e[1].toInt() and 0xFF)).toLong() }
                else if (len == 127L) { val e = ByteArray(8); readFully(input, e); len = e.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) } }
                if (len > 1_000_000) break
                val mask = ByteArray(4)
                if (masked) readFully(input, mask)
                val payload = ByteArray(len.toInt())
                readFully(input, payload)
                if (masked) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                when (opcode) {
                    0x2 -> onMic(payload)
                    0x8 -> break
                    0x9 -> wsSend(cl, 0xA, payload) // ping -> pong
                }
            }
        } finally {
            audioClients.remove(cl)
            onAudioClients(audioClients.size)
        }
    }

    private fun query(path: String): Map<String, String> =
        path.substringAfter('?', "").split('&').mapNotNull { kv ->
            val i = kv.indexOf('=')
            if (i <= 0) null else kv.substring(0, i) to java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8")
        }.toMap()

    private fun send(out: OutputStream, type: String, body: ByteArray, status: String = "200 OK") {
        out.write(("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body)
        out.flush()
    }

    private fun stream(out: OutputStream) {
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.flush()
        viewers.incrementAndGet()
        try {
            var sentAt = 0L
            while (running) {
                val frame: ByteArray
                synchronized(frameLock) {
                    while (running && latestAt == sentAt) frameLock.wait(1000)
                    frame = latest ?: ByteArray(0)
                    sentAt = latestAt
                }
                if (!running) break
                if (frame.isEmpty()) continue
                out.write("--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n".toByteArray())
                out.write(frame)
                out.write("\r\n".toByteArray())
                out.flush()
            }
        } finally {
            viewers.decrementAndGet()
        }
    }

    /**
     * Page du casque : ce que voit Joey en grand, et ce qu'il dit. Bouton « Entrer en VR » (adresse https) :
     * la nacelle suit la tête (gauche/droite et haut/bas, 1 pour 1). Gâchette = « devant » ici, grip = suivi
     * marche/arrêt (à l'arrêt, manche droit = joystick), A = parler à Joey, B = « cherche-moi », X/Y = image plus petite/grande.
     */
    private val PAGE = """
<!doctype html>
<html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Joey – ses yeux</title>
<style>
  html,body{margin:0;height:100%;background:#000;color:#fff;font-family:system-ui,sans-serif;overflow:hidden}
  #view{display:block;margin:0 auto;width:100%;height:calc(100% - 70px);object-fit:contain}
  #btns{position:absolute;left:0;right:0;bottom:10px;display:flex;gap:10px;justify-content:center;align-items:center;flex-wrap:wrap}
  button{font-size:18px;padding:10px 16px;border-radius:10px;border:0;background:#4fc3f7;color:#000}
  #vr{background:#66bb6a;font-weight:bold}
  #note{font-size:14px;color:#cfd8dc}
  #gl{display:none}
  #pad{position:absolute;left:10px;top:10px;text-align:center}
  #pad button{width:56px;height:56px;margin:3px;font-size:22px;background:rgba(79,195,247,.75)}
  #journal{position:absolute;right:10px;top:10px;width:300px;max-height:60%;overflow:auto;background:rgba(0,0,0,.6);padding:8px;border-radius:10px;font-size:14px}
  #journal img{width:100%;border-radius:6px;margin-top:4px}
  .alert{color:#ff8a65}
</style></head><body>
<canvas id="view" width="1280" height="800"></canvas>
<div id="pad">
  <button data-d="haut">▲</button><br>
  <button data-d="gauche">◀</button><button data-d="devant">●</button><button data-d="droite">▶</button><br>
  <button data-d="bas">▼</button></div>
<div id="journal"><b>🛡 Journal de garde</b><div id="ev"></div></div>
<div id="btns">
  <button id="vr" style="display:none">🥽 Entrer en VR</button>
  <button id="talk">🎙 Parler à Joey</button>
  <button id="find">👀 Cherche-moi</button>
  <button onclick="button('photo')">📸 Photo</button>
  <button onclick="button('dance')">🕺 Danse</button>
  <button onclick="button('watch')">🛡 Garde on/off</button>
  <button onclick="document.documentElement.requestFullscreen&&document.documentElement.requestFullscreen()">Plein écran</button>
  <span id="note"></span></div>
<canvas id="gl"></canvas>
<script>
const V=document.getElementById('view'), g=V.getContext('2d'), W=1280, H=800, VH=720;
let state={}, frame=null, follow=true, inVR=false, audioOn=false, dirty=true;

// ---- dessin : l'image de la caméra, et en bas ce que dit Joey ----
function draw(){
  g.fillStyle='#000';g.fillRect(0,0,W,H);
  if(frame){const r=Math.min(W/frame.width,VH/frame.height),w=frame.width*r,h=frame.height*r;g.drawImage(frame,(W-w)/2,(VH-h)/2,w,h);}
  else{g.fillStyle='#4fc3f7';g.font='40px sans-serif';g.textAlign='center';g.fillText('En attente de l\'image de Joey…',W/2,VH/2);}
  g.fillStyle='rgba(0,0,0,.75)';g.fillRect(0,VH,W,H-VH);
  g.font='26px sans-serif';g.textAlign='left';
  const line=(state.joey||'').slice(-90);
  g.fillStyle='#4fc3f7';g.fillText(line?('Joey : '+line):'',16,VH+34);
  g.font='20px sans-serif';g.fillStyle='#b0bec5';
  const info=[state.live||'',state.gimbal||'',state.pose||'',inVR?(follow?'tête : suit ton regard':'tête : manche droit'):'',state.tracking?('🔎 '+state.tracking):''].filter(x=>x).join('  ·  ');
  g.fillText(info,16,VH+66);
  if(inVR){g.strokeStyle='rgba(79,195,247,.6)';g.lineWidth=2;g.beginPath();g.moveTo(W/2-20,VH/2);g.lineTo(W/2+20,VH/2);g.moveTo(W/2,VH/2-20);g.lineTo(W/2,VH/2+20);g.stroke();}
}
async function frameLoop(){
  while(true){
    try{const r=await fetch('/frame?'+Date.now(),{cache:'no-store'});
      if(r.ok){const img=await createImageBitmap(await r.blob());if(frame&&frame.close)frame.close();frame=img;dirty=true;}}catch(e){}
    await new Promise(r=>setTimeout(r,70));
  }
}
async function statePoll(){
  while(true){
    if(!inVR){try{state=await (await fetch('/state',{cache:'no-store'})).json();dirty=true;}catch(e){}}
    await new Promise(r=>setTimeout(r,300));
  }
}
function paint(){if(dirty){draw();dirty=false;}requestAnimationFrame(paint);}
frameLoop();statePoll();paint();

// ---- boutons ----
const note=document.getElementById('note'), vrBtn=document.getElementById('vr');
if(!window.isSecureContext){
  note.textContent='VR, suivi de la tête et audio : ouvre l\'adresse https://…:8443 (accepte l\'avertissement de sécurité).';
}else if(navigator.xr){
  navigator.xr.isSessionSupported('immersive-vr').then(ok=>{if(ok)vrBtn.style.display='inline-block';else note.textContent='VR non disponible dans ce navigateur.'});
}
function button(b){fetch('/head?bt='+b,{cache:'no-store'}).catch(()=>{});}
vrBtn.onclick=()=>{startVR();};
document.getElementById('talk').onclick=()=>{startAudio();button('talk');};
document.getElementById('find').onclick=()=>button('find_me');
document.querySelectorAll('#pad button').forEach(b=>b.onclick=()=>button('look_'+b.dataset.d));
async function journal(){
  while(true){
    try{const ev=await (await fetch('/events',{cache:'no-store'})).json();
      document.getElementById('ev').innerHTML=ev.map(e=>'<div class="'+(e.alert?'alert':'')+'">'+new Date(e.time).toLocaleString('fr-FR')+
        ' — '+e.text.replace(/</g,'&lt;')+(e.photo?'<br><img loading="lazy" src="/snap?id='+e.id+'">':'')+'</div><hr>').join('')||'(rien)';}catch(e){}
    await new Promise(r=>setTimeout(r,15000));
  }
}
journal();

// ---- réalité virtuelle ----
let recenter=true, lastHead=0;
async function startVR(){
  const canvas=document.getElementById('gl');
  const gl=canvas.getContext('webgl',{xrCompatible:true});
  const session=await navigator.xr.requestSession('immersive-vr',{optionalFeatures:['local']});
  inVR=true;dirty=true;
  await gl.makeXRCompatible();
  session.updateRenderState({baseLayer:new XRWebGLLayer(session,gl)});
  const ref=await session.requestReferenceSpace('local');
  const vs='attribute vec3 p;attribute vec2 t;uniform mat4 P;varying vec2 u;void main(){u=t;gl_Position=P*vec4(p,1.0);}';
  const fs='precision mediump float;varying vec2 u;uniform sampler2D s;void main(){gl_FragColor=texture2D(s,u);}';
  function sh(type,src){const s=gl.createShader(type);gl.shaderSource(s,src);gl.compileShader(s);return s;}
  const pr=gl.createProgram();gl.attachShader(pr,sh(gl.VERTEX_SHADER,vs));gl.attachShader(pr,sh(gl.FRAGMENT_SHADER,fs));gl.linkProgram(pr);
  // L'image reste devant les yeux (attachée à la tête) : c'est la nacelle qui tourne avec toi.
  let size=1.0;
  const buf=gl.createBuffer();
  function setQuad(){const Z=-2.0,QW=1.6*size,QH=QW*H/W;
    gl.bindBuffer(gl.ARRAY_BUFFER,buf);gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([-QW,-QH,Z,0,1, QW,-QH,Z,1,1, -QW,QH,Z,0,0, QW,QH,Z,1,0]),gl.STATIC_DRAW);}
  setQuad();
  const tex=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,tex);
  gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
  const aP=gl.getAttribLocation(pr,'p'),aT=gl.getAttribLocation(pr,'t'),uP=gl.getUniformLocation(pr,'P');
  session.addEventListener('select',()=>{recenter=true;});
  session.addEventListener('squeeze',()=>{follow=!follow;recenter=true;dirty=true;});
  session.addEventListener('end',()=>{inVR=false;dirty=true;fetch('/head?p=0&y=0&f=0').catch(()=>{});});
  const prev={a:false,b:false};
  function onFrame(tm,fr){
    session.requestAnimationFrame(onFrame);
    let rx=0,ry=0,bt='';
    for(const src of session.inputSources){const gp=src.gamepad;if(!gp||gp.axes.length<4)continue;
      const b4=gp.buttons[4]&&gp.buttons[4].pressed, b5=gp.buttons[5]&&gp.buttons[5].pressed;
      if(src.handedness==='left'){if(b4){size=Math.max(0.5,size-0.01);setQuad();} if(b5){size=Math.min(1.6,size+0.01);setQuad();}}
      else{rx=gp.axes[2]||0;ry=gp.axes[3]||0;
        if(b4&&!prev.a){bt='talk';startAudio();} if(b5&&!prev.b)bt='find_me'; prev.a=b4;prev.b=b5;}}
    const pose=fr.getViewerPose(ref);if(!pose)return;
    const q=pose.transform.orientation;
    const fx=-2*(q.x*q.z+q.w*q.y), fy=-2*(q.y*q.z-q.w*q.x), fz=-(1-2*(q.x*q.x+q.y*q.y));
    const yaw=Math.atan2(fx,-fz)*180/Math.PI, pitch=Math.asin(Math.max(-1,Math.min(1,fy)))*180/Math.PI;
    if(tm-lastHead>50||bt){lastHead=tm;
      fetch('/head?p='+pitch.toFixed(1)+'&y='+yaw.toFixed(1)+'&f='+(follow?1:0)+'&c='+(recenter?1:0)+
        '&lx=0&ly=0&rx='+rx.toFixed(2)+'&ry='+ry.toFixed(2)+(bt?'&bt='+bt:''),{cache:'no-store'})
        .then(r=>r.json()).then(s=>{state=s;dirty=true;}).catch(()=>{});
      recenter=false;}
    draw();
    gl.bindTexture(gl.TEXTURE_2D,tex);gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,gl.RGBA,gl.UNSIGNED_BYTE,V);
    const layer=session.renderState.baseLayer;
    gl.bindFramebuffer(gl.FRAMEBUFFER,layer.framebuffer);
    gl.clearColor(0,0,0,1);gl.clear(gl.COLOR_BUFFER_BIT|gl.DEPTH_BUFFER_BIT);
    gl.useProgram(pr);gl.bindBuffer(gl.ARRAY_BUFFER,buf);
    gl.enableVertexAttribArray(aP);gl.vertexAttribPointer(aP,3,gl.FLOAT,false,20,0);
    gl.enableVertexAttribArray(aT);gl.vertexAttribPointer(aT,2,gl.FLOAT,false,20,12);
    gl.bindTexture(gl.TEXTURE_2D,tex);
    for(const view of pose.views){const vp=layer.getViewport(view);gl.viewport(vp.x,vp.y,vp.width,vp.height);
      gl.uniformMatrix4fv(uP,false,view.projectionMatrix);gl.drawArrays(gl.TRIANGLE_STRIP,0,4);}
  }
  session.requestAnimationFrame(onFrame);
}

// ---- audio : ton micro vers Joey, sa voix dans le casque ----
const WORKLET="class MicPcm extends AudioWorkletProcessor{constructor(){super();this.b=new Int16Array(1600);this.n=0}"+
 "process(i){const c=i[0][0];if(c)for(let k=0;k<c.length;k++){const s=Math.max(-1,Math.min(1,c[k]));"+
 "this.b[this.n++]=s<0?s*0x8000:s*0x7fff;if(this.n===this.b.length){this.port.postMessage(this.b.buffer.slice(0));this.n=0}}return true}}"+
 "registerProcessor('mic-pcm',MicPcm);";
let audio=null;
async function startAudio(){
  if(audio)return;
  const talk=document.getElementById('talk');
  if(!window.isSecureContext||!navigator.mediaDevices){note.textContent='Pour l\'audio dans le casque, ouvre l\'adresse https://…:8443.';return;}
  try{
    const ws=new WebSocket('wss://'+location.host+'/audio');ws.binaryType='arraybuffer';
    const out=new AudioContext({sampleRate:24000});let next=0;const playing=new Set();
    ws.onmessage=e=>{
      if(typeof e.data==='string'){if(e.data==='flush'){playing.forEach(s=>{try{s.stop()}catch(_){}});playing.clear();next=0;}return;}
      const pcm=new Int16Array(e.data);const b=out.createBuffer(1,pcm.length,24000);const ch=b.getChannelData(0);
      for(let i=0;i<pcm.length;i++)ch[i]=pcm[i]/32768;
      const src=out.createBufferSource();src.buffer=b;src.connect(out.destination);
      next=Math.max(next,out.currentTime+0.05);src.start(next);next+=b.duration;
      playing.add(src);src.onended=()=>playing.delete(src);
    };
    const stream=await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true,autoGainControl:true}});
    const mic=new AudioContext({sampleRate:16000});
    await mic.audioWorklet.addModule(URL.createObjectURL(new Blob([WORKLET],{type:'application/javascript'})));
    const node=new AudioWorkletNode(mic,'mic-pcm');
    node.port.onmessage=({data})=>{if(ws.readyState===1)ws.send(data);};
    mic.createMediaStreamSource(stream).connect(node);
    out.resume();mic.resume();
    audio={ws,out,mic,stream};audioOn=true;dirty=true;
    talk.textContent='🎙 Conversation dans le casque';talk.disabled=true;
    ws.onclose=()=>{stream.getTracks().forEach(t2=>t2.stop());mic.close();out.close();audio=null;audioOn=false;dirty=true;
      talk.textContent='🎙 Parler à Joey';talk.disabled=false;};
  }catch(e){note.textContent='Audio impossible : '+e.message;}
}
</script></body></html>
""".trimIndent()
}
