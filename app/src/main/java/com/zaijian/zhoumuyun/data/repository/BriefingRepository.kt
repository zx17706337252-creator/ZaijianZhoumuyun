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
            if ((entry.daysSinceContact ?: 0) >= noContactThresholdDays) {
                items += BriefingAttentionItem.NoContact(entry.character, entry.daysSinceContact!!)
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
        return items
    }
}
