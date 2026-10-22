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
    val phoneBattery: Int = -1,
    /** Grand texte au milieu du visage (pierre-feuille-ciseaux, compte de cache-cache…). */
    val bigText: String? = null,
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
    private val people = com.joey.app.brain.People(File(app.filesDir, "personnes.json"))
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

    fun log(text: String) {
        journal.write("log", text)
        val line = "${timeFmt.format(Date())} $text"
        _state.update { it.copy(log = (it.log + line).takeLast(200)) }
    }

    fun message(text: String?) = _state.update { it.copy(message = text) }

    fun flagValue(name: String, def: Boolean) = flag(name, def)
    fun setFlagValue(name: String, v: Boolean) { setFlag(name, v); refreshSettings() }

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
        // Où regardait la tête quand l'image a été prise (avant les calculs) : sert à viser en degrés.
        val framePose = head.pose()
        val frameAbs = head.absPan()
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
        if (last != null && System.currentTimeMillis() - last.third < 30 * 60_000L) {
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
    private val liveStartle = com.joey.app.life.Startle(jump = 20.0, floor = -30.0)
    private val wakeStartle = com.joey.app.life.Startle(jump = 6.0, floor = 6.0)
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
            !_state.value.busy && gimbal.ready && geminiKey.isNotBlank() && flag("follow_gaze", true) && !handAttention.active) {
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
        if (r != null && System.currentTimeMillis() - roombaMovedAt < 3000 && e == com.joey.app.life.BodyMotion.Event.PUSHED) return
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
        if (!flag("camera", true) || geminiKey.isBlank() || head.moving) return
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
            if (!follower.enabled || !gimbal.ready || now < follower.pausedUntil || calibrating) { aimTarget = null; return }
            val prev = aimTarget?.takeIf { now - aimTargetAt < 1000 }
            // Petite zone morte (4°) : la tête ne tremble pas pour un visage presque centré.
            var tp = pose.first + dPan
            var tt = (pose.second + dTilt).coerceIn(-70.0, 70.0)
            if (prev != null) {
                tp = if (kotlin.math.abs(dPan) < 4) prev.first else prev.first + Head.wrap(tp - prev.first) * 0.7
                tt = if (kotlin.math.abs(dTilt) < 4) prev.second else prev.second + (tt - prev.second) * 0.7
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
            if (now < follower.pausedUntil || !follower.enabled) { aimTarget = null; return }
            // Juste perdu : il finit d'aller là où il l'a vue (elle est sûrement là, un peu plus loin).
            if (now - aimTargetAt < 1200) { lastTrackAimAt = now; head.aim(t.first, t.second) } else aimTarget = null
        }
    }

    @Volatile private var lastGazeBack = 0L

    /**
     * En conversation, Joey regarde la personne : s'il ne la voit plus depuis 2,5 s (coup d'œil ailleurs, on lui a
     * montré quelque chose, elle a bougé…) et que personne ne lui a demandé de regarder ailleurs, il revient vers
     * l'endroit où il l'a vue en dernier. Sinon (pas revue là), la recherche de celui qui parle prend le relais.
     */
    private fun conversationGaze(now: Long, s: UiState) {
        if (live == null || s.busy || !gimbal.ready || !follower.enabled || !flag("camera", true)) return
        if (follower.sees || now < follower.pausedUntil || trackLabels != null || dancing || questFollowing) return
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
        while (true) {
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
                    val er = if (geminiKey.isNotBlank()) lookWherePointed() else null
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

    private fun shaken() {
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
        head.look(dp, ((head.pose()?.second ?: 0.0) + dt).coerceIn(-70.0, 70.0))
    }

    /** Gemini Robotics-ER sur l'image de maintenant (pleine résolution). null s'il ne répond pas. */
    private fun erLocate(what: String, floor: Boolean = false): com.joey.app.brain.GeminiVision.Spot? {
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
    private fun searchSpatial(what: String, firstDir: Int = 1): com.joey.app.brain.GeminiVision.Spot? {
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
                    Thread.sleep(450) // image nette, tête arrêtée
                    hitDone()?.let { return goTo(it) }
                    val img = camera.frame(1000) ?: continue
                    val jpeg = camera.zoomJpeg("tout") ?: continue
                    val abs = head.absPan() ?: continue
                    val t = head.pose()?.second ?: tilt
                    // Au plus 2 analyses en cours : sinon on attend un peu (et on regarde si l'une a trouvé).
                    while (stops.count { !it.job.isDone } >= 2) {
                        hitDone()?.let { return goTo(it) }
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
        if (!pt.pointing) return "personne ne montre rien sur l'image"
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
        follower.pausedUntil = Long.MAX_VALUE // la tête vise la chose, pas les visages
        var sp = erLocate(what, floor = true)
        if (sp == null) return "Gemini Robotics-ER ne répond pas"
        if (!sp.found || sp.confidence < 0.5) {
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
            if (res.contains("obstacle") || res.contains("vide") || res.contains("interrompu")) return "je me suis arrêté : $res"
        }
        return "je me suis approché de « $what »"
    }

    private fun distanceText(d: Double?) = d?.let { " (à environ %.1f m)".format(it) } ?: ""

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
        val l = live ?: return
        val now = System.currentTimeMillis()
        if (now - lastRemarkAt < minGapMs || now - lastPersonTalkAt < 4000 || _state.value.live != LiveState.LISTENING) return
        lastRemarkAt = now
        l.event(text, reply = true)
    }

    private fun speakUp(text: String) {
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
        warn("gimbal", gimbal.battery in 0..14, "La batterie de ma nacelle (mon cou) est à ${gimbal.battery} %.")
        val r = roomba
        val rb = r?.battery ?: -1
        warn("roomba", rb in 0..14 && r?.charging == false, "La batterie de mes roues est à $rb %, je rentre à ma base.")
        if (r != null && rb in 0..14 && !r.charging && !r.onBase && !following && !_state.value.busy && warned.contains("roomba") &&
            warned.add("roomba_dock")) {
            log("🛞 " + r.seekDock())
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
    fun connectRoomba(quiet: Boolean = false) {
        if (roomba != null || roombaConnecting) return
        roombaConnecting = true
        thread(name = "roomba", isDaemon = true) {
            try {
                val link = com.joey.app.robot.UsbSerial.open(getApplication())
                val r = com.joey.app.robot.RoombaRobot(link, ::log)
                r.connect()
                roomba = r
                log("🛞 Roomba branché : j'ai des roues !")
                refreshRoomba()
            } catch (e: Exception) {
                if (!quiet) { log("Roomba : ${e.message}"); message("Roomba : ${e.message}") }
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

    /** Tourne le corps vers où regarde la tête ; la tête revient droit devant (le suivi recentre ensuite). */
    private fun alignBodyWithHead(): Double {
        val r = roomba ?: return 0.0
        val pan = head.pose()?.first ?: return 0.0
        if (kotlin.math.abs(pan) < 8) return 0.0
        r.turn(pan)
        head.look(-pan, null)
        return pan
    }

    /** « Viens ici » : trouve la personne, se tourne vers elle, s'approche jusqu'à ~1 m. Bloquant. */
    private fun comeHere(): String {
        val r = roomba ?: return noWheels
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

    /** « Suis-moi » : la tête suit la personne, le corps suit la tête et garde ~1,5 m. */
    private fun followMe(minutes: Double) {
        val r = roomba ?: return
        following = true
        trackName = "personne"
        trackLabels = setOf("person")
        trackUntil = System.currentTimeMillis() + (minutes * 60_000).toLong()
        follower.pausedUntil = 0
        _state.update { it.copy(tracking = "je te suis") }
        thread(name = "suivre", isDaemon = true) {
            var lostAt = 0L
            try {
                while (following && System.currentTimeMillis() < trackUntil && roomba === r) {
                    Thread.sleep(150)
                    val pan = head.pose()?.first ?: 0.0
                    val p = seenNow().filter { it.label == "person" && it.score >= 0.5 }.maxByOrNull { it.box.h }
                    if (p == null && !follower.sees) {
                        if (lostAt == 0L) lostAt = System.currentTimeMillis()
                        r.velocity(0.0, 0.0, 300)
                        if (System.currentTimeMillis() - lostAt > 8000) { log("🛞 Je t'ai perdu"); break }
                        continue
                    }
                    lostAt = 0L
                    val h = p?.box?.h ?: 0.6
                    val fwd = ((0.6 - h) * 0.8).coerceIn(-0.08, r.maxSpeed)
                    r.velocity(fwd, (pan * 1.5).coerceIn(-60.0, 60.0), 400)
                }
            } finally {
                r.velocity(0.0, 0.0, 100)
                following = false
                trackUntil = 0
            }
        }
    }

    // ---- carte de la maison (Gemini Robotics-ER) -------------------------------------------------
    /**
     * Relève le lieu où Joey se trouve : tour du regard en 6 arrêts (60°), à chaque arrêt Robotics-ER pointe les éléments
     * et estime leur distance ; on les place au cap boussole. Bloquant (~30 s).
     */
    private fun mapHere(name: String): String {
        log("🗺 Je relève : $name")
        head.setHomeHere()
        val compass0 = sensors.compass()
        val (hfov, vfov) = camera.frame(5000)?.let { camera.fov(it.width, it.height) } ?: (camera.fovLong to camera.fovShort)
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
        houseMap.put(name, kind, descs.joinToString(" ").take(700), kept, roomba?.x, roomba?.y)
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
            systemPrompt = JoeyPrompt.SYSTEM + (if (robot) JoeyPrompt.ROBOT_STYLE else "") + memory.promptText(),
            tools = GeminiLive.toJson(JoeyPrompt.TOOLS) as JSONArray,
            onTool = ::onTool,
            onTranscript = ::onTranscript,
            onTurnEnd = { lastSpeaker = null; flushTurn() },
            onState = ::onLiveState,
            onError = { e -> log(e); message(e) },
            bargeIn = flag("barge_in", true),
            robot = { flag("robot_voice", true) },
            micOptional = headsetAudioClients > 0,
        )
        live = l
        l.onMicPcm = { pcm, n ->
            voices.feed(pcm, n)
            // Bruit fort et soudain (pas pendant qu'il parle : il s'entendrait lui-même).
            if (_state.value.live != LiveState.SPEAKING && n > 0) {
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
    @Volatile private var lastSeenPan: Double? = null
    @Volatile private var lastSeenAt = 0L

    /**
     * Retrouve la personne qui l'appelle : là où il l'a vue la dernière fois (moins de 15 min), sinon un tour complet en
     * commençant du côté d'où venait la voix ([side] : -1 gauche, +1 droite, 0 inconnu). Avec les roues, s'approche si
     * elle est loin. Apprend aussi dans quel sens sont câblés les micros. Bloquant (fil des mouvements).
     */
    private fun findCaller(side: Int): String {
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
                side != 0 -> side * micSign
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
        if (side != 0) {
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
    private fun maybeSearchSpeaker() {
        val now = System.currentTimeMillis()
        if (!gimbal.ready || !follower.enabled || !flag("camera", true) || follower.sees) return
        // On ne cherche que si personne n'est vu depuis un moment (sinon la tête bouge « toute seule » sans raison).
        if (_state.value.busy || now - follower.seenAt < 8000 || now - lastAutoSearch < 30_000) return
        lastAutoSearch = now
        log("👀 on me parle, je cherche qui")
        moveAsync(holdMs = 0) { findCaller(0) }
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

    private fun onTranscript(person: Boolean, text: String) {
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
    private val longTools = setOf("chercher", "aller_vers", "viens_ici", "cartographier", "regarder_ce_que_je_montre")
    private val toolPool = Executors.newCachedThreadPool()

    private fun onTool(name: String, args: Map<String, Any?>): Map<String, Any?> {
        flushTurn()
        val t0 = System.currentTimeMillis()
        journal.write("outil", "$name ${args}")
        if (name !in longTools) return runTool(name, args).also {
            journal.write("outil", "$name -> $it (${System.currentTimeMillis() - t0} ms)")
        }
        val job = toolPool.submit<Map<String, Any?>> { runTool(name, args) }
        // Réponse rapide (refus, déjà vu…) : on la donne tout de suite.
        runCatching { return job.get(1500, TimeUnit.MILLISECONDS) }
        thread(name = "resultat-$name", isDaemon = true) {
            val r = runCatching { job.get() }.getOrElse { mapOf("result" to "interrompu") }
            journal.write("outil", "$name -> $r (${System.currentTimeMillis() - t0} ms, en arrière-plan)")
            deliverLater(name, args, r)
        }
        return mapOf("result" to "C'est lancé (ça prend quelques secondes). Le résultat t'arrivera tout seul dans un message de " +
            "l'application. En attendant, ne commente pas (au plus « je regarde »), réponds normalement si on te parle, et ne " +
            "dis pas que c'est trouvé ou fait avant d'avoir reçu le résultat.")
    }

    /** Donne à Gemini le résultat d'un outil long, quand personne ne parle (sans couper la parole). */
    private fun deliverLater(name: String, args: Map<String, Any?>, r: Map<String, Any?>) {
        val l = live ?: return
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 15_000 &&
            (_state.value.live != LiveState.LISTENING || System.currentTimeMillis() - lastTalkAt < 1200)) Thread.sleep(150)
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
                    live?.sendFrameNow()
                    return mapOf("result" to if (r.startsWith("trouvé") || r.startsWith("je te vois")) "$r (vérifie sur l'image)" else r)
                }
                if (labels != null) {
                    val person = "person" in labels
                    val found = AtomicBoolean(false)
                    moveSync(holdMs = 0, timeoutMs = 40_000) {
                        found.set(searchLocal(what, labels, keepMs = if (person) 2500 else 15_000))
                        if (found.get()) "trouvé : $what" else "pas trouvé : $what"
                    }
                    // Vérification : une fois la tête centrée, on regarde à nouveau (une fausse détection ne compte pas).
                    var confirmed = false
                    var erNote = ""
                    if (found.get()) {
                        Thread.sleep(900)
                        val now = seenNow()
                        confirmed = now.any { it.label in labels && it.score >= 0.5 } || (person && follower.sees)
                        if (!confirmed) log("🔎 fausse alerte : plus de « $what » une fois la tête tournée")
                        // Un objet : Gemini Robotics-ER a le dernier mot (le détecteur du téléphone se trompe parfois).
                        if (confirmed && !person) {
                            val v = erVerify(what)
                            if (v != null) {
                                confirmed = v.first
                                erNote = if (v.first) " Vérifié par Gemini Robotics-ER" + distanceText(v.second?.distance) + "." else
                                    " Gemini Robotics-ER ne le voit pas : ${v.second?.description}"
                            }
                        }
                    }
                    // Pas trouvé par le détecteur (ou fausse alerte) : Gemini Robotics-ER fait le tour, il voit mieux.
                    if (!confirmed && !person) {
                        var sp: com.joey.app.brain.GeminiVision.Spot? = null
                        log("🔎 je cherche « $what » avec Gemini Robotics-ER")
                        moveSync(holdMs = 8000, timeoutMs = 100_000) {
                            sp = searchSpatial(what)
                            if (sp != null) "trouvé : $what" else "pas trouvé : $what"
                        }
                        live?.sendFrameNow()
                        return sp?.let { mapOf("result" to "trouvé (vérifié par Gemini Robotics-ER) : ta tête est tournée vers « $what »" +
                            distanceText(it.distance) + ". Regarde l'image pour en dire plus.", "description" to it.description) }
                            ?: mapOf("result" to "pas de « $what » en faisant le tour (détecteur puis Gemini Robotics-ER, 360°, deux " +
                                "hauteurs). Peut-être caché, trop loin ou dans le noir. Dis-le honnêtement.")
                    }
                    live?.sendFrameNow()
                    if (confirmed) mapOf("result" to "trouvé (vérifié) : ta tête est tournée vers « $what » et le suit des yeux" +
                        (if (person) " (puis suit son visage)" else " pendant 15 s") + ".$erNote Regarde l'image pour en dire plus.",
                        "vu_maintenant" to objects.describe(seenNow()))
                    else if (found.get()) mapOf("result" to "fausse alerte : j'ai cru voir « $what » en tournant, mais une fois la " +
                        "tête tournée il n'y est pas. Dis-le honnêtement (« j'ai cru… ») et propose de chercher encore.",
                        "vu_maintenant" to objects.describe(seenNow()))
                    else mapOf("result" to "pas de « $what » en faisant le tour (360°, deux hauteurs). Peut-être caché, trop loin, " +
                        "ou dans le noir.")
                } else {
                    var hit: com.joey.app.brain.GeminiVision.Spot? = null
                    var check: Pair<Boolean, com.joey.app.brain.GeminiVision.Spot?>? = null
                    moveSync(holdMs = 8000, timeoutMs = 100_000) {
                        hit = searchSpatial(what)
                        if (hit != null) check = erVerify(what)
                        if (hit != null) "trouvé : $what" else "pas trouvé : $what"
                    }
                    live?.sendFrameNow()
                    val h = hit
                    when {
                        h == null -> mapOf("result" to "pas de « $what » en faisant le tour (Gemini Robotics-ER, 360°, deux hauteurs). " +
                            "Dis-le honnêtement.")
                        check?.first == false -> mapOf("result" to "fausse alerte : Gemini Robotics-ER a cru voir « $what », mais une " +
                            "fois la tête tournée il ne le voit plus (${check?.second?.description}). Dis-le honnêtement.")
                        else -> mapOf("result" to "trouvé (vérifié par Gemini Robotics-ER) : ta tête est tournée vers « $what »" +
                            distanceText((check?.second ?: h).distance) + ".", "description" to h.description)
                    }
                }
            }
            "regarder_personne" -> {
                if (!gimbal.ready) return mapOf("result" to noHead)
                val who = (args["qui"] as? String)?.trim().orEmpty()
                log("👀 je regarde : ${who.ifEmpty { "l'autre" }}")
                var r = ""
                moveSync(holdMs = 0, timeoutMs = 15_000) { r = lookAtPerson(who); r }
                live?.sendFrameNow()
                mapOf("result" to r, "visages_dans_l_image" to chooser.count, "reconnus" to chooser.names().joinToString())
            }
            "regarder_ce_que_je_montre" -> {
                if (!gimbal.ready) return mapOf("result" to noHead)
                if (!flag("camera", true)) return mapOf("result" to "ta caméra est coupée")
                var r: String? = null
                moveSync(holdMs = 8000, timeoutMs = 40_000) { r = lookWherePointed(); r ?: "vision indisponible" }
                live?.sendFrameNow()
                mapOf("result" to (r ?: "Gemini Robotics-ER ne répond pas : demande à la personne ce qu'elle montre") +
                    ". Regarde la nouvelle image pour en parler.")
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
                            "oui" -> { roomba?.sing(com.joey.app.robot.RoombaRobot.Tune.YES); head.nod() }
                            "non" -> { roomba?.sing(com.joey.app.robot.RoombaRobot.Tune.NO); head.shake() }
                            "hesiter" -> { roomba?.sing(com.joey.app.robot.RoombaRobot.Tune.HESITATE); head.hesitate() }
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
                mapOf("result" to busyLearn { r.drive(m) })
            }
            "tourner_corps" -> {
                val r = roomba ?: return mapOf("result" to noWheels)
                val d = ((args["degres"] as? Number)?.toDouble() ?: 90.0).coerceIn(-360.0, 360.0)
                mapOf("result" to busyLearn { r.turn(d) })
            }
            "viens_ici" -> {
                if (roomba == null) return mapOf("result" to noWheels)
                log("🛞 J'arrive")
                var res = ""
                moveSync(holdMs = 0, timeoutMs = 90_000) { res = comeHere(); res }
                mapOf("result" to res)
            }
            "suis_moi" -> {
                if (roomba == null) return mapOf("result" to noWheels)
                followMe(((args["minutes"] as? Number)?.toDouble() ?: 3.0).coerceIn(0.5, 15.0))
                mapOf("result" to "tu suis la personne (dis-lui de marcher doucement) ; « arrete » pour t'arrêter")
            }
            "arrete" -> {
                following = false
                roomba?.stop()
                motionJob?.cancel(true)
                head.stop()
                mapOf("result" to "arrêté")
            }
            "rentrer_base" -> {
                val r = roomba ?: return mapOf("result" to noWheels)
                following = false
                mapOf("result" to r.seekDock())
            }
            "aller_vers" -> {
                if (roomba == null) return mapOf("result" to noWheels)
                if (!gimbal.ready) return mapOf("result" to noHead)
                val what = (args["objet"] as? String)?.trim().orEmpty()
                if (what.isEmpty()) return mapOf("result" to "vers quoi ?")
                log("🛞 Je vais vers : $what")
                var res = ""
                moveSync(holdMs = 3000, timeoutMs = 150_000) { res = goToward(what); res }
                live?.sendFrameNow()
                mapOf("result" to res)
            }
            "aller_a" -> {
                val r = roomba ?: return mapOf("result" to noWheels)
                val lieu = args["lieu"] as? String ?: ""
                val p = houseMap.place(lieu) ?: return mapOf("result" to "« $lieu » n'est pas sur ta carte")
                val px = p.x; val py = p.y
                if (px == null || py == null) return mapOf("result" to "je ne connais pas la position de « $lieu » (relevé fait sans les roues) : " +
                    "il faudra le relever à nouveau en y étant, avec les roues branchées")
                log("🛞 Je vais : ${p.name}")
                var res = ""
                moveSync(holdMs = 1000, timeoutMs = 120_000) { res = r.goTo(px, py); res }
                if (!res.contains("obstacle") && !res.contains("interrompu")) currentPlace = p.name
                mapOf("result" to res)
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
                    "roues" to (roomba?.let { r -> "branchées, batterie ${r.battery ?: "?"} %" + (if (r.onBase) ", sur ta base" else "") +
                        (if (r.charging) ", en charge" else "") + (r.safetyNote?.let { n -> ", dernier arrêt de sécurité : $n" } ?: "") }
                        ?: "pas branchées (Roomba)"),
                    "souvenirs" to memory.size,
                    "visage_vu" to follower.sees,
                    "personne_reconnue" to (seeing() ?: "personne de connu en vue"),
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
        camera.deviceOrientation = { sensors.deviceOrientation }
        head.expression = { exprPose.pan to exprPose.tilt }
        sensors.onMotion = { e -> bodyMoved(e) }
        wake.onLevel = { db -> if (wakeStartle.feed(db.toDouble())) startled() }
        sensors.onShake = { shaken() }
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
