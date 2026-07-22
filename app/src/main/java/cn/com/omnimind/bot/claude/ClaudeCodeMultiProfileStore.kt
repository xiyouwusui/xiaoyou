package cn.com.omnimind.bot.claude

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Claude Code CLI 配置 — 支持多个实例（多个中转站/Key），可切换激活。
 *
 * Claude Code 通过环境变量 ANTHROPIC_API_KEY 和 ANTHROPIC_BASE_URL 配置。
 */
class ClaudeCodeMultiProfileStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    /** 单个 Claude Code 配置实例 */
    data class Profile(
        val id: String,
        val name: String,
        val apiKey: String = "",
        val baseUrl: String = "",
        val model: String = "",
        val extraArgs: String = ""
    ) {
        fun normalized(): Profile = copy(
            apiKey = apiKey.trim(),
            baseUrl = baseUrl.trim(),
            model = model.trim(),
            extraArgs = extraArgs.trim()
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

    /** 获取当前激活的配置 */
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
        val activeId = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)
        if (activeId == id) {
            if (profiles.isNotEmpty()) {
                setActiveProfile(profiles.first().id)
            } else {
                prefs.edit().remove(KEY_ACTIVE_PROFILE_ID).apply()
            }
        }
    }

    val hasStoredConfig: Boolean
        get() = getAllProfiles().isNotEmpty()

    /** 构建 Claude Code 运行所需的环境变量 */
    fun buildEnvironment(profile: Profile): Map<String, String> {
        val env = mutableMapOf<String, String>()
        if (profile.apiKey.isNotBlank()) {
            env["ANTHROPIC_API_KEY"] = profile.apiKey
        }
        if (profile.baseUrl.isNotBlank()) {
            env["ANTHROPIC_BASE_URL"] = profile.baseUrl
        }
        return env
    }

    private fun generateId(): String = "claude_${System.currentTimeMillis()}_${(100..999).random()}"

    private fun saveProfiles(profiles: List<Profile>) {
        prefs.edit().putString(KEY_PROFILES, gson.toJson(profiles)).apply()
    }

    companion object {
        private const val PREFS_NAME = "claude_code_multi_profile_config"
        private const val KEY_PROFILES = "profiles_json"
        private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"

        @Volatile
        private var instance: ClaudeCodeMultiProfileStore? = null

        fun getInstance(context: Context): ClaudeCodeMultiProfileStore {
            return instance ?: synchronized(this) {
                instance ?: ClaudeCodeMultiProfileStore(context).also { instance = it }
            }
        }
    }
}
