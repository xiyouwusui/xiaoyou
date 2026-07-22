package cn.com.omnimind.bot.claude

import android.content.Context
import android.util.Log
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.setup.EnvironmentSetupLogic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Claude Code CLI 进程管理器 — 管理在 proot 终端中运行的 Claude Code 实例。
 *
 * 与 CodexAppServerManager 类似的架构，但适配 Claude Code CLI 的接口。
 * Claude Code 通过 npm 全局安装 @anthropic-ai/claude-code，使用 `claude` 命令运行。
 */
class ClaudeCodeAppServerManager private constructor(
    private val context: Context
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val sessions = ConcurrentHashMap<String, ClaudeCodeSession>()

    companion object {
        private const val TAG = "ClaudeCodeManager"

        @Volatile
        private var instance: ClaudeCodeAppServerManager? = null

        fun getInstance(context: Context): ClaudeCodeAppServerManager {
            return instance ?: synchronized(this) {
                instance ?: ClaudeCodeAppServerManager(context).also { instance = it }
            }
        }
    }

    /** 检查 Claude Code CLI 是否已安装 */
    fun isClaudeCodeInstalled(): Boolean {
        return try {
            val terminalManager = TerminalManager.getInstance(appContext)
            val result = terminalManager.executeScript("claude --version 2>&1")
            result?.contains("claude", ignoreCase = true) == true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check claude installation", e)
            false
        }
    }

    /** 确保 Claude Code CLI 已安装 */
    fun ensureClaudeCodeInstalled(): Boolean {
        if (isClaudeCodeInstalled()) return true
        return try {
            val terminalManager = TerminalManager.getInstance(appContext)
            // Claude Code 通过 npm 全局安装
            terminalManager.executeScript("npm install -g @anthropic-ai/claude-code 2>&1")
            isClaudeCodeInstalled()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install claude code", e)
            false
        }
    }

    /** 创建新的 Claude Code 会话 */
    suspend fun createSession(sessionId: String): ClaudeCodeSession? = mutex.withLock {
        if (sessions.containsKey(sessionId)) {
            return@withLock sessions[sessionId]
        }

        val profileStore = ClaudeCodeMultiProfileStore.getInstance(appContext)
        val profile = profileStore.getActiveProfile() ?: return@withLock null

        if (!ensureClaudeCodeInstalled()) {
            Log.e(TAG, "Claude Code CLI is not installed")
            return@withLock null
        }

        val env = profileStore.buildEnvironment(profile)
        val session = ClaudeCodeSession(
            sessionId = sessionId,
            profile = profile,
            environment = env,
            appContext = appContext
        )
        sessions[sessionId] = session
        session
    }

    /** 获取会话 */
    fun getSession(sessionId: String): ClaudeCodeSession? = sessions[sessionId]

    /** 销毁会话 */
    suspend fun destroySession(sessionId: String) = mutex.withLock {
        sessions.remove(sessionId)?.shutdown()
    }

    /** 销毁所有会话 */
    suspend fun destroyAllSessions() = mutex.withLock {
        sessions.values.forEach { it.shutdown() }
        sessions.clear()
    }

    /** 获取当前激活的配置 */
    fun getActiveProfile(): ClaudeCodeMultiProfileStore.Profile? {
        return ClaudeCodeMultiProfileStore.getInstance(appContext).getActiveProfile()
    }

    /** 切换激活配置（需要重启会话才能生效） */
    fun switchProfile(profileId: String) {
        ClaudeCodeMultiProfileStore.getInstance(appContext).setActiveProfile(profileId)
    }
}

/**
 * 单个 Claude Code 会话 — 管理一个 Claude Code CLI 进程实例。
 */
class ClaudeCodeSession(
    val sessionId: String,
    val profile: ClaudeCodeMultiProfileStore.Profile,
    val environment: Map<String, String>,
    private val appContext: Context
) {
    private var process: Process? = null
    private var isRunning = false

    /** 启动 Claude Code CLI 进程 */
    fun start(): Boolean {
        if (isRunning) return true
        return try {
            val terminalManager = TerminalManager.getInstance(appContext)
            val envArgs = mutableListOf<String>()

            // 设置环境变量
            val envString = environment.entries.joinToString(" ") { (k, v) ->
                "$k='${v.replace("'", "'\\''")}'"
            }

            // 构建 claude 命令
            val cmd = if (envString.isNotEmpty()) {
                "env $envString claude ${profile.extraArgs}"
            } else {
                "claude ${profile.extraArgs}"
            }

            // 通过 TerminalManager 在 proot 环境中执行
            terminalManager.executeScript(cmd)
            isRunning = true
            true
        } catch (e: Exception) {
            Log.e("ClaudeCodeSession", "Failed to start session", e)
            false
        }
    }

    /** 发送消息到 Claude Code */
    fun sendMessage(message: String): String? {
        if (!isRunning) return null
        return try {
            val terminalManager = TerminalManager.getInstance(appContext)
            // 使用 pipe 模式：claude -p "message"
            val escaped = message.replace("'", "'\\''").replace("\"", "\\\"")
            terminalManager.executeScript("claude -p \"$escaped\"")
        } catch (e: Exception) {
            Log.e("ClaudeCodeSession", "Failed to send message", e)
            null
        }
    }

    /** 关闭会话 */
    fun shutdown() {
        isRunning = false
        process?.destroyForcibly()
        process = null
    }

    val running: Boolean get() = isRunning
}
