package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 角色间私聊配对配置（方案_角色间私聊_v2-5 3.1 节）
 *
 * 逐配对开关，默认开启。每对角色的每日上限/冷却时间/轮数上限均在此表配置。
 * pairId 规范化生成：两个 characterId 按数值排序后拼接，如 "1_7"。
 *
 * 修复（通道默认开启需求）：此前默认值为 false（注释也写"默认关闭"），与实际
 * 建档行为不一致——PrivateChatViewModel.createPair 和 PrivateChatSendTool.execute
 * 两处唯一的建档入口都显式传 enabled = true 顶掉了这个默认值，false 从未在生产
 * 代码路径中真正生效过，纯粹是文档与默认值的隐性矛盾。改为 true 让默认值与
 * 实际行为、以及"私聊通道默认开启"的要求一致，避免未来新增建档入口时因遗漏
 * 显式赋值而静默退回"关闭"状态。
 *
 * 角色自身没有任何路径能把 enabled 改回 false——搜遍全部调用点，
 * 能写 false 的只有 owner 在 PrivateChatViewModel.toggleEnabled 里手动关闭；
 * 关闭后角色仍可在 owner 授意（PrivateChatSendTool.execute，即 owner 在对话里
 * 指示角色去联系对方）下自动重新打开，两处建档/重开逻辑均显式 updateEnabled(true)。
 * 与 6.4 节角色自主下线机制（characterDisconnectState）是两回事，不要混淆：
 * 那是角色在私聊会话内被追求到超出承受阈值时的自我保护反应，通道本身
 * （enabled）从未被那套机制关闭过。
 */
@Entity(
    tableName = "private_chat_pairs",
    indices = [Index(value = ["characterIdA", "characterIdB"], unique = true)],
)
data class PrivateChatPairEntity(
    @PrimaryKey val pairId: String,
    val characterIdA: Int,
    val characterIdB: Int,
    val enabled: Boolean = true,
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
