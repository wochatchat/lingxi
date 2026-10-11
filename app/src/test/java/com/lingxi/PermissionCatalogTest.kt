package com.lingxi

import com.lingxi.data.permissions.PermissionCatalog
import com.lingxi.data.permissions.PermissionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionCatalogTest {

    @Test
    fun `清单 key 全局唯一`() {
        val keys = PermissionCatalog.CATALOG.map { it.key }
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun `运行时权限必须带 android 权限串，其它 kind 不带`() {
        for (item in PermissionCatalog.CATALOG) {
            if (item.kind == PermissionKind.RUNTIME) {
                assertTrue(item.androidPermission.startsWith("android.permission."))
            } else {
                assertTrue(item.androidPermission.isEmpty())
            }
        }
    }

    @Test
    fun `核心权限项齐全（麦克风_通知_悬浮窗_联系人_位置）`() {
        val keys = PermissionCatalog.CATALOG.map { it.key }
        for (expected in listOf("mic", "notify", "overlay", "contacts", "location")) {
            assertTrue("缺少权限项 $expected", keys.contains(expected))
        }
    }
}
