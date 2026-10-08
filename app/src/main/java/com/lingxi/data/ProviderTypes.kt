package com.lingxi.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Provider 类别：云厂商直连 / 自定义中转站 / 本地（Ollama 等） */
enum class ProviderKind { CLOUD, RELAY, LOCAL }

/**
 * 一个可用的 LLM Provider。apiKey 不入库（不进 DataStore/JSON），
 * 单独存 EncryptedSharedPreferences（F18），以 [id] 为键。
 */
@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    val kind: ProviderKind = ProviderKind.CLOUD,
    /** OpenAI-compatible 根路径，如 https://api.deepseek.com/v1 */
    val baseUrl: String,
    val model: String,
    val enabled: Boolean = true,
)

/** 内置预设：一键填 baseUrl + 默认 model，用户只补 key */
data class ProviderPreset(
    val key: String,
    val label: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val model: String,
)

val PROVIDER_PRESETS = listOf(
    ProviderPreset("openai", "OpenAI", ProviderKind.CLOUD, "https://api.openai.com/v1", "gpt-4o-mini"),
    ProviderPreset("deepseek", "DeepSeek", ProviderKind.CLOUD, "https://api.deepseek.com/v1", "deepseek-chat"),
    ProviderPreset("kimi", "Kimi", ProviderKind.CLOUD, "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
    ProviderPreset("qwen", "通义千问", ProviderKind.CLOUD, "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
    ProviderPreset("zhipu", "智谱 GLM", ProviderKind.CLOUD, "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
    ProviderPreset("doubao", "豆包", ProviderKind.CLOUD, "https://ark.cn-beijing.volces.com/api/v3", "doubao-pro-32k"),
    ProviderPreset("ollama", "Ollama (本地)", ProviderKind.LOCAL, "http://127.0.0.1:11434/v1", "qwen2.5:1.5b"),
)

/** 中转站/自定义入口：baseUrl + apiKey 即接入 */
val CUSTOM_PRESET = ProviderPreset("custom", "自定义 / 中转站", ProviderKind.RELAY, "https://", "gpt-4o-mini")

/**
 * 规整用户输入的 baseUrl：去空白、去尾部斜杠。
 * 注意：不自动补 /v1 —— 不同网关路径各异（/v1、/v4、/compatible-mode/v1、/api/v3），
 * 预设已带全路径；自定义网关要求用户粘贴完整根路径（UI 有提示）。
 */
fun normalizeBaseUrl(raw: String): String = raw.trim().trimEnd('/')

/** chat completions 端点 */
fun chatCompletionsUrl(baseUrl: String): String =
    "${normalizeBaseUrl(baseUrl).removeSuffix("/chat/completions")}/chat/completions"

/** key 掩码：前 4 + … + 后 4；过短一律 **** */
fun maskKey(key: String): String {
    val k = key.trim()
    if (k.length <= 8) return "****"
    return k.take(4) + "…" + k.takeLast(4)
}

/** Provider 列表 JSON 持久化（不含 apiKey） */
object ProviderCodec {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun encode(list: List<ProviderConfig>): String = json.encodeToString(list)

    fun decode(raw: String): List<ProviderConfig> = runCatching {
        json.decodeFromString<List<ProviderConfig>>(raw)
    }.getOrDefault(emptyList())
}

/** 随机短 id（本地唯一即可） */
fun newProviderId(): String =
    "p_" + kotlin.random.Random.nextInt(1_000_000).toString(36) + System.currentTimeMillis().toString(36).takeLast(4)
