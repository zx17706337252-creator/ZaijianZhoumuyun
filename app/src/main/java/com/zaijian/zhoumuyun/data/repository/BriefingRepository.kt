package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.db.dao.CompetitionEntryDao
import com.zaijian.zhoumuyun.data.db.dao.CompetitionRoundDao
import com.zaijian.zhoumuyun.data.db.dao.MessageDao
import com.zaijian.zhoumuyun.data.db.dao.ProjectDao
import com.zaijian.zhoumuyun.data.db.dao.RelationshipDao
import com.zaijian.zhoumuyun.data.db.dao.RelationshipMilestoneDao
import com.zaijian.zhoumuyun.data.db.dao.TaskDao
import com.zaijian.zhoumuyun.data.db.entity.CompetitionEntryEntity
import com.zaijian.zhoumuyun.data.db.entity.RelationshipEntity
import com.zaijian.zhoumuyun.data.db.entity.RelationshipMilestoneDirection
import com.zaijian.zhoumuyun.data.db.entity.RelationshipMilestoneEntity
import com.zaijian.zhoumuyun.data.model.BriefingAttentionItem
import com.zaijian.zhoumuyun.data.model.BriefingCharacterEntry
import com.zaijian.zhoumuyun.data.model.BriefingData
import com.zaijian.zhoumuyun.data.model.DaughterDataException
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.domain.RelationshipEngine
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.flow.first

// ─────────────────────────────────────────────────────────────
//  BriefingRepository — 离线简报聚合层（整合方案 v2.1 4.10.2）
//
//  职责：只读聚合，把角色关系/怀孕/周期/任务/项目/竞赛评分/最后联系时间
//  这几类已有数据源，按角色 group 到一起，供 BriefingViewModel 一次性拉取。
//  不新增数据库表，不写任何业务数据。
//
//  角色范围：9 位母亲（DefaultCharacters，已解锁）+ 全部已完成注册的
//  女儿（daughterCharacterRepo.getAllDaughterCharacterIds()）。母亲和
//  女儿最终都转成同一个 CharacterConfig 类型，聚合逻辑不需要区分两者。
// ─────────────────────────────────────────────────────────────

