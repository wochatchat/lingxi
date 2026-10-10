package com.lingxi.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import com.lingxi.data.functions.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 一张随消息上传的图片（F10 多模态，base64） */
@Serializable
data class ChatImage(val mime: String, val base64: String)

/** 对话消息（OpenAI-compatible role/content；images 非空时 content 用多模态数组） */
@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    val images: List<ChatImage> = emptyList(),
)

/** 流式事件：增量文本 / 工具调用 / 正常结束 / 失败（带可定位信息） */
sealed interface LlmEvent {
    data class Delta(val text: String) : LlmEvent
    data class ToolCall(val name: String, val argsJson: String) : LlmEvent
    data class Completed(val finishReason: String?) : LlmEvent
    data class Failed(val message: String, val httpCode: Int? = null) : LlmEvent
}

/** 一条 tool_call 流式增量（按 index 分片到达，由客户端聚合） */
data class ToolCallDelta(
    val index: Int,
    val name: String?,
    val argsFragment: String?,
)

/** 一行 SSE 解析结果（纯函数可测） */
data class SseChunk(
    val deltaText: String?,
    val finishReason: String?,
    val done: Boolean,
    val toolCallDelta: ToolCallDelta? = null,
)

/**
 * 解析一行 OpenAI-compatible SSE。
 * 返回 null 表示该行可忽略（空行/注释/非 data 行/不可解析的 data）。
 */
fun parseSseLine(line: String): SseChunk? {
    val trimmed = line.trim()
    if (!trimmed.startsWith("data:")) return null
    val payload = trimmed.removePrefix("data:").trim()
    if (payload.isEmpty()) return null
    if (payload == "[DONE]") return SseChunk(null, null, done = true)
    return runCatching {
        val obj = Json.parseToJsonElement(payload).jsonObject
        val choices = obj["choices"] as? JsonArray ?: return@runCatching SseChunk(null, null, done = false)
        val first = choices.firstOrNull() as? JsonObject
        val delta = first?.get("delta") as? JsonObject
        val deltaText = delta?.get("content")?.jsonPrimitive?.contentOrNull
        val finish = (first?.get("finish_reason") as? JsonPrimitive)?.contentOrNull
        // tool_calls 分片：delta.tool_calls = [{index, function:{name?, arguments?}}]
        val tcArray = delta?.get("tool_calls") as? JsonArray
        val firstTc = tcArray?.firstOrNull() as? JsonObject
        val toolDelta = firstTc?.let { tc ->
                val idx = (tc["index"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
                val fn = tc["function"] as? JsonObject
                ToolCallDelta(
                    index = idx,
                    name = fn?.get("name")?.jsonPrimitive?.contentOrNull,
                    argsFragment = fn?.get("arguments")?.jsonPrimitive?.contentOrNull,
                )
            }
        SseChunk(deltaText, finish, done = false, toolCallDelta = toolDelta)
    }.getOrNull()
}

/**
 * 流式对话抽象（便于单测注入 fake；LlmClient 为 OpenAI-compatible 唯一实现）。
 */
interface LlmStream {
    fun streamChat(
        apiKey: String,
        baseUrl: String,
        model: String,
        messages: List<ChatMessage>,
        temperature: Double = 0.7,
        tools: List<ToolSpec>? = null,
    ): Flow<LlmEvent>
}

/**
 * 构建 OpenAI-compatible /chat/completions 请求体（纯函数，可单测）。
 * images 非空的消息走多模态 content 数组（text + image_url data URL）。
 */
fun buildChatBody(
    model: String,
    messages: List<ChatMessage>,
    temperature: Double,
    tools: List<ToolSpec>?,
): JsonObject = buildJsonObject {
    put("model", model)
    put("messages", JsonArray(messages.map { msg ->
        if (msg.images.isEmpty()) {
            buildJsonObject {
                put("role", msg.role)
                put("content", msg.content)
            }
        } else {
            buildJsonObject {
                put("role", msg.role)
                put("content", JsonArray(buildList {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", msg.content)
                    })
                    for (img in msg.images) {
                        add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject {
                                put("url", "data:${img.mime};base64,${img.base64}")
                            })
                        })
                    }
                }))
            }
        }
    }))
    put("stream", true)
    put("temperature", temperature)
    // F8 Function Calling tools
    if (!tools.isNullOrEmpty()) {
        put("tools", JsonArray(tools.map { spec ->
            buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", spec.name)
                    put("description", spec.description)
                    put("parameters", Json.parseToJsonElement(spec.parametersJson))
                })
            }
        }))
    }
}

