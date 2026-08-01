package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 私聊会话状态表（方案_角色间私聊_v2-5 3.2.1 节，v2.3 新增；v2.7 新增 disconnected）
 *
 * 标记"正常收尾"与"异常中断"。runSession() 开场先插入 in_progress 记录；
 * 循环正常结束时更新为 completed；catch 到异常时更新为 interrupted 并记录
 * errorMessage，再把异常上抛给 PrivateChatWorker。
 *
 * status 取值：
 * - "in_progress"：会话开场时的初始状态，尚未结束
 * - "completed"：正常收尾（wrap_up 或达到轮数上限），未发生角色下线
 * - "interrupted"：执行过程中抛出异常（含协程被取消），errorMessage 记录原因
 * - "disconnected"：角色触发 [[DECISION:DISCONNECT]] 主动下线导致会话结束
 *   （v2.7 新增，方案 v1.5 6.4 节）。这不是"异常"（没有 errorMessage，
 *   没有抛异常），是角色扮演层面的正常结局，但也不是"双方自然聊完"的
 *   completed——UI/导出器需要能区分这三种情形，不能把角色下线误记为
 *   completed（会让 owner 以为对话是正常收尾的）。
 */
@Entity(tableName = "private_chat_sessions")
data class PrivateChatSessionEntity(
    @PrimaryKey val sessionId: String,
    val pairId: String,
    val startedAt: Long,
    val status: String,        // "in_progress" | "completed" | "interrupted" | "disconnected"
    val turnCount: Int = 0,
    val errorMessage: String? = null,
)
