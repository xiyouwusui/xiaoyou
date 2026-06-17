package cn.com.omnimind.baselib.llm

import cn.com.omnimind.baselib.util.OmniLog
import com.google.gson.Gson
import com.tencent.mmkv.MMKV

object SceneOperationConfigStore {
    private const val TAG = "SceneOperationConfigStore"
    private const val KEY_SCENE_OPERATION_CONFIG = "scene_operation_config_v1"

    const val SCENE_ID = "scene.vlm.operation.primary"

    private val gson = Gson()
    private val defaultConfig = SceneOperationConfig()

    fun getConfig(): SceneOperationConfig {
        val mmkv = MMKV.defaultMMKV() ?: return defaultConfig
        val raw = mmkv.decodeString(KEY_SCENE_OPERATION_CONFIG)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return defaultConfig
        return parse(raw) ?: defaultConfig
    }

    fun saveConfig(config: SceneOperationConfig): SceneOperationConfig {
        val normalized = normalize(config)
        MMKV.defaultMMKV()?.encode(KEY_SCENE_OPERATION_CONFIG, gson.toJson(normalized))
        return normalized
    }

    fun reset() {
        MMKV.defaultMMKV()?.removeValueForKey(KEY_SCENE_OPERATION_CONFIG)
    }

    fun normalize(config: SceneOperationConfig): SceneOperationConfig {
        return SceneOperationConfig(
            useOfficialService = config.useOfficialService
        )
    }

    internal fun parse(raw: String): SceneOperationConfig? {
        return runCatching {
            gson.fromJson(raw, SceneOperationConfig::class.java)
        }.onFailure {
            OmniLog.w(TAG, "parse operation config failed: ${it.message}")
        }.getOrNull()?.let(::normalize)
    }
}
