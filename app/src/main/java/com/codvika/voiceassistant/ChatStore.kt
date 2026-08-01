package com.codvika.voiceassistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Chats live as small JSON files in filesDir/chats — app-private storage on
 * the phone, nothing leaves the device (the app has no internet permission).
 */
object ChatStore {

    data class Summary(val id: String, val title: String, val updated: Long)

    private fun dir(context: Context) =
        File(context.filesDir, "chats").apply { mkdirs() }

    fun list(context: Context): List<Summary> =
        dir(context).listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f ->
                try {
                    val o = JSONObject(f.readText())
                    Summary(o.getString("id"), o.getString("title"), f.lastModified())
                } catch (e: Exception) {
                    null
                }
            }
            ?.sortedByDescending { it.updated }
            ?: emptyList()

    fun save(context: Context, id: String, title: String, messages: List<Pair<String, String>>) {
        val arr = JSONArray()
        for ((role, content) in messages) {
            arr.put(JSONObject().put("role", role).put("content", content))
        }
        val o = JSONObject().put("id", id).put("title", title).put("messages", arr)
        File(dir(context), "$id.json").writeText(o.toString())
    }

    fun load(context: Context, id: String): List<Pair<String, String>>? = try {
        val arr = JSONObject(File(dir(context), "$id.json").readText())
            .getJSONArray("messages")
        (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i)
            m.getString("role") to m.getString("content")
        }
    } catch (e: Exception) {
        null
    }

    fun delete(context: Context, id: String) {
        File(dir(context), "$id.json").delete()
    }
}
