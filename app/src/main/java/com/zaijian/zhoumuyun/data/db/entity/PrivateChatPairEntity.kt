package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 角色间私聊配对配置（方案_角色间私聊_v2-5 3.1 节）
 *
 * 逐配对开关，默认关闭。每对角色的每日上限/冷却时间/轮数上限均在此表配置。
 * pairId 规范化生成：两个 characterId 按数值排序后拼接，如 "1_7"。
 */
@Entity(
    tableName = "private_chat_pairs",
    indices = [Index(value = ["characterIdA", "characterIdB"], unique = true)],
)
data class PrivateChatPairEntity(
    @PrimaryKey val pairId: String,
    val characterIdA: Int,
    val characterIdB: Int,
    val enabled: Boolean = false,
    val maxTurnsPerSession: Int = 6,
    val maxSessionsPerDay: Int = 8,
    val cooldownMinutes: Int = 10,
    val sessionsUsedToday: Int = 0,
    val usedTodayResetAt: Long,
    val lastSessionAt: Long = 0,

    // ── v75→v76 新增：角色忠诚锁定·角色自主下线状态（方案 v1.5 第 6.4 节）─────
    // "ACTIVE" | "DISCONNECTED_BY_CHARACTER"。后者表示该 pair 中被追求的角色
    // 自主选择中断对话（[[DECISION:DISCONNECT]] 触发），runSession 对该 pair
    // 静默跳过生成（A 视角只是"发了没回"，不暴露明确的下线状态提示）。
    // 仅 owner 手动操作可改回 ACTIVE。默认 ACTIVE，向后兼容。
    val characterDisconnectState: String = "ACTIVE",
)
