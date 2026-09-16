package io.github.crunchyinmilk.arrpilot

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AppSettings(
    val radarrUrl: String = "",
    val radarrApiKey: String = "",
    val tmdbReadToken: String = "",
    val tmdbApiKey: String = ""
) {
    val isComplete: Boolean
        get() = radarrUrl.isNotBlank() && radarrApiKey.isNotBlank() &&
            (tmdbReadToken.isNotBlank() || tmdbApiKey.isNotBlank())
}

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        radarrUrl = read(RADARR_URL),
        radarrApiKey = read(RADARR_API_KEY),
        tmdbReadToken = read(TMDB_READ_TOKEN),
        tmdbApiKey = read(TMDB_API_KEY)
    )

    fun save(settings: AppSettings) {
        preferences.edit()
            .putString(RADARR_URL, encrypt(settings.radarrUrl))
            .putString(RADARR_API_KEY, encrypt(settings.radarrApiKey))
            .putString(TMDB_READ_TOKEN, encrypt(settings.tmdbReadToken))
            .putString(TMDB_API_KEY, encrypt(settings.tmdbApiKey))
            .apply()
    }

    private fun read(key: String): String {
        val value = preferences.getString(key, null) ?: return ""
        return runCatching { decrypt(value) }.getOrDefault("")
    }

    private fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        if (value.isEmpty()) return ""
        val packed = Base64.decode(value, Base64.NO_WRAP)
        require(packed.size > IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, packed.copyOfRange(0, IV_BYTES)))
        return String(cipher.doFinal(packed.copyOfRange(IV_BYTES, packed.size)), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES = "connection_settings"
        const val RADARR_URL = "radarr_url"
        const val RADARR_API_KEY = "radarr_api_key"
        const val TMDB_READ_TOKEN = "tmdb_read_token"
        const val TMDB_API_KEY = "tmdb_api_key"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "arrpilot_connection_settings"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
