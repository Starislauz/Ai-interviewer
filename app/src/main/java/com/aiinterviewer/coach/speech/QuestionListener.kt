package com.aiinterviewer.coach.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Listens for one spoken question at a time.
 *
 * SpeechRecognizer stops itself after every utterance, or after a period of
 * silence, so it has to be restarted each time to give the effect of continuous
 * listening. Benign events such as "heard nothing" restart silently rather than
 * being reported as failures.
 *
 * Every method here must be called from the main thread.
 */
class QuestionListener(private val context: Context) {

    var onPartial: (String) -> Unit = {}
    var onQuestion: (String) -> Unit = {}
    var onStatus: (String) -> Unit = {}
    var onError: (String) -> Unit = {}

    private var recognizer: SpeechRecognizer? = null
    private var running = false

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (!isAvailable) {
            onError("This phone has no speech recognition service installed.")
            return
        }
        if (running) return
        running = true

        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(recognitionListener)
            }
        }
        onStatus("Listening")
        beginListening()
    }

    fun stop() {
        running = false
        try {
            recognizer?.stopListening()
            recognizer?.cancel()
        } catch (t: Throwable) {
            // Recogniser already gone; nothing to clean up.
        }
        onStatus("Idle")
    }

    fun release() {
        running = false
        try {
            recognizer?.destroy()
        } catch (t: Throwable) {
            // ignored
        }
        recognizer = null
    }

    private fun beginListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        try {
            recognizer?.startListening(intent)
        } catch (t: Throwable) {
            if (running) onError(t.message ?: "Could not start listening.")
        }
    }

    private fun firstResult(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()

    private val recognitionListener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            onStatus("Listening")
        }

        override fun onBeginningOfSpeech() {
            onStatus("Hearing you")
        }

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            onStatus("Transcribing")
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = firstResult(partialResults)
            if (text.isNotBlank()) onPartial(text)
        }

        override fun onResults(results: Bundle?) {
            val text = firstResult(results)
            if (text.isNotBlank()) {
                onQuestion(text)
            } else if (running) {
                onStatus("Listening")
            }
            restartIfRunning()
        }

        override fun onError(error: Int) {
            if (!isBenign(error)) onError(describe(error))
            restartIfRunning()
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun restartIfRunning() {
        if (!running) {
            onStatus("Idle")
            return
        }
        // A short restart is required because SpeechRecognizer cannot be told to
        // keep going by itself.
        try {
            recognizer?.cancel()
            beginListening()
        } catch (t: Throwable) {
            if (running) onError(t.message ?: "Listening stopped unexpectedly.")
        }
    }

    /**
     * Silence and busy signals are part of normal operation. Showing them as
     * errors would make the app look broken while it is working correctly.
     */
    private fun isBenign(error: Int): Boolean = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        SpeechRecognizer.ERROR_CLIENT -> true
        else -> false
    }

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing."
        SpeechRecognizer.ERROR_NETWORK -> "Speech recognition needs a network connection."
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition timed out."
        SpeechRecognizer.ERROR_SERVER -> "The speech recognition service reported an error."
        else -> "Speech recognition error $error."
    }
}
