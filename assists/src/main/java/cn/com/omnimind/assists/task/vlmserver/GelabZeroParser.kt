package cn.com.omnimind.assists.task.vlmserver

/**
 * Parser for the GelabZero text protocol used by the official operation VLM.
 *
 * Expected shape:
 * <THINK>...</THINK>
 * explain:...\taction:CLICK\tpoint:x,y\tsummary:...
 */
class GelabZeroParser {
    fun parseResponse(response: String): VLMResult {
        return try {
            val cleanedResponse = response.trim()
                .replace("<TINK>", "<THINK>")
                .replace("</TINK>", "</THINK>")
                .replace("<think>", "<THINK>")
                .replace("</think>", "</THINK>")
                .replace("\r", "")

            val cot = extractBetween(cleanedResponse, "<THINK>", "</THINK>")
            val kvPart = if ("</THINK>" in cleanedResponse) {
                cleanedResponse.substringAfter("</THINK>").trim()
            } else {
                cleanedResponse
            }

            val kvMap = parseKeyValues(kvPart)
            val action = parseAction(kvMap)
            val thought = kvMap["explain"].orEmpty()
            val summary = kvMap["summary"].orEmpty()

            VLMResult(
                success = true,
                step = VLMStep(
                    observation = cot,
                    thought = thought,
                    action = action,
                    summary = summary
                ),
                error = null,
                thinking = VLMThinkingContext(
                    observation = cot,
                    thought = thought,
                    summary = summary,
                    reasoning = cot,
                    rawContent = cleanedResponse
                )
            )
        } catch (e: Exception) {
            VLMResult(
                success = false,
                step = null,
                error = "Failed to parse response: ${e.message}",
                thinking = VLMThinkingContext(rawContent = response)
            )
        }
    }

    private fun extractBetween(text: String, start: String, end: String): String {
        if (start !in text || end !in text) return ""
        return text.substringAfter(start).substringBefore(end).trim()
    }

    private fun parseKeyValues(kvPart: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val tokens = kvPart.split(Regex("[\\t\\n]+")).filter { it.isNotBlank() }
        for (token in tokens) {
            if (":" !in token) continue
            val parts = token.split(":", limit = 2)
            if (parts.size == 2) {
                result[parts[0].trim().lowercase()] = parts[1].trim()
            }
        }
        return result
    }

    private fun parseAction(kvMap: Map<String, String>): UIAction {
        val actionType = kvMap["action"]
            ?: throw IllegalArgumentException("Missing action field")

        return when (actionType.uppercase()) {
            "CLICK" -> {
                val point = parsePoint(kvMap["point"] ?: throw IllegalArgumentException("Missing point for CLICK"))
                ClickAction(
                    targetDescription = kvMap["explain"].orEmpty(),
                    x = point.first,
                    y = point.second
                )
            }
            "TYPE" -> TypeAction(
                content = kvMap["value"] ?: throw IllegalArgumentException("Missing value for TYPE")
            )
            "SLIDE" -> {
                val point1 = parsePoint(kvMap["point1"] ?: throw IllegalArgumentException("Missing point1 for SLIDE"))
                val point2 = parsePoint(kvMap["point2"] ?: throw IllegalArgumentException("Missing point2 for SLIDE"))
                ScrollAction(
                    targetDescription = kvMap["explain"].orEmpty(),
                    x1 = point1.first,
                    y1 = point1.second,
                    x2 = point2.first,
                    y2 = point2.second,
                    duration = kvMap["duration"]?.toFloatOrNull() ?: 1.5f
                )
            }
            "LONGPRESS" -> {
                val point = parsePoint(kvMap["point"] ?: throw IllegalArgumentException("Missing point for LONGPRESS"))
                LongPressAction(
                    targetDescription = kvMap["explain"].orEmpty(),
                    x = point.first,
                    y = point.second
                )
            }
            "COMPLETE" -> FinishedAction(
                content = kvMap["return"] ?: kvMap["value"].orEmpty()
            )
            "WAIT" -> WaitAction(
                durationMs = ((kvMap["value"]?.toDoubleOrNull() ?: 1.0) * 1000).toLong()
            )
            "AWAKE" -> OpenAppAction(
                packageName = kvMap["value"] ?: throw IllegalArgumentException("Missing value for AWAKE")
            )
            "INFO" -> InfoAction(
                value = kvMap["value"] ?: throw IllegalArgumentException("Missing value for INFO")
            )
            "ABORT" -> AbortAction(
                value = kvMap["value"].orEmpty()
            )
            "HOT_KEY" -> {
                val key = kvMap["key"] ?: throw IllegalArgumentException("Missing key for HOT_KEY")
                when (key.uppercase()) {
                    "BACK" -> PressBackAction()
                    "HOME" -> PressHomeAction()
                    "ENTER" -> HotKeyAction(key = "ENTER")
                    else -> throw IllegalArgumentException("Unsupported hot_key: $key")
                }
            }
            else -> throw IllegalArgumentException("Unsupported action type: $actionType")
        }
    }

    private fun parsePoint(pointStr: String): Pair<Float, Float> {
        val coords = pointStr.replace(",", " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (coords.size < 2) {
            throw IllegalArgumentException("Invalid point format: $pointStr")
        }
        return coords[0].toFloat() to coords[1].toFloat()
    }
}
