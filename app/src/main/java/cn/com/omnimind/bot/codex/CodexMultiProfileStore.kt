package cn.com.omnimind.bot.codex

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 多 Codex 配置存储 — 支持多个 Codex 实例（如多个中转站），可切换激活。
 *
 * 向后兼容：首次启动时自动迁移旧的 CodexLocalConfigStore 单配置数据。
 */
class CodexMultiProfileStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    /** 单个 Codex 配置实例 */
    data class Profile(
        val id: String,
        val name: String,
        val authMode: CodexLocalAuthMode = CodexLocalAuthMode.API,
        val baseUrl: String = "",
        val apiModel: String = "",
        val apiKey: String = "",
        val officialModel: String = ""
    ) {
        fun toCodexLocalConfig(): CodexLocalConfig = CodexLocalConfig(
            authMode = authMode,
            baseUrl = baseUrl,
            apiModel = apiModel,
            apiKey = apiKey,
            officialModel = officialModel
        )

        fun normalized(): Profile = copy(
            baseUrl = baseUrl.trim(),
            apiModel = apiModel.trim(),
            apiKey = apiKey.trim(),
            officialModel = officialModel.trim()
        )
    }

    /** 获取全部配置列表 */
    fun getAllProfiles(): List<Profile> {
        val json = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Profile>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 获取当前激活的配置，如果没有则返回 null */
    fun getActiveProfile(): Profile? {
        val activeId = prefs.getString(KEY_ACTIVE_PROFILE_ID, null) ?: return null
        return getAllProfiles().firstOrNull { it.id == activeId }
    }

    /** 设置激活的配置 */
    fun setActiveProfile(id: String) {
        prefs.edit().putString(KEY_ACTIVE_PROFILE_ID, id).apply()
    }

    /** 添加新配置 */
    fun addProfile(profile: Profile): Profile {
        val normalized = profile.normalized()
        val profiles = getAllProfiles().toMutableList()
        profiles.add(normalized)
        saveProfiles(profiles)
        // 如果是第一个配置，自动激活
        if (profiles.size == 1) {
            setActiveProfile(normalized.id)
        }
        return normalized
    }

    /** 更新已有配置 */
    fun updateProfile(profile: Profile): Profile {
        val normalized = profile.normalized()
        val profiles = getAllProfiles().map {
            if (it.id == normalized.id) normalized else it
        }
        saveProfiles(profiles)
        return normalized
    }

    /** 删除配置 */
    fun deleteProfile(id: String) {
        val profiles = getAllProfiles().filterNot { it.id == id }
        saveProfiles(profiles)
        // 如果删除的是当前激活的，切换到第一个
        val activeId = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)
        if (activeId == id) {
            if (profiles.isNotEmpty()) {
                setActiveProfile(profiles.first().id)
            } else {
                prefs.edit().remove(KEY_ACTIVE_PROFILE_ID).apply()
            }
        }
    }

    /** 是否有已存储的配置 */
    val hasStoredConfig: Boolean
        get() = getAllProfiles().isNotEmpty()

    /** 从旧的单配置迁移（如果存在） */
    fun migrateFromLegacyStoreIfNeeded() {
        if (getAllProfiles().isNotEmpty()) return
        val legacyPrefs = appContext.getSharedPreferences("codex_local_config", Context.MODE_PRIVATE)
        if (!legacyPrefs.contains("auth_mode")) return

        val legacyConfig = CodexLocalConfig(
            authMode = CodexLocalAuthMode.fromPayload(legacyPrefs.getString("auth_mode", null))
                ?: CodexLocalAuthMode.API,
            baseUrl = legacyPrefs.getString("base_url", "").orEmpty(),
            apiModel = legacyPrefs.getString("api_model", "").orEmpty(),
            apiKey = legacyPrefs.getString("api_key", "").orEmpty(),
            officialModel = legacyPrefs.getString("official_model", "").orEmpty()
        )
        if (legacyConfig.apiKey.isBlank() && legacyConfig.baseUrl.isBlank()) return

        val migrated = Profile(
            id = generateId(),
            name = "默认配置",
            authMode = legacyConfig.authMode,
            baseUrl = legacyConfig.baseUrl,
            apiModel = legacyConfig.apiModel,
            apiKey = legacyConfig.apiKey,
            officialModel = legacyConfig.officialModel
        )
        addProfile(migrated)
    }

    /** 生成唯一 ID */
    private fun generateId(): String = "codex_${System.currentTimeMillis()}_${(100..999).random()}"

    private fun saveProfiles(profiles: List<Profile>) {
        prefs.edit().putString(KEY_PROFILES, gson.toJson(profiles)).apply()
    }

    companion object {
        private const val PREFS_NAME = "codex_multi_profile_config"
        private const val KEY_PROFILES = "profiles_json"
        private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"

        @Volatile
        private var instance: CodexMultiProfileStore? = null

        fun getInstance(context: Context): CodexMultiProfileStore {
            return instance ?: synchronized(this) {
                instance ?: CodexMultiProfileStore(context).also {
                    it.migrateFromLegacyStoreIfNeeded()
                    instance = it
                }
            }
        }
    }
}
