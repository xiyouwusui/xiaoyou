package cn.com.omnimind.bot.claude

import android.content.Context
import android.util.Log
import com.ai.assistance.operit.terminal.TerminalManager
import io.flutter.plugin.common.EventChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ClaudeCodeAppServerManager private constructor(
    private val context: Context
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()

    companion object {
        private const val TAG = "ClaudeCodeManager"
        private const val INSTALL_TIMEOUT_MS = 180_000L
        private const val PROBE_TIMEOUT_MS = 15_000L
        private const val RUN_TIMEOUT_MS = 300_000L
        private const val PATH_PREFIX = "export PATH=\"/root/.npm-global/bin:\${PATH}\";"

        @Volatile
        private var instance: ClaudeCodeAppServerManager? = null

        fun getInstance(context: Context): ClaudeCodeAppServerManager {
            return instance ?: synchronized(this) {
                instance ?: ClaudeCodeAppServerManager(context).also { instance = it }
            }
        }
    }

    suspend fun handleMethod(
        method: String,
        arguments: Map<String, Any?>,
        eventSink: EventChannel.EventSink?
    ): Map<String, Any?> {
        Log.d(TAG, "handleMethod: $method, args: ${arguments.keys}")
        return when (method) {
            "status" -> status()
            "send" -> send(arguments, eventSink)
            "profiles/list" -> listProfiles()
            "profiles/active" -> activeProfile()
            "profiles/activate" -> activateProfile(arguments)
            "profiles/add" -> addProfile(arguments)
            "profiles/update" -> updateProfile(arguments)
            "profiles/delete" -> deleteProfile(arguments)
            else -> mapOf("error" to "unknown method: $method")
        }
    }

    private suspend fun status(): Map<String, Any?> {
        val installed = isClaudeCodeInstalled()
        val hasConfig = ClaudeCodeMultiProfileStore.getInstance(appContext).getActiveProfile() != null
        return mapOf(
            "installed" to installed,
            "hasConfig" to hasConfig,
            "ready" to (installed && hasConfig)
        )
    }

    suspend fun isClaudeCodeInstalled(): Boolean {
        return try {
            val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
                command = "$PATH_PREFIX claude --version 2>&1 || echo '__CLAUDE_NOT_FOUND__'",
                executorKey = "claude-code-probe",
                timeoutMs = PROBE_TIMEOUT_MS
            )
            result.isOk && !result.output.contains("__CLAUDE_NOT_FOUND__")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check claude installation", e)
            false
        }
    }

    // === Profile 管理 ===

    private fun listProfiles(): Map<String, Any?> {
        val profiles = ClaudeCodeMultiProfileStore.getInstance(appContext).getAllProfiles()
        return mapOf("profiles" to profiles.map { p ->
            mapOf(
                "id" to p.id, "name" to p.name, "apiKey" to p.apiKey,
                "baseUrl" to p.baseUrl, "model" to p.model, "extraArgs" to p.extraArgs
            )
        })
    }

    private fun activeProfile(): Map<String, Any?> {
        val p = ClaudeCodeMultiProfileStore.getInstance(appContext).getActiveProfile()
            ?: return mapOf("profile" to null)
        return mapOf("profile" to mapOf(
            "id" to p.id, "name" to p.name, "apiKey" to p.apiKey,
            "baseUrl" to p.baseUrl, "model" to p.model, "extraArgs" to p.extraArgs
        ))
    }

    private fun activateProfile(args: Map<String, Any?>): Map<String, Any?> {
        val id = args["id"] as? String ?: return mapOf("ok" to false, "error" to "id required")
        ClaudeCodeMultiProfileStore.getInstance(appContext).setActiveProfile(id)
        return mapOf("ok" to true)
    }

    private fun addProfile(args: Map<String, Any?>): Map<String, Any?> {
        val name = args["name"] as? String ?: "未命名"
        val apiKey = args["apiKey"] as? String ?: ""
        val baseUrl = args["baseUrl"] as? String ?: ""
        val model = args["model"] as? String ?: ""
        val extraArgs = args["extraArgs"] as? String ?: ""
        val store = ClaudeCodeMultiProfileStore.getInstance(appContext)
        val p = store.addProfile(ClaudeCodeMultiProfileStore.Profile(
            id = "", name = name, apiKey = apiKey, baseUrl = baseUrl, model = model, extraArgs = extraArgs
        ))
        return mapOf("ok" to true, "id" to p.id)
    }

    private fun updateProfile(args: Map<String, Any?>): Map<String, Any?> {
        val id = args["id"] as? String ?: return mapOf("ok" to false, "error" to "id required")
        val store = ClaudeCodeMultiProfileStore.getInstance(appContext)
        val existing = store.getAllProfiles().firstOrNull { it.id == id }
            ?: return mapOf("ok" to false, "error" to "profile not found")
        val updated = existing.copy(
            name = args["name"] as? String ?: existing.name,
            apiKey = args["apiKey"] as? String ?: existing.apiKey,
            baseUrl = args["baseUrl"] as? String ?: existing.baseUrl,
            model = args["model"] as? String ?: existing.model,
            extraArgs = args["extraArgs"] as? String ?: existing.extraArgs
        )
        store.updateProfile(updated)
        return mapOf("ok" to true)
    }

    private fun deleteProfile(args: Map<String, Any?>): Map<String, Any?> {
        val id = args["id"] as? String ?: return mapOf("ok" to false, "error" to "id required")
        ClaudeCodeMultiProfileStore.getInstance(appContext).deleteProfile(id)
        return mapOf("ok" to true)
    }
}
