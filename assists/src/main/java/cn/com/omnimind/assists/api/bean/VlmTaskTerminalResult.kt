package cn.com.omnimind.assists.api.bean

enum class VlmTaskTerminalStatus {
    WAITING_INPUT,
    FINISHED,
    ERROR,
    CANCELLED
}

data class VlmTaskTerminalResult(
    val status: VlmTaskTerminalStatus,
    val message: String = "",
    val finishedContent: String? = null,
    val errorMessage: String? = null,
    val waitingQuestion: String? = null,
    val feedback: String? = null
)
