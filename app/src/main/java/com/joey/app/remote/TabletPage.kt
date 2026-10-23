package com.joey.app.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * Le fil de ce que Joey pense et fait (les dernières lignes de son journal : ce qu'il entend, dit, les outils qu'il
 * appelle et leurs résultats, ses décisions), pour la tablette. Chaque ligne a un numéro : la page demande « depuis ».
 */
class TabletFeed(private val max: Int = 400) {
    private val lines = ArrayDeque<JSONObject>()
    private var next = 0L

    fun add(at: Long, kind: String, text: String) = synchronized(lines) {
        lines.addLast(JSONObject().put("id", ++next).put("t", at).put("k", kind).put("x", text.take(500)))
        while (lines.size > max) lines.removeFirst()
    }

    /** Les lignes après [since] (toutes les récentes si 0), au plus 150. */
    fun since(since: Long): JSONArray = synchronized(lines) {
        JSONArray().also { a -> lines.filter { it.getLong("id") > since }.takeLast(150).forEach { a.put(it) } }
    }
}

/**
 * La page de la tablette : ouvrir https://<adresse du téléphone>:8443/tablette (même Wi-Fi ; accepter l'avertissement
 * de sécurité, le certificat est fait par le téléphone). Ce qu'il voit, ce qu'il pense, sa carte, ses commandes, et lui
 * parler (micro de la tablette, ou en écrivant).
 */
