package cn.com.omnimind.assists.task.vlmserver

import android.content.Context
import cn.com.omnimind.assists.TaskManager
import cn.com.omnimind.assists.api.bean.VlmTaskTerminalResult
import cn.com.omnimind.assists.api.bean.VlmTaskTerminalStatus
import cn.com.omnimind.assists.api.enums.TaskFinishType
import cn.com.omnimind.assists.api.enums.TaskType
import cn.com.omnimind.assists.api.interfaces.OnMessagePushListener
import cn.com.omnimind.assists.api.interfaces.TaskChangeListener
import cn.com.omnimind.assists.api.enums.toStatus
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.assists.task.Task
import cn.com.omnimind.assists.api.eventapi.ExecutionTaskEventApi
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.baselib.http.Http429Exception
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.util.exception.PrivacyBlockedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * 视觉模型执行任务
 */
open class VLMOperationTask(
    open val executionTaskEventApi: ExecutionTaskEventApi?,
    override val taskChangeListener: TaskChangeListener,
    private val onMessagePushListener: OnMessagePushListener? = null,
    override val taskManager: TaskManager
) : Task(taskChangeListener,taskManager), DeviceOperator {
    private val Tag = "VLMOperationTask"

    private lateinit var vlmOperationService: VLMOperationService
    private lateinit var androidDeviceOperator: AndroidDeviceOperator
    private lateinit var onTaskFinishListener: () -> Unit?
    
    /** 
     * 取消请求标记，用于在 delay 期间检查取消状态
     * 公开属性，供 ExecutionUIImpl 在 delay 循环中检查
     */
    @Volatile
    var isCancellationRequested: Boolean = false
        private set
    
    private var executionRecordId: Long = -1L // 执行记录 ID，用于任务结束时更新状态
    private var isSubTask: Boolean = false // 标识当前任务是否为子任务

    @Volatile
    private var pauseRequested: Boolean = false
    private lateinit var streamClient: VLMStreamClient

    private var taskContext: Context? = null

    // INFO动作等待通道：用于挂起任务等待用户回复
    private val userInputChannel = Channel<String>(Channel.Factory.UNLIMITED)

    // 用户主动暂停通道：用于用户点击"接管"按钮时暂停任务
    private val userPauseChannel = Channel<Unit>(Channel.Factory.CONFLATED)

    private var goal: String? = null
    private var taskStartTime = 0L//任务开始时间
    private var setStartWithNotShowReadFlag = false

    fun appendExternalMemory(memory: String): Boolean {
        val trimmed = memory.trim()
        if (trimmed.isEmpty()) return false
        if (!this::vlmOperationService.isInitialized) return false
        vlmOperationService.addExternalMemory(trimmed)
        return true
    }

    /**
     * Append a priority event to the VLM task
     * @param memory The event message
     * @param eventType The event type (e.g., "file_received")
     * @param suggestCompletion Whether to suggest VLM complete the task
     */
    fun appendPriorityEvent(memory: String, eventType: String, suggestCompletion: Boolean = false): Boolean {
        val trimmed = memory.trim()
        if (trimmed.isEmpty()) return false
        if (!this::vlmOperationService.isInitialized) return false
        vlmOperationService.addPriorityEvent(trimmed, eventType, suggestCompletion)
        return true
    }

    override suspend fun onTaskCreated() {
        super.onTaskCreated()
        streamClient = HttpVLMStreamClient(scope = taskScope)
        vlmOperationService = VLMOperationService(
            this,
            streamClient,
            onInfoAction = { question ->
                handleInfoAction(question)
            },
            onPauseCheck = {
                checkAndHandlePause()
            },
            isSubTask = isSubTask

        )
        androidDeviceOperator = AndroidDeviceOperator(executionTaskEventApi, taskContext)
    }

    /**
     * 处理INFO动作：小猫显示提示信息，用户在当前页面操作，操作完成后点击小猫继续
     */
    private suspend fun handleInfoAction(question: String): String {
        OmniLog.d(Tag, "INFO动作触发，向用户推送问题：$question")
        var mQuestion = if (question.isNotEmpty()) {
            "\n${question}"
        } else {
            question
        }
        val infoMessage = "小万需要你的帮助：$mQuestion"
        AccessibilityController.restoreKeyboard()

        onTaskStop(TaskFinishType.WAITING_INPUT, infoMessage)
        notifyTerminalResult(
                VlmTaskTerminalResult(
                    status = VlmTaskTerminalStatus.WAITING_INPUT,
                    message = infoMessage,
                    waitingQuestion = infoMessage
                )
            )

        if (onMessagePushListener != null) {
            try {
                onMessagePushListener.onVLMRequestUserInput(infoMessage)
                OmniLog.d(Tag, "已通知Flutter层")
            } catch (e: Exception) {
                OmniLog.e(Tag, "通知UI层失败: ${e.message}")
            }
        }

        OmniLog.d(Tag, "等待用户完成操作并点击继续...")
        val userConfirmation = userInputChannel.receive()
        OmniLog.d(Tag, "收到用户确认：$userConfirmation")

        AccessibilityController.hideKeyboard()
        setStartWithNotShowReadFlag = true
        onTaskStarted()
        taskStartTime = System.currentTimeMillis()
        return "用户已完成操作：$userConfirmation"
    }

    /**
     * 接收用户回复（公开方法，供外部调用）
     */
    fun provideUserInput(input: String) {
        OmniLog.d(Tag, "接收用户输入：$input")
        taskScope.launch {
            userInputChannel.send(input)
        }
    }

    /**
     * 检查并处理用户暂停请求（VLMOperationService每步执行前调用）
     */
    private suspend fun checkAndHandlePause() {
        if (pauseRequested) {
            OmniLog.d(Tag, "检测到用户暂停请求，进入暂停状态")
            pauseRequested = false // 重置标志
            handleUserPause()
        }
    }

    /**
     * 用户主动暂停任务：不推送按钮卡片，直接切换小猫状态为"继续"
     */
    private suspend fun handleUserPause() {
        onTaskStop(TaskFinishType.USER_PAUSED, "")
        executionTaskEventApi?.onVlmTaskPaused(this)
        // 不推送按钮卡片，直接通知UI层切换小猫状态
        AccessibilityController.Companion.restoreKeyboard()
        if (onMessagePushListener != null) {
            try {
                onMessagePushListener.onVLMRequestUserInput("已接管控制，完成操作后点击继续")
            } catch (e: Exception) {
                OmniLog.e(Tag, "通知UI层失败: ${e.message}")
            }
        }
        userPauseChannel.receive() // 阻塞等待用户点击继续
        AccessibilityController.Companion.hideKeyboard()
        setStartWithNotShowReadFlag = true
        onTaskStarted()
        taskStartTime = System.currentTimeMillis()
    }

    /**
     * 请求暂停任务（公开方法，供UI调用）
     */
    fun requestPause() {
        OmniLog.d(Tag, "收到暂停请求")
        pauseRequested = true
    }

    /**
     * 从暂停状态恢复（公开方法，供UI调用）
     */
    fun resumeFromPause() {
        OmniLog.d(Tag, "收到继续请求")
        taskScope.launch {
            userPauseChannel.send(Unit)
        }
    }

    private fun notifyTerminalResult(result: VlmTaskTerminalResult) {
        try {
            onMessagePushListener?.onVlmTaskResult(result)
        } catch (e: Exception) {
            OmniLog.e(Tag, "通知VLM终态结果失败: ${e.message}")
        }
    }

    private fun extractFinishedContent(report: TaskExecutionReport): String {
        val finishedStep = report.executionTrace.lastOrNull { it.action is FinishedAction }
        val fromResult = finishedStep?.result?.trim().orEmpty()
        if (fromResult.isNotEmpty()) return fromResult

        val fromAction = (finishedStep?.action as? FinishedAction)?.content?.trim().orEmpty()
        if (fromAction.isNotEmpty()) return fromAction

        val lastResult = report.executionTrace.asReversed()
            .mapNotNull { it.result?.trim()?.takeIf { value -> value.isNotEmpty() } }
            .firstOrNull()
        if (!lastResult.isNullOrEmpty()) return lastResult

        return "任务完成"
    }

    fun start(
        context: Context,
        goal: String,
        model: String?,
        maxSteps: Int?,
        packageName: String?,
        onTaskFinishListener: () -> Unit,
        skipGoHome: Boolean = false,  // 是否跳过回到主页，从当前页面开始执行
        stepSkillGuidance: String = ""
    ) {
        this.goal = goal;
        this.taskContext = context
        this.onTaskFinishListener = onTaskFinishListener
        super.start {
            AccessibilityController.Companion.hideKeyboard()
            val currentPackageName = packageName ?: (AccessibilityController.Companion.getPackageName() ?: "")
            val installedApps = AccessibilityController.Companion.mapInstalledApplications()

            executionRecordId = DatabaseHelper.saveExecutionRecord(
                context,
                goal,
                currentPackageName,
                "vlm",
                goal,
                null,
                "vlm"
            )
            OmniLog.d(Tag, "VLM Operation Task Is Running ! skipGoHome=$skipGoHome")
            try {
                taskStartTime = System.currentTimeMillis()
                val taskExecutionReport = if (!isSubTask) {
                    executeOpenAppFastPath(
                        goal = goal,
                        installedApps = installedApps,
                        packageName = packageName
                    ) ?: vlmOperationService.executeTask(
                        goal = goal,
                        installedApps = installedApps,
                        model = model ?: "scene.vlm.operation.primary",
                        maxSteps = maxSteps,
                        packageName = packageName,
                        skipGoHome = skipGoHome,
                        currentStepGoal = goal,
                        stepSkillGuidance = stepSkillGuidance
                    )
                } else {
                    vlmOperationService.executeTask(
                        goal = goal,
                        installedApps = installedApps,
                        model = model ?: "scene.vlm.operation.primary",
                        maxSteps = maxSteps,
                        packageName = packageName,
                        skipGoHome = skipGoHome,  // 使用传入的 skipGoHome 参数
                        currentStepGoal = goal,
                        stepSkillGuidance = stepSkillGuidance

                    )
                }
                OmniLog.d(Tag, "VLM Operation Task Finished: $taskExecutionReport")
                val finishType = when {
                    taskExecutionReport.success -> TaskFinishType.FINISH
                    else -> TaskFinishType.ERROR
                }
                val finishMessage = taskExecutionReport.error.orEmpty()
                OmniLog.i(
                    Tag,
                    "VLM task terminal state: finishType=$finishType success=${taskExecutionReport.success} error=${taskExecutionReport.error.orEmpty()}"
                )

                if (taskExecutionReport.success) {
                    notifyTerminalResult(
                        VlmTaskTerminalResult(
                            status = VlmTaskTerminalStatus.FINISHED,
                            message = extractFinishedContent(taskExecutionReport),
                            finishedContent = extractFinishedContent(taskExecutionReport),
                            feedback = taskExecutionReport.feedback
                        )
                    )
                } else {
                    val errorMessage = finishMessage.ifBlank { "任务执行失败" }
                    notifyTerminalResult(
                        VlmTaskTerminalResult(
                            status = VlmTaskTerminalStatus.ERROR,
                            message = errorMessage,
                            finishedContent = null,
                            errorMessage = errorMessage,
                            feedback = taskExecutionReport.feedback
                        )
                    )
                }
                onTaskStop(finishType, finishMessage)
                onTaskDestroy()
            } catch (e: PrivacyBlockedException) {
                notifyTerminalResult(
                    VlmTaskTerminalResult(
                        status = VlmTaskTerminalStatus.ERROR,
                        message = e.message ?: "应用未授权，已被隐私设置限制",
                        errorMessage = e.message ?: "应用未授权，已被隐私设置限制"
                    )
                )
                onTaskStop(TaskFinishType.ERROR, e.message ?: "应用未授权，已被隐私设置限制")
                onTaskDestroy()
            } catch (e: Http429Exception) {
                notifyTerminalResult(
                    VlmTaskTerminalResult(
                        status = VlmTaskTerminalStatus.ERROR,
                        message = e.message ?: "请求过于频繁",
                        errorMessage = e.message ?: "请求过于频繁"
                    )
                )
                onTaskStop(TaskFinishType.ERROR, e.message)
                onTaskDestroy()
            } catch (e: CancellationException) {
                OmniLog.i(Tag, "VLM Operation Task cancelled")
            } catch (e: Exception) {
                OmniLog.e(Tag, "VLM Operation Task Error: ${e.message}")
                notifyTerminalResult(
                    VlmTaskTerminalResult(
                        status = VlmTaskTerminalStatus.ERROR,
                        message = e.message ?: "任务执行异常",
                        errorMessage = e.message ?: "任务执行异常"
                    )
                )
                onTaskStop(TaskFinishType.ERROR, e.message ?: "任务执行异常")
                onTaskDestroy()
            }

        }
    }

    override suspend fun onTaskStarted() {
        if (setStartWithNotShowReadFlag) {
            setStartWithNotShowReadFlag = false
        } else if (!isSubTask) {  // 子任务时不显示"小万即将为您执行任务..."提示
            executionTaskEventApi?.onReadyStartVLMTask(this)
        }

        super.onTaskStarted()

    }

    /**
     * 专门用于sequence执行的启动方法，完全不操作UI状态
     */
    fun startAsSequenceSubTask(
        goal: String,
        model: String?,
        maxSteps: Int?,
        onTaskFinishListener: () -> Unit
    ) {
        this.onTaskFinishListener = onTaskFinishListener
        this.isSubTask = true  // 标记为子任务
        this.taskContext = BaseApplication.instance

        super.start {
            taskStartTime = System.currentTimeMillis()
            AccessibilityController.Companion.hideKeyboard()
            val installedApps = AccessibilityController.Companion.mapInstalledApplications()
            OmniLog.d(Tag, "VLM Operation Sequence Sub Task Is Running !")
            try {
                val report = vlmOperationService.executeTask(
                    goal = goal,
                    installedApps = installedApps,
                    model = model ?: "scene.vlm.operation.primary",
                    maxSteps = maxSteps,
                    skipGoHome = true  // 作为子任务执行时，不回退到桌面
                )
                OmniLog.d(Tag, "VLM Operation Sequence Sub Task Finished")
                onTaskStop(
                    if (report.success) TaskFinishType.FINISH else TaskFinishType.ERROR,
                    report.error.orEmpty()
                )
                onTaskDestroy()
            } catch (e: PrivacyBlockedException) {
                onTaskStop(TaskFinishType.ERROR, e.message ?: "应用未授权，已被隐私设置限制")
                onTaskDestroy()
            } catch (e: Exception) {
                onTaskStop(TaskFinishType.ERROR, e.message ?: "任务执行异常")
                onTaskDestroy()
            }
        }
    }

    override suspend fun onTaskStop(finishType: TaskFinishType, message: String) {
        super.onTaskStop(finishType, message)
        // 更新执行记录的状态
        if (finishType != TaskFinishType.WAITING_INPUT && finishType != TaskFinishType.USER_PAUSED && taskContext != null) {
            DatabaseHelper.updateExecutionRecordStatus(executionRecordId, finishType.toStatus())
        }
    }

    private suspend fun executeOpenAppFastPath(
        goal: String,
        installedApps: Map<String, String>,
        packageName: String?
    ): TaskExecutionReport? {
        if (packageName.isNullOrBlank()) return null
        if (!shouldUseOpenAppFastPath(goal, packageName, installedApps)) {
            OmniLog.i(
                Tag,
                "Skip open-app fast path: goal requires more than opening app. goal=$goal package=$packageName"
            )
            return null
        }

        val currentPackage = AccessibilityController.getPackageName().orEmpty()
        if (currentPackage == packageName) {
            OmniLog.i(Tag, "Open-app fast path hit: already in target package=$packageName")
            return TaskExecutionReport(
                success = true,
                goal = goal,
                totalSteps = 0,
                executionTrace = emptyList(),
                finalContext = UIContext(
                    overallTask = goal,
                    currentStepGoal = goal,
                    installedApplications = installedApps
                ),
                error = null
            )
        }

        val launchResult = androidDeviceOperator.launchApplication(packageName)
        val afterLaunchPackage = AccessibilityController.getPackageName().orEmpty()
        if (launchResult.success && afterLaunchPackage == packageName) {
            OmniLog.i(Tag, "Open-app fast path hit: launched target package=$packageName")
            return TaskExecutionReport(
                success = true,
                goal = goal,
                totalSteps = 0,
                executionTrace = emptyList(),
                finalContext = UIContext(
                    overallTask = goal,
                    currentStepGoal = goal,
                    installedApplications = installedApps
                ),
                error = null
            )
        }

        OmniLog.w(
            Tag,
            "Open-app fast path failed: pkg=$packageName, success=${launchResult.success}, current=$afterLaunchPackage"
        )
        return null
    }

    private fun shouldUseOpenAppFastPath(
        goal: String,
        packageName: String,
        installedApps: Map<String, String>
    ): Boolean {
        val normalizedGoal = normalizeGoalForIntentMatching(goal)
        if (normalizedGoal.isBlank()) {
            return false
        }

        val openVerbs = listOf("打开", "启动", "进入", "点开").map(::normalizeGoalForIntentMatching)
        val openVerbCount = openVerbs.sumOf { verb ->
            Regex(Regex.escape(verb)).findAll(normalizedGoal).count()
        }
        if (openVerbCount != 1 || openVerbs.none { normalizedGoal.contains(it) }) {
            return false
        }

        val appName = installedApps[packageName].orEmpty()
        val targetTokens = buildList {
            normalizeGoalForIntentMatching(appName).takeIf { it.isNotBlank() }?.let(::add)
            packageName.substringAfterLast('.')
                .takeIf { it.isNotBlank() }
                ?.let(::normalizeGoalForIntentMatching)
                ?.takeIf { it.length >= 3 }
                ?.let(::add)
        }.distinct()
        if (targetTokens.isEmpty() || targetTokens.none { normalizedGoal.contains(it) }) {
            return false
        }

        var remainder = normalizedGoal
        listOf("请帮我", "帮我", "请", "麻烦你", "麻烦", "帮忙").forEach { prefix ->
            remainder = remainder.removePrefix(normalizeGoalForIntentMatching(prefix))
        }
        openVerbs.forEach { verb ->
            remainder = remainder.replaceFirst(verb, "")
        }
        listOf("一下", "下", "app", "应用", "软件", "客户端").forEach { filler ->
            remainder = remainder.replaceFirst(normalizeGoalForIntentMatching(filler), "")
        }
        targetTokens.sortedByDescending { it.length }.forEach { token ->
            remainder = remainder.replaceFirst(token, "")
        }

        val trailingPoliteWords = listOf("即可", "就行", "就可以", "就好", "好了", "吧", "呀", "哈", "啦")
        trailingPoliteWords.forEach { word ->
            remainder = remainder.removePrefix(normalizeGoalForIntentMatching(word))
            remainder = remainder.removeSuffix(normalizeGoalForIntentMatching(word))
        }
        return remainder.isBlank()
    }

    private fun normalizeGoalForIntentMatching(text: String): String {
        if (text.isBlank()) return ""
        return text.lowercase()
            .replace(Regex("[\\s\\p{Punct}，。！？；：、“”‘’（）【】《》·`~@#%^&*_+=|<>/\\\\-]+"), "")
    }

    override suspend fun clickCoordinate(x: Float, y: Float): OperationResult {
        return androidDeviceOperator.clickCoordinate(x, y)
    }

    override suspend fun longClickCoordinate(x: Float, y: Float, duration: Long): OperationResult {
        return androidDeviceOperator.longClickCoordinate(x, y, duration)
    }

    override suspend fun inputText(text: String): OperationResult {
        return androidDeviceOperator.inputText(text)
    }

    override suspend fun pressHotKey(key: String): OperationResult {
        return androidDeviceOperator.pressHotKey(key)
    }

    suspend fun inputTextViaShell(text: String): OperationResult {
        return androidDeviceOperator.inputTextViaShell(text)
    }

    override suspend fun copyToClipboard(text: String): OperationResult {
        return androidDeviceOperator.copyToClipboard(text)
    }

    override suspend fun getClipboard(): String? {
        return androidDeviceOperator.getClipboard()
    }

    override suspend fun slideCoordinate(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        duration: Long
    ): OperationResult {
        return androidDeviceOperator.slideCoordinate(x1, y1, x2, y2, duration)
    }

    override suspend fun goHome(): OperationResult {
        return androidDeviceOperator.goHome()
    }

    override suspend fun goBack(): OperationResult {
        return androidDeviceOperator.goBack()
    }

    override suspend fun launchApplication(packageName: String): OperationResult {
        return androidDeviceOperator.launchApplication(packageName)
    }

    override suspend fun captureScreenshot(): String {
        return androidDeviceOperator.captureScreenshot()
    }

    override fun getLastScreenshotWidth(): Int {
        return androidDeviceOperator.getLastScreenshotWidth()
    }

    override fun getLastScreenshotHeight(): Int {
        return androidDeviceOperator.getLastScreenshotHeight()
    }

    override fun getDisplayWidth(): Int {
        return androidDeviceOperator.getDisplayWidth()
    }

    override fun getDisplayHeight(): Int {
        return androidDeviceOperator.getDisplayHeight()
    }

    override suspend fun showInfo(message: String) {
        androidDeviceOperator.showInfo(message)
    }

    fun finishTask() {
        OmniLog.d(Tag, "Finishing VLM Operation Task")
        isCancellationRequested = true
        notifyTerminalResult(
            VlmTaskTerminalResult(
                status = VlmTaskTerminalStatus.CANCELLED,
                message = "任务已取消"
            )
        )
        super.finishTask {
        }
        taskScope.cancel()
    }

    fun cancelTask() {
        OmniLog.d(Tag, "Cancelling VLM Operation Task - cancelling taskScope immediately")
        isCancellationRequested = true
        notifyTerminalResult(
            VlmTaskTerminalResult(
                status = VlmTaskTerminalStatus.CANCELLED,
                message = "任务已取消"
            )
        )
        taskScope.cancel("Task cancelled by user")
    }

    override suspend fun onTaskDestroy() {
        AccessibilityController.Companion.restoreKeyboard()
        onTaskFinishListener.invoke()
        super.onTaskDestroy()
    }

    override fun getTaskType(): TaskType {
        return TaskType.VLM_OPERATION_EXECUTION
    }
}
