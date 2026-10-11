package com.lingxi.data.functions

import android.content.Context
import android.provider.ContactsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * 判断收件人串是否长得像电话号码（纯函数可单测）：
 * 数字 ≥7 位且不含文字字母（汉字/英文字母）→ 视为号码；否则按联系人姓名查通讯录。
 * 允许 +86 138 0013 8000 里的空格/横线。
 */
fun looksLikePhoneNumber(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return false
    val digits = trimmed.count { it.isDigit() }
    if (digits < 7) return false
    // 去掉数字、+、-、空格、() 后不允许再有任何字符（有字母 = 人名）
    return trimmed.none { !it.isDigit() && it != '+' && it != '-' && it != ' ' && it != '(' && it != ')' }
}

/** 号码归一：去空格/横线，保留 + */
fun normalizePhone(raw: String): String =
    raw.filter { it.isDigit() || it == '+' }

/**
 * 通讯录查号（R15）：收件人是姓名时 → 号码。
 * 需要 READ_CONTACTS（未授权由调用方先拦，本类不做权限判定）。
 */
class ContactResolver @Inject constructor(
    @ApplicationContext private val app: Context,
) {
    /** 姓名 → 首个号码（含模糊匹配「张三」命中「张三丰」）；找不到返回 null */
    fun numberForName(name: String): String? {
        val query = name.trim()
        if (query.isEmpty() || looksLikePhoneNumber(name)) return null
        return runCatching {
            app.contentResolver.query(
                android.net.Uri.withAppendedPath(
                    android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
                    android.net.Uri.encode(name),
                ),
                arrayOf(
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ),
                null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.let { normalizePhone(it) }
                } else null
            }
        }.getOrNull()
    }
}
