package com.nuomisp.englishbook.services

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class TtsMode { SYSTEM, OPENAI_API }

data class ApiSettings(
    val baseUrl: String = "",
    val apiKey: String = "",
    val chatModel: String = "",
    val teachingModel: String = "",
    val memoryModel: String = "",
    val ttsMode: TtsMode = TtsMode.SYSTEM,
    val ttsBaseUrl: String = "",
    val ttsApiKey: String = "",
    val ttsModel: String = "tts-1",
    val ttsVoice: String = "alloy",
    val remindersEnabled: Boolean = true,
    val serverUrl: String = "",
    val serverToken: String = "",
    val serverModeEnabled: Boolean = false,
) {
    // Settings can appear in debugger/UI state: never expose secrets through toString().
    override fun toString() = "ApiSettings(secrets=redacted, ttsMode=$ttsMode, remindersEnabled=$remindersEnabled)"
}

class SettingsStorageException(message: String) : Exception(message)

/** All settings (including keys) are AES-GCM encrypted with a non-exportable Android Keystore key. */
class SecureSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("secure_api_settings", Context.MODE_PRIVATE)

    fun load(): ApiSettings = synchronized(lock) {
        val encrypted = preferences.getString("payload", null) ?: return@synchronized ApiSettings()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = Base64.decode(preferences.getString("iv", null) ?: error("Missing IV"), Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            val json = JSONObject(String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8))
            ApiSettings(
                baseUrl = json.optString("baseUrl"), apiKey = json.optString("apiKey"),
                chatModel = json.optString("chatModel"), teachingModel = json.optString("teachingModel"),
                memoryModel = json.optString("memoryModel"),
                ttsMode = TtsMode.entries.find { it.name == json.optString("ttsMode") } ?: TtsMode.SYSTEM,
                ttsBaseUrl = json.optString("ttsBaseUrl"), ttsApiKey = json.optString("ttsApiKey"),
                ttsModel = json.optString("ttsModel", "tts-1"), ttsVoice = json.optString("ttsVoice", "alloy"),
                remindersEnabled = json.optBoolean("remindersEnabled", true),
                serverUrl = json.optString("serverUrl"), serverToken = json.optString("serverToken"),
                serverModeEnabled = json.optBoolean("serverModeEnabled", false),
            )
        } catch (_: Exception) {
            throw SettingsStorageException("无法解密本机配置。请重新填写接口设置；学习记录不受影响。")
        }
    }

    fun save(settings: ApiSettings) = synchronized(lock) {
        listOf(settings.baseUrl, settings.ttsBaseUrl, settings.serverUrl).filter { it.isNotBlank() }
            .forEach { ApiEndpoints.validateBaseUrl(it) }
        try {
            val json = JSONObject().apply {
                put("baseUrl", settings.baseUrl.trim()); put("apiKey", settings.apiKey.trim())
                put("chatModel", settings.chatModel.trim()); put("teachingModel", settings.teachingModel.trim())
                put("memoryModel", settings.memoryModel.trim()); put("ttsMode", settings.ttsMode.name)
                put("ttsBaseUrl", settings.ttsBaseUrl.trim()); put("ttsApiKey", settings.ttsApiKey.trim())
                put("ttsModel", settings.ttsModel.trim()); put("ttsVoice", settings.ttsVoice.trim())
                put("remindersEnabled", settings.remindersEnabled); put("serverUrl", settings.serverUrl.trim())
                put("serverToken", settings.serverToken.trim())
                put("serverModeEnabled", settings.serverModeEnabled)
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ciphertext = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
            val saved = preferences.edit()
                .putString("payload", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit()
            if (!saved) throw SettingsStorageException("保存接口配置失败，请检查可用存储空间。")
        } catch (error: SettingsStorageException) { throw error
        } catch (_: Exception) { throw SettingsStorageException("保存接口配置失败，请重试。") }
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private companion object {
        val lock = Any()
        const val KEY_ALIAS = "englishbook_settings_v1"
    }
}
