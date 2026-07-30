package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 私聊会话状态表（方案_角色间私聊_v2-5 3.2.1 节，v2.3 新增）
 *
 * 标记"正常收尾"与"异常中断"。runSession() 开场先插入 in_progress 记录；
 * 循环正常结束时更新为 completed；catch 到异常时更新为 interrupted 并记录
 * errorMessage，再把异常上抛给 PrivateChatWorker。
 */
@Entity(tableName = "private_chat_sessions")
data class PrivateChatSessionEntity(
    @PrimaryKey val sessionId: String,
    val pairId: String,
    val startedAt: Long,
    val status: String,        // "in_progress" | "completed" | "interrupted"
    val turnCount: Int = 0,
    val errorMessage: String? = null,
)
