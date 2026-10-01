package com.aiinterviewer.coach.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One question the coach answered, kept so it can be revised later. */
data class QaTurn(
    val question: String,
    val answer: String,
    val createdAt: Long,
)

/** Everything the app remembers between runs. */
data class CoachSettings(
    val apiKey: String = "",
    val model: String = "deepseek-flash",
    val cvText: String = "",
    val cvSourceName: String = "",
    val jobContext: String = "",
)

/**
 * Dead simple JSON-file persistence. No database, no DataStore dependency.
 *
 * All methods touch the disk, so call them off the main thread.
 */
class AppStore(context: Context) {

    private val settingsFile = File(context.filesDir, "coach_settings.json")
    private val historyFile = File(context.filesDir, "coach_history.json")

    fun loadSettings(): CoachSettings {
        if (!settingsFile.exists()) return CoachSettings()
        return try {
            val json = JSONObject(settingsFile.readText())
            CoachSettings(
                apiKey = stringOrEmpty(json, "apiKey"),
                model = stringOrEmpty(json, "model").ifBlank { "deepseek-flash" },
                cvText = stringOrEmpty(json, "cvText"),
                cvSourceName = stringOrEmpty(json, "cvSourceName"),
                jobContext = stringOrEmpty(json, "jobContext"),
            )
        } catch (t: Throwable) {
            CoachSettings()
        }
    }

    fun saveSettings(settings: CoachSettings) {
        val json = JSONObject()
            .put("apiKey", settings.apiKey)
            .put("model", settings.model)
            .put("cvText", settings.cvText)
            .put("cvSourceName", settings.cvSourceName)
            .put("jobContext", settings.jobContext)
        settingsFile.writeText(json.toString())
    }

    fun loadHistory(): List<QaTurn> {
        if (!historyFile.exists()) return emptyList()
        return try {
            val array = JSONArray(historyFile.readText())
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                QaTurn(
                    question = stringOrEmpty(item, "question"),
                    answer = stringOrEmpty(item, "answer"),
                    createdAt = item.optLong("createdAt", 0L),
                )
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun appendHistory(turn: QaTurn): List<QaTurn> {
        val existing = loadHistory().toMutableList()
        existing.add(0, turn)
        val trimmed = existing.take(200)
        val array = JSONArray()
        for (item in trimmed) {
            array.put(
                JSONObject()
                    .put("question", item.question)
                    .put("answer", item.answer)
                    .put("createdAt", item.createdAt)
            )
        }
        historyFile.writeText(array.toString())
        return trimmed
    }

    fun clearHistory() {
        if (historyFile.exists()) historyFile.delete()
    }

    private fun stringOrEmpty(json: JSONObject, key: String): String {
        val raw = json.opt(key)
        return if (raw is String) raw else ""
    }
}
