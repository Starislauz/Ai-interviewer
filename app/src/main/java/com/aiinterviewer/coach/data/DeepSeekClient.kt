package com.aiinterviewer.coach.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Minimal DeepSeek client built on HttpURLConnection, so the project needs no
 * HTTP or JSON library at all.
 *
 * Two things matter for speed and cost:
 *  - thinking is switched off, which removes several seconds of hidden tokens
 *  - the system prompt is sent unchanged on every call, which lets DeepSeek's
 *    prompt cache serve it at the cached (much cheaper, much faster) rate
 */
class DeepSeekClient {

    private companion object {
        const val ENDPOINT = "https://api.deepseek.com/chat/completions"
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 180_000
    }

    /**
     * The request currently in flight, if any.
     *
     * Cancelling a coroutine cannot interrupt a blocking socket read, so cancelling
     * an answer has to disconnect the socket as well. Without this the request
     * would keep running and keep costing tokens after the user pressed stop.
     */
    @Volatile
    private var inFlight: HttpURLConnection? = null

    fun abort() {
        val connection = inFlight
        inFlight = null
        try {
            connection?.disconnect()
        } catch (t: Throwable) {
            // Nothing useful to do; the read will fail on its own.
        }
    }

    /**
     * Single-shot request. Used for reading a CV out of PDF page images.
     *
     * @param userContent either a plain String, or a JSONArray of content blocks
     *                    when sending images.
     */
    fun complete(
        apiKey: String,
        model: String,
        system: String,
        userContent: Any,
        maxTokens: Int = 4096,
    ): String {
        val first = post(apiKey, body(model, system, userContent, false, true, maxTokens))
        if (first.code == 400 && first.text.contains("thinking", ignoreCase = true)) {
            // The thinking switch was rejected. Retry once without it rather than
            // failing the whole import.
            val second = post(apiKey, body(model, system, userContent, false, false, maxTokens))
            if (second.code !in 200..299) {
                throw IOException("DeepSeek HTTP ${second.code}: ${second.text.take(500)}")
            }
            return contentOf(second.text)
        }
        if (first.code !in 200..299) {
            throw IOException("DeepSeek HTTP ${first.code}: ${first.text.take(500)}")
        }
        return contentOf(first.text)
    }

    /**
     * Streaming request. [onDelta] is invoked from the calling thread for every
     * fragment of text as it arrives, so the first words appear long before the
     * full answer is finished.
     */
    fun stream(
        apiKey: String,
        model: String,
        system: String,
        userContent: Any,
        maxTokens: Int = 900,
        onDelta: (String) -> Unit,
    ) {
        var connection = open(apiKey, body(model, system, userContent, true, true, maxTokens))
        var code = connection.responseCode

        if (code == 400) {
            val errorText = connection.errorStream
                ?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            connection.disconnect()
            if (!errorText.contains("thinking", ignoreCase = true)) {
                throw IOException("DeepSeek HTTP 400: ${errorText.take(500)}")
            }
            connection = open(apiKey, body(model, system, userContent, true, false, maxTokens))
            code = connection.responseCode
        }

        try {
            if (code !in 200..299) {
                val errorText = connection.errorStream
                    ?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
                throw IOException("DeepSeek HTTP $code: ${errorText.take(500)}")
            }

            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload.isEmpty()) continue
                    if (payload == "[DONE]") break
                    val fragment = deltaOf(payload)
                    if (fragment.isNotEmpty()) onDelta(fragment)
                }
            }
        } finally {
            inFlight = null
            connection.disconnect()
        }
    }

    /** Builds the content-block array used when sending images with a prompt. */
    fun visionContent(promptText: String, base64JpegPages: List<String>): JSONArray {
        val blocks = JSONArray()
        blocks.put(JSONObject().put("type", "text").put("text", promptText))
        for (page in base64JpegPages) {
            blocks.put(
                JSONObject()
                    .put("type", "image_url")
                    .put(
                        "image_url",
                        JSONObject()
                            .put("url", "data:image/jpeg;base64,$page")
                            .put("detail", "high")
                    )
            )
        }
        return blocks
    }

    private data class Response(val code: Int, val text: String)

    private fun body(
        model: String,
        system: String,
        userContent: Any,
        stream: Boolean,
        disableThinking: Boolean,
        maxTokens: Int,
    ): String {
        val messages = JSONArray()
        // Images are only permitted in user messages, so a blank system prompt is
        // omitted entirely rather than sent as an empty string.
        if (system.isNotBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", system))
        }
        messages.put(JSONObject().put("role", "user").put("content", userContent))

        val root = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", stream)
            .put("max_tokens", maxTokens)
            .put("temperature", 0.3)

        if (disableThinking) {
            root.put("thinking", JSONObject().put("type", "disabled"))
        }
        return root.toString()
    }

    private fun open(apiKey: String, payload: String): HttpURLConnection {
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "text/event-stream")
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
        inFlight = connection
        return connection
    }

    private fun post(apiKey: String, payload: String): Response {
        val connection = open(apiKey, payload)
        return try {
            val code = connection.responseCode
            val text = if (code in 200..299) {
                connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            } else {
                connection.errorStream
                    ?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            }
            Response(code, text)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Reads a field that may legitimately be an explicit JSON null. Android's
     * org.json returns the string "null" from optString in that case, so the
     * value is type-checked instead.
     */
    private fun stringOrEmpty(json: JSONObject, key: String): String {
        val raw = json.opt(key)
        return if (raw is String) raw else ""
    }

    private fun contentOf(json: String): String {
        val choices = JSONObject(json).optJSONArray("choices") ?: return ""
        val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return ""
        return stringOrEmpty(message, "content")
    }

    private fun deltaOf(json: String): String {
        return try {
            val choices = JSONObject(json).optJSONArray("choices") ?: return ""
            val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: return ""
            stringOrEmpty(delta, "content")
        } catch (t: Throwable) {
            ""
        }
    }
}
