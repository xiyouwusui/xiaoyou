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
 * Claude Code CLI 管理器 — 使用 TerminalManager.executeHiddenCommand 在 proot 中运行。
 */
class ClaudeCodeAppServerManager private constructor(
    private val context: Context
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    companion object {
        private const val TAG = "ClaudeCodeManager"
        private const val INSTALL_TIMEOUT_MS = 120_000L
        private const val PROBE_TIMEOUT_MS = 15_000L
        private const val RUN_TIMEOUT_MS = 300_000L

        @Volatile
        private var instance: ClaudeCodeAppServerManager? = null

        fun getInstance(context: Context): ClaudeCodeAppServerManager {
            return instance ?: synchronized(this) {
                instance ?: ClaudeCodeAppServerManager(context).also { instance = it }
            }
        }
    }

    suspend fun isClaudeCodeInstalled(): Boolean {
        return try {
            val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
                command = "claude --version 2>&1 || echo '__CLAUDE_NOT_FOUND__'",
                executorKey = "claude-code-probe",
                timeoutMs = PROBE_TIMEOUT_MS
            )
            result.isOk && !result.output.contains("__CLAUDE_NOT_FOUND__")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check claude installation", e)
            false
        }
    }

    suspend fun ensureClaudeCodeInstalled(): Boolean {
        if (isClaudeCodeInstalled()) return true
        return try {
            Log.i(TAG, "Installing Claude Code CLI via npm...")
            val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
                command = "npm install -g @anthropic-ai/claude-code 2>&1",
                executorKey = "claude-code-install",
                timeoutMs = INSTALL_TIMEOUT_MS
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

        val envPrefix = buildEnvironmentPrefix(profile)
        val escapedMessage = message.replace("\\", "\\\\").replace("\"", "\\\"")
        val command = "$envPrefix claude -p \"$escapedMessage\" 2>&1"

        return@withLock try {
            val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
                command = command,
                executorKey = "claude-code-run",
                timeoutMs = RUN_TIMEOUT_MS
            )
            if (result.isOk) result.output else {
                Log.e(TAG, "Claude Code run failed: ${result.error}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to run claude code", e)
            null
        }
    }

    fun getActiveProfile(): ClaudeCodeMultiProfileStore.Profile? {
        return ClaudeCodeMultiProfileStore.getInstance(appContext).getActiveProfile()
    }

    fun switchProfile(profileId: String) {
        ClaudeCodeMultiProfileStore.getInstance(appContext).setActiveProfile(profileId)
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
