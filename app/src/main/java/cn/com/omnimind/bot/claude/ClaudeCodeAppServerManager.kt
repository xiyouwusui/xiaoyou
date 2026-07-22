package cn.com.omnimind.bot.claude

import android.content.Context
import android.util.Log
import com.ai.assistance.operit.terminal.TerminalManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Claude Code CLI 管理器 — 管理在 proot 终端中运行的 Claude Code 实例。
 *
 * 与 CodexAppServerManager 类似的架构，使用 TerminalManager.executeHiddenCommand
 * 在 proot 环境中执行 Claude Code CLI 命令。
 *
 * Claude Code 通过 npm 全局安装 @anthropic-ai/claude-code，使用 `claude` 命令运行。
 * 配置通过 ANTHROPIC_API_KEY 和 ANTHROPIC_BASE_URL 环境变量传入。
 */
class ClaudeCodeAppServerManager private constructor(
    private val context: Context
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    companion object {
        private const val TAG = "ClaudeCodeManager"
        private const val CLAUDE_CODE_INSTALL_TIMEOUT_MS = 120_000L
        private const val CLAUDE_CODE_PROBE_TIMEOUT_MS = 15_000L
        private const val CLAUDE_CODE_RUN_TIMEOUT_MS = 300_000L

        @Volatile
        private var instance: ClaudeCodeAppServerManager? = null

        fun getInstance(context: Context): ClaudeCodeAppServerManager {
            return instance ?: synchronized(this) {
                instance ?: ClaudeCodeAppServerManager(context).also { instance = it }
            }
        }
    }

    /**
     * 检查 Claude Code CLI 是否已安装。
     * 使用 executeHiddenCommand 在 proot 环境中运行 `claude --version`。
     */
    suspend fun isClaudeCodeInstalled(): Boolean {
        return try {
            val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
                command = "claude --version 2>&1 || echo '__CLAUDE_NOT_FOUND__'",
                executorKey = "claude-code-probe",
                timeoutMs = CLAUDE_CODE_PROBE_TIMEOUT_MS
            )
            result.isOk && !result.output.contains("__CLAUDE_NOT_FOUND__")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check claude installation", e)
            false
        }
    }

    /**
     * 确保 Claude Code CLI 已安装。
     * 通过 npm 全局安装 @anthropic-ai/claude-code。
     */
    suspend fun ensureClaudeCodeInstalled(): Boolean {
        if (isClaudeCodeInstalled()) return true
        return try {
            Log.i(TAG, "Installing Claude Code CLI via npm...")
            val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
                command = "npm install -g @anthropic-ai/claude-code 2>&1",
                executorKey = "claude-code-install",
                timeoutMs = CLAUDE_CODE_INSTALL_TIMEOUT_MS
            )
            if (!result.isOk) {
                Log.e(TAG, "npm install failed: ${result.error}")
                return false
            }
            isClaudeCodeInstalled()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install claude code", e)
            false
        }
    }

    /**
     * 使用当前激活的配置运行 Claude Code CLI 的一次性命令。
     * Claude Code 的 `-p` (print) 模式：读取一条消息，输出结果后退出。
     *
     * @param message 要发送给 Claude Code 的消息
     * @return Claude Code 的输出，或 null 表示失败
     */
    suspend fun runOneShot(message: String): String? = mutex.withLock {
        val profileStore = ClaudeCodeMultiProfileStore.getInstance(appContext)
        val profile = profileStore.getActiveProfile() ?: run {
            Log.e(TAG, "No active Claude Code profile configured")
            return@withLock null
        }

        if (!ensureClaudeCodeInstalled()) {
            Log.e(TAG, "Claude Code CLI is not installed")
            return@withLock null
        }

        // 构建环境变量前缀
        val envPrefix = buildEnvironmentPrefix(profile)

        // 转义消息中的特殊字符
        val escapedMessage = message.replace("\\", "\\\\").replace("\"", "\\\"")

        val command = "$envPrefix claude -p \"$escapedMessage\" 2>&1"

        return@withLock try {
            val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
                command = command,
                executorKey = "claude-code-run",
                timeoutMs = CLAUDE_CODE_RUN_TIMEOUT_MS
            )
            if (result.isOk) {
                result.output
            } else {
                Log.e(TAG, "Claude Code run failed: ${result.error}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to run claude code", e)
            null
        }
    }

    /**
     * 获取当前激活的配置。
     */
    fun getActiveProfile(): ClaudeCodeMultiProfileStore.Profile? {
        return ClaudeCodeMultiProfileStore.getInstance(appContext).getActiveProfile()
    }

    /**
     * 切换激活配置。
     */
    fun switchProfile(profileId: String) {
        ClaudeCodeMultiProfileStore.getInstance(appContext).setActiveProfile(profileId)
    }

    /**
     * 构建环境变量前缀字符串。
     * 将 API Key 和 Base URL 设置为环境变量。
     */
    private fun buildEnvironmentPrefix(profile: ClaudeCodeMultiProfileStore.Profile): String {
        val parts = mutableListOf<String>()
        if (profile.apiKey.isNotBlank()) {
            val escapedKey = profile.apiKey.replace("'", "'\\''")
            parts.add("ANTHROPIC_API_KEY='$escapedKey'")
        }
        if (profile.baseUrl.isNotBlank()) {
            val escapedUrl = profile.baseUrl.replace("'", "'\\''")
            parts.add("ANTHROPIC_BASE_URL='$escapedUrl'")
        }
        return if (parts.isEmpty()) "" else parts.joinToString(" ")
    }
}
