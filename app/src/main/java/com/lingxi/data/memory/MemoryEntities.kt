package com.lingxi.data.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 已完成的一轮对话（F7 短期会话层：落库 + 重启恢复滑窗） */
@Entity(tableName = "turns")
data class TurnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val user: String,
    val reply: String,
    /** 是否已被每日摘要消费（防重复摘要） */
    val summarized: Boolean = false,
)

/** 每日本地摘要（F7 中期层，滚动保留最近 N 天） */
@Entity(tableName = "daily_summaries")
data class DailySummaryEntity(
    /** YYYY-MM-DD（设备本地时区） */
    @PrimaryKey val date: String,
    val summary: String,
    val updatedAt: Long,
)

/** 长期画像条目（F7 长期层：语音编辑沉淀 / 即时生效） */
@Entity(tableName = "profile_entries")
data class ProfileEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    /** voice_edit = 语音编辑记忆沉淀 */
    val source: String,
    val createdAt: Long,
    val enabled: Boolean = true,
)
