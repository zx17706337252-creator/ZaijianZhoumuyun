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
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.CyclePhase
import com.zaijian.zhoumuyun.data.model.DaughterDataException
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.model.MenstrualCycleState
import com.zaijian.zhoumuyun.data.model.PregnancyState
import com.zaijian.zhoumuyun.domain.RelationshipEngine
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

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

// A6-2 修复: 角标轻量路径原先用 Triple<CharacterConfig, Long?, Boolean>
// 只装得下 character/lastMessageAt/isPregnant 三样，cyclePhase 装不进去。
// 改为具名数据类，多带一个 cyclePhase 字段，可读性也优于 Triple 的 _1/_2/_3。
private data class AttentionLightEntry(
    val character: CharacterConfig,
    val lastMessageAt: Long?,
    val isPregnant: Boolean,
    val cyclePhase: CyclePhase,
)

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
        // B4审查报告【序号2】修复：原先只过滤 REPAIRED，STAGE_TRANSITION（关系阶段跃升，
        // 同属正向"好消息"）不计入，导致角色卡蜡封角标与通知中心"好消息"区都不显示该里程碑。
        // 两者都是值得展示的正向里程碑，一并计入。
        val repairedCharIds = milestonesSince
            .filter {
                it.direction == RelationshipMilestoneDirection.REPAIRED.name ||
                    it.direction == RelationshipMilestoneDirection.STAGE_TRANSITION.name
            }
            .map { it.toId }
            .toSet()

        // ── 竞赛评分：先取本周期完成的轮次，再按角色反查条目 ──────
        // 第8窗口问题2修复：原先对 completedRounds 逐轮调用 getAllForRound()，
        // 轮次越多查询次数越多（N+1）。改为一次批量查询后在内存按 characterId 分组。
        val completedRounds = competitionRoundDao.getCompletedSince(since)
        val entriesByCharacter = mutableMapOf<Int, MutableList<CompetitionEntryEntity>>()
        if (completedRounds.isNotEmpty()) {
            val allEntries = competitionEntryDao.getAllForRounds(completedRounds.map { it.id })
            allEntries.forEach { entry ->
                entriesByCharacter.getOrPut(entry.characterId) { mutableListOf() }.add(entry)
            }
        }

        // W14 修复：按角色收集部分失败的错误信息
        val partialErrors = mutableListOf<String>()

        val perCharacter = characters.mapNotNull { config ->
            try {
                val relation = relationByCharId[config.id.toString()]
                val lastMessageAt = messageDao.getLastMessageAt(config.id)
                val pregnancy = pregnancyRepo.getPregnancy(config.id)
                val cyclePhase = menstrualCycleRepo.get(config.id)
                    .currentPhase(isPregnant = pregnancy.isPregnant, now = now)
                val completedTasks = taskDao.getCompletedByCharacterSince(config.id, since)
                val projects = projectDao.getActiveProjectsForCharacter(config.id)
                val entries = entriesByCharacter[config.id].orEmpty()
                val avgScore = entries.mapNotNull { it.compositeScore }
                    .filter { it > 0f }
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.w("BriefingRepository", "characterId=${config.id} 简报聚合失败，跳过", e)
                partialErrors += "角色「${config.nickname ?: config.name}」(${config.id}) 聚合失败：${e.message?.take(60) ?: "未知错误"}"
                null // 单个角色失败不影响其他角色
            }
        }

        val attentionItems = buildAttentionList(perCharacter, interMatrix, worsenedMilestones).toMutableList()
        // 叙事类：对话引用 + 约定事项（帧02/19「需要关注」补叙事，不只健康/关系状态）。
        // 限量：各自至多取 3 条（按最近时间），避免每个角色都塞一条把列表刷爆。
        // 对话引用 = 该角色最近一条有内容的用户消息片段；约定事项 = 进行中(RUNNING)任务。
        val narratives = buildNarrativeItems(perCharacter)
        val quoteItems = narratives.first.sortedByDescending { it.sourceMessageAt }.take(3)
        val agreementItems = narratives.second.take(3)
        attentionItems += quoteItems
        attentionItems += agreementItems
        val ranking = perCharacter.sortedByDescending { it.relation?.affection ?: 0 }

        return BriefingData(
            periodStart      = since,
            periodEnd        = now,
            characters       = perCharacter,
            attentionItems   = attentionItems,
            affectionRanking = ranking,
            partialErrors    = partialErrors,
        )
    }

    // ─────────────────────────────────────────────────────────────
    //  角标 Flow 化改造第3步：observeAttentionItems(since)
    //
    //  背景：完整方案C（generateBriefing() 整体 Flow 化，覆盖简报页/通知
    //  中心/角标三处调用方）经排查后确认工程量和收益不成比例——通知中心
    //  和简报页的产品语义本来就是"一次性查询"，只有角标（
    //  NotificationBadgeViewModel）真正需要实时更新。因此只做这一条窄
    //  路线：不产出完整 BriefingData，只产出角标需要的 attentionItems。
    //
    //  与 generateBriefing() 的差异：
    //  - 不查任务完成数、项目名称、竞赛评分——buildAttentionList() 本来
    //    就没用到这三样，角标场景可以完全跳过。
    //  - 不设计 partialErrors。单个角色数据损坏或子查询出错时直接跳过/
    //    兜底默认值，不上抛、不收集错误文案——角标场景下"这个人数据有
    //    问题就不算她"比"报错给用户看"更合适，也避免了 partialErrors
    //    在 Flow 语境（combine 链路）下的重新设计。
    //  - generateBriefing() 本身一行未改，两条路径完全独立、互不影响。
    // ─────────────────────────────────────────────────────────────

    /**
     * 监听「需要关注」条目列表，供 NotificationBadgeViewModel 实时订阅角标。
     * 关系数据、怀孕状态、最后联系时间、跨角色紧张度、关系恶化事件任一
     * 变化都会触发这里重新计算。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeAttentionItems(since: Long): Flow<List<BriefingAttentionItem>> {
        val charactersFlow = daughterCharacterRepo.observeAllCharacterConfigs()
        val milestonesFlow = relationshipMilestoneDao.observeAllSince(since)
            .map { list -> list.filter { it.direction == RelationshipMilestoneDirection.WORSENED.name } }
        // 批次3 3-3修复：matrixFlow 原为裸 DAO 查询 relationshipDao.observeAllInterCharacter()，
        // 不按 characters 列表过滤。女儿数据损坏被剔出 characters 时，她的高 tension 关系行
        // 仍进角标但不进通知中心（generateBriefing 用 getInterCharacterMatrix(chars) 过滤），
        // 导致角标持续误报。改为 flatMapLatest(charactersFlow)，只保留 fromId/toId 都在
        // characters 列表里的关系行，与 generateBriefing 的角色范围口径对齐。
        val matrixFlow = charactersFlow.flatMapLatest { characters ->
            if (characters.isEmpty()) {
                // 类型推断修复：emptyList() 的类型参数 T 只出现在返回类型里，
                // 没有输入参数可供推断；这里又没有 val 类型标注兜底期望类型，
                // 与 else 分支的 Flow<List<RelationshipEntity>> 互相统一时
                // 编译器无法确定 T，需要显式指定类型参数。
                flowOf(emptyList<RelationshipEntity>())
            } else {
                // 修复：RelationshipEntity.fromId/toId 是 String 类型的角色ID
                // （与本文件107行 relationByCharId[config.id.toString()] 同一口径），
                // 原先 validIds 是 Set<Int>（来自 characters.map{it.id}），
                // 与 String 类型的 fromId/toId 比较类型不匹配，需转成 String。
                val validIds = characters.map { it.id.toString() }.toSet()
                relationshipDao.observeAllInterCharacter()
                    .map { list -> list.filter { it.fromId in validIds && it.toId in validIds } }
            }
        }

        val perCharacterFlow: Flow<List<AttentionLightEntry>> =
            charactersFlow.flatMapLatest { characters ->
                if (characters.isEmpty()) {
                    // 同上，显式指定类型参数，不依赖 val 标注隔层传播期望类型
                    flowOf(emptyList<AttentionLightEntry>())
                } else {
                    val perCharacterFlows = characters.map { config ->
                        // A6-2 修复: 把 menstrualCycleRepo.observe() 并入 perCharacterFlow，
                        // 让周期阶段变化也能驱动角标实时刷新（此前只合了 lastMessageAt
                        // 和 isPregnant，排卵期/经期变化角标不会更新）。menstrualCycleRepo
                        // 已是本类构造参数，与同文件 pregnancyRepo/messageDao 同口径访问，
                        // 不走 AppContainer.instance。三路 combine 后用 currentPhase() 把
                        // 周期状态折算成 cyclePhase 一并装进 AttentionLightEntry。
                        combine(
                            messageDao.observeLastMessageAt(config.id)
                                .catch { e ->
                                    ZLog.w("BriefingRepository", "characterId=${config.id} observeLastMessageAt 失败，兜底为 null", e)
                                    emit(null)
                                },
                            pregnancyRepo.observePregnancy(config.id)
                                .catch { e ->
                                    ZLog.w("BriefingRepository", "characterId=${config.id} observePregnancy 失败，兜底为未怀孕", e)
                                    emit(PregnancyState(characterId = config.id))
                                },
                            menstrualCycleRepo.observe(config.id)
                                .catch { e ->
                                    ZLog.w("BriefingRepository", "characterId=${config.id} observe 周期失败，兜底为默认 SAFE", e)
                                    emit(MenstrualCycleState(characterId = config.id))
                                },
                        ) { lastMessageAt, pregnancy, cycleState ->
                            AttentionLightEntry(
                                character     = config,
                                lastMessageAt = lastMessageAt,
                                isPregnant    = pregnancy.isPregnant,
                                cyclePhase    = cycleState.currentPhase(isPregnant = pregnancy.isPregnant, now = System.currentTimeMillis()),
                            )
                        }
                    }
                    combine(perCharacterFlows) { it.toList() }
                }
            }

        return combine(perCharacterFlow, milestonesFlow, matrixFlow) { perChar, worsened, matrix ->
            buildAttentionListLight(perChar, matrix, worsened, now = System.currentTimeMillis())
        }
    }

    /**
     * buildAttentionList() 的轻量版：角标场景只需要 character/daysSinceContact/
     * isPregnant/cyclePhase 四样，不需要凑出完整 BriefingCharacterEntry（那样会带着
     * 一堆无意义的默认值——completedTaskCount=0、projectNames=空列表等，容易让人
     * 误以为那是真实数据）。排序规则复用 attentionItemComparator，与
     * buildAttentionList() 保持完全一致的输出顺序。
     *
     * interMatrix 这里直接传 List，不像 buildAttentionList() 接收 Map——
     * 角标场景不需要按 key 反查，直接遍历即可，省去构造 Map 的一步。
     * isInterCharacter=1 的过滤已经在 RelationshipDao.observeAllInterCharacter()
     * 的 SQL 里做了（见第1步新增方法），这里不需要再过滤一次。
     *
     * 批次3 3-3修复：interMatrix 在 observeAttentionItems() 里已按 characters
     * 列表过滤（fromId/toId 都在 validIds 里），与 generateBriefing() 调用
     * relationshipEngine.getInterCharacterMatrix(characters.map{it.id}) 的口径
     * 对齐。原先不过滤会导致女儿数据损坏时角标持续误报。
     *
     * A6-2 修复: 入参由 Triple<CharacterConfig, Long?, Boolean> 改为
     * AttentionLightEntry，多带 cyclePhase，使排卵期/经期也能进入角标判定。
     */
    private fun buildAttentionListLight(
        entries: List<AttentionLightEntry>,
        interMatrix: List<RelationshipEntity>,
        worsened: List<RelationshipMilestoneEntity>,
        now: Long,
    ): List<BriefingAttentionItem> {
        val items = mutableListOf<BriefingAttentionItem>()

        // 阈值与 buildAttentionList() 保持一致，不重复定义为共享常量的原因见
        // 该函数原有注释——阈值为建议默认值，可调，两处各自集中方便找。
        val noContactThresholdDays = 7L
        val tensionThreshold       = 60

        entries.forEach { (character, lastMessageAt, isPregnant, cyclePhase) ->
            val days = lastMessageAt?.let { (now - it) / 86_400_000L }
            if (days == null) {
                items += BriefingAttentionItem.NeverContacted(character)
            } else if (days >= noContactThresholdDays) {
                items += BriefingAttentionItem.NoContact(character, days)
            }
            if (isPregnant) {
                items += BriefingAttentionItem.Pregnancy(character)
            }
            // A6-2 修复: 角标场景同样补充排卵期/经期判定，与 buildAttentionList()
            // 保持一致，确保两条路径产出的 attentionItems 口径相同。
            if (cyclePhase == CyclePhase.FERTILE) {
                items += BriefingAttentionItem.FertileAttention(character.id, character.name)
            } else if (cyclePhase == CyclePhase.MENSTRUAL) {
                items += BriefingAttentionItem.MenstrualAttention(character.id, character.name)
            }
        }
        // P1-20 修复：buildAttentionList() 接收的是 getInterCharacterMatrix()
        // 产出的 Map<relKey, RelationshipEntity>，同一对角色天然只保留一条；
        // 这里接收的却是原始 List，同一对角色若因历史数据（M7 归一化之前）
        // 同时存在 fromId-toId 和 toId-fromId 两条记录，会被各自判一次
        // tension，产出两条 Tension item——角标未读数（数这份 List 的结果）
        // 因此比通知中心实际展示条目（数 Map 的结果）多1，且多出的那条
        // 因为跟另一条共享同一个 buildItemKey，标不了已读。按 relKey 去重，
        // 与 buildAttentionList() 的 Map 语义对齐。
        interMatrix
            .distinctBy { RelationshipEngine.relKey(it.fromId.toInt(), it.toId.toInt()) }
            .filter { it.tension >= tensionThreshold }
            .forEach { rel ->
                items += BriefingAttentionItem.Tension(rel.fromId, rel.toId, rel.tension)
            }
        worsened.forEach { m ->
            items += BriefingAttentionItem.RelationWorsened(m.fromId, m.toId, m.description, m.id)
        }
        return items.sortedWith(attentionItemComparator)
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
            // A6-1 修复: 根据 cyclePhase 补充排卵期/经期两类需要关注的条目，
            // 与 isPregnant 同级判断。怀孕时 cyclePhase 为 PREGNANT，既不会
            // 命中 FERTILE 也不会命中 MENSTRUAL，与上面 Pregnancy 分支互不冲突。
            if (entry.cyclePhase == CyclePhase.FERTILE) {
                items += BriefingAttentionItem.FertileAttention(entry.character.id, entry.character.name)
            } else if (entry.cyclePhase == CyclePhase.MENSTRUAL) {
                items += BriefingAttentionItem.MenstrualAttention(entry.character.id, entry.character.name)
            }
        }
        interMatrix.values.filter { it.tension >= tensionThreshold }.forEach { rel ->
            items += BriefingAttentionItem.Tension(rel.fromId, rel.toId, rel.tension)
        }
        worsened.forEach { m ->
            items += BriefingAttentionItem.RelationWorsened(m.fromId, m.toId, m.description, m.id)
        }
        return items.sortedWith(attentionItemComparator)
    }

    /**
     * 叙事类「需要关注」条目（帧02/19）：对话引用 + 约定事项。
     *
     * 对话引用（QuoteReference）= 该角色最近一条有内容的用户消息片段，让"需要关注"
     * 带上对话的温度；约定事项（AgreementDue）= 该角色进行中(RUNNING)的任务标题，
     * 作为"约定/待办"关注点。TaskEntity 无截止日期字段，故不编造"今天验收"这类
     * 时间措辞，只安全透出真实存在的任务标题。
     *
     * 生成逻辑独立于 buildAttentionList()，不改变原有健康/关系条目的判定；
     * 调用方在 buildAttentionList() 结果上附加并限量（各取 3 条）。
     */
    private suspend fun buildNarrativeItems(
        entries: List<BriefingCharacterEntry>,
    ): Pair<List<BriefingAttentionItem.QuoteReference>, List<BriefingAttentionItem.AgreementDue>> {
        val quotes = mutableListOf<BriefingAttentionItem.QuoteReference>()
        val agreements = mutableListOf<BriefingAttentionItem.AgreementDue>()
        entries.forEach { entry ->
            val config = entry.character
            try {
                val recentMsg = messageDao.getByCharacter(config.id, limit = 1).firstOrNull()
                if (recentMsg != null && recentMsg.content.isNotBlank()) {
                    val snippet = recentMsg.content.trim().replace(Regex("\\s+"), " ").take(24)
                    quotes += BriefingAttentionItem.QuoteReference(config, snippet, recentMsg.createdAt)
                }
                val runningTask = taskDao.getByCharacter(config.id, limit = 10)
                    .firstOrNull { it.status == com.zaijian.zhoumuyun.data.db.entity.TaskStatus.RUNNING.name }
                if (runningTask != null) {
                    agreements += BriefingAttentionItem.AgreementDue(config, runningTask.title)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.w("BriefingRepository", "characterId=${config.id} 叙事聚合失败，跳过", e)
            }
        }
        return quotes to agreements
    }

    /**
     * 「需要关注」排序规则（v2.1 补充，原方案未规定顺序，仅明确了
     * NeverContacted 语义上比 NoContact 更紧急这一条相对关系）。
     *
     * 一级：按类型分组，优先级从高到低：
     *   1. Pregnancy        —— 健康相关，风险最高，永远置顶
     *   2. FertileAttention —— 排卵期/易孕窗口，与怀孕同属周期健康维度，紧随其后
     *   3. MenstrualAttention —— 经期，需要关怀，优先级略低于排卵期
     *   4. RelationWorsened —— 已经发生的关系恶化事件，属于"已出问题"
     *   5. Tension          —— 关系紧张但尚未恶化，属于"有风险但未爆发"
     *   6. NeverContacted   —— 从未联系，文档明确比 NoContact 更紧急
     *   7. NoContact        —— 久未联系，组内再按天数降序
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
                is BriefingAttentionItem.Pregnancy          -> 0
                is BriefingAttentionItem.FertileAttention   -> 1
                is BriefingAttentionItem.MenstrualAttention -> 2
                is BriefingAttentionItem.RelationWorsened   -> 3
                is BriefingAttentionItem.Tension            -> 4
                is BriefingAttentionItem.NeverContacted     -> 5
                is BriefingAttentionItem.NoContact          -> 6
                // 叙事类：排在健康/关系条目之后，不抢占关键关注位。
                is BriefingAttentionItem.QuoteReference     -> 7
                is BriefingAttentionItem.AgreementDue       -> 8
            }
        }.thenByDescending { item ->
            when (item) {
                is BriefingAttentionItem.Tension            -> item.tension.toLong()
                is BriefingAttentionItem.NoContact          -> item.days
                is BriefingAttentionItem.Pregnancy          -> 0L
                is BriefingAttentionItem.FertileAttention   -> 0L
                is BriefingAttentionItem.MenstrualAttention -> 0L
                is BriefingAttentionItem.RelationWorsened   -> 0L
                is BriefingAttentionItem.NeverContacted     -> 0L
                is BriefingAttentionItem.QuoteReference     -> 0L
                is BriefingAttentionItem.AgreementDue       -> 0L
            }
        }
}
