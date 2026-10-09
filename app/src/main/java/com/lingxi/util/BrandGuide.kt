package com.lingxi.util

/**
 * F15 厂商自启白名单引导（PRD：小米/华为/OPPO/vivo 按品牌出引导页）。
 *
 * 按设备品牌匹配引导文案与「自启管理」入口组件（各厂商 ROM 路径随版本漂移，
 * 逐个尝试组件、全部失败回落应用详情页）。品牌匹配为纯函数可单测。
 */
data class BrandGuide(
    /** 品牌显示名（小米 / 华为 / OPPO / vivo / 三星 / 其他） */
    val label: String,
    /** 引导文案：告诉用户在哪允许灵犀自启/后台运行 */
    val hint: String,
    /** 自启管理入口组件（包名/类名），按顺序尝试 */
    val entryComponents: List<String>,
) {
    companion object {
        val GENERIC = BrandGuide(
            label = "通用",
            hint = "在系统设置 → 电池 / 应用管理中，允许灵犀「后台运行」与「自启动」。",
            entryComponents = emptyList(),
        )
    }
}

private val BRAND_TABLE: List<Pair<String, BrandGuide>> = listOf(
    // 小米 / 红米：MIUI 自启管理
    Pair("miui_autostart", BrandGuide(
        label = "小米 / 红米 (MIUI)",
        hint = "MIUI 需在「自启动管理」允许灵犀自启，并在省电策略里设为「无限制」，否则常听会被后台杀掉。",
        entryComponents = listOf(
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.miui.securitycenter",
        ),
    )),
    Pair("huawei_honor", BrandGuide(
        label = "华为 / 荣耀",
        hint = "EMUI/HarmonyOS：在「应用启动管理」中把灵犀改为手动管理，允许自启动、关联启动、后台活动。",
        entryComponents = listOf(
            "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager/com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            "com.huawei.systemmanager",
        ),
    )),
    Pair("oppo_realme_oneplus", BrandGuide(
        label = "OPPO / 一加 / realme (ColorOS)",
        hint = "ColorOS：在「自启动管理」允许灵犀自启，电池设置里关闭「智能省电」对灵犀的限制。",
        entryComponents = listOf(
            "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter/com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.coloros.safecenter",
        ),
    )),
    Pair("vivo_iqoo", BrandGuide(
        label = "vivo / iQOO (OriginOS)",
        hint = "vivo：在「后台弹出权限/自启动管理」允许灵犀，并在电池后台高耗电中允许灵犀后台运行。",
        entryComponents = listOf(
            "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.vivo.permissionmanager",
        ),
    )),
    Pair("samsung", BrandGuide(
        label = "三星 (One UI)",
        hint = "三星：设置 → 电池 → 后台使用限制，把灵犀移出「深度睡眠」应用列表。",
        entryComponents = listOf(
            "com.samsung.android.lool/com.samsung.android.sm.battery.ui.BatteryActivity",
            "com.samsung.android.lool",
        ),
    )),
)

private val BRAND_KEYWORDS: Map<String, List<String>> = mapOf(
    "miui_autostart" to listOf("xiaomi", "redmi"),
    "huawei_honor" to listOf("huawei", "honor"),
    "oppo_realme_oneplus" to listOf("oppo", "oneplus", "realme"),
    "vivo_iqoo" to listOf("vivo", "iqoo"),
    "samsung" to listOf("samsung"),
)

/** 按 manufacturer 串匹配品牌引导（Build.MANUFACTURER 小写后传入）；未收录返回 null（走通用文案） */
fun brandGuideFor(manufacturer: String): BrandGuide? {
    val m = manufacturer.lowercase().trim()
    if (m.isEmpty()) return null
    for ((key, guide) in BRAND_TABLE) {
        if (BRAND_KEYWORDS[key]?.any { m.contains(it) } == true) return guide
    }
    return null
}
