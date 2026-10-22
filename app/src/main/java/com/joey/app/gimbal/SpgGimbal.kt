package com.joey.app.gimbal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Ce dont la tête a besoin : envoyer le joystick, recentrer, lire les angles. Remplacé par un faux dans les tests. */
interface GimbalLink {
    val ready: Boolean
    /** Derniers angles reçus (null tant que rien n'est arrivé). */
    val angles: SpgProtocol.Angles?
    /** Heure (ms) des derniers angles reçus. */
    val anglesAt: Long
    /** Joystick tenu en continu (renvoyé ~20 fois/s) jusqu'à stick(0, 0). */
    fun stick(pan: Int, tilt: Int)
    fun recenter()
}

enum class GimbalState { OFF, SEARCHING, CONNECTING, READY }

/**
 * Liaison Bluetooth basse consommation avec la nacelle Feiyu SPG (nom « FY_Spg_xx »).
 * Protocole : voir hardware/gimbal-spg/PROTOCOLE.md. Écrit dans le journal ([onLog]) les services trouvés,
 * pour qu'on puisse figer les UUID plus tard.
 */
@SuppressLint("MissingPermission") // permissions Bluetooth demandées par l'activité avant connect()
class SpgGimbal(
    private val context: Context,
    private val savedAddress: () -> String?,
    private val saveAddress: (String) -> Unit,
    private val onState: (GimbalState) -> Unit,
    private val onLog: (String) -> Unit,
    private val onBattery: (Int) -> Unit,
) : GimbalLink {
    companion object {
        private const val TAG = "SpgGimbal"
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        // Handles vus dans la capture (Android les donne avec getInstanceId()).
        private const val WRITE_HANDLE = 0x2F
        private const val NOTIFY_HANDLE = 0x2C
        private const val BATTERY_PARAM = 0x71
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

    @Volatile private var wanted = false
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var state = GimbalState.OFF
    @Volatile private var scanning = false
    /** Numéro de la tentative de connexion en cours (les minuteurs des anciennes tentatives ne font plus rien). */
    @Volatile private var attempt = 0

    @Volatile override var angles: SpgProtocol.Angles? = null
        private set
    @Volatile override var anglesAt = 0L
        private set
    override val ready get() = state == GimbalState.READY

    /** Derniers mouvements du joystick physique du manche (pour l'afficher). */
    @Volatile var handStick: SpgProtocol.Stick? = null
        private set

    @Volatile private var stickPan = 0
    @Volatile private var stickTilt = 0
    @Volatile private var lastParam = -1
    @Volatile var battery = -1
        private set

    private val writes = LinkedBlockingQueue<ByteArray>()
    @Volatile private var writeDone: CountDownLatch? = null

    init {
        thread(name = "spg-write", isDaemon = true) { writeLoop() }
        thread(name = "spg-stick", isDaemon = true) { stickLoop() }
    }

    private fun setState(s: GimbalState) {
        if (s == state) return
        state = s
        onState(s)
    }

    private fun log(t: String) {
        Log.i(TAG, t)
        onLog(t)
    }

    // ---- connexion ----------------------------------------------------------------------------
    fun connect() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            log("Bluetooth éteint : allume-le puis réessaie.")
            return
        }
        wanted = true
        if (state != GimbalState.OFF) return
        val addr = savedAddress()
        if (addr != null && BluetoothAdapter.checkBluetoothAddress(addr)) {
            log("Connexion directe à $addr…")
            val gen = ++attempt
            connectTo(a.getRemoteDevice(addr))
            // Si rien au bout de 8 s : on cherche à nouveau (la nacelle a pu changer d'adresse). Seulement pour CETTE
            // tentative (sinon un vieux minuteur coupait une connexion en train de réussir).
            main.postDelayed({ if (gen == attempt && wanted && state == GimbalState.CONNECTING) { closeGatt(); scan() } }, 8000)
        } else {
            scan()
        }
    }

    fun disconnect() {
        wanted = false
        stopScan()
        stickPan = 0; stickTilt = 0
        closeGatt()
        setState(GimbalState.OFF)
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull() ?: return
            if (!name.startsWith(SpgProtocol.NAME_PREFIX, ignoreCase = true)) return
            if (!scanning) return
            stopScan()
            log("Trouvée : $name (${result.device.address})")
            saveAddress(result.device.address)
            connectTo(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            log("Recherche Bluetooth refusée (code $errorCode). Sur Android 9-11, active la localisation.")
            scanning = false
            setState(GimbalState.OFF)
        }
    }

    private fun scan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        setState(GimbalState.SEARCHING)
        log("Recherche de la nacelle (allume-la, et ferme l'app Feiyu)…")
        scanning = true
        runCatching {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
        }.onFailure { log("Recherche impossible : ${it.message}"); scanning = false; setState(GimbalState.OFF) }
        val gen = ++attempt
        main.postDelayed({
            if (scanning && gen == attempt && state == GimbalState.SEARCHING) {
                stopScan()
                val locOff = Build.VERSION.SDK_INT < Build.VERSION_CODES.S && runCatching {
                    val lm = context.getSystemService(android.location.LocationManager::class.java)
                    if (Build.VERSION.SDK_INT >= 28) !lm.isLocationEnabled else false
                }.getOrDefault(false)
                log(if (locOff) "Nacelle introuvable : la LOCALISATION du téléphone est coupée (Android 9 en a besoin pour le Bluetooth). Active-la."
                    else "Nacelle introuvable. Est-elle allumée ? Est-elle encore reliée à un autre téléphone (Joey ouvert sur le S10, " +
                        "ou l'app Feiyu) ? Une nacelle ne parle qu'à un téléphone à la fois.")
                setState(GimbalState.OFF)
                wanted = false
            }
        }, 15_000)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCb) }
    }

    private fun connectTo(device: BluetoothDevice) {
        setState(GimbalState.CONNECTING)
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, callback)
        }
    }

    private fun closeGatt() {
        val g = gatt
        gatt = null
        writeChar = null
        writes.clear()
        writeDone?.countDown()
        runCatching { g?.disconnect() }
        runCatching { g?.close() }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) return
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                log("Connectée, lecture des services…")
                main.postDelayed({ runCatching { g.discoverServices() } }, 300)
            } else {
                log("Nacelle déconnectée (état $newState, code $status).")
                closeGatt()
                setState(GimbalState.OFF)
                if (wanted) main.postDelayed({ if (wanted && state == GimbalState.OFF) connect() }, 3000)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            var write: BluetoothGattCharacteristic? = null
            var notify: BluetoothGattCharacteristic? = null
            for (s in g.services) {
                log("Service ${s.uuid}")
                for (c in s.characteristics) {
                    val p = c.properties
                    log("  car. ${c.uuid} handle 0x%02X%s%s%s".format(c.instanceId,
                        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) " écriture-sans-réponse" else "",
                        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) " écriture" else "",
                        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) " notification" else ""))
                    val standard = s.uuid.toString().let { it.startsWith("00001800") || it.startsWith("00001801") || it.startsWith("0000180a") }
                    if (standard) continue
                    if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 &&
                        (write == null || c.instanceId == WRITE_HANDLE)) write = c
                    if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 &&
                        (notify == null || c.instanceId == NOTIFY_HANDLE)) notify = c
                }
            }
            if (write == null || notify == null) {
                log("Caractéristiques de la nacelle introuvables (voir la liste ci-dessus).")
                closeGatt()
                setState(GimbalState.OFF)
                return
            }
            log("Choisies : écriture ${write.uuid}, notification ${notify.uuid}")
            writeChar = write
            g.setCharacteristicNotification(notify, true)
            notify.getDescriptor(CCCD)?.let { d ->
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(d)
                }
            }
            // Comme l'app Feiyu : on se présente, puis on lit la batterie.
            main.postDelayed({
                send(SpgProtocol.PING)
                send(SpgProtocol.readParam(0x7F))
                readBattery()
                stopScan() // connectée : plus besoin de chercher
                setState(GimbalState.READY)
                log("Nacelle prête.")
            }, 400)
        }

        @Deprecated("Android < 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            received(c.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
            received(value)

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeDone?.countDown()
        }
    }

    // ---- réception ----------------------------------------------------------------------------
    private fun received(data: ByteArray) {
        // Une notification = une trame en général ; on découpe quand même si plusieurs sont collées.
        var i = 0
        while (i + 7 <= data.size) {
            if (data[i] != 0xA5.toByte() || data[i + 1] != 0x5A.toByte()) { i++; continue }
            val n = data[i + 4].toInt() and 0xFF
            val end = i + 7 + n
            if (end > data.size) break
            when (val m = SpgProtocol.parse(data.copyOfRange(i, end))) {
                is SpgProtocol.Angles -> { angles = m; anglesAt = System.currentTimeMillis() }
                is SpgProtocol.Stick -> handStick = m
                is SpgProtocol.Param -> if (m.cmd == 0x06 && m.data.size >= 2 && lastParam == BATTERY_PARAM) {
                    val v = (m.data[0].toInt() and 0xFF) or ((m.data[1].toInt() and 0xFF) shl 8)
                    if (v in 0..100) { battery = v; onBattery(v) }
                }
                null -> {}
            }
            i = end
        }
    }

    fun readBattery() {
        lastParam = BATTERY_PARAM
        send(SpgProtocol.readParam(BATTERY_PARAM))
    }

    // ---- envoi ----------------------------------------------------------------------------------
    fun send(frame: ByteArray) {
        if (writeChar != null) writes.offer(frame)
    }

    override fun stick(pan: Int, tilt: Int) {
        stickPan = pan
        stickTilt = tilt
    }

    override fun recenter() = send(SpgProtocol.RECENTER)

    /** Joystick renvoyé 20 fois par seconde tant qu'il n'est pas au centre, puis 3 arrêts (comme l'app Feiyu). */
    private fun stickLoop() {
        var stops = 0
        var lastPing = 0L
        while (true) {
            Thread.sleep(50)
            if (state != GimbalState.READY) continue
            val p = stickPan; val t = stickTilt
            if (p != 0 || t != 0) {
                send(SpgProtocol.joystick(p, t))
                stops = 3
            } else if (stops > 0) {
                send(SpgProtocol.STOP)
                stops--
            }
            val now = System.currentTimeMillis()
            if (now - lastPing > 2000) {
                lastPing = now
                send(SpgProtocol.PING)
                if (now / 2000 % 30 == 0L) readBattery() // environ toutes les minutes
            }
        }
    }

    private fun writeLoop() {
        while (true) {
            val f = writes.take()
            val g = gatt ?: continue
            val c = writeChar ?: continue
            val done = CountDownLatch(1)
            writeDone = done
            val ok = runCatching {
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(c, f, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    @Suppress("DEPRECATION")
                    c.value = f
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(c)
                }
            }.getOrDefault(false)
            if (ok) done.await(150, TimeUnit.MILLISECONDS) else Thread.sleep(20)
        }
    }
}
