package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 私聊消息本体（方案_角色间私聊_v2-5 3.2 节）
 *
 * 独立于 roundtable_messages 表。triggerSource 区分"manual"（开场白）和
 * "reply_chain"（会话内后续回复），不再对应"用户手动/状态变化"两种发起方式
 * （第一版只有用户手动发起，见 2.1 节）。
 */
@Entity(
    tableName = "private_chat_messages",
    indices = [Index(value = ["pairId", "timestamp"])],
)
data class PrivateChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pairId: String,
    val senderCharacterId: Int,
    val content: String,
    val timestamp: Long,
    val sessionId: String,
    val turnIndexInSession: Int,
    val triggerSource: String,
)
