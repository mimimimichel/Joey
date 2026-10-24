package com.joey.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.joey.app.ui.JoeyScreen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val needed: Array<String>
        get() = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT < 29) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.toTypedArray()

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private val ask = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { afterPermissions() }

    private fun afterPermissions() {
        CrashReport.mark("permissions ok")
        if (granted(Manifest.permission.CAMERA)) vm.startCamera(this)
        CrashReport.mark("caméra lancée")
        val bt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) granted(Manifest.permission.BLUETOOTH_CONNECT) &&
            granted(Manifest.permission.BLUETOOTH_SCAN) else granted(Manifest.permission.ACCESS_FINE_LOCATION)
        if (bt) {
            // Bluetooth coupé (ça arrive tout seul) : on propose de le rallumer, et on se reconnecte dès qu'il revient.
            val adapter = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
            if (adapter != null && !adapter.isEnabled) {
                runCatching {
                    @Suppress("MissingPermission")
                    startActivity(android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }
            }
            vm.connectGimbal()
        } else vm.log("Permission Bluetooth refusée : la tête ne pourra pas bouger.")
        if (!granted(Manifest.permission.RECORD_AUDIO)) vm.log("Permission micro refusée : Joey ne t'entendra pas.")
        CrashReport.mark("nacelle lancée")
        vm.updateWake()
        CrashReport.mark("mot de réveil lancé")
        vm.connectRoomba(quiet = true)
        CrashReport.mark("démarrage fini")
    }

    /** Plein écran remis quand on revient sur l'app (Android 9 le perd parfois). */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && Build.VERSION.SDK_INT < 30) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    /** Carte USB-série du Roomba branchée pendant que Joey tourne. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) vm.connectRoomba()
    }

    /** Bluetooth rallumé : la nacelle se reconnecte toute seule. */
    private val btWatcher = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: android.content.Intent) {
            val st = i.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1)
            if (st == android.bluetooth.BluetoothAdapter.STATE_ON) { vm.log("Bluetooth rallumé : je reconnecte ma nacelle"); vm.connectGimbal() }
            if (st == android.bluetooth.BluetoothAdapter.STATE_OFF) vm.log("⚠️ Le Bluetooth vient de se couper : ma nacelle est déconnectée")
        }
    }

    // ---- manette (Xbox…) : prise avant l'écran, sinon B ferme l'app (c'est « retour » pour Android) ----------------
    private fun fromPad(source: Int) = (source and android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD ||
        (source and android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (started && fromPad(event.source)) {
            val b = when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_BUTTON_A -> com.joey.app.remote.Gamepad.Button.A
                android.view.KeyEvent.KEYCODE_BUTTON_B -> com.joey.app.remote.Gamepad.Button.B
                android.view.KeyEvent.KEYCODE_BUTTON_X -> com.joey.app.remote.Gamepad.Button.X
                android.view.KeyEvent.KEYCODE_BUTTON_Y -> com.joey.app.remote.Gamepad.Button.Y
                android.view.KeyEvent.KEYCODE_BUTTON_L1 -> com.joey.app.remote.Gamepad.Button.LB
                android.view.KeyEvent.KEYCODE_BUTTON_R1 -> com.joey.app.remote.Gamepad.Button.RB
                android.view.KeyEvent.KEYCODE_BUTTON_START -> com.joey.app.remote.Gamepad.Button.START
                android.view.KeyEvent.KEYCODE_BUTTON_SELECT -> com.joey.app.remote.Gamepad.Button.SELECT
                else -> null
            }
            if (b != null) {
                if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) vm.padButton(b)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        if (started && fromPad(event.source) && event.action == android.view.MotionEvent.ACTION_MOVE) {
            fun ax(a: Int) = event.getAxisValue(a)
            val rt = maxOf(ax(android.view.MotionEvent.AXIS_RTRIGGER), ax(android.view.MotionEvent.AXIS_GAS))
            vm.padAxes(ax(android.view.MotionEvent.AXIS_X), ax(android.view.MotionEvent.AXIS_Y),
                ax(android.view.MotionEvent.AXIS_Z), ax(android.view.MotionEvent.AXIS_RZ), rt)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(btWatcher) }
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { registerReceiver(btWatcher, android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)) }
        // Le rapport du plantage précédent (lu par JoeyApp avant tout le reste).
        val app = application as? JoeyApp
        val lastCrash = app?.lastCrash
        app?.lastCrash = null
        if (app == null) CrashReport.install(this)
        CrashReport.mark("lancement")
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // le visage reste allumé
        // C'est un robot : relancé écran éteint et verrouillé (mise à jour par le PC la nuit), il s'affiche quand même par-dessus
        // l'écran de verrouillage et rallume l'écran (24/10 : relancé derrière le verrouillage, il ne répondait plus au PC).
        // Le reste du téléphone reste verrouillé.
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        // Sur le S8, « allumer l'écran » ne suffit pas quand il est relancé écran éteint (24/10, 19 h 51) : on l'allume.
        runCatching { vm.wakeScreen("démarrage") }
        // Plein écran : c'est un visage, pas une app.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            // Android 9 et avant : on force aussi l'ancien plein écran (sinon la barre restait visible sur le S8+).
            if (Build.VERSION.SDK_INT < 30) {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
            }
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // Il a planté la dernière fois : on montre le rapport SEUL, avant de démarrer quoi que ce soit
        // (sinon il replante avant de pouvoir l'afficher).
        if (lastCrash != null) {
            setContent {
                MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF4FC3F7))) {
                    CrashScreen(lastCrash) { startJoey() }
                }
            }
            return
        }
        startJoey()
    }

    private var started = false

    private fun startJoey() {
        if (started) return
        started = true
        CrashReport.mark("démarrage de Joey")
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF4FC3F7))) {
                JoeyScreen(vm)
            }
        }
        if (needed.all(::granted)) afterPermissions() else ask.launch(needed)
    }

}

@androidx.compose.runtime.Composable
private fun CrashScreen(report: String, onContinue: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxSize().background(Color.Black).padding(24.dp),
    ) {
        Text("Joey a planté la dernière fois", color = Color.White, fontSize = 20.sp)
        Text("Appuie sur « Copier » et envoie le texte pour qu'on corrige.", color = Color.LightGray, fontSize = 14.sp,
            modifier = Modifier.padding(vertical = 8.dp))
        androidx.compose.foundation.layout.Row {
            androidx.compose.material3.Button(onClick = { clipboard.setText(AnnotatedString(report)); copied = true }) {
                Text(if (copied) "Copié ✓" else "Copier")
            }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))
            TextButton(onClick = onContinue) { Text("Lancer Joey quand même") }
        }
        Text(report, color = Color(0xFFB0BEC5), fontSize = 11.sp,
            modifier = Modifier.padding(top = 12.dp).verticalScroll(rememberScrollState()))
    }
}
