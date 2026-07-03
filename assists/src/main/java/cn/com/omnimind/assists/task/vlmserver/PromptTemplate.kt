package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.assists.util.TimeUtil
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.baselib.llm.ModelSceneRegistry

/**
 * 主 VLM prompt 构造器：
 * - system: 稳定规则、工具协议、GUI 操作规范
 * - user: 当前轮动态上下文 + 当前截图
 */
object PromptTemplate {
    private fun currentLocale(): PromptLocale = AppLocaleManager.currentPromptLocale()

    private fun t(locale: PromptLocale, zh: String, en: String): String {
        return when (locale) {
            PromptLocale.ZH_CN -> zh
            PromptLocale.EN_US -> en
        }
    }

    fun getPrompt(context: UIContext, sceneId: String? = null): String {
        return buildTurnUserPrompt(context, sceneId)
    }

    fun buildSystemPrompt(sceneId: String? = null): String {
        val locale = currentLocale()
        val resolvedSceneId = if (sceneId.isNullOrBlank()) {
            "scene.vlm.operation.primary"
        } else {
            sceneId
        }
        val runtimeProfile = ModelSceneRegistry.getRuntimeProfile(resolvedSceneId)
        val parser = runtimeProfile?.responseParser ?: ModelSceneRegistry.ResponseParser.TEXT_CONTENT
        val template = ModelSceneRegistry.getPrompt(resolvedSceneId)
            ?: ModelSceneRegistry.getPrompt("scene.vlm.operation.primary")
            ?: throw IllegalStateException("scene.vlm.operation.primary prompt not found")

        val responseContract = if (parser == ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS) {
            VLMToolDefinitions.responseContract(locale)
        } else {
            ""
        }

        return ModelSceneRegistry.renderPrompt(
            template,
            mapOf(
                "priorityEvent" to t(locale, "若后续 user 消息包含紧急事件，请优先处理。", "If later user messages contain urgent events, prioritize them."),
                "overallTask" to t(locale, "见后续 user 消息", "See the following user message"),
                "currentStepGoal" to t(locale, "见后续 user 消息", "See the following user message"),
                "stepSkillGuidance" to t(locale, "见后续 user 消息", "See the following user message"),
                "summaryHistory" to t(locale, "见后续 user 消息", "See the following user message"),
                "currentState" to t(locale, "见后续 user 消息", "See the following user message"),
                "nextStepHint" to t(locale, "见后续 user 消息", "See the following user message"),
                "completedMilestones" to t(locale, "见后续 user 消息", "See the following user message"),
                "keyMemory" to t(locale, "见后续 user 消息", "See the following user message"),
                "installedApps" to t(locale, "见后续 user 消息", "See the following user message"),
                "currentTime" to t(locale, "见后续 user 消息", "See the following user message"),
                "responseContract" to responseContract
            )
        )
    }

