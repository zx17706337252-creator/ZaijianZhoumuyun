package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 圆桌消息表（待办7：圆桌消息持久化）。
 *
 * 与单人聊天的 [MessageEntity] 按 characterId 分表不同，圆桌一条对话线由
 * "进入圆桌时的成员集合"决定（[roundtableId] = 成员 characterId 排序后用
 * "_" 拼接，如 "1_2_3_4"），后续 addMember/removeMember 临时增减人不改变
 * 这条线的归属，避免每次改名单就"断片"成新对话。
 *
 * 一条记录 = 一条消息（用户发言 或 某个 Bot 的回复）。
 */
@Entity(
    tableName = "roundtable_messages",
    indices = [
        Index(value = ["roundtableId"]),
        Index(value = ["roundtableId", "createdAt"]),
    ]
)
data class RoundtableMessageEntity(
    @PrimaryKey val id: String,
    /** 成员 characterId 排序后拼接，如 "1_2_3_4" */
    val roundtableId: String,
    /** "user" 或 characterId 字符串 */
    val speakerId: String,
    val speakerName: String,
    val content: String,
    val createdAt: Long,
    /** 回应目标：null=回应用户，非null=回应某Bot */
    val replyTargetId: String? = null,
    val replyTargetName: String? = null,
    /** 此消息所属的用户消息轮次 */
    val turnIndex: Int = 0,
    /**
     * P6 专长进化系统新增：与 MessageEntity.exportedFileJson 同语义。
     * 圆桌场景此前没有这个能力，专长进化的每日修炼播报需要"产出全文可下载"，
     * 借此补齐圆桌消息的文件卡片能力。
     * null = 普通消息，非 null = 该消息携带可下载文件卡片。
     */
    val exportedFileJson: String? = null,
)