object TabletPage {
    val HTML = """
<!doctype html>
<html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
<title>Joey — tablette</title>
<style>
:root{--bg:#0e1116;--card:#171b22;--line:#262c36;--txt:#e6e9ee;--dim:#8a93a3;--acc:#4fc3f7;--warn:#ffb74d;--bad:#ef5350;--ok:#66bb6a}
*{box-sizing:border-box}html,body{margin:0;height:100%;background:var(--bg);color:var(--txt);font:15px system-ui,sans-serif}
#app{display:grid;grid-template-columns:minmax(0,1.15fr) minmax(0,1fr);gap:10px;height:100%;padding:10px}
@media (max-width:820px){#app{grid-template-columns:1fr;height:auto}}
.card{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:10px;min-height:0}
#left{display:flex;flex-direction:column;gap:10px;min-height:0}
#video{position:relative;flex:1;min-height:220px;display:flex;align-items:center;justify-content:center;overflow:hidden;padding:0;background:#000}
#video img{max-width:100%;max-height:100%;object-fit:contain}
#badge{position:absolute;left:8px;top:8px;background:#000a;padding:4px 8px;border-radius:8px;font-size:13px}
#say{position:absolute;left:8px;right:8px;bottom:8px;background:#000b;padding:6px 10px;border-radius:8px;font-size:15px;min-height:1em}
#controls{display:grid;grid-template-columns:auto 1fr auto;gap:10px;align-items:center}
.stick{width:150px;height:150px;border-radius:50%;background:#0b0e13;border:2px solid var(--line);position:relative;touch-action:none}
.stick .knob{position:absolute;width:56px;height:56px;border-radius:50%;background:var(--acc);left:47px;top:47px;opacity:.85}
.stick .lab{position:absolute;width:100%;text-align:center;bottom:-20px;color:var(--dim);font-size:12px}
#buttons{display:flex;flex-wrap:wrap;gap:6px;justify-content:center}
button{background:#222936;color:var(--txt);border:1px solid var(--line);border-radius:10px;padding:9px 12px;font-size:14px}
button:active{background:#2e3747}button.on{border-color:var(--acc);color:var(--acc)}button.stop{background:#4a1c1c;border-color:var(--bad)}
#right{display:flex;flex-direction:column;gap:10px;min-height:0}
#status{display:flex;flex-wrap:wrap;gap:6px}
.pill{background:#0b0e13;border:1px solid var(--line);border-radius:999px;padding:3px 9px;font-size:13px;color:var(--dim)}
.pill b{color:var(--txt);font-weight:600}
#tabs{display:flex;gap:6px}#tabs button{flex:1}
#panes{flex:1;min-height:260px;position:relative}
.pane{position:absolute;inset:0;overflow:auto;display:none}.pane.show{display:block}
#feed div{padding:4px 2px;border-bottom:1px solid #1d222b;font-size:14px;line-height:1.35}
#feed .t{color:var(--dim);font-size:12px;margin-right:6px}
#feed .toi{color:#c5e1a5}#feed .joey{color:#81d4fa}#feed .outil{color:#ce93d8}#feed .erreur{color:var(--bad)}#feed .demande{color:var(--warn)}
#map{width:100%;height:100%;background:#0b0e13;border-radius:8px}
#talkbar{display:flex;gap:6px}#talkbar input{flex:1;background:#0b0e13;color:var(--txt);border:1px solid var(--line);border-radius:10px;padding:10px;font-size:15px}
#note{color:var(--warn);font-size:13px;min-height:1em}
</style></head><body>
<div id="app">
 <div id="left">
  <div class="card" id="video"><img id="cam" alt="ce que voit Joey"><div id="badge">…</div><div id="say"></div></div>
  <div class="card" id="controls">
   <div class="stick" id="drive"><div class="knob"></div><div class="lab">rouler</div></div>
   <div id="buttons">
    <button class="stop" data-c="stop">⛔ Stop</button>
    <button data-c="come">🫵 Viens ici</button>
    <button data-c="explore">🧭 Explorer</button>
    <button data-c="map">🗺 Cartographier</button>
    <button data-c="dock">🔌 Base</button>
    <button data-c="vacuum" id="bvac">🧹 Aspiration</button>
    <button data-c="center">🎯 Tête devant</button>
    <button data-c="autonomy" id="bauto">🤖 Autonome</button>
    <button data-c="sleep">💤 Dormir</button>
   </div>
   <div class="stick" id="head"><div class="knob"></div><div class="lab">tête</div></div>
  </div>
 </div>
 <div id="right">
  <div class="card" id="status"></div>
  <div id="tabs"><button class="on" data-p="feed">🧠 Ce qu'il pense</button><button data-p="mapp">🗺 Carte</button></div>
  <div class="card" id="panes">
   <div class="pane show" id="feed"></div>
   <div class="pane" id="mapp"><canvas id="map"></canvas></div>
  </div>
  <div class="card">
   <div id="talkbar"><input id="text" placeholder="Écrire à Joey…" autocomplete="off"><button id="send">Envoyer</button><button id="talk">🎙 Parler</button></div>
   <div id="note"></div>
  </div>
 </div>
</div>
<script>
const q=s=>document.querySelector(s);const note=q('#note');
let since=0,auto=false,vac=false;
// ---- image : flux continu (repli : une image à la fois) ----
const cam=q('#cam');cam.src='/stream';cam.onerror=()=>{setInterval(()=>{cam.src='/frame?'+Date.now()},250)};
function cmd(c,extra){return fetch('/tab/cmd?c='+c+(extra||'')).then(r=>r.json()).then(j=>{if(j.msg)note.textContent=j.msg;return j}).catch(()=>{})}
document.querySelectorAll('#buttons button').forEach(b=>b.onclick=()=>cmd(b.dataset.c));
// ---- onglets ----
document.querySelectorAll('#tabs button').forEach(b=>b.onclick=()=>{
 document.querySelectorAll('#tabs button').forEach(x=>x.classList.toggle('on',x===b));
 document.querySelectorAll('.pane').forEach(p=>p.classList.toggle('show',p.id===b.dataset.p));
 if(b.dataset.p==='mapp')drawMap();});
// ---- état et fil de pensée ----
const icons={toi:'🗣',joey:'🤖',outil:'🔧',log:'·',erreur:'⚠',mesure:'📏',demande:'📝',roues:'🛞',etat:'◦',tete:'👀',demarrage:'▶'};
function esc(s){return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;')}
async function poll(){
 try{
  const j=await (await fetch('/tab/state?since='+since)).json();
  const pills=[['humeur',j.mood],['conversation',j.live],['fait',j.busy?'occupé':'libre'],['voit',j.seeing||'—'],
   ['téléphone',j.phone+' %'],['roues',j.roomba||'non branchées'],['cou',j.gimbal],['carte',j.map],['devant',j.ahead]];
  q('#status').innerHTML=pills.map(p=>'<span class="pill">'+p[0]+' <b>'+esc(p[1]==null?'—':p[1])+'</b></span>').join('');
  q('#badge').textContent=(j.thinking?'💭 réfléchit… ':'')+(j.tracking?'suit : '+j.tracking:'')+(j.version?'  v'+j.version:'');
  q('#say').textContent=j.joey||'';
  auto=j.autonomy;vac=j.vacuum;q('#bauto').classList.toggle('on',auto);q('#bvac').classList.toggle('on',vac);
  const f=q('#feed');const atEnd=f.scrollTop+f.clientHeight>=f.scrollHeight-30;
  for(const e of j.feed){since=Math.max(since,e.id);if(e.k==='tete'||e.k==='etat')continue;
   const d=document.createElement('div');const t=new Date(e.t);
   d.className=e.k;d.innerHTML='<span class="t">'+t.toLocaleTimeString().slice(0,8)+'</span>'+(icons[e.k]||'·')+' '+esc(e.x);f.appendChild(d);}
  while(f.children.length>500)f.removeChild(f.firstChild);
  if(atEnd)f.scrollTop=f.scrollHeight;
 }catch(e){q('#badge').textContent='⚠ Joey ne répond pas';}
 setTimeout(poll,700);
}
poll();
// ---- carte ----
async function drawMap(){
 const pane=q('#mapp');if(!pane.classList.contains('show'))return;
 try{
  const m=await (await fetch('/tab/map')).json();const cv=q('#map');const W=cv.width=pane.clientWidth,H=cv.height=pane.clientHeight;
  const g=cv.getContext('2d');g.fillStyle='#0b0e13';g.fillRect(0,0,W,H);
  const px=Math.max(3,Math.min(W,H)/(2*m.radius+1));const ri=Math.floor(m.rx/m.cell),rj=Math.floor(m.ry/m.cell);
  // x (devant au départ) vers le haut, y (droite) vers la droite
  const X=j=>W/2+(j-rj)*px,Y=i=>H/2-(i-ri)*px;
  for(const c of m.cells){g.fillStyle=c[2]>1.5?'#cfd8dc':(c[2]<-0.9?'#1f3b2c':'#151a20');g.fillRect(X(c[1]),Y(c[0]),px+0.5,px+0.5);}
  g.fillStyle='#ef5350';for(const c of m.cliffs)g.fillRect(X(c[1]),Y(c[0]),px+0.5,px+0.5);
  g.font='12px system-ui';
  for(const p of m.places){g.fillStyle='#ffb74d';g.fillText('🏠 '+p.n,X(p.y/m.cell),Y(p.x/m.cell));}
  for(const p of m.items){g.fillStyle='#90a4ae';g.fillRect(X(p.y/m.cell)-2,Y(p.x/m.cell)-2,4,4);g.fillText(p.n,X(p.y/m.cell)+4,Y(p.x/m.cell)+4);}
  for(const p of m.people){g.fillStyle='#c5e1a5';g.fillText('🧍 '+p.n,X(p.y/m.cell),Y(p.x/m.cell));}
  if(m.base){g.fillStyle='#66bb6a';g.fillText('🔌 base',X(0),Y(0));}
  // le robot : un triangle dans la direction du cap
  const cx=X(m.ry/m.cell),cy=Y(m.rx/m.cell),a=m.rh*Math.PI/180;
  g.save();g.translate(cx,cy);g.rotate(a);g.fillStyle='#4fc3f7';g.beginPath();g.moveTo(0,-12);g.lineTo(8,9);g.lineTo(-8,9);g.closePath();g.fill();g.restore();
  g.fillStyle='#8a93a3';g.fillText(m.info,8,H-10);
 }catch(e){}
}
setInterval(drawMap,1500);
// ---- manettes tactiles : rouler (gauche) et tête (droite) ----
function stick(el,send){
 const k=el.querySelector('.knob');let id=null,vx=0,vy=0,timer=null;
 function set(e){const r=el.getBoundingClientRect();let x=(e.clientX-r.left)/r.width*2-1,y=(e.clientY-r.top)/r.height*2-1;
  const n=Math.hypot(x,y);if(n>1){x/=n;y/=n;}vx=x;vy=y;k.style.left=(47+x*47)+'px';k.style.top=(47+y*47)+'px';}
 el.onpointerdown=e=>{id=e.pointerId;el.setPointerCapture(id);set(e);send(vx,vy);timer=setInterval(()=>send(vx,vy),150);};
 el.onpointermove=e=>{if(e.pointerId===id)set(e);};
 const up=e=>{if(e.pointerId!==id)return;id=null;clearInterval(timer);vx=vy=0;k.style.left='47px';k.style.top='47px';send(0,0);};
 el.onpointerup=up;el.onpointercancel=up;
}
stick(q('#drive'),(x,y)=>cmd('drive','&f='+(-y).toFixed(2)+'&t='+x.toFixed(2)));
stick(q('#head'),(x,y)=>cmd('head','&x='+x.toFixed(2)+'&y='+(-y).toFixed(2)));
// ---- lui écrire ----
function send(){const t=q('#text').value.trim();if(!t)return;q('#text').value='';fetch('/tab/say?t='+encodeURIComponent(t)).then(r=>r.json()).then(j=>{if(j.msg)note.textContent=j.msg}).catch(()=>{});}
q('#send').onclick=send;q('#text').onkeydown=e=>{if(e.key==='Enter')send();};
// ---- lui parler : le micro de la tablette vers Joey, sa voix sur la tablette ----
const WORKLET="class MicPcm extends AudioWorkletProcessor{constructor(){super();this.b=new Int16Array(1600);this.n=0}"+
 "process(i){const c=i[0][0];if(c)for(let k=0;k<c.length;k++){const s=Math.max(-1,Math.min(1,c[k]));"+
 "this.b[this.n++]=s<0?s*0x8000:s*0x7fff;if(this.n===this.b.length){this.port.postMessage(this.b.buffer.slice(0));this.n=0}}return true}}"+
 "registerProcessor('mic-pcm',MicPcm);";
let audio=null;
q('#talk').onclick=async()=>{
 const talk=q('#talk');
 if(audio){audio.ws.close();return;}
 if(!window.isSecureContext||!navigator.mediaDevices){note.textContent="Pour parler par la tablette, ouvre l'adresse https://…:8443/tablette (et accepte l'avertissement). Sinon, écris-lui.";return;}
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
  audio={ws,out,mic,stream};talk.textContent='🔴 Raccrocher';talk.classList.add('on');
  note.textContent='Tu lui parles par la tablette ; sa voix sort ici.';
  ws.onclose=()=>{stream.getTracks().forEach(t=>t.stop());mic.close();out.close();audio=null;talk.textContent='🎙 Parler';talk.classList.remove('on');note.textContent='';};
 }catch(e){note.textContent='Micro impossible : '+e.message;}
};
</script></body></html>
""".trimIndent()
}
