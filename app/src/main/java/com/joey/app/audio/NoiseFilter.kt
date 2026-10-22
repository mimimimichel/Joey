package com.joey.app.audio

/**
 * Du bruit transcrit comme de la parole : une autre écriture (japonais, arabe, amharique…), « <noise> », ou presque
 * rien. (La télé et les voix lointaines devenaient « 今 お 金 に ね » ou « مثلا », et Joey y répondait.)
 */
object NoiseFilter {
    private const val FRENCH_LETTERS = "àâäéèêëîïôöùûüçœæÀÂÄÉÈÊËÎÏÔÖÙÛÜÇŒÆ"

    fun isNoise(t: String): Boolean {
        val s = t.replace(Regex("<[^>]*>"), " ").trim()
        val letters = s.filter { it.isLetter() }
        if (letters.length < 2) return true
        val latin = letters.count { it in 'a'..'z' || it in 'A'..'Z' || it in FRENCH_LETTERS }
        return latin < letters.length * 0.7
    }
}
