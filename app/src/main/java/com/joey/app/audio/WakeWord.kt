package com.joey.app.audio

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Mot de réveil « Joey » : la reconnaissance vocale du téléphone écoute en boucle (sur le téléphone quand c'est
 * possible : rien n'est envoyé tant qu'on ne l'appelle pas), et [onWake] est appelé quand on dit « Joey ».
 * Le micro est rendu dès qu'on arrête (la conversation Gemini en a besoin).
 */
class WakeWord(
    private val context: Context,
    private val onWake: (heard: String) -> Unit,
    private val log: (String) -> Unit = {},
) {
    companion object {
        /** « Joey » tel que la reconnaissance française l'écrit souvent. */
        // Pas de \b ni de (?U) : sur Android (ICU) l'option (?U) n'existe pas et faisait planter l'app au lancement.
        // On encadre le mot « à la main » : ni lettre ni chiffre juste avant ou juste après.
        private const val WORDS = "joey|joeys|joÿ|joie|joé|jo[eé]y|djo[eé]y|joy|joe|jaw[ie]"
        val WAKE: Regex? = runCatching {
            Regex("(?<![\\p{L}\\p{N}_])($WORDS)(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
        }.getOrNull()
        private val WORD_LIST = listOf("joey", "joeys", "joÿ", "joie", "joé", "joéy", "djoey", "djoéy", "joy", "joe", "jawi", "jawe")
        fun matches(text: String): Boolean = WAKE?.containsMatchIn(text)
            ?: text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).any { it in WORD_LIST }
    }

    /** Niveau du micro pendant l'écoute (dB de la reconnaissance vocale), pour sursauter aux bruits forts. */
    @Volatile var onLevel: ((Float) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private var recognizer: SpeechRecognizer? = null
    @Volatile var running = false
        private set
    private var muted = false
    private var onDevice = false

    fun start() = main.post {
        if (running) return@post
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            log("Mot de réveil : pas de reconnaissance vocale sur ce téléphone.")
            return@post
        }
        running = true
        mute(true)
        listen()
    }

    fun stop() = main.post {
        running = false
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        mute(false)
    }

    /** Les bips de la reconnaissance vocale à chaque relance : on les coupe pendant l'écoute. */
    private fun mute(on: Boolean) {
        if (on == muted) return
        muted = on
        val dir = if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        for (stream in listOf(AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION)) {
            runCatching { audio.adjustStreamVolume(stream, dir, 0) }
        }
    }

    private fun create(): SpeechRecognizer {
        // Reconnaissance sur le téléphone si elle existe (Android 12+ et pack de langue français installé).
        if (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            onDevice = true
            return SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        }
        onDevice = false
        return SpeechRecognizer.createSpeechRecognizer(context)
    }

    private fun listen() {
        if (!running) return
        val r = recognizer ?: create().also {
            recognizer = it
            it.setRecognitionListener(listener)
            log("👂 Mot de réveil « Joey » actif" + if (onDevice) " (reconnaissance sur le téléphone)" else "")
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        runCatching { r.startListening(intent) }.onFailure {
            Log.w("WakeWord", "écoute", it)
            restart(2000)
        }
    }

    private fun restart(delayMs: Long) {
        if (!running) return
        main.postDelayed({ listen() }, delayMs)
    }

    private fun check(results: Bundle?): Boolean {
        val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return false
        val hit = texts.firstOrNull { matches(it) } ?: return false
        if (!running) return true
        running = false
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        mute(false)
        onWake(hit)
        return true
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { onLevel?.invoke(rmsdB) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) { check(partialResults) }
        override fun onResults(results: Bundle?) {
            if (!check(results)) restart(100)
        }
        override fun onError(error: Int) {
            // Silence ou rien compris : on relance (plus doucement si le service est occupé).
            val wait = when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> 1500L
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> 5000L
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { log("Mot de réveil : permission micro refusée."); running = false; return }
                else -> 150L
            }
            if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                runCatching { recognizer?.destroy() }
                recognizer = null
            }
            restart(wait)
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
