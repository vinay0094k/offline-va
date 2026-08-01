package com.codvika.voiceassistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Talks to whatever OpenAI-compatible `/chat/completions` endpoint the user
 * configured in Settings. Covers OpenAI itself, most third-party providers,
 * and self-hosted servers (LM Studio, Ollama's compat layer, vLLM, etc.).
 * Blocking; call from Dispatchers.IO.
 */
object RemoteLlm {

    fun chat(
        baseUrl: String, apiKey: String, model: String,
        roles: Array<String>, contents: Array<String>, maxTokens: Int
    ): String {
        val messages = JSONArray()
        for (i in roles.indices) {
            messages.put(JSONObject().put("role", roles[i]).put("content", contents[i]))
        }
        val response = request(baseUrl, apiKey, model, messages, maxTokens)
        val choice = response.getJSONArray("choices").getJSONObject(0)
        return choice.getJSONObject("message").getString("content")
    }

    /** Minimal request that verifies the endpoint, auth, and model slug work. */
    fun test(baseUrl: String, apiKey: String, model: String): String {
        val messages = JSONArray().put(
            JSONObject().put("role", "user").put("content", "ping")
        )
        request(baseUrl, apiKey, model, messages, 1)
        return "Connected — server accepted \"$model\""
    }

    /** One blocking POST to `{baseUrl}/chat/completions`; throws IOException on failure. */
    private fun request(
        baseUrl: String, apiKey: String, model: String,
        messages: JSONArray, maxTokens: Int
    ): JSONObject {
        val url = URL("$baseUrl/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
            }

            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("max_tokens", maxTokens)

            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use {
                it.write(body.toString())
            }

            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""

            if (status !in 200..299) {
                val msg = try {
                    JSONObject(text).optJSONObject("error")?.optString("message")
                } catch (e: Exception) {
                    null
                }
                throw IOException("HTTP $status${if (msg != null) ": $msg" else ""}")
            }

            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
