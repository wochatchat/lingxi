package com.lingxi.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
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

/** 对话消息（OpenAI-compatible role/content） */
@Serializable
data class ChatMessage(val role: String, val content: String)

/** 流式事件：增量文本 / 正常结束 / 失败（带可定位信息） */
sealed interface LlmEvent {
    data class Delta(val text: String) : LlmEvent
    data class Completed(val finishReason: String?) : LlmEvent
    data class Failed(val message: String, val httpCode: Int? = null) : LlmEvent
}

/** 一行 SSE 解析结果（纯函数可测） */
data class SseChunk(val deltaText: String?, val finishReason: String?, val done: Boolean)

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
        val deltaText = (first?.get("delta") as? JsonObject)
            ?.get("content")?.jsonPrimitive?.contentOrNull
        val finish = (first?.get("finish_reason") as? JsonPrimitive)?.contentOrNull
        SseChunk(deltaText, finish, done = false)
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
    ): Flow<LlmEvent>
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
        temperature: Double = 0.7,
    ): Flow<LlmEvent> = callbackFlow {
        val body = buildJsonObject {
            put("model", model)
            put("messages", JsonArray(messages.map {
                buildJsonObject {
                    put("role", it.role)
                    put("content", it.content)
                }
            }))
            put("stream", true)
            put("temperature", temperature)
        }.toString()

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
                        while (true) {
                            if (closed) return
                            val line = reader.readLine() ?: break
                            val chunk = parseSseLine(line) ?: continue
                            if (chunk.done) { sawDone = true; break }
                            chunk.deltaText?.takeIf { it.isNotEmpty() }?.let { trySend(LlmEvent.Delta(it)) }
                            chunk.finishReason?.let { finishReason = it }
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
