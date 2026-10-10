package com.lingxi.data.functions

import com.lingxi.data.UiAction

/** 工具执行结果 */
data class ActionResult(
    val success: Boolean,
    /** 成功时朗读给用户的口语化确认（如"已设好明天早上 7 点的闹钟"） */
    val spoken: String,
    /** InfoCard 标题（null 表示不发 InfoCard） */
    val cardTitle: String? = null,
    /** InfoCard 正文 */
    val cardBody: String? = null,
    /** open_app 模糊匹配时返回候选列表（触发 ChoiceSheet） */
    val candidates: List<String> = emptyList(),
    /** 执行失败的错误说明（给用户看） */
    val errorMessage: String? = null,
    /** R10：创建巡查任务后弹出的参数确认面板（引擎呈现并回写参数） */
    val paramPanel: UiAction? = null,
    /** R10：paramPanel 对应的任务 id（引擎回写 update_interval 用） */
    val paramTaskId: Long = 0L,
) {
    companion object {
        fun ok(
            spoken: String,
            cardTitle: String? = null,
            cardBody: String? = null,
            paramPanel: UiAction? = null,
            paramTaskId: Long = 0L,
        ) = ActionResult(true, spoken, cardTitle, cardBody, paramPanel = paramPanel, paramTaskId = paramTaskId)

        fun candidates(list: List<String>) =
            ActionResult(false, "找到多个应用，请说第几个", candidates = list)

        fun error(msg: String) =
            ActionResult(false, "操作失败：$msg", errorMessage = msg)
    }
}

/**
 * 动作执行器接口（R6 F8）。
 * Android 实现由 Hilt 注入 @ApplicationContext。
 */
interface ActionExecutor {
    suspend fun execute(toolName: String, argsJson: String): ActionResult
}
