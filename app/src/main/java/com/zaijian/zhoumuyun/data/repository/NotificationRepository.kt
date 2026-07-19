package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.db.dao.NotificationReadStateDao
import com.zaijian.zhoumuyun.data.db.entity.NotificationReadStateEntity
import com.zaijian.zhoumuyun.data.model.BriefingAttentionItem
import com.zaijian.zhoumuyun.data.model.BriefingCharacterEntry
import com.zaijian.zhoumuyun.data.model.BriefingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// ─────────────────────────────────────────────────────────────
//  NotificationRepository — 通知中心聚合层
//  通知中心设计方案 第五节。
//
//  职责：不重新聚合业务数据（复用 BriefingRepository.generateBriefing()
//  产出的 BriefingData），只做两件事：
//    1. 把 BriefingData 拆成"需要关注"/"好消息"两个区块，供通知中心
//       页面展示（简报页只用了 attentionItems，好消息这块字段
//       BriefingData 里本来就有，只是简报页没用）。
//    2. 已读状态的读写/角标计数/孤儿数据清理。
// ─────────────────────────────────────────────────────────────

/** 好消息条目——通知中心专用，BriefingAttentionItem 没有对应的正面语义分支，独立建模。 */
sealed class GoodNewsItem {
    data class MilestoneRepaired(val entry: BriefingCharacterEntry) : GoodNewsItem()
    data class HighCompetitionScore(val entry: BriefingCharacterEntry, val score: Float) : GoodNewsItem()
}

class NotificationRepository(
    private val readStateDao: NotificationReadStateDao,
) {
    /**
     * 竞赛高分口径（产品侧拍板，2026-07-18）：不用固定分数阈值，改为
     * "参赛者内部排名前 30%"。
     *
     * 步骤：
     *   1. 先剔除 competitionScore == null（本周期没参赛）的角色，
     *      不让它们进候选池、不参与分母计算。
     *   2. 剩下"这次真的打了比赛"的角色按分数从高到低排名。
     *   3. 取前 30%（向上取整，至少 1 人），不设最低分数线——
     *      哪怕参赛者整体分数都不高，排名前 30% 依然算好消息，
     *      因为好消息区块的语义是"相对同批参赛者表现值得夸"，
     *      不是"绝对分数达标"。
     *
     * 这样即使参赛人数很少（例如只有 1-2 人参赛），也不会出现
     * "全员都进前 30%"或"没参赛角色拉高分母导致排名虚高"的边界怪相，
     * 因为没参赛的角色从一开始就不在分母里。
     */
    private val topPercentThreshold = 0.3

    /** 从 BriefingData 里提取"好消息"区块，不做二次聚合，只做筛选和分组。 */
    fun buildGoodNewsItems(data: BriefingData): List<GoodNewsItem> {
        val items = mutableListOf<GoodNewsItem>()

        data.characters.forEach { entry ->
            if (entry.hasRecentGoodMilestone) {
                items += GoodNewsItem.MilestoneRepaired(entry)
            }
        }

        // 只在有实际参赛记录的角色里排名：先剔除 null，再排序取前 30%。
        val participants = data.characters
            .filter { it.competitionScore != null }
            .sortedByDescending { it.competitionScore }

        if (participants.isNotEmpty()) {
            val cutoff = kotlin.math.ceil(participants.size * topPercentThreshold)
                .toInt()
                .coerceAtLeast(1)
            participants.take(cutoff).forEach { entry ->
                items += GoodNewsItem.HighCompetitionScore(entry, entry.competitionScore!!)
            }
        }

        return items
    }

    /**
     * itemKey 生成规则集中在这一处，NotificationReadStateDao 的调用方
     * 和 ViewModel 都必须走这个函数，不能各自拼字符串——否则同一条目
     * 在写入已读表和读取比对时用了不同格式的 key，会导致已读状态永远
     * 对不上（标记了也不生效）。
     */
    fun buildItemKey(item: BriefingAttentionItem): String = when (item) {
        is BriefingAttentionItem.NoContact ->
            "no_contact:${item.character.id}"
        is BriefingAttentionItem.NeverContacted ->
            "never_contacted:${item.character.id}"
        is BriefingAttentionItem.Pregnancy ->
            "pregnancy:${item.character.id}"
        is BriefingAttentionItem.Tension ->
            "tension:${item.fromId}-${item.toId}"
        is BriefingAttentionItem.RelationWorsened ->
            // milestoneId 而非 fromId-toId：同一对角色在同一窗口内可能发生
            // 多次独立恶化事件，用角色对拼 key 会让两条不同事件共享同一个
            // itemKey，标记其一已读会连带另一条一起变已读（深度检查发现，
            // 2026-07-18 修复）。
            "worsened:${item.milestoneId}"
    }

    suspend fun markRead(item: BriefingAttentionItem) {
        readStateDao.markRead(
            NotificationReadStateEntity(
                itemKey = buildItemKey(item),
                readAt  = System.currentTimeMillis(),
            )
        )
    }

    fun observeReadKeys(): Flow<Set<String>> =
        readStateDao.observeAllReadKeys().map { it.toSet() }

    /**
     * 清理孤儿已读记录。传入本次"需要关注"区块实际产出的全部 itemKey，
     * 不在其中的旧已读记录会被删除。
     *
     * 注意：stillValidKeys 为空列表（即本次没有任何需要关注的条目）时，
     * DAO 层的 NOT IN 空集合会删除全表已读记录——这正是期望行为
     * （所有问题都已解决，已读表清空是对的），不是 bug，调用方不需要
     * 对空列表做特判。
     */
    suspend fun pruneStaleReadState(stillValidKeys: List<String>) {
        readStateDao.deleteNotIn(stillValidKeys)
    }
}