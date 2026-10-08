package com.lingxi.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.providerDataStore by preferencesDataStore("providers")

/**
 * Provider 配置仓库（F6 + F18）：
 * - 配置（不含 apiKey）→ DataStore JSON
 * - apiKey → AndroidKeyStore + EncryptedSharedPreferences，按 provider id 存取
 *   （EncryptedSharedPreferences 初始化失败的设备降级普通 SharedPreferences，
 *    仅存本机 App 私有目录，并在 UI 上可辨识——降级不炸）
 */
@Singleton
class ProviderRepository @javax.inject.Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val jsonKey = stringPreferencesKey("providers_json")

    /** API Key 安全存储：优先 EncryptedSharedPreferences，失败降级普通 prefs */
    private val securePrefs: SharedPreferences by lazy {
        runCatching {
            val masterKey = androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "lingxi_secure_prefs",
                masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse {
            android.util.Log.w("ProviderRepo", "EncryptedSharedPreferences init failed, falling back", it)
            context.getSharedPreferences("lingxi_keys_fallback", Context.MODE_PRIVATE)
        }
    }

    /** 配置列表（不含 key 明文） */
    val providers: Flow<List<ProviderConfig>> = context.providerDataStore.data
        .map { prefs -> ProviderCodec.decode(prefs[jsonKey] ?: "[]") }

    suspend fun upsert(config: ProviderConfig, apiKey: String?) {
        val list = providers.first()
        val next = list.filterNot { it.id == config.id } + config
        context.providerDataStore.edit { it[jsonKey] = ProviderCodec.encode(next.sortedBy { it.name }) }
        if (apiKey != null) setApiKey(config.id, apiKey)
    }

    suspend fun delete(id: String) {
        val list = providers.first()
        context.providerDataStore.edit { it[jsonKey] = ProviderCodec.encode(list.filterNot { it.id == id }) }
        securePrefs.edit().remove(id).apply()
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val list = providers.first()
        context.providerDataStore.edit {
            it[jsonKey] = ProviderCodec.encode(list.map { p -> if (p.id == id) p.copy(enabled = enabled) else p })
        }
    }

    fun getApiKey(providerId: String): String =
        securePrefs.getString(providerId, null).orEmpty()

    fun setApiKey(providerId: String, key: String) {
        securePrefs.edit().putString(providerId, key.trim()).apply()
    }

    /** 供对话引擎取「默认可用的 provider」；R5 意图路由在此基础上扩展 */
    suspend fun firstEnabled(): Pair<ProviderConfig, String>? {
        val list = providers.first().filter { it.enabled }
        for (p in list) {
            val key = getApiKey(p.id)
            if (p.kind == ProviderKind.LOCAL || key.isNotBlank()) return p to key
        }
        return null
    }

    /** 连通性测试：发一句 ping，收到首个增量即成功（早停）。20s 超时。 */
    suspend fun testConnection(config: ProviderConfig, apiKey: String): Result<String> {
        // 哨兵异常：collect 中提前终止，语义清晰且不会误伤协程取消机制
        class Stop(val text: String?, val failure: LlmEvent.Failed?) : Exception()

        return kotlinx.coroutines.withTimeoutOrNull(20_000) {
            var firstText: String? = null
            var failure: LlmEvent.Failed? = null
            runCatching {
                LlmClient().streamChat(
                    apiKey = apiKey.ifBlank { getApiKey(config.id) },
                    baseUrl = config.baseUrl,
                    model = config.model,
                    messages = listOf(ChatMessage("user", "ping，只回一个字")),
                ).collect { ev ->
                    when (ev) {
                        is LlmEvent.Delta -> if (ev.text.isNotEmpty()) throw Stop(ev.text, null)
                        is LlmEvent.Failed -> { failure = ev; throw Stop(null, ev) }
                        is LlmEvent.Completed -> throw Stop(null, null)
                    }
                }
            }.onFailure { if (it is Stop) { firstText = it.text; failure = it.failure } }
            when {
                firstText != null -> Result.success(firstText!!)
                failure != null -> Result.failure(Exception(failure!!.message))
                else -> Result.failure(Exception("无响应"))
            }
        } ?: Result.failure(Exception("连接超时（20s）"))
    }
}
