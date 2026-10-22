package com.lingxi.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 网络 AI 服务预设目录（替代内置 PROVIDER_PRESETS 的硬编码）。
 * 数据源：本仓库 catalog/providers.json，经 jsDelivr CDN（国内可达）优先、
 * raw.githubusercontent 回落。App 内置预设仅作离线兜底。
 */
object ProviderCatalog {

    /** 目录地址，按序尝试，首个成功即用 */
    val CATALOG_URLS = listOf(
        "https://cdn.jsdelivr.net/gh/wochatchat/lingxi@main/catalog/providers.json",
        "https://raw.githubusercontent.com/wochatchat/lingxi/main/catalog/providers.json",
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** 依次尝试各 URL；全部失败返回 null（调用方回落内置预设） */
    suspend fun fetch(
        baseClient: OkHttpClient,
        urls: List<String> = CATALOG_URLS,
    ): List<ProviderPreset>? = withContext(Dispatchers.IO) {
        // 目录文件很小，用短超时的衍生客户端，避免拖慢弹窗
        val client = baseClient.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
        for (url in urls) {
            val parsed = runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) null
                    else parse(resp.body?.string().orEmpty()).takeIf { it.isNotEmpty() }
                }
            }.getOrNull()
            if (parsed != null) return@withContext parsed
        }
        null
    }

    /**
     * 解析目录 JSON → ProviderPreset 列表。
     * 宽松策略：单条非法（未知 kind、空 key、非 http baseUrl）只丢弃该条，不整包失败；
     * 整包 JSON 非法返回空列表。
     */
    fun parse(raw: String): List<ProviderPreset> = runCatching {
        json.decodeFromString<RemoteCatalog>(raw.trim())
    }.fold(
        onSuccess = { catalog ->
            catalog.presets.mapNotNull { it.toPresetOrNull() }
        },
        onFailure = { emptyList() },
    )

    /** 调试用：目录序列化（仓库维护 catalog/providers.json 时本地生成参考） */
    fun encode(catalog: RemoteCatalog): String = json.encodeToString(catalog)

    private fun RemotePreset.toPresetOrNull(): ProviderPreset? {
        if (key.isBlank() || label.isBlank()) return null
        if (!baseUrl.startsWith("http")) return null
        if (model.isBlank()) return null
        return ProviderPreset(
            key = key.trim(),
            label = label.trim(),
            kind = runCatching { ProviderKind.valueOf(kind) }.getOrDefault(ProviderKind.CLOUD),
            baseUrl = normalizeBaseUrl(baseUrl),
            model = model.trim(),
        )
    }
}

/** 目录 JSON 中的单条预设。kind 用字符串承载，未知名回落 CLOUD（服务器先于客户端新增枚举时客户端不炸） */
@Serializable
data class RemotePreset(
    val key: String,
    val label: String,
    val kind: String = "CLOUD",
    val baseUrl: String,
    val model: String,
)

@Serializable
data class RemoteCatalog(
    val version: Int = 1,
    val presets: List<RemotePreset> = emptyList(),
)
