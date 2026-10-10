package com.lingxi.data.delegation

/**
 * manage_task 工具参数解析（纯函数，可单测）。
 * LLM 传入 args：{ action, kind, title, when_text, interval_minutes, id }
 */
object DelegationArgs {

    /** 解析结果命令 */
    sealed interface Command {
        data class CreateReminder(val title: String, val atMillis: Long, val timeHuman: String) : Command
        data class CreatePoll(val title: String, val intervalMinutes: Int) : Command
        data object List : Command
        data class Cancel(val id: Long?, val title: String) : Command
        data class Pause(val id: Long?, val title: String) : Command
        data class Resume(val id: Long?, val title: String) : Command
    }

    sealed interface Result {
        data class Ok(val command: Command) : Result
        data class Error(val message: String) : Result
    }

    fun parse(
        args: Map<String, String>,
        nowMillis: Long,
        zone: java.time.ZoneId,
    ): Result {
        val action = (args["action"] ?: "").trim().lowercase()
        val title = (args["title"] ?: "").trim()
        val whenText = (args["when_text"] ?: "").trim()
        val id = args["id"]?.trim()?.toLongOrNull()

        if (id != null && title.isNotBlank() && action != "create") {
            return Result.Error("id 与 title 二选一")
        }
        return when (action) {
            "create", "add", "new" -> parseCreate(args, title, whenText, nowMillis, zone)
            "list", "查询", "查看" -> Result.Ok(Command.List)
            "cancel", "取消" -> cancelLike(Command::Cancel, id, title)
            "pause", "暂停" -> cancelLike(Command::Pause, id, title)
            "resume", "恢复" -> cancelLike(Command::Resume, id, title)
            else -> Result.Error("未知 action：${action.ifBlank { "(空)" }}")
        }
    }

    private fun parseCreate(
        args: Map<String, String>,
        title: String,
        whenText: String,
        nowMillis: Long,
        zone: java.time.ZoneId,
    ): Result {
        if (title.isBlank()) return Result.Error("缺少任务标题 title")
        val kind = (args["kind"] ?: "").trim().lowercase()
        // 未指定 kind：有 when_text 且可解析 → 提醒；有 interval → 巡查；都不行 → 报错
        val intervalRaw = (args["interval_minutes"] ?: "").trim().toIntOrNull()
        val parsedWhen = whenText.takeIf { it.isNotBlank() }?.let { TaskTime.parse(it, nowMillis, zone) }

        val effectiveKind = when {
            kind == "reminder" || kind == "提醒" -> "reminder"
            kind == "poll" || kind == "巡查" || kind == "盯着" -> "poll"
            parsedWhen is TaskTime.When.Daily || parsedWhen is TaskTime.When.Every -> "poll"
            parsedWhen != null -> "reminder"
            intervalRaw != null -> "poll"
            else -> "reminder"
        }

        return if (effectiveKind == "reminder") {
            val whenResolved = parsedWhen as? TaskTime.When.At
                ?: return Result.Error("提醒需要可解析的时间 when_text（如「明天 8:30」「2小时后」）")
            Result.Ok(Command.CreateReminder(title, whenResolved.epochMillis, whenResolved.human))
        } else {
            val interval = when (val w = parsedWhen) {
                is TaskTime.When.Daily -> 24 * 60
                is TaskTime.When.Every -> w.intervalMinutes
                null -> intervalRaw
                else -> intervalRaw
            }?.coerceAtLeast(15) ?: return Result.Error("巡查任务需要间隔（interval_minutes 或「每小时」）")
            Result.Ok(Command.CreatePoll(title, interval))
        }
    }

    private inline fun cancelLike(
        build: (Long?, String) -> Command,
        id: Long?,
        title: String,
    ): Result {
        if (id == null && title.isBlank()) return Result.Error("需要任务 id 或 title")
        return Result.Ok(build(id, title))
    }
}
