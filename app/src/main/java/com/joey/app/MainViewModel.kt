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
    val wakeWord: Boolean = true,
    /** Mode garde (journal + alertes), et sujet ntfy pour les alertes sur le téléphone de Michael. */
    val watch: Boolean = false,
    val ntfyTopic: String = "",
    val idleLife: Boolean = true,
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
    /** Gens de la maison que Joey reconnaît, et qui il voit en ce moment. */
    val people: List<String> = emptyList(),
    val seeing: String? = null,
    /** Compte à rebours de la photo affiché sur le visage (null : pas de photo), et flash. */
    val countdown: Int? = null,
    val flashAt: Long = 0L,
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
    private val people = com.joey.app.brain.People(File(app.filesDir, "personnes.json"))
    private val faceId = com.joey.app.senses.FaceId(app)
    val houseMap = com.joey.app.brain.HouseMap(File(app.filesDir, "carte.json"))
    @Volatile private var currentPlace: String? = null
    val watch = com.joey.app.remote.Watch(File(app.filesDir, "garde"), { prefs.getString("ntfy_topic", null) }, ::log)
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
            wakeWord = flag("wake_word", true),
            watch = flag("watch", false),
            ntfyTopic = prefs.getString("ntfy_topic", "") ?: "",
            idleLife = flag("idle_life", true),
            headSpeed = headSpeed,
            memories = memory.size,
            people = people.names(),
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
    fun saveSettings(key: String, robotVoice: Boolean, camera: Boolean, front: Boolean, invertPan: Boolean, bargeIn: Boolean, subtitles: Boolean, followFace: Boolean, speed: Int,
                     wakeWord: Boolean = flag("wake_word", true), idleLife: Boolean = flag("idle_life", true)) {
        val cameraChanged = camera != flag("camera", true) || front != flag("front_camera", true)
        geminiKey = key
        setFlag("robot_voice", robotVoice)
        setFlag("camera", camera)
        setFlag("front_camera", front)
        setFlag("invert_pan", invertPan)
        setFlag("barge_in", bargeIn)
        setFlag("subtitles", subtitles)
        setFlag("follow_face", followFace)
        setFlag("wake_word", wakeWord)
        setFlag("idle_life", idleLife)
        updateWake()
        prefs.edit().putInt("head_speed_pct", speed).apply()
        follower.enabled = followFace
        if (!followFace) gimbal.stick(0, 0)
        refreshSettings()
        if (cameraChanged) cameraOwner?.let { startCamera(it) }
    }

    fun memories(): List<com.joey.app.brain.Memory.Note> = memory.all().reversed()
    fun forgetOne(n: com.joey.app.brain.Memory.Note) { memory.remove(n); refreshSettings() }

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
            val f = faces.find(bmp)?.takeIf { it.size >= 0.025f }
            faceStreak = if (f != null) faceStreak + 1 else 0
            val sure = f?.takeIf { faceStreak >= 2 }
            follower.onFace(sure?.x, sure?.y)
            if (sure != null) recognize(bmp, sure)
        }
        val sees = follower.sees
        if (sees != _state.value.seesFace) _state.update { it.copy(seesFace = sees) }
        detectMotion(bmp)
        if (flag("watch", false)) guard(bmp)
    }

    // ---- mode garde ------------------------------------------------------------------------------
    @Volatile private var lastGuardAt = 0L
    @Volatile private var lastMischiefCheck = 0L

    fun setWatch(on: Boolean) {
        setFlag("watch", on)
        refreshSettings()
        log(if (on) "🛡 Mode garde activé" else "🛡 Mode garde arrêté")
        if (on) watch.record("garde", "Mode garde activé", null, alert = false, everyMs = 0)
    }

    fun setNtfy(topic: String) {
        prefs.edit().putString("ntfy_topic", topic.trim()).apply()
        refreshSettings()
        if (topic.isNotBlank()) watch.notify("Joey est relié à ce téléphone : tu recevras ici ses alertes.", null)
    }

    private fun jpegNow(): ByteArray? = camera.zoomJpeg("tout")?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }

    /** Toutes les 1,5 s en mode garde : qui ou quoi est là ? Inconnu -> alerte ; chien -> Gemini juge s'il fait une bêtise. */
    private fun guard(bmp: android.graphics.Bitmap) {
        val now = System.currentTimeMillis()
        if (now - lastGuardAt < 1500) return
        lastGuardAt = now
        val seen = objects.detect(bmp).filter { it.score >= 0.55 }
        if (seen.any { it.label == "person" }) {
            val who = seeing()
            if (who != null) watch.record("vu_$who", "$who est là", jpegNow(), alert = false, everyMs = 30 * 60_000L)
            else if (follower.sees || seen.any { it.label == "person" && it.box.h > 0.3 })
                watch.record("inconnu", "Quelqu'un que je ne connais pas est là", jpegNow(), alert = true, everyMs = 10 * 60_000L)
        }
        val pet = seen.firstOrNull { it.label == "dog" || it.label == "cat" } ?: return
        if (now - lastMischiefCheck < 3 * 60_000L) return
        lastMischiefCheck = now
        val animal = if (pet.label == "dog") "chien" else "chat"
        val b64 = camera.zoomJpeg("tout") ?: return
        thread(isDaemon = true) {
            val answer = runCatching {
                vision.ask(b64, "Le $animal fait-il une bêtise (sur le canapé, le lit ou la table, mâche un objet, fouille la " +
                    "poubelle, fait ses besoins, renverse quelque chose) ? Commence ta réponse par OUI ou NON, puis décris en une phrase.")
            }.getOrNull() ?: return@thread
            val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
            val text = answer.removePrefix("OUI").removePrefix("NON").trimStart(' ', ',', '.', ':', '-')
            if (answer.trim().uppercase().startsWith("OUI")) watch.record("betise", "Le $animal fait une bêtise : $text", bytes, alert = true, everyMs = 10 * 60_000L)
            else watch.record(animal, "Le $animal est là : $text", bytes, alert = false, everyMs = 15 * 60_000L)
        }
    }

    // ---- vie propre : il remarque ce qui bouge, s'ennuie, s'endort, se réveille ----------------------
    private var prevGray: IntArray? = null
    @Volatile private var motionX = 0f
    @Volatile private var motionY = 0f
    @Volatile private var motionAt = 0L
    @Volatile private var lastHeadMoveAt = 0L
    @Volatile private var lastLifeAt = System.currentTimeMillis()
    @Volatile private var asleep = false

    /** Mouvement dans l'image (tête immobile) : comparaison de deux vignettes 32 x 24 en gris. */
    private fun detectMotion(bmp: android.graphics.Bitmap) {
        val now = System.currentTimeMillis()
        if (head.moving || _state.value.busy) { lastHeadMoveAt = now; prevGray = null; return }
        if (now - lastHeadMoveAt < 800) { prevGray = null; return }
        val small = android.graphics.Bitmap.createScaledBitmap(bmp, 32, 24, true)
        val px = IntArray(32 * 24)
        small.getPixels(px, 0, 32, 0, 0, 32, 24)
        val gray = IntArray(px.size) { ((px[it] shr 16 and 0xFF) + (px[it] shr 8 and 0xFF) + (px[it] and 0xFF)) / 3 }
        val prev = prevGray
        prevGray = gray
        if (prev == null) return
        var n = 0; var sx = 0f; var sy = 0f
        for (i in gray.indices) if (kotlin.math.abs(gray[i] - prev[i]) > 28) { n++; sx += i % 32; sy += i / 32 }
        // Entre 2 % et 60 % de l'image change : quelque chose bouge (au-delà : lumière, ou la tête bouge).
        if (n > gray.size * 0.02 && n < gray.size * 0.6) {
            motionX = (sx / n) / 16f - 1f
            motionY = (sy / n) / 12f - 1f
            motionAt = now
        }
    }

    private fun lifeLoop() {
        var lastGlance = 0L
        var nextBoredom = System.currentTimeMillis() + 90_000
        while (true) {
            Thread.sleep(300)
            val now = System.currentTimeMillis()
            val s = _state.value
            // Conversation lancée par « Joey » : 45 s sans rien dire de part et d'autre -> il retourne à ses rêveries.
            if (live != null && startedByWake && s.live == LiveState.LISTENING && !s.busy &&
                now - lastTalkAt > 45_000 && headsetAudioClients == 0) {
                log("💤 Fin de la conversation (silence) : dis « Joey » pour me rappeler")
                stopLive()
                continue
            }
            val active = live != null || s.busy || questFollowing || s.tracking != null
            if (active || follower.sees) lastLifeAt = now
            if (!flag("idle_life", true) || !gimbal.ready || !flag("camera", true)) continue
            // Réveil : quelqu'un, du mouvement, ou une conversation.
            if (asleep && (active || follower.sees || now - motionAt < 500)) {
                asleep = false
                lastLifeAt = now
                log("😮 Je me réveille")
                setMood(Mood.ETONNE)
                moveAsync(holdMs = 500) { head.look(null, 0.0) }
                continue
            }
            if (active || asleep) continue
            // Endormissement : 10 min sans personne ni mouvement.
            if (!flag("watch", false) && now - lastLifeAt > 10 * 60_000L && now - motionAt > 10 * 60_000L) {
                asleep = true
                log("😴 Je m'endors")
                moodResetAt = 0
                _state.update { it.copy(mood = Mood.DORT) }
                moveAsync(holdMs = 0) { head.look(null, -25.0) }
                continue
            }
            if (follower.sees) continue
            // Curiosité : quelque chose bouge (sans visage) -> un coup d'œil de ce côté.
            if (now - motionAt < 400 && now - lastGlance > 5000) {
                lastGlance = now
                lastLifeAt = now
                val mx = motionX; val my = motionY
                moveAsync(holdMs = 3000) {
                    head.turnStick(mx * 30.0 * follower.panSign, -my * 20.0 * follower.tiltSign)
                    "coup d'œil vers ce qui bouge"
                }
                continue
            }
            // Ennui : de temps en temps, il regarde ailleurs, puis revient.
            val guarding = flag("watch", false)
            if (now > nextBoredom && (guarding || now - lastLifeAt > 60_000)) {
                // En garde : une ronde du regard toutes les ~1 min 30 ; sinon un coup d'œil toutes les 2-4 min.
                nextBoredom = now + if (guarding) 90_000L else 120_000 + (Math.random() * 120_000).toLong()
                if (guarding) {
                    moveAsync(holdMs = 2000) { head.sweep(120.0) { }; "ronde du regard" }
                    continue
                }
                val side = if (Math.random() < 0.5) -1 else 1
                moveAsync(holdMs = 2000) {
                    val p0 = head.pose()?.first ?: 0.0
                    head.look(side * (30 + Math.random() * 50), 5.0)
                    Thread.sleep(1500 + (Math.random() * 2000).toLong())
                    head.look((head.pose()?.first ?: 0.0).let { p0 - it }, 0.0)
                    "je m'ennuie un peu"
                }
            }
        }
    }

    // ---- reconnaissance des gens de la maison (sur le téléphone) ------------------------------------
    @Volatile private var lastRecognizeAt = 0L
    @Volatile private var lastCandidate: String? = null
    @Volatile var seeingName: String? = null
        private set
    @Volatile private var seeingAt = 0L
    private val greetedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Empreintes en cours d'apprentissage (null : pas d'apprentissage). */
    @Volatile private var enrolling: MutableList<FloatArray>? = null

    /** Qui il voit en ce moment (reconnu il y a moins de 3 s), ou null. */
    fun seeing(): String? = seeingName?.takeIf { System.currentTimeMillis() - seeingAt < 3000 }

    private fun recognize(bmp: android.graphics.Bitmap, face: com.joey.app.senses.SeenFace) {
        val now = System.currentTimeMillis()
        val box = face.box ?: return
        val learning = enrolling
        if (learning != null) {
            if (face.size >= 0.05f && now - lastRecognizeAt >= 250) {
                lastRecognizeAt = now
                faceId.embed(bmp, box)?.let { synchronized(learning) { learning.add(it) } }
            }
            return
        }
        if (people.names().isEmpty() || face.size < 0.04f || now - lastRecognizeAt < 700) return
        lastRecognizeAt = now
        val print = faceId.embed(bmp, box) ?: return
        val (name, _) = people.identify(print)
        // Deux reconnaissances de suite du même nom avant d'y croire.
        val prev = lastCandidate
        lastCandidate = name
        if (name == null || name != prev) return
        seeingName = name
        seeingAt = now
        if (_state.value.seeing != name) _state.update { it.copy(seeing = name) }
        // Quelqu'un qu'il n'a pas vu depuis 20 min : il le salue par son prénom (si on est en conversation).
        val l = live
        if (l != null && now - (greetedAt[name] ?: 0L) > 20 * 60_000L && greeted) {
            greetedAt[name] = now
            log("👤 Je reconnais $name")
            l.event("Tu reconnais $name devant toi (reconnaissance du visage). Salue-le ou salue-la par son prénom, " +
                "en une phrase courte, à ta façon.", reply = true)
        } else if (l == null) {
            greetedAt.putIfAbsent(name, 0L)
        }
    }

    /**
     * Apprend le visage de [name] : la personne doit être en face (le plus grand visage), ~5 s.
     * Bloquant. Renvoie un message.
     */
    fun learnFace(name: String): String {
        val n = name.trim()
        if (n.isEmpty()) return "il me faut un prénom"
        if (!flag("camera", true)) return "ma caméra est coupée"
        val list = ArrayList<FloatArray>()
        enrolling = list
        try {
            Thread.sleep(5000)
        } finally {
            enrolling = null
        }
        val prints = synchronized(list) { list.toList() }
        if (prints.size < 6) return "je n'ai pas assez bien vu ton visage (${prints.size} images) : mets-toi bien en face, assez près, et recommence"
        // Toutes les empreintes doivent se ressembler (sinon il y avait plusieurs personnes).
        val ref = prints[prints.size / 2]
        val coherent = prints.filter { p -> p.indices.sumOf { (p[it] * ref[it]).toDouble() } > 0.5 }
        if (coherent.size < prints.size * 0.7) return "j'ai vu plusieurs visages différents : qu'une personne à la fois, bien en face, et recommence"
        val other = people.identify(ref).first
        people.learn(n, coherent)
        refreshSettings()
        log("👤 Visage appris : $n (${coherent.size} empreintes)")
        greetedAt[n.replaceFirstChar { it.uppercase() }] = System.currentTimeMillis()
        return "visage de $n appris (${coherent.size} images)" +
            (if (other != null && !other.equals(n, true)) ". Attention : il ressemblait à celui de $other." else "")
    }

    fun learnFaceUi(name: String) = thread(isDaemon = true) {
        message("Regarde Joey bien en face, en tournant un peu la tête, pendant 5 secondes…")
        val r = learnFace(name)
        message(r.replaceFirstChar { it.uppercase() } + ".")
    }

    fun forgetFace(name: String) {
        people.forget(name)
        refreshSettings()
        log("👤 Visage oublié : $name")
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

    // ---- danse : la tête suit les temps forts de la musique (micro de la conversation) ------------
    @Volatile private var beats = 0
    private val beatDetector = com.joey.app.audio.BeatDetector()

    fun dance(seconds: Int) {
        setMood(Mood.CONTENT)
        live?.onMicPcm = { pcm, n -> beatDetector.feed(pcm, n) { beats++ } }
        moveAsync(holdMs = 1500) {
            head.setHomeHere()
            val end = System.currentTimeMillis() + seconds * 1000L
            var seen = beats
            var step = 0
            var lastMove = 0L
            try {
                while (System.currentTimeMillis() < end) {
                    val now = System.currentTimeMillis()
                    // Un temps entendu, ou à défaut (pas de musique) un rythme de 110 BPM.
                    val beat = beats != seen || now - lastMove > 545
                    if (beat && now - lastMove > 250) {
                        seen = beats
                        lastMove = now
                        step++
                        val pan = if ((step / 2) % 2 == 0) 14.0 else -14.0 // balancement tous les 2 temps
                        val tilt = if (step % 2 == 0) 8.0 else -6.0          // hochement à chaque temps
                        head.aim(pan, tilt)
                    } else {
                        head.aim(if ((step / 2) % 2 == 0) 14.0 else -14.0, if (step % 2 == 0) 8.0 else -6.0)
                    }
                    Thread.sleep(40)
                }
                head.aim(0.0, 0.0)
                Thread.sleep(800)
            } finally {
                live?.onMicPcm = null
            }
            "danse finie"
        }
    }

    fun danceUi() = dance(20)

    // ---- carte de la maison (Gemini Robotics-ER) -------------------------------------------------
    /**
     * Relève le lieu où Joey se trouve : tour du regard en 6 arrêts (60°), à chaque arrêt Robotics-ER pointe les éléments
     * et estime leur distance ; on les place au cap boussole. Bloquant (~30 s).
     */
    private fun mapHere(name: String): String {
        log("🗺 Je relève : $name")
        head.setHomeHere()
        val compass0 = sensors.compass()
        val (hfov, vfov) = fov()
        val items = ArrayList<com.joey.app.brain.HouseMap.Item>()
        val kinds = ArrayList<String>()
        val descs = ArrayList<String>()
        var errors = 0
        for (k in 0 until 6) {
            val target = k * 60.0
            val cur = head.pose()?.first ?: 0.0
            head.look(Head.wrap(target - cur), -5.0)
            Thread.sleep(700)
            val pose = head.pose() ?: (target to -5.0)
            // Cap absolu de cet arrêt : boussole du départ + rotation mesurée (la boussole bouge avec les moteurs).
            val stopHeading = compass0?.let { (it + pose.first + 360) % 360 }
            val jpeg = camera.zoomJpeg("tout") ?: continue
            val sv = runCatching { vision.survey(jpeg) }.getOrElse { errors++; log("🗺 vue ${k + 1} : ${it.message}"); null } ?: continue
            if (sv.room.isNotBlank()) kinds.add(sv.room.lowercase())
            if (sv.description.isNotBlank()) descs.add(sv.description)
            val now = System.currentTimeMillis()
            for ((nom, pt, dist) in sv.elements) {
                if (nom.isBlank()) continue
                val off = (pt.first * 2 - 1) * hfov / 2
                val elev = pose.second - (pt.second * 2 - 1) * vfov / 2
                items.add(com.joey.app.brain.HouseMap.Item(nom.trim(), stopHeading?.let { (it + off + 360) % 360 }, elev, dist, now))
            }
            log("🗺 vue ${k + 1}/6 : ${sv.elements.size} éléments")
        }
        // Retour au départ.
        head.look(Head.wrap(-(head.pose()?.first ?: 0.0)), 0.0)
        if (items.isEmpty()) return "relevé impossible ($errors erreurs de vision)"
        // Un même élément vu sur deux arrêts voisins : on n'en garde qu'un.
        val kept = ArrayList<com.joey.app.brain.HouseMap.Item>()
        for (it in items) {
            if (kept.none { k -> k.name.equals(it.name, true) && (k.heading == null || it.heading == null ||
                    kotlin.math.abs(Head.wrap(k.heading - it.heading)) < 25) }) kept.add(it)
        }
        val kind = kinds.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "pièce"
        houseMap.put(name, kind, descs.joinToString(" ").take(700), kept)
        currentPlace = houseMap.place(name)?.name ?: name
        log("🗺 $name relevé : ${kept.size} éléments ($kind)")
        return "lieu « $name » relevé ($kind) : ${kept.size} éléments, dont " + kept.take(12).joinToString(", ") { it.name } +
            (if (compass0 == null) ". Pas de boussole : les directions sont approximatives." else ".")
    }

    /** Où est [what] d'après la carte ; si c'est dans le lieu actuel, la tête se tourne vers lui. */
    private fun whereIs(what: String): String {
        val hits = houseMap.find(what)
        if (hits.isEmpty()) {
            val mem = memory.search(what, 3)
            return "pas sur la carte" + if (mem.isNotEmpty()) " ; dans ta mémoire : " + mem.joinToString(" ; ") { it.text } else ""
        }
        val fmtAgo = { t: Long -> val m = (System.currentTimeMillis() - t) / 60_000; if (m < 60) "il y a $m min" else if (m < 1440) "il y a ${m / 60} h" else "il y a ${m / 1440} j" }
        val lines = hits.map { (p, it) ->
            "${it.name} : ${p.name}" + (it.heading?.let { h -> ", vers le ${com.joey.app.brain.HouseMap.compassName(h)}" } ?: "") +
                (it.distanceM?.let { d -> ", à ~%.1f m".format(d) } ?: "") + ", vu ${fmtAgo(it.seenAt)}"
        }
        val (place, item) = hits.first()
        val now = sensors.compass()
        if (place.name == currentPlace && item.heading != null && now != null && gimbal.ready) {
            moveSync(holdMs = 8000) { head.look(Head.wrap(item.heading - now), item.elevation.coerceIn(-60.0, 60.0)) }
            live?.sendFrameNow()
            return lines.joinToString("\n") + "\n(ta tête est tournée vers « ${item.name} » : regarde l'image pour vérifier)"
        }
        return lines.joinToString("\n") + (if (place.name != currentPlace) "\n(ce n'est pas le lieu où tu es, d'après ce que tu sais)" else "")
    }

    fun mapPlaces(): List<com.joey.app.brain.HouseMap.Place> = houseMap.all()
    fun removePlace(name: String) { houseMap.remove(name); if (currentPlace == name) currentPlace = null }

    /** Photo avec compte à rebours sur l'écran (bloquant). */
    fun takePhotoBlocking(countdown: Int): String {
        log("📸 Photo dans $countdown s")
        for (i in countdown downTo 1) {
            _state.update { it.copy(countdown = i) }
            Thread.sleep(1000)
        }
        _state.update { it.copy(countdown = null, flashAt = System.currentTimeMillis()) }
        val done = java.util.concurrent.CountDownLatch(1)
        var r = "photo ratée"
        camera.takePhoto { msg -> r = msg; done.countDown() }
        done.await(8, TimeUnit.SECONDS)
        log("📸 $r")
        return r
    }

    fun photoUi() = thread(isDaemon = true) { takePhotoBlocking(3) }

    /** Apprentissage d'un visage : la tête reste en suivi du visage (pas de mouvement demandé). */
    private fun <T> busyLearn(body: () -> T): T {
        _state.update { it.copy(busy = true) }
        try { return body() } finally { _state.update { it.copy(busy = false) } }
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
    // ---- mot de réveil « Joey » ------------------------------------------------------------------
    @Volatile private var startedByWake = false
    @Volatile private var lastTalkAt = 0L
    private val wake: com.joey.app.audio.WakeWord = com.joey.app.audio.WakeWord(app,
        onWake = { heard ->
            log("👂 « $heard » : je t'écoute")
            if (live == null && geminiKey.isNotBlank()) {
                startedByWake = true
                thread(isDaemon = true) { Thread.sleep(350); startLive() } // le temps de rendre le micro
            }
        },
        log = ::log)

    /** Le mot de réveil écoute quand il n'y a pas de conversation (et que c'est activé). */
    fun updateWake() {
        val mic = androidx.core.content.ContextCompat.checkSelfPermission(getApplication(), android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (flag("wake_word", true) && live == null && mic && geminiKey.isNotBlank()) wake.start() else wake.stop()
    }

    fun micPressed() {
        if (live != null) {
            stopLive()
            return
        }
        startedByWake = false
        if (geminiKey.isBlank()) {
            message("Ajoute d'abord ta clé Gemini dans les réglages (⚙).")
            return
        }
        startLive()
    }

    private fun startLive() {
        wake.stop()
        lastTalkAt = System.currentTimeMillis()
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
        extractMemories()
        thread(isDaemon = true) { Thread.sleep(500); updateWake() }
        head.stop()
        _state.update { it.copy(live = LiveState.OFF, mood = Mood.NEUTRE) }
    }

    private fun onLiveState(s: LiveState) {
        _state.update { it.copy(live = s) }
        if (s == LiveState.LISTENING && !greeted) {
            greeted = true
            val who = seeing()
            who?.let { greetedAt[it] = System.currentTimeMillis() }
            live?.event((if (startedByWake) "On vient de t'appeler par ton nom (« Joey »). Réponds présent, brièvement. "
                else "La conversation commence (la personne vient d'appuyer sur ton bouton). ") +
                (if (who != null) "Tu reconnais $who (reconnaissance du visage). " else "") +
                "Salue en une phrase courte, à ta façon.", reply = true)
        }
        if (s == LiveState.SPEAKING) lastTalkAt = System.currentTimeMillis()
        if (s == LiveState.OFF) {
            live = null
            extractMemories()
            updateWake()
        }
    }

    // ---- souvenirs automatiques : Gemini relit la conversation et en tire les faits à retenir --------
    /** Tout ce qui a été dit depuis le dernier passage (personne / Joey). */
    private val transcript = ArrayList<Line>()
    @Volatile private var lastExtractAt = System.currentTimeMillis()
    @Volatile private var extracting = false

    private val MEMO_SCHEMA = org.json.JSONObject("""
        {"type":"object","properties":{"souvenirs":{"type":"array","items":{"type":"string"},
          "description":"faits durables à retenir, une phrase courte chacun, en français"}},"required":["souvenirs"]}
    """.trimIndent())

    private fun extractMemories() {
        val lines = synchronized(transcript) {
            if (transcript.size < 2 || extracting) return
            transcript.toList().also { transcript.clear() }
        }
        lastExtractAt = System.currentTimeMillis()
        extracting = true
        thread(name = "souvenirs", isDaemon = true) {
            try {
                val talk = lines.joinToString("\n") { (if (it.joey) "Joey : " else "Personne : ") + it.text.trim() }.takeLast(12_000)
                val known = memory.all().takeLast(150).joinToString("\n") { "- " + it.text }
                val today = java.text.SimpleDateFormat("EEEE d MMMM yyyy", Locale.FRENCH).format(Date())
                val prompt = "Tu es la mémoire de Joey, un petit robot de maison. Voici un extrait de conversation entre Joey et les " +
                    "gens de la maison (la transcription peut contenir des erreurs). Le plus souvent, c'est Michael (le " +
                    "propriétaire de Joey) qui parle, sauf si la conversation montre que c'est quelqu'un d'autre. " +
                    "Aujourd'hui : $today. Écris les dates relatives (« samedi », « demain ») en vraies dates.\n\n$talk\n\n" +
                    "Ce que Joey sait déjà :\n${known.ifEmpty { "(rien)" }}\n\n" +
                    "Extrais de 0 à 8 faits NOUVEAUX et DURABLES qui valent la peine d'être retenus pour les prochaines fois : " +
                    "prénoms et qui est qui, goûts, habitudes, animaux, où sont rangées les choses, projets, rendez-vous, promesses, " +
                    "ce qu'on a demandé à Joey de retenir. Une phrase courte et autonome chacun (avec les prénoms quand on les connaît). " +
                    "Pas de banalités (« on a discuté »), rien de ce qui est déjà connu, rien d'inventé. Liste vide si rien."
                val got = vision.json(prompt, MEMO_SCHEMA).optJSONArray("souvenirs")
                var n = 0
                if (got != null) for (i in 0 until got.length()) {
                    val t = got.optString(i).trim()
                    if (t.isNotEmpty() && memory.add(t, auto = true)) { n++; log("🧠 Souvenir : $t") }
                }
                if (n > 0) refreshSettings()
            } catch (e: Exception) {
                log("Souvenirs : extraction impossible (${e.message})")
                synchronized(transcript) { transcript.addAll(0, lines) } // on réessaiera
            } finally {
                extracting = false
            }
        }
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
        lastTalkAt = System.currentTimeMillis()
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
        // Transcription complète pour les souvenirs (morceaux recollés par interlocuteur).
        synchronized(transcript) {
            if (transcript.isNotEmpty() && transcript.last().joey == joey) {
                transcript[transcript.size - 1] = Line(joey, transcript.last().text + text)
            } else {
                transcript.add(Line(joey, text.trimStart()))
            }
            if (transcript.size > 400) transcript.removeAt(0)
        }
        // Toutes les 2 minutes de conversation, on met de côté ce qui compte (au cas où l'app serait fermée d'un coup).
        if (System.currentTimeMillis() - lastExtractAt > 120_000 && !extracting) extractMemories()
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
                val isNew = memory.add(note)
                refreshSettings()
                log("🧠 Retenu : $note")
                mapOf("result" to if (isNew) "retenu (sur le téléphone, pour toujours)" else "je le savais déjà")
            }
            "apprendre_visage" -> {
                val who = (args["prenom"] as? String)?.trim().orEmpty()
                log("👤 J'apprends le visage de $who…")
                val r = busyLearn { learnFace(who) }
                mapOf("result" to r)
            }
            "oublier_visage" -> {
                val who = (args["prenom"] as? String)?.trim().orEmpty()
                forgetFace(who)
                mapOf("result" to "visage de $who oublié")
            }
            "regarder_de_pres" -> {
                val q = args["question"] as? String ?: "que vois-tu ?"
                val zone = args["zone"] as? String ?: "tout"
                val jpeg = camera.zoomJpeg(zone) ?: return mapOf("result" to "pas d'image (caméra coupée ?)")
                log("🔍 de près ($zone) : $q")
                val r = runCatching { vision.ask(jpeg, q) }.getOrElse { "vision indisponible : ${it.message}" }
                mapOf("reponse" to r)
            }
            "prendre_photo" -> {
                if (!flag("camera", true)) return mapOf("result" to "ta caméra est coupée")
                val n = ((args["decompte"] as? Number)?.toInt() ?: 3).coerceIn(0, 10)
                mapOf("result" to takePhotoBlocking(n))
            }
            "danser" -> {
                if (!gimbal.ready) return mapOf("result" to noHead)
                val secs = ((args["secondes"] as? Number)?.toInt() ?: 20).coerceIn(5, 120)
                dance(secs)
                mapOf("result" to "tu danses ${secs} s (la tête suit le rythme entendu ; sans musique : rythme à toi)")
            }
            "mode_garde" -> {
                val on = args["actif"] != false
                setWatch(on)
                mapOf("result" to if (on) "mode garde activé : tu notes qui passe, tu surveilles les animaux, et tu alertes " +
                    "Michael sur son téléphone si besoin" else "mode garde arrêté")
            }
            "journal_de_garde" -> {
                val ev = watch.recent(15)
                val fmt = SimpleDateFormat("EEE d HH:mm", Locale.FRENCH)
                mapOf("journal" to if (ev.isEmpty()) "rien de noté" else ev.joinToString("\n") { fmt.format(Date(it.time)) + " — " + it.text })
            }
            "cartographier" -> {
                if (!gimbal.ready) return mapOf("result" to noHead)
                if (!flag("camera", true)) return mapOf("result" to "ta caméra est coupée")
                val lieu = (args["lieu"] as? String)?.trim().orEmpty().ifEmpty { "ici" }
                var r = ""
                moveSync(holdMs = 2000, timeoutMs = 150_000) { r = mapHere(lieu); r }
                live?.sendFrameNow()
                mapOf("result" to r)
            }
            "ou_est" -> {
                val what = args["objet"] as? String ?: ""
                mapOf("result" to whereIs(what))
            }
            "ou_suis_je" -> {
                if (houseMap.all().isEmpty()) return mapOf("result" to "carte vide : relève d'abord les pièces (cartographier)")
                val jpeg = camera.zoomJpeg("tout") ?: return mapOf("result" to "pas d'image")
                val (lieu, cert, why) = runCatching { vision.whichPlace(jpeg, houseMap.summary()) }
                    .getOrElse { return mapOf("result" to "vision indisponible : ${it.message}") }
                if (cert >= 0.5 && houseMap.place(lieu) != null) currentPlace = houseMap.place(lieu)!!.name
                mapOf("lieu" to lieu, "certitude" to cert, "raison" to why)
            }
            "carte" -> mapOf("carte" to houseMap.summary(), "lieu_actuel" to (currentPlace ?: "inconnu"))
            "se_souvenir" -> {
                val q = args["sujet"] as? String ?: ""
                val hits = memory.search(q)
                log("🧠 Je me souviens de : $q (${hits.size})")
                mapOf("souvenirs" to if (hits.isEmpty()) "aucun souvenir sur « $q »" else hits.joinToString("\n") { memory.dated(it) })
            }
            "oublier" -> {
                val q = args["sujet"] as? String ?: ""
                val gone = memory.forget(q)
                refreshSettings()
                log("🧠 Oublié : ${gone.joinToString(" / ")}")
                mapOf("result" to if (gone.isEmpty()) "rien à oublier sur « $q »" else "oublié : " + gone.joinToString(" ; "))
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
                    "personne_reconnue" to (seeing() ?: "personne de connu en vue"),
                    "gens_de_la_maison_connus" to people.names().ifEmpty { listOf("aucun") }.joinToString(),
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
        thread(name = "vie", isDaemon = true) { lifeLoop() }
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
            extra = { path ->
                when {
                    path.startsWith("/events") -> "application/json; charset=utf-8" to watch.json().toByteArray()
                    path.startsWith("/snap") -> path.substringAfter("id=", "").toLongOrNull()?.let { watch.photo(it) }?.let { "image/jpeg" to it }
                    else -> null
                }
            },
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
        when {
            b.startsWith("look_") -> {
                val d = b.removePrefix("look_")
                questFollowing = false
                moveAsync(holdMs = 6000) { head.lookDirection(d, if (d == "haut" || d == "bas") 20.0 else 30.0) }
                return
            }
            b == "photo" -> { thread(isDaemon = true) { takePhotoBlocking(3) }; return }
            b == "dance" -> { if (gimbal.ready) dance(20); return }
            b == "watch" -> { setWatch(!flag("watch", false)); return }
        }
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
        wake.stop()
        headset?.stop()
        sensors.stop()
        stopLive()
        gimbal.disconnect()
        camera.stop()
    }
}
