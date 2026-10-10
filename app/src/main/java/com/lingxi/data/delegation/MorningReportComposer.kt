package com.lingxi.data.delegation

/**
 * F11 晨报作曲器（纯函数，可单测）：天气 + 今日日程 + 委托任务 → 一段口播友好文本。
 * 不走 LLM（省成本、稳定），模板拼装；后续可按需加 LLM 润色。
 */
class MorningReportComposer @javax.inject.Inject constructor() {

    data class Inputs(
        val dateLine: String,
        /** 「多云 12°C」，城市未配置或拉取失败为 null */
        val weather: String?,
        /** 今日日程（已格式化的一行） */
        val events: List<String>,
        /** 进行中的委托任务标题 */
        val activeTasks: List<String>,
    )

    fun compose(inputs: Inputs): String = buildString {
        append("早上好。今天是${inputs.dateLine}。")
        inputs.weather?.let { append("天气：$it。") }
        if (inputs.events.isEmpty()) {
            append("今天没有日程安排。")
        } else {
            append("今天有${inputs.events.size}件日程：")
            inputs.events.forEachIndexed { i, e -> append("${i + 1}，$e。") }
        }
        if (inputs.activeTasks.isEmpty()) {
            append("没有进行中的委托任务。")
        } else {
            append("有${inputs.activeTasks.size}个委托在盯：")
            inputs.activeTasks.forEachIndexed { i, t -> append("${i + 1}，${t}。") }
        }
    }
}
