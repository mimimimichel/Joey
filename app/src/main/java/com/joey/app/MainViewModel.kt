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
    /** Roues (Roomba) : état affiché, ou null s'il n'est pas branché. */
    val roomba: String? = null,
    /** Mode nuit (écran assombri, il s'endort plus vite). */
    val night: Boolean = false,
    /** Personne depuis 5 min, rien à faire : écran baissé, caméra au ralenti (économie de batterie). */
    val idle: Boolean = false,
    val phoneBattery: Int = -1,
    /** Grand texte au milieu du visage (pierre-feuille-ciseaux, compte de cache-cache…). */
    val bigText: String? = null,
    val idleLife: Boolean = true,
    /** Vitesse de la tête, en % de la vitesse max mesurée. */
    val headSpeed: Int = 100,
    /** Mode test (pilote PC) : écran noir, pour ne pas marquer l'écran AMOLED pendant des heures d'essais. */
    val testScreen: Boolean = false,
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
    val voices: List<String> = emptyList(),
    val haUrl: String = "",
    val haToken: String = "",
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
    private val babbleTimer = Executors.newSingleThreadScheduledExecutor()

    /** Voix de Gemini : grave par défaut (celle d'origine est aiguë, ~240 Hz ; Charon ~130 Hz, Algenib ~105 Hz). */
    fun voiceName(): String = prefs.getString("voice_name", null) ?: "Charon"
    fun setVoice(v: String) {
        prefs.edit().putString("voice_name", v).apply()
        refreshSettings()
        // Nouvelle voix : la conversation redémarre (après sa phrase) pour la prendre.
        if (live != null) thread(isDaemon = true) { Thread.sleep(4500); if (live != null) { stopLive(); Thread.sleep(800); micPressed() } }
    }

    /** Tours de la tête par rapport au corps (le câble vers le Roomba ne doit pas s'entortiller). */
    private val cable = com.joey.app.gimbal.CableTwist()
    /** La carte en carreaux de 10 cm (où il est passé, ce qu'il a touché) : trajets et exploration. */
    private val grid = com.joey.app.robot.NavGrid()
    private val gridFile = File(app.filesDir, "carte_grille.json")
    /** Repère de la carte = sa base (gardé d'une fois sur l'autre) ; sinon carte de la séance seulement. */
    @Volatile private var gridAnchored = false
    /**
     * Le repère des positions de sa carte : « base » s'il est parti de sa base (valable d'une fois sur l'autre), sinon
     * celui de cette séance. Une pièce notée dans un autre repère n'a pas de position ici (journal du 23/10 : le « salon »
     * d'une autre séance l'envoyait au fond de la cuisine).
     */
    @Volatile private var gridFrame = "seance-" + System.currentTimeMillis()
    /** Pendant un trajet, la tête regarde la route (déclaré tôt : la boucle de la tête et les images s'en servent). */
    private val roadGaze = com.joey.app.gimbal.RoadGaze(
        bodyPan = { bodyPan() },
        pose = { head.pose() },
        aim = { p, t -> head.aim(p, t) },
        stopAim = { head.stopAim() },
        recenter = { calibrateBody() },
        refAgeMs = { bodyRef?.let { System.currentTimeMillis() - it.third } },
    )

    /** Ce qu'il apprend de ce qu'on lui dit : réglages ajustés, règles de conduite. */
    private val tuning = com.joey.app.life.Tuning(File(app.filesDir, "reglages.json"))
    private val people = com.joey.app.brain.People(File(app.filesDir, "personnes.json"))

    /** Code du foyer : celui des réglages, sinon celui de la compilation (le même sur tous les téléphones). */
    private fun foyerCode(): String {
        val saved = prefs.getString("foyer_code", null) ?: ""
        if (saved.isNotBlank()) return saved
        // Gardé sur le téléphone dès la première fois : les versions compilées ailleurs (le PC) le gardent.
        if (BuildConfig.JOEY_FOYER.isNotBlank()) prefs.edit().putString("foyer_code", BuildConfig.JOEY_FOYER).apply()
        return BuildConfig.JOEY_FOYER
    }

    /** Souvenirs et visages partagés entre les téléphones de la maison (Supabase, protégé par le code du foyer). */
    private val shared by lazy {
        com.joey.app.brain.SharedMemory(
            url = BuildConfig.SUPABASE_URL, key = BuildConfig.SUPABASE_KEY, foyer = ::foyerCode,
            device = (prefs.getString("device_id", null) ?: "?") + "-" + android.os.Build.MODEL,
            memory = memory, people = people,
            lastSync = { prefs.getString("shared_last_sync", "") ?: "" },
            setLastSync = { prefs.edit().putString("shared_last_sync", it).apply() },
            log = ::log,
        )
    }
    @Volatile private var syncWantedAt = 0L
    @Volatile private var lastSyncAt = 0L
    @Volatile var syncStatus = ""
        private set

    /** Partage maintenant (sur un fil à part). */
    fun syncShared(manual: Boolean = false) = thread(name = "partage", isDaemon = true) {
        if (cleared || android.os.Build.FINGERPRINT.contains("robolectric", ignoreCase = true)) return@thread
        if (!shared.configured) { syncStatus = "pas de code du foyer"; return@thread }
        lastSyncAt = System.currentTimeMillis(); syncWantedAt = 0
        val r = runCatching { shared.sync() }.getOrElse { "échec : ${it.message}" }
        syncStatus = r + " (" + timeFmt.format(Date()) + ")"
        if (manual || (r != "à jour" && !r.startsWith("échec"))) log("🔄 Mémoire partagée : $r")
        if (r.startsWith("échec")) journal.write("erreur", "mémoire partagée : $r")
        refreshSettings()
    }

    fun setFoyerCode(code: String) { prefs.edit().putString("foyer_code", code.trim()).putString("shared_last_sync", "").apply(); syncShared(true) }
    fun foyerCodeForUi(): String = foyerCode()
    private val faceId = com.joey.app.senses.FaceId(app)
    val voices = com.joey.app.brain.Voices(File(app.filesDir, "voix"))
    val home = com.joey.app.brain.HomeAssistant({ prefs.getString("ha_url", "") ?: "" }, { prefs.getString("ha_token", "") ?: "" })

    fun setHomeAssistant(url: String, token: String) {
        prefs.edit().putString("ha_url", url.trim()).putString("ha_token", token.trim()).apply()
        refreshSettings()
        if (url.isNotBlank() && token.isNotBlank()) thread(isDaemon = true) {
            val r = runCatching { "Home Assistant relié : ${home.entities().size} appareils" }.getOrElse { "Home Assistant : ${it.message}" }
            log("🏠 $r"); message(r)
        }
    }
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
            voices = voices.names(),
            haUrl = prefs.getString("ha_url", "") ?: "",
            haToken = prefs.getString("ha_token", "") ?: "",
            usesPhoneSensors = head.usesPhone,
        )
    }

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.FRENCH)
    /** Journal de bord sur le téléphone (exportable) : conversations, actions, mesures. */
    private val journal = com.joey.app.brain.Journal(File(app.filesDir, "journal"))
    /** Le journal part aussi dans Supabase (pour que Claude voie ce qui se passe), si activé. */
    /** Ce qu'il pense et fait, pour la tablette (déclaré avant le journal en ligne : il le remplit dès le démarrage). */
    private val tabletFeed = com.joey.app.remote.TabletFeed()

    private val upload = com.joey.app.remote.JournalUpload(
        url = BuildConfig.SUPABASE_URL, key = BuildConfig.SUPABASE_KEY,
        device = (prefs.getString("device_id", null) ?: java.util.UUID.randomUUID().toString().take(8).also {
            prefs.edit().putString("device_id", it).apply()
        }) + "-" + android.os.Build.MODEL,
        version = BuildConfig.VERSION_NAME,
        enabled = { flag("upload_journal", true) && !android.os.Build.FINGERPRINT.contains("robolectric", ignoreCase = true) },
    ).also { u ->
        journal.onLine = { at, kind, text -> u.add(at, kind, text); tabletFeed.add(at, kind, text) }
        u.start()
    }

    fun log(text: String) {
        journal.write("log", text)
        val line = "${timeFmt.format(Date())} $text"
        _state.update { it.copy(log = (it.log + line).takeLast(200)) }
    }

    fun message(text: String?) = _state.update { it.copy(message = text) }

    fun flagValue(name: String, def: Boolean) = flag(name, def)
    fun setFlagValue(name: String, v: Boolean) {
        setFlag(name, v)
        if (name == "roomba_reversed") roomba?.reversed = v
        refreshSettings()
    }

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

    // ---- voir l'obstacle avant de le toucher (la caméra regarde le sol devant) ------------------------------------
    private val floorVision = com.joey.app.senses.FloorVision()
    // Profondeur par réseau (MiDaS small) : voit ce que la couleur du sol ne voit pas (un pied de chaise sur un tapis).
    private val depth = com.joey.app.senses.DepthSense(app, ::log)
    private val depthGround = com.joey.app.senses.DepthGround()
    @Volatile private var depthClear = Double.NaN
    @Volatile private var depthAt = 0L

    /**
     * Pendant qu'il roule : la profondeur de la dernière image, 3 à 4 fois par seconde (sur un fil à part : 0,2 à 0,5 s par
     * image sur le S8+). Elle s'ajoute à la couleur du sol et au détecteur d'objets pour s'arrêter avant le choc, et trace la
     * carte. Coupable par le réglage « profondeur ».
     */
    private fun depthLoop() {
        var logged = false
        while (!cleared) {
            runCatching {
                val r = roomba
                val now = System.currentTimeMillis()
                val moving = r != null && (now - r.movingAt < 1500 || roadGaze.active)
                if (r == null || !moving || !flag("profondeur", true) || !depth.available) { Thread.sleep(200); return@runCatching }
                val shotAt = camera.frameAt
                val bmp = camera.frame(400) ?: run { Thread.sleep(100); return@runCatching }
                val (hf, vf) = camera.fov(bmp.width, bmp.height)
                val pitch = (if (gimbal.ready) head.pose()?.second else sensors.read()?.second) ?: run { Thread.sleep(150); return@runCatching }
                val pan = if (gimbal.ready) bodyPan() ?: 99.0 else 0.0
                // Comme pour la couleur du sol : la caméra doit regarder dans l'axe du corps, et voir le sol.
                if (kotlin.math.abs(pan) > 20 || -pitch + vf / 2 < 15) { depthClear = Double.NaN; Thread.sleep(150); return@runCatching }
                val heading = r.heading
                val inv = depth.estimate(bmp) ?: run { Thread.sleep(500); return@runCatching }
                val res = depthGround.analyse(inv, com.joey.app.senses.DepthSense.SIZE, com.joey.app.senses.DepthSense.SIZE, pitch, hf, vf)
                depthClear = if (res.ok) res.clearAhead else Double.NaN
                depthAt = shotAt
                if (!logged) { logged = true; log("🧠 Profondeur (MiDaS) : ${depth.lastMs} ms par image") }
                if (res.ok) nav?.vision(heading + (if (gimbal.ready) pan else 0.0), res.rays.map { it.bearing to it.distance })
                Thread.sleep(maxOf(20L, 250 - depth.lastMs))
            }.onFailure { Thread.sleep(1000) }
        }
    }

    /**
     * Un tour d'horizon cartographique (méthode des aspirateurs à lidar) : il tourne sur lui-même par pas de 30° et, à
     * chaque pas, la profondeur (MiDaS) donne la distance du premier obstacle dans 32 directions. L'ensemble est recalé
     * sur sa carte ([com.joey.app.robot.ScanMatcher] : sa position, qui dérive, est corrigée), puis ajouté à la carte. Sans
     * nacelle, le gyroscope du téléphone donne déjà le bon cap : le recalage ne touche l'angle que de quelques degrés.
     */
    private fun scanStation(steps: Int = 12): String {
        val r = roomba ?: return noWheels
        if (!depth.available) return "profondeur indisponible"
        val h0 = r.heading
        val views = ArrayList<Pair<Double, List<Pair<Double, Double>>>>()
        // Avec la nacelle (verrouillée : elle ne suit pas le corps), le corps reste immobile et c'est la tête qui fait le tour,
        // de -165° à +165° (le câble ne supporte pas plus) : le repère tête-corps, pris une fois par les pastilles, reste juste
        // puisque le corps ne bouge pas, et le gyroscope du téléphone mesure chaque angle de la tête.
        if (gimbal.ready) {
            if (!calibrateBody()) return "tour d'horizon impossible : je ne sais pas où regarde ma tête"
            head.look(-165.0, -22.0, timeoutMs = 6000)
        }
        for (k in 0 until steps) {
            if (cleared || roomba !== r) break
            if (gimbal.ready) {
                if (k > 0) head.look(330.0 / (steps - 1), -22.0, timeoutMs = 2500)
                Thread.sleep(250)
            } else {
                if (k > 0) { val res = r.turn(360.0 / steps); if (res.startsWith("arrêté") || res == "interrompu") return "tour d'horizon interrompu : $res" }
                Thread.sleep(350)
            }
            val shotAt = System.currentTimeMillis()
            var bmp: android.graphics.Bitmap? = null
            while (System.currentTimeMillis() - shotAt < 1500) { if (camera.frameAt > shotAt) { bmp = camera.frame(500); break }; Thread.sleep(30) }
            val img = bmp ?: continue
            val (hf, vf) = camera.fov(img.width, img.height)
            val pitch = (if (gimbal.ready) head.pose()?.second else sensors.read()?.second) ?: continue
            val inv = depth.estimate(img) ?: continue
            val res = depthGround.analyse(inv, com.joey.app.senses.DepthSense.SIZE, com.joey.app.senses.DepthSense.SIZE, pitch, hf, vf, columns = 32)
            if (!res.ok) continue
            val camRel = Head.wrap(r.heading - h0) + (if (gimbal.ready) bodyPan() ?: continue else 0.0)
            views.add(camRel to res.rays.map { it.bearing to it.distance })
        }
        if (gimbal.ready) head.look(-165.0, -22.0, timeoutMs = 6000) // la tête revient dans l'axe (le câble se détord)
        if (views.size < steps / 2) return "tour d'horizon raté (${views.size} images utiles sur $steps)"
        // Les bords vus, en vecteurs depuis sa position, dans le repère de la carte tel qu'il le croit.
        val pts = views.flatMap { (rel, rays) -> rays.filter { !it.second.isInfinite() && it.second < 3.0 }.map { (b, d) ->
            val a = Math.toRadians(h0 + rel + b); d * kotlin.math.cos(a) to d * kotlin.math.sin(a) } }
        var fixed = ""
        var dth = 0.0
        com.joey.app.robot.ScanMatcher.match(grid.occupiedKeys(), grid.cell, r.x, r.y, pts, maxTurnDeg = if (gimbal.ready) 20.0 else 4.0)?.let { f ->
            r.setPose(r.x + f.dx, r.y + f.dy, r.heading + f.dthDeg)
            dth = f.dthDeg
            fixed = ", recalé de %.0f cm et %.0f°".format(kotlin.math.hypot(f.dx, f.dy) * 100, f.dthDeg)
        }
        // Ajout à la carte depuis la pose corrigée (même cap de départ corrigé pour tous les pas).
        views.forEach { (rel, rays) -> nav?.vision(h0 + dth + rel, rays) }
        val msg = "tour d'horizon : ${views.size} images, ${pts.size} bords vus$fixed"
        log("🗺 ${msg.replaceFirstChar { it.uppercase() }}")
        return msg
    }

    /**
     * Cartographier la pièce (et au-delà) par tours d'horizon : un tour, puis la frontière la plus proche (le bord du
     * connu), un tour, et ainsi de suite, jusqu'à [maxSec] secondes ou plus de frontière (tout est connu).
     */
    private fun mapByScans(maxSec: Int): String = withRoadGaze {
        val n = nav ?: return@withRoadGaze noWheels
        val t0 = System.currentTimeMillis()
        val area0 = grid.knownArea()
        var stations = 0
        log("🗺 Je cartographie par tours d'horizon")
        scanStation().let { stations++; if (it.startsWith("tour d'horizon interrompu")) return@withRoadGaze it }
        var end = "temps écoulé"
        while (System.currentTimeMillis() - t0 < maxSec * 1000L && !cleared) {
            val res = n.frontierStep(budget = 3.0) { roadLegStart(it) }
            if (res == null) { end = "plus rien d'inconnu à portée"; break }
            if (res.startsWith("arrêté")) { end = res; break }
            val sc = scanStation(); stations++
            if (sc.startsWith("tour d'horizon interrompu")) { end = sc; break }
        }
        frontiersAt = 0L
        "carte : $stations tours d'horizon, %.0f m² de plus (%.0f m² connus) ; $end".format(grid.knownArea() - area0, grid.knownArea())
    }

    /** Place libre devant (m) : la plus prudente de la couleur du sol (et des objets) et de la profondeur, si elles sont récentes. */
    private fun clearAhead(maxAgeMs: Long = 400): Double? {
        val now = System.currentTimeMillis()
        val c = floorClear.takeIf { !it.isNaN() && now - floorAt < maxAgeMs }
        val d = depthClear.takeIf { !it.isNaN() && now - depthAt < maxAgeMs + 500 }
        return when { c == null -> d; d == null -> c; else -> minOf(c, d) }
    }
    @Volatile private var floorClear = Double.NaN
    @Volatile private var floorAt = 0L
    private var floorFrameAt = 0L

    private fun floorFrame(bmp: android.graphics.Bitmap) {
        val r = roomba ?: return
        val now = System.currentTimeMillis()
        if (now - floorFrameAt < 150) return
        floorFrameAt = now
        // La caméra doit regarder dans l'axe du corps et voir le sol (le bas de l'image sous l'horizon).
        val (hf, vf) = camera.fov(bmp.width, bmp.height)
        val pitch = (if (gimbal.ready) head.pose()?.second else sensors.read()?.second) ?: run { floorClear = Double.NaN; return }
        val pan = if (gimbal.ready) bodyPan() ?: 99.0 else 0.0
        if (kotlin.math.abs(pan) > 20 || -pitch + vf / 2 < 15) { floorClear = Double.NaN; return }
        val (w, h) = if (bmp.width >= bmp.height) 64 to 48 else 48 to 64
        val small = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        // Le sol s'apprend en roulant sans rien toucher (ce qu'il y avait devant était bien du sol).
        val movingFree = now - r.movingAt < 300 && !r.obstacleNear && r.sensors?.bump != true && !r.stuck
        if (movingFree || !floorVision.ready) floorVision.learnFloor(px, w, h, if (floorVision.ready) 0.08 else 1.0)
        val res = floorVision.analyse(px, w, h, pitch, hf, vf)
        // Pendant qu'il roule : les meubles et animaux reconnus comptent aussi (24/10 : l'apparence du sol ne voyait pas
        // un pied de chaise en bois clair sur le tapis crème ; il l'a heurté à 0,4 m/s).
        val objC = obstacleObjects(bmp, r, pitch, hf, vf, now)
        var c = if (res.floorVisible) res.clearAhead else Double.NaN
        if (!objC.isNaN() && !objC.isInfinite()) c = if (c.isNaN()) objC else minOf(c, objC)
        floorClear = c
        floorAt = now
        if (!res.floorVisible) return
        // Sur la carte : le sol vu est libre, son bord est un obstacle (à confirmer).
        nav?.vision(r.heading + (if (gimbal.ready) pan else 0.0), res.rays.map { it.bearing to it.distance })
    }

    @Volatile private var objClear = Double.NaN
    private var objAt = 0L
    private var objLogAt = 0L

    /** Place libre devant d'après le détecteur d'objets (m), seulement en roulant (~3 fois/s) ; NaN = pas d'avis. */
    private fun obstacleObjects(bmp: android.graphics.Bitmap, r: com.joey.app.robot.RoombaRobot, pitch: Double, hf: Double, vf: Double, now: Long): Double {
        if (now - r.movingAt > 1500 && !roadGaze.active) return Double.NaN
        if (now - objAt >= 300) {
            objAt = now
            val seen = objects.detect(bmp).filter { it.label in com.joey.app.senses.FloorVision.OBSTACLE_LABELS && it.score >= 0.4 }
            objClear = floorVision.objectClear(seen.map { it.box }, pitch, hf, vf)
            if (!objClear.isInfinite() && objClear < 1.2 && now - objLogAt > 3000) {
                objLogAt = now
                val what = seen.maxByOrNull { it.box.cy + it.box.h / 2 }?.label?.let { CocoClasses.french(it) } ?: "un obstacle"
                log("👁️ $what devant (%.0f cm)".format(objClear * 100))
            }
        }
        return if (now - objAt < 700) objClear else Double.NaN
    }

    private fun onFrame(bmp: android.graphics.Bitmap) {
        runCatching { floorFrame(bmp) }
        // Où regardait la tête quand l'image a été prise (un peu avant : l'image a ~80 ms de retard), SANS les petits
        // mouvements expressifs (sinon ils étaient comptés deux fois : la tête oscillait de ±25° en suivant un visage).
        val shot = poseAt(System.currentTimeMillis() - 80)
        val framePose = shot?.let { Pair(Head.wrap(it.first - it.third.pan), it.second - it.third.tilt) } ?: head.pose()
        val frameAbs = head.absPan()?.let { a -> shot?.let { Head.wrap(a - (head.pose()?.first ?: 0.0) + it.first - it.third.pan) } ?: a }
        val labels = trackLabels
        if (labels != null && System.currentTimeMillis() < trackUntil) {
            val best = objects.detect(bmp).filter { it.label in labels && it.score >= (if (it.label == "person") 0.55 else 0.5) }
                .maxByOrNull { it.score * kotlin.math.sqrt(it.box.w * it.box.h) }
            targetStreak = if (best != null) targetStreak + 1 else 0
            val sure = best?.takeIf { targetStreak >= 2 }
            // Une personne : on vise le haut de la silhouette (la tête), pour que le visage apparaisse.
            val y = sure?.let { if (it.label == "person") it.box.cy - it.box.h * 0.3 else it.box.cy }
            val sx = sure?.let { (it.box.cx * 2 - 1).toFloat() }
            val sy = y?.let { (it * 2 - 1).toFloat() }
            follower.onFace(sx, sy)
            angleTrack(sx, sy, framePose, frameAbs, bmp.width, bmp.height, person = "person" in labels)
        } else {
            if (labels != null) {
                trackLabels = null
                follower.forget()
                _state.update { it.copy(tracking = null) }
            }
            // Un « visage » n'est cru que s'il est assez grand, ou si le détecteur voit aussi une personne autour
            // (sinon un motif du sol ou du mur peut passer pour un visage, et Joey croit te voir).
            // Plusieurs visages : celui qu'on lui a demandé, celui qui parle, ou celui qu'il regardait déjà.
            val all = faces.findAll(bmp).filter { it.size >= 0.025f && (it.size >= 0.09f || personAround(bmp, it)) }
            val f = chooser.choose(all, speaking = System.currentTimeMillis() - lastPersonTalkAt < 1500)
            if (all.size > 1 || chooser.count > 1) nameFaces(bmp, frameAbs, framePose)
            faceStreak = if (f != null) faceStreak + 1 else 0
            val sure = f?.takeIf { faceStreak >= 2 }
            follower.onFace(sure?.x, sure?.y)
            if (sure != null) { targetFaceX = sure.x; targetFaceY = sure.y; targetFaceAt = System.currentTimeMillis() }
            // Elle parle et on voit son visage : les micros se règlent (au centre : équilibre ; sur un côté : sens).
            if (sure != null && System.currentTimeMillis() - lastPersonTalkAt < 800 && (chooser.count <= 1 || chooser.targetTalking())) stereoDir.learn(sure.x)
            runCatching { faceSocial(sure) }
            // Elle bouge les mains : un coup d'œil à ses mains (en gardant le visage dans l'image si possible).
            val handAim = handAttention.aimPoint(sure?.x, sure?.y)
            if (handAim != null) angleTrack(handAim.first, handAim.second, framePose, frameAbs, bmp.width, bmp.height, person = false)
            else angleTrack(sure?.x, sure?.y, framePose, frameAbs, bmp.width, bmp.height, person = true)
            if (sure != null) recognize(bmp, sure)
            if (sure != null || rpsWaiting || handAttention.active) readBody(bmp)
        }
        val sees = follower.sees
        if (sees != _state.value.seesFace) _state.update { it.copy(seesFace = sees) }
        detectMotion(bmp)
        if (flag("watch", false)) guard(bmp)
    }

    // ---- plusieurs personnes : qui regarder -------------------------------------------------------
    private val chooser = com.joey.app.senses.FaceChooser()
    @Volatile private var lastPersonTalkAt = 0L
    private val enrichedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Direction de la voix avec les deux micros, réglée toute seule en t'écoutant (gardée sur le téléphone). */
    private val stereoDir = com.joey.app.audio.StereoDirection(
        balanceDb = prefs.getFloat("mic_balance_db", 0f).toDouble(),
        lagBias = prefs.getFloat("mic_lag_bias", 0f).toDouble(),
        sideSign = prefs.getInt("stereo_side_sign", 1),
        save = { b, l, sgn, n ->
            prefs.edit().putFloat("mic_balance_db", b.toFloat()).putFloat("mic_lag_bias", l.toFloat()).putInt("stereo_side_sign", sgn).apply()
            log("🎙️ Micros réglés : écart %.1f dB, sens %d (%d mesures)".format(b, sgn, n))
        },
    )
    @Volatile private var lastNameAt = 0L
    /** Où il a vu chaque personne reconnue en dernier : cap absolu, inclinaison, heure. */
    private val seenByName = java.util.concurrent.ConcurrentHashMap<String, Triple<Double, Double, Long>>()

    /** Met un prénom sur les visages visibles, un à la fois (toutes les 0,4 s), pour « regarde Johanna ». */
    private fun nameFaces(bmp: android.graphics.Bitmap, abs: Double?, pose: Pair<Double, Double>?) {
        val now = System.currentTimeMillis()
        if (people.names().isEmpty() || enrolling != null || now - lastNameAt < 400) return
        val f = chooser.needsName() ?: return
        lastNameAt = now
        val box = f.box ?: return
        if (f.size < 0.035f) return
        val print = faceId.embed(bmp, box) ?: return
        val name = people.identify(print).first
        chooser.setName(f, name)
        if (name != null && abs != null && pose != null) {
            val (dp, dt) = imageToDegrees(f.x, f.y, bmp.width, bmp.height)
            seenByName[name] = Triple(Head.wrap(abs + dp), (pose.second + dt).coerceIn(-60.0, 60.0), now)
        }
    }

    /**
     * « Regarde Johanna » : la tête se tourne vers elle et la garde en face. Si elle est dans l'image, le suivi s'en
     * charge ; sinon il regarde là où il l'a vue en dernier. Prénom inconnu et deux personnes : « l'autre ».
     */
    private fun lookAtPerson(who: String): String {
        val w = who.trim().ifEmpty { "autre" }
        val lw = w.lowercase()
        val isSide = lw in setOf("gauche", "droite", "autre", "l'autre", "à gauche", "à droite")
        val known = people.names().any { it.equals(w, ignoreCase = true) }
        val target = if (!isSide && !known) "autre" else w
        chooser.want(target)
        follower.pausedUntil = 0
        Thread.sleep(900)
        if (chooser.targetName()?.equals(w, ignoreCase = true) == true || (target != w && chooser.count > 1)) {
            return if (target != w) "tu regardes l'autre personne (tu ne connais pas encore le visage de $w)" else "tu regardes $w en face"
        }
        if (isSide) return if (chooser.count > 1) "tu regardes la personne $lw" else "tu ne vois qu'une personne"
        // Pas dans l'image : là où il l'a vue en dernier (moins de 30 min).
        val last = seenByName.entries.firstOrNull { it.key.equals(w, ignoreCase = true) }?.value
        // Quelqu'un est visible mais pas reconnu : c'est peut-être elle, on ne part pas ailleurs (« tu tournes trop »).
        if (chooser.count > 0) return "tu regardes la personne devant toi (son visage n'est pas reconnu avec certitude)"
        if (last != null && System.currentTimeMillis() - last.third < 5 * 60_000L) {
            val cur = head.absPan() ?: return "je ne sais pas où regarde ma tête"
            head.look(Head.wrap(last.first - cur), last.second)
            chooser.want(w)
            follower.pausedUntil = 0
            Thread.sleep(1000)
            if (chooser.targetName()?.equals(w, ignoreCase = true) == true) return "tu regardes $w en face"
            return "$w n'est plus là où tu l'as vue en dernier"
        }
        return if (known) "tu ne vois pas $w (cherche-la avec « chercher », ou demande-lui de se montrer)"
        else "tu ne connais pas encore le visage de $w et tu ne vois qu'une personne : demande-lui de se mettre devant toi " +
            "(tu peux apprendre son visage avec « apprendre_visage »)"
    }

    // ---- export du journal -----------------------------------------------------------------------
    /** Prépare le journal des 3 derniers jours (avec l'état de Joey en tête) pour le partager. Bloquant. */
    fun exportJournal(): File {
        flushTurn()
        val ctx = getApplication<Application>()
        val safeFlags = prefs.all.filter { (k, v) -> v is Boolean || k in setOf("head_speed_pct", "speed_table", "phone_pan_sign",
            "phone_tilt_sign", "follow_pan_sign", "follow_tilt_sign", "mic_side_sign") }
        val header = buildString {
            appendLine("Journal de Joey ${BuildConfig.VERSION_NAME} — exporté le ${Date()}")
            appendLine("Téléphone : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}")
            appendLine("Nacelle : ${_state.value.gimbal} ; capteurs du téléphone pour la tête : ${head.usesPhone} ; vitesse max mesurée : %.0f°/s".format(head.bestSpeed))
            appendLine("Caméra : ${camera.cameraChoice}")
            appendLine(stereoDir.describe())
            appendLine("Roomba : ${if (roomba != null) "branché" else "non"} ; personnes connues : ${people.names().size} ; souvenirs : ${memory.all().size}")
            appendLine("Humeur : ${temperament.describe()}")
            appendLine("Réglages : $safeFlags")
            CrashReport.lastSteps(ctx)?.let { appendLine("Dernières étapes : $it") }
        }
        val out = File(ctx.cacheDir, "export/joey_journal_${java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.FRENCH).format(Date())}.txt")
        File(ctx.cacheDir, "export").listFiles()?.forEach { it.delete() }
        return journal.export(out, days = 3, header = header)
    }

    /**
     * Enregistre le journal dans les Téléchargements du téléphone. Renvoie le nom du fichier, ou un message d'erreur.
     * Bloquant.
     */
    fun saveJournalToDownloads(): String {
        val f = exportJournal()
        val ctx = getApplication<Application>()
        return runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, f.name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Téléchargements inaccessibles")
                ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                values.clear(); values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                ctx.contentResolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                f.copyTo(File(dir, f.name), overwrite = true)
            }
            log("📥 Journal enregistré dans Téléchargements : ${f.name}")
            "Enregistré dans Téléchargements : ${f.name}"
        }.getOrElse { "Impossible d'enregistrer : ${it.message}" }
    }

    // ---- vie : humeur, langage du corps, réactions, initiatives ------------------------------------
    private val temperament = com.joey.app.life.Temperament()
    private val expressive = com.joey.app.life.Expressive()
    @Volatile private var exprPose = com.joey.app.life.Expressive.Pose(0.0, 0.0, 0f)
    @Volatile private var exprMood = Mood.NEUTRE
    @Volatile private var exprMoodSince = 0L
    @Volatile private var thinkingNow = false

    /** Il réfléchit (on attend sa réponse, il cherche, il calcule) : ça se voit sur son visage. */
    fun isThinking(): Boolean = thinkingNow
    @Volatile private var lastJoeyTalkAt = 0L
    @Volatile private var lastTrackAimAt = 0L
    @Volatile private var idleBase: Pair<Double, Double>? = null
    // Bien moins sensible qu'avant : seul un vrai bruit fort et très bref compte (porte qui claque, verre qui tombe).
    private val liveStartle = com.joey.app.life.Startle(jump = 28.0, floor = -16.0, maxMs = 250)
    @Volatile private var lastStartleAt = 0L

    /** L'expression du moment revient à celle-ci (fatigué tard le soir). */
    private fun baseMood(): Mood = if (temperament.energy() < 0.3) Mood.FATIGUE else Mood.NEUTRE

    /**
     * Personne à suivre, rien à faire : la tête tient sa pose (et respire, hoche…) au lieu de rester figée.
     * Tout mouvement demandé, le suivi ou le casque reprennent la main.
     */
    private fun idlePose(now: Long, s: UiState) {
        val free = gimbal.ready && !s.busy && now >= follower.pausedUntil && !dancing && !questFollowing && !calibrating &&
            trackLabels == null && now - lastTrackAimAt > 1500 && follower.enabled
        if (!free) { idleBase = null; return }
        // La pose de repos, SANS les petits mouvements expressifs du moment (sinon, à chaque reprise, il les ajoutait
        // à la pose et la tête montait petit à petit vers le plafond).
        val base = idleBase ?: head.pose()?.let { p ->
            Pair(Head.wrap(p.first - exprPose.pan), (p.second - exprPose.tilt).coerceIn(-35.0, 35.0))
        }?.also { idleBase = it } ?: return
        head.aim(base.first, base.second)
    }

    // Visage de la personne regardée : sourire, regard ailleurs, arrivée.
    private var smileFrames = 0
    @Volatile private var lastMimicAt = 0L
    @Volatile private var turnedSince = 0L
    @Volatile private var lastGazeFollowAt = 0L
    @Volatile private var lastFaceSeenAt = 0L

    private fun faceSocial(f: com.joey.app.senses.SeenFace?) {
        val now = System.currentTimeMillis()
        if (f == null) { smileFrames = 0; turnedSince = 0; return }
        temperament.sawPerson()
        // Arrivée : personne vu depuis 20 min -> il salue (initiative).
        if (lastFaceSeenAt > 0 && now - lastFaceSeenAt > 20 * 60_000L) arrival(now - lastFaceSeenAt)
        lastFaceSeenAt = now
        // Il sourit quand on lui sourit (sans musique, c'est discret).
        smileFrames = if ((f.smile ?: 0f) > 0.75f) smileFrames + 1 else 0
        if (smileFrames >= 4 && now - lastMimicAt > 12_000 && _state.value.mood in setOf(Mood.NEUTRE, Mood.FATIGUE)) {
            lastMimicAt = now
            temperament.feel(0.03)
            setMood(Mood.CONTENT, sound = false)
            if (Math.random() < 0.3) roombaGesture(RoombaGesture.HAPPY)
        }
        // Elle regarde ailleurs (tête tournée) sans parler : il regarde aussi (attention partagée).
        if (kotlin.math.abs(f.yaw) > 30) { if (turnedSince == 0L) turnedSince = now } else turnedSince = 0
        if (turnedSince != 0L && now - turnedSince > 1500 && now - lastPersonTalkAt > 2000 && now - lastGazeFollowAt > 25_000 &&
            !_state.value.busy && gimbal.ready && geminiKey.isNotBlank() && flag("auto_vision", false) && vision.available() &&
            !handAttention.active) {
            lastGazeFollowAt = now
            turnedSince = 0
            moveAsync(holdMs = 0) { followGaze() }
        }
    }

    /** Il regarde ce que la personne regarde, un instant, puis revient vers elle. Fil des mouvements. */
    private fun followGaze(): String {
        val jpeg = camera.zoomJpeg("tout") ?: return "pas d'image"
        val pt = runCatching { vision.gazeAt(jpeg) }.getOrNull() ?: return "vision indisponible"
        if (!pt.pointing) return "elle ne regarde rien de précis"
        log("👀 Tu regardes ${pt.target} : je regarde aussi")
        turnToPointing(pt)
        live?.sendFrameNow()
        Thread.sleep(2500)
        lookAtLastSeen()
        return "j'ai regardé ce que tu regardais (${pt.target})"
    }

    /** Bruit fort et soudain : il sursaute (yeux ronds, petit recul de la tête et du corps) et s'en étonne. */
    private fun startled() {
        val now = System.currentTimeMillis()
        if (now - lastStartleAt < 8000 || _state.value.mood == Mood.DORT && Math.random() < 0.5) return
        lastStartleAt = now
        log("😲 Un bruit !")
        setMood(Mood.ETONNE, sound = false)
        roombaGesture(RoombaGesture.SURPRISE)
        if (gimbal.ready && !_state.value.busy) moveAsync(holdMs = 1500) {
            val p = head.pose()
            if (p != null) { head.look(null, (p.second + 9).coerceAtMost(60.0), timeoutMs = 600); head.look(null, p.second, timeoutMs = 800) }
            "sursaut"
        }
        // Il sursaute (visage, tête, roues) sans en parler.
    }

    /** On le soulève, on le repose, on le pousse, il tombe. */
    private fun bodyMoved(e: com.joey.app.life.BodyMotion.Event) {
        val r = roomba
        // Ses propres roues : pas une surprise.
        // (pendant qu'il roule, les à-coups ressemblaient à « on me secoue / on me soulève » : seule la chute compte)
        if (wheelsJustMoved() && e != com.joey.app.life.BodyMotion.Event.FALLING) return
        // Déplacé à la main : ses roues ne l'ont pas senti, l'écart entre la tête et le corps n'est plus sûr (24/10 : il
        // croyait sa tête tournée de 92° alors qu'elle regardait droit devant, et coupait sa vision du sol). Il le reprendra
        // au prochain recentrage.
        bodyRef = null
        val (mood, text) = when (e) {
            com.joey.app.life.BodyMotion.Event.LIFTED -> Mood.ETONNE to "On te soulève ! Réagis tout de suite, en quelques mots (surpris, un peu inquiet ou amusé)."
            com.joey.app.life.BodyMotion.Event.PUT_DOWN -> Mood.CONTENT to "On vient de te reposer : réagis en quelques mots (soulagé)."
            com.joey.app.life.BodyMotion.Event.PUSHED -> Mood.MEFIANT to "On te pousse ou on te déplace (tes roues ne tournaient pas) : réagis en quelques mots."
            com.joey.app.life.BodyMotion.Event.FALLING -> Mood.ETOURDI to "Tu tombes ou on te lâche ! Réagis tout de suite (« Aaah ! »)."
        }
        log(when (e) {
            com.joey.app.life.BodyMotion.Event.LIFTED -> "🫴 On me soulève"
            com.joey.app.life.BodyMotion.Event.PUT_DOWN -> "⬇️ On me repose"
            com.joey.app.life.BodyMotion.Event.PUSHED -> "↔️ On me pousse"
            com.joey.app.life.BodyMotion.Event.FALLING -> "🪂 Je tombe"
        })
        if (e == com.joey.app.life.BodyMotion.Event.LIFTED || e == com.joey.app.life.BodyMotion.Event.FALLING) r?.stop()
        temperament.feel(if (e == com.joey.app.life.BodyMotion.Event.PUT_DOWN) 0.02 else -0.03)
        setMood(mood, sound = e != com.joey.app.life.BodyMotion.Event.PUT_DOWN)
        // Il ne parle que pour ce qui compte vraiment (soulevé, chute) ; le reste se voit sur son visage.
        val big = e == com.joey.app.life.BodyMotion.Event.LIFTED || e == com.joey.app.life.BodyMotion.Event.FALLING
        if (flag("react_motion", true) && big) remark(text, minGapMs = 20_000)
    }

    // ---- le Roomba exprime aussi -------------------------------------------------------------------
    enum class RoombaGesture { SURPRISE, HAPPY, ATTENTION }
    @Volatile private var roombaGestureAt = 0L
    @Volatile private var roombaMovedAt = 0L

    /** Petit recul (surpris), tortillement (content), petit pas en avant (il veut ton attention). */
    private fun roombaGesture(g: RoombaGesture) {
        val r = roomba ?: return
        val now = System.currentTimeMillis()
        if (!flag("roomba_expressive", true) || following || _state.value.busy || r.onBase || now - roombaGestureAt < 20_000) return
        roombaGestureAt = now
        roombaMovedAt = now
        thread(name = "roomba-geste", isDaemon = true) {
            runCatching {
                when (g) {
                    RoombaGesture.SURPRISE -> { r.velocity(-0.15, 0.0, 300); Thread.sleep(350) }
                    RoombaGesture.HAPPY -> repeat(2) {
                        r.velocity(0.0, 70.0, 220); Thread.sleep(250)
                        r.velocity(0.0, -70.0, 220); Thread.sleep(250)
                    }
                    RoombaGesture.ATTENTION -> { r.velocity(0.12, 0.0, 600); Thread.sleep(650) }
                }
                r.velocity(0.0, 0.0, 100)
                roombaMovedAt = System.currentTimeMillis()
            }
        }
    }

    // ---- initiatives : il salue, il remarque ce qui a changé ---------------------------------------
    @Volatile private var lastArrivalAt = 0L
    @Volatile private var lastNoveltyCheck = 0L
    @Volatile private var lastNoveltyRemark = 0L
    private val viewsDir by lazy { File(getApplication<Application>().filesDir, "vues").apply { mkdirs() } }

    /** Quelqu'un arrive (personne vu depuis longtemps) : il le salue, de lui-même. */
    private fun arrival(absentMs: Long) {
        val now = System.currentTimeMillis()
        if (!flag("initiatives", true) || flag("watch", false) || _state.value.night || now - lastArrivalAt < 20 * 60_000L) return
        lastArrivalAt = now
        roombaGesture(RoombaGesture.ATTENTION)
        thread(isDaemon = true) {
            Thread.sleep(2500) // le temps de le reconnaître
            val who = seeing()
            val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRENCH).format(java.util.Date())
            log("👋 ${who ?: "Quelqu'un"} arrive")
            speakUp("[Initiative] ${who ?: "Quelqu'un"} vient d'arriver devant toi (tu ne l'avais pas vu depuis " +
                "${absentMs / 60_000} min). Il est $hm. Ton humeur : ${temperament.describe()}. Salue en une phrase, à ta " +
                "façon ; si tu connais une habitude de cette personne à cette heure-ci (dans tes souvenirs), tu peux en parler.")
        }
    }

    /**
     * Il remarque ce qui a changé : il garde une vue de chaque direction (boussole), et de temps en temps, quand
     * quelqu'un est là et que la conversation est calme, il compare (Gemini) avec la vue de maintenant.
     */
    private fun initiatives(now: Long, s: UiState) {
        if (!flag("initiatives", true) || s.busy || s.night || flag("watch", false) || now - lastNoveltyCheck < 10 * 60_000L) return
        if (!flag("camera", true) || geminiKey.isBlank() || head.moving || !flag("auto_vision", false) || !vision.available()) return
        val heading = sensors.compass() ?: return
        val sector = (((heading + 22.5) % 360) / 45).toInt()
        val file = File(viewsDir, "vue_$sector.jpg")
        val ref = file.takeIf { it.exists() }
        val calm = live == null || now - lastTalkAt > 20_000
        val someone = follower.sees
        // Pas de vue de référence (ou très vieille) : il en prend une, tranquillement.
        if (ref == null || now - ref.lastModified() > 3 * 24 * 3600_000L) {
            lastNoveltyCheck = now
            camera.zoomJpeg("tout")?.let { runCatching { file.writeBytes(android.util.Base64.decode(it, android.util.Base64.NO_WRAP)) } }
            return
        }
        if (!someone || !calm || now - lastNoveltyRemark < 2 * 3600_000L || now - ref.lastModified() < 2 * 3600_000L) return
        lastNoveltyCheck = now
        val after = camera.zoomJpeg("tout") ?: return
        val before = android.util.Base64.encodeToString(ref.readBytes(), android.util.Base64.NO_WRAP)
        thread(isDaemon = true) {
            val change = runCatching { vision.compare(before, after) }.getOrNull()
            runCatching { file.writeBytes(android.util.Base64.decode(after, android.util.Base64.NO_WRAP)) }
            if (change != null) {
                lastNoveltyRemark = System.currentTimeMillis()
                log("🧐 Ça a changé : $change")
                remark("[Initiative] Tu remarques un changement dans la pièce depuis la dernière fois : $change. Fais-en une " +
                    "remarque curieuse ou amusée, en une phrase (pose une question si tu veux).")
            }
        }
    }

    // ---- viser en degrés -------------------------------------------------------------------------
    @Volatile private var personBoxes: List<com.joey.app.senses.Box> = emptyList()
    @Volatile private var personCheckAt = 0L
    @Volatile private var aimTarget: Pair<Double, Double>? = null
    @Volatile private var aimTargetAt = 0L
    @Volatile private var lastSeenTilt = 0.0

    /** Le détecteur d'objets voit-il une personne autour de ce visage ? (vérifié au plus toutes les 0,6 s) */
    private fun personAround(bmp: android.graphics.Bitmap, f: com.joey.app.senses.SeenFace): Boolean {
        val now = System.currentTimeMillis()
        if (now - personCheckAt > 600) {
            personCheckAt = now
            personBoxes = objects.detect(bmp).filter { it.label == "person" && it.score >= 0.4 }.map { it.box }
        }
        val fx = (f.x + 1) / 2.0
        val fy = (f.y + 1) / 2.0
        return personBoxes.any { b ->
            fx in (b.cx - b.w / 2 - 0.05)..(b.cx + b.w / 2 + 0.05) && fy in (b.cy - b.h / 2 - 0.05)..(b.cy + b.h / 2 + 0.05)
        }
    }

    /** Écart en degrés (droite+, haut+) d'un point de l'image (-1..1, y vers le bas), d'après le champ de la caméra. */
    private fun imageToDegrees(x: Float, y: Float, w: Int, h: Int): Pair<Double, Double> {
        val (hf, vf) = camera.fov(w, h)
        val dPan = Math.toDegrees(kotlin.math.atan(x * kotlin.math.tan(Math.toRadians(hf / 2))))
        val dTilt = -Math.toDegrees(kotlin.math.atan(y * kotlin.math.tan(Math.toRadians(vf / 2))))
        return dPan to dTilt
    }

    /**
     * La tête vise la cible en degrés : là où elle regardait au moment de l'image + l'écart de la cible dans l'image.
     * Pas de sens à deviner : les capteurs disent où va la tête. Cible perdue : il finit d'aller là où il l'a vue.
     */
    private fun angleTrack(x: Float?, y: Float?, pose: Pair<Double, Double>?, abs: Double?, w: Int, h: Int, person: Boolean) {
        follower.observeOnly = pose != null && !calibrating
        if (pose == null) return
        val now = System.currentTimeMillis()
        if (x != null && y != null) {
            val (dPan, dTilt) = imageToDegrees(x, y, w, h)
            if (person && abs != null) {
                lastSeenPan = Head.wrap(abs + dPan); lastSeenTilt = (pose.second + dTilt).coerceIn(-60.0, 60.0)
                lastSeenAt = now
            }
            if (!follower.enabled || !gimbal.ready || now < follower.pausedUntil || calibrating || roadGaze.active) {
                aimTarget = null; return
            }
            val prev = aimTarget?.takeIf { now - aimTargetAt < 1000 }
            // Petite zone morte (4°) : la tête ne tremble pas pour un visage presque centré.
            var tp = pose.first + dPan
            var tt = (pose.second + dTilt).coerceIn(-70.0, 70.0)
            if (prev != null) {
                // Calme : petits écarts (< 5°) ignorés, écarts moyens rattrapés en douceur ; un grand saut (la personne a
                // vraiment bougé) est suivi franchement.
                val jp = Head.wrap(tp - prev.first)
                val jt = tt - prev.second
                tp = if (kotlin.math.abs(jp) < 5) prev.first else prev.first + jp * (if (kotlin.math.abs(jp) > 25) 0.8 else 0.4)
                tt = if (kotlin.math.abs(jt) < 5) prev.second else prev.second + jt * (if (kotlin.math.abs(jt) > 20) 0.8 else 0.4)
            } else {
                if (kotlin.math.abs(dPan) < 4) tp = pose.first
                if (kotlin.math.abs(dTilt) < 4) tt = pose.second
            }
            val t = Head.wrap(tp) to tt
            aimTarget = t; aimTargetAt = now
            lastTrackAimAt = now
            head.aim(t.first, t.second)
        } else {
            val t = aimTarget ?: return
            if (now < follower.pausedUntil || !follower.enabled || roadGaze.active) { aimTarget = null; return }
            // Juste perdu : il finit d'aller là où il l'a vue (elle est sûrement là, un peu plus loin).
            if (now - aimTargetAt < 1200) { lastTrackAimAt = now; head.aim(t.first, t.second) } else aimTarget = null
        }
    }

    @Volatile private var lastGazeBack = 0L
    @Volatile private var poseLoggedAt = 0L
    @Volatile private var poseLogged: Pair<Double, Double>? = null

    /** Où regarde la tête, dans le journal (toutes les 2 s au plus, quand ça change) : pour comprendre les ratés. */
    private val calmPans = ArrayDeque<Double>()
    @Volatile private var calmAt = 0L

    /** Nervosité de la tête quand elle regarde quelqu'un : écart type du cap sur 10 s (dans le journal). */
    private fun journalCalm(now: Long) {
        val p = head.pose()
        if (p != null && follower.sees && trackLabels == null && !_state.value.busy) calmPans.addLast(p.first)
        if (now - calmAt < 10_000) return
        calmAt = now
        if (calmPans.size >= 15) {
            val m = calmPans.average()
            val sd = kotlin.math.sqrt(calmPans.sumOf { (Head.wrap(it - m)) * Head.wrap(it - m) } / calmPans.size)
            journal.write("mesure", "nervosité de la tête en regardant quelqu'un : écart type %.1f° (%d points)".format(sd, calmPans.size))
        }
        calmPans.clear()
    }

    private fun journalPose(now: Long) {
        runCatching { journalCalm(now) }
        if (now - poseLoggedAt < 2000 || !gimbal.ready) return
        val p = head.pose() ?: return
        val last = poseLogged
        if (last != null && kotlin.math.abs(Head.wrap(p.first - last.first)) < 5 && kotlin.math.abs(p.second - last.second) < 5) return
        poseLoggedAt = now; poseLogged = p
        val g = gimbal.angles
        val ph = sensors.read()
        journal.write("tete", "pan %.0f° incl. %.0f° (source %s ; téléphone %s ; nacelle %s ; voit quelqu'un : %s)".format(
            p.first, p.second, if (head.usesPhone) "téléphone" else "nacelle",
            ph?.let { "cap %.0f élév. %.0f".format(it.first, it.second) } ?: "-",
            g?.let { "pan %.0f tilt %.0f roll %.0f".format(it.pan, it.tilt, it.roll) } ?: "-", follower.sees))
    }

    /**
     * En conversation, Joey regarde la personne : s'il ne la voit plus depuis 2,5 s (coup d'œil ailleurs, on lui a
     * montré quelque chose, elle a bougé…) et que personne ne lui a demandé de regarder ailleurs, il revient vers
     * l'endroit où il l'a vue en dernier. Sinon (pas revue là), la recherche de celui qui parle prend le relais.
     */
    private fun conversationGaze(now: Long, s: UiState) {
        if (live == null || s.busy || !gimbal.ready || !follower.enabled || !flag("camera", true)) return
        if (follower.sees || now < follower.pausedUntil || trackLabels != null || dancing || questFollowing) return
        if (now < gazeDirectedUntil) return
        if (now - lastTalkAt > 20_000 || now - lastGazeBack < 6000) return
        val since = now - follower.seenAt
        if (since < 2500 || now - lastSeenAt > 10 * 60_000L || lastSeenPan == null) return
        lastGazeBack = now
        moveAsync(holdMs = 0) {
            if (lookAtLastSeen()) "je te regarde" else "pas revu là où je t'ai vu"
        }
    }

    /** Regarde là où il a vu quelqu'un en dernier. true s'il le revoit. À appeler sur le fil des mouvements. */
    private fun lookAtLastSeen(): Boolean {
        val last = lastSeenPan ?: return false
        val cur = head.absPan() ?: return false
        val t = System.currentTimeMillis()
        head.look(Head.wrap(last - cur), lastSeenTilt)
        Thread.sleep(700)
        return follower.seenSince(t)
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
        while (!cleared) {
            Thread.sleep(300)
            val now = System.currentTimeMillis()
            runCatching { clockTasks(now) }
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
            runCatching { journalPose(now) }
            if ((syncWantedAt in 1..now) || now - lastSyncAt > 5 * 60_000L) { lastSyncAt = now; syncWantedAt = 0; syncShared() }
            runCatching {
                val idle = live == null && !s.busy && now - follower.seenAt > 5 * 60_000L && now - motionAt > 5 * 60_000L &&
                    !flag("watch", false) && !questFollowing
                if (idle != s.idle) {
                    _state.update { it.copy(idle = idle) }
                    camera.frameEveryMs = if (idle) 400L else 90L
                    log(if (idle) "🔅 Personne depuis 5 min : je me mets en économie" else "🔆 Je me réveille de l'économie")
                }
            }
            runCatching { conversationGaze(now, s) }
            runCatching { idlePose(now, s) }
            runCatching { initiatives(now, s) }
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
            val sleepAfter = if (_state.value.night) 2 * 60_000L else 10 * 60_000L
            if (!flag("watch", false) && now - lastLifeAt > sleepAfter && now - motionAt > sleepAfter) {
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
                val frame = camera.frame(2000)
                moveAsync(holdMs = 3000) {
                    val (dp, dt) = imageToDegrees(mx, my, frame?.width ?: 4, frame?.height ?: 3)
                    head.look(dp, (head.pose()?.second ?: 0.0) + dt)
                    "coup d'œil vers ce qui bouge"
                }
                continue
            }
            // Ennui : de temps en temps, il regarde ailleurs, puis revient.
            val guarding = flag("watch", false)
            if (now > nextBoredom && (guarding || (now - lastLifeAt > 60_000 && !_state.value.night))) {
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

    // ---- gestes : pouce, V, coucou, doigt pointé, et la main pour pierre-feuille-ciseaux ------------
    private val gestures = com.joey.app.senses.GestureWatcher(app)
    private val body = com.joey.app.senses.BodyLanguage()
    @Volatile private var lastBodyAt = 0L
    // Attention aux mains : coup d'œil quand la personne regardée bouge les mains, puis retour au visage.
    private val handAttention = com.joey.app.senses.HandAttention().apply {
        onStart = {
            log("👐 Je regarde tes mains")
            // Gemini voit ce qu'il y a dans les mains (s'il en parle, c'est d'après une image fraîche).
            thread(isDaemon = true) { Thread.sleep(700); live?.sendFrameNow() }
        }
    }
    @Volatile private var targetFaceX = 0f
    @Volatile private var targetFaceY = 0f
    @Volatile private var targetFaceAt = 0L
    @Volatile private var bodyCostMs = 0L
    @Volatile private var rpsWaiting = false

    private fun readBody(bmp: android.graphics.Bitmap) {
        val now = System.currentTimeMillis()
        // Téléphone lent (S8+…) : on espace l'analyse pour que le suivi du visage reste fluide (≤ ~25 % du temps).
        val every = maxOf(if (rpsWaiting || handAttention.active) 150L else 220L, bodyCostMs * 3)
        if (!flag("gestures", true) || now - lastBodyAt < every) return
        lastBodyAt = now
        val small = android.graphics.Bitmap.createScaledBitmap(bmp, 480, 480 * bmp.height / bmp.width, true)
        val (hand, pose) = gestures.analyze(small)
        bodyCostMs = (bodyCostMs * 3 + (System.currentTimeMillis() - now)) / 4
        val recentFace = now - targetFaceAt < 1000
        if (flag("watch_hands", true) && !_state.value.busy)
            handAttention.update(pose, if (recentFace) targetFaceX else null, if (recentFace) targetFaceY else null)
        when (val g = body.update(now, hand, pose)) {
            null -> {}
            com.joey.app.senses.Body.ThumbUp -> { log("👍"); temperament.feel(0.1); roombaGesture(RoombaGesture.HAPPY); setMood(Mood.CONTENT); }
            com.joey.app.senses.Body.ThumbDown -> { log("👎"); temperament.feel(-0.08); setMood(Mood.TRISTE); }
            com.joey.app.senses.Body.Victory -> { log("✌"); setMood(Mood.CONTENT, sound = false) }
            com.joey.app.senses.Body.Wave -> {
                log("👋 Coucou")
                setMood(Mood.CONTENT)
                speakUp("La personne te fait coucou de la main 👋 : réponds-lui, brièvement.")
            }
            is com.joey.app.senses.Body.Point -> {
                if (!gimbal.ready || _state.value.busy) return
                log("👉 On me montre quelque chose")
                // L'image est droite et non retournée : le bras tendu vers la droite de l'image montre ce qui est à la
                // droite de Joey. On tourne franchement de ce côté (on ne sait pas à quelle distance est la chose).
                moveAsync(holdMs = 6000) {
                    // Gemini Robotics-ER suit la direction du bras ; sans lui, on tourne franchement du côté montré.
                    val er = if (geminiKey.isNotBlank() && vision.available()) lookWherePointed() else null
                    if (er == null) {
                        val side = if (g.dx >= 0) 1 else -1
                        val pan = side * (35.0 + 35.0 * kotlin.math.abs(g.dx))
                        val tilt = ((head.pose()?.second ?: 0.0) - g.dy * 35.0).coerceIn(-60.0, 45.0)
                        head.look(pan, tilt)
                    }
                    Thread.sleep(500)
                    live?.sendFrameNow()
                    live?.event("La personne te montre quelque chose du doigt : tu as tourné la tête de ce côté. Regarde la nouvelle " +
                        "image et dis ce que c'est (ou demande ce qu'elle veut te montrer).", reply = true)
                    "regardé où on me montre"
                }
            }
        }
    }

    // ---- toucher et secousses ----------------------------------------------------------------------
    fun touched() {
        setMood(Mood.ETONNE)
        roomba?.sing(com.joey.app.robot.RoombaRobot.Tune.R2D2)
    }

    fun tickled() {
        temperament.feel(0.08)
        roombaGesture(RoombaGesture.HAPPY)
        setMood(Mood.AMUSE)
        roomba?.sing(com.joey.app.robot.RoombaRobot.Tune.HAPPY)
        if (gimbal.ready && !_state.value.busy) moveAsync(holdMs = 1000) { head.shake() }
        remark("On te chatouille (double tape sur ton écran) : réagis en trois mots au plus.", minGapMs = 30_000)
    }

    fun petted() {
        setMood(Mood.CONTENT)
        roomba?.sing(com.joey.app.robot.RoombaRobot.Tune.SLEEPY)
        remark("On te caresse l'écran : un petit ronronnement de robot, en trois mots au plus.", minGapMs = 30_000)
    }

    /** Ses roues ont tourné à l'instant : les secousses viennent de lui, pas de quelqu'un. */
    private fun wheelsJustMoved() = roomba?.let { System.currentTimeMillis() - it.movingAt < 2500 } == true ||
        System.currentTimeMillis() - roombaMovedAt < 3000

    private fun shaken() {
        if (wheelsJustMoved()) return
        log("😵 On me secoue")
        temperament.feel(-0.1)
        moodResetAt = 0
        _state.update { it.copy(mood = Mood.ETOURDI) }
        roomba?.sing(com.joey.app.robot.RoombaRobot.Tune.SURPRISED)
        remark("On vient de te secouer : tu as le tournis, en quelques mots.", minGapMs = 20_000)
        thread(isDaemon = true) { Thread.sleep(5000); if (_state.value.mood == Mood.ETOURDI) _state.update { it.copy(mood = Mood.NEUTRE) } }
    }

    // ---- jeux --------------------------------------------------------------------------------------
    /** Pierre-feuille-ciseaux : Joey choisit, affiche son choix, lit la main de la personne. Bloquant (~4 s). */
    private fun playRps(): Map<String, Any?> {
        val mine = listOf("pierre", "feuille", "ciseaux").random()
        rpsWaiting = true
        try {
            val t0 = System.currentTimeMillis()
            // On attend une main nette (tenue au moins un instant), au plus 4 s.
            var theirs: String? = null
            while (System.currentTimeMillis() - t0 < 4000) {
                Thread.sleep(100)
                if (body.lastHandAt > t0 + 300) com.joey.app.senses.BodyLanguage.rps(body.lastHand)?.let { theirs = it }
                if (theirs != null) break
            }
            val emoji = mapOf("pierre" to "✊", "feuille" to "✋", "ciseaux" to "✌")
            _state.update { it.copy(bigText = emoji[mine]) }
            thread(isDaemon = true) { Thread.sleep(2500); _state.update { it.copy(bigText = null) } }
            val t = theirs ?: return mapOf("toi" to mine, "personne" to "pas vu sa main (qu'elle la montre bien à la caméra)")
            val win = mapOf("pierre" to "ciseaux", "ciseaux" to "feuille", "feuille" to "pierre")
            val res = when { t == mine -> "égalité"; win[mine] == t -> "tu gagnes"; else -> "la personne gagne" }
            log("✊✋✌ moi $mine, toi $t : $res")
            return mapOf("toi" to mine, "personne" to t, "resultat" to res)
        } finally {
            rpsWaiting = false
        }
    }

    /** Cache-cache : il ferme les yeux et compte (affiché), puis cherche. */
    private fun hideAndSeekCount(seconds: Int): String {
        moodResetAt = 0
        _state.update { it.copy(mood = Mood.DORT) }
        for (i in seconds downTo 1) {
            _state.update { it.copy(bigText = "$i") }
            Thread.sleep(1000)
        }
        _state.update { it.copy(bigText = null, mood = Mood.NEUTRE) }
        return "tu as fini de compter : annonce « J'arrive ! » puis cherche (outil chercher, objet « personne »)"
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
        val (name, score) = people.identify(print)
        // Deux reconnaissances de suite du même nom avant d'y croire.
        val prev = lastCandidate
        lastCandidate = name
        if (name == null || name != prev) return
        // Reconnu avec certitude : il complète le visage (lumière, angle, coiffure du jour…), au plus toutes les 2 min.
        // (Au plus toutes les 2 min, et partagé avec la synchro périodique : le journal du 23/10 montrait un envoi du
        // visage complet toutes les 30 s.)
        if (score >= 0.62f && now - (enrichedAt[name] ?: 0L) > 120_000) {
            enrichedAt[name] = now
            people.learn(name, listOf(print), quiet = true)
        }
        seeingName = name
        seeingAt = now
        if (_state.value.seeing != name) _state.update { it.copy(seeing = name) }
        // Où il ou elle est, sur la carte (distance d'après la taille du visage, ~15 cm de large).
        roomba?.takeIf { it.linkOk }?.let { rr ->
            runCatching {
                val hf = camera.fov(bmp.width, bmp.height).first
                val dist = (0.15 / (face.size * 2 * kotlin.math.tan(Math.toRadians(hf / 2)))).coerceIn(0.3, 6.0)
                val bearing = rr.heading + (if (gimbal.ready) bodyPan() ?: 0.0 else 0.0) +
                    imageToDegrees(face.x, 0f, bmp.width, bmp.height).first
                personSeenAt[name.lowercase()] = Triple(rr.x + dist * kotlin.math.cos(Math.toRadians(bearing)),
                    rr.y + dist * kotlin.math.sin(Math.toRadians(bearing)), now)
            }
        }
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
        // Ce visage est déjà celui de quelqu'un d'autre : on ne l'enregistre pas sous un autre prénom (sinon il confond).
        if (other != null && !other.equals(n, true)) {
            log("👤 Refusé : ce visage est celui de $other, pas de $n")
            return "je n'ai rien enregistré : le visage que je regarde est celui de $other. Tourne-toi d'abord vers $n " +
                "(« regarder_personne »), qu'elle se mette bien en face, et recommence"
        }
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
    private fun searchLocal(what: String, labels: Set<String>, keepMs: Long, firstDir: Int = 1): Boolean {
        trackName = what
        trackLabels = labels
        trackUntil = System.currentTimeMillis() + 120_000
        follower.forget()
        _state.update { it.copy(tracking = what) }
        // Pas de recherche un peu plus petits que le champ de la caméra : rien n'échappe entre deux arrêts.
        val hf = camera.frame(3000)?.let { camera.fov(it.width, it.height).first } ?: 60.0
        val found = head.searchTarget(follower::seenSince, firstDir, stepDeg = hf * 0.65)
        if (found) {
            trackUntil = System.currentTimeMillis() + keepMs
        } else {
            trackUntil = 0
        }
        return found
    }


    /** Tourne la tête vers un point de l'image (x, y de 0 à 1) repéré sur la dernière image. */
    private fun centerOnImagePoint(x: Double, y: Double) {
        val img = camera.frame(5000)
        val (dp, dt) = imageToDegrees((x * 2 - 1).toFloat(), (y * 2 - 1).toFloat(), img?.width ?: 4, img?.height ?: 3)
        if (!gimbal.ready) { if (kotlin.math.abs(dp) > 5) roomba?.turn(dp); return } // sans cou : le corps se tourne
        head.look(dp, ((head.pose()?.second ?: 0.0) + dt).coerceIn(-70.0, 70.0))
    }

    // ---- le regard : la tête si la nacelle est là, sinon tout le corps (il se débrouille avec ce qu'il a) -------
    /** Peut-il tourner son regard ? (nacelle, ou à défaut les roues) */
    private val canTurnGaze: Boolean get() = gimbal.ready || roomba != null

    /** Tourne le regard de [deg] (+ = droite) ; [tilt] seulement avec la nacelle. */
    private fun turnGaze(deg: Double, tilt: Double? = null): String = when {
        gimbal.ready -> head.look(deg, tilt)
        roomba != null -> roomba!!.turn(deg)
        else -> "ni nacelle ni roues : tu ne peux pas te tourner"
    }

    /** Où regarde-t-il (+ = droite, en degrés) : la tête, ou le cap des roues. */
    private fun gazePan(): Double = if (gimbal.ready) head.pose()?.first ?: 0.0 else roomba?.heading ?: 0.0

    /** Ni nacelle ni roues. */
    private val noGaze = "ni ta nacelle (ton cou) ni tes roues ne sont branchées : tu ne peux pas te tourner pour l'instant"

    /** Gemini Robotics-ER sur l'image de maintenant (pleine résolution). null s'il ne répond pas. */
    private fun erLocate(what: String, floor: Boolean = false): com.joey.app.brain.GeminiVision.Spot? {
        if (!vision.available()) return null // quota épuisé : inutile d'essayer (et de perdre du temps)
        val jpeg = camera.zoomJpeg("tout") ?: return null
        return runCatching { vision.locate(jpeg, what, floor) }.onFailure { log("Robotics-ER : ${it.message}") }.getOrNull()
    }

    /**
     * Vérifie avec Gemini Robotics-ER que [what] est bien là où regarde la tête (au centre de l'image).
     * true / false, ou null si Gemini ne répond pas.
     */
    private fun erVerify(what: String): Pair<Boolean, com.joey.app.brain.GeminiVision.Spot?>? {
        Thread.sleep(500) // image fraîche, tête arrêtée
        val sp = erLocate(what) ?: return null
        val ok = sp.found && sp.confidence >= 0.5 && kotlin.math.abs(sp.x - 0.5) < 0.32 && kotlin.math.abs(sp.y - 0.5) < 0.38
        log(if (ok) "✅ Robotics-ER confirme : $what" else "❌ Robotics-ER ne voit pas « $what » au centre (${sp.description})")
        return ok to sp
    }

    /**
     * Cherche avec Gemini Robotics-ER, méthodiquement : un tour complet dans un seul sens, arrêts un peu plus serrés que
     * le champ de la caméra, à hauteur des yeux puis plus bas (dans l'autre sens). À chaque arrêt l'image part à
     * Robotics-ER pendant que la tête passe déjà à l'arrêt suivant (2 analyses à la fois) : le tour ne ralentit pas.
     * Trouvé : la tête revient pile sur l'objet (cap de l'arrêt + position dans l'image).
     */
    private fun searchSpatial(what: String, labels: Set<String>? = null, firstDir: Int = 1): com.joey.app.brain.GeminiVision.Spot? {
        val useEr = vision.available()
        if (!useEr) log("🔎 ${vision.quotaText() ?: "vision Gemini indisponible"} : détecteur du téléphone seulement")
        if (!useEr && labels == null) return null
        // Le détecteur du téléphone regarde aussi à chaque arrêt (instantané) : le premier des deux qui trouve gagne.
        if (labels != null) {
            trackName = what; trackLabels = labels; trackUntil = System.currentTimeMillis() + 120_000
            follower.forget()
        }
        /** Le détecteur l'a vu depuis [since], et le revoit dans la seconde (sinon : fausse alerte, on continue). */
        fun localHit(since: Long): com.joey.app.brain.GeminiVision.Spot? {
            if (labels == null || !follower.seenSince(since)) return null
            val t = System.currentTimeMillis()
            var again = false
            while (System.currentTimeMillis() - t < 1200) { Thread.sleep(80); if (follower.seenSince(t + 100)) { again = true; break } }
            if (!again) { log("🔎 fausse alerte du détecteur : je continue"); return null }
            trackUntil = System.currentTimeMillis() + 15_000
            // La tête se centre dessus tout de suite (là où le détecteur l'a vu en dernier).
            centerOnImagePoint(((follower.faceX + 1) / 2).toDouble(), ((follower.faceY + 1) / 2).toDouble())
            log("🔎 Détecteur : $what")
            return com.joey.app.brain.GeminiVision.Spot(true, 0.8, 0.5, 0.5, null, null, "vu par le détecteur du téléphone", "téléphone")
        }
        class Stop(val job: Future<com.joey.app.brain.GeminiVision.Spot?>, val abs: Double, val tilt: Double, val w: Int, val h: Int)
        _state.update { it.copy(tracking = what) }
        val pool = Executors.newFixedThreadPool(2)
        val stops = ArrayList<Stop>()
        // Une analyse terminée qui a trouvé ? (on retire celles qui sont finies)
        fun hitDone(): Pair<Stop, com.joey.app.brain.GeminiVision.Spot>? {
            val it = stops.iterator()
            while (it.hasNext()) {
                val st = it.next()
                if (!st.job.isDone) continue
                it.remove()
                val sp = runCatching { st.job.get() }.getOrNull()
                if (sp != null && sp.found && sp.confidence >= 0.5) return st to sp
            }
            return null
        }
        fun goTo(hit: Pair<Stop, com.joey.app.brain.GeminiVision.Spot>): com.joey.app.brain.GeminiVision.Spot {
            val (st, sp) = hit
            val (dp, dt) = imageToDegrees((sp.x * 2 - 1).toFloat(), (sp.y * 2 - 1).toFloat(), st.w, st.h)
            val cur = head.absPan() ?: st.abs
            head.look(Head.wrap(st.abs + dp - cur), (st.tilt + dt).coerceIn(-70.0, 70.0))
            log("🔎 Robotics-ER : $what" + distanceText(sp.distance))
            return sp
        }
        try {
            val hf = camera.frame(3000)?.let { camera.fov(it.width, it.height).first } ?: 60.0
            val step = (hf * 0.85).coerceIn(40.0, 90.0)
            val n = kotlin.math.ceil(360.0 / step).toInt()
            var dir = if (firstDir < 0) -1 else 1
            for (tilt in listOf(0.0, -30.0)) {
                head.look(null, tilt)
                for (i in 0 until n) {
                    if (i > 0) head.look(dir * step, null)
                    val stopAt = System.currentTimeMillis()
                    // Image nette, tête arrêtée : le détecteur a ~0,45 s pour le voir.
                    while (System.currentTimeMillis() - stopAt < 450) {
                        localHit(stopAt - 300)?.let { return it }
                        Thread.sleep(50)
                    }
                    hitDone()?.let { return goTo(it) }
                    if (!useEr) continue
                    val img = camera.frame(1000) ?: continue
                    val jpeg = camera.zoomJpeg("tout") ?: continue
                    val abs = head.absPan() ?: continue
                    val t = head.pose()?.second ?: tilt
                    // Au plus 2 analyses en cours : sinon on attend un peu (et on regarde si l'une a trouvé).
                    while (stops.count { !it.job.isDone } >= 2) {
                        hitDone()?.let { return goTo(it) }
                        localHit(System.currentTimeMillis() - 300)?.let { return it }
                        Thread.sleep(100)
                    }
                    stops.add(Stop(pool.submit<com.joey.app.brain.GeminiVision.Spot?> {
                        runCatching { vision.locate(jpeg, what) }.onFailure { log("Robotics-ER : ${it.message}") }.getOrNull()
                    }, abs, t, img.width, img.height))
                }
                dir = -dir
            }
            // Les dernières analyses.
            while (stops.isNotEmpty()) {
                hitDone()?.let { return goTo(it) }
                Thread.sleep(100)
            }
            return null
        } finally {
            pool.shutdownNow()
            _state.update { it.copy(tracking = null) }
            if (labels != null && System.currentTimeMillis() > trackUntil - 14_000) { trackLabels = null; trackUntil = 0 }
        }
    }

    /**
     * Ce que la personne montre du doigt : Gemini Robotics-ER suit la direction de son bras ; la tête se tourne vers la
     * chose (pile dessus si elle est dans l'image, sinon du bon côté, puis il la repère). Renvoie ce que c'est, ou null
     * si Gemini ne répond pas. À appeler sur le fil des mouvements.
     */
    private fun lookWherePointed(): String? {
        val jpeg = camera.zoomJpeg("tout") ?: return null
        val pt = runCatching { vision.pointedAt(jpeg) }.onFailure { log("Robotics-ER : ${it.message}") }.getOrNull() ?: return null
        if (!pt.pointing) {
            // Pas de doigt pointé : elle tient peut-être quelque chose devant la caméra (un écran, un papier…).
            val held = runCatching { vision.shownObject(jpeg) }.getOrNull()
            return held?.let { "la personne te montre : $it" } ?: "la personne ne montre rien de précis sur l'image"
        }
        log("👉 Robotics-ER : on me montre ${pt.target} (${pt.description})")
        turnToPointing(pt)
        return "la personne montre ${pt.target} : ${pt.description}"
    }

    /** Tourne la tête vers ce que la personne montre ou regarde (pile dessus si c'est dans l'image). */
    private fun turnToPointing(pt: com.joey.app.brain.GeminiVision.Pointing) {
        if (pt.inImage) {
            centerOnImagePoint(pt.x, pt.y)
        } else {
            val ang = (pt.angle ?: 60.0).coerceIn(30.0, 160.0)
            val tilt = head.pose()?.second ?: 0.0
            when (pt.direction) {
                "gauche" -> head.look(-ang, null)
                "droite" -> head.look(ang, null)
                "haut" -> head.look(null, (tilt + minOf(ang, 45.0)).coerceAtMost(60.0))
                "bas" -> head.look(null, (tilt - minOf(ang, 45.0)).coerceAtLeast(-70.0))
                "derriere_le_robot" -> head.lookDirection("derriere", null)
                else -> {}
            }
            // Nouvelle vue : on repère la chose pour la regarder pile.
            if (pt.target.isNotBlank() && pt.target != "inconnu") {
                Thread.sleep(600)
                erLocate(pt.target)?.takeIf { it.found && it.confidence >= 0.5 }?.let { centerOnImagePoint(it.x, it.y) }
            }
        }
    }

    /**
     * « Va vers le canapé » : Gemini Robotics-ER repère l'endroit au sol devant la chose et sa distance ; Joey s'aligne,
     * avance par étapes (1 m au plus), regarde à nouveau, et s'arrête à ~50 cm ou sur un obstacle. Bloquant.
     */
    private fun goToward(what: String): String {
        val r = roomba ?: return noWheels
        missionStart = r.x to r.y
        // Il sait où c'est (vu en cartographiant) et c'est loin : il y va d'abord par sa carte, puis s'approche à vue.
        houseMap.find(what).firstOrNull { it.second.x != null && it.first.frame == gridFrame }?.let { (place, item) ->
            val ix = item.x!!; val iy = item.y!!
            val d = kotlin.math.hypot(ix - r.x, iy - r.y)
            if (d > 1.3) {
                log("🗺 Je sais où est « ${item.name} » (${place.name}) : j'y vais")
                val k = (d - 0.7) / d
                val nav = navigateTo(r.x + (ix - r.x) * k, r.y + (iy - r.y) * k)
                if (nav.startsWith("arrêté")) return "je me suis arrêté en route : $nav"
                r.turn(Head.wrap(Math.toDegrees(kotlin.math.atan2(iy - r.y, ix - r.x)) - r.heading))
            }
        }
        follower.pausedUntil = Long.MAX_VALUE // la tête vise la chose, pas les visages
        var sp = erLocate(what, floor = true)
        if (sp == null) return "Gemini Robotics-ER ne répond pas"
        if ((!sp.found || sp.confidence < 0.5) && !gimbal.ready) {
            // Sans cou : il tourne sur lui-même par sixièmes de tour et demande à chaque arrêt.
            var hit: com.joey.app.brain.GeminiVision.Spot? = null
            for (k in 1..5) {
                r.turn(60.0); Thread.sleep(500)
                val s2 = erLocate(what, floor = true) ?: break
                if (s2.found && s2.confidence >= 0.5) { hit = s2; break }
            }
            sp = hit ?: return "je ne vois « $what » nulle part autour de moi (j'ai tourné sur moi-même)"
            centerOnImagePoint(sp.x, sp.y)
        } else if (!sp.found || sp.confidence < 0.5) {
            sp = searchSpatial(what) ?: return "je ne vois « $what » nulle part autour de moi"
        } else {
            centerOnImagePoint(sp.x, sp.y)
        }
        repeat(6) { step ->
            alignBodyWithHead()
            Thread.sleep(600)
            val s2 = erLocate(what, floor = true) ?: return "Gemini Robotics-ER ne répond plus en route"
            if (!s2.found) return if (step == 0) "je ne vois plus « $what »" else
                "je suis tout près de « $what » (je ne le vois plus en entier)"
            centerOnImagePoint(s2.x, s2.y)
            val d = s2.distance ?: 1.0
            if (d <= 0.6) return "je suis arrivé devant « $what »" + distanceText(d)
            alignBodyWithHead()
            val res = r.drive((d - 0.5).coerceIn(0.2, 1.0))
            if ((res.contains("obstacle") || res.contains("bloqué")) && step < 4) { log("🛞 ${r.unstick(step)}"); return@repeat }
            if (res.contains("obstacle") || res.contains("vide") || res.contains("interrompu") || res.contains("bloqué") ||
                res.contains("liaison")) return "je me suis arrêté : $res"
        }
        return "je me suis approché de « $what »"
    }

    private fun distanceText(d: Double?) = d?.let { " (à environ %.1f m)".format(it) } ?: ""

    /** Ce que voit le détecteur du téléphone en ce moment. */
    private fun seenNow(): List<Seen> = camera.frame()?.let { objects.detect(it) } ?: emptyList()

    fun connectGimbal() { if (!cleared) gimbal.connect() }
    fun disconnectGimbal() = gimbal.disconnect()

    // ---- commandes à la main (écran « tête ») -----------------------------------------------
    /** Joystick de l'écran : x = droite, y = haut, de -1 à 1. */
    fun manualStick(x: Float, y: Float) {
        val sign = if (flag("invert_pan", false)) -1 else 1
        if (x != 0f || y != 0f) follower.pause(4000)
        val speed = head.maxStickNow()
        // Câble : pas plus d'un demi-tour de chaque côté, même à la main.
        val px = if (cable.blocks(x.toDouble())) 0f else x
        gimbal.stick((px * speed * sign).toInt(), (y * speed * viewTiltSign).toInt())
    }

    private fun background(name: String, body: () -> String): Unit = moveAsync(holdMs = 4000) { body() }

    // ---- mouvements de tête : un seul à la fois, le dernier demandé annule le précédent ----------------
    private val motion = Executors.newSingleThreadExecutor()
    @Volatile private var motionJob: Future<*>? = null
    private val motionGen = AtomicInteger()

    private fun startMotion(): Int {
        motionJob?.cancel(true)
        follower.pause(120_000)
        head.stopAim()
        aimTarget = null
        handAttention.stop()
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

    @Volatile private var dancing = false

    fun dance(seconds: Int) {
        setMood(Mood.CONTENT)
        dancing = true
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
                dancing = false
            }
            "danse finie"
        }
    }

    fun danceUi() = dance(20)

    // ---- rappels, batteries, nuit, journal du jour : ce qui dépend de l'heure ---------------------------
    val reminders = com.joey.app.brain.Reminders(File(app.filesDir, "rappels.json"))
    /** Message à dire dès que la conversation démarre (rappel qui l'a réveillé…). */
    @Volatile private var pendingEvent: String? = null
    @Volatile private var lastClockMinute = 0L
    private val warned = HashSet<String>()
    @Volatile private var convStarts = 0
    @Volatile private var photosToday = 0

    /** Fait dire quelque chose à Joey : dans la conversation, ou en la démarrant exprès. */
    @Volatile private var lastRemarkAt = 0L

    /**
     * Remarque spontanée (pas demandée) : seulement en conversation, quand personne ne parle, et pas plus d'une toutes
     * les [minGapMs]. Sinon on ne dit rien (il réagit déjà avec son visage et sa tête).
     */
    private fun remark(text: String, minGapMs: Long = 45_000) {
        if (testQuiet) return
        val l = live ?: return
        val now = System.currentTimeMillis()
        if (now - lastRemarkAt < minGapMs || now - lastPersonTalkAt < 4000 || _state.value.live != LiveState.LISTENING) return
        lastRemarkAt = now
        l.event(text, reply = true)
    }

    private fun speakUp(text: String) {
        if (testQuiet) { log("🔇 (mode test) $text".take(160)); return }
        val l = live
        if (l != null) { l.event(text, reply = true); return }
        if (geminiKey.isBlank()) return
        pendingEvent = text
        startedByWake = true // la conversation s'arrêtera seule après le silence
        thread(isDaemon = true) { Thread.sleep(300); startLive() }
    }

    private fun clockTasks(now: Long) {
        // Rappels : chaque seconde environ.
        for (r in reminders.due()) {
            log("⏰ Rappel : ${r.text}")
            setMood(Mood.ETONNE)
            speakUp("[Rappel programmé] C'est l'heure : « ${r.text} ». Dis-le à la personne maintenant, clairement, à ta façon.")
            watch.notify("⏰ Rappel : ${r.text}", null)
        }
        // Le reste : une fois par minute.
        val minute = now / 60_000
        if (minute == lastClockMinute) return
        lastClockMinute = minute
        refreshRoomba()
        val cal = java.util.Calendar.getInstance()
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val night = flag("night_mode", true) && (h >= 23 || h < 7)
        val phone = phoneBattery()
        if (night != _state.value.night || phone != _state.value.phoneBattery) _state.update { it.copy(night = night, phoneBattery = phone) }
        // Batteries faibles : on prévient une fois.
        fun warn(key: String, low: Boolean, text: String) {
            if (low && warned.add(key)) { log("🔋 $text"); if (live != null) live?.event("[Batterie] $text Dis-le brièvement.", reply = true) else watch.notify("🔋 $text", null) }
            if (!low) warned.remove(key)
        }
        warn("phone", phone in 0..19 && !phoneCharging(), "La batterie de mon téléphone est à $phone % : branche-moi.")
        // Téléphone presque vide et des roues : il rentre à la base de lui-même (une fois).
        roomba?.let { rr ->
            if (phone in 0..12 && !phoneCharging() && !rr.onBase && !following && !_state.value.busy && warned.add("phone_dock")) {
                log("🔋 Téléphone à $phone % : je rentre à ma base")
                thread(isDaemon = true) { runCatching { log("🛞 " + dockAndVerify()) } }
            }
            if (phone > 20) warned.remove("phone_dock")
        }
        // La nuit (3 h - 5 h), sans conversation : ménage dans les souvenirs, une fois par jour.
        val cleanDay = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.FRENCH).format(Date())
        if (h in 3..4 && live == null && prefs.getString("memory_cleanup_day", "") != cleanDay && geminiKey.isNotBlank()) {
            prefs.edit().putString("memory_cleanup_day", cleanDay).apply()
            thread(name = "menage-souvenirs", isDaemon = true) { runCatching { cleanMemories() } }
        }
        warn("gimbal", gimbal.battery in 0..14, "La batterie de ma nacelle (mon cou) est à ${gimbal.battery} %.")
        val r = roomba
        val rb = r?.battery ?: -1
        warn("roomba", rb in 0..14 && r?.charging == false, "La batterie de mes roues est à $rb %, je rentre à ma base.")
        if (r != null && rb in 0..14 && !r.charging && !r.onBase && !following && !_state.value.busy && warned.contains("roomba") &&
            warned.add("roomba_dock")) {
            thread(isDaemon = true) { runCatching { log("🛞 " + dockAndVerify()) } }
        }
        if (rb > 30) warned.remove("roomba_dock")
        // Journal du jour : le soir à 21 h 30, une fois.
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.FRENCH).format(Date(now))
        if (h == 21 && cal.get(java.util.Calendar.MINUTE) >= 30 && prefs.getString("diary_done", "") != today && flag("diary", true)) {
            prefs.edit().putString("diary_done", today).apply()
            thread(isDaemon = true) { runCatching { writeDiary(send = true) } }
        }
        if (cal.get(java.util.Calendar.HOUR_OF_DAY) == 0 && cal.get(java.util.Calendar.MINUTE) == 0) { photosToday = 0; convStarts = 0 }
    }

    private fun batteryIntent() = getApplication<Application>().registerReceiver(null,
        android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))

    fun phoneBattery(): Int = runCatching {
        val i = batteryIntent()!!
        val level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
        if (level < 0) -1 else level * 100 / scale
    }.getOrDefault(-1)

    private fun phoneCharging(): Boolean = runCatching {
        batteryIntent()!!.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
    }.getOrDefault(false)

    /** Le journal de la journée, écrit par Joey (Gemini) d'après ce qu'il a noté ; envoyé sur le téléphone si [send]. */
    fun writeDiary(send: Boolean): String {
        val start = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
        }.timeInMillis
        val events = watch.recent(60).filter { it.time >= start }.reversed()
        val mems = memory.all().filter { it.time >= start }
        val fmt = SimpleDateFormat("HH:mm", Locale.FRENCH)
        val facts = buildString {
            append("Conversations aujourd'hui : $convStarts. Photos prises : $photosToday.\n")
            if (greetedAt.isNotEmpty()) append("Gens reconnus : ${greetedAt.keys.joinToString()}.\n")
            if (events.isNotEmpty()) append("Ce que j'ai remarqué :\n" + events.joinToString("\n") { "- " + fmt.format(Date(it.time)) + " " + it.text } + "\n")
            if (mems.isNotEmpty()) append("Ce que j'ai appris :\n" + mems.joinToString("\n") { "- " + it.text } + "\n")
            if (currentPlace != null) append("Je suis dans : $currentPlace.\n")
        }
        val schema = org.json.JSONObject("{\"type\":\"object\",\"properties\":{\"journal\":{\"type\":\"string\"}},\"required\":[\"journal\"]}")
        val text = runCatching {
            vision.json("Tu es Joey, petit robot de maison pince-sans-rire (inspiré de Beneath a Steel Sky). Écris ton journal de la " +
                "journée en 4 à 7 phrases, à la première personne, avec humour et tendresse, d'après ces notes (n'invente rien " +
                "d'important ; si la journée a été calme, dis-le avec style) :\n\n$facts", schema).optString("journal")
        }.getOrElse { "Journal impossible aujourd'hui (${it.message})." }
        val dir = File(getApplication<Application>().filesDir, "journal").apply { mkdirs() }
        runCatching { File(dir, java.text.SimpleDateFormat("yyyy-MM-dd", Locale.FRENCH).format(Date()) + ".txt").writeText(text) }
        log("📔 Journal du jour écrit")
        if (send) watch.notify("📔 Journal de Joey : $text", events.lastOrNull { it.hasPhoto }?.let { watch.photo(it.id) })
        return text
    }

    // ---- les roues : le Roomba ------------------------------------------------------------------
    @Volatile var roomba: com.joey.app.robot.RoombaRobot? = null
        private set
    @Volatile private var roombaConnecting = false
    @Volatile private var following = false

    /** Branche le Roomba par la carte USB-série (en arrière-plan). [quiet] : pas de message si rien n'est branché. */
    /**
     * La liaison série est bloquée (plus de réponse depuis 20 s) : on referme et rouvre la carte USB, sans perdre la carte ni
     * la position (24/10 : il a fallu relancer l'app à la main pour débloquer la liaison).
     */
    private fun reopenRoomba() {
        val old = roomba ?: return
        val pose = Triple(old.x, old.y, old.heading)
        val rev = old.reversed
        thread(name = "roomba-reouverture", isDaemon = true) {
            runCatching { old.disconnect() }
            if (roomba === old) roomba = null
            refreshRoomba()
            Thread.sleep(1500)
            // Plusieurs essais (toutes les 10 s pendant 5 min) : le Roomba peut mettre un moment à répondre de nouveau.
            for (attempt in 0 until 30) {
                if (cleared || roomba != null) break
                connectRoomba(quiet = true, restore = pose, restoreReversed = rev)
                Thread.sleep(10_000)
            }
            if (roomba == null) log("🛞 Le Roomba ne répond toujours pas : vérifie qu'il est allumé et que les fils sont en place")
        }
    }

    @Volatile private var lastRoombaError: String? = null

    /**
     * Sans roues, il réessaie de lui-même toutes les 30 s (Roomba endormi puis réveillé, autorisation USB accordée plus
     * tard…) : avant, après un échec au démarrage, il restait sans roues jusqu'à ce qu'on relance l'app.
     */
    private fun roombaRetryLoop() {
        while (!cleared) {
            Thread.sleep(30_000)
            if (roomba == null && !roombaConnecting && flag("roomba_auto", true)) runCatching { connectRoomba(quiet = true) }
        }
    }

    fun connectRoomba(quiet: Boolean = false, restore: Triple<Double, Double, Double>? = null, restoreReversed: Boolean? = null) {
        if (roomba != null || roombaConnecting) return
        roombaConnecting = true
        thread(name = "roomba", isDaemon = true) {
            try {
                val link = com.joey.app.robot.UsbSerial.open(getApplication())
                val r = com.joey.app.robot.RoombaRobot(link, ::log)
                r.connect()
                roomba = r
                lastRoombaError = null
                applyTuning(r)
                restoreReversed?.let { r.reversed = it }
                r.onDead = { reopenRoomba() }
                val n = com.joey.app.robot.Navigator(r, grid, ::log)
                nav = n
                if (restore != null) {
                    // Réouverture après une panne de liaison : même carte, même position (pas un nouveau départ).
                    r.setPose(restore.first, restore.second, restore.third)
                    log("🛞 Carte USB rouverte : je garde ma carte et ma position")
                } else {
                    bodyRef = null // nouvelles roues, nouveau repère
                    anchorGrid(r)
                }
                r.onContact = { kind, side -> runCatching { n.contact(kind, side) } }
                // Ses détecteurs de proximité et son capteur de mur dessinent les obstacles sur la carte avant qu'il les touche.
                r.onProximity = { angle, d -> runCatching { n.proximity(angle, d) } }
                // Sans nacelle, le téléphone est fixé au corps : son gyroscope donne les rotations, plus justes que les roues
                // (qui patinent sur un tapis) ; la carte ne se tord plus. Avec la nacelle (qui le stabilise), ou téléphone à plat
                // (cap de la caméra indéfini) : les roues.
                r.gyroHeading = { if (gimbal.ready) null else sensors.read()?.takeIf { kotlin.math.abs(it.second) < 60 }?.first }
                // Ce que la caméra voit de sol libre devant (récent seulement) : il ralentit et s'arrête avant le choc.
                r.cautiousWhenBlind = true
                r.visionClear = { clearAhead() }
                // Les boutons du Roomba deviennent des commandes.
                r.onButton = { b -> runCatching { roombaButton(r, b) } }
                log("🛞 Roomba branché : j'ai des roues !")
                // Il le sait tout de suite (sinon il répondait « pas branchées » d'après ce qu'il savait avant).
                live?.event("Tes roues (le Roomba) viennent d'être branchées : tu peux rouler.", reply = false)
                r.onLink = { ok ->
                    log(if (ok) "🛞 Liaison avec le Roomba revenue" else "🛞 Liaison avec le Roomba coupée (fil qui bouge ?)")
                    live?.event(if (ok) "La liaison avec tes roues est revenue." else "Tes roues ne répondent plus (le Roomba s'est " +
                        "peut-être endormi, ou un fil a bougé) : tu t'es arrêté par sécurité. Dis-le simplement, et si ça dure, " +
                        "demande qu'on appuie une fois sur le bouton CLEAN du Roomba pour le réveiller.", reply = false)
                }
                refreshRoomba()
            } catch (e: Exception) {
                // Toujours au journal (24/10 : un échec silencieux au démarrage laissait Joey sans roues, sans trace) ; le
                // message à l'écran seulement si on l'a demandé.
                val why = e.message ?: e.toString()
                if (why != lastRoombaError) { log("Roomba : $why"); lastRoombaError = why }
                if (!quiet) message("Roomba : $why")
            } finally {
                roombaConnecting = false
            }
        }
    }

    fun disconnectRoomba() {
        following = false
        roomba?.disconnect()
        roomba = null
        refreshRoomba()
    }

    private fun refreshRoomba() {
        val r = roomba
        _state.update { it.copy(roomba = r?.let { x ->
            (if (x.charging) "⚡ " else "") + "🔋" + (x.battery?.let { b -> "$b%" } ?: "?") + (if (x.onBase) " · sur sa base" else "")
        }) }
    }

    private val noWheels = "tes roues (le Roomba) ne sont pas branchées : tu ne peux pas te déplacer pour l'instant"

    /**
     * Repère du corps : cap de la tête (capteurs, repère du monde) quand elle regarde droit devant le Roomba, et cap des
     * roues à ce moment-là. La tête et les roues ont chacune leur repère : sans lui, Joey croyait la tête « à 169° »
     * alors qu'elle était presque dans l'axe, et faisait demi-tour au lieu de s'approcher.
     */
    @Volatile private var bodyRef: Triple<Double, Double, Long>? = null
    /** Source de l'angle de la tête quand le repère a été pris (téléphone ou nacelle) : les deux n'ont pas le même zéro. */
    @Volatile private var bodyRefPhone = false

    private fun setBodyRef(abs: Double, wheels: Double) {
        bodyRefPhone = head.usesPhone
        bodyRef = Triple(abs, wheels, System.currentTimeMillis())
    }

    /** La tête se recentre (droit devant le Roomba) : on note le repère du corps. */
    private fun calibrateBody(): Boolean {
        val r = roomba ?: return false
        if (!gimbal.ready) return false
        follower.pause(2500) // le suivi ne doit pas tirer la tête pendant qu'elle se recentre
        // D'abord les pastilles du Roomba (le vrai axe du corps) ; sinon le recentrage de la nacelle (24/10 : il laissait
        // la tête à +30° ou -15° de l'axe, selon les fois).
        if (flag("pastilles", true) && alignHeadByMarks() != null) return true
        head.center()
        val abs = head.absPan() ?: return false
        setBodyRef(abs, r.heading)
        return true
    }

    /** Inclinaison de la tête pour voir les repères du Roomba (séance du 24/10 : visibles de -50° à -70°). */
    private val marksTilt = -60.0

    /**
     * La tête se baisse vers le Roomba, les deux pastilles blanches de l'axe donnent l'angle exact entre la tête et le corps
     * ([com.joey.app.senses.BodyMarks]) : le repère tête-corps est repris, puis la tête se tourne dans l'axe (3 essais au
     * plus, jusqu'à moins de 4°). Renvoie le dernier angle mesuré, ou null si les pastilles restent invisibles.
     */
    private fun alignHeadByMarks(): Double? {
        val r = roomba ?: return null
        if (!gimbal.ready) return null
        follower.pause(12_000)
        var last: Double? = null
        // 5 essais, correction amortie : la nacelle dépasse la cible (24/10, rejoué depuis le PC : 14° -> -11° -> 6°).
        for (attempt in 0 until 5) {
            head.look(null, marksTilt, timeoutMs = 1500)
            Thread.sleep(400)
            val asked = System.currentTimeMillis()
            var img: android.graphics.Bitmap? = null
            while (System.currentTimeMillis() - asked < 1500) { if (camera.frameAt > asked) { img = camera.frame(500); break }; Thread.sleep(30) }
            val bmp = img ?: break
            val (hf, vf) = camera.fov(bmp.width, bmp.height)
            val pitch = head.pose()?.second ?: marksTilt
            val w = 320; val h = (320.0 * bmp.height / bmp.width).toInt().coerceAtLeast(120)
            val small = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
            val px = IntArray(w * h); small.getPixels(px, 0, w, 0, 0, w, h)
            if (small !== bmp) small.recycle()
            val res = com.joey.app.senses.BodyMarks.measure(px, w, h, pitch, hf, vf, arrow = flag("fleche", false)) ?: break
            last = res.panDeg
            log("🎯 Essai ${attempt + 1} : tête à %+.0f° de l'axe du corps (inclinaison %.0f°)".format(res.panDeg, pitch))
            val abs = head.absPan() ?: break
            setBodyRef(abs - res.panDeg, r.heading) // maintenant bodyPan() vaut l'angle mesuré
            cable.correct(res.panDeg) // le compteur du câble suit l'angle réel (au tour près)
            if (kotlin.math.abs(res.panDeg) < 5) break
            // C'est le corps qui se tourne vers la tête : la nacelle, verrouillée, garde la tête fixe dans la pièce, et le
            // Roomba tourne juste. (Tourner la tête vers le corps ne marchait pas : la nacelle obéit mal aux petites
            // rotations, 24/10 : -18° -> -70° -> +38° -> -36° -> +39°, puis -80° -> -40° -> +37°.)
            val turned = r.turn(res.panDeg)
            if (turned.startsWith("arrêté") || turned == "interrompu") break
            Thread.sleep(300)
        }
        if (last == null) log("🎯 Pastilles invisibles : je recentre la nacelle")
        return last
    }

    /** Où regarde la tête par rapport à l'avant du Roomba (+ = droite), ou null si on ne sait pas. */
    private fun bodyPan(): Double? {
        val r = roomba ?: return null
        if (!gimbal.ready) return 0.0 // sans nacelle, le téléphone est fixé au corps
        val ref = bodyRef ?: return null
        // La tête a changé de source de mesure : l'ancien repère ne veut plus rien dire.
        if (head.usesPhone != bodyRefPhone) return null
        val abs = head.absPan() ?: return null
        return Head.wrap(abs - (ref.first + Head.wrap(r.heading - ref.second)))
    }

    /** Tourne le corps vers où regarde la tête ; la tête garde sa cible (elle revient vers le même point). */
    private fun alignBodyWithHead(): Double {
        val r = roomba ?: return 0.0
        if (!gimbal.ready) return 0.0
        val target = head.absPan() ?: return 0.0
        // Repère inconnu ou ancien (les capteurs dérivent un peu) : la tête se recentre pour le reprendre, puis revient.
        val ref = bodyRef
        if (ref == null || System.currentTimeMillis() - ref.third > 10 * 60_000) {
            if (!calibrateBody()) return 0.0
            head.absPan()?.let { head.look(Head.wrap(target - it), null) }
        }
        val pan = bodyPan() ?: return 0.0
        if (kotlin.math.abs(pan) < 8) return 0.0
        r.turn(pan)
        head.absPan()?.let { now -> val d = Head.wrap(target - now); if (kotlin.math.abs(d) > 4) head.look(d, null) }
        return pan
    }

    /** Largeur de champ de la caméra, en degrés, pour l'image en cours. */
    private fun hfovNow(): Double = camera.frame()?.let { camera.fov(it.width, it.height).first } ?: camera.fovShort

    private fun bestSeen(labels: Set<String>) = seenNow().filter { it.label in labels && it.score >= 0.5 }.maxByOrNull { it.box.h * it.box.w }

    /**
     * Sans nacelle, le corps sert de cou : il tourne sur place par huitièmes de tour et regarde à chaque arrêt.
     * Renvoie ce qu'il a vu (déjà à peu près en face), ou null après un tour complet.
     */
    private fun searchWithBody(r: com.joey.app.robot.RoombaRobot, labels: Set<String>): com.joey.app.senses.Seen? {
        for (i in 0..8) {
            val p = bestSeen(labels) ?: run { Thread.sleep(250); bestSeen(labels) } // deux regards par arrêt
            if (p != null) {
                val ang = (p.box.cx - 0.5) * hfovNow()
                if (kotlin.math.abs(ang) > 8) { r.turn(ang); Thread.sleep(400) }
                return bestSeen(labels) ?: p
            }
            if (i == 8 || r.ready() != null) return null
            r.turn(45.0)
            Thread.sleep(500)
        }
        return null
    }

    // ---- il apprend : réglages, auto-tests des roues ------------------------------------------------------------
    private fun applyTuning(r: com.joey.app.robot.RoombaRobot) {
        r.reversed = flag("roomba_reversed", false)
        r.keepAwake = flag("roomba_awake", true)
        r.maxSpeed = tuning.get("vitesse")
        r.turnScale = tuning.get("echelle_rotation")
    }

    /** Petite image en niveaux de gris de la caméra, prise après [after] (ms), ou null. */
    private fun grayAfter(after: Long, timeoutMs: Long = 2500): Triple<FloatArray, Int, Int>? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (camera.frameAt > after) {
                val b = camera.frame() ?: break
                val (w, h) = if (b.width >= b.height) 80 to 60 else 60 to 80
                val small = android.graphics.Bitmap.createScaledBitmap(b, w, h, true)
                val px = IntArray(w * h)
                small.getPixels(px, 0, w, 0, 0, w, h)
                return Triple(FloatArray(w * h) { i ->
                    val c = px[i]
                    (0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)) / 255f
                }, w, h)
            }
            Thread.sleep(50)
        }
        return null
    }

    /**
     * Auto-test du sens : une image, 30 cm « en avant », une image, retour. Si la scène a rapetissé, « avancer »
     * l'éloigne de ce que voit son visage : le visage regarde vers l'arrière du Roomba, il corrige le réglage.
     */
    private fun selfTestDirection(r: com.joey.app.robot.RoombaRobot): String {
        if (!flag("camera", true)) return "sens : caméra coupée, pas vérifié"
        if (gimbal.ready) head.center() // la tête droit devant, sinon l'image ne dit rien du sens des roues
        Thread.sleep(500)
        val a = grayAfter(System.currentTimeMillis()) ?: return "sens : pas d'image, pas vérifié"
        val go = r.drive(0.3)
        if (!go.startsWith("avancé")) return "sens : pas pu avancer ($go)"
        Thread.sleep(300)
        val b = grayAfter(System.currentTimeMillis())
        r.drive(-0.3)
        b ?: return "sens : pas d'image, pas vérifié"
        val z = com.joey.app.senses.ImageZoom.estimate(a.first, b.first, a.second, a.third)
        journal.write("roues", "auto-test du sens : zoom %.3f (erreur %.4f, sans zoom %.4f)".format(z.scale, z.error, z.errorAtOne))
        return when {
            !z.confident -> "sens : pas sûr (rien de net devant la caméra ?), rien changé"
            z.scale > 1 -> "sens : bon (avancer va bien du côté de ton visage)"
            else -> {
                val now = !r.reversed
                setFlagValue("roomba_reversed", now)
                log("🛞 Auto-test : j'avançais du mauvais côté, j'ai inversé le sens de mes roues")
                "sens : FAUX, corrigé (avancer allait à l'opposé de ton visage ; réglage sens_roues inversé)"
            }
        }
    }

    @Volatile private var wheelFlipAskedAt = 0L

    /**
     * « Tu avances à l'envers » : on vérifie d'abord avec la caméra (le monde grossit-il en avançant ?). Journal du 23/10 :
     * « tu vas dans le mauvais sens » (= pas au bon endroit) a fait inverser les roues, et il est reparti en reculant.
     * Sans vérification possible, on n'inverse qu'à la deuxième demande (confirmée).
     */
    private fun adjustWheelDirection(how: String): String {
        if (how == "normal") {
            setFlagValue("roomba_reversed", false)
            return "sens des roues : réglage d'origine (visage vers l'avant du Roomba)"
        }
        if (how != "inverse") return "pour sens_roues : « inverse » ou « normal »"
        val r = roomba
        if (r != null && flag("camera", true)) {
            val test = moveSync(holdMs = 1000, timeoutMs = 20_000) { selfTestDirection(r) }
            if (test.startsWith("sens : bon")) return "$test. Je n'ai RIEN changé. « Tu vas dans le mauvais sens » voulait sûrement " +
                "dire « pas au bon endroit » : demande où aller, ou tourne-toi (tourner_corps)."
            if (test.startsWith("sens : FAUX")) return test
        }
        val now = System.currentTimeMillis()
        if (now - wheelFlipAskedAt > 3 * 60_000L) {
            wheelFlipAskedAt = now
            return "pas pu vérifier avec la caméra, rien changé. Demande d'abord : « quand je dois avancer, est-ce que je pars " +
                "en arrière ? » ; ne rappelle cet outil que si on te répond oui."
        }
        wheelFlipAskedAt = 0L
        val rev = !flag("roomba_reversed", false)
        setFlagValue("roomba_reversed", rev)
        return "sens des roues inversé (confirmé) : " + if (rev) "visage vers l'arrière du Roomba" else "visage vers l'avant du Roomba"
    }

    /** Auto-test des angles : un quart de tour d'après les roues, mesuré par les capteurs du téléphone (sans nacelle). */
    private fun selfTestRotation(r: com.joey.app.robot.RoombaRobot): String {
        if (gimbal.ready) return "angles : pas mesurés (la nacelle stabilise le téléphone)"
        val h0 = sensors.read()?.first ?: return "angles : pas de capteur d'orientation, pas mesurés"
        r.turn(90.0)
        Thread.sleep(500)
        val h1 = sensors.read()?.first
        r.turn(-90.0)
        h1 ?: return "angles : mesure perdue"
        val measured = Head.wrap(h1 - h0)
        journal.write("roues", "auto-test des angles : 90° des roues = %.0f° mesurés".format(measured))
        if (measured < 45 || measured > 140) return "angles : mesure bizarre (%.0f°), rien changé".format(measured)
        val ratio = measured / 90.0
        if (kotlin.math.abs(ratio - 1) < 0.05) return "angles : justes (%.0f° pour 90°)".format(measured)
        val scale = (tuning.get("echelle_rotation") * ratio)
        tuning.set("echelle_rotation", scale)
        r.turnScale = tuning.get("echelle_rotation")
        return "angles : corrigés (%.0f° en vrai pour 90° annoncés)".format(measured)
    }

    /**
     * « Cartographie la maison » : il relève la pièce où il est (nommée d'après ce qu'il y voit : salon, chambre…),
     * part explorer ailleurs, relève la suivante… jusqu'à 5 pièces ou 8 minutes.
     */
    /**
     * Relève la pièce où il est (tour du regard avec Gemini) et la nomme d'après ce qu'il y voit : « chambre », ou
     * « chambre 2 » si une autre chambre est déjà ailleurs. Renvoie son nom, ou null si le relevé a échoué.
     */
    private fun mapAndNameHere(n: Int = 1): String? {
        val tmp = "pièce $n (provisoire)"
        mapHere(tmp)
        val p = houseMap.place(tmp) ?: return null
        var name = p.kind.ifBlank { "pièce" }
        val near = { q: com.joey.app.brain.HouseMap.Place -> q.x != null && p.x != null && q.frame == p.frame &&
            kotlin.math.hypot(q.x!! - p.x!!, q.y!! - p.y!!) < 2.5 }
        var k = 2
        while (houseMap.place(name)?.let { it.name != tmp && !near(it) } == true) name = "${p.kind} ${k++}"
        houseMap.put(name, p.kind, p.description, p.items, p.x, p.y, p.frame)
        houseMap.remove(tmp)
        currentPlace = name
        return name
    }

    /** Il est dans une pièce de sa carte qui n'a pas encore de nom (aucun lieu relevé dedans). */
    private fun inUnnamedRoom(): Boolean {
        val r = roomba ?: return false
        val rooms = nav?.rooms() ?: return false
        val here = com.joey.app.robot.Rooms.nearestRoom(rooms, r.x, r.y) ?: return false
        return houseMap.all().none { p -> p.frame == gridFrame && p.x != null && p.y != null && rooms.roomAt(p.x!!, p.y!!) == here }
    }

    private fun mapHouse(): String {
        val r = roomba ?: return mapHere("ici")
        val t0 = System.currentTimeMillis()
        val done = ArrayList<String>()
        for (i in 0 until 5) {
            if (System.currentTimeMillis() - t0 > 8 * 60_000) break
            log("🗺 Pièce ${i + 1}")
            mapAndNameHere(i + 1)?.let { name -> if (name !in done) done.add(name) }
            if (i == 4) break
            // Ailleurs : il part explorer un moment avant le relevé suivant.
            val e = explore(null, 45)
            if (e.startsWith("exploration arrêtée") || e.contains("coincé")) { log("🗺 $e"); break }
        }
        return if (done.isEmpty()) "relevé impossible" else
            "maison relevée : ${done.size} pièce(s) — ${done.joinToString()} (sur ta carte, avec leur position)"
    }

    // ---- rester joignable, même écran éteint ------------------------------------------------------------------------
    // 24/10 : écran éteint et verrouillé, le téléphone coupait son Wi-Fi ; Joey ne répondait plus au PC et la mise à jour
    // attendait. Tant que Joey tourne, le processeur et le Wi-Fi restent éveillés (l'installation automatique l'exempte
    // aussi de la mise en veille profonde d'Android). Sans alimentation : relâché sous 15 % de batterie, pour l'économiser.
    private var cpuLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    @Synchronized private fun setAwake(on: Boolean) {
        val ctx = getApplication<Application>()
        if (on) {
            if (cpuLock?.isHeld != true) {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                cpuLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "joey:eveil").apply { setReferenceCounted(false); acquire() }
            }
            if (wifiLock?.isHeld != true) {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "joey:wifi").apply { setReferenceCounted(false); acquire() }
            }
        } else {
            runCatching { if (cpuLock?.isHeld == true) cpuLock?.release() }
            runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        }
    }

    private fun awakeLoop() {
        var awake: Boolean? = null
        while (!cleared) {
            val want = phoneCharging() || phoneBattery() !in 0 until 15
            if (want != awake) {
                runCatching { setAwake(want) }.onFailure { log("Éveil : ${it.message}") }
                if (awake != null || !want) log(if (want) "📶 Je reste éveillé (Wi-Fi compris)" else "🔋 Batterie à ${phoneBattery()} % : je laisse le téléphone dormir")
                awake = want
            }
            Thread.sleep(60_000)
        }
    }

    // ---- mode autonome : quand on ne lui demande rien, il vit sa vie de robot ------------------------------------------
    @Volatile private var dockFailures = 0
    @Volatile private var lastDockFailAt = 0L
    @Volatile private var lastAutoExploreAt = System.currentTimeMillis() - 25 * 60_000L
    @Volatile private var lastAutoGreetAt = 0L
    private var frontiersCache = true
    private var frontiersAt = 0L

    /**
     * Rentre à sa base et vérifie qu'il y est vraiment (sur la base ou en charge), au lieu d'envoyer la commande et de
     * passer à autre chose. Raté : il recule, fait demi-tour et réessaie une fois ; raté encore, il prévient Michael et
     * attend de plus en plus longtemps avant de retenter (règle d'autonomie).
     */
    private fun dockAndVerify(): String {
        val r = roomba ?: return noWheels
        if (r.onBase || r.charging) { dockFailures = 0; return "déjà sur ma base" }
        for (attempt in 1..2) {
            val start = goHome()
            log("🏠 $start")
            if (start.startsWith("le Roomba ne répond pas")) break
            val end = System.currentTimeMillis() + 5 * 60_000L
            while (System.currentTimeMillis() < end && !cleared && roomba === r) {
                if (r.onBase || r.charging) {
                    dockFailures = 0
                    log("🏠 Je suis sur ma base (batterie des roues : ${r.battery ?: "?"} %)")
                    return "je suis sur ma base, en charge"
                }
                Thread.sleep(1000)
            }
            if (attempt == 1 && roomba === r) {
                log("🏠 Je n'ai pas trouvé ma base : je recule, je me retourne et je réessaie")
                runCatching { r.drive(-0.3); r.turn(180.0) }
            }
        }
        dockFailures++
        lastDockFailAt = System.currentTimeMillis()
        val wait = com.joey.app.life.AutonomyPolicy.dockBackoffMs(dockFailures) / 60_000
        log("🏠 Je n'arrive pas à rentrer à ma base ($dockFailures fois) : je réessaierai dans $wait min")
        watch.notify("🏠 Joey n'arrive pas à rentrer à sa base (roues : ${r.battery ?: "?"} %, téléphone : ${phoneBattery()} %). " +
            "Nouvel essai dans $wait min.", null)
        return "je n'ai pas réussi à rentrer à ma base"
    }

    /** Il reste des frontières sur sa carte (la maison n'est pas toute connue) ; recalculé au plus une fois par minute. */
    private fun frontiersLeft(): Boolean {
        val now = System.currentTimeMillis()
        if (now - frontiersAt > 60_000) {
            frontiersAt = now
            frontiersCache = nav?.frontiersLeft() ?: true
        }
        return frontiersCache
    }

    /**
     * Toutes les 10 s : la règle d'autonomie ([com.joey.app.life.AutonomyPolicy]) décide (se recharger, saluer,
     * explorer, se reposer), et il le fait.
     */
    private fun autonomyLoop() {
        var seenSince = 0L
        var lastLabel = ""
        while (!cleared) {
            Thread.sleep(10_000)
            runCatching {
                val r = roomba ?: return@runCatching
                val s = _state.value
                val now = System.currentTimeMillis()
                if (follower.sees) { if (seenSince == 0L) seenSince = now } else seenSince = 0L
                val on = flag("autonomy", false)
                val busy = s.busy || following || dancing || s.live == LiveState.SPEAKING
                val snap = com.joey.app.life.AutonomyPolicy.Snapshot(
                    now = now,
                    hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY),
                    autonomyOn = on, busy = busy, lastTalkAt = lastPersonTalkAt,
                    roombaBattery = r.battery, phoneBattery = phoneBattery(), phoneCharging = phoneCharging(),
                    onBase = r.onBase, charging = r.charging, mapFromBase = gridAnchored,
                    personSince = seenSince, lastGreetAt = lastAutoGreetAt, lastFaceSeenAt = lastFaceSeenAt,
                    lastExploreAt = lastAutoExploreAt,
                    frontiersLeft = if (on && !busy) frontiersLeft() else true,
                    lastDockFailAt = lastDockFailAt, dockFailures = dockFailures,
                )
                val a = com.joey.app.life.AutonomyPolicy.decide(snap)
                val label = a.toString()
                when (a) {
                    is com.joey.app.life.AutonomyPolicy.Action.None -> {}
                    is com.joey.app.life.AutonomyPolicy.Action.Rest -> if (label != lastLabel) log("🤖 Autonome : ${a.reason}")
                    is com.joey.app.life.AutonomyPolicy.Action.Charge -> {
                        log("🤖 Autonome : ${a.reason}, je rentre me recharger")
                        if (!com.joey.app.life.AutonomyPolicy.night(snap.hour))
                            remark("Dis en quelques mots que tu rentres te recharger à ta base (${a.reason}).", minGapMs = 0)
                        log("🔋 " + moveSync(holdMs = 1000, timeoutMs = 12 * 60_000L) { dockAndVerify() })
                    }
                    is com.joey.app.life.AutonomyPolicy.Action.Greet -> {
                        lastAutoGreetAt = now
                        log("🤖 Autonome : je vais voir qui est là")
                        val res = moveSync(holdMs = 3000, timeoutMs = 90_000) { comeHere() }
                        if (res.startsWith("je suis arrivé")) remark("Tu es allé de toi-même voir la personne qui vient d'arriver : " +
                            "salue-la, en quelques mots, avec ton caractère.", minGapMs = 0)
                    }
                    is com.joey.app.life.AutonomyPolicy.Action.Explore -> {
                        lastAutoExploreAt = now
                        val fromBase = r.onBase || gridAnchored
                        log("🤖 Autonome : j'explore ${a.seconds} s (${a.reason})" + if (fromBase) ", puis je rentre à ma base" else "")
                        missionStart = r.x to r.y
                        moveSync(holdMs = 1000, timeoutMs = (a.seconds + 15 * 60).toLong() * 1000) {
                            // Avec la profondeur : cartographie par tours d'horizon (carte nette) ; sinon, exploration simple.
                            val e = if (depth.available && flag("profondeur", true)) mapByScans(a.seconds) else explore(null, a.seconds)
                            // Une pièce nouvelle (sans nom sur sa carte) : il en fait le tour du regard et la nomme (Gemini).
                            if (canTurnGaze && flag("camera", true) && geminiKey.isNotBlank() && runCatching { inUnnamedRoom() }.getOrDefault(false)) {
                                log("🗺 Pièce nouvelle : je la relève")
                                mapAndNameHere()?.let { log("🗺 C'est : $it") }
                            }
                            val back = if (fromBase) dockAndVerify() else missionStart?.let { navigateTo(it.first, it.second) } ?: ""
                            missionStart = null
                            frontiersAt = 0L
                            log("🤖 $e ; retour : $back")
                            e
                        }
                    }
                }
                lastLabel = label
            }
        }
    }

    // ---- carte en grille, trajets ---------------------------------------------------------------------------------
    /** La navigation (carte tenue à jour, trajets) : créée quand les roues se branchent. */
    @Volatile private var nav: com.joey.app.robot.Navigator? = null
    private var gridSavedAt = 0L
    private var wasOnBase = false

    /** Posé sur sa base : la base devient l'origine, et on reprend la carte gardée (sinon : carte de la séance). */
    private fun anchorGrid(r: com.joey.app.robot.RoombaRobot) {
        if (r.onBase) {
            r.resetOdometry()
            if (!gridAnchored) { grid.clear(); grid.load(gridFile) }
            gridAnchored = true
            if (gridFrame != "base") personSeenAt.clear()
            gridFrame = "base"
            log("🗺 Sur ma base : ma carte part d'ici (%.0f m² connus)".format(grid.knownArea()))
        } else {
            // Branché ailleurs que sur sa base : ses roues repartent de zéro ici, c'est une nouvelle carte (celle gardée
            // part de la base : elle reviendra quand il y retournera).
            grid.clear()
            gridAnchored = false
            gridFrame = "seance-" + System.currentTimeMillis()
            personSeenAt.clear()
        }
        wasOnBase = r.onBase
        nav?.resetTrack()
    }

    /** 10 fois par seconde : là où il passe est libre ; revenu sur sa base, il recale sa position (fini la dérive). */
    private fun gridTick() {
        val r = roomba ?: return
        if (!r.linkOk) return
        if (r.onBase && !wasOnBase) anchorGrid(r)
        wasOnBase = r.onBase
        nav?.tick()
        val now = System.currentTimeMillis()
        if (gridAnchored && now - gridSavedAt > 30_000) { gridSavedAt = now; grid.save(gridFile) }
    }

    /** CLEAN : aspiration marche/arrêt ; SPOT : il vient vers toi ; DOCK : il rentre à sa base. */
    private fun roombaButton(r: com.joey.app.robot.RoombaRobot, b: String) {
        // Mode test : un appui sert à réveiller le Roomba, pas à commander Joey (24/10 : CLEAN pour le réveiller a lancé
        // l'aspiration).
        if (testQuiet) { log("🔘 Bouton $b du Roomba (ignoré : mode test)"); return }
        log("🔘 Bouton $b du Roomba")
        when (b) {
            "clean" -> thread(isDaemon = true) { log("🧹 " + r.vacuum(!r.vacuuming)) }
            "spot" -> moveAsync(holdMs = 3000) { comeHere() }
            "dock" -> moveAsync(holdMs = 1000) { goHome() }
        }
        live?.event("On vient d'appuyer sur le bouton ${b.uppercase()} de ton Roomba (" + when (b) {
            "clean" -> "ton aspiration " + (if (r.vacuuming) "s'arrête" else "démarre")
            "spot" -> "tu viens vers la personne"
            else -> "tu rentres à ta base"
        } + "). Réagis en trois mots au plus.", reply = true)
    }

    /** Distance parcourue d'après le résumé de drive() (« avancé de 0,70 m », « … après 0,35 m »). */
    private fun movedOf(res: String, asked: Double): Double =
        Regex("([0-9]+[,.][0-9]+) m").find(res)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()?.let { kotlin.math.abs(it) }
            ?: if (res.startsWith("avancé")) kotlin.math.abs(asked) else 0.0

    /**
     * Va au point (x, y) de sa carte par le plus court chemin connu : il suit les segments, et s'il touche quelque chose
     * (noté sur la carte), il se dégage et recalcule. [budget] : mètres au plus.
     */
    /** Va au point (x, y) de sa carte (voir [com.joey.app.robot.Navigator.navigateTo]) ; la tête regarde le sol devant. */
    private fun navigateTo(tx: Double, ty: Double, tries: Int = 8, budget: Double = 25.0): String {
        val n = nav ?: return noWheels
        return withRoadGaze { n.navigateTo(tx, ty, tries, budget) { attempt -> roadLegStart(attempt) } }
    }

    // ---- la tête regarde la route pendant qu'il roule (voir [com.joey.app.gimbal.RoadGaze]) ------------------------
    private fun <T> withRoadGaze(body: () -> T): T {
        if (!gimbal.ready || roomba == null) return body()
        return roadGaze.during(
            // Ni suivi de visage, ni recherche de qui parle pendant le trajet : la route d'abord.
            onStart = { follower.pause(Long.MAX_VALUE / 4) },
            onEnd = { follower.pausedUntil = System.currentTimeMillis() + 2000 },
            body = body,
        )
    }

    /** Dans la boucle de la tête (25 fois par seconde) : vise l'avant du corps pendant un trajet. */
    private fun roadAim() { if (gimbal.ready) roadGaze.tick() }

    /** Avant chaque essai d'un trajet : la tête se recentre après un dégagement et on attend qu'elle regarde devant. */
    private fun roadLegStart(attempt: Int) { if (gimbal.ready) roadGaze.legStart(attempt) }

    /**
     * Rentrer à la base : si sa carte part de la base (il y était au départ), il revient d'abord tout près par le plus
     * court chemin, puis le Roomba accoste avec ses balises. Sinon, le Roomba cherche sa base seul (plus long).
     */
    private fun goHome(): String {
        val r = roomba ?: return noWheels
        following = false
        if (r.onBase) return "déjà sur ma base"
        val n = nav
        if (gridAnchored && n != null && n.distanceTo(0.0, 0.0) > 1.0) {
            log("🏠 Je rentre à ma base par ma carte")
            // La base est à l'origine ; il s'arrête 50 cm devant, du côté où il en sort (le Roomba recule de ~30 cm en la
            // quittant), puis le Roomba accoste avec ses balises.
            val (ax, ay) = n.dockApproach()
            val res = navigateTo(ax, ay, tries = 6, budget = 30.0)
            log("🏠 $res")
        }
        return r.seekDock()
    }

    /** Où il a vu chacun pour la dernière fois (x, y sur la carte, heure). */
    private val personSeenAt = java.util.concurrent.ConcurrentHashMap<String, Triple<Double, Double, Long>>()

    /**
     * « Va voir Johanna » : si elle est en vue, il y va ; sinon il va là où il l'a vue la dernière fois (sa carte), et la
     * cherche autour ; sinon il explore pour la trouver.
     */
    private fun goSee(who: String): String {
        val r = roomba ?: return noWheels
        missionStart = r.x to r.y
        val key = who.trim().lowercase()
        if (key.isNotEmpty() && chooser.names().any { it.equals(who.trim(), true) }) {
            chooser.want(who)
            return comeHere()
        }
        val seen = personSeenAt[key]
        if (seen != null && System.currentTimeMillis() - seen.third < 6 * 3600_000L) {
            val d = kotlin.math.hypot(seen.first - r.x, seen.second - r.y)
            log("🗺 Je vais là où j'ai vu $who (il y a ${(System.currentTimeMillis() - seen.third) / 60_000} min)")
            if (d > 1.0) {
                val k = (d - 0.8) / d
                val nav = navigateTo(r.x + (seen.first - r.x) * k, r.y + (seen.second - r.y) * k)
                if (nav.startsWith("arrêté")) return "arrêté en route : $nav"
            }
            r.turn(Head.wrap(Math.toDegrees(kotlin.math.atan2(seen.second - r.y, seen.first - r.x)) - r.heading))
            Thread.sleep(800)
            if (key.isEmpty() || chooser.names().any { it.equals(who.trim(), true) } || follower.sees) {
                if (key.isNotEmpty()) chooser.want(who)
                return comeHere()
            }
            return "arrivé là où j'avais vu $who, mais $who n'y est plus" +
                (searchWithBody(r, setOf("person"))?.let { " ; il y a quelqu'un près de moi" } ?: "")
        }
        val e = explore("personne", 90)
        return if (e.startsWith("trouvé")) comeHere() else "je n'ai pas trouvé $who : $e"
    }

    /** Où il était avant sa dernière mission (pour « reviens »). */
    @Volatile private var missionStart: Pair<Double, Double>? = null

    // ---- explorer : partir à l'aventure (trouver une pièce, le chien, une porte…) -----------------------------------
    /** Une pièce se reconnaît à ce qu'il y a dedans (le détecteur du téléphone les connaît, sans quota). */
    private fun roomClue(goal: String): String? {
        val g = goal.lowercase()
        return when {
            "chambre" in g -> "lit"
            "cuisine" in g -> "frigo"
            "salle de bain" in g || "salle d'eau" in g -> "lavabo"
            "toilette" in g || "wc" in g -> "toilettes"
            "salon" in g || "séjour" in g -> "canapé"
            "bureau" in g -> "ordinateur"
            else -> null
        }
    }

    /**
     * Explore : il avance tout droit, contourne ce qu'il touche (se dégage et repart d'un autre côté), change de
     * direction de temps en temps, et regarde à chaque arrêt si [goal] est en vue : le détecteur du téléphone (gratuit)
     * pour ce qu'il connaît (une chambre = un lit…), sinon Gemini Robotics-ER une fois sur trois. Bloquant, [maxSec] au plus.
     */
    private fun explore(goal: String?, maxSec: Int = 120): String = withRoadGaze { exploreLoop(goal, maxSec) }

    private fun exploreLoop(goal: String?, maxSec: Int): String {
        val r = roomba ?: return noWheels
        following = false
        followGen.incrementAndGet()
        missionStart = missionStart ?: (r.x to r.y)
        val clue = goal?.let { roomClue(it) ?: it }
        val labels = clue?.let { CocoClasses.forTarget(it) }
        val t0 = System.currentTimeMillis()
        val area0 = grid.knownArea()
        var travelled = 0.0
        var sinceTurn = 0.0
        var stops = 0
        var stuckInRow = 0
        var doors = 0
        log("🧭 J'explore" + (goal?.let { " : je cherche $it" + (if (clue != it) " (un $clue)" else "") } ?: ""))
        roadLegStart(0) // la tête droit devant, vers le sol (voir les obstacles)
        var turned = false
        fun summary() = "%.1f m parcourus, %.0f m² de plus sur ma carte%s".format(travelled, grid.knownArea() - area0,
            if (doors > 0) ", $doors porte(s) passée(s)" else "")
        while (System.currentTimeMillis() - t0 < maxSec * 1000L) {
            // Le corps a tourné : la tête se recentre avant de regarder où aller (sinon l'image montre le côté).
            if (turned) { roadLegStart(1); turned = false }
            // 1. Regarder : le détecteur du téléphone (gratuit) pour ce qu'il connaît.
            if (clue != null && labels != null) {
                bestSeen(labels)?.let { seen ->
                    val ang = (seen.box.cx - 0.5) * hfovNow()
                    if (kotlin.math.abs(ang) > 8) r.turn(ang)
                    return "trouvé : « $goal » est devant toi (j'ai vu ${if (clue != goal) "un $clue" else clue}) ; ${summary()}"
                }
            }
            stops++
            // 2. Où aller : la vision (Robotics-ER) choisit un point du sol — une porte, un passage, vers l'inconnu.
            val jpeg = if (vision.available()) camera.zoomJpeg("tout") else null
            val wp = jpeg?.let { j -> runCatching { vision.waypoint(j, clue) }.onFailure { log("🧭 vision : ${it.message}") }.getOrNull() }
            val res: String
            var asked: Double
            if (wp != null) {
                if (wp.goalVisible && goal != null) {
                    centerOnImagePoint(wp.x, wp.y)
                    return "trouvé : « $goal » en vue" + distanceText(wp.distance) + " ; ${summary()}"
                }
                val img = camera.frame(3000)
                val bearing = imageToDegrees((wp.x * 2 - 1).toFloat(), 0f, img?.width ?: 4, img?.height ?: 3).first +
                    (if (gimbal.ready) bodyPan() ?: 0.0 else 0.0)
                if (wp.doorway) { doors++; log("🚪 Un passage : ${wp.description}") }
                // Ce que la vision voit de sol libre droit devant : noté sur la carte (sans y être passé).
                wp.clearAhead?.let { c -> if (c > 0.3) grid.markFreeRay(r.x, r.y, r.heading + (if (gimbal.ready) bodyPan() ?: 0.0 else 0.0), c.coerceAtMost(3.0)) }
                // Il roule vers ce point d'un seul mouvement (en courbe), plus loin qu'avant (moins d'arrêts pour regarder),
                // vite si la vision voit beaucoup de sol libre, prudemment sinon.
                asked = ((wp.distance ?: 1.2) - 0.2).coerceIn(0.4, 2.5)
                if (kotlin.math.abs(bearing) < 15) wp.clearAhead?.let { c -> asked = asked.coerceAtMost((c - 0.15).coerceAtLeast(0.3)) }
                val th = Math.toRadians(r.heading + bearing)
                val target = (r.x + asked * kotlin.math.cos(th)) to (r.y + asked * kotlin.math.sin(th))
                val open = (wp.clearAhead ?: 1.0).let { if (it > 1.5) 1.0 else if (it > 0.8) 0.75 else 0.5 }
                res = r.followPath(listOf(target), r.maxSpeed) { x, y, h -> minOf(open, grid.speedFactor(x, y, h).coerceAtLeast(0.6)) }
                    .let { if (it == "arrivé") "avancé de %.2f m".format(asked) else it }
            } else {
                // 3. Sans vision Gemini : exploration méthodique par les frontières de sa carte (bord du connu).
                val n = nav
                if (n != null && stops % 4 != 0) {
                    val before = r.x to r.y
                    val step = n.frontierStep(budget = 3.0) { attempt -> roadLegStart(attempt) }
                    travelled += kotlin.math.hypot(r.x - before.first, r.y - before.second)
                    if (step != null) {
                        if (step.startsWith("arrêté")) return "exploration arrêtée : $step (${summary()})"
                        continue
                    }
                }
                // 4. Sinon : tout droit, et on change de cap de temps en temps.
                asked = 0.7
                res = r.drive(asked)
            }
            val moved = movedOf(res, asked)
            travelled += moved; sinceTurn += moved
            when {
                res.contains("liaison") || res.contains("vide") || res.startsWith("je ne roule pas") || res == "interrompu" ->
                    return "exploration arrêtée : $res (${summary()})"
                res.startsWith("obstacle vu") || res.contains("obstacle vu devant") -> {
                    // Vu à temps : pas de choc, il part simplement d'un autre côté.
                    log("👁️ $res")
                    r.turn(if (stops % 2 == 0) 60.0 else -60.0)
                    sinceTurn = 0.0; turned = true
                }
                res.contains("obstacle") || res.contains("bloqué") -> {
                    if (++stuckInRow > 4) return "je n'arrive plus à avancer (coincé) : aide-moi (${summary()})"
                    log("🧭 ${r.unstick(stuckInRow - 1)}")
                    sinceTurn = 0.0; turned = true
                }
                else -> {
                    stuckInRow = 0
                    if (wp == null && sinceTurn > 2.5) { r.turn(if (stops % 2 == 0) 70.0 else -100.0); sinceTurn = 0.0; turned = true }
                }
            }
        }
        return (if (goal != null) "pas trouvé « $goal »" else "exploration finie") + " (${summary()})"
    }

    /** « Viens ici » sans nacelle : les roues font le cou (il tourne pour te trouver et te garder en face). */
    private fun comeHereWithBody(r: com.joey.app.robot.RoombaRobot): String = chase(r, untilClose = true)

    /** « Viens ici » : trouve la personne, se tourne vers elle, s'approche jusqu'à ~1 m. Bloquant. */
    private fun comeHere(): String {
        val r = roomba ?: return noWheels
        following = false
        followGen.incrementAndGet()
        if (!gimbal.ready) return comeHereWithBody(r)
        if (!follower.sees && !searchLocal("personne", setOf("person"), keepMs = 2500)) return "je ne vois personne autour de moi"
        follower.pausedUntil = 0 // le suivi garde la personne au centre pendant qu'il avance
        repeat(8) { step ->
            Thread.sleep(900) // le suivi centre la personne
            alignBodyWithHead()
            Thread.sleep(500)
            val seen = seenNow().filter { it.label == "person" && it.score >= 0.5 }.maxByOrNull { it.box.h }
            val faceBig = follower.sees && (faces.lastSize ?: 0f) >= 0.12f
            if (faceBig || (seen != null && seen.box.h >= 0.85)) return "je suis arrivé près de toi"
            if (seen == null && !follower.sees) return "je t'ai perdu de vue en route"
            // Distance estimée d'après la taille de la silhouette (1,7 m) ; on s'arrête à ~1 m.
            val d = seen?.let { 0.9 / it.box.h.coerceAtLeast(0.15) } ?: 1.5
            val res = r.drive((d - 1.0).coerceIn(0.15, 0.6))
            if (res.contains("obstacle") || res.contains("vide") || res.contains("interrompu")) return "je me suis arrêté : $res"
            if (step == 7) return "je me suis approché autant que j'ai pu"
        }
        return "je suis là"
    }

    /**
     * « Suis-moi », avec ou sans nacelle : le corps se tourne vers la personne (sans nacelle, c'est lui qui fait le
     * cou) et avance tant qu'elle est loin.
     * - Ordres lissés : il accélère et tourne en douceur (avant, il changeait d'avis toutes les 0,15 s et avançait
     *   « en tremblant », un coup en avant, un coup en arrière).
     * - L'image a toujours un peu de retard : on retire ce que le corps a déjà tourné depuis (odométrie), sinon il
     *   dépassait la cible et repartait dans l'autre sens.
     * - Perdue : il ralentit, puis tourne doucement du côté où il l'a vue (un tour au plus).
     */
    /** Un seul « suis-moi » à la fois (le journal en montrait trois en même temps, qui se battaient). */
    private val followGen = AtomicInteger()

    private fun followMe(minutes: Double) {
        val r = roomba ?: return
        val gen = followGen.incrementAndGet()
        if (gimbal.ready && bodyRef == null) calibrateBody()
        following = true
        trackName = "personne"
        trackLabels = setOf("person")
        trackUntil = System.currentTimeMillis() + (minutes * 60_000).toLong()
        follower.pausedUntil = 0
        _state.update { it.copy(tracking = "je te suis") }
        thread(name = "suivre", isDaemon = true) {
            try { chase(r, untilClose = false, gen = gen) } finally {
                if (gen == followGen.get()) {
                    following = false
                    trackUntil = 0
                    _state.update { it.copy(tracking = null) }
                }
            }
        }
    }

    /**
     * Le pilotage commun de « suis-moi » et « viens ici » : boucle de 0,1 s, voir [followMe].
     * [untilClose] : s'arrête et renvoie dès qu'il est tout près (viens ici) ; sinon tant que [following].
     */
    private fun chase(r: com.joey.app.robot.RoombaRobot, untilClose: Boolean, maxMs: Long = 60_000, gen: Int = followGen.get()): String {
            var unstuck = 0
            val start = System.currentTimeMillis()
            val person = setOf("person")
            val headings = ArrayDeque<Pair<Long, Double>>() // cap des roues dans le temps
            fun headingAt(t: Long) = headings.lastOrNull { it.first <= t }?.second ?: r.heading
            var seenAngle = 0.0  // où était la personne par rapport à l'avant du corps (+ = droite)…
            var seenHeading = r.heading // … et le cap du corps à ce moment-là
            var seenAt = 0L
            var seenH = 0.75
            var lastFrame = 0L
            var fwd = 0.0
            var yaw = 0.0
            var searchAt = 0L
            var searched = 0.0
            var prevHeading = r.heading
            try {
                while (roomba === r && gen == followGen.get() && (if (untilClose) System.currentTimeMillis() - start < maxMs
                        else following && System.currentTimeMillis() < trackUntil)) {
                    Thread.sleep(100)
                    val now = System.currentTimeMillis()
                    // Coincé : il se dégage tout seul (3 essais), puis demande de l'aide.
                    if (r.stuck) {
                        if (unstuck >= 3) {
                            log("🛞 Coincé, je n'arrive pas à me dégager")
                            remark("Tu es coincé et tu n'arrives pas à te dégager après 3 essais : demande de l'aide, en " +
                                "quelques mots.", minGapMs = 10_000)
                            return "coincé : j'ai essayé 3 fois de me dégager, il faut m'aider"
                        }
                        log("🛞 Coincé : je me dégage (essai ${unstuck + 1})")
                        r.unstick(unstuck++)
                        fwd = 0.0; yaw = 0.0
                        continue
                    }
                    headings.addLast(now to r.heading)
                    while (headings.size > 40) headings.removeFirst()
                    // Une nouvelle image : où est la personne ?
                    val frameAt = camera.frameAt
                    if (frameAt != lastFrame) {
                        lastFrame = frameAt
                        val p = bestSeen(person)
                        if (gimbal.ready && follower.sees) {
                            // La tête la suit déjà : l'angle de la tête par rapport au corps, en ce moment.
                            bodyPan()?.let { seenAngle = it; seenHeading = r.heading; seenAt = now; searchAt = 0L }
                            p?.let { seenH = it.box.h }
                        } else if (p != null) {
                            seenAngle = (p.box.cx - 0.5) * hfovNow() + (if (gimbal.ready) bodyPan() ?: 0.0 else 0.0)
                            seenHeading = headingAt(frameAt)
                            seenH = p.box.h
                            seenAt = now
                            searchAt = 0L
                        }
                    }
                    val lost = now - seenAt
                    when {
                        seenAt > 0 && lost < 1500 -> {
                            val a = Head.wrap(seenAngle - Head.wrap(r.heading - seenHeading))
                            val near = tuning.get("proximite")
                            if (untilClose && kotlin.math.abs(a) < 20 && (seenH >= near + 0.1 || (faces.lastSize ?: 0f) >= 0.12f))
                                return "je suis arrivé près de toi"
                            val wantYaw = if (kotlin.math.abs(a) < 6) 0.0 else (a * tuning.get("virage")).coerceIn(-70.0, 70.0)
                            // Caméra au ras du sol : quelqu'un de proche remplit l'image. Il avance tant que la silhouette
                            // est petite, s'arrête quand elle remplit l'image, ne recule que si on est tout contre lui.
                            var wantFwd = when {
                                seenH < near -> ((near - seenH) * 0.9).coerceIn(0.06, r.maxSpeed)
                                seenH > 0.97 && (faces.lastSize ?: 0f) > 0.25f -> -0.05
                                else -> 0.0
                            }
                            // Bien de côté : d'abord se tourner, puis avancer.
                            wantFwd *= (1 - kotlin.math.abs(a) / 35).coerceIn(0.0, 1.0)
                            fwd += (wantFwd - fwd) * 0.3
                            yaw += (wantYaw - yaw) * 0.45
                            r.velocity(fwd, yaw, 400)
                        }
                        seenAt > 0 && lost < 3000 -> {
                            fwd *= 0.6; yaw *= 0.6
                            r.velocity(fwd, yaw, 300)
                        }
                        else -> {
                            if (searchAt == 0L) { searchAt = now; searched = 0.0; prevHeading = r.heading; log("🛞 Je te cherche en tournant") }
                            searched += kotlin.math.abs(Head.wrap(r.heading - prevHeading))
                            prevHeading = r.heading
                            if (searched > 360 || now - searchAt > 15_000) {
                                log("🛞 Je t'ai perdu")
                                return if (seenAt == 0L) "je ne vois personne autour de moi (j'ai fait un tour sur moi-même)"
                                else "je t'ai perdu de vue en route"
                            }
                            fwd = 0.0
                            yaw += ((if (seenAngle < 0) -45.0 else 45.0) - yaw) * 0.4
                            r.velocity(0.0, yaw, 300)
                        }
                    }
                }
            } finally {
                r.velocity(0.0, 0.0, 100)
            }
            return if (untilClose) "je me suis approché autant que j'ai pu" else "fini de te suivre"
    }

    // ---- manette (Xbox…) : on le pilote à la main -------------------------------------------------------------
    private val pad = com.joey.app.remote.Gamepad()
    @Volatile private var padLoop: Thread? = null
    @Volatile private var padWheels = false
    @Volatile private var padHead = false
    @Volatile private var padSeen = false

    private fun padHello() {
        if (padSeen) return
        padSeen = true
        log("🎮 Manette : stick gauche = rouler (RT = plus vite), stick droit = tête, A = aspiration, B = stop, " +
            "Y = tête droite, X = parler, LB = base")
    }

    /** Les axes de la manette ont bougé. */
    fun padAxes(lx: Float, ly: Float, rx: Float, ry: Float, rt: Float) {
        padHello()
        pad.axes(lx, ly, rx, ry, rt)
        if (padLoop == null) synchronized(pad) {
            if (padLoop == null) padLoop = thread(name = "manette", isDaemon = true) { padTick() }
        }
    }

    /** Toutes les 80 ms : la manette conduit les roues et la tête tant qu'un stick est poussé. */
    private fun padTick() {
        while (!cleared) {
            try {
                Thread.sleep(80)
                val d = pad.drive()
                val r = roomba
                if (r != null && !d.idle) {
                    if (!padWheels) {
                        // On reprend la main : il arrête ce qu'il faisait tout seul (suivre, venir, avancer).
                        padWheels = true
                        following = false
                        wheelGen.incrementAndGet()
                        if (_state.value.busy) motionJob?.cancel(true) // « viens ici », « va vers »… en cours
                        r.stop()
                    }
                    r.manual(d.forward, d.yaw)
                } else if (padWheels) {
                    padWheels = false
                    r?.manual(0.0, 0.0)
                }
                val (hx, hy) = pad.head()
                if (hx != 0f || hy != 0f) { padHead = true; manualStick(hx, hy) }
                else if (padHead) { padHead = false; manualStick(0f, 0f) }
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                log("🎮 Manette : ${e.message}")
            }
        }
    }

    /** Un bouton de la manette. */
    private val padPressedAt = HashMap<com.joey.app.remote.Gamepad.Button, Long>()

    fun padButton(b: com.joey.app.remote.Gamepad.Button) {
        padHello()
        // Un appui = une fois (le journal montrait l'aspiration marche/arrêt/marche/arrêt en moins d'une seconde).
        val now = System.currentTimeMillis()
        synchronized(padPressedAt) {
            if (now - (padPressedAt[b] ?: 0L) < 400) return
            padPressedAt[b] = now
        }
        val r = roomba
        when (b) {
            com.joey.app.remote.Gamepad.Button.A -> thread(isDaemon = true) {
                if (r == null) { log("🎮 Pas de roues branchées : pas d'aspiration"); return@thread }
                val res = r.vacuum(!r.vacuuming)
                log("🧹 $res")
                if (r.vacuuming) r.sing(com.joey.app.robot.RoombaRobot.Tune.YES) else r.sing(com.joey.app.robot.RoombaRobot.Tune.NO)
            }
            com.joey.app.remote.Gamepad.Button.B -> {
                following = false
                wheelGen.incrementAndGet()
                motionJob?.cancel(true)
                head.stop()
                thread(isDaemon = true) { r?.stop(); if (r?.vacuuming == true) r.vacuum(false) }
                log("🎮 Stop")
            }
            com.joey.app.remote.Gamepad.Button.Y -> recenter()
            com.joey.app.remote.Gamepad.Button.X -> { log("🎮 X : " + (if (live != null) "fin de la conversation" else "je t'écoute")); micPressed() }
            com.joey.app.remote.Gamepad.Button.LB -> thread(isDaemon = true) {
                following = false
                log("🎮 " + (if (r == null) noWheels else goHome()))
            }
            else -> {}
        }
    }

    // ---- carte de la maison (Gemini Robotics-ER) -------------------------------------------------
    /**
     * Relève le lieu où Joey se trouve : tour du regard en 6 arrêts (60°), à chaque arrêt Robotics-ER pointe les éléments
     * et estime leur distance ; on les place au cap boussole. Bloquant (~30 s).
     */
    private fun mapHere(name: String): String {
        log("🗺 Je relève : $name" + if (!gimbal.ready) " (en tournant sur moi-même)" else "")
        if (gimbal.ready) head.setHomeHere()
        val pan0 = gazePan()
        val compass0 = sensors.compass()
        val (hfov, vfov) = camera.frame(5000)?.let { camera.fov(it.width, it.height) } ?: (camera.fovLong to camera.fovShort)
        val items = ArrayList<com.joey.app.brain.HouseMap.Item>()
        val kinds = ArrayList<String>()
        val descs = ArrayList<String>()
        var errors = 0
        for (k in 0 until 6) {
            val target = k * 60.0
            val cur = Head.wrap(gazePan() - pan0)
            turnGaze(Head.wrap(target - cur), -5.0)
            Thread.sleep(700)
            val pose = if (gimbal.ready) head.pose() ?: (target to -5.0) else Head.wrap(gazePan() - pan0) to 0.0
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
                // Sa position sur la carte en grille (seulement si la carte est gardée : repère = sa base).
                val rr = roomba
                val xy = if (rr != null && gridAnchored && dist != null && dist < 6) {
                    val bearing = rr.heading + (if (gimbal.ready) bodyPan() ?: pose.first else 0.0) + off
                    (rr.x + dist * kotlin.math.cos(Math.toRadians(bearing))) to (rr.y + dist * kotlin.math.sin(Math.toRadians(bearing)))
                } else null
                items.add(com.joey.app.brain.HouseMap.Item(nom.trim(), stopHeading?.let { (it + off + 360) % 360 }, elev, dist, now,
                    xy?.first, xy?.second))
            }
            log("🗺 vue ${k + 1}/6 : ${sv.elements.size} éléments")
        }
        // Retour au départ.
        turnGaze(Head.wrap(-(gazePan() - pan0)), 0.0)
        if (items.isEmpty()) return "relevé impossible ($errors erreurs de vision)"
        // Un même élément vu sur deux arrêts voisins : on n'en garde qu'un.
        val kept = ArrayList<com.joey.app.brain.HouseMap.Item>()
        for (it in items) {
            if (kept.none { k -> k.name.equals(it.name, true) && (k.heading == null || it.heading == null ||
                    kotlin.math.abs(Head.wrap(k.heading - it.heading)) < 25) }) kept.add(it)
        }
        val kind = kinds.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "pièce"
        houseMap.put(name, kind, descs.joinToString(" ").take(700), kept, roomba?.x, roomba?.y, roomba?.let { gridFrame })
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
        photosToday++
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
        } catch (e: java.util.concurrent.TimeoutException) {
            // Trop long : on arrête vraiment (sinon il continuait à rouler après avoir répondu « » à Gemini).
            job.cancel(true)
            roomba?.stop()
            "pas fini à temps (${timeoutMs / 1000} s) : je me suis arrêté"
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

    fun recenter() = background("centrer") {
        // Comme avant un trajet : le suivi du visage ne tire pas la tête pendant qu'elle se recentre, puis on reprend le repère.
        if (roomba != null && calibrateBody()) { head.look(null, 0.0, timeoutMs = 1500); "tête droite devant" } else head.center()
    }
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
            endTestScreen() // on lui parle : il reprend son visage
            if (live == null && geminiKey.isNotBlank()) {
                startedByWake = true
                thread(isDaemon = true) {
                    Thread.sleep(150) // le temps que la reconnaissance rende le micro
                    // On écoute encore un instant avec les deux micros : d'où vient la voix ?
                    val (side, conf) = if (!follower.sees && gimbal.ready) com.joey.app.audio.SoundDirection.listen(900) else (0 to 0.0)
                    startLive()
                    // Il ne voit personne : il cherche tout de suite qui l'appelle.
                    if (!follower.sees && gimbal.ready && flag("camera", true)) {
                        lastAutoSearch = System.currentTimeMillis()
                        moveAsync(holdMs = 0) { findCaller(if (conf >= 0.4) side else 0) }
                    }
                }
            }
        },
        log = ::log)

    /** Le mot de réveil écoute quand il n'y a pas de conversation (et que c'est activé). */
    fun updateWake() {
        if (cleared) { wake.stop(); return }
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
        if (cleared) return
        if (testQuiet) { log("🔇 Mode test : pas de conversation (dis « Joey » ou cmd visage pour en sortir)"); return }
        convStarts++
        wake.stop()
        lastTalkAt = System.currentTimeMillis()
        greeted = false
        val robot = flag("robot_voice", true)
        val l = GeminiLive(
            context = getApplication(),
            apiKey = geminiKey,
            network = { null },
            eyes = camera,
            systemPrompt = JoeyPrompt.SYSTEM + (if (robot) JoeyPrompt.ROBOT_STYLE else "") + memory.promptText() + tuning.promptText(),
            tools = GeminiLive.toJson(JoeyPrompt.TOOLS) as JSONArray,
            onTool = ::onTool,
            onTranscript = ::onTranscript,
            onTurnEnd = { lastSpeaker = null; flushTurn() },
            onState = ::onLiveState,
            onError = { e -> log(e); message(e) },
            bargeIn = flag("barge_in", true),
            robot = { flag("robot_voice", true) },
            micOptional = headsetAudioClients > 0,
            voiceName = voiceName().takeIf { it != "defaut" },
        )
        // Babil : le Roomba bipe au rythme de sa voix (un peu après, le temps que le son sorte du haut-parleur).
        val babble = com.joey.app.audio.Babble(24_000)
        l.onSpeechChunk = { chunk ->
            val r = roomba
            if (r != null && flag("roomba_babble", true)) {
                val notes = babble.feed(chunk)
                notes.forEachIndexed { i, n -> babbleTimer.schedule({ r.chirp(n) }, 120L + i * 150L, TimeUnit.MILLISECONDS) }
            }
        }
        live = l
        var stereoSeen = false
        l.onStereo = { left, right, n ->
            if (!stereoSeen) { stereoSeen = true; log("🎙️ Micro de la conversation en stéréo (${stereoDir.describe()})") }
            stereoDir.feed(left, right, n)
        }
        l.onMicPcm = { pcm, n ->
            voices.feed(pcm, n)
            // Bruit fort et soudain : pas pendant qu'il parle ni juste après (il s'entendrait lui-même), ni quand sa tête
            // bouge (les moteurs de la nacelle), ni si on l'a désactivé.
            val quietNow = _state.value.live == LiveState.LISTENING && System.currentTimeMillis() - lastSpeakingAt > 2000 &&
                !head.moving && flag("startle", true)
            if (quietNow && n > 0) {
                var acc = 0.0
                for (i in 0 until n) acc += pcm[i].toDouble() * pcm[i]
                val db = 20 * kotlin.math.log10(kotlin.math.sqrt(acc / n).coerceAtLeast(1.0) / 32768.0)
                if (liveStartle.feed(db)) startled()
            }
            if (dancing) beatDetector.feed(pcm, n) { beats++ }
        }
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

    @Volatile private var lastLatencyMark = 0L
    @Volatile private var lastSpeakingAt = 0L
    @Volatile private var lastJournalState: LiveState? = null

    private fun onLiveState(s: LiveState) {
        _state.update { it.copy(live = s) }
        if (s == LiveState.LISTENING && !greeted) {
            greeted = true
            val who = seeing()
            who?.let { greetedAt[it] = System.currentTimeMillis() }
            val pending = pendingEvent
            pendingEvent = null
            if (pending != null) { live?.event(pending, reply = true); return }
            live?.event((if (startedByWake) "On vient de t'appeler par ton nom (« Joey »). Réponds présent, brièvement. "
                else "La conversation commence (la personne vient d'appuyer sur ton bouton). ") +
                (if (who != null) "Tu reconnais $who (reconnaissance du visage). " else "") +
                "Ton humeur en ce moment : ${temperament.describe()}. " +
                "Salue en une phrase courte, à ta façon.", reply = true)
        }
        if (s == LiveState.SPEAKING || _state.value.live == LiveState.SPEAKING) lastSpeakingAt = System.currentTimeMillis()
        if (s == LiveState.SPEAKING) {
            val now = System.currentTimeMillis()
            if (lastPersonTalkAt > lastLatencyMark && now - lastPersonTalkAt < 30_000) {
                lastLatencyMark = lastPersonTalkAt
                journal.write("mesure", "réponse ${now - lastPersonTalkAt} ms après tes derniers mots")
            }
            lastTalkAt = now
        }
        if (s != lastJournalState) { journal.write("etat", "conversation : $s"); lastJournalState = s }
        if (s == LiveState.OFF) {
            flushTurn()
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

    /**
     * Ménage dans les souvenirs : Gemini repère les doublons, les banalités, l'état passager et ce qui est dépassé ;
     * l'app applique (avec une copie de l'ancienne mémoire). Bloquant.
     */
    fun cleanMemories(): String {
        val notes = memory.all()
        if (notes.size < 8) return "rien à ranger"
        val list = notes.mapIndexed { i, n -> "$i. (${java.text.SimpleDateFormat("d MMM yyyy", Locale.FRENCH).format(Date(n.time))}) ${n.text}" }
            .joinToString("\n")
        val schema = org.json.JSONObject("""
        {"type":"object","properties":{
          "supprimer":{"type":"array","items":{"type":"integer"},"description":"numéros des souvenirs à supprimer"},
          "fusions":{"type":"array","items":{"type":"object","properties":{
            "remplace":{"type":"array","items":{"type":"integer"}},"par":{"type":"string"}},"required":["remplace","par"]}}
        },"required":["supprimer","fusions"]}
        """.trimIndent())
        val prompt = "Voici la mémoire de Joey, un petit robot de maison (numérotée) :\n$list\n\nFais le ménage, prudemment : " +
            "supprime les banalités (« on a discuté »), l'état passager de Joey (nacelle connectée, roues, batterie, Bluetooth, " +
            "ce qu'il regardait), les événements sans intérêt durable, et ce qui est contredit par un souvenir plus récent. " +
            "Fusionne les doublons en une phrase claire. GARDE tout ce qui est durable et utile : prénoms et qui est qui, " +
            "animaux et leurs noms, goûts, habitudes, où sont les choses, projets, promesses, rendez-vous. Dans le doute, garde."
        val j = vision.json(prompt, schema)
        val remove = HashSet<Int>()
        j.optJSONArray("supprimer")?.let { a -> for (i in 0 until a.length()) remove.add(a.optInt(i, -1)) }
        val merges = ArrayList<Pair<Set<Int>, String>>()
        j.optJSONArray("fusions")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val g = HashSet<Int>(); o.optJSONArray("remplace")?.let { r -> for (k in 0 until r.length()) g.add(r.optInt(k, -1)) }
                merges.add(g to o.optString("par"))
            }
        }
        // Garde-fou : jamais plus de la moitié d'un coup.
        if (remove.size > notes.size / 2) return "ménage refusé (trop de suppressions proposées)"
        val (gone, merged) = memory.applyCleanup(notes, remove, merges)
        refreshSettings()
        log("🧹 Souvenirs rangés : $gone retirés ou fusionnés, $merged fusions (${memory.size} gardés)")
        return "souvenirs rangés"
    }

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
                    "Pas de banalités (« on a discuté »), rien de ce qui est déjà connu, rien d'inventé. Jamais l'état passager " +
                    "de Joey (nacelle connectée ou non, Bluetooth, roues branchées, batterie, ce qu'il regarde) : c'est faux le " +
                    "lendemain. Liste vide si rien."
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
    @Volatile private var lastSeenPan: Double? = null
    @Volatile private var lastSeenAt = 0L

    /**
     * Retrouve la personne qui l'appelle : là où il l'a vue la dernière fois (moins de 15 min), sinon un tour complet en
     * commençant du côté d'où venait la voix ([side] : -1 gauche, +1 droite, 0 inconnu). Avec les roues, s'approche si
     * elle est loin. Apprend aussi dans quel sens sont câblés les micros. Bloquant (fil des mouvements).
     */
    private fun findCaller(side: Int, oriented: Boolean = false): String {
        if (follower.sees) return "je te vois déjà"
        val startPan = head.pose()?.first ?: 0.0
        var found = false
        // 1. Là où il l'a vue la dernière fois.
        val last = lastSeenPan
        val cur = head.absPan()
        if (last != null && cur != null && System.currentTimeMillis() - lastSeenAt < 15 * 60_000L) {
            val delta = Head.wrap(last - cur)
            if (kotlin.math.abs(delta) > 10) {
                log("👀 Je regarde là où je t'ai vu")
                trackLabels = setOf("person"); trackName = "personne"; trackUntil = System.currentTimeMillis() + 60_000
                follower.forget()
                head.look(delta, lastSeenTilt)
                val t = System.currentTimeMillis()
                Thread.sleep(700)
                found = follower.seenSince(t)
            }
        }
        // 2. Un tour complet, en commençant du côté de la voix (ou du dernier endroit vu).
        if (!found) {
            val micSign = prefs.getInt("mic_side_sign", 1)
            val dir = when {
                side != 0 -> if (oriented) side else side * micSign
                last != null && cur != null -> if (Head.wrap(last - cur) >= 0) 1 else -1
                else -> 1
            }
            if (side != 0) log("👂 Ta voix venait plutôt de ${if (dir > 0) "ma droite" else "ma gauche"}")
            found = searchLocal("personne", setOf("person"), keepMs = 2500, firstDir = dir)
        } else {
            trackUntil = System.currentTimeMillis() + 2500
        }
        if (!found) return "je ne te trouve pas"
        // Le côté de la voix était-il le bon ? (on apprend le câblage des micros : il peut être inversé)
        if (side != 0 && !oriented) {
            Thread.sleep(800)
            val moved = Head.wrap((head.pose()?.first ?: startPan) - startPan)
            if (kotlin.math.abs(moved) in 25.0..155.0) {
                val ok = (if (moved > 0) 1 else -1) == side * prefs.getInt("mic_side_sign", 1)
                val misses = if (ok) 0 else prefs.getInt("mic_misses", 0) + 1
                prefs.edit().putInt("mic_misses", if (misses >= 2) 0 else misses).apply()
                if (misses >= 2) {
                    prefs.edit().putInt("mic_side_sign", -prefs.getInt("mic_side_sign", 1)).apply()
                    log("👂 Micros : gauche et droite inversés (corrigé)")
                }
            }
        }
        // 3. Loin, et avec des roues : il vient.
        if (roomba != null) {
            Thread.sleep(600)
            val p = seenNow().filter { it.label == "person" && it.score >= 0.5 }.maxByOrNull { it.box.h }
            val faceSmall = (faces.lastSize ?: 0f) in 0.001f..0.06f
            if ((p != null && p.box.h < 0.45) || faceSmall) {
                log("🛞 Tu es loin : j'arrive")
                return "trouvé, et " + comeHere()
            }
        }
        return "trouvé"
    }

    /** On lui parle et il ne voit personne : il cherche du regard, tout seul (au plus toutes les 20 s). */
    /** Il regarde ce qu'il vient de trouver : pas de recherche de qui parle jusque-là. */
    @Volatile private var showingFoundUntil = 0L
    /**
     * On guide son regard (« regarde à droite… encore… ») : il ne repart pas tout seul vers la personne entre deux
     * consignes. (Journal du 23/10 : « plus à droite » partait de là où il avait cherché Michael, pas de là où on l'avait mis.)
     */
    @Volatile private var gazeDirectedUntil = 0L

    private fun maybeSearchSpeaker() {
        val now = System.currentTimeMillis()
        if (now < showingFoundUntil || now < follower.pausedUntil || now < gazeDirectedUntil) return
        if (!gimbal.ready || !follower.enabled || !flag("camera", true) || follower.sees) return
        // On ne cherche que si personne n'est vu depuis un moment (sinon la tête bouge « toute seule » sans raison).
        if (_state.value.busy || now - follower.seenAt < 8000 || now - lastAutoSearch < 30_000) return
        lastAutoSearch = now
        // Les deux micros disent de quel côté vient la voix (s'ils sont réglés) : il commence par là.
        val (side, conf) = stereoDir.side()
        log("👀 on me parle, je cherche qui" + if (conf >= 0.35 && side != 0) " (voix ${if (side > 0) "à ma droite" else "à ma gauche"})" else "")
        moveAsync(holdMs = 0) { findCaller(if (conf >= 0.35) side else 0, oriented = true) }
    }

    private val turnText = StringBuilder()
    @Volatile private var turnPerson: Boolean? = null

    /** Écrit la réplique en cours dans le journal (quand l'autre prend la parole ou que le tour se termine). */
    private fun flushTurn() = synchronized(turnText) {
        val who = turnPerson ?: return@synchronized
        val t = turnText.toString().trim()
        if (t.isNotEmpty()) journal.write(if (who) "toi" else "joey", t)
        turnText.setLength(0)
        turnPerson = null
    }

    @Volatile private var lastSpokenMoodAt = 0L

    /**
     * Ses expressions suivent ce qu'il dit (au lieu d'un outil, que le modèle avait tendance à lire à voix haute) :
     * « oui » au début -> hochement, « non » -> non de la tête, « hmm » -> hésitation ; « oh ! » -> étonné, « haha » -> amusé…
     */
    private fun expressFromSpeech(chunk: String, turnStart: Boolean) {
        val t = chunk.lowercase()
        val now = System.currentTimeMillis()
        if (turnStart) {
            val first = t.trimStart().take(14)
            when {
                Regex("^(oui|ouais|d'accord|exactement|bien sûr|absolument|carrément)").containsMatchIn(first) -> expressive.cue(com.joey.app.life.Expressive.Cue.YES)
                Regex("^(non|pas du tout|jamais|hors de question)").containsMatchIn(first) -> expressive.cue(com.joey.app.life.Expressive.Cue.NO)
                Regex("^(hm+|euh|bof|peut-être)").containsMatchIn(first) -> expressive.cue(com.joey.app.life.Expressive.Cue.HMM)
            }
        }
        if (now - lastSpokenMoodAt < 3000) return
        val m = when {
            Regex("ha ?ha|hi ?hi|hé hé|mdr|trop drôle|rigol").containsMatchIn(t) -> Mood.AMUSE
            Regex("\\b(oh|ah|waouh|wow|ouah|incroyable|sérieux|quoi) ?!").containsMatchIn(t) -> Mood.ETONNE
            Regex("désolé|dommage|triste|snif|pauvre").containsMatchIn(t) -> Mood.TRISTE
            Regex("grr|agaçant|énervé|ça m'énerve|pas content").containsMatchIn(t) -> Mood.FACHE
            Regex("super|génial|bravo|trop bien|content|chouette|youpi|cool").containsMatchIn(t) -> Mood.CONTENT
            Regex("suspect|bizarre|louche|mouais").containsMatchIn(t) -> Mood.MEFIANT
            Regex("fatigué|bâill|dodo|crevé").containsMatchIn(t) -> Mood.FATIGUE
            else -> null
        } ?: return
        lastSpokenMoodAt = now
        setMood(m, sound = false)
    }

    /** Ce que la personne a dit depuis la dernière réponse de Joey (pour reconnaître du bruit). */
    private val heardTurn = StringBuilder()

    private fun isNoise(t: String) = com.joey.app.audio.NoiseFilter.isNoise(t)

    private fun onTranscript(person: Boolean, text: String) {
        if (person) {
            synchronized(heardTurn) { if (turnPerson == false) heardTurn.setLength(0); heardTurn.append(text) }
            // Du bruit : ça ne compte pas comme quelqu'un qui parle (pas de recherche, pas de « je réfléchis »).
            if (isNoise(synchronized(heardTurn) { heardTurn.toString() })) {
                synchronized(turnText) {
                    if (turnPerson != null && turnPerson != person) flushTurn()
                    turnPerson = person; turnText.append(text)
                }
                return
            }
        } else {
            // Il commence à répondre à un tour qui n'était que du bruit : on coupe sa réponse.
            val heard = synchronized(heardTurn) { heardTurn.toString() }
            if (turnPerson == true && heard.isNotBlank() && isNoise(heard)) {
                log("🔇 Bruit pris pour de la parole (« ${heard.take(30)} ») : je ne réponds pas")
                live?.dropTurn()
                synchronized(heardTurn) { heardTurn.setLength(0) }
                return
            }
            synchronized(heardTurn) { heardTurn.setLength(0) }
        }
        if (!person) expressFromSpeech(text, turnStart = turnPerson != false)
        synchronized(turnText) {
            if (turnPerson != null && turnPerson != person) flushTurn()
            turnPerson = person
            turnText.append(text)
        }
        lastTalkAt = System.currentTimeMillis()
        if (person) lastPersonTalkAt = lastTalkAt else lastJoeyTalkAt = lastTalkAt
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
    // ---- outils longs en arrière-plan : la conversation continue pendant qu'il cherche ou se déplace ----------
    /** Outils qui peuvent durer (recherche, déplacement, relevé) : Gemini n'attend pas, il reçoit le résultat après. */
    private val longTools = setOf("chercher", "aller_vers", "viens_ici", "cartographier", "regarder_ce_que_je_montre", "tester_roues",
        "explorer", "se_degager", "aller_a", "revenir", "aller_voir", "rentrer_base", "ajuster")
    private val toolPool = Executors.newCachedThreadPool()
    @Volatile private var longToolGen = 0
    /** L'outil long en cours (nom + paramètres). */
    @Volatile private var runningLong: String? = null
    /** Des actions (bouger, regarder, allumer…) : refusées si elles ne viennent que de bruit. « arrete » passe toujours. */
    private val actionTools = setOf("regarder", "chercher", "tester_roues", "dormir", "regarder_autour", "maison_commande",
        "avancer", "tourner_corps", "viens_ici", "suis_moi", "aller_vers", "aller_a", "rentrer_base", "cartographier",
        "mode_garde", "danser", "prendre_photo", "ajuster", "se_degager", "explorer", "mode_autonome", "aller_voir", "revenir",
        "changer_voix", "aspirer")

    private fun onTool(name: String, args: Map<String, Any?>): Map<String, Any?> {
        val heard = synchronized(heardTurn) { heardTurn.toString() }
        flushTurn()
        val t0 = System.currentTimeMillis()
        journal.write("outil", "$name ${args}")
        // Une action déclenchée par du bruit (télé, voix lointaine en coréen…) : personne ne l'a demandée.
        // (Journal du 23/10 : « <noise> » -> demi-tour, « 가 가 » -> exploration relancée.)
        if (name in actionTools && heard.isNotBlank() && isNoise(heard)) {
            log("🔇 « $name » ignoré : déclenché par du bruit (« ${heard.take(30)} »)")
            return mapOf("result" to "personne ne t'a rien demandé : ce que tu as entendu n'était que du bruit (« ${heard.take(40)} »). " +
                "Ne fais rien et ne réponds pas.").also { journal.write("outil", "$name -> $it") }
        }
        // La même demande, encore en cours : on ne la relance pas (sinon elle repartait de zéro).
        val key = name + args.toString()
        if (name in longTools && runningLong == key) {
            return mapOf("result" to "déjà en cours (même demande) : attends son résultat, ne relance pas l'outil")
                .also { journal.write("outil", "$name -> $it") }
        }
        if (name in wheelTools) return wheelTool(name, args, t0)
        if (name !in longTools) return runTool(name, args).also {
            journal.write("outil", "$name -> $it (${System.currentTimeMillis() - t0} ms)")
        }
        val gen = ++longToolGen
        runningLong = key
        val job = toolPool.submit<Map<String, Any?>> { try { runTool(name, args) } finally { if (runningLong == key) runningLong = null } }
        // Réponse rapide (refus, déjà vu…) : on la donne tout de suite. Pas plus de 0,4 s : au-delà, Gemini attendait
        // pour rien avant de dire « j'arrive » (réponses à 4-5 s dans le journal du 23/10).
        runCatching { return job.get(400, TimeUnit.MILLISECONDS) }
        thread(name = "resultat-$name", isDaemon = true) {
            val r = runCatching { job.get() }.getOrElse { mapOf("result" to "interrompu") }
            journal.write("outil", "$name -> $r (${System.currentTimeMillis() - t0} ms, en arrière-plan)")
            // Une autre demande est arrivée entre-temps : ce résultat est périmé, on ne le dit pas (sinon « je n'ai pas
            // trouvé le chien » arrivait au milieu de la recherche de la télé).
            if (gen == longToolGen) deliverLater(name, args, r)
        }
        return mapOf("result" to "C'est lancé (ça prend quelques secondes). Le résultat t'arrivera tout seul dans un message de " +
            "l'application. En attendant, ne commente pas (au plus « je regarde »), réponds normalement si on te parle, et ne " +
            "dis pas que c'est trouvé ou fait avant d'avoir reçu le résultat.")
    }

    // ---- roues : Joey dit « j'avance » pendant qu'il roule, au lieu d'attendre la fin du trajet pour parler ----------
    private val wheelTools = setOf("avancer", "tourner_corps")
    /** Voix graves essayées (hauteur mesurée), et celle d'origine. */
    val VOICES = listOf("Algenib", "Charon", "Orus", "Fenrir", "Iapetus", "Alnilam", "defaut")
    /** Un seul déplacement à la fois, dans l'ordre demandé (« avance, encore » = deux fois). */
    private val wheelPool = Executors.newSingleThreadExecutor()
    private val wheelGen = AtomicInteger()

    private fun wheelTool(name: String, args: Map<String, Any?>, t0: Long): Map<String, Any?> {
        if (roomba == null) return mapOf("result" to noWheels)
        val gen = wheelGen.get()
        val job = wheelPool.submit<Map<String, Any?>> {
            if (gen != wheelGen.get()) mapOf("result" to "annulé") else runTool(name, args)
        }
        runCatching { return job.get(400, TimeUnit.MILLISECONDS).also { journal.write("outil", "$name -> $it (${System.currentTimeMillis() - t0} ms)") } }
        thread(name = "roues-$name", isDaemon = true) {
            val r = runCatching { job.get() }.getOrElse { mapOf("result" to "interrompu") }
            journal.write("outil", "$name -> $r (${System.currentTimeMillis() - t0} ms, en arrière-plan)")
            val text = r["result"]?.toString().orEmpty()
            // Tout s'est bien passé : il l'a déjà dit en partant, on ne le répète pas.
            val ok = text.startsWith("avancé") || text.startsWith("tourné") || text.startsWith("déjà") ||
                text.startsWith("rien à faire") || text == "annulé" || text == "interrompu"
            if (!ok && gen == wheelGen.get()) deliverLater(name, args, r)
        }
        return mapOf("result" to "C'est parti, tu es en train de bouger. Dis-le en trois mots au plus (« j'avance », " +
            "« je tourne »). Tu ne seras prévenu que s'il y a un problème (obstacle, vide…).")
    }

    /** Donne à Gemini le résultat d'un outil long, quand personne ne parle (sans couper la parole). */
    private fun deliverLater(name: String, args: Map<String, Any?>, r: Map<String, Any?>) {
        val l = live ?: return
        val t0 = System.currentTimeMillis()
        // Tout de suite, sauf s'il est en train de parler (8 s au plus) ou si la personne parle à l'instant (3 s au plus).
        while (System.currentTimeMillis() - t0 < 8000 && (_state.value.live == LiveState.SPEAKING ||
                (System.currentTimeMillis() - lastPersonTalkAt < 700 && System.currentTimeMillis() - t0 < 3000))) Thread.sleep(100)
        if (live !== l) return
        l.sendFrameNow()
        val what = args.values.filterIsInstance<String>().joinToString().ifBlank { "" }
        val res = r.entries.joinToString(" ; ") { "${it.key} : ${it.value}" }
        l.event("Résultat de « $name${if (what.isNotEmpty()) " $what" else ""} » : $res. Dis-le brièvement.", reply = true)
    }

    private fun runTool(name: String, args: Map<String, Any?>): Map<String, Any?> {
        CrashReport.mark("outil $name")
        val noHead = "ta nacelle (ton cou) n'est pas connectée : tu ne peux pas bouger la tête pour l'instant"
        return when (name) {
            "regarder" -> {
                val dir = args["direction"] as? String ?: "devant"
                val deg = (args["degres"] as? Number)?.toDouble()
                if (!gimbal.ready) {
                    // Sans cou : à gauche, à droite, derrière… c'est tout le corps qui tourne.
                    val r = roomba ?: return mapOf("result" to noGaze)
                    val turn = when (dir.lowercase()) {
                        "gauche" -> -(deg ?: 45.0); "droite" -> deg ?: 45.0; "derriere" -> 180.0; "devant" -> 0.0
                        else -> return mapOf("result" to "sans ta nacelle tu ne peux pas lever ou baisser les yeux")
                    }
                    log("👀 regarder $dir (avec les roues)")
                    val res = moveSync(holdMs = 3000) { if (turn == 0.0) "déjà devant" else r.turn(turn) }
                    live?.sendFrameNow()
                    return mapOf("result" to res, "visage_dans_l_image" to ((faces.lastSize ?: 0f) > 0f))
                }
                log("👀 regarder $dir${deg?.let { " ${it.toInt()}°" } ?: ""}")
                gazeDirectedUntil = System.currentTimeMillis() + 60_000
                // Il garde ce regard quelques secondes avant de revenir te suivre.
                val r = moveSync(holdMs = if (dir == "devant") 1000 else 7000, timeoutMs = if (dir == "devant") 45_000 else 15_000) {
                    // « Devant » avec les roues : la nacelle se remet dans l'axe du corps (sa propre commande, sans dérive)
                    // et le repère tête-corps est repris.
                    if (dir.lowercase() == "devant" && roomba != null && calibrateBody()) { head.look(null, 0.0, timeoutMs = 1500); "tête droite devant, dans l'axe de ton corps" }
                    else head.lookDirection(dir, deg)
                }
                live?.sendFrameNow()
                mapOf("result" to r, "visage_dans_l_image" to follower.sees)
            }
            "chercher" -> {
                if (!flag("camera", true)) return mapOf("result" to "ta caméra est coupée (réglages) : tu ne vois rien")
                val what = (args["objet"] as? String)?.trim().orEmpty().ifEmpty { "personne" }
                val labels = CocoClasses.forTarget(what)
                if (!gimbal.ready) {
                    // Sans cou, il tourne tout le corps (s'il a ses roues) pour chercher ce que le téléphone sait reconnaître.
                    val r = roomba ?: return mapOf("result" to noHead)
                    if (labels == null) return mapOf("result" to "sans ta nacelle, tu ne sais chercher en tournant que des " +
                        "choses simples (personne, chien, chat, chaise, télé…) : pas « $what »")
                    log("🔎 je cherche en tournant sur moi-même : $what")
                    var found: com.joey.app.senses.Seen? = null
                    var res = ""
                    moveSync(holdMs = 3000, timeoutMs = 150_000) {
                        found = labels?.let { searchWithBody(r, it) }
                        // Pas ici : il part le chercher ailleurs.
                        res = if (found != null) "trouvé" else explore(what, 90)
                        res
                    }
                    live?.sendFrameNow()
                    return mapOf("result" to if (found != null) "trouvé : tu es tourné vers « $what » (regarde l'image)"
                        else "pas en vue autour de toi, alors tu es parti explorer : $res")
                }
                log("🔎 je cherche : $what" + (labels?.let { " (détecteur : ${it.joinToString()})" } ?: " (vision Gemini)"))
                if (labels != null && labels == setOf("person") && !follower.sees) {
                    var r = ""
                    moveSync(holdMs = 0, timeoutMs = 90_000) {
                        r = findCaller(0)
                        // Trouvé une silhouette mais pas de visage confirmé : Gemini Robotics-ER vérifie qu'il y a bien quelqu'un.
                        if (r.startsWith("trouvé") && !follower.sees) {
                            val v = erVerify("une personne")
                            if (v?.first == false) r = "fausse alerte : ${v.second?.description}"
                        }
                        r
                    }
                    if (!(r.startsWith("trouvé") || r.startsWith("je te vois")) && roomba != null) {
                        var e = ""
                        e = moveSync(holdMs = 3000, timeoutMs = 120_000) { explore("personne", 90) }
                        r = "pas en vue d'ici, alors tu es parti chercher : $e"
                    }
                    live?.sendFrameNow()
                    return mapOf("result" to if (r.startsWith("trouvé") || r.startsWith("je te vois")) "$r (vérifie sur l'image)" else r)
                }
                // Un objet ou un animal : un seul tour, détecteur du téléphone et Gemini à chaque arrêt.
                var sp: com.joey.app.brain.GeminiVision.Spot? = null
                moveSync(holdMs = 8000, timeoutMs = 100_000) {
                    sp = searchSpatial(what, labels?.takeIf { "person" !in it })
                    if (sp != null) "trouvé : $what" else "pas trouvé : $what"
                }
                // Pas ici : avec ses roues, il part le chercher ailleurs (au lieu d'abandonner sur place).
                if (sp == null && roomba != null) {
                    var res = ""
                    res = moveSync(holdMs = 8000, timeoutMs = 120_000) { explore(what, 90) }
                    live?.sendFrameNow()
                    return mapOf("result" to "pas en vue d'ici, alors tu es parti explorer : $res")
                }
                val found = sp
                if (found != null) showingFoundUntil = System.currentTimeMillis() + 8000
                live?.sendFrameNow()
                return if (found != null) mapOf("result" to "trouvé (vérifié) : ta tête est tournée vers « $what »" +
                    distanceText(found.distance) + ". Dis-le tout de suite ; regarde l'image pour en dire plus.",
                    "description" to found.description)
                else mapOf("result" to "pas de « $what » en faisant le tour (360°, deux hauteurs" +
                    (vision.quotaText()?.let { " ; détecteur du téléphone seulement : $it" } ?: ", détecteur et Gemini") +
                    "). Peut-être caché, trop loin ou dans le noir. Dis-le honnêtement ; si la personne te dit où il est, " +
                    "regarde là (« regarder »).")
            }
            "tester_roues" -> {
                val r = roomba ?: return mapOf("result" to noWheels)
                log("🛞 Test des roues")
                var res = ""
                moveSync(holdMs = 1000, timeoutMs = 60_000) {
                    val steps = ArrayList<String>()
                    r.ready()?.let { steps.add("pas prêt : $it") }
                    if (steps.isEmpty()) {
                        steps.add("avance 20 cm : " + r.drive(0.2))
                        steps.add("recule 20 cm : " + r.drive(-0.2))
                        steps.add(selfTestDirection(r))
                        steps.add(selfTestRotation(r))
                    }
                    steps.add("batterie des roues : ${r.battery} %" + (if (r.onBase) ", sur la base" else ""))
                    res = steps.joinToString(" ; ")
                    res
                }
                journal.write("roues", res)
                mapOf("result" to res)
            }
            "ajuster" -> {
                val what = (args["reglage"] as? String)?.trim()?.lowercase().orEmpty()
                val how = (args["sens"] as? String)?.trim()?.lowercase().orEmpty()
                val res = if (what == "sens_roues") adjustWheelDirection(how)
                else tuning.adjust(what, how).also { roomba?.let { applyTuning(it) } }
                log("🎛️ $res")
                mapOf("result" to res)
            }
            "changer_voix" -> {
                val v = (args["voix"] as? String)?.trim().orEmpty()
                if (v !in VOICES) return mapOf("result" to "voix inconnue ; au choix : ${VOICES.joinToString()}")
                log("🗣️ Nouvelle voix : $v")
                setVoice(v)
                mapOf("result" to "voix « $v » choisie : dis une phrase pour la saluer, la conversation redémarre dans 4 s avec elle")
            }
            "retenir_regle" -> {
                val rule = (args["regle"] as? String)?.trim().orEmpty()
                val isNew = tuning.addRule(rule)
                if (isNew) log("📏 Règle apprise : $rule")
                mapOf("result" to if (isNew) "règle retenue (appliquée dès maintenant et à chaque conversation)" else "tu la connaissais déjà")
            }
            "oublier_regle" -> {
                val gone = tuning.removeRule((args["mots"] as? String).orEmpty())
                gone.forEach { log("📏 Règle oubliée : $it") }
                mapOf("result" to if (gone.isEmpty()) "aucune règle ne correspond (tes règles : ${tuning.rules().joinToString(" | ")})"
                    else "oublié : ${gone.joinToString(" | ")}")
            }
            "demander_amelioration" -> {
                val ask = (args["demande"] as? String)?.trim().orEmpty()
                if (ask.isEmpty()) return mapOf("result" to "quelle demande ?")
                journal.write("demande", ask)
                log("📝 Demande pour Claude : $ask")
                mapOf("result" to "noté pour Claude (il lit tes demandes chaque nuit et améliore ton programme ; Michael " +
                    "recevra une nouvelle version)")
            }
            "dormir" -> {
                // Il dit au revoir, puis la conversation s'arrête ; « Joey » le réveille.
                log("💤 Je vais dormir (on me l'a demandé)")
                thread(isDaemon = true) { Thread.sleep(3500); stopLive() }
                mapOf("result" to "dis au revoir en quelques mots : la conversation s'arrête dans 3 s ; on te réveille en disant « Joey »")
            }
            "regarder_personne" -> {
                val who = (args["qui"] as? String)?.trim().orEmpty()
                if (!gimbal.ready) {
                    val r = roomba ?: return mapOf("result" to noGaze)
                    chooser.want(who.ifEmpty { "autre" })
                    log("👀 je me tourne vers : ${who.ifEmpty { "l'autre" }} (avec les roues)")
                    moveAsync(holdMs = 0) {
                        Thread.sleep(400) // le temps que le choix du visage se fasse sur l'image suivante
                        if (follower.sees) {
                            val ang = follower.faceX * hfovNow() / 2
                            if (kotlin.math.abs(ang) > 6) r.turn(ang) else "en face"
                        } else searchWithBody(r, setOf("person"))?.let { "trouvé" } ?: "personne en vue"
                    }
                    return mapOf("result" to "tu te tournes (avec tes roues) vers ${who.ifEmpty { "l'autre personne" }}",
                        "visages_dans_l_image" to chooser.count, "reconnus" to chooser.names().joinToString())
                }
                log("👀 je regarde : ${who.ifEmpty { "l'autre" }}")
                // Tout de suite : la tête s'en charge pendant qu'il parle (avant, il attendait 2 à 4 s pour répondre).
                moveAsync(holdMs = 0) { lookAtPerson(who) }
                mapOf("result" to "tu te tournes vers ${who.ifEmpty { "l'autre personne" }}", "visages_dans_l_image" to chooser.count,
                    "reconnus" to chooser.names().joinToString())
            }
            "regarder_ce_que_je_montre" -> {
                if (!flag("camera", true)) return mapOf("result" to "ta caméra est coupée")
                if (!gimbal.ready) {
                    // Sans cou : il regarde ce qu'il a devant lui ; à la personne de le mettre dans le champ.
                    live?.sendFrameNow()
                    return mapOf("result" to "sans ta nacelle tu ne peux pas baisser les yeux : décris ce que tu vois sur " +
                        "la nouvelle image ; si ce n'est pas dedans, demande qu'on te le mette devant la caméra")
                }
                var r: String? = null
                moveSync(holdMs = 8000, timeoutMs = 40_000) { r = lookWherePointed(); r ?: "vision indisponible" }
                live?.sendFrameNow()
                mapOf("result" to (r ?: "Gemini Robotics-ER ne répond pas : demande à la personne ce qu'elle montre") +
                    ". Regarde la nouvelle image pour en parler.")
            }
            "regarder_autour" -> {
                if (!gimbal.ready) {
                    val r = roomba ?: return mapOf("result" to noGaze)
                    log("👀 regarder autour (en tournant sur moi-même)")
                    val seen = StringBuilder()
                    moveSync(holdMs = 3000, timeoutMs = 40_000) {
                        for ((k, where) in listOf("devant", "à droite", "derrière", "à gauche").withIndex()) {
                            if (k > 0) { r.turn(90.0); Thread.sleep(400) }
                            live?.sendFrameNow()
                            seen.append("$where : ${objects.describe(seenNow())}. ")
                        }
                        r.turn(90.0) // retour face au départ
                        "fait"
                    }
                    return mapOf("result" to "fait en tournant tout le corps (tu as reçu une image à chaque arrêt)", "detecteur" to seen.toString().trim())
                }
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
                var zone = args["zone"] as? String ?: "tout"
                // Une question sur la personne (ce qu'elle tient, porte, montre) : d'abord la regarder.
                if (Regex("(?i)(je tiens|je porte|j'ai dans|ma main|mes mains|je montre|sur moi|mon |ma |mes |tu tiens|tiens-tu)").containsMatchIn(q) &&
                    !follower.sees && gimbal.ready) {
                    moveSync(holdMs = 4000, timeoutMs = 4000) { if (lookAtLastSeen()) "je te regarde" else "pas revu" }
                    zone = "tout"
                }
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
                val secs = ((args["secondes"] as? Number)?.toInt() ?: 20).coerceIn(5, 120)
                if (!gimbal.ready) {
                    // Sans cou : il se dandine sur ses roues.
                    val r = roomba ?: return mapOf("result" to noGaze)
                    log("💃 je danse avec mes roues")
                    moveAsync(holdMs = 1000) {
                        val end = System.currentTimeMillis() + secs * 1000L
                        var side = 1.0
                        while (System.currentTimeMillis() < end && !Thread.currentThread().isInterrupted) {
                            r.velocity(0.0, 120.0 * side, 350); Thread.sleep(330); side = -side
                        }
                        r.velocity(0.0, 0.0, 100)
                        "fini"
                    }
                    return mapOf("result" to "tu danses ${secs} s en te dandinant sur tes roues")
                }
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
            "rappel" -> {
                val text = args["message"] as? String ?: ""
                val r = reminders.add(text, (args["dans_minutes"] as? Number)?.toDouble(), args["heure"] as? String, args["chaque_jour"] == true)
                    ?: return mapOf("result" to "heure illisible : donne « dans_minutes » ou « heure » (HH:MM)")
                val fmt = SimpleDateFormat("EEEE HH:mm", Locale.FRENCH)
                log("⏰ Rappel noté pour ${fmt.format(Date(r.at))} : $text")
                mapOf("result" to "rappel noté pour ${fmt.format(Date(r.at))}" + if (r.daily) " (chaque jour)" else "")
            }
            "rappels" -> {
                val fmt = SimpleDateFormat("EEE d HH:mm", Locale.FRENCH)
                val all = reminders.all()
                mapOf("rappels" to if (all.isEmpty()) "aucun" else all.joinToString("\n") { fmt.format(Date(it.at)) + " — " + it.text + if (it.daily) " (chaque jour)" else "" })
            }
            "annuler_rappel" -> {
                val gone = reminders.cancel(args["sujet"] as? String ?: "")
                mapOf("result" to if (gone.isEmpty()) "aucun rappel ne correspond" else "annulé : " + gone.joinToString(" ; "))
            }
            "journal_du_jour" -> mapOf("journal" to writeDiary(send = false))
            "pierre_feuille_ciseaux" -> mapOf("result" to playRps())
            "apprendre_voix" -> {
                val who = (args["prenom"] as? String)?.trim().orEmpty()
                if (who.isEmpty()) return mapOf("result" to "il me faut un prénom")
                log("🎙 J'écoute la voix de $who…")
                val ok = busyLearn { voices.learn(who) }
                refreshSettings()
                mapOf("result" to if (ok) "voix de $who apprise" else "pas assez entendu sa voix : qu'elle parle 6 secondes sans s'arrêter, et recommence")
            }
            "qui_parle" -> {
                val refs = voices.references()
                if (refs.isEmpty()) return mapOf("result" to "tu ne connais encore aucune voix (outil apprendre_voix)")
                val clip = voices.recentSpeech() ?: return mapOf("result" to "pas assez entendu de parole récemment")
                val (name, cert) = runCatching { vision.whoSpeaks(refs, clip) }.getOrElse { return mapOf("result" to "écoute impossible : ${it.message}") }
                log("🎙 Voix : $name (${(cert * 100).toInt()} %)")
                // La comparaison de voix par Gemini est approximative : en dessous de 0,7, on ne conclut pas.
                if (cert < 0.7 || name.equals("inconnu", true)) mapOf("voix" to "pas sûr (peut-être $name)", "certitude" to cert,
                    "conseil" to "ne prétends pas savoir qui parle ; demande-le gentiment si c'est important")
                else mapOf("voix" to name, "certitude" to cert, "conseil" to "reconnaissance approximative : reste prudent")
            }
            "oublier_voix" -> {
                voices.forget(args["prenom"] as? String ?: "")
                refreshSettings()
                mapOf("result" to "voix oubliée")
            }
            "maison_appareils" -> {
                if (!home.configured) return mapOf("result" to "Home Assistant n'est pas relié (réglages ⚙)")
                val list = runCatching { home.entities() }.getOrElse { return mapOf("result" to "${it.message}") }
                val filtre = (args["filtre"] as? String)?.lowercase().orEmpty()
                mapOf("appareils" to list.filter { filtre.isEmpty() || it.name.lowercase().contains(filtre) || it.domain.contains(filtre) }
                    .take(60).joinToString("\n") { "${it.name} (${it.id}) : ${it.state}" })
            }
            "maison_commande" -> {
                if (!home.configured) return mapOf("result" to "Home Assistant n'est pas relié (réglages ⚙)")
                val r = runCatching {
                    home.command(args["appareil"] as? String ?: "", args["action"] as? String ?: "basculer", (args["valeur"] as? Number)?.toDouble())
                }.getOrElse { "échec : ${it.message}" }
                log("🏠 $r")
                mapOf("result" to r)
            }
            "cache_cache_compter" -> {
                val n = ((args["secondes"] as? Number)?.toInt() ?: 20).coerceIn(5, 60)
                mapOf("result" to busyLearn { hideAndSeekCount(n) })
            }
            "avancer" -> {
                val r = roomba ?: return mapOf("result" to noWheels)
                val m = ((args["metres"] as? Number)?.toDouble() ?: 0.5).coerceIn(-2.0, 3.0)
                log("🛞 avancer ${"%.2f".format(m)} m")
                // Plus de 30 cm devant : la tête regarde la route (la caméra voit l'obstacle avant le choc).
                mapOf("result" to busyLearn { if (m > 0.3 && gimbal.ready) withRoadGaze { roadLegStart(0); r.drive(m) } else r.drive(m) })
            }
            "tourner_corps" -> {
                val r = roomba ?: return mapOf("result" to noWheels)
                if (args["vers_le_regard"] == true) {
                    // « C'est par là » après lui avoir fait tourner la tête : le corps se tourne là où elle regarde.
                    if (!gimbal.ready) return mapOf("result" to "sans nacelle ta tête est ton corps : tu regardes déjà devant toi")
                    val turned = busyLearn { alignBodyWithHead() }
                    return mapOf("result" to if (kotlin.math.abs(turned) < 1) "déjà tourné vers là où tu regardes"
                        else "tourné de %.0f° : ton corps fait face à là où tu regardais".format(turned))
                }
                val d = ((args["degres"] as? Number)?.toDouble() ?: 90.0).coerceIn(-360.0, 360.0)
                mapOf("result" to busyLearn { r.turn(d) })
            }
            "viens_ici" -> {
                if (roomba == null) return mapOf("result" to noWheels)
                log("🛞 J'arrive")
                var res = ""
                res = moveSync(holdMs = 0, timeoutMs = 90_000) { comeHere() }
                mapOf("result" to res)
            }
            "suis_moi" -> {
                if (roomba == null) return mapOf("result" to noWheels)
                followMe(((args["minutes"] as? Number)?.toDouble() ?: 3.0).coerceIn(0.5, 15.0))
                mapOf("result" to "tu suis la personne (dis-lui de marcher doucement) ; « arrete » pour t'arrêter")
            }
            "aspirer" -> {
                val r = roomba ?: return mapOf("result" to noWheels)
                val res = r.vacuum(args["marche"] != false)
                log("🧹 $res")
                mapOf("result" to res)
            }
            "mode_autonome" -> {
                val on = args["actif"] != false
                setFlagValue("autonomy", on)
                log(if (on) "🤖 Mode autonome activé" else "🤖 Mode autonome arrêté")
                mapOf("result" to if (on) "mode autonome activé : quand on ne te demande rien (de 8 h à 22 h), tu vas voir qui " +
                    "arrive, tu agrandis ta carte de la maison, et tu rentres à ta base quand ta batterie baisse" else "mode autonome arrêté")
            }
            "aller_voir" -> {
                if (roomba == null) return mapOf("result" to noWheels)
                val who = (args["qui"] as? String)?.trim().orEmpty()
                log("🛞 Je vais voir ${who.ifEmpty { "quelqu'un" }}")
                var res = ""
                res = moveSync(holdMs = 3000, timeoutMs = 240_000) { goSee(who) }
                mapOf("result" to res)
            }
            "revenir" -> {
                val r = roomba ?: return mapOf("result" to noWheels)
                val back = missionStart ?: return mapOf("result" to "tu n'es parti de nulle part : tu es déjà là")
                log("🛞 Je reviens")
                var res = ""
                res = moveSync(holdMs = 1000, timeoutMs = 180_000) { navigateTo(back.first, back.second) }
                if (res.startsWith("arrivé") || res.startsWith("presque")) missionStart = null
                mapOf("result" to "retour : $res")
            }
            "se_degager" -> {
                val r = roomba ?: return mapOf("result" to noWheels)
                log("🛞 Je me dégage")
                var res = ""
                moveSync(holdMs = 1000, timeoutMs = 30_000) {
                    val tries = ArrayList<String>()
                    for (i in 0 until 3) {
                        tries.add(r.unstick(i))
                        Thread.sleep(300)
                        if (!r.stuck && r.sensors?.bump != true) break
                    }
                    res = tries.joinToString(" ; ")
                    res
                }
                mapOf("result" to res + (if (r.stuck) " — toujours coincé : demande de l'aide" else " — libre"))
            }
            "explorer" -> {
                if (roomba == null) return mapOf("result" to noWheels)
                val goal = (args["objectif"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                val secs = ((args["secondes"] as? Number)?.toInt() ?: 120).coerceIn(20, 300)
                var res = ""
                res = moveSync(holdMs = 3000, timeoutMs = secs * 1000L + 30_000) { explore(goal, secs) }
                live?.sendFrameNow()
                mapOf("result" to res + ". Si c'est une pièce que tu ne connais pas, propose de la cartographier.")
            }
            "arrete" -> {
                following = false
                followGen.incrementAndGet()
                wheelGen.incrementAndGet() // les déplacements en attente sont annulés
                roomba?.stop()
                motionJob?.cancel(true)
                head.stop()
                mapOf("result" to "arrêté")
            }
            "rentrer_base" -> {
                if (roomba == null) return mapOf("result" to noWheels)
                following = false
                var res = ""
                res = moveSync(holdMs = 1000, timeoutMs = 240_000) { goHome() }
                mapOf("result" to res)
            }
            "aller_vers" -> {
                if (roomba == null) return mapOf("result" to noWheels)
                val what = (args["objet"] as? String)?.trim().orEmpty()
                if (what.isEmpty()) return mapOf("result" to "vers quoi ?")
                log("🛞 Je vais vers : $what")
                var res = ""
                res = moveSync(holdMs = 3000, timeoutMs = 150_000) { goToward(what) }
                live?.sendFrameNow()
                mapOf("result" to res)
            }
            "aller_a" -> {
                val r = roomba ?: return mapOf("result" to noWheels)
                val lieu = args["lieu"] as? String ?: ""
                val p = houseMap.place(lieu) ?: run {
                    // Pas sur la carte : il part la chercher.
                    var e = ""
                    e = moveSync(holdMs = 3000, timeoutMs = 150_000) { explore(lieu, 120) }
                    return mapOf("result" to "« $lieu » n'était pas sur ta carte, alors tu es parti la chercher : $e. Si tu l'as " +
                        "trouvée, propose de la cartographier.")
                }
                val xy = p.xyIn(gridFrame) ?: run {
                    // Position inconnue sur la carte actuelle (relevé sans les roues, ou noté sur la carte d'une autre
                    // séance) : il la cherche en explorant, au lieu de foncer vers un point qui ne veut rien dire ici.
                    val why = if (p.x == null) "relevé fait sans les roues" else "position notée sur une autre carte (je n'étais " +
                        "pas parti de ma base)"
                    log("🗺 « ${p.name} » : $why, je le cherche")
                    var e = ""
                    e = moveSync(holdMs = 3000, timeoutMs = 150_000) { explore(p.name, 120) }
                    return mapOf("result" to "« ${p.name} » : $why, alors tu l'as cherché en explorant : $e. Si tu y es, " +
                        "relève-le (cartographier) pour noter sa position. Astuce à dire : si je pars de ma base, ma carte et " +
                        "les positions des pièces restent valables d'une fois sur l'autre.")
                }
                val px = xy.first; val py = xy.second
                log("🛞 Je vais : ${p.name}")
                var res = ""
                missionStart = r.x to r.y
                res = moveSync(holdMs = 1000, timeoutMs = 180_000) { navigateTo(px, py) }
                if (!res.contains("obstacle") && !res.contains("interrompu")) currentPlace = p.name
                mapOf("result" to res)
            }
            "cartographier" -> {
                if (!canTurnGaze) return mapOf("result" to noGaze)
                if (!flag("camera", true)) return mapOf("result" to "ta caméra est coupée")
                val lieu = (args["lieu"] as? String)?.trim().orEmpty().ifEmpty { "ici" }
                var r = ""
                val wholeHouse = Regex("(?i)(maison|appartement|toutes les pi|les pièces|partout|tout l'étage)").containsMatchIn(lieu)
                if (wholeHouse && roomba != null) {
                    r = moveSync(holdMs = 2000, timeoutMs = 9 * 60_000L) { mapHouse() }
                    live?.sendFrameNow()
                    return mapOf("result" to r)
                }
                r = moveSync(holdMs = 2000, timeoutMs = 150_000) { mapHere(lieu) }
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
            // Pour le pilote PC (pas proposé à Gemini) : les capteurs bruts du Roomba pendant quelques secondes, min et max
            // (vérifier sur le vrai robot les détecteurs de proximité, la roulette avant, les balises de la base…).
            "capteurs" -> {
                val r = roomba ?: return mapOf("result" to noWheels)
                val secs = ((args["secondes"] as? Number)?.toDouble() ?: 3.0).coerceIn(0.5, 30.0)
                val light = Array(6) { IntArray(2) { if (it == 0) Int.MAX_VALUE else Int.MIN_VALUE } }
                val ir = sortedSetOf<Int>(); var stasisOn = 0; var n = 0
                var wallMax = 0; var cliff = false; var bump = false; var drop = false; var over = 0; var virt = false
                var curMin = Int.MAX_VALUE; var curMax = Int.MIN_VALUE
                val end = System.currentTimeMillis() + (secs * 1000).toLong()
                var last: Any? = null
                while (System.currentTimeMillis() < end) {
                    val x = r.sensors
                    if (x != null && x !== last) {
                        last = x; n++
                        x.lightSignals?.forEachIndexed { k, v -> light[k][0] = minOf(light[k][0], v); light[k][1] = maxOf(light[k][1], v) }
                        listOf(x.irOmni, x.irLeft, x.irRight).filter { it != 0 }.forEach { ir.add(it) }
                        if ((x.stasis ?: 0) and 1 == 1) stasisOn++
                        wallMax = maxOf(wallMax, x.wallSignal); cliff = cliff || x.cliff; bump = bump || x.bump
                        drop = drop || x.wheelDropLeft || x.wheelDropRight; over = over or x.overcurrent; virt = virt || x.virtualWall == true
                        curMin = minOf(curMin, x.currentMa); curMax = maxOf(curMax, x.currentMa)
                    }
                    Thread.sleep(30)
                }
                val x = r.sensors
                mapOf(
                    "lectures" to n,
                    "proximite_min_max" to (if (x?.lightSignals == null) "non lus (capteurs de base seulement)" else
                        com.joey.app.robot.RoombaOI.LIGHT_ANGLES.indices.joinToString(" ; ") { k ->
                            "%+.0f° : %d-%d".format(com.joey.app.robot.RoombaOI.LIGHT_ANGLES[k], light[k][0], light[k][1]) }),
                    "proximite_bits" to (x?.lightBumper ?: -1),
                    "mur_intensite_max" to wallMax,
                    "roulette_avant_tourne" to "$stasisOn/$n lectures",
                    "infrarouge_codes" to ir.joinToString(),
                    "vide" to cliff, "pare_chocs" to bump, "roue_soulevee" to drop, "mur_virtuel" to virt,
                    "surintensite_bits" to over,
                    "courant_batterie_ma" to "$curMin..$curMax",
                    "batterie" to "${x?.voltageMv ?: 0} mV, ${x?.chargeMah ?: 0}/${x?.capacityMah ?: 0} mAh, charge ${x?.chargingState ?: -1}",
                    "mode" to (x?.oiMode ?: -1), "sur_la_base" to (x?.onHomeBase ?: false),
                )
            }
            "etat" -> {
                val pose = head.pose()
                mapOf(
                    "nacelle" to if (gimbal.ready) "connectée" else "non connectée",
                    "tete" to (pose?.let { "%.0f° %s, inclinée à %.0f°".format(kotlin.math.abs(it.first), if (it.first >= 0) "à droite" else "à gauche", it.second) } ?: "position inconnue"),
                    "batterie_nacelle_pourcent" to gimbal.battery.takeIf { it >= 0 },
                    "roues" to (roomba?.let { r -> "branchées, batterie ${r.battery ?: "?"} %" + (if (r.onBase) ", sur ta base" else "") +
                        (if (r.charging) ", en charge" else "") + (if (r.vacuuming) ", aspiration en marche" else "") +
                        (if (r.reversed) ", visage vers l'arrière du Roomba" else "") +
                        (if (System.currentTimeMillis() - r.dockSeenAt < 5000) ", ta base est en vue (balises)" else "") +
                        (if (r.extended) ", tous tes capteurs (proximité, mur, boutons)" else ", capteurs de base") +
                        (r.sensors?.batteryTempC?.let { t -> if (t >= 45) ", batterie chaude ($t °C)" else "" } ?: "") +
                        (r.safetyNote?.let { n -> ", dernier arrêt de sécurité : $n" } ?: "") }
                        ?: "pas branchées (Roomba)"),
                    "souvenirs" to memory.size,
                    "reglages_appris" to tuning.describe(),
                    "carte" to "%.0f m² connus%s".format(grid.knownArea(), if (gridAnchored) ", repère = ta base" else
                        " (carte de cette séance : pose-toi sur ta base pour la garder)"),
                    "cable_tete" to "tête tournée de $cable par rapport au corps (limite ±185°)",
                    "regles_apprises" to tuning.rules().size,
                    "visage_vu" to follower.sees,
                    "personne_reconnue" to (seeing() ?: "personne de connu en vue"),
                    "visages_visibles" to chooser.count,
                    "visages_reconnus" to chooser.names().distinct().joinToString().ifEmpty { "aucun" },
                    "micros" to stereoDir.describe(),
                    "batterie_telephone" to phoneBattery(),
                    "heure" to SimpleDateFormat("EEEE d MMMM, HH:mm", Locale.FRENCH).format(Date()),
                    "mode_nuit" to _state.value.night,
                    "gens_de_la_maison_connus" to people.names().ifEmpty { listOf("aucun") }.joinToString(),
                    "boussole" to (sensors.compassText() ?: "indisponible"),
                    "orientation_par" to if (head.usesPhone) "capteurs du téléphone" else "angles de la nacelle",
                    "suivi_du_visage" to follower.enabled,
                )
            }
            else -> mapOf("result" to "outil inconnu")
        }
    }

    private fun setMood(m: Mood, sound: Boolean = true) {
        _state.update { it.copy(mood = m) }
        if (sound) roomba?.let { r ->
            when (m) {
                Mood.CONTENT, Mood.AMUSE -> { r.sing(com.joey.app.robot.RoombaRobot.Tune.HAPPY); r.lights(0) }
                Mood.TRISTE -> { r.sing(com.joey.app.robot.RoombaRobot.Tune.SAD); r.lights(60, 120) }
                Mood.ETONNE -> { r.sing(com.joey.app.robot.RoombaRobot.Tune.SURPRISED); r.lights(128, 255, spot = true) }
                Mood.FACHE -> { r.sing(com.joey.app.robot.RoombaRobot.Tune.ANGRY); r.lights(255) }
                Mood.FATIGUE, Mood.DORT -> { r.sing(com.joey.app.robot.RoombaRobot.Tune.SLEEPY); r.lights(0, 30) }
                Mood.MEFIANT -> { r.sing(com.joey.app.robot.RoombaRobot.Tune.HESITATE); r.lights(180) }
                else -> r.lights(0, 120)
            }
        }
        val at = System.currentTimeMillis() + 9000
        moodResetAt = at
        // L'expression s'efface toute seule après quelques secondes.
        thread(isDaemon = true) {
            Thread.sleep(9000)
            if (moodResetAt == at) _state.update { it.copy(mood = baseMood()) }
        }
    }

    // ---- pour le visage ----------------------------------------------------------------------
    fun voiceLevel(): Float = live?.level ?: 0f

    /** Tête penchée à l'écran (il réfléchit). */
    fun screenTilt(): Float = exprPose.screenTilt

    fun faceActivity(s: UiState): Activity = when {
        s.live == LiveState.SPEAKING -> Activity.PARLE
        s.live == LiveState.LISTENING -> Activity.ECOUTE
        s.live == LiveState.CONNECTING -> Activity.REFLECHIT
        else -> Activity.REPOS
    }

    fun gaze(): Pair<Float, Float> {
        // Les yeux partent vers la cible avant la tête (comme nous) : tant que la tête n'y est pas, ils y regardent.
        head.aimError()?.let { (ep, et) ->
            if (kotlin.math.abs(ep) > 6 || kotlin.math.abs(et) > 6)
                return Pair((-ep / 35).coerceIn(-1.0, 1.0).toFloat(), (-et / 35).coerceIn(-1.0, 1.0).toFloat())
        }
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
        journal.write("demarrage", "Joey ${BuildConfig.VERSION_NAME} sur ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
            "Android ${android.os.Build.VERSION.RELEASE}")
        vision.onQuota = { model, until ->
            log("⛔ Quota Gemini atteint pour $model, jusqu'à " +
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRENCH).format(Date(until)))
        }
        // Mémoire partagée : 15 s après un changement, toutes les 5 min, et au démarrage.
        memory.onChange = { if (syncWantedAt == 0L) syncWantedAt = System.currentTimeMillis() + 15_000 }
        people.onChange = { if (syncWantedAt == 0L) syncWantedAt = System.currentTimeMillis() + 15_000 }
        syncWantedAt = System.currentTimeMillis() + 5000
        camera.deviceOrientation = { sensors.deviceOrientation }
        // En suivant quelqu'un, les petits mouvements expressifs sont atténués (la tête reste calme sur la personne).
        head.expression = {
            val tracking = System.currentTimeMillis() - lastTrackAimAt < 1000
            if (tracking) exprPose.pan * 0.3 to exprPose.tilt * 0.6 else exprPose.pan to exprPose.tilt
        }
        sensors.onMotion = { e -> bodyMoved(e) }
        // (Le niveau de la reconnaissance du mot de réveil saute à chaque relance : il n'est plus utilisé pour sursauter.)
        sensors.onShake = { shaken() }
        sensors.start()
        head.setPhoneSigns(prefs.getInt("phone_pan_sign", 0), prefs.getInt("phone_tilt_sign", 0))
        // Câble vers le Roomba : on compte les tours de la tête par rapport au corps (10 fois par seconde).
        head.twist = cable
        thread(name = "cable", isDaemon = true) {
            while (!cleared) {
                runCatching {
                    val ph = sensors.read()
                    // Tête presque à la verticale : son cap ne veut plus rien dire, on reprend après.
                    if (ph != null && kotlin.math.abs(ph.second) < 70) cable.update(ph.first, roomba?.heading) else cable.pause()
                }
                runCatching { gridTick() }
                Thread.sleep(100)
            }
        }
        thread(name = "visee-casque", isDaemon = true) { aimLoop() }
        // La tablette (et le casque) peuvent se connecter à tout moment : le serveur démarre avec l'app.
        if (flag("tablet", true)) thread(name = "tablette", isDaemon = true) { Thread.sleep(3000); if (!cleared) runCatching { setHeadset(true, quiet = true) } }
        thread(name = "vie", isDaemon = true) { lifeLoop() }
        thread(name = "autonome", isDaemon = true) { autonomyLoop() }
        thread(name = "profondeur", isDaemon = true) { depthLoop() }
        thread(name = "eveil", isDaemon = true) { awakeLoop() }
        thread(name = "roomba-essais", isDaemon = true) { roombaRetryLoop() }
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

    fun setHeadset(on: Boolean, quiet: Boolean = false) {
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
                    path.startsWith("/tablette") -> "text/html; charset=utf-8" to com.joey.app.remote.TabletPage.HTML.toByteArray()
                    path.startsWith("/tab/") -> "application/json; charset=utf-8" to (runCatching { tablet(path) }
                        .getOrElse { org.json.JSONObject().put("msg", "erreur : ${it.message}").toString() }).toByteArray()
                    path.startsWith("/events") -> "application/json; charset=utf-8" to watch.json().toByteArray()
                    path.startsWith("/snap") -> path.substringAfter("id=", "").toLongOrNull()?.let { watch.photo(it) }?.let { "image/jpeg" to it }
                    else -> null
                }
            },
        )
        if (!h.start()) {
            if (!quiet) message("Impossible de lancer la vue casque (port 8080 occupé ?).")
            return
        }
        headset = h
        val url = h.urls().firstOrNull()
        _state.update { it.copy(headsetUrl = url ?: "") }
        val tabletUrl = h.urls().firstOrNull { it.startsWith("https") }?.trimEnd('/')?.plus("/tablette")
        log("📱 Tablette : ouvre " + (tabletUrl ?: "https://<adresse du téléphone>:8443/tablette") + " (même Wi-Fi)")
        if (quiet) return
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

    // Historique de la pose (40 ms) : pour savoir où regardait la tête au moment où l'image a été prise.
    private val poseHistory = ArrayDeque<Pair<Long, Triple<Double, Double, com.joey.app.life.Expressive.Pose>>>()

    private fun poseAt(t: Long): Triple<Double, Double, com.joey.app.life.Expressive.Pose>? = synchronized(poseHistory) {
        poseHistory.lastOrNull { it.first <= t }?.second ?: poseHistory.firstOrNull()?.second
    }

    private fun aimLoop() {
        while (!cleared) {
            Thread.sleep(40)
            runCatching {
                val p = head.pose()
                if (p != null) synchronized(poseHistory) {
                    val now = System.currentTimeMillis()
                    poseHistory.addLast(now to Triple(p.first, p.second, exprPose))
                    while (poseHistory.size > 30) poseHistory.removeFirst()
                }
            }
            runCatching {
                val st = _state.value
                val now = System.currentTimeMillis()
                if (st.mood != exprMood) { exprMood = st.mood; exprMoodSince = now }
                val think = st.busy || st.live == LiveState.CONNECTING ||
                    (st.live == LiveState.LISTENING && now - lastPersonTalkAt in 600..6000 && lastJoeyTalkAt < lastPersonTalkAt)
                thinkingNow = think
                val thinkPose = st.live == LiveState.CONNECTING ||
                    (st.live == LiveState.LISTENING && now - lastPersonTalkAt in 600..6000 && lastJoeyTalkAt < lastPersonTalkAt)
                exprPose = expressive.pose(
                    voice = live?.level ?: 0f,
                    speaking = st.live == LiveState.SPEAKING,
                    listening = now - lastPersonTalkAt < 1500,
                    thinking = thinkPose && !st.busy,
                    energy = temperament.energy(),
                    mood = st.mood, moodSince = exprMoodSince,
                ).let { if (flag("body_language", true)) it else com.joey.app.life.Expressive.Pose(0.0, 0.0, 0f) }
            }
            runCatching { roadAim() }
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

    // ---- la tablette : voir, comprendre, piloter, parler -----------------------------------------------------
    private fun tabletQuery(path: String): Map<String, String> = path.substringAfter('?', "").split('&').filter { '=' in it }
        .associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    private fun tablet(path: String): String {
        val q = tabletQuery(path)
        return when {
            path.startsWith("/tab/state") -> tabletState(q["since"]?.toLongOrNull() ?: 0L)
            path.startsWith("/tab/map") -> tabletMap()
            path.startsWith("/tab/say") -> tabletSay(q["t"].orEmpty())
            path.startsWith("/tab/cmd") -> tabletCmd(q)
            path.startsWith("/tab/tool") -> pilotTool(q)
            else -> org.json.JSONObject().put("msg", "?").toString()
        }
    }

    private fun tabletState(since: Long): String {
        val s = _state.value
        val r = roomba
        val ahead = clearAhead(1000)
        return org.json.JSONObject()
            .put("version", BuildConfig.VERSION_NAME)
            .put("mood", s.mood.name.lowercase())
            .put("live", when (s.live) { LiveState.OFF -> "en veille"; LiveState.CONNECTING -> "réveil…"; LiveState.LISTENING -> "écoute"; LiveState.SPEAKING -> "parle" })
            .put("thinking", isThinking())
            .put("busy", s.busy || following)
            .put("tracking", s.tracking ?: (if (following) "toi (il te suit)" else ""))
            .put("seeing", seeing() ?: (if (follower.sees) "quelqu'un" else ""))
            .put("joey", s.lines.lastOrNull { it.joey }?.text?.trim() ?: "")
            .put("phone", phoneBattery())
            .put("roomba", s.roomba ?: org.json.JSONObject.NULL)
            .put("gimbal", if (s.gimbal == GimbalState.READY) "prête" + (gimbal.battery.takeIf { it >= 0 }?.let { " $it %" } ?: "") else s.gimbal.name.lowercase())
            .put("map", "%.0f m²".format(grid.knownArea()) + if (gridAnchored) " (base)" else "")
            .put("ahead", ahead?.let { if (it.isInfinite()) "libre" else "%.0f cm".format(it * 100) } ?: "—")
            .put("autonomy", flag("autonomy", false))
            .put("vacuum", r?.vacuuming == true)
            .put("feed", tabletFeed.since(since))
            .toString()
    }

    private fun tabletMap(): String {
        val r = roomba
        val x = r?.x ?: 0.0; val y = r?.y ?: 0.0
        val radius = (4.5 / grid.cell).toInt() // 4,5 m autour de lui, quelle que soit la taille des carreaux
        val (cells, cliffs) = grid.around(x, y, radius)
        val o = org.json.JSONObject().put("cell", grid.cell).put("radius", radius).put("rx", x).put("ry", y).put("rh", r?.heading ?: 0.0)
            .put("base", gridAnchored)
            .put("info", if (r == null) "roues non branchées" else "%.0f m² connus%s".format(grid.knownArea(), if (gridAnchored) ", repère = la base" else ""))
        o.put("cells", org.json.JSONArray().also { a -> cells.forEach { c -> a.put(org.json.JSONArray().put(c.first).put(c.second).put(c.third.toDouble())) } })
        o.put("cliffs", org.json.JSONArray().also { a -> cliffs.forEach { c -> a.put(org.json.JSONArray().put(c.first).put(c.second)) } })
        val places = org.json.JSONArray(); val items = org.json.JSONArray()
        houseMap.all().forEach { p ->
            if (p.frame != gridFrame) return@forEach
            if (p.x != null && p.y != null) places.put(org.json.JSONObject().put("n", p.name).put("x", p.x).put("y", p.y))
            p.items.filter { it.x != null }.take(30).forEach { items.put(org.json.JSONObject().put("n", it.name).put("x", it.x).put("y", it.y)) }
        }
        o.put("places", places).put("items", items)
        // Les pièces de sa carte, nommées d'après les lieux qu'il connaît (« salon »…) quand l'un d'eux y est.
        runCatching {
            val rr = nav?.rooms()
            if (rr != null) o.put("rooms", org.json.JSONArray().also { a ->
                rr.rooms.forEach { room ->
                    val name = houseMap.all().firstOrNull { p -> p.frame == gridFrame && p.x != null && p.y != null && rr.roomAt(p.x!!, p.y!!) == room.id }?.name
                    a.put(org.json.JSONObject().put("id", room.id).put("m2", room.area).put("x", room.cx).put("y", room.cy).put("n", name ?: ""))
                }
            })
        }
        o.put("people", org.json.JSONArray().also { a -> personSeenAt.forEach { (n, v) ->
            if (System.currentTimeMillis() - v.third < 3 * 3600_000L) a.put(org.json.JSONObject().put("n", n.replaceFirstChar { it.uppercase() }).put("x", v.first).put("y", v.second)) } })
        return o.toString()
    }

    /** Ce qu'on lui écrit depuis la tablette : comme si on le lui disait (la conversation démarre si besoin). */
    private fun tabletSay(text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return org.json.JSONObject().put("msg", "").toString()
        // Un essai de conversation (pilote PC ou tablette) : il reprend la parole, même en mode test.
        if (testQuiet) { testQuiet = false; log("🗣 Essai de conversation : je reprends la parole") }
        journal.write("toi", "(tablette) $t")
        val l = live
        if (l != null) { l.userText(t); return org.json.JSONObject().put("msg", "").toString() }
        if (geminiKey.isBlank()) return org.json.JSONObject().put("msg", "pas de clé Gemini sur le téléphone").toString()
        // Pas de conversation : on la lance, puis on lui transmet le message quand elle est prête.
        thread(isDaemon = true) {
            runCatching { android.os.Handler(android.os.Looper.getMainLooper()).post { if (live == null) micPressed() } }
            val end = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < end && _state.value.live != LiveState.LISTENING) Thread.sleep(200)
            live?.userText(t)
        }
        return org.json.JSONObject().put("msg", "je le réveille…").toString()
    }

    /**
     * Le PC lance un de ses outils (comme Gemini le ferait) et attend le résultat : pour les tests en autonomie.
     * Ouvert à tout le Wi-Fi de la maison (choix de Michael), comme la tablette.
     */
    // ---- mode test : écran noir tant que le pilote PC travaille ----------------------------------------------------
    @Volatile private var testScreenUntil = 0L
    private val testScreenWatch by lazy {
        thread(isDaemon = true, name = "ecran-test") {
            while (true) {
                Thread.sleep(5000)
                if (_state.value.testScreen && System.currentTimeMillis() > testScreenUntil) endTestScreen()
            }
        }
    }

    /** Mode test : il se tait aussi (pas de conversation ni de remarques à voix haute), sauf pour un essai de conversation. */
    @Volatile private var testQuiet = false

    /** Le pilote PC travaille : écran noir et silence pendant 10 min (prolongé à chaque appel). */
    fun testScreenOn(minutes: Int = 10) {
        testScreenUntil = System.currentTimeMillis() + minutes * 60_000L
        testScreenWatch
        if (!testQuiet) {
            testQuiet = true
            if (live != null) stopLive()
        }
        roomba?.muted = true // ni bips ni mélodies du Roomba pendant les essais
        if (!_state.value.testScreen) { _state.update { it.copy(testScreen = true) }; log("⬛ Mode test : écran noir, je me tais") }
    }

    /** On l'appelle, on le touche, ou les tests sont finis : le visage revient. */
    fun endTestScreen() {
        testScreenUntil = 0L
        testQuiet = false
        roomba?.muted = false
        if (_state.value.testScreen) { _state.update { it.copy(testScreen = false) }; log("🙂 Fin du mode test : visage rallumé") }
    }

    private fun pilotTool(q: Map<String, String>): String {
        testScreenOn()
        val name = q["name"].orEmpty()
        val args = runCatching { org.json.JSONObject(q["args"] ?: "{}") }.getOrNull()
            ?: return org.json.JSONObject().put("erreur", "args : JSON invalide").toString()
        val map = HashMap<String, Any?>()
        args.keys().forEach { k -> map[k] = args.get(k).let { v -> if (v == org.json.JSONObject.NULL) null else v } }
        val t0 = System.currentTimeMillis()
        journal.write("outil", "(pilote PC) $name $map")
        val res = runCatching { runTool(name, map) }.getOrElse { mapOf("erreur" to (it.message ?: it.toString())) }
        journal.write("outil", "(pilote PC) $name -> $res (${System.currentTimeMillis() - t0} ms)")
        val o = org.json.JSONObject().put("outil", name).put("ms", System.currentTimeMillis() - t0)
        res.forEach { (k, v) -> o.put(k, v?.toString() ?: org.json.JSONObject.NULL) }
        return o.toString()
    }

    @Volatile private var tabletDriving = false

    private fun tabletCmd(q: Map<String, String>): String {
        val r = roomba
        val msg: String = when (q["c"]) {
            "drive" -> {
                val f = q["f"]?.toDoubleOrNull() ?: 0.0
                val t = q["t"]?.toDoubleOrNull() ?: 0.0
                if (r == null) "roues non branchées" else {
                    if (kotlin.math.abs(f) > 0.05 || kotlin.math.abs(t) > 0.05) {
                        if (!tabletDriving) { tabletDriving = true; following = false; wheelGen.incrementAndGet(); if (_state.value.busy) motionJob?.cancel(true); r.stop() }
                        r.manual(f * f * kotlin.math.sign(f) * 0.45, t * kotlin.math.abs(t) * 120.0)
                    } else if (tabletDriving) { tabletDriving = false; r.manual(0.0, 0.0) }
                    ""
                }
            }
            "head" -> { manualStick((q["x"]?.toFloatOrNull() ?: 0f), (q["y"]?.toFloatOrNull() ?: 0f)); "" }
            "stop" -> { padButton(com.joey.app.remote.Gamepad.Button.B); "arrêté" }
            "vacuum" -> { if (r == null) "roues non branchées" else { thread(isDaemon = true) { log("🧹 " + r.vacuum(!r.vacuuming)) }; "" } }
            "dock" -> { if (r == null) "roues non branchées" else { moveAsync(holdMs = 1000) { goHome() }; "il rentre à sa base" } }
            "center" -> { recenter(); "" }
            "test" -> { testScreenOn(); "mode test : écran noir" }
            // Cartographie : un tour d'horizon (cmd scan), ou la pièce par tours d'horizon (cmd carto, secondes=…).
            "scan" -> { if (r == null) "roues non branchées" else { moveAsync(holdMs = 1000) { scanStation() }; "tour d'horizon" } }
            "carto" -> { if (r == null) "roues non branchées" else { val sec = (q["secondes"]?.toIntOrNull() ?: 180).coerceIn(30, 900)
                moveAsync(holdMs = 1000) { mapByScans(sec) }; "cartographie par tours d'horizon ($sec s)" } }
            // Profondeur (MiDaS) : cmd profondeur&on=0 la coupe (si elle voit des obstacles qui n'existent pas), on=1 la rallume.
            // La flèche imprimée est collée sur le Roomba (cmd fleche on=1) : il s'en sert pour trouver l'axe du corps.
            "fleche" -> { val on = q["on"] != "0"; prefs.edit().putBoolean("fleche", on).apply(); "flèche ${if (on) "utilisée" else "ignorée"}" }
            "profondeur" -> { val on = q["on"] != "0"; prefs.edit().putBoolean("profondeur", on).apply()
                log("🧠 Profondeur ${if (on) "activée" else "coupée"}"); "profondeur ${if (on) "activée" else "coupée"}" }
            "visage" -> { endTestScreen(); "visage rallumé" }
            "come" -> { if (r == null) "roues non branchées" else { moveAsync(holdMs = 3000) { comeHere() }; "il vient vers toi (vers la personne qu'il voit)" } }
            "explore" -> { if (r == null) "roues non branchées" else { moveAsync(holdMs = 3000) { explore(null, 120) }; "il explore (2 min)" } }
            "map" -> { if (!canTurnGaze) "ni nacelle ni roues" else { moveAsync(holdMs = 2000) { if (roomba != null) mapHouse() else mapHere("ici") }; "il cartographie" } }
            "autonomy" -> { val on = !flag("autonomy", false); setFlagValue("autonomy", on); log(if (on) "🤖 Mode autonome activé (tablette)" else "🤖 Mode autonome arrêté (tablette)"); if (on) "mode autonome" else "mode autonome arrêté" }
            "sleep" -> { if (live != null) stopLive(); "il dort (dis « Joey » pour le réveiller)" }
            else -> "commande inconnue"
        }
        return org.json.JSONObject().put("msg", msg).toString()
    }

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

    /** L'app est fermée : plus rien ne doit tourner (avant, le cerveau continuait et relançait le micro). */
    @Volatile private var cleared = false

    override fun onCleared() {
        cleared = true
        runCatching { setAwake(false) }
        upload.stop()
        wake.stop()
        headset?.stop()
        sensors.stop()
        stopLive()
        gimbal.disconnect()
        camera.stop()
    }
}
