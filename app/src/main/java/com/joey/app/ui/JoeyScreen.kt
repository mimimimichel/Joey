package com.joey.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joey.app.MainViewModel
import com.joey.app.brain.LiveState
import com.joey.app.face.Activity
import com.joey.app.face.Face
import com.joey.app.gimbal.GimbalState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val PANEL = Color(0xCC111820)
private val ACCENT = Color(0xFF4FC3F7)

/** Écran unique : le visage plein écran ; un appui affiche ou cache les commandes. */
@Composable
fun JoeyScreen(vm: MainViewModel) {
    val s by vm.state.collectAsState()
    var controls by remember { mutableStateOf(true) }
    var headPanel by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var gaze by remember { mutableStateOf(0f to 0f) }
    var lean by remember { mutableStateOf(0f) }
    var thinking by remember { mutableStateOf(false) }
    var pose by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            gaze = vm.gaze()
            lean = vm.screenTilt()
            thinking = vm.isThinking()
            pose = vm.poseText()
            delay(100)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Face(
            mood = s.mood,
            activity = vm.faceActivity(s).let { if (thinking && it != Activity.PARLE) Activity.REFLECHIT else it },
            level = vm::voiceLevel,
            gazeX = gaze.first,
            gazeY = gaze.second,
            tilt = lean,
            modifier = Modifier.fillMaxSize()
                // Toucher : appui = commandes (et il sursaute), double appui = chatouilles, appui long = caresse.
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { controls = !controls; vm.touched() },
                        onDoubleTap = { vm.tickled() },
                        onLongPress = { vm.petted() },
                    )
                }
                // Caresse : glisser le doigt sur l'écran.
                .pointerInput(Unit) {
                    var dist = 0f
                    detectDragGestures(onDragStart = { dist = 0f }, onDragEnd = { if (dist > 400f) vm.petted() }) { change, d ->
                        change.consume()
                        dist += kotlin.math.abs(d.x) + kotlin.math.abs(d.y)
                    }
                },
        )
        s.bigText?.let { t ->
            Text(t, color = Color.White, fontSize = 150.sp, modifier = Modifier.align(Alignment.Center))
        }

        // Nuit : écran presque éteint (le visage reste visible, faiblement).
        val ctx = androidx.compose.ui.platform.LocalContext.current
        LaunchedEffect(s.night) {
            (ctx as? android.app.Activity)?.window?.let { w ->
                val a = w.attributes
                a.screenBrightness = if (s.night) 0.02f else android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                w.attributes = a
            }
        }
        if (s.night) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))

        // Photo : compte à rebours en grand, puis flash blanc.
        s.countdown?.let { n ->
            Text("$n", color = Color.White, fontSize = 160.sp, modifier = Modifier.align(Alignment.Center))
        }
        var flash by remember { mutableStateOf(0f) }
        LaunchedEffect(s.flashAt) {
            if (s.flashAt > 0) { flash = 1f; repeat(10) { delay(30); flash -= 0.1f }; flash = 0f }
        }
        if (flash > 0f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.coerceIn(0f, 1f))))

        // Sous-titres
        if (s.subtitles && s.lines.isNotEmpty() && s.live != LiveState.OFF) {
            Column(
                Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 88.dp, start = 16.dp, end = 16.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                s.lines.takeLast(2).forEach { l ->
                    Text(
                        (if (l.joey) "" else "🗣 ") + l.text.trim(),
                        color = if (l.joey) ACCENT else Color(0xFFB0BEC5),
                        fontSize = 15.sp,
                        maxLines = 3,
                    )
                }
            }
        }

        if (controls) {
            // Barre d'état
            Row(
                Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(12.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Chip(when (s.gimbal) {
                    GimbalState.READY -> "🦒 Nacelle prête" + (if (s.battery >= 0) " · 🔋${s.battery}%" else "")
                    GimbalState.SEARCHING -> "🦒 Recherche…"
                    GimbalState.CONNECTING -> "🦒 Connexion…"
                    GimbalState.OFF -> "🦒 Nacelle déconnectée"
                }) { headPanel = true }
                if (s.watch) Chip("🛡 Garde")
                s.roomba?.let { Chip("🛞 $it") }
                if (s.headsetFollow) Chip("🥽 Suit le casque")
                else if (s.tracking != null) Chip("🔎 ${s.tracking}")
                else if (s.followFace && s.gimbal == GimbalState.READY) Chip(when {
                    s.seeing != null && s.seesFace -> "👤 ${s.seeing}"
                    s.seesFace -> "👤 Je te vois"
                    else -> "👤 Personne"
                })
                Chip(when (s.live) {
                    LiveState.OFF -> if (s.wakeWord) "💤 Dis « Joey »" else "💤 En veille"
                    LiveState.CONNECTING -> "… Réveil"
                    LiveState.LISTENING -> "👂 J'écoute"
                    LiveState.SPEAKING -> "🗨 Je parle"
                })
                Spacer(Modifier.weight(1f))
                RoundButton("⚙") { settings = true }
            }
            // Boutons du bas
            Row(
                Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundButton("🦒", size = 56) { headPanel = !headPanel }
                RoundButton(if (s.live == LiveState.OFF) "🎤" else "■", size = 72,
                    color = if (s.live == LiveState.OFF) ACCENT else Color(0xFFE57373)) { vm.micPressed() }
            }
        }

        if (headPanel) HeadPanel(vm, s, pose) { headPanel = false }
        if (settings) SettingsDialog(vm, s) { settings = false }

        s.message?.let { m ->
            AlertDialog(
                onDismissRequest = { vm.message(null) },
                text = { Text(m) },
                confirmButton = { TextButton(onClick = { vm.message(null) }) { Text("OK") } },
            )
        }
    }
}

