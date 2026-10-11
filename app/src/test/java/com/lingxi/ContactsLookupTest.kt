package com.lingxi

import com.lingxi.data.functions.looksLikePhoneNumber
import com.lingxi.data.functions.normalizePhone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsLookupTest {

    @Test
    fun `标准手机号 → 是号码`() {
        assertTrue(looksLikePhoneNumber("13800138000"))
        assertTrue(looksLikePhoneNumber("+86 138 0013 8000"))
        assertTrue(looksLikePhoneNumber("010-12345678"))
    }

    @Test
    fun `中文姓名与英文名 → 不是号码`() {
        assertFalse(looksLikePhoneNumber("张三"))
        assertFalse(looksLikePhoneNumber("Alice"))
    }

    @Test
    fun `数字过短 → 不是号码（可能是分机号误判防线）`() {
        assertFalse(looksLikePhoneNumber("123"))
        assertFalse(looksLikePhoneNumber(""))
    }

    @Test
    fun `混合姓名含数字 → 不是号码`() {
        assertFalse(looksLikePhoneNumber("张三138"))
        assertFalse(looksLikePhoneNumber("Boss 13800138000"))
    }

    @Test
    fun `归一化 - 去空格横线保留加号`() {
        assertEquals("+8613800138000", normalizePhone("+86 138-0013-8000"))
        assertEquals("13800138000", normalizePhone("138 0013 8000"))
    }
}