    fun buildTurnUserPrompt(context: UIContext, sceneId: String? = null): String {
        val locale = currentLocale()
        val resolvedSceneId = if (sceneId.isNullOrBlank()) {
            "scene.vlm.operation.primary"
        } else {
            sceneId
        }
        val summaryHistory = if (context.runningSummary.isNotEmpty()) {
            context.runningSummary
        } else if (context.trace.isNotEmpty()) {
            context.trace.last().summary
        } else {
            t(locale, "暂无历史操作", "No prior execution history yet")
        }
        val installedApps = if (context.installedApplications.isNotEmpty()) {
            context.installedApplications.values.joinToString(", ")
        } else {
            t(locale, "暂无数据", "No data")
        }
        val completedMilestones = if (context.completedMilestones.isNotEmpty()) {
            context.completedMilestones.joinToString(
                separator = if (locale == PromptLocale.ZH_CN) "、" else ", "
            )
        } else {
            t(locale, "暂无", "None yet")
        }
        val keyMemory = if (context.keyMemory.isNotEmpty()) {
            context.keyMemory.joinToString(
                separator = if (locale == PromptLocale.ZH_CN) "；" else "; "
            )
        } else {
            t(locale, "暂无", "None yet")
        }
        val priorityEventSection = if (context.priorityEvent != null) {
            buildString {
                appendLine(t(locale, "【紧急事件】", "[Urgent Event]"))
                appendLine(context.priorityEvent)
                if (context.suggestCompletion) {
                    appendLine(
                        t(
                            locale,
                            "如果已经确认任务完成，请尽快调用 finished 工具结束任务。",
                            "If the task has already been confirmed complete, call the finished tool as soon as possible."
                        )
                    )
                }
                appendLine()
            }.trim()
        } else {
            ""
        }

        return buildString {
            appendLine(
                t(
                    locale,
                    "以下是当前这一轮的动态上下文，请结合当前截图选择下一步动作。",
                    "Below is the dynamic context for the current turn. Use it together with the current screenshot to choose the next action."
                )
            )
            appendLine("${t(locale, "场景", "Scene")}: $resolvedSceneId")
            appendLine("${t(locale, "当前时间", "Current time")}: ${TimeUtil.getCurrentTimeString()}")
            appendLine("${t(locale, "用户任务", "User task")}: ${context.overallTask}")
            appendLine("${t(locale, "当前子目标", "Current sub-goal")}: ${context.activeGoal()}")
            appendLine(
                "${t(locale, "技能提示", "Skill guidance")}: ${context.stepSkillGuidance.ifEmpty { t(locale, "无", "None") }}"
            )
            if (priorityEventSection.isNotBlank()) {
                appendLine(priorityEventSection)
            }
            appendLine("${t(locale, "当前状态", "Current state")}: ${context.currentState.ifEmpty { t(locale, "未知", "Unknown") }}")
            appendLine("${t(locale, "建议下一步", "Suggested next step")}: ${context.nextStepHint.ifEmpty { t(locale, "无", "None") }}")
            appendLine("${t(locale, "已完成里程碑", "Completed milestones")}: $completedMilestones")
            appendLine("${t(locale, "关键记忆", "Key memory")}: $keyMemory")
            appendLine("${t(locale, "历史总结", "History summary")}: $summaryHistory")
            appendLine("${t(locale, "已安装应用", "Installed apps")}: $installedApps")
            appendLine()
            appendLine("${t(locale, "输出要求", "Output requirements")}:")
            appendLine(
                t(
                    locale,
                    "1. 直接从 tools 列表中选择下一步动作，每轮只调用一个工具。",
                    "1. Pick the next action directly from the tools list, and call exactly one tool per turn."
                )
            )
            appendLine(
                t(
                    locale,
                    "2. click/long_press 只填 x、y；scroll 只填 x1、y1、x2、y2；每个坐标字段都必须是单个数值。",
                    "2. For click and long_press, only fill x and y. For scroll, only fill x1, y1, x2, and y2. Every coordinate field must be a single numeric scalar."
                )
            )
            appendLine(
                t(
                    locale,
                    "3. assistant.content 只写 observation/thought/summary 元信息；只有真正完成任务时才调用 finished。",
                    "3. assistant.content may only contain observation / thought / summary metadata. Call finished only when the task is truly complete."
                )
            )
        }.trim()
    }

    fun buildGelabZeroPrompt(context: UIContext): String {
        val compressedState = context.compressedState.ifBlank { context.runningSummary }
        val summaryHistory = if (compressedState.isNotBlank()) {
            compressedState
        } else if (context.trace.isNotEmpty()) {
            context.trace.last().summary.ifBlank { "暂无历史操作" }
        } else {
            "暂无历史操作"
        }
        val rawHistory = buildGelabRawHistory(context)
        val installedApps = if (context.installedApplications.isNotEmpty()) {
            context.installedApplications.values.joinToString(", ")
        } else {
            "暂无数据"
        }
        val activeGoal = context.activeGoal()
        val extraContext = buildString {
            if (activeGoal != context.overallTask) {
                appendLine("当前子目标(currentStepGoal)为：$activeGoal")
            }
            if (context.stepSkillGuidance.isNotBlank()) {
                appendLine("当前技能提示(stepSkillGuidance)为：${context.stepSkillGuidance}")
            }
            if (context.priorityEvent != null) {
                appendLine("紧急事件(priorityEvent)为：${context.priorityEvent}")
                if (context.suggestCompletion) {
                    appendLine("若已经确认任务完成，请尽快使用 COMPLETE 结束任务。")
                }
            }
            if (context.currentState.isNotBlank()) {
                appendLine("当前状态(currentState)为：${context.currentState}")
            }
            if (context.nextStepHint.isNotBlank()) {
                appendLine("建议下一步(nextStepHint)为：${context.nextStepHint}")
            }
            if (context.completedMilestones.isNotEmpty()) {
                appendLine("已完成里程碑(completedMilestones)为：${context.completedMilestones.joinToString("、")}")
            }
            if (context.keyMemory.isNotEmpty()) {
                appendLine("关键记忆(keyMemory)为：${context.keyMemory.joinToString("；")}")
            }
        }.trim()

        return ModelSceneRegistry.renderPrompt(
            GELAB_ZERO_OPERATION_PROMPT,
            mapOf(
                "overallTask" to context.overallTask,
                "summaryHistory" to summaryHistory,
                "rawHistory" to rawHistory,
                "compressedUptoStep" to context.compressedUptoStep.toString(),
                "installedApps" to installedApps,
                "currentTime" to TimeUtil.getCurrentTimeString(),
                "extraContext" to extraContext
            )
        )
    }

