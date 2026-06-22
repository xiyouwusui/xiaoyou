package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.accessibility.util.XmlTreeUtils
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.baselib.http.Http429Exception
import cn.com.omnimind.baselib.llm.OfficialVlmOperationConfigStore
import cn.com.omnimind.baselib.llm.SceneOperationConfigStore
import cn.com.omnimind.baselib.llm.contentText
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.util.exception.PrivacyBlockedException
import cn.com.omnimind.assists.util.TreeEditDistance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/**
 * VLM操作服务 - 统一的UI自动化服务入口
 * 对应Python中的ExplorerExpert，提供完整的VLM驱动的UI操作能力
 */
class VLMOperationService(
    private val deviceOperator: DeviceOperator,
    private val streamClient: VLMStreamClient,
    private val onInfoAction: suspend (String) -> String, // INFO动作回调：传入问题，返回用户答案
    private val onPauseCheck: suspend () -> Unit = {}, // 暂停检查回调：用于检测用户主动暂停
    private val isSubTask: Boolean = false // 标识当前是否为子任务

) {
    private val Tag = "VLMOperationService"
    private val vlmClient = VLMClient()
    private val officialVlmClient = OfficialVlmOperationClient()
    private val gelabZeroParser = GelabZeroParser()
    private val contextManager = UIContextManager()
    private val actionExecutor = ActionExecutor(deviceOperator, contextManager)
    private val logJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        classDiscriminator = "action_type"
    }

    // Loading Sprite Agent (赛博精灵加载状态生成器)
    private val loadingSpriteAgent = LoadingSpriteAgent()

    // 解析失败计数器
    private var parseFailureCount = 0
    private val maxParseFailures = 3
    private val externalMemories = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val conversationState = VLMConversationState()
    private var lastReasoningOverlay = ""
    private var lastReasoningOverlayAt = 0L

    // Priority event management
    private var priorityEvent: Triple<String, String, Boolean>? = null  // (message, type, suggestCompletion)

    private fun resetConversationState() {
        conversationState.clear()
        lastReasoningOverlay = ""
        lastReasoningOverlayAt = 0L
    }

    fun addExternalMemory(memory: String) {
        val trimmed = memory.trim()
        if (trimmed.isEmpty()) return
        externalMemories.add(trimmed)
    }

    /**
     * Add a priority event that will be displayed prominently in the next VLM prompt
     * @param message The event message
     * @param eventType The event type (e.g., "file_received")
     * @param suggestCompletion Whether to suggest VLM complete the task
     */
    fun addPriorityEvent(message: String, eventType: String, suggestCompletion: Boolean = false) {
        priorityEvent = Triple(message, eventType, suggestCompletion)
        OmniLog.i(Tag, "Added priority event: type=$eventType, suggestCompletion=$suggestCompletion, msg=$message")
    }

    private fun drainExternalMemories(context: UIContext): UIContext {
        var updatedContext = context

        // Drain regular external memories
        if (externalMemories.isNotEmpty()) {
            val drained = ArrayList<String>()
            while (true) {
                val item = externalMemories.poll() ?: break
                drained.add(item)
            }
            if (drained.isNotEmpty()) {
                updatedContext = updatedContext.copy(keyMemory = context.keyMemory + drained)
            }
        }

        // Drain priority event
        val event = priorityEvent
        if (event != null) {
            updatedContext = updatedContext.copy(
                priorityEvent = event.first,
                priorityEventType = event.second,
                suggestCompletion = event.third
            )
            priorityEvent = null  // Clear after consuming
            OmniLog.i(Tag, "Priority event consumed: ${event.second}")
        }

        return updatedContext
    }

    private suspend fun ensureTaskActive(stage: String) {
        currentCoroutineContext().ensureActive()
    }

    private suspend fun safePauseCheck(stage: String) {
        ensureTaskActive("before_pause_check_$stage")
        try {
            onPauseCheck()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OmniLog.e(Tag, "暂停检查异常($stage): ${e.message}")
        }
        ensureTaskActive("after_pause_check_$stage")
    }

    /**
     * 执行完整的任务流程
     */
    suspend fun executeTask(
        goal: String,
        installedApps: Map<String, String> = emptyMap(),
        model: String = "scene.vlm.operation.primary",
        maxSteps: Int? = null,
        packageName: String? = null,
        skipGoHome: Boolean = false,
        currentStepGoal: String = goal,
        stepSkillGuidance: String = ""
    ): TaskExecutionReport {

        val normalizedMaxSteps = maxSteps?.takeIf { it > 0 }
        OmniLog.d(Tag, "executeTask - package_name: $packageName, skipGoHome: $skipGoHome")

        resetConversationState()
        ensureTaskActive("execute_task_start")

        // 任务开始执行时，先回到手机首页Home（除非 skipGoHome = true）
        if (!skipGoHome) {
            if (packageName != null && packageName.isNotEmpty()) {
                try {
                    ensureTaskActive("before_launch_application")
                    val launchResult = deviceOperator.launchApplication(packageName)
                    if (launchResult.success) {
                        OmniLog.d(Tag, "成功拉起应用: $packageName")
                        delay(2000L)
                        ensureTaskActive("after_launch_application_delay")
                    } else {
                        OmniLog.e(Tag, "拉起应用失败: ${launchResult.message}")
                    }
                } catch (e: PrivacyBlockedException) {
                    // 隐私限制异常，继续抛出异常
                    OmniLog.e(Tag, "应用因隐私设置被阻止: ${e.message}")
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    OmniLog.e(Tag, "拉起应用异常: ${e.message}")
                }
            }
        } else {
            OmniLog.d(Tag, "skipGoHome=true，跳过回到桌面和启动应用的操作")
        }

        var context = contextManager.initializeContext(
            overallTask = goal,
            installedApplications = installedApps,
            maxSteps = normalizedMaxSteps,
            currentStepGoal = currentStepGoal,
            stepSkillGuidance = stepSkillGuidance
        )
        context = drainExternalMemories(context)
        val executionTrace = mutableListOf<UIStep>()
        var lastError: String? = null
        // 内部传递用 scene.xxx 格式，不要提前解析
        var useModel = model

        // 预生成赛博精灵加载提示词（异步，不阻塞主流程）
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                loadingSpriteAgent.prepareForTask(goal)
            } catch (e: Exception) {
                OmniLog.w(Tag, "预生成加载提示词失败: ${e.message}")
            }
        }

        var stepIndex = 0
        while (normalizedMaxSteps == null || stepIndex < normalizedMaxSteps) {
            // 检查用户是否请求暂停（每一步执行前都检查）
            safePauseCheck("before_step_$stepIndex")
            context = drainExternalMemories(context)

            val result = executeSingleStepWithTimeOut(context, useModel)
            ensureTaskActive("after_single_step_$stepIndex")

            if (result.feedback != null) {
                result.step?.let { feedbackStep ->
                    context = result.context
                    context = updateContext(feedbackStep, context)
                    executionTrace.add(feedbackStep)
                }
                return TaskExecutionReport(
                    success = false,
                    goal = goal,
                    totalSteps = stepIndex + 1,
                    executionTrace = executionTrace,
                    finalContext = context,
                    error = "VLM反馈: ${result.feedback}",
                    feedback = result.feedback
                )
            }

            if (!result.success) {
                result.step?.let { failedStep ->
                    val stepJson =
                        runCatching { logJson.encodeToString(failedStep) }.getOrElse { failedStep.toString() }
                    OmniLog.d(Tag, "Step $stepIndex detail (failure): $stepJson")
                }
                OmniLog.e(
                    Tag,
                    "Step $stepIndex failed: ${result.error ?: "unknown error"}; step=${result.step?.action?.name}"
                )
                println("VLM step $stepIndex failed: ${result.error ?: "unknown error"}; step=${result.step?.action?.name}")

                // 使用 result.context，并将当前 step 添加到 trace 中
                context = result.context
                if (result.step != null) {
                    context = updateContext(result.step, context)
                    executionTrace.add(result.step)
                }
                lastError = result.error

                if (parseFailureCount >= maxParseFailures) {
                    return TaskExecutionReport(
                        success = false,
                        goal = goal,
                        totalSteps = stepIndex + 1,
                        executionTrace = executionTrace,
                        finalContext = context,
                        error = "VLM解析失败次数超过限制(${maxParseFailures}次)，任务终止"
                    )
                }

                if (result.error?.contains("解析失败") == true ||
                    result.error?.contains("定位失败") == true ||
                    result.error?.contains("不支持的操作类型") == true ||
                    result.error?.contains("Failed to parse response") == true ||
                    result.error?.contains("Serializer for subclass") == true
                ) {
                    stepIndex++
                    continue
                } else {
                    break
                }
            }

            val step = result.step!!

            runCatching { logJson.encodeToString(step) }
                .onSuccess { OmniLog.d(Tag, "Step $stepIndex detail: $it") }
                .onFailure { OmniLog.w(Tag, "Step $stepIndex log encode failed: ${it.message}") }

            OmniLog.d(
                Tag,
                "Step $stepIndex success: action=${step.action.name} result=${step.result ?: "OK"}"
            )
            println("VLM step $stepIndex success: action=${step.action.name} result=${step.result ?: "OK"}")

            // 使用 result.context，并将当前 step 添加到 trace 中
            context = result.context
            context = updateContext(step, context)
            executionTrace.add(step)

            if (step.action is FinishedAction) {
                return TaskExecutionReport(
                    success = true,
                    goal = goal,
                    totalSteps = stepIndex + 1,
                    executionTrace = executionTrace,
                    finalContext = context,
                    error = null
                )
            }
            if (step.action is InfoAction) {
                try {
                    //接管需要将任务执行时间清空

                    val userAnswer = onInfoAction(step.action.value)
                    ensureTaskActive("after_info_action_$stepIndex")

                    val userReplyStep = UIStep(
                        observation = "用户回复：$userAnswer",
                        thought = "收到用户回复，继续执行任务",
                        action = RecordAction(content = "用户回答了：$userAnswer"),
                        result = "已记录用户回复"
                    )
                    context = updateContext(userReplyStep, context)
                    executionTrace.add(userReplyStep)
                    stepIndex++
                    continue
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return TaskExecutionReport(
                        success = false,
                        goal = goal,
                        totalSteps = stepIndex + 1,
                        executionTrace = executionTrace,
                        finalContext = context,
                        error = "INFO动作处理失败: ${e.message}"
                    )
                }
            }
            if (step.action is RequireUserChoiceAction ||
                step.action is RequireUserConfirmationAction
            ) {
                try {
                    val question = when (val action = step.action) {
                        is RequireUserChoiceAction -> {
                            buildString {
                                append(action.prompt)
                                if (action.options.isNotEmpty()) {
                                    append("\n可选项：")
                                    append(action.options.joinToString(" / "))
                                }
                            }
                        }

                        is RequireUserConfirmationAction -> action.prompt
                        else -> step.result.orEmpty()
                    }
                    val userAnswer = onInfoAction(question)
                    ensureTaskActive("after_user_interaction_$stepIndex")
                    val userReplyStep = UIStep(
                        observation = "用户回复：$userAnswer",
                        thought = "收到用户交互结果，继续执行任务",
                        action = RecordAction(content = "用户交互结果：$userAnswer"),
                        result = "已记录用户交互结果"
                    )
                    context = updateContext(userReplyStep, context)
                    executionTrace.add(userReplyStep)
                    stepIndex++
                    continue
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return TaskExecutionReport(
                        success = false,
                        goal = goal,
                        totalSteps = stepIndex + 1,
                        executionTrace = executionTrace,
                        finalContext = context,
                        error = "用户交互动作处理失败: ${e.message}"
                    )
                }
            }
            if (step.action is AbortAction) {
                return TaskExecutionReport(
                    success = false,
                    goal = goal,
                    totalSteps = stepIndex + 1,
                    executionTrace = executionTrace,
                    finalContext = context,
                    error = "任务终止: ${(step.action as AbortAction).value}"
                )
            }
            stepIndex++
        }

        return TaskExecutionReport(
            success = false,
            goal = goal,
            totalSteps = executionTrace.size,
            executionTrace = executionTrace,
            finalContext = context,
            error = lastError ?: "任务未完成"
        )
    }



    private fun updateContext(step: UIStep, context: UIContext): UIContext {
        val updated = if (step.action is RecordAction) {
            contextManager.addKeyMemory(
                contextManager.updateContext(context, step),
                step.action.content
            )
        } else {
            contextManager.updateContext(context, step)
        }
        return maybeCompressGelabState(updated)
    }

    private fun maybeCompressGelabState(context: UIContext): UIContext {
        val interval = 10
        val recentWindow = 10
        val traceSize = context.trace.size
        val compressibleSteps = traceSize - recentWindow
        if (compressibleSteps < interval) return context

        val localTarget = (compressibleSteps / interval) * interval
        if (localTarget <= 0) return context
        val absoluteTarget = context.compressedUptoStep + localTarget

        val previousState = context.compressedState.trim()
        val chunk = context.trace
            .take(localTarget)
        val compressedState = buildGelabCompressedState(
            context = context,
            previousState = previousState,
            chunk = chunk,
            compressedUptoStep = absoluteTarget
        )
        val recentTrace = context.trace.drop(localTarget)
        val totalStepsUsed = absoluteTarget + recentTrace.size
        return context.copy(
            trace = recentTrace,
            stepsUsed = totalStepsUsed,
            stepsRemaining = context.maxSteps?.let { (it - totalStepsUsed).coerceAtLeast(0) },
            runningSummary = compressedState,
            compressedState = compressedState,
            compressedUptoStep = absoluteTarget
        )
    }

    private fun buildGelabCompressedState(
        context: UIContext,
        previousState: String,
        chunk: List<UIStep>,
        compressedUptoStep: Int
    ): String {
        fun String.cleanField(): String = replace("\r", " ")
            .replace("\n", " ")
            .trim()
            .ifBlank { "none" }

        val importantInputs = mutableListOf<String>()
        val importantObservations = mutableListOf<String>()
        val completedProgress = mutableListOf<String>()
        val pendingRisks = mutableListOf<String>()
        var currentSubgoal = context.activeGoal().cleanField()
        var lastEffectiveState = "none"

        if (previousState.isNotBlank()) {
            importantObservations += previousState
        }

        chunk.forEachIndexed { index, step ->
            val stepNumber = context.compressedUptoStep + index + 1
            val observation = step.observation.cleanField()
            val thought = step.thought.cleanField()
            val summary = step.summary.cleanField()
            val result = step.result.orEmpty().cleanField()

            if (observation != "none") {
                importantObservations += "step $stepNumber: $observation"
                lastEffectiveState = "step $stepNumber: $observation"
            }
            if (summary != "none") {
                completedProgress += "step $stepNumber: $summary"
            } else if (result != "none") {
                completedProgress += "step $stepNumber: ${step.action.name} -> $result"
            }
            if (thought != "none") {
                currentSubgoal = "step $stepNumber: $thought"
            }
            when (val action = step.action) {
                is InfoAction -> {
                    importantInputs += "step $stepNumber: agent asked user ${action.value.cleanField()}"
                    pendingRisks += "step $stepNumber: user input required"
                }
                is AbortAction -> pendingRisks += "step $stepNumber: abort ${action.value.cleanField()}"
                is RecordAction -> importantObservations += "step $stepNumber: ${action.content.cleanField()}"
                else -> {}
            }
        }

        fun formatList(items: List<String>): String {
            val normalized = items
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "none" }
                .distinct()
                .takeLast(10)
            return if (normalized.isEmpty()) "- none" else normalized.joinToString("\n") { "- $it" }
        }

        return buildString {
            appendLine("<<<STATE_COMPRESSION>>>")
            appendLine("covered_steps: 1-$compressedUptoStep")
            appendLine("task: ${context.overallTask.cleanField()}")
            appendLine("important_user_inputs:")
            appendLine(formatList(importantInputs))
            appendLine("important_observations:")
            appendLine(formatList(importantObservations))
            appendLine("completed_progress:")
            appendLine(formatList(completedProgress))
            appendLine("current_subgoal: $currentSubgoal")
            appendLine("pending_risks:")
            appendLine(formatList(pendingRisks))
            appendLine("last_effective_state: $lastEffectiveState")
            append("<<<END_STATE_COMPRESSION>>>")
        }
    }

    suspend fun executeSingleStepWithTimeOut(
        context: UIContext,
        useModel: String = "scene.vlm.operation.primary"
    ): VLMOperationResult {
        return try {
            executeSingleStep(context, useModel)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PrivacyBlockedException) {
            // 隐私限制异常，继续抛出异常
            throw e
        } catch (e: Exception) {
            VLMOperationResult(
                success = false,
                error = "执行单步任务异常: ${e.message}",
                step = null,
                context = context
            )
        }
    }

    /**
     * 移除尾部的 WaitAction，避免深度递归导致栈溢出
     */
    fun List<UIStep>.cleanTopWaitAction(): List<UIStep> {
        if (isEmpty()) return this
        // 迭代式处理，O(n)，无递归栈风险
        return dropLastWhile { it.action is WaitAction }
    }

    suspend fun executeSingleStep(
        context: UIContext,
        model: String = "scene.vlm.operation.primary"
    ): VLMOperationResult {
        // 内部传递都用 scene.xxx 格式，只在需要判断模型类型或发送请求时才解析
        if (shouldUseOfficialVlmOperation(model)) {
            return executeOfficialSingleStep(context, model)
        }

        val maxRetries = 3
        // 创建可变的工作变量
        var _context = context

        var stabilityAttempt = 0
        while (stabilityAttempt < maxRetries) {
            try {
                safePauseCheck("before_attempt_$stabilityAttempt")

                OmniLog.d(
                    Tag,
                    "executeSingleStep: stabilityAttempt=$stabilityAttempt, overallTask=${_context.overallTask}, currentStepGoal=${_context.activeGoal()}"
                )
                println("executeSingleStep: stabilityAttempt=$stabilityAttempt, overallTask=${_context.overallTask}, currentStepGoal=${_context.activeGoal()}")
                ensureTaskActive("before_screenshot_$stabilityAttempt")
                val screenshot = deviceOperator.captureScreenshot()
                safePauseCheck("after_screenshot_$stabilityAttempt")
                val beforeXml = captureCurrentXml()
                safePauseCheck("after_capture_xml_$stabilityAttempt")

                val maxToolCallRetries = 2
                var toolCallRetryCount = 0
                var retryState: VLMToolCallRetryState? = null
                var vlmResult: VLMResult
                var sceneTurn: SceneChatCompletionTurn? = null
                var currentUserTextSnapshot = ""
                conversationState.updateStreamingReasoning("")
                lastReasoningOverlay = ""
                lastReasoningOverlayAt = 0L

                while (true) {
                    val requestEnvelope = vlmClient.buildUIOperationRequest(
                        context = _context,
                        screenshot = screenshot,
                        conversationState = conversationState,
                        model = model,
                        retryState = retryState
                    )
                    currentUserTextSnapshot = requestEnvelope.currentUserText
                    OmniLog.i(
                        Tag,
                        "Dispatching VLM stream request: attempt=$stabilityAttempt toolRetry=$toolCallRetryCount scene=$model activeGoal=${_context.activeGoal()} traceSize=${_context.trace.size} historyRounds=${conversationState.roundCount()}"
                    )

                    safePauseCheck("before_http_${stabilityAttempt}_retry_$toolCallRetryCount")
                    val httpClientStartTime = System.currentTimeMillis()
                    val streamedTurn = try {
                        streamClient.streamTurn(
                            request = requestEnvelope.request,
                            onReasoningUpdate = { reasoning ->
                                conversationState.updateStreamingReasoning(reasoning)
                                emitReasoningOverlay(reasoning)
                            }
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val streamError = buildStreamFailureMessage(e)
                        OmniLog.e(Tag, "VLM stream request failed: $streamError")
                        val failureStep = UIStep(
                            observation = "STREAM_ERROR",
                            thought = "VLM流式请求失败,忽略后面的action字段",
                            action = RecordAction(content = streamError),
                            result = "VLM流式请求失败"
                        )
                        return VLMOperationResult(
                            success = false,
                            error = streamError,
                            step = failureStep,
                            context = _context
                        )
                    }
                    sceneTurn = streamedTurn
                    safePauseCheck("after_http_${stabilityAttempt}_retry_$toolCallRetryCount")
                    OmniLog.i(
                        "TimeRecord",
                        "VLM1-streamClient (stabilityAttempt:$stabilityAttempt toolRetry:$toolCallRetryCount) took ${System.currentTimeMillis() - httpClientStartTime} ms"
                    )
                    val rawResponsePreview = streamedTurn.turn.message.contentText()
                        .ifBlank { streamedTurn.turn.reasoning }
                        .take(4000)
                    OmniLog.d(
                        Tag,
                        "Raw VLM response: $rawResponsePreview"
                    )
                    OmniLog.d(
                        Tag,
                        "VLM stream finish_reason=${streamedTurn.turn.finishReason.orEmpty()} tool_calls=${streamedTurn.turn.message.toolCalls?.size ?: 0}"
                    )
                    val responseRoute = streamedTurn.route?.trim().orEmpty()
                    if (responseRoute.isNotEmpty()) {
                        OmniLog.i(Tag, "vlm_route=$responseRoute scene=$model")
                    }

                    // 解析链路统一由主场景解析器处理
                    vlmResult = vlmClient.parseVLMResponse(streamedTurn, model)
                    safePauseCheck("after_parse_${stabilityAttempt}_retry_$toolCallRetryCount")

                    if (
                        vlmResult.shouldRetryForToolCall &&
                        vlmResult.step == null &&
                        toolCallRetryCount < maxToolCallRetries
                    ) {
                        val thinkingText = buildThinkingOverlayText(vlmResult.thinking)
                        if (!isSubTask && thinkingText.isNotBlank()) {
                            emitReasoningOverlay(thinkingText)
                        }
                        toolCallRetryCount++
                        retryState = VLMToolCallRetryState(
                            retryIndex = toolCallRetryCount,
                            thinking = vlmResult.thinking ?: VLMThinkingContext(),
                            failureReason = vlmResult.error
                        )
                        val retryReason = vlmResult.error?.takeIf { it.isNotBlank() }
                            ?: "模型未返回标准 tool_calls"
                        OmniLog.w(
                            Tag,
                            "$retryReason，进入协议纠偏重试 $toolCallRetryCount/$maxToolCallRetries; finish_reason=${vlmResult.thinking?.finishReason.orEmpty()}"
                        )
                        continue
                    }

                    break
                }

                if (!vlmResult.success || vlmResult.step == null) {
                    parseFailureCount++
                    val resolvedError = resolveVlmFailureMessage(vlmResult)
                    OmniLog.e(
                        Tag,
                        "Parse VLM response failed (#$parseFailureCount): $resolvedError"
                    )
                    val finalThinkingText = buildThinkingOverlayText(vlmResult.thinking)
                    if (!isSubTask && finalThinkingText.isNotBlank()) {
                        emitReasoningOverlay(finalThinkingText)
                    }

                    val failureStep = UIStep(
                        observation = vlmResult.thinking?.observation?.ifBlank { "VLM响应解析失败" }
                            ?: "VLM响应解析失败",
                        thought = buildParseFailureThought(vlmResult),
                        action = RecordAction(content = "解析失败: $resolvedError"),
                        result = "解析失败，第${parseFailureCount}次失败"
                    )

                    return VLMOperationResult(
                        success = false,
                        error = resolvedError,
                        step = failureStep,
                        context = _context
                    )
                }

                var processedStep = vlmResult.step!!
                // normalizeOpenAppAction 需要判断模型类型
                processedStep = normalizeOpenAppAction(processedStep, _context, model)

                if (processedStep.action is FeedbackAction) {
                    val feedbackAction = processedStep.action as FeedbackAction
                    val feedbackStep = UIStep(
                        observation = processedStep.observation,
                        thought = processedStep.thought,
                        action = feedbackAction,
                        result = feedbackAction.value,
                        summary = processedStep.summary
                    )
                    sceneTurn?.let { completedTurn ->
                        conversationState.appendRound(
                            vlmClient.buildConversationRound(
                                currentUserText = currentUserTextSnapshot,
                                assistantTurn = completedTurn,
                                executedStep = feedbackStep
                            )
                        )
                    }
                    return VLMOperationResult(
                        success = true,
                        step = feedbackStep,
                        context = _context,
                        error = null,
                        feedback = feedbackAction.value
                    )
                }

                when (processedStep.action) {
                    is ClickAction -> processedStep = updateActionWithCoordinates(
                        processedStep,
                        position = listOf(processedStep.action.x, processedStep.action.y)
                    )

                    is ScrollAction -> processedStep = updateActionWithCoordinates(
                        processedStep,
                        position = listOf(
                            processedStep.action.x1,
                            processedStep.action.y1,
                            processedStep.action.x2,
                            processedStep.action.y2
                        )
                    )

                    is LongPressAction -> processedStep = updateActionWithCoordinates(
                        processedStep,
                        position = listOf(processedStep.action.x, processedStep.action.y)
                    )

                    else -> {}
                }

                if (needsPreciseLocation(processedStep.action)) {
                    val afterXml = captureCurrentXml()
                    if (!isPageStableByXml(beforeXml, afterXml)) {
                        OmniLog.d(Tag, "页面不稳定，第${stabilityAttempt + 1}次重试")
                        println("页面不稳定，第${stabilityAttempt + 1}次重试")
                        safePauseCheck("before_retry_delay_$stabilityAttempt")
                        delay(500)
                        stabilityAttempt++  // 页面不稳定，增加重试计数
                        continue
                    }
                } else {
                    OmniLog.d(
                        Tag,
                        "Action ${processedStep.action.name} does not require precise location, skipping stability check"
                    )
                }

                safePauseCheck("before_action_${processedStep.action.name}_${stabilityAttempt}")
                ensureTaskActive("before_action_dispatch_${processedStep.action.name}_$stabilityAttempt")

                val executedStep = actionExecutor.act(
                    VLMStep(
                        observation = processedStep.observation,
                        thought = processedStep.thought,
                        action = processedStep.action,
                        summary = processedStep.summary
                    )
                )
                safePauseCheck("after_action_${processedStep.action.name}_${stabilityAttempt}")
                val finalStep = executedStep.copy(summary = processedStep.summary)

                OmniLog.d(
                    Tag,
                    "Execute action: ${finalStep.action.name}, result=${finalStep.result ?: "OK"}"
                )
                println("Execute action: ${finalStep.action.name}, result=${finalStep.result ?: "OK"}")

                if (finalStep.result?.contains("不支持的操作类型") == true) {
                    parseFailureCount++

                    return VLMOperationResult(
                        success = false,
                        error = "不支持的操作类型: ${finalStep.result}",
                        step = finalStep,
                        context = _context
                    )
                }

                parseFailureCount = 0
                sceneTurn?.let { completedTurn ->
                    conversationState.appendRound(
                        vlmClient.buildConversationRound(
                            currentUserText = currentUserTextSnapshot,
                            assistantTurn = completedTurn,
                            executedStep = finalStep
                        )
                    )
                }

                return VLMOperationResult(
                    success = true,
                    step = finalStep,
                    context = _context,
                    error = null
                )

            } catch (e: Http429Exception) {
                val failureStep = UIStep(
                    observation = "429",
                    thought = "服务端请求失败,忽略后面的action字段",
                    action = RecordAction(content = "服务端请求失败"),
                    result = "服务端请求失败"
                )
                return VLMOperationResult(
                    success = false,
                    error = "!200",
                    step = failureStep,
                    context = _context
                )
            } catch (e: PrivacyBlockedException) {
                // 隐私限制异常，继续抛出异常
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                OmniLog.e(Tag, "执行异常，第${stabilityAttempt + 1}次重试: ${e.message}")
                println("执行异常，第${stabilityAttempt + 1}次重试: ${e.message}")
                if (stabilityAttempt >= maxRetries - 1) {
                    return VLMOperationResult(
                        success = false,
                        error = "操作执行异常: ${e.message}",
                        step = null,
                        context = _context
                    )
                }
                stabilityAttempt++  // 执行异常，增加重试计数
                delay(500)
            }
        }

        return VLMOperationResult(
            success = false,
            error = "页面稳定性检测失败，达到最大重试次数",
            step = null,
            context = _context
        )
    }

    private fun shouldUseOfficialVlmOperation(model: String): Boolean {
        if (model != "scene.vlm.operation.primary") {
            return false
        }
        if (!SceneOperationConfigStore.getConfig().useOfficialService) {
            return false
        }
        val officialConfig = OfficialVlmOperationConfigStore.getConfig()
        if (!officialConfig.isConfigured()) {
            OmniLog.w(Tag, "official VLM operation is enabled but worker config is not ready, fallback to custom tool-call flow")
            return false
        }
        return true
    }

    private suspend fun executeOfficialSingleStep(
        context: UIContext,
        model: String
    ): VLMOperationResult {
        val maxRetries = 3
        var _context = context

        var stabilityAttempt = 0
        while (stabilityAttempt < maxRetries) {
            try {
                safePauseCheck("official_before_attempt_$stabilityAttempt")

                OmniLog.d(
                    Tag,
                    "executeOfficialSingleStep: stabilityAttempt=$stabilityAttempt, overallTask=${_context.overallTask}, currentStepGoal=${_context.activeGoal()}"
                )
                ensureTaskActive("official_before_screenshot_$stabilityAttempt")
                val screenshot = deviceOperator.captureScreenshot()
                safePauseCheck("official_after_screenshot_$stabilityAttempt")
                val beforeXml = captureCurrentXml()
                safePauseCheck("official_after_capture_xml_$stabilityAttempt")

                val config = OfficialVlmOperationConfigStore.getConfig()
                if (!config.isConfigured()) {
                    return VLMOperationResult(
                        success = false,
                        error = "官方 VLM 服务配置未就绪",
                        step = null,
                        context = _context
                    )
                }

                val prompt = PromptTemplate.buildGelabZeroPrompt(_context)
                safePauseCheck("official_before_http_$stabilityAttempt")
                val httpClientStartTime = System.currentTimeMillis()
                val officialResponse = officialVlmClient.complete(
                    config = config,
                    prompt = prompt,
                    screenshot = screenshot
                )
                safePauseCheck("official_after_http_$stabilityAttempt")
                OmniLog.i(
                    "TimeRecord",
                    "VLM1-officialClient (stabilityAttempt:$stabilityAttempt) took ${System.currentTimeMillis() - httpClientStartTime} ms"
                )

                if (!officialResponse.success) {
                    OmniLog.e(Tag, "official VLM request failed: ${officialResponse.message}")
                    val failureStep = UIStep(
                        observation = officialResponse.code,
                        thought = "官方VLM服务请求失败,忽略后面的action字段",
                        action = RecordAction(content = officialResponse.message),
                        result = "官方VLM服务请求失败"
                    )
                    return VLMOperationResult(
                        success = false,
                        error = officialResponse.message.ifBlank { "!200" },
                        step = failureStep,
                        context = _context
                    )
                }

                val rawContent = officialResponse.content.ifBlank { officialResponse.reasoning }
                OmniLog.d(Tag, "Raw official VLM response: ${rawContent.take(4000)}")
                val vlmResult = gelabZeroParser.parseResponse(rawContent)
                safePauseCheck("official_after_parse_$stabilityAttempt")

                if (!vlmResult.success || vlmResult.step == null) {
                    parseFailureCount++
                    val resolvedError = resolveVlmFailureMessage(vlmResult)
                    OmniLog.e(
                        Tag,
                        "Parse official VLM response failed (#$parseFailureCount): $resolvedError"
                    )
                    val finalThinkingText = buildThinkingOverlayText(vlmResult.thinking)
                    if (!isSubTask && finalThinkingText.isNotBlank()) {
                        emitReasoningOverlay(finalThinkingText)
                    }
                    val failureStep = UIStep(
                        observation = vlmResult.thinking?.observation?.ifBlank { "VLM响应解析失败" }
                            ?: "VLM响应解析失败",
                        thought = buildParseFailureThought(vlmResult),
                        action = RecordAction(content = "解析失败: $resolvedError"),
                        result = "解析失败，第${parseFailureCount}次失败"
                    )
                    return VLMOperationResult(
                        success = false,
                        error = resolvedError,
                        step = failureStep,
                        context = _context
                    )
                }

                val overlayText = buildThinkingOverlayText(vlmResult.thinking)
                    .ifBlank { vlmResult.step.thought }
                if (!isSubTask && overlayText.isNotBlank()) {
                    emitReasoningOverlay(overlayText)
                }

                var processedStep = normalizeOpenAppAction(vlmResult.step, _context, model)

                if (processedStep.action is FeedbackAction) {
                    val feedbackAction = processedStep.action as FeedbackAction
                    val feedbackStep = UIStep(
                        observation = processedStep.observation,
                        thought = processedStep.thought,
                        action = feedbackAction,
                        result = feedbackAction.value,
                        summary = processedStep.summary
                    )
                    return VLMOperationResult(
                        success = true,
                        step = feedbackStep,
                        context = _context,
                        error = null,
                        feedback = feedbackAction.value
                    )
                }

                when (processedStep.action) {
                    is ClickAction -> processedStep = updateActionWithCoordinates(
                        processedStep,
                        position = listOf(processedStep.action.x, processedStep.action.y)
                    )

                    is ScrollAction -> processedStep = updateActionWithCoordinates(
                        processedStep,
                        position = listOf(
                            processedStep.action.x1,
                            processedStep.action.y1,
                            processedStep.action.x2,
                            processedStep.action.y2
                        )
                    )

                    is LongPressAction -> processedStep = updateActionWithCoordinates(
                        processedStep,
                        position = listOf(processedStep.action.x, processedStep.action.y)
                    )

                    else -> {}
                }

                if (needsPreciseLocation(processedStep.action)) {
                    val afterXml = captureCurrentXml()
                    if (!isPageStableByXml(beforeXml, afterXml)) {
                        OmniLog.d(Tag, "页面不稳定，第${stabilityAttempt + 1}次重试")
                        safePauseCheck("official_before_retry_delay_$stabilityAttempt")
                        delay(500)
                        stabilityAttempt++
                        continue
                    }
                } else {
                    OmniLog.d(
                        Tag,
                        "Action ${processedStep.action.name} does not require precise location, skipping stability check"
                    )
                }

                safePauseCheck("official_before_action_${processedStep.action.name}_${stabilityAttempt}")
                ensureTaskActive("official_before_action_dispatch_${processedStep.action.name}_$stabilityAttempt")

                val executedStep = actionExecutor.act(
                    VLMStep(
                        observation = processedStep.observation,
                        thought = processedStep.thought,
                        action = processedStep.action,
                        summary = processedStep.summary
                    )
                )
                safePauseCheck("official_after_action_${processedStep.action.name}_${stabilityAttempt}")
                val finalStep = executedStep.copy(summary = processedStep.summary)

                OmniLog.d(
                    Tag,
                    "Execute official action: ${finalStep.action.name}, result=${finalStep.result ?: "OK"}"
                )

                if (finalStep.result?.contains("不支持的操作类型") == true) {
                    parseFailureCount++
                    return VLMOperationResult(
                        success = false,
                        error = "不支持的操作类型: ${finalStep.result}",
                        step = finalStep,
                        context = _context
                    )
                }

                parseFailureCount = 0
                return VLMOperationResult(
                    success = true,
                    step = finalStep,
                    context = _context,
                    error = null
                )
            } catch (e: Http429Exception) {
                val failureStep = UIStep(
                    observation = "429",
                    thought = "服务端请求失败,忽略后面的action字段",
                    action = RecordAction(content = "服务端请求失败"),
                    result = "服务端请求失败"
                )
                return VLMOperationResult(
                    success = false,
                    error = "!200",
                    step = failureStep,
                    context = _context
                )
            } catch (e: PrivacyBlockedException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                OmniLog.e(Tag, "官方VLM执行异常，第${stabilityAttempt + 1}次重试: ${e.message}")
                if (stabilityAttempt >= maxRetries - 1) {
                    return VLMOperationResult(
                        success = false,
                        error = "官方VLM操作执行异常: ${e.message}",
                        step = null,
                        context = _context
                    )
                }
                stabilityAttempt++
                delay(500)
            }
        }

        return VLMOperationResult(
            success = false,
            error = "页面稳定性检测失败，达到最大重试次数",
            step = null,
            context = _context
        )
    }

    private fun buildThinkingOverlayText(thinking: VLMThinkingContext?): String {
        if (thinking == null) return ""
        val primary = thinking.reasoning.trim()
        if (primary.isBlank()) return ""
        return normalizeOverlayText(primary, maxLen = 320)
    }

    private suspend fun emitReasoningOverlay(reasoning: String) {
        if (isSubTask) return
        val normalized = normalizeOverlayText(reasoning, maxLen = 320)
        if (normalized.isBlank()) return
        val now = System.currentTimeMillis()
        val deltaLength = normalized.length - lastReasoningOverlay.length
        val shouldEmit = when {
            normalized == lastReasoningOverlay -> false
            lastReasoningOverlay.isBlank() -> true
            deltaLength >= 18 -> true
            normalized.endsWith("\n") || normalized.endsWith("。") || normalized.endsWith(".") -> true
            now - lastReasoningOverlayAt >= 350L -> true
            else -> false
        }
        if (!shouldEmit) return
        lastReasoningOverlay = normalized
        lastReasoningOverlayAt = now
        deviceOperator.showInfo(normalized)
    }

    private fun buildParseFailureThought(vlmResult: VLMResult): String {
        return if (vlmResult.shouldRetryForToolCall) {
            val thinking = buildThinkingOverlayText(vlmResult.thinking)
            if (thinking.isNotBlank()) {
                "模型连续多次只返回思考内容，未给出原生 tool_calls。最后一次思考：$thinking"
            } else {
                "模型连续多次未给出原生 tool_calls，当前模型可能不支持标准工具调用。"
            }
        } else {
            "解析VLM返回的结构化响应时发生错误，可能是格式不正确或缺少必需字段,忽略后面的action字段"
        }
    }

    private fun resolveVlmFailureMessage(vlmResult: VLMResult): String {
        if (vlmResult.shouldRetryForToolCall) {
            val finishReasonSuffix = vlmResult.thinking?.finishReason
                ?.takeIf { it.isNotBlank() }
                ?.let { "（finish_reason=$it）" }
                .orEmpty()
            return "模型多次未返回标准 tool_calls，可能仍停留在思考阶段或不支持标准工具调用$finishReasonSuffix"
        }
        return vlmResult.error ?: "VLM推理失败"
    }

    private fun normalizeOverlayText(text: String, maxLen: Int): String {
        val normalized = text.replace("\r\n", "\n").trim()
        return if (normalized.length <= maxLen) normalized else "..." + normalized.takeLast(maxLen - 3)
    }

    private fun buildStreamFailureMessage(error: Exception): String {
        val message = error.message?.trim().orEmpty()
        if (message.isBlank()) {
            return "模型或服务商不支持标准流式工具调用"
        }
        return if (
            message.contains("stream", ignoreCase = true) ||
            message.contains("event-stream", ignoreCase = true) ||
            message.contains("sse", ignoreCase = true)
        ) {
            "模型或服务商不支持标准流式工具调用: $message"
        } else {
            message
        }
    }

    private fun needsPreciseLocation(action: UIAction): Boolean {
        return when (action) {
            is ClickAction, is ScrollAction, is LongPressAction -> true
            else -> false
        }
    }

    private fun updateActionWithCoordinates(step: VLMStep, position: List<Float>): VLMStep {
        val encodedWidth = deviceOperator.getLastScreenshotWidth().coerceAtLeast(1)
        val encodedHeight = deviceOperator.getLastScreenshotHeight().coerceAtLeast(1)
        val displayWidth = deviceOperator.getDisplayWidth().coerceAtLeast(encodedWidth)
        val displayHeight = deviceOperator.getDisplayHeight().coerceAtLeast(encodedHeight)
        val scaleX = if (encodedWidth > 0) displayWidth.toDouble() / encodedWidth else 1.0
        val scaleY = if (encodedHeight > 0) displayHeight.toDouble() / encodedHeight else 1.0

        fun coordType(value: Float, encodedSize: Int): String {
            return when {
                value <= 1f -> "ratio_0-1"
                value <= 1000f -> "norm_0-1000"
                value <= encodedSize -> "pixel_in_encoded"
                else -> "pixel_overflow"
            }
        }

        fun toScreenCoord(value: Float, encodedSize: Int, scale: Double, maxSize: Int): Int {
            val mapped = when {
                value <= 1f -> (value.toDouble() * encodedSize * scale).roundToInt()
                value <= 1000f -> (value / 1000.0 * encodedSize * scale).roundToInt()
                else -> (value * scale).roundToInt()
            }
            return mapped.coerceIn(0, maxSize)
        }

        val updatedAction = when (val action = step.action) {
            is ClickAction -> {
                val absoluteX = toScreenCoord(position[0], encodedWidth, scaleX, displayWidth)
                val absoluteY = toScreenCoord(position[1], encodedHeight, scaleY, displayHeight)
                OmniLog.d(
                    Tag,
                    "Coord mapping(click): raw=(${position[0]}, ${position[1]}) type=(${
                        coordType(
                            position[0],
                            encodedWidth
                        )
                    }, ${
                        coordType(
                            position[1],
                            encodedHeight
                        )
                    }), encoded=${encodedWidth}x${encodedHeight}, mapped=(${absoluteX}, ${absoluteY}), display=${displayWidth}x${displayHeight}"
                )
                action.copy(x = absoluteX.toFloat(), y = absoluteY.toFloat())
            }

            is ScrollAction -> {
                val rawX1 = position.getOrNull(0) ?: 0f
                val rawY1 = position.getOrNull(1) ?: 0f
                val rawX2 = position.getOrNull(2) ?: 0f
                val rawY2 = position.getOrNull(3) ?: 0f
                val absoluteX1 = toScreenCoord(rawX1, encodedWidth, scaleX, displayWidth)
                val absoluteY1 = toScreenCoord(rawY1, encodedHeight, scaleY, displayHeight)
                val absoluteX2 = toScreenCoord(rawX2, encodedWidth, scaleX, displayWidth)
                val absoluteY2 = toScreenCoord(rawY2, encodedHeight, scaleY, displayHeight)
                OmniLog.d(
                    Tag,
                    "Coord mapping(scroll): raw=($rawX1, $rawY1, $rawX2, $rawY2) type=(${
                        coordType(
                            rawX1,
                            encodedWidth
                        )
                    }, ${coordType(rawY1, encodedHeight)}, ${
                        coordType(
                            rawX2,
                            encodedWidth
                        )
                    }, ${
                        coordType(
                            rawY2,
                            encodedHeight
                        )
                    }), encoded=${encodedWidth}x${encodedHeight}, mapped=($absoluteX1, $absoluteY1, $absoluteX2, $absoluteY2), display=${displayWidth}x${displayHeight}"
                )
                action.copy(
                    x1 = absoluteX1.toFloat(),
                    y1 = absoluteY1.toFloat(),
                    x2 = absoluteX2.toFloat(),
                    y2 = absoluteY2.toFloat()
                )
            }

            is LongPressAction -> {
                val absoluteX = toScreenCoord(position[0], encodedWidth, scaleX, displayWidth)
                val absoluteY = toScreenCoord(position[1], encodedHeight, scaleY, displayHeight)
                OmniLog.d(
                    Tag,
                    "Coord mapping(long_press): raw=(${position[0]}, ${position[1]}) type=(${
                        coordType(
                            position[0],
                            encodedWidth
                        )
                    }, ${
                        coordType(
                            position[1],
                            encodedHeight
                        )
                    }), encoded=${encodedWidth}x${encodedHeight}, mapped=(${absoluteX}, ${absoluteY}), display=${displayWidth}x${displayHeight}"
                )
                action.copy(x = absoluteX.toFloat(), y = absoluteY.toFloat())
            }

            else -> action
        }

        return step.copy(action = updatedAction)
    }

    /**
     * 将 open_app 动作中的应用名/别名映射为真实包名
     */
    private fun normalizeOpenAppAction(step: VLMStep, context: UIContext, model: String): VLMStep {
        val action = step.action
        if (action !is OpenAppAction) return step

        val resolvedPackage = resolvePackageName(action.packageName, context.installedApplications)
        return if (resolvedPackage != null && resolvedPackage != action.packageName) {
            OmniLog.d(Tag, "Resolved open_app value '${action.packageName}' -> '$resolvedPackage'")
            step.copy(action = action.copy(packageName = resolvedPackage))
        } else {
            step
        }
    }

    private fun resolvePackageName(
        nameOrPkg: String,
        installedApps: Map<String, String>
    ): String? {
        val input = nameOrPkg.trim()
        if (input.isEmpty()) return null

        // 直接匹配包名
        installedApps.keys.firstOrNull { it.equals(input, ignoreCase = true) }?.let { return it }

        val lower = input.lowercase()
        val aliasMap = mapOf(
            "小红书" to "com.xingin.xhs",
            "xhs" to "com.xingin.xhs",
            "xiaohongshu" to "com.xingin.xhs"
        )
        aliasMap[lower]?.let { aliasPkg ->
            if (installedApps.containsKey(aliasPkg)) return aliasPkg
        }

        // 精确匹配应用名
        installedApps.entries.firstOrNull {
            it.value.equals(
                input,
                ignoreCase = true
            )
        }?.key?.let { return it }

        val normalizedInput = normalizeName(input)
        installedApps.entries.firstOrNull { normalizeName(it.value) == normalizedInput }?.key?.let { return it }

        // 包名后缀或包含关系
        installedApps.keys.firstOrNull {
            normalizeName(it).endsWith(normalizedInput) || normalizedInput.endsWith(normalizeName(it))
        }?.let { return it }

        installedApps.entries.firstOrNull {
            val appNorm = normalizeName(it.value)
            appNorm.contains(normalizedInput) || normalizedInput.contains(appNorm)
        }?.key?.let { return it }

        return null
    }

    private fun normalizeName(input: String): String {
        return input.lowercase().replace(Regex("[^a-z0-9\u4e00-\u9fa5]+"), "")
    }

    private fun suggestPackages(
        pkg: String,
        installedApps: Map<String, String>
    ): List<String> {
        if (pkg.isBlank()) return emptyList()
        val target = normalizeName(pkg)
        return installedApps.filter { (packageName, appName) ->
            val pkgNorm = normalizeName(packageName)
            val appNorm = normalizeName(appName)
            pkgNorm.contains(target) ||
                    target.contains(pkgNorm) ||
                    appNorm.contains(target) ||
                    target.contains(appNorm)
        }.keys.take(3)
    }

    /**
     * 获取当前屏幕的XML表示
     */
    private fun captureCurrentXml(): String? {
        return try {
            val service = AssistsService.instance
            val rootNode = service?.rootInActiveWindow ?: return null
            val xmlTree = XmlTreeUtils.buildXmlTree(rootNode) ?: return null
            XmlTreeUtils.serializeXml(xmlTree)
        } catch (e: Exception) {
            println("获取XML失败: ${e.message}")
            null
        }
    }

    private fun isPageStableByXml(beforeXml: String?, afterXml: String?): Boolean {
        return try {
            if (beforeXml == null || afterXml == null) {
                println("XML为空，默认认为不稳定")
                return false
            }

            val similarity = TreeEditDistance.getSimilarity(beforeXml, afterXml)
            val isStable = similarity >= 0.85

            println("页面稳定性检测: 相似度=$similarity, 稳定=$isStable")
            isStable
        } catch (e: Exception) {
            println("页面稳定性比较异常: ${e.message}")
            false
        }
    }
}

data class VLMOperationResult(
    val success: Boolean,
    val step: UIStep?,
    val context: UIContext,
    val error: String?,
    val feedback: String? = null
)

data class TaskExecutionReport(
    val success: Boolean,
    val goal: String,
    val totalSteps: Int,
    val executionTrace: List<UIStep>,
    val finalContext: UIContext,
    val error: String?,
    val feedback: String? = null
)