/**
 * OpenAI-compatible 流式对话客户端。
 * 统一抽象：云厂商直连 / 自定义中转站 / Ollama 等本地网关走同一接口（F6/PRD §5）。
 */
class LlmClient(
    private val client: OkHttpClient = defaultHttpClient(),
) : LlmStream {
    override fun streamChat(
        apiKey: String,
        baseUrl: String,
        model: String,
        messages: List<ChatMessage>,
        temperature: Double,
        tools: List<ToolSpec>?,
    ): Flow<LlmEvent> = callbackFlow {
        val body = buildChatBody(model, messages, temperature, tools).toString()

        val request = Request.Builder()
            .url(chatCompletionsUrl(baseUrl))
            .post(body.toRequestBody("application/json".toMediaType()))
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .build()

        val call = client.newCall(request)
        var closed = false
        fun closeSafe() { if (!closed) { closed = true; close() } }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (closed) return
                trySend(LlmEvent.Failed("网络请求失败: ${e.message ?: e.javaClass.simpleName}"))
                closeSafe()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (closed) return
                    if (!resp.isSuccessful) {
                        val errBody = runCatching { resp.body?.string().orEmpty().take(400) }.getOrDefault("")
                        trySend(LlmEvent.Failed(
                            "HTTP ${resp.code}: ${errBody.ifBlank { resp.message }}", resp.code))
                        closeSafe()
                        return
                    }
                    val reader: BufferedReader = resp.body?.byteStream()?.bufferedReader() ?: run {
                        trySend(LlmEvent.Failed("响应体为空"))
                        closeSafe()
                        return
                    }
                    runCatching {
                        var finishReason: String? = null
                        var sawDone = false
                        // 聚合 tool_call 分片（index → name/arguments 拼接）
                        val toolCalls = mutableMapOf<Int, Pair<StringBuilder?, StringBuilder?>>()
                        while (true) {
                            if (closed) return
                            val line = reader.readLine() ?: break
                            val chunk = parseSseLine(line) ?: continue
                            if (chunk.done) { sawDone = true; break }
                            chunk.deltaText?.takeIf { it.isNotEmpty() }?.let { trySend(LlmEvent.Delta(it)) }
                            chunk.toolCallDelta?.let { tc ->
                                val pair = toolCalls.getOrPut(tc.index) { StringBuilder() to StringBuilder() }
                                tc.name?.let { pair.first?.append(it) }
                                tc.argsFragment?.let { pair.second?.append(it) }
                            }
                            chunk.finishReason?.let { finishReason = it }
                        }
                        // 流结束：先发完整 ToolCall 再 Completed
                        for ((_, pair) in toolCalls.toSortedMap()) {
                            val name = pair.first?.toString().orEmpty()
                            val args = pair.second?.toString().orEmpty()
                            if (name.isNotBlank()) trySend(LlmEvent.ToolCall(name, args))
                        }
                        trySend(LlmEvent.Completed(finishReason ?: if (sawDone) "stop" else null))
                    }.onFailure { e ->
                        trySend(LlmEvent.Failed("读取流失败: ${e.message ?: e.javaClass.simpleName}"))
                    }
                    closeSafe()
                }
            }
        })

        awaitClose { closed = true; call.cancel() }
    }.flowOn(Dispatchers.IO)
}

fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(180, TimeUnit.SECONDS) // 流式长读：token 间隔可能很久
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()