    private fun buildGelabRawHistory(context: UIContext): String {
        if (context.trace.isEmpty()) return "暂无未压缩原始历史"
        return context.trace.mapIndexed { index, step ->
            val stepNumber = context.compressedUptoStep + index + 1
            val actionText = when (val action = step.action) {
                is ClickAction -> "CLICK point:${action.x},${action.y}"
                is TypeAction -> "TYPE value:${action.content}"
                is ScrollAction -> "SLIDE point1:${action.x1},${action.y1} point2:${action.x2},${action.y2}"
                is LongPressAction -> "LONGPRESS point:${action.x},${action.y}"
                is OpenAppAction -> "AWAKE value:${action.packageName}"
                is PressBackAction -> "BACK"
                is PressHomeAction -> "HOME"
                is WaitAction -> "WAIT value:${((action.durationMs ?: action.duration ?: 1000L) / 1000.0)}"
                is InfoAction -> "INFO value:${action.value}"
                is AbortAction -> "ABORT value:${action.value}"
                is FinishedAction -> "COMPLETE return:${action.content}"
                is RecordAction -> "RECORD value:${action.content}"
                is HotKeyAction -> "HOT_KEY key:${action.key}"
                else -> action.name
            }
            """
            [STEP $stepNumber]
            observation: ${step.observation.ifBlank { "none" }}
            explain: ${step.thought.ifBlank { "none" }}
            action: $actionText
            result: ${step.result.orEmpty().ifBlank { "none" }}
            key_process: ${step.summary.ifBlank { "none" }}
            [/STEP $stepNumber]
            """.trimIndent()
        }.joinToString("\n\n")
    }

    fun buildToolCallRetryPrompt(context: UIContext, retryState: VLMToolCallRetryState): String {
        val locale = currentLocale()
        val thinking = retryState.thinking
        return buildString {
            val failureReason = retryState.failureReason?.trim().orEmpty()
            if (failureReason.isNotEmpty()) {
                appendLine(
                    t(
                        locale,
                        "系统检查到你上一轮的 tool_call 参数不合规：$failureReason",
                        "The system detected that the tool_call arguments from your previous turn were invalid: $failureReason"
                    )
                )
            } else {
                appendLine(
                    t(
                        locale,
                        "系统检查到你上一轮没有返回标准 tool_calls，但当前任务仍是执行型 GUI 自动化。",
                        "The system detected that your previous turn did not return standard tool_calls, but the current task is still an execution-oriented GUI automation task."
                    )
                )
            }
            appendLine(
                t(
                    locale,
                    "请在本轮严格返回一个原生 tool_call，并从 tools 列表中选择下一步动作。",
                    "In this turn, return exactly one native tool_call and choose the next action from the tools list."
                )
            )
            appendLine(
                t(
                    locale,
                    "不要只输出 observation/thought/summary JSON，不要在 assistant.content 中写动作参数，也不要提前宣布任务完成。",
                    "Do not output only observation/thought/summary JSON, do not put action arguments in assistant.content, and do not announce completion prematurely."
                )
            )
            appendLine(
                t(
                    locale,
                    "只有当用户目标已经真正完成时，才能调用 finished。",
                    "Call finished only when the user's goal is truly complete."
                )
            )
            appendLine(
                t(
                    locale,
                    "若你判断下一步是点击、输入、滑动、返回、等待或结束，请直接使用对应工具。",
                    "If the next step should be tap, type, scroll, go back, wait, or finish, call the matching tool directly."
                )
            )
            appendLine(
                t(
                    locale,
                    "若需要坐标，必须分别写入 x/y 或 x1/y1/x2/y2；每个字段都只能是单个数值，不要返回 [x,y]、coordinates 或对象。",
                    "If coordinates are needed, write them separately into x/y or x1/y1/x2/y2. Each field must be a single numeric scalar; do not return [x,y], coordinates, or objects."
                )
            )
            appendLine(
                t(
                    locale,
                    "本次为第 ${retryState.retryIndex} 次协议纠偏。",
                    "This is protocol correction attempt #${retryState.retryIndex}."
                )
            )
            appendLine("${t(locale, "用户原始任务", "Original user task")}: ${context.overallTask}")
            appendLine("${t(locale, "当前子目标", "Current sub-goal")}: ${context.activeGoal()}")
            thinking.finishReason?.takeIf { it.isNotBlank() }?.let {
                appendLine("${t(locale, "上一轮 finish_reason", "Previous finish_reason")}: $it")
            }
            thinking.observation.takeIf { it.isNotBlank() }?.let {
                appendLine("${t(locale, "上一轮 observation", "Previous observation")}: ${truncateForRetry(it)}")
            }
            thinking.thought.takeIf { it.isNotBlank() }?.let {
                appendLine("${t(locale, "上一轮 thought", "Previous thought")}: ${truncateForRetry(it)}")
            }
            thinking.summary.takeIf { it.isNotBlank() }?.let {
                appendLine("${t(locale, "上一轮 summary", "Previous summary")}: ${truncateForRetry(it)}")
            }
            thinking.reasoning.takeIf { it.isNotBlank() }?.let {
                appendLine("${t(locale, "上一轮 reasoning_content", "Previous reasoning_content")}: ${truncateForRetry(it, maxLen = 900)}")
            }
        }.trim()
    }