class BriefingRepository(
    private val relationshipDao: RelationshipDao,
    private val relationshipMilestoneDao: RelationshipMilestoneDao,
    private val relationshipEngine: RelationshipEngine,
    private val pregnancyRepo: PregnancyRepository,
    private val menstrualCycleRepo: MenstrualCycleRepository,
    private val projectDao: ProjectDao,
    private val taskDao: TaskDao,
    private val messageDao: MessageDao,
    private val competitionRoundDao: CompetitionRoundDao,
    private val competitionEntryDao: CompetitionEntryDao,
    private val daughterCharacterRepo: DaughterCharacterRepository,
) {
    suspend fun generateBriefing(since: Long, now: Long = System.currentTimeMillis()): BriefingData {
        // ── 角色范围：9 位母亲 + 全部已注册女儿 ──────────────────
        val mothers = DefaultCharacters.filter { it.isUnlocked }
        val daughterIds = daughterCharacterRepo.getAllDaughterCharacterIds()
        // getCharacterConfig() 在女儿数据损坏（identityJson/stateLayerJson 解析失败或
        // 关键字段缺失）时会抛 DaughterDataException——这是 DaughterCharacterRepository
        // 有意设计的行为（宁可这一位女儿的数据报错，不能让她带着残缺人格说话，
        // 见该文件注释）。但那个原则是针对"这一条消息"的隔离粒度；这里是批量
        // 生成全体角色的简报，一位女儿数据损坏不该连累其余八九位角色的简报也生不出来，
        // 因此在此处按角色单独 try-catch，跳过损坏的一位，其余正常返回。
        val daughters = daughterIds.mapNotNull { id ->
            try {
                daughterCharacterRepo.getCharacterConfig(id)
            } catch (e: DaughterDataException) {
                ZLog.w("BriefingRepository", "daughter characterId=$id data corrupted, skip from briefing", e)
                null
            }
        }
        val characters = mothers + daughters

        // ── 关系数据：一次性 collect Flow 首个值 ─────────────────
        val relations = relationshipDao.observeFrom("user").first()
        val relationByCharId = relations.associateBy { it.toId }

        val interMatrix = relationshipEngine.getInterCharacterMatrix(characters.map { it.id })

        val milestonesSince = relationshipMilestoneDao.getAllSince(since)
        val worsenedMilestones = milestonesSince
            .filter { it.direction == RelationshipMilestoneDirection.WORSENED.name }
        val repairedCharIds = milestonesSince
            .filter { it.direction == RelationshipMilestoneDirection.REPAIRED.name }
            .map { it.toId }
            .toSet()

        // ── 竞赛评分：先取本周期完成的轮次，再按角色反查条目 ──────
        val completedRounds = competitionRoundDao.getCompletedSince(since)
        val entriesByCharacter = mutableMapOf<Int, MutableList<CompetitionEntryEntity>>()
        completedRounds.forEach { round ->
            competitionEntryDao.getAllForRound(round.id).forEach { entry ->
                entriesByCharacter.getOrPut(entry.characterId) { mutableListOf() }.add(entry)
            }
        }

        val perCharacter = characters.map { config ->
            val relation = relationByCharId[config.id.toString()]
            val lastMessageAt = messageDao.getLastMessageAt(config.id)
            val pregnancy = pregnancyRepo.getPregnancy(config.id)
            val cyclePhase = menstrualCycleRepo.get(config.id)
                .currentPhase(isPregnant = pregnancy.isPregnant, now = now)
            val completedTasks = taskDao.getCompletedByCharacterSince(config.id, since)
            val projects = projectDao.getActiveProjectsForCharacter(config.id.toString())
            val entries = entriesByCharacter[config.id].orEmpty()
            val avgScore = entries.mapNotNull { it.compositeScore }
                .takeIf { it.isNotEmpty() }
                ?.average()
                ?.toFloat()

            BriefingCharacterEntry(
                character              = config,
                relation               = relation,
                lastMessageAt          = lastMessageAt,
                daysSinceContact       = lastMessageAt?.let { (now - it) / 86_400_000L },
                isPregnant             = pregnancy.isPregnant,
                cyclePhase             = cyclePhase,
                completedTaskCount     = completedTasks.size,
                projectNames           = projects.map { it.title },
                competitionScore       = avgScore,
                hasRecentGoodMilestone = config.id.toString() in repairedCharIds,
            )
        }

        val attentionItems = buildAttentionList(perCharacter, interMatrix, worsenedMilestones)
        val ranking = perCharacter.sortedByDescending { it.relation?.affection ?: 0 }

        return BriefingData(
            periodStart      = since,
            periodEnd        = now,
            characters       = perCharacter,
            attentionItems   = attentionItems,
            affectionRanking = ranking,
        )
    }

    /** 「需要关注」判定逻辑集中在这一个函数，方便后续单独调阈值，不用满文件找散落的 if。 */
    private fun buildAttentionList(
        entries: List<BriefingCharacterEntry>,
        interMatrix: Map<String, RelationshipEntity>,
        worsened: List<RelationshipMilestoneEntity>,
    ): List<BriefingAttentionItem> {
        val items = mutableListOf<BriefingAttentionItem>()

        // 阈值为建议默认值，可调，集中写在这里方便找：
        val noContactThresholdDays = 7L
        val tensionThreshold       = 60

        entries.forEach { entry ->
            val days = entry.daysSinceContact
            if (days == null) {
                // 从未联系过——比"N天没联系"更需要关注，不能用 ?: 0 兜底成
                // "0天没联系"（那样反而会被 >= 7 的判断排除在外，见本函数
                // 历史缺陷记录）。用独立的 NeverContacted 分支表达，不塞进
                // NoContact.days 编一个不成立的数字。
                items += BriefingAttentionItem.NeverContacted(entry.character)
            } else if (days >= noContactThresholdDays) {
                items += BriefingAttentionItem.NoContact(entry.character, days)
            }
            if (entry.isPregnant) {
                items += BriefingAttentionItem.Pregnancy(entry.character)
            }
        }
        interMatrix.values.filter { it.tension >= tensionThreshold }.forEach { rel ->
            items += BriefingAttentionItem.Tension(rel.fromId, rel.toId, rel.tension)
        }
        worsened.forEach { m ->
            items += BriefingAttentionItem.RelationWorsened(m.fromId, m.toId, m.description)
        }
        return items.sortedWith(attentionItemComparator)
    }

    /**
     * 「需要关注」排序规则（v2.1 补充，原方案未规定顺序，仅明确了
     * NeverContacted 语义上比 NoContact 更紧急这一条相对关系）。
     *
     * 一级：按类型分组，优先级从高到低：
     *   1. Pregnancy        —— 健康相关，风险最高，永远置顶
     *   2. RelationWorsened —— 已经发生的关系恶化事件，属于"已出问题"
     *   3. Tension          —— 关系紧张但尚未恶化，属于"有风险但未爆发"
     *   4. NeverContacted   —— 从未联系，文档明确比 NoContact 更紧急
     *   5. NoContact        —— 久未联系，组内再按天数降序
     *
     * 二级：同类型内部再排序，避免退化回原始遍历顺序：
     *   - Tension 按 tension 数值降序（越紧张越靠前）
     *   - NoContact 按 days 降序（越久没联系越靠前）
     *   - 其余类型没有可比的强度字段，二级键恒定，保留原始相对顺序
     *     （sortedWith 是稳定排序，不会打乱同优先级内的原始顺序）
     */
    private val attentionItemComparator: Comparator<BriefingAttentionItem> =
        compareBy<BriefingAttentionItem> { item ->
            when (item) {
                is BriefingAttentionItem.Pregnancy        -> 0
                is BriefingAttentionItem.RelationWorsened -> 1
                is BriefingAttentionItem.Tension          -> 2
                is BriefingAttentionItem.NeverContacted   -> 3
                is BriefingAttentionItem.NoContact        -> 4
            }
        }.thenByDescending { item ->
            when (item) {
                is BriefingAttentionItem.Tension          -> item.tension.toLong()
                is BriefingAttentionItem.NoContact        -> item.days
                is BriefingAttentionItem.Pregnancy        -> 0L
                is BriefingAttentionItem.RelationWorsened -> 0L
                is BriefingAttentionItem.NeverContacted   -> 0L
            }
        }
}
