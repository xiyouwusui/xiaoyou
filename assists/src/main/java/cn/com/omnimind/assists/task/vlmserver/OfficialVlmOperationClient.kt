package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionResponse
import cn.com.omnimind.baselib.llm.OfficialVlmOperationConfig
import cn.com.omnimind.baselib.llm.contentText
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class OfficialVlmOperationClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }
) {
    private val tag = "OfficialVlmOperationClient"

    suspend fun complete(
        config: OfficialVlmOperationConfig,
        prompt: String,
        screenshot: String
    ): OfficialVlmOperationResponse = withContext(Dispatchers.IO) {
        val requestPayload = ChatCompletionRequest(
            model = config.model,
            messages = listOf(
                ChatCompletionMessage(
                    role = "user",
                    content = buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive(prompt))
                            }
                        )
                        add(buildImageContent(screenshot))
                    }
                )
            ),
            maxTokens = 2048,
            temperature = 0.5,
            topP = 1.0,
            stream = false
        )
        val requestJson = json.encodeToString(requestPayload)
        val url = buildChatCompletionsUrl(config.apiBase)
        val request = Request.Builder()
            .url(url)
            .post(requestJson.toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .build()

        OmniLog.i(tag, "official VLM request dispatching promptLen=${prompt.length} screenshotLen=${screenshot.length}")

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            OmniLog.d(tag, "official VLM response status=${response.code} bodyLen=${body.length}")
            if (!response.isSuccessful) {
                return@withContext OfficialVlmOperationResponse(
                    success = false,
                    content = "",
                    reasoning = "",
                    code = response.code.toString(),
                    message = extractErrorMessage(body).ifBlank { response.message },
                    rawBody = body
                )
            }
            val parsed = runCatching {
                json.decodeFromString<ChatCompletionResponse>(body)
            }.getOrElse { error ->
                return@withContext OfficialVlmOperationResponse(
                    success = false,
                    content = "",
                    reasoning = "",
                    code = "500",
                    message = "Parse error: ${error.message}",
                    rawBody = body
                )
            }
            val firstChoice = parsed.choices.firstOrNull()
            val message = firstChoice?.message
            val content = message?.let {
                ChatCompletionMessage(role = it.role, content = it.content).contentText()
            }.orEmpty()
            val reasoning = message?.reasoningContent ?: message?.reasoning ?: ""
            OmniLog.d(
                tag,
                "official VLM response parsed contentPreview=${content.take(800)} reasoningPreview=${reasoning.take(800)}"
            )
            OfficialVlmOperationResponse(
                success = true,
                content = content,
                reasoning = reasoning,
                code = "200",
                message = "success",
                rawBody = body
            )
        }
    }

    private fun buildChatCompletionsUrl(apiBase: String): String {
        val base = apiBase.trim().trimEnd('/')
        return when {
            base.endsWith("/chat/completions", ignoreCase = true) -> base
            base.endsWith("/v1", ignoreCase = true) -> "$base/chat/completions"
            else -> "$base/v1/chat/completions"
        }
    }

    private fun buildImageContent(rawImage: String) = buildJsonObject {
        val imageUrl = if (
            rawImage.startsWith("http://", ignoreCase = true) ||
            rawImage.startsWith("https://", ignoreCase = true) ||
            rawImage.startsWith("data:", ignoreCase = true)
        ) {
            rawImage
        } else {
            "data:image/png;base64,$rawImage"
        }
        put("type", JsonPrimitive("image_url"))
        put(
            "image_url",
            buildJsonObject {
                put("url", JsonPrimitive(imageUrl))
            }
        )
    }

    private fun extractErrorMessage(body: String): String {
        if (body.isBlank()) return ""
        return runCatching {
            val element = json.parseToJsonElement(body)
            val obj = element as? kotlinx.serialization.json.JsonObject
            val errorObj = obj?.get("error") as? kotlinx.serialization.json.JsonObject
            val message = errorObj?.get("message")?.toString()?.trim('"')
            message.orEmpty()
        }.getOrDefault("")
    }
}

data class OfficialVlmOperationResponse(
    val success: Boolean,
    val content: String,
    val reasoning: String,
    val code: String,
    val message: String,
    val rawBody: String
)
