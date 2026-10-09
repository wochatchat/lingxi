package com.lingxi

import com.lingxi.util.GENERIC
import com.lingxi.util.brandGuideFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrueimport org.junit.Test

class BrandGuideTest {

    @Test
    fun `小米品牌命中`() {
        val g = brandGuideFor("Xiaomi")
        assertTrue(g != null && g.label.contains("小米"))
    }

    @Test
    fun `红米走小米引导`() {
        assertEquals(brandGuideFor("Xiaomi"), brandGuideFor("Redmi"))
    }

    @Test
    fun `华为与荣耀同一组`() {
        assertEquals(brandGuideFor("HUAWEI"), brandGuideFor("HONOR"))
    }

    @Test
    fun `OPPO 一加 realme 同组 vivo iQOO 同组`() {
        assertEquals(brandGuideFor("OPPO"), brandGuideFor("OnePlus"))
        assertEquals(brandGuideFor("OPPO"), brandGuideFor("realme"))
        assertEquals(brandGuideFor("vivo"), brandGuideFor("iQOO"))
    }

    @Test
    fun `未收录品牌返回null走通用文案`() {
        assertNull(brandGuideFor("Google"))
        assertNull(brandGuideFor(""))
        // 通用兜底由调用方提供
        assertTrue(GENERIC.entryComponents.isEmpty())
    }
}
