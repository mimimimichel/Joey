package com.joey.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import com.joey.app.brain.GeminiLive
import com.joey.app.brain.JoeyPrompt
import com.joey.app.brain.LiveState
import com.joey.app.brain.Memory
import com.joey.app.face.Activity
import com.joey.app.face.Mood
import com.joey.app.gimbal.FaceFollower
import com.joey.app.gimbal.GimbalState
import com.joey.app.gimbal.Head
import com.joey.app.gimbal.SpgGimbal
import com.joey.app.senses.Camera
import com.joey.app.senses.PhoneSensors
import com.joey.app.remote.HeadsetServer
import com.joey.app.senses.CocoClasses
import com.joey.app.senses.FaceSense
import com.joey.app.senses.ObjectSense
import com.joey.app.senses.Seen
import com.joey.app.brain.GeminiVision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Une ligne de sous-titre : qui parle et ce qui a été dit. */
data class Line(val joey: Boolean, val text: String)

data class UiState(
    val gimbal: GimbalState = GimbalState.OFF,
    val battery: Int = -1,
    val live: LiveState = LiveState.OFF,
    val mood: Mood = Mood.NEUTRE,
    val busy: Boolean = false,
    val lines: List<Line> = emptyList(),
    val log: List<String> = emptyList(),
    val message: String? = null,
    val hasKey: Boolean = false,
    val robotVoice: Boolean = true,
    val camera: Boolean = true,
    val frontCamera: Boolean = true,
    val invertPan: Boolean = false,
    val bargeIn: Boolean = true,
    val subtitles: Boolean = true,
    val followFace: Boolean = true,
    /** Vitesse de la tête, en % de la vitesse max mesurée. */
    val headSpeed: Int = 100,
    val seesFace: Boolean = false,
    /** Ce qu'il cherche ou suit du regard en ce moment (null : les visages). */
    val tracking: String? = null,
    /** Casque (Quest) : adresse à ouvrir, ou null si la vue casque est arrêtée. */
    val headsetUrl: String? = null,
    val headsetFollow: Boolean = false,
    val usesPhoneSensors: Boolean = false,
    val memories: Int = 0,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        /** À augmenter quand la mesure de la tête change : elle est refaite à la connexion suivante. */
        const val CALIB_VERSION = 2
    }

    private val prefs = app.getSharedPreferences("joey", Context.MODE_PRIVATE)

    var geminiKey: String
        get() = (prefs.getString("gemini_key", "") ?: "").ifBlank { BuildConfig.GEMINI_KEY }
        private set(v) = prefs.edit().putString("gemini_key", v.trim()).apply()
    private fun flag(name: String, def: Boolean) = prefs.getBoolean(name, def)
    private fun setFlag(name: String, v: Boolean) = prefs.edit().putBoolean(name, v).apply()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    val memory = Memory(File(app.filesDir, "memoire.json"))
    val camera = Camera()
    private val faces = FaceSense()
    private val objects = ObjectSense(app, ::log)
    private val vision = GeminiVision { geminiKey }

    // Ce que la tête suit : les visages par défaut, ou des objets du détecteur (« le chien ») pendant un moment.
    @Volatile private var trackLabels: Set<String>? = null
    @Volatile private var trackUntil = 0L
    @Volatile private var trackName = ""

    val gimbal: SpgGimbal = SpgGimbal(
        context = app,
        savedAddress = { prefs.getString("gimbal_address", null) },
        saveAddress = { prefs.edit().putString("gimbal_address", it).apply() },
        onState = { s ->
            _state.update { it.copy(gimbal = s) }
            // Première connexion : Joey « s'étire » pour mesurer la vitesse réelle de sa tête.
            if (s == GimbalState.READY && !calibrating && (prefs.getInt("calib_version", 0) < CALIB_VERSION ||
                    sensors.available && prefs.getInt("phone_pan_sign", 0) == 0)) {
                calibrating = true
                thread(isDaemon = true) { Thread.sleep(1500); calibrateHead() }
            }
        },
        onLog = ::log,
        onBattery = { b -> _state.update { it.copy(battery = b) } },
    )
    private val headSpeed get() = prefs.getInt("head_speed_pct", 100)
    /** Joey regarde avec la caméra avant (côté écran) : pour la nacelle, haut et bas sont inversés. */
    private val viewTiltSign get() = if (flag("camera", true) && flag("front_camera", true)) -1 else 1
    /** Capteurs du téléphone (posé sur la nacelle) : où regarde vraiment la caméra. */
    val sensors: PhoneSensors = PhoneSensors(app) { flag("camera", true) && flag("front_camera", true) }
    val head: Head = Head(gimbal, rightSign = { if (flag("invert_pan", false)) -1 else 1 }, tiltSign = { viewTiltSign },
        speedFraction = { headSpeed / 100.0 }, onLog = ::log, phone = sensors,
        onPhoneSigns = { p, t -> prefs.edit().putInt("phone_pan_sign", p).putInt("phone_tilt_sign", t).apply() })
    val follower: FaceFollower = FaceFollower(
        gimbal,
        initialPanSign = prefs.getInt("follow_pan_sign", 1),
        initialTiltSign = prefs.getInt("follow_tilt_sign", 1),
        initialConfirmed = prefs.getBoolean("follow_confirmed", false),
        onSignsChanged = { p, t -> prefs.edit().putInt("follow_pan_sign", p).putInt("follow_tilt_sign", t).apply() },
        onConfirmed = { prefs.edit().putBoolean("follow_confirmed", true).apply() },
        onLog = ::log,
        maxStick = { head.maxStickNow() },
        stickFor = { head.stickFor(it) },
    )

    @Volatile private var live: GeminiLive? = null
    @Volatile private var greeted = false
    @Volatile private var moodResetAt = 0L
    private var cameraOwner: LifecycleOwner? = null

    private fun refreshSettings() = _state.update {
        it.copy(
            hasKey = geminiKey.isNotBlank(),
            robotVoice = flag("robot_voice", true),
            camera = flag("camera", true),
            frontCamera = flag("front_camera", true),
            invertPan = flag("invert_pan", false),
            bargeIn = flag("barge_in", true),
            subtitles = flag("subtitles", false),
            followFace = flag("follow_face", true),
            headSpeed = headSpeed,
            memories = memory.size,
            usesPhoneSensors = head.usesPhone,
        )
    }

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.FRENCH)
    fun log(text: String) {
        val line = "${timeFmt.format(Date())} $text"
        _state.update { it.copy(log = (it.log + line).takeLast(200)) }
    }

    fun message(text: String?) = _state.update { it.copy(message = text) }

    // ---- réglages ---------------------------------------------------------------------------
    fun saveSettings(key: String, robotVoice: Boolean, camera: Boolean, front: Boolean, invertPan: Boolean, bargeIn: Boolean, subtitles: Boolean, followFace: Boolean, speed: Int) {
        val cameraChanged = camera != flag("camera", true) || front != flag("front_camera", true)
        geminiKey = key
        setFlag("robot_voice", robotVoice)
        setFlag("camera", camera)
        setFlag("front_camera", front)
        setFlag("invert_pan", invertPan)
        setFlag("barge_in", bargeIn)
        setFlag("subtitles", subtitles)
        setFlag("follow_face", followFace)
        prefs.edit().putInt("head_speed_pct", speed).apply()
        follower.enabled = followFace
        if (!followFace) gimbal.stick(0, 0)
        refreshSettings()
        if (cameraChanged) cameraOwner?.let { startCamera(it) }
    }

    fun forgetAll() {
        memory.clear()
        refreshSettings()
        log("Mémoire effacée.")
    }

    // ---- sens ---------------------------------------------------------------------------------
    fun startCamera(owner: LifecycleOwner) {
        cameraOwner = owner
        follower.enabled = flag("follow_face", true)
        camera.onFrame = { bmp -> onFrame(bmp) }
        camera.stop()
        if (flag("camera", true)) {
            camera.start(owner, getApplication(), flag("front_camera", true)) { log(it) }
        }
    }

    /** Chaque image (~10/s) : on cherche la cible du moment (visage, ou objet) et la tête la suit. */
    // Une cible n'est crue qu'après 2 images de suite (une fausse détection d'une image ne fait pas bouger la tête).
    private var targetStreak = 0
    private var faceStreak = 0

    private fun onFrame(bmp: android.graphics.Bitmap) {
        val labels = trackLabels
        if (labels != null && System.currentTimeMillis() < trackUntil) {
            val best = objects.detect(bmp).filter { it.label in labels && it.score >= (if (it.label == "person") 0.55 else 0.5) }
                .maxByOrNull { it.score * kotlin.math.sqrt(it.box.w * it.box.h) }
            targetStreak = if (best != null) targetStreak + 1 else 0
            val sure = best?.takeIf { targetStreak >= 2 }
            // Une personne : on vise le haut de la silhouette (la tête), pour que le visage apparaisse.
            val y = sure?.let { if (it.label == "person") it.box.cy - it.box.h * 0.3 else it.box.cy }
            follower.onFace(sure?.let { (it.box.cx * 2 - 1).toFloat() }, y?.let { (it * 2 - 1).toFloat() })
        } else {
            if (labels != null) {
                trackLabels = null
                follower.forget()
                _state.update { it.copy(tracking = null) }
            }
            val f = faces.find(bmp)?.takeIf { it.size >= 0.05f }
            faceStreak = if (f != null) faceStreak + 1 else 0
            val sure = f?.takeIf { faceStreak >= 2 }
            follower.onFace(sure?.x, sure?.y)
        }
        val sees = follower.sees
        if (sees != _state.value.seesFace) _state.update { it.copy(seesFace = sees) }
    }

    /**
     * Cherche du regard une catégorie du détecteur du téléphone (personne, chien, chaise…) en faisant le tour.
     * Trouvé : la tête la suit pendant [keepMs], puis revient aux visages. À appeler sur le fil des mouvements.
     */
    private fun searchLocal(what: String, labels: Set<String>, keepMs: Long): Boolean {
        trackName = what
        trackLabels = labels
        trackUntil = System.currentTimeMillis() + 120_000
        follower.forget()
        _state.update { it.copy(tracking = what) }
        val found = head.searchTarget(follower::seenSince)
        if (found) {
            trackUntil = System.currentTimeMillis() + keepMs
        } else {
            trackUntil = 0
        }
        return found
    }

    /** Champ de vision approximatif de la caméra (degrés), selon le sens de l'image. */
    private fun fov(): Pair<Double, Double> {
        val b = camera.frame(5000)
        val landscape = b == null || b.width >= b.height
        return if (landscape) 68.0 to 50.0 else 50.0 to 68.0
    }

    /** Objet inconnu du détecteur : Gemini regarde chaque direction. Trouvé : la tête se centre dessus. */
    private fun searchGemini(what: String): com.joey.app.brain.Found? {
        var hit: com.joey.app.brain.Found? = null
        _state.update { it.copy(tracking = what) }
        try {
            head.scan(listOf(0.0, -60.0, 60.0, -120.0, 120.0, 179.0), listOf(0.0, -30.0)) { _ ->
                Thread.sleep(650) // image fraîche après le mouvement
                val jpeg = camera.captureJpegBase64() ?: return@scan false
                val f = runCatching { vision.find(jpeg, what) }.onFailure { log("Vision Gemini : ${it.message}") }.getOrNull()
                if (f != null && f.found && f.confidence >= 0.5) { hit = f; true } else false
            }
            hit?.box?.let { b ->
                val (h, v) = fov()
                val ex = b.cx * 2 - 1
                val ey = -(b.cy * 2 - 1)
                head.turnStick(ex * h / 2 * follower.panSign, ey * v / 2 * follower.tiltSign)
            }
        } finally {
            _state.update { it.copy(tracking = null) }
        }
        return hit
    }

    /** Ce que voit le détecteur du téléphone en ce moment. */
    private fun seenNow(): List<Seen> = camera.frame()?.let { objects.detect(it) } ?: emptyList()

    fun connectGimbal() = gimbal.connect()
    fun disconnectGimbal() = gimbal.disconnect()

    // ---- commandes à la main (écran « tête ») -----------------------------------------------
    /** Joystick de l'écran : x = droite, y = haut, de -1 à 1. */
    fun manualStick(x: Float, y: Float) {
        val sign = if (flag("invert_pan", false)) -1 else 1
        if (x != 0f || y != 0f) follower.pause(4000)
        val speed = head.maxStickNow()
        gimbal.stick((x * speed * sign).toInt(), (y * speed * viewTiltSign).toInt())
    }

    private fun background(name: String, body: () -> String): Unit = moveAsync(holdMs = 4000) { body() }

    // ---- mouvements de tête : un seul à la fois, le dernier demandé annule le précédent ----------------
    private val motion = Executors.newSingleThreadExecutor()
    @Volatile private var motionJob: Future<*>? = null
    private val motionGen = AtomicInteger()

    private fun startMotion(): Int {
        motionJob?.cancel(true)
        follower.pause(120_000)
        _state.update { it.copy(busy = true) }
        return motionGen.incrementAndGet()
    }

    private fun endMotion(gen: Int, holdMs: Long) {
        if (gen != motionGen.get()) return // un mouvement plus récent a pris la main
        follower.pausedUntil = System.currentTimeMillis() + holdMs
        _state.update { it.copy(busy = false) }
    }

    /** Mouvement en arrière-plan : l'outil répond tout de suite, Joey parle pendant que la tête bouge. */
    private fun moveAsync(holdMs: Long, body: () -> String) {
        val gen = startMotion()
        motionJob = motion.submit {
            val r = try {
                body()
            } catch (e: InterruptedException) {
                "interrompu"
            } catch (e: Exception) {
                "échec : ${e.message}"
            } finally {
                gimbal.stick(0, 0)
                endMotion(gen, holdMs)
            }
            log("Tête : $r")
        }
    }

    /** Mouvement dont Joey a besoin du résultat (voir ce qu'il y a là-bas) : on attend, mais vite. */
    private fun moveSync(holdMs: Long, timeoutMs: Long = 15_000, body: () -> String): String {
        val gen = startMotion()
        val job = motion.submit<String> {
            try { body() } catch (e: InterruptedException) { "interrompu" } finally { gimbal.stick(0, 0) }
        }
        motionJob = job
        return try {
            job.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            "interrompu"
        } finally {
            endMotion(gen, holdMs)
        }
    }

    /** Mesure la vitesse réelle de la tête et la retient. */
    @Volatile private var calibrating = false

    fun calibrateHead(): Unit = moveAsync(holdMs = 1000) {
        calibrating = true
        try {
            log("Je mesure ma tête (vitesse et sens)…")
            val r = head.calibrate()
            if (r.startsWith("vitesses")) {
                prefs.edit()
                    .putString("speed_table", head.table.filter { it.first > 0 }.joinToString(",") { "${it.first}:${it.second}" })
                    .putInt("calib_version", CALIB_VERSION)
                    .apply()
                refreshSettings()
            }
            r
        } finally {
            calibrating = false
        }
    }

    fun recenter() = background("centrer") { head.center() }
    fun nod() = background("oui") { head.nod() }
    fun shake() = background("non") { head.shake() }
    fun sweep() = background("balayage") { head.sweep { } }

    // ---- conversation -----------------------------------------------------------------------
    fun micPressed() {
        if (live != null) {
            stopLive()
            return
        }
        if (geminiKey.isBlank()) {
            message("Ajoute d'abord ta clé Gemini dans les réglages (⚙).")
            return
        }
        startLive()
    }

    private fun startLive() {
        greeted = false
        val robot = flag("robot_voice", true)
        val l = GeminiLive(
            context = getApplication(),
            apiKey = geminiKey,
            network = { null },
            eyes = camera,
            systemPrompt = JoeyPrompt.SYSTEM + (if (robot) JoeyPrompt.ROBOT_STYLE else "") + memory.promptText(),
            tools = GeminiLive.toJson(JoeyPrompt.TOOLS) as JSONArray,
            onTool = ::onTool,
            onTranscript = ::onTranscript,
            onTurnEnd = { lastSpeaker = null },
            onState = ::onLiveState,
            onError = { e -> log(e); message(e) },
            bargeIn = flag("barge_in", true),
            robot = { flag("robot_voice", true) },
            micOptional = headsetAudioClients > 0,
        )
        live = l
        l.start()
        routeLiveAudio()
    }

    fun stopLive() {
        live?.stop()
        live = null
        head.stop()
        _state.update { it.copy(live = LiveState.OFF, mood = Mood.NEUTRE) }
    }

    private fun onLiveState(s: LiveState) {
        _state.update { it.copy(live = s) }
        if (s == LiveState.LISTENING && !greeted) {
            greeted = true
            live?.event("La conversation commence (la personne vient d'appuyer sur ton bouton). " +
                "Salue-la en une phrase courte, à ta façon.", reply = true)
        }
        if (s == LiveState.OFF) live = null
    }

    @Volatile private var lastSpeaker: Boolean? = null
    @Volatile private var lastAutoSearch = 0L

    /** On lui parle et il ne voit personne : il cherche du regard, tout seul (au plus toutes les 20 s). */
    private fun maybeSearchSpeaker() {
        val now = System.currentTimeMillis()
        if (!gimbal.ready || !follower.enabled || !flag("camera", true) || follower.sees) return
        // On ne cherche que si personne n'est vu depuis un moment (sinon la tête bouge « toute seule » sans raison).
        if (_state.value.busy || now - follower.seenAt < 8000 || now - lastAutoSearch < 30_000) return
        lastAutoSearch = now
        log("👀 on me parle, je cherche qui")
        moveAsync(holdMs = 0) {
            if (searchLocal("personne", setOf("person"), keepMs = 2500)) "trouvé quelqu'un" else "personne en vue"
        }
    }

    private fun onTranscript(person: Boolean, text: String) {
        if (person) maybeSearchSpeaker()
        val joey = !person
        _state.update { st ->
            val lines = st.lines.toMutableList()
            if (lastSpeaker == joey && lines.isNotEmpty() && lines.last().joey == joey) {
                lines[lines.size - 1] = Line(joey, (lines.last().text + text).takeLast(400))
            } else {
                lines.add(Line(joey, text.trimStart()))
            }
            st.copy(lines = lines.takeLast(6))
        }
        lastSpeaker = joey
    }

    /** Appelé sur un fil à part par Gemini Live ; peut bloquer le temps du mouvement. */
    private fun onTool(name: String, args: Map<String, Any?>): Map<String, Any?> {
        CrashReport.mark("outil $name")
        val noHead = "ta nacelle (ton cou) n'est pas connectée : tu ne peux pas bouger la tête pour l'instant"
        return when (name) {
            "regarder" -> {
                if (!gimbal.ready) return mapOf("result" to noHead)
                val dir = args["direction"] as? String ?: "devant"
                val deg = (args["degres"] as? Number)?.toDouble()
                log("👀 regarder $dir${deg?.let { " ${it.toInt()}°" } ?: ""}")
                // Il garde ce regard quelques secondes avant de revenir te suivre.
                val r = moveSync(holdMs = if (dir == "devant") 1000 else 7000) { head.lookDirection(dir, deg) }
                live?.sendFrameNow()
                mapOf("result" to r, "visage_dans_l_image" to follower.sees)
            }
            "chercher" -> {
                if (!gimbal.ready) return mapOf("result" to noHead)
                if (!flag("camera", true)) return mapOf("result" to "ta caméra est coupée (réglages) : tu ne vois rien")
                val what = (args["objet"] as? String)?.trim().orEmpty().ifEmpty { "personne" }
                val labels = CocoClasses.forTarget(what)
                log("🔎 je cherche : $what" + (labels?.let { " (détecteur : ${it.joinToString()})" } ?: " (vision Gemini)"))
                if (labels != null) {
                    val person = "person" in labels
                    val found = AtomicBoolean(false)
                    moveSync(holdMs = 0, timeoutMs = 40_000) {
                        found.set(searchLocal(what, labels, keepMs = if (person) 2500 else 15_000))
                        if (found.get()) "trouvé : $what" else "pas trouvé : $what"
                    }
                    // Vérification : une fois la tête centrée, on regarde à nouveau (une fausse détection ne compte pas).
                    var confirmed = false
                    if (found.get()) {
                        Thread.sleep(900)
                        val now = seenNow()
                        confirmed = now.any { it.label in labels && it.score >= 0.5 } || (person && follower.sees)
                        if (!confirmed) log("🔎 fausse alerte : plus de « $what » une fois la tête tournée")
                    }
                    live?.sendFrameNow()
                    if (confirmed) mapOf("result" to "trouvé (vérifié) : ta tête est tournée vers « $what » et le suit des yeux" +
                        (if (person) " (puis suit son visage)" else " pendant 15 s") + ". Regarde l'image pour en dire plus.",
                        "vu_maintenant" to objects.describe(seenNow()))
                    else if (found.get()) mapOf("result" to "fausse alerte : j'ai cru voir « $what » en tournant, mais une fois la " +
                        "tête tournée il n'y est pas. Dis-le honnêtement (« j'ai cru… ») et propose de chercher encore.",
                        "vu_maintenant" to objects.describe(seenNow()))
                    else mapOf("result" to "pas de « $what » en faisant le tour (360°, deux hauteurs). Peut-être caché, trop loin, " +
                        "ou dans le noir.")
                } else {
                    var hit: com.joey.app.brain.Found? = null
                    moveSync(holdMs = 8000, timeoutMs = 90_000) {
                        hit = searchGemini(what)
                        if (hit != null) "trouvé : $what" else "pas trouvé : $what"
                    }
                    live?.sendFrameNow()
                    hit?.let { mapOf("result" to "trouvé, ta tête est tournée vers « $what »", "description" to it.description) }
                        ?: mapOf("result" to "pas de « $what » en faisant le tour (360°, deux hauteurs)")
                }
            }
            "regarder_autour" -> {
                if (!gimbal.ready) return mapOf("result" to noHead)
                log("👀 regarder autour")
                val seen = StringBuilder()
                moveSync(holdMs = 3000) {
                    head.sweep { where ->
                        live?.sendFrameNow()
                        seen.append("$where : ${objects.describe(seenNow())}. ")
                    }
                }
                mapOf("result" to "fait (tu as reçu une image à chaque arrêt)", "detecteur" to seen.toString().trim())
            }
            "exprimer" -> {
                val mood = when (args["emotion"] as? String) {
                    "content" -> Mood.CONTENT
                    "triste" -> Mood.TRISTE
                    "etonne" -> Mood.ETONNE
                    "fache" -> Mood.FACHE
                    "fatigue" -> Mood.FATIGUE
                    "amuse" -> Mood.AMUSE
                    "mefiant" -> Mood.MEFIANT
                    else -> Mood.NEUTRE
                }
                setMood(mood)
                val geste = args["geste"] as? String ?: "aucun"
                // Le geste se fait pendant qu'il parle : réponse immédiate.
                val r = if (geste != "aucun" && gimbal.ready) {
                    moveAsync(holdMs = 1500) {
                        when (geste) {
                            "oui" -> head.nod()
                            "non" -> head.shake()
                            "hesiter" -> head.hesitate()
                            else -> ""
                        }
                    }
                    "fait"
                } else if (geste != "aucun") "expression affichée ; geste impossible ($noHead)" else "fait"
                mapOf("result" to r)
            }
            "retenir" -> {
                val note = args["note"] as? String ?: ""
                memory.add(note)
                refreshSettings()
                log("🧠 Retenu : $note")
                mapOf("result" to "retenu")
            }
            "etat" -> {
                val pose = head.pose()
                mapOf(
                    "nacelle" to if (gimbal.ready) "connectée" else "non connectée",
                    "tete" to (pose?.let { "%.0f° %s, inclinée à %.0f°".format(kotlin.math.abs(it.first), if (it.first >= 0) "à droite" else "à gauche", it.second) } ?: "position inconnue"),
                    "batterie_nacelle_pourcent" to gimbal.battery.takeIf { it >= 0 },
                    "roues" to "pas encore branchées (Roomba)",
                    "souvenirs" to memory.size,
                    "visage_vu" to follower.sees,
                    "boussole" to (sensors.compassText() ?: "indisponible"),
                    "orientation_par" to if (head.usesPhone) "capteurs du téléphone" else "angles de la nacelle",
                    "suivi_du_visage" to follower.enabled,
                )
            }
            else -> mapOf("result" to "outil inconnu")
        }
    }

    private fun setMood(m: Mood) {
        _state.update { it.copy(mood = m) }
        val at = System.currentTimeMillis() + 9000
        moodResetAt = at
        // L'expression s'efface toute seule après quelques secondes.
        thread(isDaemon = true) {
            Thread.sleep(9000)
            if (moodResetAt == at) _state.update { it.copy(mood = Mood.NEUTRE) }
        }
    }

    // ---- pour le visage ----------------------------------------------------------------------
    fun voiceLevel(): Float = live?.level ?: 0f

    fun faceActivity(s: UiState): Activity = when {
        s.live == LiveState.SPEAKING -> Activity.PARLE
        s.live == LiveState.LISTENING -> Activity.ECOUTE
        s.live == LiveState.CONNECTING -> Activity.REFLECHIT
        else -> Activity.REPOS
    }

    fun gaze(): Pair<Float, Float> {
        if (head.moving) {
            // Joey regarde à sa droite = vers la gauche de l'écran vu de face ; vers le haut = haut de l'écran.
            return Pair(-head.gazeX * 0.8f, -head.gazeY * 0.8f)
        }
        // Il regarde la personne. Image de la caméra avant non retournée : à droite de l'image = à droite de Joey,
        // donc vers la gauche de l'écran vu de face.
        if (follower.sees) return Pair(-follower.faceX * 0.9f, follower.faceY * 0.9f)
        return Pair(0f, 0f)
    }

    fun poseText(): String {
        val p = head.pose() ?: return if (gimbal.ready) "angles en attente…" else ""
        return "%s %.0f° · incl. %.0f°".format(if (p.first >= 0) "droite" else "gauche", kotlin.math.abs(p.first), p.second)
    }

    // Doit rester en dernier (juste avant onCleared) : tout ce qui est au-dessus est prêt (sinon plantage au lancement).
    init {
        sensors.start()
        head.setPhoneSigns(prefs.getInt("phone_pan_sign", 0), prefs.getInt("phone_tilt_sign", 0))
        thread(name = "visee-casque", isDaemon = true) { aimLoop() }
        prefs.getString("speed_table", null)?.let { t ->
            runCatching {
                head.setTable(t.split(",").map { it.split(":").let { (a, b) -> a.toInt() to b.toDouble() } })
            }
        }
        refreshSettings()
        CrashReport.mark("viewmodel prêt")
    }

    // ---- casque (Meta Quest) : voir par les yeux de Joey, diriger la tête avec la sienne -------------
    @Volatile private var headset: HeadsetServer? = null
    @Volatile private var headsetAudioClients = 0
    @Volatile private var questYaw0 = 0.0
    @Volatile private var questFollowing = false

    fun setHeadset(on: Boolean) {
        if (!on) {
            headset?.stop()
            headset = null
            _state.update { it.copy(headsetUrl = null, headsetFollow = false) }
            log("Vue casque arrêtée.")
            return
        }
        if (headset != null) return
        val h = HeadsetServer(
            grab = { camera.frame(1500) },
            state = ::headsetState,
            onHead = ::questHead,
            onSticks = { _, _, rx, ry -> if (!questFollowing && (kotlin.math.abs(rx) > 0.15 || kotlin.math.abs(ry) > 0.15)) manualStick(rx.toFloat(), (-ry).toFloat()) else if (!questFollowing) manualStick(0f, 0f) },
            onButton = ::questButton,
            tls = headsetTls(),
            onMic = { pcm -> live?.feedMic(pcm) },
            onAudioClients = ::headsetAudio,
        )
        if (!h.start()) {
            message("Impossible de lancer la vue casque (port 8080 occupé ?).")
            return
        }
        headset = h
        val url = h.urls().firstOrNull()
        _state.update { it.copy(headsetUrl = url ?: "") }
        message(if (url == null) "Vue casque prête, mais le téléphone n'est sur aucun Wi-Fi : connecte-le au même Wi-Fi que le casque."
            else "Dans le navigateur du Quest (même Wi-Fi), ouvre :\n$url\n\nAccepte l'avertissement (Avancé → Continuer), puis « 🥽 Entrer en VR » : " +
                "la tête de Joey suit la tienne. Gâchette = « devant » ici ; grip = suivi marche/arrêt ; A = parler à Joey ; B = « cherche-moi ».")
    }

    /** Orientation du casque : la tête de Joey suit (cap relatif au moment du recentrage, inclinaison absolue). */
    private fun questHead(pitch: Double, yaw: Double, follow: Boolean, recenter: Boolean) {
        if (!follow) {
            if (questFollowing) { questFollowing = false; _state.update { it.copy(headsetFollow = false) } }
            return
        }
        if (!questFollowing || recenter) {
            // Début du suivi : on arrête tout autre mouvement ; « devant » = là où Joey regarde, le casque aussi.
            motionJob?.cancel(true)
            questYaw0 = yaw
            head.setHomeHere()
            if (!questFollowing) log("🥽 La tête suit le casque.")
            questFollowing = true
            _state.update { it.copy(headsetFollow = true) }
        }
        follower.pause(1500)
        head.aim(Head.wrap(yaw - questYaw0), pitch)
    }

    private fun aimLoop() {
        while (true) {
            Thread.sleep(40)
            runCatching { head.aimStep() }
        }
    }

    private fun questButton(b: String) {
        when (b) {
            "talk" -> if (live == null && geminiKey.isNotBlank()) startLive()
            "find_me" -> {
                questFollowing = false
                _state.update { it.copy(headsetFollow = false) }
                moveAsync(holdMs = 0) { if (searchLocal("personne", setOf("person"), keepMs = 2500)) "trouvé quelqu'un" else "personne en vue" }
            }
        }
    }

    /** Casque relié (ou non) en audio : la conversation passe par lui. */
    private fun headsetAudio(n: Int) {
        headsetAudioClients = n
        if (n > 0 && live == null && geminiKey.isNotBlank()) startLive()
        routeLiveAudio()
        log(if (n > 0) "🎧 Audio dans le casque : parle avec le micro du Quest." else "🎧 Audio du casque coupé.")
    }

    private fun routeLiveAudio() {
        val l = live ?: return
        val h = headset
        if (headsetAudioClients > 0 && h != null) {
            l.audioOut = { pcm -> h.sendAudio(pcm) }
            l.onFlush = { h.sendText("flush") }
            l.useLocalSpeaker = false
        } else {
            l.audioOut = null
            l.onFlush = null
            l.useLocalSpeaker = true
        }
    }

    /** Certificat de l'adresse https du casque (fourni dans l'application, auto-signé). */
    private fun headsetTls(): javax.net.ssl.SSLServerSocketFactory? = runCatching {
        val ks = java.security.KeyStore.getInstance("PKCS12")
        getApplication<Application>().assets.open("headset.p12").use { ks.load(it, "bebopia".toCharArray()) }
        val kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, "bebopia".toCharArray())
        val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, null)
        ctx.serverSocketFactory
    }.getOrNull()

    private fun headsetState(): String {
        val s = _state.value
        return org.json.JSONObject()
            .put("joey", s.lines.lastOrNull { it.joey }?.text?.trim() ?: "")
            .put("live", when (s.live) { LiveState.OFF -> "en veille"; LiveState.CONNECTING -> "réveil…"; LiveState.LISTENING -> "écoute"; LiveState.SPEAKING -> "parle" })
            .put("gimbal", if (s.gimbal == GimbalState.READY) "nacelle prête" else "nacelle : ${s.gimbal.name.lowercase()}")
            .put("pose", poseText())
            .put("tracking", s.tracking ?: "")
            .toString()
    }

    override fun onCleared() {
        headset?.stop()
        sensors.stop()
        stopLive()
        gimbal.disconnect()
        camera.stop()
    }
}
