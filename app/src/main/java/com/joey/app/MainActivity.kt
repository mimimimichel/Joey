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
        if (granted(Manifest.permission.CAMERA)) vm.startCamera(this)
        val bt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) granted(Manifest.permission.BLUETOOTH_CONNECT) &&
            granted(Manifest.permission.BLUETOOTH_SCAN) else granted(Manifest.permission.ACCESS_FINE_LOCATION)
        if (bt) vm.connectGimbal() else vm.log("Permission Bluetooth refusée : la tête ne pourra pas bouger.")
        if (!granted(Manifest.permission.RECORD_AUDIO)) vm.log("Permission micro refusée : Joey ne t'entendra pas.")
        vm.updateWake()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lastCrash = CrashReport.take(this)
        CrashReport.install(this)
        CrashReport.mark("lancement")
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // le visage reste allumé
        // Plein écran : c'est un visage, pas une app.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF4FC3F7))) {
                JoeyScreen(vm)
                var crash by remember { mutableStateOf(lastCrash) }
                crash?.let { report ->
                    val clipboard = LocalClipboardManager.current
                    AlertDialog(
                        onDismissRequest = { crash = null },
                        title = { Text("Joey a planté la dernière fois") },
                        text = {
                            Text("Copie ce rapport et envoie-le pour corriger le problème :\n\n$report",
                                fontSize = 11.sp, modifier = Modifier.verticalScroll(rememberScrollState()))
                        },
                        confirmButton = {
                            TextButton(onClick = { clipboard.setText(AnnotatedString(report)); crash = null }) { Text("Copier") }
                        },
                        dismissButton = { TextButton(onClick = { crash = null }) { Text("Fermer") } },
                    )
                }
            }
        }
        if (needed.all(::granted)) afterPermissions() else ask.launch(needed)
    }
}
