package com.lingxi.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 网络三档（R15 F-断网弱网）：ONLINE 正常 / WEAK 连着但未过验证（ captive portal 等）/ OFFLINE 无网 */
enum class NetTier { ONLINE, WEAK, OFFLINE }

/** 纯判定函数（可单测）：有无网络 × 是否已过连通性验证 → 三档 */
fun resolveNetTier(hasNetwork: Boolean, isValidated: Boolean): NetTier = when {
    !hasNetwork -> NetTier.OFFLINE
    !isValidated -> NetTier.WEAK
    else -> NetTier.ONLINE
}

/** 网络状态查询口（引擎/Runner 依赖抽象，测试注入假实现） */
interface NetStatus {
    fun current(): NetTier
}

/** 测试/无网关场景兜底：永远在线 */
object AlwaysOnlineNetStatus : NetStatus {
    override fun current(): NetTier = NetTier.ONLINE
}

/**
 * ConnectivityManager 网络状态流（R15）：
 * registerDefaultNetworkCallback 跟踪默认网络，能力变化即更新三档。
 * 引擎断网占位 / 弱网降短回复 / Runner 选 ASR 降级链共用这一个事实源。
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext context: Context,
) : NetStatus {

    private val _tier = MutableStateFlow(NetTier.OFFLINE)
    val tier: StateFlow<NetTier> = _tier.asStateFlow()

    override fun current(): NetTier = _tier.value

    private val cm =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    init {
        val manager = cm
        if (manager != null) {
            _tier.value = snapshot(manager)
            runCatching {
                manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { refresh(manager) }
                    override fun onLost(network: Network) { refresh(manager) }
                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities,
                    ) { refresh(manager) }
                })
            }
        }
    }

    private fun refresh(manager: ConnectivityManager) {
        _tier.value = snapshot(manager)
    }

    private fun snapshot(manager: ConnectivityManager): NetTier {
        val caps = runCatching {
            manager.getNetworkCapabilities(manager.activeNetwork)
        }.getOrNull() ?: return NetTier.OFFLINE
        val hasNet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return resolveNetTier(hasNet, validated)
    }
}
