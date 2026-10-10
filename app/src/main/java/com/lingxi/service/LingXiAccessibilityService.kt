package com.lingxi.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * F9 UI 代操作（默认关：需用户在系统设置手动开启无障碍服务）。
 * v1 能力：读取当前窗口可见文字（read_screen）+ 点击包含指定文字的可点击元素（click_ui）。
 * 风险告知（PRD 要求原文写入功能说明页）：在第三方 App 内使用辅助服务可能违反其用户协议。
 */
class LingXiAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() {}

    /** 广度优先收集当前窗口可见文本（去重、限长） */
    fun readScreenText(): String? {
        val root = rootInActiveWindow ?: return null
        val seen = LinkedHashSet<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst() ?: continue
            visited++
            node.text?.takeIf { it.isNotBlank() }?.let { seen.add(it.toString().trim()) }
            node.contentDescription?.takeIf { it.isNotBlank() }?.let { seen.add(it.toString().trim()) }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        if (seen.isEmpty()) return null
        return seen.joinToString("\n").take(MAX_TEXT)
    }

    /** 点击包含 target 文字/描述的可点击节点（节点本身不可点则向上找最近的可点击祖先） */
    fun clickByText(target: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst() ?: continue
            val label = node.text?.toString().orEmpty() + " " + (node.contentDescription ?: "")
            if (target.isNotBlank() && label.contains(target, ignoreCase = true)) {
                var cur: AccessibilityNodeInfo? = node
                while (cur != null) {
                    if (cur.isClickable) return cur.performAction(ACTION_CLICK)
                    cur = cur.parent
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    companion object {
        @Volatile
        var instance: LingXiAccessibilityService? = null
            private set

        val enabled: Boolean get() = instance != null

        fun readScreenText(): String? = instance?.readScreenText()

        fun clickText(target: String): Boolean = instance?.clickText(target) ?: false

        private const val MAX_TEXT = 2000
        private const val MAX_NODES = 500
    }
}