@Composable
private fun Chip(text: String, onClick: (() -> Unit)? = null) {
    Text(
        text,
        color = Color.White,
        fontSize = 13.sp,
        modifier = Modifier.background(PANEL, RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun RoundButton(label: String, size: Int = 44, color: Color = PANEL, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).background(color, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = (size / 2.4).sp, color = Color.White) }
}

/** Panneau « tête » : connexion de la nacelle, joystick, gestes, journal. */
@Composable
private fun HeadPanel(vm: MainViewModel, s: com.joey.app.UiState, pose: String, close: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), contentAlignment = Alignment.CenterStart) {
        Column(
            Modifier.width(340.dp).heightIn(max = 640.dp).background(PANEL, RoundedCornerShape(16.dp)).padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tête (nacelle SPG)", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = close) { Text("Fermer") }
            }
            if (s.gimbal == GimbalState.OFF) {
                Button(onClick = vm::connectGimbal, modifier = Modifier.fillMaxWidth()) { Text("Connecter la nacelle") }
                Text("Allume la nacelle et ferme l'app Feiyu (une seule app à la fois).", color = Color.Gray, fontSize = 12.sp)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(pose.ifBlank { s.gimbal.name.lowercase() }, color = ACCENT, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::disconnectGimbal) { Text("Déconnecter") }
                }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Joystick { x, y -> vm.manualStick(x, y) } }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(onClick = vm::recenter) { Text("Centrer") }
                FilledTonalButton(onClick = vm::nod) { Text("Oui") }
                FilledTonalButton(onClick = vm::shake) { Text("Non") }
            }
            FilledTonalButton(onClick = vm::sweep, modifier = Modifier.fillMaxWidth()) { Text("Regarder autour") }
            FilledTonalButton(onClick = vm::calibrateHead, modifier = Modifier.fillMaxWidth()) { Text("Mesurer la tête (vitesse et sens)") }
            if (s.roomba == null) {
                Button(onClick = { vm.connectRoomba() }, modifier = Modifier.fillMaxWidth()) { Text("🛞 Brancher le Roomba (roues)") }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🛞 Roomba ${s.roomba}", color = ACCENT, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::disconnectRoomba) { Text("Débrancher") }
                }
            }
            if (s.headsetUrl == null) {
                Button(onClick = { vm.setHeadset(true) }, modifier = Modifier.fillMaxWidth()) { Text("🥽 Vue dans le casque (Quest)") }
            } else {
                Text("🥽 Dans le Quest : ${s.headsetUrl.ifBlank { "(pas de Wi-Fi)" }}", color = ACCENT, fontSize = 14.sp)
                TextButton(onClick = { vm.setHeadset(false) }) { Text("Arrêter la vue casque") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Journal", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { clipboard.setText(AnnotatedString(s.log.joinToString("\n"))) }) { Text("Copier") }
            }
            val listState = rememberLazyListState()
            LaunchedEffect(s.log.size) { if (s.log.isNotEmpty()) listState.scrollToItem(s.log.size - 1) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp), state = listState) {
                items(s.log) { Text(it, color = Color(0xFFB0BEC5), fontSize = 11.sp) }
            }
        }
    }
}

/** Joystick à l'écran : on tire le rond, la tête tourne ; on lâche, elle s'arrête. */
@Composable
private fun Joystick(onMove: (Float, Float) -> Unit) {
    val radius = 70f
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier.size(160.dp).border(2.dp, ACCENT.copy(alpha = 0.6f), CircleShape)
            .pointerInput(Unit) {
                val r = radius * density
                detectDragGestures(
                    onDragEnd = { dx = 0f; dy = 0f; onMove(0f, 0f) },
                    onDragCancel = { dx = 0f; dy = 0f; onMove(0f, 0f) },
                ) { change, drag ->
                    change.consume()
                    var nx = dx + drag.x
                    var ny = dy + drag.y
                    val d = sqrt(nx * nx + ny * ny)
                    if (d > r) { nx *= r / d; ny *= r / d }
                    dx = nx; dy = ny
                    onMove(nx / r, -ny / r)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.offset { IntOffset(dx.roundToInt(), dy.roundToInt()) }.size(56.dp).background(ACCENT, CircleShape))
    }
}

