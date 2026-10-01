package com.aiinterviewer.coach

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aiinterviewer.coach.data.AppStore
import com.aiinterviewer.coach.data.CoachSettings
import com.aiinterviewer.coach.data.CvImport
import com.aiinterviewer.coach.data.CvImporter
import com.aiinterviewer.coach.data.DeepSeekClient
import com.aiinterviewer.coach.data.Prompt
import com.aiinterviewer.coach.data.QaTurn
import com.aiinterviewer.coach.speech.QuestionListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds all app state and coordinates the CV import and the question pipeline.
 *
 * Compose state is only ever written from the main thread here. Streaming deltas
 * arrive on an IO thread and are posted across, which also guarantees they are
 * applied in order.
 */
class CoachController(
    private val context: Context,
    private val store: AppStore,
    private val scope: CoroutineScope,
) {

    private val main = Handler(Looper.getMainLooper())
    private val client = DeepSeekClient()
    private val listener = QuestionListener(context)

    var settings by mutableStateOf(CoachSettings())
        private set

    var history by mutableStateOf<List<QaTurn>>(emptyList())
        private set

    var busy by mutableStateOf(false)
        private set

    var listening by mutableStateOf(false)
        private set

    var status by mutableStateOf("")
        private set

    var error by mutableStateOf("")
        private set

    /**
     * The question text. The microphone writes it while listening, and the user can
     * edit it before pressing Answer. Nothing is answered automatically.
     */
    var questionText by mutableStateOf("")
        private set

    /** The question that was last answered, kept for display above the answer. */
    var lastQuestion by mutableStateOf("")
        private set

    var answer by mutableStateOf("")
        private set

    private var answerJob: Job? = null
    private var persistJob: Job? = null
    private var lastAcceptedQuestion = ""
    private var lastAcceptedAt = 0L

    /** Finalised speech so far, with a trailing space, so partials join cleanly. */
    private var committedSpeech = ""

    private companion object {
        const val PERSIST_DEBOUNCE_MS = 400L
    }

    init {
        listener.onPartial = { text ->
            // Live typing while the question is being spoken.
            questionText = (committedSpeech + text).trim()
            status = "Listening"
        }
        listener.onStatus = { text ->
            if (!status.startsWith("Answering")) status = text
        }
        listener.onError = { message ->
            error = message
        }
        listener.onQuestion = { text ->
            val now = System.currentTimeMillis()
            // The recogniser sometimes delivers the same utterance twice in a row.
            val isEcho = text == lastAcceptedQuestion && now - lastAcceptedAt < 2500
            if (!isEcho) {
                lastAcceptedQuestion = text
                lastAcceptedAt = now
                // Committed to the field, not answered. The user presses Answer.
                committedSpeech = (committedSpeech + text).trim() + " "
                questionText = committedSpeech.trim()
            }
        }
    }

    fun setQuestion(value: String) {
        questionText = value
        // A manual edit becomes the new starting point, so anything spoken next
        // is appended to what was typed rather than overwriting it.
        committedSpeech = if (listening) "$value " else ""
    }

    fun clearQuestion() {
        questionText = ""
        committedSpeech = ""
    }

    fun refresh() {
        scope.launch {
            val loadedSettings = withContext(Dispatchers.IO) { store.loadSettings() }
            val loadedHistory = withContext(Dispatchers.IO) { store.loadHistory() }
            settings = loadedSettings
            history = loadedHistory
        }
    }

    // ---------------------------------------------------------------- CV import

    fun importCv(uri: Uri, fileName: String) {
        if (settings.apiKey.isBlank()) {
            error = "Add your DeepSeek API key on the Settings tab first."
            return
        }
        busy = true
        error = ""
        status = "Reading your CV"

        scope.launch {
            try {
                when (val imported = withContext(Dispatchers.IO) { CvImporter.read(context, uri, fileName) }) {
                    is CvImport.Text -> {
                        val text = imported.text.trim()
                        if (text.isBlank()) {
                            error = "That file had no readable text. Try a PDF instead."
                            return@launch
                        }
                        persistCv(text, fileName)
                    }

                    is CvImport.Pages -> {
                        if (imported.base64Jpeg.isEmpty()) {
                            error = "Could not read that PDF. It may be password protected or scanned oddly."
                            return@launch
                        }
                        status = "Reading ${imported.base64Jpeg.size} page(s) with DeepSeek"
                        val content = client.visionContent(Prompt.EXTRACTION, imported.base64Jpeg)
                        val profile = withContext(Dispatchers.IO) {
                            client.complete(
                                apiKey = settings.apiKey,
                                model = settings.model,
                                system = "",
                                userContent = content,
                                maxTokens = 4096,
                            )
                        }
                        if (profile.isBlank()) {
                            error = "DeepSeek returned nothing for that CV."
                            return@launch
                        }
                        persistCv(profile.trim(), fileName)
                    }
                }
            } catch (t: Throwable) {
                error = t.message ?: "CV import failed."
            } finally {
                busy = false
                if (error.isBlank()) status = "CV saved"
            }
        }
    }

    private suspend fun persistCv(text: String, sourceName: String) {
        val updated = settings.copy(cvText = text, cvSourceName = sourceName)
        settings = updated
        withContext(Dispatchers.IO) { store.saveSettings(updated) }
    }

    fun clearCv() {
        settings = settings.copy(cvText = "", cvSourceName = "")
        persist()
        status = "CV removed"
    }

    // ------------------------------------------------------------------ settings

    fun updateApiKey(value: String) {
        // API keys contain no spaces, so trimming as you type is harmless here.
        settings = settings.copy(apiKey = value.trim())
        persist()
    }

    fun updateModel(value: String) {
        settings = settings.copy(model = value.trim().ifBlank { "deepseek-flash" })
        persist()
    }

    fun updateJobContext(value: String) {
        settings = settings.copy(jobContext = value)
        persist()
    }

    /**
     * Writes the settings once typing pauses. Without the debounce every keystroke
     * would trigger a file write, and each write carries the whole CV text.
     */
    private fun persist() {
        val snapshot = settings
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            withContext(Dispatchers.IO) { store.saveSettings(snapshot) }
        }
    }

    fun savePastedCv(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            error = "There is nothing to save."
            return
        }
        settings = settings.copy(cvText = trimmed, cvSourceName = "pasted text")
        val snapshot = settings
        scope.launch(Dispatchers.IO) { store.saveSettings(snapshot) }
        status = "CV saved"
    }

    // ------------------------------------------------------------------ answering

    fun ask(question: String) {
        val trimmed = question.trim()
        if (trimmed.isEmpty()) return
        // Answering is always the user's decision, so listening never triggers this.
        if (listening) stopListening()
        if (settings.apiKey.isBlank()) {
            error = "Add your DeepSeek API key on the Settings tab first."
            return
        }
        if (settings.cvText.isBlank()) {
            error = "Add your CV on the My CV tab first."
            return
        }

        answerJob?.cancel()
        error = ""
        answer = ""
        lastQuestion = trimmed
        busy = true
        status = "Answering"

        val systemPrompt = Prompt.coach(settings.cvText, settings.jobContext)
        val apiKey = settings.apiKey
        val model = settings.model

        answerJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    client.stream(
                        apiKey = apiKey,
                        model = model,
                        system = systemPrompt,
                        userContent = trimmed,
                        maxTokens = 900,
                    ) { fragment ->
                        main.post { answer += fragment }
                    }
                }
                // The deltas above are already queued on the main thread, so this
                // runs after all of them and sees the complete answer.
                main.post { finishAnswer(trimmed) }
            } catch (t: Throwable) {
                main.post {
                    busy = false
                    status = ""
                    error = t.message ?: "Could not reach DeepSeek."
                }
            }
        }
    }

    private fun finishAnswer(question: String) {
        busy = false
        val completed = answer.trim()
        if (completed.isBlank()) {
            status = ""
            error = "DeepSeek returned an empty answer."
            return
        }
        status = "Done"
        val turn = QaTurn(question, completed, System.currentTimeMillis())
        history = listOf(turn) + history
        scope.launch(Dispatchers.IO) { store.appendHistory(turn) }
    }

    fun cancelAnswer() {
        client.abort()
        answerJob?.cancel()
        answerJob = null
        busy = false
        status = ""
    }

    // ------------------------------------------------------------------ listening

    fun startListening() {
        if (!listener.isAvailable) {
            error = "This phone has no speech recognition service installed."
            return
        }
        error = ""
        // A fresh listen starts a fresh question.
        committedSpeech = ""
        lastAcceptedQuestion = ""
        questionText = ""
        listening = true
        listener.start()
    }

    fun stopListening() {
        listening = false
        listener.stop()
        // Keep whatever was heard so far; the user may still press Answer.
        committedSpeech = questionText.trim().let { if (it.isEmpty()) "" else "$it " }
    }

    // -------------------------------------------------------------------- history

    fun recallTurn(turn: QaTurn) {
        lastQuestion = turn.question
        questionText = turn.question
        answer = turn.answer
        status = ""
    }

    fun clearHistory() {
        history = emptyList()
        scope.launch(Dispatchers.IO) { store.clearHistory() }
    }

    // ---------------------------------------------------------------------- misc

    fun reportError(message: String) {
        error = message
    }

    fun dismissError() {
        error = ""
    }

    fun release() {
        client.abort()
        listener.release()
    }
}
