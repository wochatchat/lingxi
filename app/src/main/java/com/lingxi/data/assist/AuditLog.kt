package com.lingxi.data.assist

import android.content.Context

/**
 * F9 UI 代操作审计日志（PRD §16.4：操作留痕可审计）。
 * SharedPreferences 存最近 100 条，每行 `ts|动作|详情|结果`，最新在前。
 */
object AuditLog {

    private const val PREFS = "ui_assist_audit"
    private const val KEY = "lines"
    private const val MAX_LINES = 100

    fun append(context: Context, action: String, detail: String, success: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = prefs.getString(KEY, null).orEmpty()
        val line = buildString {
            append(System.currentTimeMillis())
            append('|').append(action)
            append('|').append(detail.replace('\n', ' ').take(80))
            append('|').append(if (success) "成功" else "失败")
        }
        val next = (listOf(line) + old.lines().filter { it.isNotBlank() }).take(MAX_LINES)
        prefs.edit().putString(KEY, next.joinToString("\n")).apply()
    }

    /** 解析后的审计条目（旧→新顺序由调用方决定；这里新→旧） */
    fun entries(context: Context): List<AuditEntry> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null).orEmpty()
        return raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size < 4) return@mapNotNull null
            AuditEntry(
                ts = parts[0].toLongOrNull() ?: return@mapNotNull null,
                action = parts[1],
                detail = parts[2],
                success = parts[3] == "成功",
            )
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}

data class AuditEntry(val ts: Long, val action: String, val detail: String, val success: Boolean)