    private fun truncateForRetry(text: String, maxLen: Int = 280): String {
        val normalized = text.replace("\r\n", "\n").trim()
        return if (normalized.length <= maxLen) normalized else normalized.take(maxLen) + "..."
    }

    private const val GELAB_ZERO_OPERATION_PROMPT = """
	# Role: 手机 GUI-Agent 操作专家
	Version: GELab-Zero Kotlin Port

	你需要根据用户下发的任务、当前手机屏幕截图和交互操作的历史记录，借助既定动作空间与手机交互，从而完成用户任务。
	坐标系以左上角为原点，x 轴向右，y 轴向下，取值范围均为 0-1000。
	注意：为了避免遮挡内容，系统已强制隐藏软键盘。执行输入操作前只要有尝试 CLICK 激活输入框的动作，就可以用 TYPE，请忽略未见软键盘的情况。

	## 动作空间
	在 Android 手机的场景下，你的动作空间包含以下操作，所有输出都必须遵守对应参数要求：
	1. CLICK：点击手机屏幕坐标，需包含点击的坐标位置 point。
	例如：action:CLICK	point:x,y
	2. TYPE：在手机输入框中输入文字，需包含输入内容 value。
	例如：action:TYPE	value:输入内容
	3. COMPLETE：任务完成后向用户报告结果，需包含报告的内容 return。
	例如：action:COMPLETE	return:完成任务后向用户报告的内容
	4. WAIT：等待指定时长，需包含等待时间 value（秒）。
	例如：action:WAIT	value:等待时间
5. AWAKE：唤醒指定应用，需包含唤醒的应用名称 value。
例如：action:AWAKE	value:应用名称
6. INFO：询问用户问题或详细信息，需包含提问内容 value。
例如：action:INFO	value:提问内容
7. ABORT：终止当前任务，仅在当前任务无法继续执行时使用，需包含 value 说明原因。
例如：action:ABORT	value:终止任务的原因
	8. SLIDE：在手机屏幕上滑动，滑动的方向不限，需包含起点 point1 和终点 point2。
	例如：action:SLIDE	point1:x1,y1	point2:x2,y2
	9. LONGPRESS：长按手机屏幕坐标，需包含长按的坐标位置 point。
	例如：action:LONGPRESS	point:x,y
	10. HOT_KEY：按系统按键，key 只能是 BACK、HOME、ENTER。
	例如：action:HOT_KEY	key:BACK
	11. CALL_USER：请求用户接管或补充信息，需包含 value。
	例如：action:CALL_USER	value:需要用户接管登录

	【当前时间（24 小时制）】
	{{currentTime}}

	额外约束：
		- 无视悬浮小猫的状态指示
		- 无视任务正在进行中的悬浮提示
		重要信息:
		- 如果当前截图与历史冲突，始终以当前截图为准。
		- 如果没有截图、截图空白或关键敏感操作需要用户接管，使用 CALL_USER。

	已知用户任务(overallTask)为：{{overallTask}}
	{{extraContext}}
	以下是对更早历史状态的一轮压缩结果。它保留了更早步骤中的关键用户输入、页面信息、关键进展和风险点：
	{{summaryHistory}}

	下面保留的是压缩之后仍需逐步回顾的原始步骤，起始于原始第 {{compressedUptoStep}} 步之后：
	{{rawHistory}}

	已知手机已安装的应用列表(installedApps)如下：{{installedApps}}

	当前手机屏幕截图如下：

	## 输出要求
	在执行操作之前，请务必仔细观察当前截图，先进行思考，然后输出动作空间和对应参数。
	输出必须采用 CSV 风格，以 tab 分割字段，绝对不要使用 JSON。
	输出必须以 verify 字段开头，并包含 verify、note、explain、action、key_process。
	explain 字段必须简短，10 个汉字以内。
	note 字段用于记录当前页面中和用户任务有关的关键信息，信息提取类任务必须尽量完整抄写相关文字。

	严格输出格式示例：
	<THINK> 思考的内容 </THINK>
	verify:证据表明上个动作是否生效，因此我判断 符合 上一步预期	note:当前页面总结出的关键信息	explain:十个汉字以内	action:动作空间和对应的参数	key_process:当前关键进展
	"""
	}
