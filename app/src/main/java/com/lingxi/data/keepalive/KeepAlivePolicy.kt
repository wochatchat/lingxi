package com.lingxi.data.keepalive

/**
 * R11 保活与自启——纯判定逻辑（无 Android 依赖，可单测）。
 */
object KeepAlivePolicy {

    /** 开机后是否启动常驻服务：自启开关开 且（胶囊或常听任一启用） */
    fun shouldStartOnBoot(autoStart: Boolean, capsule: Boolean, listen: Boolean): Boolean =
        autoStart && (capsule || listen)

    /** 心跳时是否需要拉起服务：用户要常驻但服务没活着 */
    fun shouldRevive(serviceAlive: Boolean, wantService: Boolean): Boolean =
        wantService && !serviceAlive
}
