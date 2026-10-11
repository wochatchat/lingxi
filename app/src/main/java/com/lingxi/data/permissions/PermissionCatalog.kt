package com.lingxi.data.permissions

/**
 * 权限中心目录（R15，纯数据可单测）：
 * 全部权限清单 + 状态判定方式 + 授权入口。复用 F15 checklist 模式。
 */
enum class PermissionKind {
    /** 运行时权限：checkSelfPermission 判定 */
    RUNTIME,
    /** 特殊权限：悬浮窗（Settings.canDrawOverlays） */
    OVERLAY,
    /** 电池豁免（PowerManager） */
    BATTERY,
    /** 无障碍服务（软件侧 enabled 标志） */
    ACCESSIBILITY,
    /** 厂商自启管理（无统一 API，只给引导路径） */
    AUTOSTART,
}

data class PermissionItem(
    val key: String,
    val title: String,
    val desc: String,
    val kind: PermissionKind,
    /** RUNTIME 类的 android 权限串；其它 kind 为空 */
    val androidPermission: String = "",
)

object PermissionCatalog {

    const val P_MIC = "android.permission.RECORD_AUDIO"
    const val P_NOTIFY = "android.permission.POST_NOTIFICATIONS"
    const val P_CALENDAR = "android.permission.READ_CALENDAR"
    const val P_CONTACTS = "android.permission.READ_CONTACTS"
    const val P_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    const val P_IMAGES = "android.permission.READ_MEDIA_IMAGES"

    /** 全部权限清单（顺序即页面展示顺序；key 全局唯一） */
    val CATALOG: List<PermissionItem> = listOf(
        PermissionItem("mic", "麦克风", "语音对话与常听（核心能力，建议开启）", PermissionKind.RUNTIME, P_MIC),
        PermissionItem("notify", "通知", "晨报、日程提醒、委托进展等主动通知", PermissionKind.RUNTIME, P_NOTIFY),
        PermissionItem("overlay", "悬浮窗", "耳语胶囊与悬浮对话卡片", PermissionKind.OVERLAY),
        PermissionItem("battery", "电池豁免", "防止系统杀后台，保活常听与巡检", PermissionKind.BATTERY),
        PermissionItem("accessibility", "无障碍", "读屏与 UI 代操作（可选，默认关）", PermissionKind.ACCESSIBILITY),
        PermissionItem("calendar", "日历", "日程提醒读取今日日程", PermissionKind.RUNTIME, P_CALENDAR),
        PermissionItem("contacts", "通讯录", "发短信时把收件人姓名解析成号码", PermissionKind.RUNTIME, P_CONTACTS),
        PermissionItem("location", "位置", "晨报城市未配置时按位置精化天气", PermissionKind.RUNTIME, P_LOCATION),
        PermissionItem("images", "照片", "截屏问答读取最近一张截图", PermissionKind.RUNTIME, P_IMAGES),
        PermissionItem("autostart", "厂商自启", "部分 ROM 需在厂商设置里单独允许自启", PermissionKind.AUTOSTART),
    )
}
