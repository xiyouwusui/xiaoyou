package cn.com.omnimind.bot.codex

import android.content.Context

enum class CodexLocalAuthMode(val payloadValue: String) {
    CHATGPT("chatgpt"),
    API("api");

    companion object {
        fun fromPayload(value: String?): CodexLocalAuthMode? {
            return when (value?.trim()?.lowercase()) {
                CHATGPT.payloadValue -> CHATGPT
                API.payloadValue -> API
                else -> null
            }
        }
    }
}

data class CodexLocalConfig(
    val authMode: CodexLocalAuthMode = CodexLocalAuthMode.API,
    val baseUrl: String = "",
    val apiModel: String = "",
    val apiKey: String = "",
    val officialModel: String = ""
)

fun CodexLocalConfig.normalized(): CodexLocalConfig {
    return copy(
        baseUrl = baseUrl.trim(),
        apiModel = apiModel.trim(),
        apiKey = apiKey.trim(),
        officialModel = officialModel.trim()
    )
}

class CodexLocalConfigStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    val hasStoredConfig: Boolean
        get() = prefs.contains(KEY_AUTH_MODE)

    val needsLegacyAuthKeyCleanup: Boolean
        get() = prefs.getBoolean(KEY_LEGACY_AUTH_KEY_CLEANUP, false)

    fun read(): CodexLocalConfig {
        return CodexLocalConfig(
            authMode = CodexLocalAuthMode.fromPayload(
                prefs.getString(KEY_AUTH_MODE, null)
            ) ?: CodexLocalAuthMode.API,
            baseUrl = prefs.getString(KEY_BASE_URL, "").orEmpty(),
            apiModel = prefs.getString(KEY_API_MODEL, "").orEmpty(),
            apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
            officialModel = prefs.getString(KEY_OFFICIAL_MODEL, "").orEmpty()
        )
    }

    fun write(
        config: CodexLocalConfig,
        needsLegacyAuthKeyCleanup: Boolean? = null
    ): CodexLocalConfig {
        val normalized = config.normalized()
        val editor = prefs.edit()
            .putString(KEY_AUTH_MODE, normalized.authMode.payloadValue)
            .putString(KEY_BASE_URL, normalized.baseUrl)
            .putString(KEY_API_MODEL, normalized.apiModel)
            .putString(KEY_API_KEY, normalized.apiKey)
            .putString(KEY_OFFICIAL_MODEL, normalized.officialModel)
        needsLegacyAuthKeyCleanup?.let {
            editor.putBoolean(KEY_LEGACY_AUTH_KEY_CLEANUP, it)
        }
        editor.apply()
        return read()
    }

    fun markLegacyAuthKeyCleanupComplete() {
        prefs.edit().putBoolean(KEY_LEGACY_AUTH_KEY_CLEANUP, false).apply()
    }

    private companion object {
        private const val PREFS_NAME = "codex_local_config"
        private const val KEY_AUTH_MODE = "auth_mode"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_MODEL = "api_model"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_OFFICIAL_MODEL = "official_model"
        private const val KEY_LEGACY_AUTH_KEY_CLEANUP = "legacy_auth_key_cleanup"
    }
}

internal fun buildCodexLocalEnvironment(
    authMode: CodexLocalAuthMode,
    apiKey: String
): Map<String, String> {
    val normalizedApiKey = apiKey.trim()
    if (authMode != CodexLocalAuthMode.API || normalizedApiKey.isEmpty()) {
        return emptyMap()
    }
    return mapOf(CODEX_CUSTOM_API_KEY_ENV to normalizedApiKey)
}

internal const val CODEX_CUSTOM_API_KEY_ENV = "OMNIBOT_CODEX_API_KEY"
