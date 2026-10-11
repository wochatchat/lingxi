package com.lingxi.data.asr

import com.lingxi.data.net.NetTier

/** ASR 路径决策结果：CLOUD 云端识别 / SYSTEM 系统离线识别 / NONE 无可用通道 */
enum class AsrPath { CLOUD, SYSTEM, NONE }

/**
 * ASR 降级链决策（R15，纯函数可单测）：
 * 全离线或断网 → 只能系统识别（前提系统可用）；
 * 有云 Provider 且在线 → 云端优先，失败后回落系统识别；
 * 未配置云 Provider → 系统识别兜底。
 */
fun planAsrPath(hasCloud: Boolean, systemAvailable: Boolean, net: NetTier, offlineMode: Boolean): AsrPath {
    if (offlineMode || net == NetTier.OFFLINE) {
        return if (systemAvailable) AsrPath.SYSTEM else AsrPath.NONE
    }
    if (hasCloud) return AsrPath.CLOUD
    return if (systemAvailable) AsrPath.SYSTEM else AsrPath.NONE
}

/** 云端识别失败后的回落决策（同参数约束） */
fun fallbackAfterCloudFailure(systemAvailable: Boolean, net: NetTier): AsrPath =
    if (systemAvailable && net != NetTier.OFFLINE) AsrPath.SYSTEM else AsrPath.NONE