@Composable
private fun SettingsDialog(vm: MainViewModel, s: com.joey.app.UiState, close: () -> Unit) {
    var key by remember { mutableStateOf(vm.geminiKey) }
    var robot by remember { mutableStateOf(s.robotVoice) }
    var camera by remember { mutableStateOf(s.camera) }
    var front by remember { mutableStateOf(s.frontCamera) }
    var invert by remember { mutableStateOf(s.invertPan) }
    var barge by remember { mutableStateOf(s.bargeIn) }
    var subs by remember { mutableStateOf(s.subtitles) }
    var follow by remember { mutableStateOf(s.followFace) }
    var wakeWord by remember { mutableStateOf(s.wakeWord) }
    var life by remember { mutableStateOf(s.idleLife) }
    var ntfy by remember { mutableStateOf(s.ntfyTopic) }
    var speed by remember { mutableStateOf(s.headSpeed) }
    var confirmForget by remember { mutableStateOf(false) }
    var showMemories by remember { mutableStateOf(false) }
    var newPerson by remember { mutableStateOf("") }
    var haUrl by remember { mutableStateOf(s.haUrl) }
    var haToken by remember { mutableStateOf(s.haToken) }
    var showMap by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Réglages") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = key, onValueChange = { key = it.trim() }, label = { Text("Clé Gemini") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Gratuite sur aistudio.google.com (« Get API key »).", fontSize = 12.sp, color = Color.Gray)
                var saved by remember { mutableStateOf<String?>(null) }
                androidx.compose.material3.Button(onClick = {
                    // Conversations + ce qu'il a fait, des 3 derniers jours, dans les Téléchargements du téléphone.
                    saved = "Enregistrement…"
                    kotlin.concurrent.thread { saved = vm.saveJournalToDownloads() }
                }, modifier = Modifier.fillMaxWidth()) { Text("📥 Enregistrer le journal (Téléchargements)") }
                saved?.let { Text(it, fontSize = 12.sp, color = Color.Gray) }
                Toggle("Envoyer le journal à Claude (pour l'améliorer)", vm.flagValue("upload_journal", true)) { vm.setFlagValue("upload_journal", it) }
                Toggle("Voix de robot", robot) { robot = it }
                Toggle("Joey voit (caméra)", camera) { camera = it }
                Toggle("Caméra avant (côté écran)", front) { front = it }
                Toggle("Mot de réveil « Joey » (sans bouton)", wakeWord) { wakeWord = it }
                Toggle("Vie propre (curiosité, ennui, sommeil)", life) { life = it }
                Toggle("Gestes (pouce, coucou, doigt pointé)", vm.flagValue("gestures", true)) { vm.setFlagValue("gestures", it) }
                Toggle("Mode nuit (23 h - 7 h)", vm.flagValue("night_mode", true)) { vm.setFlagValue("night_mode", it) }
                Toggle("Langage du corps (hochements, respiration)", vm.flagValue("body_language", true)) { vm.setFlagValue("body_language", it) }
                Toggle("Il parle de lui-même (salue, remarque)", vm.flagValue("initiatives", true)) { vm.setFlagValue("initiatives", it) }
                Toggle("Sursaute aux bruits forts", vm.flagValue("startle", true)) { vm.setFlagValue("startle", it) }
                Toggle("Vision avancée automatique (regard partagé, nouveautés : consomme le quota Gemini)", vm.flagValue("auto_vision", false)) { vm.setFlagValue("auto_vision", it) }
                Toggle("Journal du jour (21 h 30, sur ton téléphone)", vm.flagValue("diary", true)) { vm.setFlagValue("diary", it) }
                Toggle("🛡 Mode garde (journal, rondes, alertes)", s.watch) { vm.setWatch(it) }
                OutlinedTextField(value = ntfy, onValueChange = { ntfy = it.trim() }, singleLine = true,
                    label = { Text("Alertes sur ton téléphone : sujet ntfy") }, modifier = Modifier.fillMaxWidth())
                Row {
                    TextButton(onClick = { ntfy = "joey-" + java.util.UUID.randomUUID().toString().take(10) }) { Text("Inventer un sujet") }
                    TextButton(onClick = { vm.setNtfy(ntfy) }) { Text("Tester l'alerte") }
                }
                Text("Installe l'app « ntfy » sur ton téléphone et abonne-toi à ce sujet.", fontSize = 12.sp, color = Color.Gray)
                Toggle("Suivre mon visage", follow) { follow = it }
                Text("Vitesse de la tête", fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(35 to "Douce", 60 to "Vive", 85 to "Rapide", 100 to "Max").forEach { (v, label) ->
                        if (speed == v) Button(onClick = {}) { Text(label, fontSize = 12.sp) }
                        else TextButton(onClick = { speed = v }) { Text(label, fontSize = 12.sp) }
                    }
                }
                Toggle("Inverser gauche / droite de la tête", invert) { invert = it }
                Toggle("Pouvoir lui couper la parole", barge) { barge = it }
                Toggle("Sous-titres", subs) { subs = it }
                Text("Souvenirs : ${s.memories} (gardés sur le téléphone)", fontSize = 13.sp)
                TextButton(onClick = { showMemories = true }) { Text("Voir ses souvenirs") }
                TextButton(onClick = { showMap = true }) { Text("🗺 Voir sa carte de la maison") }
                if (s.voices.isNotEmpty()) Text("🎙 Voix apprises : ${s.voices.joinToString()}", fontSize = 13.sp)
                Text("🏠 Maison connectée (Home Assistant)", fontSize = 13.sp)
                OutlinedTextField(value = haUrl, onValueChange = { haUrl = it.trim() }, singleLine = true,
                    label = { Text("Adresse (http://homeassistant.local:8123)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = haToken, onValueChange = { haToken = it.trim() }, singleLine = true,
                    label = { Text("Jeton d'accès longue durée") }, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { vm.setHomeAssistant(haUrl, haToken) }) { Text("Relier Home Assistant") }
                Text("Gens de la maison (visages reconnus, gardés sur le téléphone)", fontSize = 13.sp)
                s.people.forEach { who ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("👤 $who", fontSize = 14.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.forgetFace(who) }) { Text("Oublier") }
                    }
                }
                OutlinedTextField(value = newPerson, onValueChange = { newPerson = it }, label = { Text("Prénom") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(enabled = newPerson.isNotBlank(), onClick = { vm.learnFaceUi(newPerson); newPerson = "" }) {
                    Text("Apprendre ce visage (se mettre en face de Joey)")
                }
                TextButton(onClick = { confirmForget = true }) { Text("Effacer toute sa mémoire") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (ntfy != s.ntfyTopic) vm.setNtfy(ntfy)
                vm.saveSettings(key, robot, camera, front, invert, barge, subs, follow, speed, wakeWord, life)
                close()
            }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Annuler") } },
    )
    if (showMemories) MemoriesDialog(vm) { showMemories = false }
    if (showMap) MapDialog(vm) { showMap = false }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            text = { Text("Effacer tout ce que Joey a retenu ?") },
            confirmButton = { TextButton(onClick = { vm.forgetAll(); confirmForget = false }) { Text("Effacer") } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Non") } },
        )
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked = value, onCheckedChange = onChange)
    }
}

