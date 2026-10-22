package com.joey.app.brain

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Les voix des gens de la maison : un court enregistrement de référence par personne (gardé sur le téléphone),
 * et la parole récente entendue par Joey (anneau de quelques secondes, seulement quand quelqu'un parle).
 * L'identification compare un extrait aux références (Gemini, qui sait comparer des voix).
 */
class Voices(private val dir: File, private val sampleRate: Int = 16_000) {
    init { dir.mkdirs() }

    // ---- parole récente (anneau, ~8 s de parole) ----
    private val ring = ShortArray(sampleRate * 8)
    private var pos = 0
    private var filled = 0
    @Volatile private var recording: ShortArrayList? = null

    class ShortArrayList { val data = ArrayList<Short>() }

    /** Son du micro (PCM 16 bits) : on ne garde que les moments où quelqu'un parle. */
    fun feed(pcm: ShortArray, n: Int) {
        var e = 0.0
        for (i in 0 until n) e += pcm[i] * pcm[i].toDouble()
        val rms = kotlin.math.sqrt(e / n.coerceAtLeast(1)) / 32768.0
        if (rms < 0.015) return // silence
        synchronized(ring) {
            for (i in 0 until n) { ring[pos] = pcm[i]; pos = (pos + 1) % ring.size }
            filled = (filled + n).coerceAtMost(ring.size)
        }
        recording?.let { r -> synchronized(r) { for (i in 0 until n) r.data.add(pcm[i]) } }
    }

    /** Les [seconds] dernières secondes de parole entendues (WAV), ou null s'il n'y en a pas assez. */
    fun recentSpeech(seconds: Double = 5.0): ByteArray? = synchronized(ring) {
        val want = (seconds * sampleRate).toInt().coerceAtMost(filled)
        if (want < sampleRate * 1.5) return null
        val out = ShortArray(want)
        for (i in 0 until want) out[i] = ring[(pos - want + i + ring.size) % ring.size]
        wav(out)
    }

    /** Enregistre ~[seconds] s de parole comme référence de [name]. Bloquant (attend au plus 3× la durée). */
    fun learn(name: String, seconds: Double = 6.0): Boolean {
        val r = ShortArrayList()
        recording = r
        val end = System.currentTimeMillis() + (seconds * 3000).toLong()
        try {
            while (System.currentTimeMillis() < end && synchronized(r) { r.data.size } < seconds * sampleRate) Thread.sleep(100)
        } finally {
            recording = null
        }
        val data = synchronized(r) { r.data.toShortArray() }
        if (data.size < sampleRate * 3) return false
        File(dir, file(name)).writeBytes(wav(data))
        return true
    }

    private fun file(name: String) = name.trim().lowercase().replace(Regex("[^a-z0-9àâäéèêëîïôöùûüç-]"), "_") + ".wav"

    fun names(): List<String> = dir.listFiles { f -> f.name.endsWith(".wav") }?.map { it.name.removeSuffix(".wav").replaceFirstChar { c -> c.uppercase() } } ?: emptyList()

    fun references(): List<Pair<String, ByteArray>> =
        dir.listFiles { f -> f.name.endsWith(".wav") }?.map { it.name.removeSuffix(".wav").replaceFirstChar { c -> c.uppercase() } to it.readBytes() } ?: emptyList()

    fun forget(name: String) = File(dir, file(name)).delete()

    /** PCM 16 bits mono -> fichier WAV. */
    fun wav(pcm: ShortArray): ByteArray {
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { data.putShort(it) }
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size * 2); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1); h.putInt(sampleRate); h.putInt(sampleRate * 2)
        h.putShort(2); h.putShort(16); h.put("data".toByteArray()); h.putInt(pcm.size * 2)
        return ByteArrayOutputStream().also { it.write(h.array()); it.write(data.array()) }.toByteArray()
    }
}
