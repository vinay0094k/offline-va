package com.codvika.voiceassistant

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Optional online-mode config: off by default, and only ever read when the
 * user has flipped the switch. The API key is encrypted at rest; nothing
 * here is sent anywhere except the endpoint the user themselves configured.
 */
object Settings {

    private const val FILE = "settings_secure"
    private const val KEY_ONLINE = "online_enabled"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model"

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        FILE,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun onlineEnabled(context: Context) = prefs(context).getBoolean(KEY_ONLINE, false)
    fun baseUrl(context: Context) = prefs(context).getString(KEY_BASE_URL, "") ?: ""
    fun apiKey(context: Context) = prefs(context).getString(KEY_API_KEY, "") ?: ""
    fun model(context: Context) = prefs(context).getString(KEY_MODEL, "") ?: ""

    /** True once base URL and model are both filled in (an API key isn't
     *  always required — some self-hosted OpenAI-compatible servers skip auth). */
    fun isConfigured(context: Context) = baseUrl(context).isNotBlank() && model(context).isNotBlank()

    fun save(context: Context, online: Boolean, baseUrl: String, apiKey: String, model: String) {
        prefs(context).edit()
            .putBoolean(KEY_ONLINE, online)
            .putString(KEY_BASE_URL, baseUrl.trim().trimEnd('/'))
            .putString(KEY_API_KEY, apiKey.trim())
            .putString(KEY_MODEL, model.trim())
            .apply()
    }
}