/** Les souvenirs de Joey, du plus récent au plus ancien ; ✕ pour en effacer un. */
@Composable
private fun MemoriesDialog(vm: MainViewModel, close: () -> Unit) {
    var list by remember { mutableStateOf(vm.memories()) }
    val fmt = remember { java.text.SimpleDateFormat("d MMM", java.util.Locale.FRENCH) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Souvenirs de Joey (${list.size})") },
        text = {
            if (list.isEmpty()) Text("Aucun souvenir pour l'instant. Parle-lui : il retient tout seul ce qui compte.")
            else LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(list) { n ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text((if (n.auto) "💬 " else "🧠 ") + n.text + "  · " + fmt.format(java.util.Date(n.time)),
                            fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.forgetOne(n); list = vm.memories() }) { Text("✕") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Fermer") } },
    )
}

/** La carte de la maison : les lieux relevés et leurs éléments (direction boussole, distance). */
@Composable
private fun MapDialog(vm: MainViewModel, close: () -> Unit) {
    var places by remember { mutableStateOf(vm.mapPlaces()) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Carte de la maison (${places.size} lieux)") },
        text = {
            if (places.isEmpty()) Text("Aucun lieu relevé. Dis à Joey : « cartographie ici, c'est le salon ».")
            else LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(places) { p ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("📍 ${p.name} (${p.kind})", fontSize = 15.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.removePlace(p.name); places = vm.mapPlaces() }) { Text("✕") }
                        }
                        Text(p.items.joinToString(", ") { it.name + (it.heading?.let { h -> " (" + com.joey.app.brain.HouseMap.compassName(h) + ")" } ?: "") },
                            fontSize = 12.sp, color = Color(0xFFB0BEC5))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Fermer") } },
    )
}
