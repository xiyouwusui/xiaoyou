package cn.com.omnimind.bot.claude

import android.content.Context
import android.util.Log
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.setup.EnvironmentSetupLogic
import io.flutter.plugin.common.EventChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Claude Code CLI 管理器 — 管理 proot 终端中的 Claude Code 实例。
 *
 * 支持的 Method Channel 方法:
 * - status: 返回安装/配置状态
 * - send: 发送一条消息给 Claude Code，返回结果
 * - profiles/list: 获取所有配置
 * - profiles/activate: 激活某个配置
 * - profiles/add: 添加配置
 * - profiles/update: 更新配置
 * - profiles/delete: 删除配置
 * - profiles/active: 获取当前激活的配置
 */
class ClaudeCodeAppServerManager private constructor(
    private val context: Context
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    companion object {
        private const val TAG = "ClaudeCodeManager"
        private const val PROBE_TIMEOUT_MS = 15_000L
        private const val INSTALL_TIMEOUT_MS = 120_000L
        private const val RUN_TIMEOUT_MS = 300_000L

        @Volatile
        private var instance: ClaudeCodeAppServerManager? = null

        fun getInstance(context: Context): ClaudeCodeAppServerManager {
            return instance ?: synchronized(this) {
                instance ?: ClaudeCodeAppServerManager(context).also { instance = it }
            }
        }
    }

    /** Flutter Method Channel 入口 */
    suspend fun handleMethod(
        method: String,
        arguments: Map<String, Any>,
        eventSink: EventChannel.EventSink?
    ): Map<String, Any> {
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
            else -> mapOf("error" to "Unknown method: $method")
        }
    }

    /** 返回 Claude Code 状态 */
    private suspend fun status(): Map<String, Any> {
        val store = ClaudeCodeMultiProfileStore.getInstance(appContext)
        val installed = isClaudeCodeInstalled()
        val hasConfig = store.hasStoredConfig
        return mapOf(
            "installed" to installed,
            "hasConfig" to hasConfig,
            "ready" to (installed && hasConfig)
        )
    }

    /** 发送消息给 Claude Code */
    private suspend fun send(
        arguments: Map<String, Any>,
        eventSink: EventChannel.EventSink?
    ): Map<String, Any> = mutex.withLock {
        val message = arguments["message"] as? String ?: ""
        if (message.isBlank()) {
            return@withLock mapOf("error" to "message is required")
        }

        val store = ClaudeCodeMultiProfileStore.getInstance(appContext)
        val profile = store.getActiveProfile() ?: run {
            eventSink?.success(mapOf("type" to "error", "error" to "No active Claude Code profile"))
            return@withLock mapOf("error" to "No active Claude Code profile")
        }

        // 推送开始事件
        eventSink?.success(mapOf(
            "type" to "claude/event",
            "event" to "turn/started",
            "threadId" to "claude-default"
        ))

        // 确保已安装
        if (!ensureClaudeCodeInstalled()) {
            eventSink?.success(mapOf(
                "type" to "error",
                "error" to "Claude Code CLI is not installed"
            ))
            return@withLock mapOf("error" to "Claude Code CLI is not installed")
        }

        // 构建环境变量
        val envPrefix = buildEnvironmentPrefix(profile)
        val escapedMessage = message.replace("\\", "\\\\").replace("\"", "\\\"").replace("`", "\\`").replace("$", "\\$")
        val command = "$envPrefix claude -p \"$escapedMessage\" 2>&1"

        return@withLock try {
            val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
                command = command,
                executorKey = "claude-code-run",
                timeoutMs = RUN_TIMEOUT_MS
            )

            if (result.isOk) {
                // 推送输出事件
                eventSink?.success(mapOf(
                    "type" to "claude/event",
                    "event" to "turn/message",
                    "threadId" to "claude-default",
                    "content" to result.output
                ))
                // 推送完成事件
                eventSink?.success(mapOf(
                    "type" to "claude/event",
                    "event" to "turn/completed",
                    "threadId" to "claude-default"
                ))
                mapOf("ok" to true, "output" to result.output)
            } else {
                eventSink?.success(mapOf(
                    "type" to "error",
                    "error" to result.error.ifBlank { "Claude Code execution failed" }
                ))
                mapOf("error" to result.error.ifBlank { "Execution failed" })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to run claude code", e)
            eventSink?.success(mapOf("type" to "error", "error" to (e.message ?: "Unknown error")))
            mapOf("error" to (e.message ?: "Unknown error"))
        }
    }

    // === Profile 管理 ===

    private fun listProfiles(): Map<String, Any> {
        val profiles = ClaudeCodeMultiProfileStore.getInstance(appContext).getAllProfiles()
        return mapOf("profiles" to profiles.map { p ->
            mapOf(
                "id" to p.id,
                "name" to p.name,
                "apiKey" to p.apiKey,
                "baseUrl" to p.baseUrl,
                "model" to p.model,
                "extraArgs" to p.extraArgs
            )
        })
    }

    private fun activeProfile(): Map<String, Any> {
        val p = ClaudeCodeMultiProfileStore.getInstance(appContext).getActiveProfile()
            ?: return mapOf("profile" to null)
        return mapOf("profile" to mapOf(
            "id" to p.id, "name" to p.name, "apiKey" to p.apiKey,
            "baseUrl" to p.baseUrl, "model" to p.model, "extraArgs" to p.extraArgs
        ))
    }

    private fun activateProfile(arguments: Map<String, Any>): Map<String, Any> {
        val id = arguments["id"] as? String ?: return mapOf("error" to "id is required")
        ClaudeCodeMultiProfileStore.getInstance(appContext).setActiveProfile(id)
        return mapOf("ok" to true)
    }

    private fun addProfile(arguments: Map<String, Any>): Map<String, Any> {
        val store = ClaudeCodeMultiProfileStore.getInstance(appContext)
        val profile = ClaudeCodeMultiProfileStore.Profile(
            id = "claude_${System.currentTimeMillis()}_${(100..999).random()}",
            name = arguments["name"] as? String ?: "Untitled",
            apiKey = arguments["apiKey"] as? String ?: "",
            baseUrl = arguments["baseUrl"] as? String ?: "",
            model = arguments["model"] as? String ?: "",
            extraArgs = arguments["extraArgs"] as? String ?: ""
        )
        store.addProfile(profile)
        return mapOf("ok" to true, "id" to profile.id)
    }

    private fun updateProfile(arguments: Map<String, Any>): Map<String, Any> {
        val id = arguments["id"] as? String ?: return mapOf("error" to "id is required")
        val store = ClaudeCodeMultiProfileStore.getInstance(appContext)
        val existing = store.getAllProfiles().firstOrNull { it.id == id }
            ?: return mapOf("error" to "Profile not found")
        val updated = existing.copy(
            name = arguments["name"] as? String ?: existing.name,
            apiKey = arguments["apiKey"] as? String ?: existing.apiKey,
            baseUrl = arguments["baseUrl"] as? String ?: existing.baseUrl,
            model = arguments["model"] as? String ?: existing.model,
            extraArgs = arguments["extraArgs"] as? String ?: existing.extraArgs
        )
        store.updateProfile(updated)
        return mapOf("ok" to true)
    }

    private fun deleteProfile(arguments: Map<String, Any>): Map<String, Any> {
        val id = arguments["id"] as? String ?: return mapOf("error" to "id is required")
        ClaudeCodeMultiProfileStore.getInstance(appContext).deleteProfile(id)
        return mapOf("ok" to true)
    }

    // === 内部工具 ===

    suspend fun isClaudeCodeInstalled(): Boolean {
        return try {
            val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
                command = "claude --version 2>&1 || echo '__CLAUDE_NOT_FOUND__'",
                executorKey = "claude-code-probe",
                timeoutMs = PROBE_TIMEOUT_MS
            )
            result.isOk && !result.output.contains("__CLAUDE_NOT_FOUND__")
        } catch (e: Exception) {
            false
        }
    }

    suspend fun ensureClaudeCodeInstalled(): Boolean {
        if (isClaudeCodeInstalled()) return true
        return try {
            val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
                command = "npm install -g @anthropic-ai/claude-code 2>&1",
                executorKey = "claude-code-install",
                timeoutMs = INSTALL_TIMEOUT_MS
            )
            isClaudeCodeInstalled()
        } catch (e: Exception) {
            false
        }
    }

    private fun buildEnvironmentPrefix(profile: ClaudeCodeMultiProfileStore.Profile): String {
        val parts = mutableListOf<String>()
        if (profile.apiKey.isNotBlank()) {
            parts.add("ANTHROPIC_API_KEY='${profile.apiKey.replace("'", "'\\''")}'")
        }
        if (profile.baseUrl.isNotBlank()) {
            parts.add("ANTHROPIC_BASE_URL='${profile.baseUrl.replace("'", "'\\''")}'")
        }
        return if (parts.isEmpty()) "" else parts.joinToString(" ")
    }
}
