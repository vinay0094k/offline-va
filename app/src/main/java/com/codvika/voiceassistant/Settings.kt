package com.codvika.voiceassistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Optional online-mode config: off by default, and only ever read when the
 * user has flipped the switch. The API key is encrypted at rest with an
 * Android Keystore AES-GCM key; nothing here is sent anywhere except the
 * endpoint the user themselves configured.
 */
object Settings {

    private const val FILE = "settings"
    private const val KEY_ALIAS = "settings_key"
    private const val KEY_ONLINE = "online_enabled"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Loads the Keystore key, generating it on first use. */
    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** Base64 "iv || ciphertext"; GCM IV is always 12 bytes. */
    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val out = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(iv + out, Base64.NO_WRAP)
    }

    /** Returns null when the key was invalidated or the value is corrupt. */
    private fun decrypt(stored: String): String? = try {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE, getOrCreateKey(),
            GCMParameterSpec(128, bytes.copyOfRange(0, 12))
        )
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), StandardCharsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    fun onlineEnabled(context: Context) = prefs(context).getBoolean(KEY_ONLINE, false)
    fun baseUrl(context: Context) = prefs(context).getString(KEY_BASE_URL, "") ?: ""
    fun apiKey(context: Context): String {
        val stored = prefs(context).getString(KEY_API_KEY, "") ?: return ""
        return if (stored.isEmpty()) "" else decrypt(stored) ?: ""
    }
    fun model(context: Context) = prefs(context).getString(KEY_MODEL, "") ?: ""

    /** True once base URL and model are both filled in (an API key isn't
     *  always required — some self-hosted OpenAI-compatible servers skip auth). */
    fun isConfigured(context: Context) = baseUrl(context).isNotBlank() && model(context).isNotBlank()

    fun save(context: Context, online: Boolean, baseUrl: String, apiKey: String, model: String) {
        prefs(context).edit()
            .putBoolean(KEY_ONLINE, online)
            .putString(KEY_BASE_URL, baseUrl.trim().trimEnd('/'))
            .putString(KEY_API_KEY, if (apiKey.isBlank()) "" else encrypt(apiKey.trim()))
            .putString(KEY_MODEL, model.trim())
            .apply()
    }
}
