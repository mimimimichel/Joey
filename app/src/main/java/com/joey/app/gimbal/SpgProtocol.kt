package com.joey.app.gimbal

// Protocole Bluetooth de la nacelle Feiyu SPG (décodé depuis une capture : voir PROTOCOLE.md).
// Pur Kotlin, sans Android : à tester avec des tests unitaires (trames réelles de la capture).

object SpgProtocol {
    /** Nom annoncé en Bluetooth : "FY_Spg_" + fin de l'adresse (ex. FY_Spg_82). */
    const val NAME_PREFIX = "FY_Spg"

    /** CRC-16, polynôme 0x1021, départ 0 (XMODEM), calculé sur tout ce qui suit A5 5A. */
    fun crc16(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var c = 0
        for (i in from until to) {
            c = c xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x1021) and 0xFFFF else (c shl 1) and 0xFFFF }
        }
        return c
    }

    /** A5 5A | dest | cmd | longueur | données | CRC (octet bas d'abord). */
    fun frame(dest: Int, cmd: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(5 + payload.size + 2)
        out[0] = 0xA5.toByte(); out[1] = 0x5A
        out[2] = dest.toByte(); out[3] = cmd.toByte(); out[4] = payload.size.toByte()
        payload.copyInto(out, 5)
        val crc = crc16(out, 2, 5 + payload.size)
        out[out.size - 2] = (crc and 0xFF).toByte()
        out[out.size - 1] = (crc shr 8).toByte()
        return out
    }

    /**
     * Joystick virtuel. pan : + = droite, tilt : + = haut. Plage vue dans la capture : environ -320..+320
     * (vers ±300 : ~35°/s en pan, ~20°/s en tilt). L'app Feiyu l'envoie ~20 fois/s tant que le doigt est posé,
     * puis plusieurs fois (0, 0) pour arrêter.
     */
    fun joystick(pan: Int, tilt: Int): ByteArray {
        val p = pan.coerceIn(-512, 511); val t = tilt.coerceIn(-512, 511)
        return frame(0x00, 0x10, byteArrayOf(0xFF.toByte(),
            (p and 0xFF).toByte(), (p shr 8).toByte(), (t and 0xFF).toByte(), (t shr 8).toByte()))
    }

    val STOP: ByteArray get() = joystick(0, 0)

    /** Recentrer la tête (vu juste après un mouvement : la tête revient tout droit en < 1 s). À confirmer. */
    val RECENTER: ByteArray get() = frame(0x00, 0x40, byteArrayOf(0x05, 0x02))

    /** Demande d'information envoyée en boucle par l'app (réponse : dest 03, cmd 40, 06 01 00 28 4B). */
    val PING: ByteArray get() = frame(0x00, 0x40, byteArrayOf(0x06))

    /** Lecture d'un réglage (réponse : dest 03, cmd 06, valeur sur 2 octets). 0x71 vaut 100 : batterie en % ? */
    fun readParam(id: Int, dest: Int = 0x00): ByteArray = frame(dest, 0x06, byteArrayOf(id.toByte()))

    sealed interface Message
    /** Angles en degrés. tilt vaut ±180 tête droite (téléphone monté à l'envers pour la centrale). */
    data class Angles(val tilt: Double, val roll: Double, val pan: Double) : Message
    /** Position du joystick physique du manche (même trame que le joystick virtuel). */
    data class Stick(val pan: Int, val tilt: Int) : Message
    data class Param(val cmd: Int, val data: ByteArray) : Message

    /** Décode une notification de la nacelle ; null si la trame est incomplète ou le CRC faux. */
    fun parse(f: ByteArray): Message? {
        if (f.size < 7 || f[0] != 0xA5.toByte() || f[1] != 0x5A.toByte()) return null
        val n = f[4].toInt() and 0xFF
        if (f.size < 7 + n) return null
        val crc = (f[5 + n].toInt() and 0xFF) or ((f[6 + n].toInt() and 0xFF) shl 8)
        if (crc16(f, 2, 5 + n) != crc) return null
        val dest = f[2].toInt() and 0xFF; val cmd = f[3].toInt() and 0xFF
        fun s16(i: Int) = ((f[i].toInt() and 0xFF) or (f[i + 1].toInt() shl 8)).toShort().toInt()
        return when {
            dest == 0x03 && cmd == 0x10 && n == 7 -> Angles(s16(6) / 100.0, s16(8) / 100.0, s16(10) / 100.0)
            cmd == 0x10 && n == 5 -> Stick(s16(6), s16(8))
            else -> Param(cmd, f.copyOfRange(5, 5 + n))
        }
    }
}
