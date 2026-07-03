package cn.com.omnimind.bot.mcp

import cn.com.omnimind.baselib.i18n.AppLocaleManager

/**
 * MCP 工具定义
 */
object McpToolDefinitions {
    private fun brandName(): String = AppLocaleManager.brandName()
    
    val vlmTaskTool = mapOf(
        "name" to "vlm_task",
        "description" to """Execute an autonomous VLM (Visual Language Model) agent task on an Android device.

This tool enables AI-driven device automation by using a visual language model to understand screen content and perform actions. The agent will:
1. Analyze the current screen state using screenshots
2. Reason about the next best action to achieve the goal
3. Execute UI actions (tap, scroll, input text, etc.)
4. Iterate until the goal is achieved or intervention is needed

Use cases:
- Automate repetitive mobile tasks (ordering food, sending messages, etc.)
- Navigate complex app workflows autonomously
- Extract information from mobile applications
- Perform multi-step operations across different apps

BEHAVIOR:
- This tool BLOCKS and waits for the task to complete or require input (up to 2 minutes)
- If the agent needs clarification, the response will include the agent's question
- When you receive a WAITING_INPUT response, use 'task_reply' to answer the agent
- After replying, the tool will again wait for completion or next interaction
- Provide clear, specific goals for best results

WORKFLOW:
1. Call vlm_task with your goal
2. If response shows WAITING_INPUT with a question, call task_reply with your answer
3. Repeat step 2 if the agent asks more questions
4. Task completes when you receive a FINISHED status
""".trimIndent(),
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "goal" to mapOf(
                    "type" to "string",
                    "description" to "The task goal in natural language. Be specific and clear. Example: 'Open WeChat and send a message saying Hello to contact John'"
                ),
                "model" to mapOf(
                    "type" to "string",
                    "description" to "Optional: AI model identifier to use for vision reasoning. Leave empty for default."
                ),
                "packageName" to mapOf(
                    "type" to "string",
                    "description" to "Optional: Target app package name (e.g., 'com.tencent.mm' for WeChat). If not specified, the agent will start from the current screen."
                )
            ),
            "required" to listOf("goal")
        )
    )

    val taskStatusTool = mapOf(
        "name" to "task_status",
        "description" to """Query the current status of a VLM task (for long-running tasks that timed out).

This is a backup tool - normally vlm_task and task_reply will wait and return the final status.
Only use this if a previous call timed out but the task is still running.

Returns the task state including:
- status: RUNNING, WAITING_INPUT, USER_PAUSED, FINISHED, ERROR, CANCELLED
- message: Status message or error description
- waitingQuestion: When status is WAITING_INPUT, contains the question the agent is asking
- chatMessages: Recent agent reasoning/action messages
""".trimIndent(),
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "taskId" to mapOf(
                    "type" to "string",
                    "description" to "The task ID returned from vlm_task execution."
                )
            ),
            "required" to listOf("taskId")
        )
    )

    val taskReplyTool = mapOf(
        "name" to "task_reply",
        "description" to """Provide user input to a VLM task that is waiting for input.

WHEN TO USE:
When vlm_task returns with status WAITING_INPUT, the agent is asking a question.
Use this tool to answer the question and the task will continue.

BEHAVIOR:
- This tool BLOCKS and waits for the task to complete or require more input (up to 2 minutes)
- After providing your reply, the agent will resume and this tool returns the next status
- If the agent asks another question, you'll receive another WAITING_INPUT response
- Continue the conversation until the task completes (FINISHED status)

Common scenarios:
- Agent asks for verification code: reply with the code
- Agent asks which song to play: reply with the song name
- Agent asks for confirmation: reply '确认' or specific instructions
- Agent needs manual intervention: reply '已完成操作，继续执行' after completing the action
""".trimIndent(),
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "taskId" to mapOf(
                    "type" to "string",
                    "description" to "The task ID of the waiting task."
                ),
                "reply" to mapOf(
                    "type" to "string",
                    "description" to "The user's reply or input to provide to the agent."
                )
            ),
            "required" to listOf("taskId", "reply")
        )
    )

    val taskWaitUnlockTool = mapOf(
        "name" to "task_wait_unlock",
        "description" to """Wait for the device screen to be unlocked and resume/start a paused VLM task.

WHEN TO USE:
When you receive a SCREEN_LOCKED status, ask the user to unlock their phone,
then call this tool to wait for unlock and automatically resume the task.

BEHAVIOR:
- This tool BLOCKS and waits for the screen to be unlocked (up to 2 minutes)
- Once unlocked, if this is a new task it will start execution
- If this is a paused task, it will resume from where it left off
- Returns the next task status (FINISHED, WAITING_INPUT, etc.)
""".trimIndent(),
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "taskId" to mapOf(
                    "type" to "string",
                    "description" to "The task ID of the screen-locked task."
                )
            ),
            "required" to listOf("taskId")
        )
    )

    val fileTransferTool
        get() = mapOf(
        "name" to "file_transfer",
        "description" to """Retrieve files shared to the ${brandName()} app on the Android device.

WORKFLOW:
1. Use vlm_task to navigate to the file and choose "Open with" or "Share" -> 小万.
2. Call this tool to fetch file metadata and a short-lived download URL.
3. Download the file from the returned URL (valid for about 15 minutes).

ACTIONS:
- latest (default): return the most recently received file
- wait: block until a new file arrives (timeoutMs, default 120000)
- list: list recent received files
- get: fetch a file by fileId
- clear: delete one file (fileId) or all files

NOTES:
- Files are stored temporarily on the device (about 2 hours).
- Download URLs are only reachable on the same LAN.
""".trimIndent(),
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "action" to mapOf(
                    "type" to "string",
                    "description" to "latest | wait | list | get | clear. Default: latest."
                ),
                "fileId" to mapOf(
                    "type" to "string",
                    "description" to "Target file ID (required for action=get; optional for action=clear)."
                ),
                "afterFileId" to mapOf(
                    "type" to "string",
                    "description" to "For action=wait, only return a file newer than this ID."
                ),
                "timeoutMs" to mapOf(
                    "type" to "integer",
                    "description" to "For action=wait, max wait time in milliseconds (default 120000)."
                ),
                "limit" to mapOf(
                    "type" to "integer",
                    "description" to "For action=list, max number of items to return."
                )
            )
        )
    )

    val allTools
        get() = listOf(vlmTaskTool, taskStatusTool, taskReplyTool, taskWaitUnlockTool, fileTransferTool)
}
