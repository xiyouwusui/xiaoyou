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
            "install" -> install(eventSink)
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

    private suspend fun install(eventSink: EventChannel.EventSink?): Map<String, Any?> {
        eventSink?.success(mapOf("type" to "install/started", "message" to "正在检查环境..."))
        if (isClaudeCodeInstalled()) {
            eventSink?.success(mapOf("type" to "install/completed", "message" to "Claude Code 已安装"))
            return mapOf("ok" to true, "message" to "already installed")
        }
        eventSink?.success(mapOf("type" to "install/progress", "message" to "正在通过 npm 安装 @anthropic-ai/claude-code ..."))
        return try {
            val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
                command = "mkdir -p /root/.npm-global/bin && npm config set prefix /root/.npm-global && export PATH=\"/root/.npm-global/bin:\${PATH}\" && npm install -g @anthropic-ai/claude-code@latest 2>&1 && ln -sf /root/.npm-global/bin/claude /usr/local/bin/claude || true",
                executorKey = "claude-code-install",
                timeoutMs = INSTALL_TIMEOUT_MS
            )
            if (!result.isOk) {
                eventSink?.success(mapOf("type" to "install/error", "message" to "安装失败: ${result.error}"))
                return mapOf("ok" to false, "error" to result.error)
            }
            eventSink?.success(mapOf("type" to "install/progress", "message" to "安装完成，正在验证..."))
            val verified = isClaudeCodeInstalled()
            if (verified) {
                eventSink?.success(mapOf("type" to "install/completed", "message" to "Claude Code 安装成功"))
                mapOf("ok" to true, "message" to "installed")
            } else {
                eventSink?.success(mapOf("type" to "install/error", "message" to "安装后验证失败，claude 命令未找到"))
                mapOf("ok" to false, "error" to "verification failed")
            }
        } catch (e: Exception) {
            eventSink?.success(mapOf("type" to "install/error", "message" to "安装异常: ${e.message}"))
            mapOf("ok" to false, "error" to (e.message ?: "unknown"))
        }
    }

    private suspend fun send(
        arguments: Map<String, Any?>,
        eventSink: EventChannel.EventSink?
    ): Map<String, Any?> = mutex.withLock {
        val message = arguments["message"] as? String ?: ""
        if (message.isBlank()) {
            return@withLock mapOf("error" to "message is required")
        }

        val store = ClaudeCodeMultiProfileStore.getInstance(appContext)
        val profile = store.getActiveProfile() ?: run {
            eventSink?.success(mapOf("type" to "error", "error" to "未配置 Claude Code，请在设置中添加配置"))
            return@withLock mapOf("error" to "No active Claude Code profile")
        }

        eventSink?.success(mapOf("type" to "claude/event", "event" to "turn/started", "threadId" to "claude-default"))

        if (!isClaudeCodeInstalled()) {
            eventSink?.success(mapOf("type" to "error", "error" to "Claude Code CLI 未安装，请在设置中安装"))
            return@withLock mapOf("error" to "Claude Code CLI is not installed")
        }

        // 构建环境变量前缀
        val envParts = mutableListOf<String>()
        if (profile.apiKey.isNotBlank()) {
            envParts.add("ANTHROPIC_API_KEY='${profile.apiKey.replace("'", "'\\''")}'")
        }
        if (profile.baseUrl.isNotBlank()) {
            envParts.add("ANTHROPIC_BASE_URL='${profile.baseUrl.replace("'", "'\\''")}'")
        }
        val envPrefix = if (envParts.isEmpty()) "" else envParts.joinToString(" ")

        // 构建 model 参数
        val modelArg = if (profile.model.isNotBlank()) "--model ${profile.model} " else ""

        // 构建 extraArgs
        val extraArgs = if (profile.extraArgs.isNotBlank()) "${profile.extraArgs} " else ""

        // 转义消息
        val escapedMessage = message
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("`", "\\`")
            .replace("\$", "\\\$")
            .replace("\n", " ")

        // 完整命令: 设置 PATH + 环境变量 + claude -p
        val command = "$PATH_PREFIX $envPrefix claude -p $modelArg$extraArgs\"$escapedMessage\" 2>&1"

        Log.i(TAG, "Running claude command (model=${profile.model}, baseUrl=${profile.baseUrl.take(20)}...)")

        return@withLock try {
            val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
                command = command,
                executorKey = "claude-code-run",
                timeoutMs = RUN_TIMEOUT_MS
            )

            if (result.isOk) {
                eventSink?.success(mapOf(
                    "type" to "claude/event", "event" to "turn/message",
                    "threadId" to "claude-default", "content" to result.output
                ))
                eventSink?.success(mapOf(
                    "type" to "claude/event", "event" to "turn/completed",
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
