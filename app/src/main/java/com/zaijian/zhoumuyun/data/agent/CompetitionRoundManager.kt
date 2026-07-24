package com.zaijian.zhoumuyun.data.agent

import androidx.room.withTransaction
import com.zaijian.zhoumuyun.data.provider.chatSyncWithRetry
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.util.ZLog
import com.zaijian.zhoumuyun.data.db.entity.CompetitionEntryEntity
import com.zaijian.zhoumuyun.data.db.entity.CompetitionRoundEntity
import com.zaijian.zhoumuyun.data.db.entity.JudgeProfileEntity
import com.zaijian.zhoumuyun.data.db.entity.RoundtableMessageEntity
import com.zaijian.zhoumuyun.data.db.entity.SystemSuggestionEntity
import com.zaijian.zhoumuyun.domain.CompetitionEngine
import com.zaijian.zhoumuyun.domain.SpecialtyEvolutionConfig
import com.zaijian.zhoumuyun.data.model.CompetitionRoundStatus.STATUS_COLLECTING
import com.zaijian.zhoumuyun.data.model.CompetitionRoundStatus.STATUS_COLLECTING_IN_PROGRESS
import com.zaijian.zhoumuyun.data.model.CompetitionRoundStatus.STATUS_COLLECTED
import com.zaijian.zhoumuyun.data.model.CompetitionRoundStatus.STATUS_JUDGING
import com.zaijian.zhoumuyun.data.model.CompetitionRoundStatus.STATUS_AWAITING_USER
import com.zaijian.zhoumuyun.data.model.CompetitionRoundStatus.STATUS_COMPLETED
import com.zaijian.zhoumuyun.data.model.CompetitionRoundStatus.STATUS_CANCELLED
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.provider.LLMConfig
import com.zaijian.zhoumuyun.data.provider.LLMMessage
import com.zaijian.zhoumuyun.data.provider.LLMProvider
import com.zaijian.zhoumuyun.data.db.entity.MemoryDomain
import com.zaijian.zhoumuyun.data.db.entity.MemoryEntity
import com.zaijian.zhoumuyun.data.repository.DaughterCharacterRepository
import com.zaijian.zhoumuyun.data.repository.MemoryRepository
import com.zaijian.zhoumuyun.data.repository.SpecialtyProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * CompetitionRoundManager — 裁判与竞争机制编排器（执行方案第4节）
 *
 * 本类是用户触发的竞赛全流程编排器，**不是定时 Worker**——
 * 调度模型与 DailyPracticeWorker 完全不同，独立新建，不往那里加任何逻辑。
 *
 * ═══════════════════════════════════════════════════════
 * 状态机（数据库 competition_rounds.status 字段驱动）：
 *
 *   startRound()         → COLLECTING
 *   ↓
 *   runCollecting()      → 对每位参赛角色调用 generateCompetitionEntry（独立 chatSync 调用），
 *                          携带各自 EvolutionPlan.content 和 SpecialtyProfile.styleNotes，
 *                          告知"按自己风格做到最好"，逐条写入 competition_entries
 *   ↓
 *   runJudging()         → 调用 CompetitionEngine.judgeRound（实名批量评审）
 *                          + 对每位参赛者调用 selfEvaluateEntry（盲评）
 *                        → status = AWAITING_USER
 *                          + 圆桌播报（裁判点评摘要 + 各自分数）
 *   ↓
 *   submitUserScore()    → 用户打分/排名/评语三种输入统一换算为 userScore
 *                          写入对应 competition_entries 行
 *   ↓
 *   finalizeRound()      → 算 compositeScore（固定权重或信任系数动态折扣）
 *                        → status = COMPLETED
 *                          + 裁判准确度记录（judge_accuracy_log）
 *                          + （奖惩反哺 · 执行方案第6节：赢家候选观察+稳定加速，输家写 COMPETITION_FEEDBACK）
 * ═══════════════════════════════════════════════════════
 *
 * 裁判懒创建（执行方案第2节）：
 *   startRound 检查该角色在本 projectDomain 是否已有 judge_profiles 记录；
 *   若没有，自动插入一条空白档案（standardNotes="" / maturityStage="EXPLORING"），
 *   不强制用户先完成训练才能开赛。
 *
 * 圆桌播报（执行方案第4节注释 / 参照 DailyPracticeWorker.postToRoundtable）：
 *   复用 RoundtableMessageDao.insert + findMostRecentRoundtableIdForSpeaker，
 *   找不到圆桌就跳过，不强制创建。
 *   所有角色名解析均在 suspend 函数内完成（避免 non-suspend 调 suspend 的编译错误）。
 *
 * 关于 postSystemAlertToRoundtable / resolveCharacterName suspend 问题（用户备注）：
 *   本类中所有播报方法均设计为 suspend 函数，resolveCharacterName 是 suspend，
 *   只在 suspend 上下文中调用，不存在 non-suspend 调 suspend 的编译错误。
 */
class CompetitionRoundManager(
    private val db: AppDatabase,
    private val competitionEngine: CompetitionEngine,
    private val daughterRepo: DaughterCharacterRepository,
    private val memoryRepo: MemoryRepository,
    private val specialtyProfileRepository: SpecialtyProfileRepository? = null,
    private val provider: LLMProvider? = null,
) {

    companion object {
        private const val TAG = "CompetitionRoundManager"

        /** 裁判连续低吻合度告警阈值（Spearman 系数低于此值视为"偏差"） */
        private const val JUDGE_ACCURACY_ALERT_THRESHOLD = 0.4f

        /** 连续几轮低于阈值才发圆桌提醒 */
        private const val JUDGE_ACCURACY_ALERT_CONSECUTIVE = 3

        // ── 竞赛轮次状态常量 ──────────────────────────────────────────────
        // (S-5) 已提取至 data.model.CompetitionRoundStatus，解决 Entity 反向依赖
        // agent 层的分层倒置问题。此处通过文件顶部 import 保留简写用法，内部逻辑不变。

        // maturityStage → 信任系数映射（执行方案第10节）
        private fun trustFactor(maturityStage: String): Float = when (maturityStage) {
            "STABLE" -> 1.0f
            "FORMING" -> 0.8f
            else -> 0.5f  // EXPLORING 或未知
        }
    }

    // P1-6-11 修复：runCollecting / runJudging / finalizeRound 三个阶段函数原先无互斥保护。
    // 场景：UI 层连续快速点击"开始评审"、或 App 从后台恢复后同时触发补偿调用，
    //   两个协程并发进入同一 roundId 的同一阶段，读到相同状态通过检查，各自执行一遍
    //   LLM 调用 + 数据库写入，产生重复评分、重复进度记录等数据污染。
    // 修复：以 roundId 为 key 维护独立 Mutex，进入阶段函数前先获取锁；
    //   不同 roundId 之间互不阻塞，同一 roundId 的并发调用完全串行化。
    // 注：状态检查（status != "COLLECTING" 等）保留不变，作为额外的幂等守卫。
    private val roundMutexes = ConcurrentHashMap<String, Mutex>()
    private fun getRoundMutex(roundId: String): Mutex =
        roundMutexes.computeIfAbsent(roundId) { Mutex() }

    // B-2/A-P0-4 修复：feedJudgeCorrectionCandidates 对 judge_profiles 表
    // 同一个 judgeProfileId 的 candidateCorrectionsJson 做 read-modify-write。
    // getRoundMutex 是按 roundId 维度加锁的，两场不同的竞赛如果同时结算、
    // 又命中同一个裁判角色（同一个 judgeProfileId），会在不同协程里并发跑进
    // feedJudgeCorrectionCandidates，各自读到旧值、各自改、后写的覆盖先写的，
    // 造成候选修正池的 lost update。与 getRoundMutex/getCandidateMutex 同款模式，
    // 按 judgeProfileId 维护独立 Mutex；不同裁判之间互不阻塞，同一裁判的并发
    // 调用完全串行化。
    private val judgeCandidateMutexes = ConcurrentHashMap<String, Mutex>()
    private fun getJudgeCandidateMutex(judgeProfileId: String): Mutex =
        judgeCandidateMutexes.computeIfAbsent(judgeProfileId) { Mutex() }

    // ─────────────────────────────────────────────────────────
    //  1. startRound — 建轮次，裁判懒创建，状态 = COLLECTING
    // ─────────────────────────────────────────────────────────

    /**
     * 发起一轮竞赛，写入 competition_rounds 行（status=COLLECTING），
     * 并做裁判懒创建检查。
     *
     * @param projectDomain   项目方向（如"短篇小说"）
     * @param topic           命题题目（用户在发起表单里填写）
     * @param judgeCharacterId 当轮裁判的 characterId
     * @param participantIds  参赛角色 ID 列表（不含裁判，由调用方保证）
     * @return 新建的竞赛轮次 ID（给 UI 层订阅进度用）
     */
    suspend fun startRound(
        projectDomain: String,
        topic: String,
        judgeCharacterId: Int,
        participantIds: List<Int>,
    ): String = withContext(Dispatchers.IO) {
        // 方案 4-2 补丁：裁判/参赛者重复校验。
        // CompetitionScreen.LaunchRoundDialog 已在 UI 层做了防护（选裁判后自动
        // 从参赛者候选中排除、已选参赛者被选为裁判时会被自动移除），当前唯一的
        // UI 入口无法触发这个问题；但本函数作为底层 API，其 @param participantIds
        // 文档注释写的"不含裁判，由调用方保证"只是约定，没有运行时保障。
        // 补上校验是为了防御未来可能出现的其他调用路径（例如 agent 自主发起竞赛、
        // 批量脚本等不经过这个 UI 对话框的场景）——避免裁判评审自己的作品，
        // 或裁判准确度 Spearman 计算把裁判自己当参赛者算进去导致数据无意义。
        require(judgeCharacterId !in participantIds) {
            "裁判不能同时作为参赛者 (judgeCharacterId=$judgeCharacterId 出现在 participantIds 中)"
        }

        val now = System.currentTimeMillis()
        val roundId = UUID.randomUUID().toString()

        // 裁判懒创建（执行方案第2节 / 第4节）
        ensureJudgeProfile(judgeCharacterId, projectDomain, now)

        val round = CompetitionRoundEntity(
            id = roundId,
            projectDomain = projectDomain,
            topic = topic,
            judgeCharacterId = judgeCharacterId,
            participantIdsJson = JSONArray(participantIds).toString(),
            status = STATUS_COLLECTING,
            createdAt = now,
        )
        db.competitionRoundDao().insert(round)

        ZLog.i(TAG, "[startRound] roundId=$roundId domain=$projectDomain topic=$topic " +
                "judge=$judgeCharacterId participants=$participantIds → status=COLLECTING")

        // ── 节点①：开赛记忆（让参赛角色和裁判日后能回忆"那次比赛"）
        val allIds = participantIds + judgeCharacterId
        val participantNames = participantIds.map { resolveCharacterName(it) }
        val judgeName0 = resolveCharacterName(judgeCharacterId)
        allIds.forEach { charId ->
            recordCompetitionMemory(
                characterId = charId,
                content = "参加了一场「${projectDomain}」方向的竞赛，题目是「${topic}」，" +
                    "参赛选手：${participantNames.joinToString("、")}，裁判：${judgeName0}。",
                keywords = buildCompetitionKeywords(projectDomain, topic, "比赛 竞赛 参赛 开赛"),
                roundId = roundId,
            )
        }

        roundId
    }

    /**
     * 裁判懒创建：若该角色在本 projectDomain 没有 judge_profiles 记录，
     * 自动插入一条空白档案，让她"先按自身审美评"。
     * 不阻塞主流程，插入失败只记录警告日志。
     */
    private suspend fun ensureJudgeProfile(
        characterId: Int,
        domain: String,
        now: Long,
    ) {
        try {
            val profileId = UUID.randomUUID().toString()
            val result = db.judgeProfileDao().ensureProfile(
                JudgeProfileEntity(
                    id = profileId,
                    characterId = characterId,
                    domain = domain,
                    anchorIntent = "",       // 空：等用户后续训练时填写
                    standardNotes = "",      // 空：EXPLORING 期按自身审美评
                    maturityStage = "EXPLORING",
                    createdAt = now,
                    updatedAt = now,
                )
            )
            if (result.id == profileId) {
                ZLog.i(TAG, "[ensureJudgeProfile] 懒创建裁判档案 char=$characterId domain=$domain profileId=$profileId")
            } else {
                ZLog.d(TAG, "[ensureJudgeProfile] 已有裁判档案 char=$characterId domain=$domain id=${result.id}")
            }
        } catch (e: Exception) {
            ZLog.w(TAG, "[ensureJudgeProfile] 懒创建失败 char=$characterId domain=$domain", e)
        }
    }

    // ─────────────────────────────────────────────────────────
    //  2. runCollecting — 生成各参赛角色的作品，落入 competition_entries
    // ─────────────────────────────────────────────────────────

    /**
     * COLLECTING 阶段：为每位参赛角色生成参赛作品，写入 competition_entries。
     *
     * 调用 generateCompetitionEntry 对每位参赛角色执行独立的一次性 LLM 生成：
     *   - 携带该角色当前生效的 EvolutionPlan.content（进化方案）
     *   - 携带该角色在本 domain 的 SpecialtyProfile.styleNotes（风格说明书）
     *   - 在 systemPrompt 中明确告知"这是一场比赛，按自己的风格做到最好"
     * 若 provider 为 null（未配置 API），本阶段静默跳过（不写空记录）。
     *
     * 函数调用完成后，状态流转到 JUDGING（由 runJudging 继续驱动）。
     */
    suspend fun runCollecting(roundId: String): Boolean = withContext(Dispatchers.IO) {
        getRoundMutex(roundId).withLock {
        val round = db.competitionRoundDao().getById(roundId) ?: run {
            ZLog.w(TAG, "[runCollecting] 轮次不存在 roundId=$roundId")
            return@withLock false
        }
        // P-11 修复：入口推进到中间态 COLLECTING_IN_PROGRESS，作为状态机保护。
        // 允许从 COLLECTING（首次进入）或 COLLECTING_IN_PROGRESS（崩溃后重入）继续。
        // 若已是 JUDGING/AWAITING_USER 等后续态则跳过。
        if (round.status != STATUS_COLLECTING && round.status != STATUS_COLLECTING_IN_PROGRESS) {
            ZLog.w(TAG, "[runCollecting] 状态不符，跳过。当前状态=${round.status}")
            return@withLock false
        }
        // 推进到中间态，标记收集进行中（跨进程重入可见）
        db.competitionRoundDao().updateStatus(roundId, STATUS_COLLECTING_IN_PROGRESS)

        val currentProvider = provider ?: run {
            ZLog.w(TAG, "[runCollecting] 未配置 LLMProvider，跳过生成 roundId=$roundId")
            return@withLock false
        }

        val participantIds = parseParticipantIds(round.participantIdsJson)
        ZLog.i(TAG, "[runCollecting] 开始生成参赛作品 roundId=$roundId 参赛者=$participantIds")

        val successfulIds = mutableListOf<Int>()
        for (characterId in participantIds) {
            try {
                // P1-13-11 修复：幂等检查——若该角色在本轮已有 entry（进程被杀后重跑场景），
                // 直接跳过生成，避免重复 LLM 调用和重复写库产生多条参赛作品。
                val existingEntry = db.competitionEntryDao().getByRoundAndCharacter(roundId, characterId)
                if (existingEntry != null) {
                    ZLog.d(TAG, "[runCollecting] 角色${characterId}已有参赛作品，跳过重复生成 entry.id=${existingEntry.id}")
                    successfulIds += characterId
                    continue
                }

                val content = generateCompetitionEntry(
                    provider = currentProvider,
                    characterId = characterId,
                    domain = round.projectDomain,
                    topic = round.topic,
                )
                if (content.isBlank()) {
                    ZLog.w(TAG, "[runCollecting] 角色${characterId}生成内容为空，跳过写库")
                    continue
                }

                // W1 修复：先查后写——在事务内做二次检查，防止 LLM 调用期间
                // 被其他协程抢先写入同角色同轮次的参赛作品。
                db.withTransaction {
                    val recheck = db.competitionEntryDao().getByRoundAndCharacter(roundId, characterId)
                    if (recheck != null) {
                        ZLog.d(TAG, "[runCollecting] 角色${characterId}在LLM调用期间已被写入，跳过重复插入")
                        successfulIds += characterId
                        return@withTransaction
                    }

                    val entry = CompetitionEntryEntity(
                        id = UUID.randomUUID().toString(),
                        roundId = roundId,
                        characterId = characterId,
                        content = content,
                        createdAt = System.currentTimeMillis(),
                    )
                    db.competitionEntryDao().insert(entry)
                    successfulIds += characterId
                    ZLog.d(TAG, "[runCollecting] 角色${characterId}作品已写入 entry.id=${entry.id} 字符数=${content.length}")
                }
            } catch (e: Exception) {
                ZLog.w(TAG, "[runCollecting] 角色${characterId}作品生成/写入失败", e)
                // 单个角色失败不影响其他角色继续
            }
        }

        ZLog.i(TAG, "[runCollecting] COLLECTING 完成 roundId=$roundId，即将进入 JUDGING")

        // ── 节点②：产出完成记忆（只给真正成功写入作品的角色记"我交了作品"，
        //          避免生成失败/写库异常的角色留下虚假的"已提交参赛"记忆）
        successfulIds.forEach { charId ->
            val name2 = resolveCharacterName(charId)
            recordCompetitionMemory(
                characterId = charId,
                content = "在「${round.projectDomain}」竞赛（题目：「${round.topic}」）中" +
                    "完成了作品，已提交参赛，等待裁判评审。",
                keywords = buildCompetitionKeywords(round.projectDomain, round.topic, "作品 提交 参赛 竞赛"),
                roundId = roundId,
            )
            ZLog.d(TAG, "[节点②] 已记录产出完成记忆 char=$charId($name2)")
        }

        // P-11 修复：收集完成，推进到 COLLECTED 终态。
        // runJudging 期望从 COLLECTING 或 JUDGING 进入；为兼容，COLLECTED 视同 COLLECTING 的完成态，
        // 但 runJudging 的状态检查需同时接受 COLLECTED（见 runJudging 入口）。
        db.competitionRoundDao().updateStatus(roundId, STATUS_COLLECTED)

        true
        } // end getRoundMutex(roundId).withLock — runCollecting
    }

    // ─────────────────────────────────────────────────────────
    //  generateCompetitionEntry — 独立的一次性命题生成（执行方案第5节）
    // ─────────────────────────────────────────────────────────

    /**
     * 为单个参赛角色生成竞赛作品（独立 chatSync 调用，不经过 PromptOrchestrator）。
     *
     * 对标 DailyPracticeWorker.generateTodayPractice，区别：
     *   - 题目由外部命题给定，不是角色自选；
     *   - systemPrompt 中明确告知"这是一场比赛，按自己的风格做到最好"；
     *   - 直接返回正文字符串（无需 topic/content 对），因为竞赛题目已由 round.topic 确定。
     *
     * 若该角色在本 domain 没有 SpecialtyProfile（从未练习过该方向），
     * planContent / styleNotes 均为空，systemPrompt 中照实说明，不中止生成——
     * 角色按当前状态参赛即可，这本就是裁判评审时应考虑的背景。
     *
     * @return 参赛作品正文；生成失败时返回空字符串（调用方按空字符串跳过处理）
     */
    private suspend fun generateCompetitionEntry(
        provider: LLMProvider,
        characterId: Int,
        domain: String,
        topic: String,
    ): String {
        // 取该角色在本 domain 的 SpecialtyProfile（含 styleNotes）
        val profile = db.specialtyProfileDao().getByCharacterAndDomain(characterId, domain)
        val styleNotes = profile?.styleNotes?.takeIf { it.isNotBlank() } ?: ""

        // 取当前生效的 EvolutionPlan（需要先有 profile.id）
        val planContent = profile?.let { p ->
            db.evolutionPlanDao().getActivePlan(p.id)?.content?.takeIf { it.isNotBlank() }
        } ?: ""

        val characterName = resolveCharacterName(characterId)

        val styleBlock = if (styleNotes.isNotBlank())
            "你已经沉淀的风格说明书：\n$styleNotes"
        else
            "你在「$domain」方向的风格说明书尚未形成，仍在摸索阶段——按你现阶段最真实的感觉来写即可。"

        val planBlock = if (planContent.isNotBlank())
            "你当前的自我进化方案：\n$planContent"
        else
            "你在「$domain」方向暂无正式进化方案——按自己对这个方向的理解和感觉来写。"

        val systemPrompt = """
            你是「${characterName}」，正在参加一场「${domain}」方向的命题竞赛，题目是：${topic}

            裁判和用户都会评判这次产出，但你不需要去猜裁判喜欢什么、不需要模仿任何人——
            按你已经确立的风格和正在练习的方向，做到你能做到的最好。

            $planBlock

            $styleBlock

            请直接产出正文，不需要标题，不需要解释创作思路，不需要说"我的参赛作品是"——
            直接开始写内容本身。
        """.trimIndent()

        return try {
            val response = provider.chatSyncWithRetry(
                messages = listOf(LLMMessage("user", "请开始这次竞赛的创作。")),
                systemPrompt = systemPrompt,
                config = LLMConfig(
                    model = "",
                    maxTokens = 1500,
                    temperature = SpecialtyEvolutionConfig.PRACTICE_TEMPERATURE,
                    stream = false,
                ),
            )
            response.trim().also { result ->
                ZLog.d(TAG, "[generateEntry] char=$characterId domain=$domain topic=$topic 字符数=${result.length}")
            }
        } catch (e: Exception) {
            ZLog.w(TAG, "[generateEntry] 生成失败 char=$characterId domain=$domain", e)
            ""
        }
    }

    // ─────────────────────────────────────────────────────────
    //  3. runJudging — 实名批量评审 + 各角色自评，圆桌播报
    // ─────────────────────────────────────────────────────────

    /**
     * JUDGING 阶段：
     *   ① 调用 CompetitionEngine.judgeRound（裁判实名批量评审）
     *   ② 对每位参赛者调用 selfEvaluateEntry（盲评，看不到他人产出和裁判分）
     *   ③ 将评审结果写回 competition_entries
     *   ④ 状态 → AWAITING_USER
     *   ⑤ 圆桌播报：裁判点评摘要 + 各自分数
     */
    suspend fun runJudging(roundId: String): Boolean = withContext(Dispatchers.IO) {
        getRoundMutex(roundId).withLock {
        val round = db.competitionRoundDao().getById(roundId) ?: run {
            ZLog.w(TAG, "[runJudging] 轮次不存在 roundId=$roundId")
            return@withLock false
        }
        // 允许从 COLLECTING（旧路径）/ COLLECTED（P-11 新中间态完成）/ JUDGING（崩溃恢复）进入；
        // 其他状态（AWAITING_USER / COMPLETED）不允许重跑评审。
        if (round.status != STATUS_COLLECTING && round.status != STATUS_COLLECTED && round.status != STATUS_JUDGING) {
            ZLog.w(TAG, "[runJudging] 期望状态 COLLECTING/COLLECTED/JUDGING，实际=${round.status}")
            return@withLock false
        }

        val entries = db.competitionEntryDao().getAllForRound(roundId)
        if (entries.isEmpty()) {
            ZLog.w(TAG, "[runJudging] 没有参赛条目，无法评审 roundId=$roundId")
            return@withLock false
        }

        // 状态 → JUDGING（幂等写入：若已是 JUDGING 则相当于重置落库时间戳，
        // 崩溃重入时保证后续读到的是最新状态）
        db.competitionRoundDao().updateStatus(roundId, STATUS_JUDGING)
        ZLog.i(TAG, "[runJudging] 状态 → JUDGING roundId=$roundId (来源=${round.status})")

        // 取裁判档案（标准说明书 + 裁判名）
        val judgeProfile = db.judgeProfileDao().getByCharacterAndDomain(
            round.judgeCharacterId, round.projectDomain
        )
        val judgeName = resolveCharacterName(round.judgeCharacterId)
        val judgeStandardNotes = judgeProfile?.standardNotes ?: ""

        ZLog.i(TAG, "[runJudging] 裁判=$judgeName(${round.judgeCharacterId}) standardNotes长度=${judgeStandardNotes.length}")

        // 组装 ContestantEntry 列表（为每位参赛者拼 styleNotes）
        val contestants = entries.map { entry ->
            val styleNotes = resolveStyleNotes(entry.characterId, round.projectDomain)
            val charName = resolveCharacterName(entry.characterId)
            CompetitionEngine.ContestantEntry(
                characterId = entry.characterId,
                characterName = charName,
                styleNotes = styleNotes,
                content = entry.content,
            )
        }

        // ① 裁判实名批量评审（一次 LLM 调用）
        ZLog.i(TAG, "[runJudging] 调用 judgeRound，参赛者数=${contestants.size}")
        val judgeResult = competitionEngine.judgeRound(
            domain = round.projectDomain,
            judgeStandardNotes = judgeStandardNotes,
            judgeName = judgeName,
            entries = contestants,
        )

        if (!judgeResult.success) {
            ZLog.w(TAG, "[runJudging] judgeRound 解析失败，跳过本轮评审")
            // 回退到 COLLECTING，否则下次重试会被「期望状态 COLLECTING」拦住，
            // 状态卡死在 JUDGING 无法恢复
            db.competitionRoundDao().updateStatus(roundId, STATUS_COLLECTING)
            ZLog.i(TAG, "[runJudging] 状态回退 → COLLECTING（评审失败，可重试）roundId=$roundId")
            return@withLock false
        }

        ZLog.i(TAG, "[runJudging] judgeRound 成功，overallComment=${judgeResult.overallComment}")

        // ② 将裁判结果写回 competition_entries + 各角色自评
        //
        // 修复 P0-3：每条 entry 的写回单独包 try-catch——updateJudgeResult/
        // updateSelfResult 都是裸 DB 写，一旦某条抛异常（如该行已被删除、
        // 磁盘异常等），不能让异常穿透整个 for 循环：那样会导致"部分 entry
        // 已写、部分未写"，且下面 ④ 的 status→AWAITING_USER 永远到不了，
        // 状态卡在 JUDGING 无法恢复。单条失败只记录日志，跳过该条目继续。
        for (entry in entries) {
            try {
                val verdict = judgeResult.verdicts.find { it.characterId == entry.characterId }
                if (verdict != null) {
                    db.competitionEntryDao().updateJudgeResult(
                        id = entry.id,
                        score = verdict.score,
                        reasoning = "${verdict.issues}\n\n改进方向：${verdict.improvementDirection}",
                    )
                    ZLog.d(TAG, "[runJudging] 裁判分写回 char=${entry.characterId} score=${verdict.score} rank=${verdict.rank}")
                }

                // 自评（盲评，独立进行，不依赖裁判结果）
                val contestant = contestants.find { it.characterId == entry.characterId }
                if (contestant != null) {
                    val selfResult = competitionEngine.selfEvaluateEntry(
                        characterName = contestant.characterName,
                        domain = round.projectDomain,
                        ownContent = entry.content,
                        ownStyleNotes = contestant.styleNotes,
                    )
                    db.competitionEntryDao().updateSelfResult(
                        id = entry.id,
                        score = selfResult.selfScore,
                        reasoning = selfResult.selfReasoning,
                    )
                    ZLog.d(TAG, "[runJudging] 自评完成 char=${entry.characterId} selfScore=${selfResult.selfScore}")
                }
            } catch (e: Exception) {
                ZLog.w(TAG, "[runJudging] entry写回失败，跳过该条目继续 char=${entry.characterId} entryId=${entry.id}", e)
            }
        }

        // W4-3 修复：裁判次数计数从这里移到 finalizeRound（只在本轮至少有一条
        // 有效用户打分时才递增，避免"全员未打分直接结算"的空轮也被计入裁判的
        // 有效评判次数，导致 maturityStage 成熟度提前晋升）。见 finalizeRound
        // 中 scoredFinal.isNotEmpty() 判断处。

        // ③.5 将每位参赛者收到的裁判 reasoning 喂给候选修正池
        //     judgeProfile 非 null 才有意义；null 表示懒创建失败，跳过，不中止主流程
        judgeProfile?.let { jp ->
            feedJudgeCorrectionCandidates(
                judgeProfileId  = jp.id,
                judgeCharacterId = round.judgeCharacterId,
                domain          = round.projectDomain,
                entries         = entries,
                judgeResult     = judgeResult,
            )
        }

        // ④ 状态 → AWAITING_USER
        db.competitionRoundDao().updateStatus(roundId, STATUS_AWAITING_USER)
        ZLog.i(TAG, "[runJudging] 状态 → AWAITING_USER roundId=$roundId")

        // ⑤ 圆桌播报（suspend 方法，所有角色名解析均在这里做，无 non-suspend 调 suspend 问题）
        postJudgeResultToRoundtable(
            round = round,
            judgeName = judgeName,
            judgeResult = judgeResult,
            entries = entries,
        )

        // ── 节点③：裁判公布结果记忆（参赛者 + 裁判均记录"评审结果出来了"）
        val allIdsJudging = entries.map { it.characterId } + round.judgeCharacterId
        for (charId in allIdsJudging) {
            val verdict = judgeResult.verdicts.find { it.characterId == charId }
            val scoreDesc = if (verdict != null) "，得分 ${verdict.score}，排名第 ${verdict.rank}" else ""
            val role = if (charId == round.judgeCharacterId) "担任裁判" else "参赛"
            val charName3 = resolveCharacterName(charId)
            val issuesSuffix = if (verdict?.issues?.isNotBlank() == true)
                "裁判指出的问题：${verdict.issues.take(60)}。" else ""
            recordCompetitionMemory(
                characterId = charId,
                content = "「${round.projectDomain}」竞赛（题目：「${round.topic}」）" +
                    "裁判 ${judgeName} 已公布评审结果，${charName3}${role}${scoreDesc}。${issuesSuffix}",
                keywords = buildCompetitionKeywords(round.projectDomain, round.topic, "评审 结果 裁判 点评 分数"),
                roundId = roundId,
            )
            ZLog.d(TAG, "[节点③] 已记录裁判公布结果记忆 char=$charId($charName3)$scoreDesc")
        }

        true
        } // end getRoundMutex(roundId).withLock — runJudging
    }

    // ─────────────────────────────────────────────────────────
    //  4. submitUserScore — 用户评分三种输入统一换算
    // ─────────────────────────────────────────────────────────

    /**
     * 用户评分输入（三种形式之一）：
     *
     * @param entryId       competition_entries.id
     * @param directScore   直接打分（0-100），与 rankAmongN / sentimentComment 三选一
     * @param rankAmongN    排名（第 k 名 / 共 N 名），与 directScore / sentimentComment 三选一
     * @param sentimentComment 纯文字评语，与 directScore / rankAmongN 三选一
     * @param rawComment    用户评语原文（无论哪种输入方式都可附上，落库留档）
     */
    suspend fun submitUserScore(
        entryId: String,
        roundId: String,
        directScore: Int? = null,
        rankAmongN: Pair<Int, Int>? = null,  // Pair(rank=k, totalN=N)
        sentimentComment: String? = null,
        rawComment: String = "",
    ) = withContext(Dispatchers.IO) {
        getRoundMutex(roundId).withLock {
            // W1 修复（2.4.3 事务拆分版）：原实现把 entry/round 校验 + sentimentToScore
            // + updateUserScore 全部包在同一个 db.withTransaction{} 块内。改造后
            // judgeSentimentScore() 是一次挂起的 LLM 网络请求，不能在事务内调用——
            // 挂起点会让事务在等待 LLM 响应期间一直悬空，阻塞同一 roundId 上的其他
            // 并发写入。拆分为两个短事务，LLM 调用放在事务之间：
            //   1. 第一个短事务：校验 entry 存在 + entry.roundId == roundId + round 状态
            //      为 AWAITING_USER。校验通过即提交事务。
            //   2. 事务外：计算分数（sentimentComment 路径走 judgeSentimentScore LLM 调用，
            //      directScore/rankAmongN 路径纯本地计算）。
            //   3. 第二个短事务：重新校验 round 状态（防止第1步到第3步之间状态被其他协程
            //      改变——这正是 W1 修复原本要防的那类竞态），校验通过再执行 updateUserScore()。
            //
            // getRoundMutex(roundId).withLock 全程持有（含 LLM 调用期间），确保同一 roundId
            // 不会有并发的 submitUserScore，但不阻塞其他 roundId 的操作。

            // ── 第一步：短事务校验 entry + round 状态 ──
            val entryValid = db.withTransaction {
                val entry = db.competitionEntryDao().getById(entryId)
                if (entry == null) {
                    ZLog.w(TAG, "[submitUserScore] entry 不存在 entryId=$entryId")
                    return@withTransaction false
                }
                if (entry.roundId != roundId) {
                    ZLog.w(TAG, "[submitUserScore] entry 不属于该轮次 entryId=$entryId roundId=$roundId actual=${entry.roundId}")
                    return@withTransaction false
                }
                val round = db.competitionRoundDao().getById(roundId)
                if (round == null || round.status != STATUS_AWAITING_USER) {
                    ZLog.w(TAG, "[submitUserScore] 轮次状态不允许打分 roundId=$roundId status=${round?.status}")
                    return@withTransaction false
                }
                true
            }

            if (!entryValid) return@withLock

            // ── 第二步：事务外计算分数（sentimentComment 路径走 LLM）──
            val userScore = when {
                directScore != null -> directScore.coerceIn(0, 100)
                rankAmongN != null -> {
                    val (k, n) = rankAmongN
                    if (n <= 0) 50
                    else (100 - (k - 1) * (100.0 / n)).toInt().coerceIn(0, 100)
                }
                sentimentComment != null -> judgeSentimentScore(sentimentComment)
                else -> 50  // 兜底默认
            }

            val userRank = rankAmongN?.first

            // ── 第三步：短事务重新校验 round 状态 + 写入 ──
            db.withTransaction {
                val round = db.competitionRoundDao().getById(roundId)
                if (round == null || round.status != STATUS_AWAITING_USER) {
                    ZLog.w(TAG, "[submitUserScore] 轮次状态已变更，放弃写入 roundId=$roundId status=${round?.status}")
                    return@withTransaction
                }
                db.competitionEntryDao().updateUserScore(
                    id = entryId,
                    score = userScore,
                    comment = rawComment,
                    rank = userRank,
                )
                ZLog.i(TAG, "[submitUserScore] entryId=$entryId userScore=$userScore rank=$userRank")
            }
        }
    }

    /**
     * 情感粗粒度映射（执行方案第10节）——兜底路径。
     *
     * 见《Window B 执行方案 v1.1》2.4.3。原 sentimentToScore() 改名为
     * sentimentToScoreFallback()，作为 judgeSentimentScore() LLM 语义判断
     * 主路径的兜底——当 LLM 不可用或返回无法解析时，自动降级到本函数。
     *
     * 把用户纯文字评语映射成 0-100 分数区间代表值。
     *
     * 否定前缀守卫（negation guard）：
     * "不喜欢"这种否定短语本身整段已经在 strongNegative 里，但它同时包含
     * "喜欢"这个 mildPositive 关键词的子串——纯子串匹配下，"喜欢"会先命中，
     * 把明显负面的评语误判成正面。修复方式：检查正面关键词命中时，
     * 排除该关键词紧跟在否定字（不/没/无/别/未）之后的情况，
     * 不影响 mildNegative 里"有点"这类程度副词的独立判断（"有点意思"该判正面，
     * 不应被"有点"单独抢先命中，这是关键词顺序本身保证的，跟否定守卫无关）。
     */
    private fun sentimentToScoreFallback(comment: String): Int {
        // 简单关键词匹配（不调 LLM，足够满足"粗粒度"要求）
        val lower = comment.lowercase()
        val negationChars = setOf('不', '没', '无', '别', '未')

        fun matches(keywords: List<String>, guardNegation: Boolean): Boolean {
            return keywords.any { kw ->
                val idx = lower.indexOf(kw)
                if (idx < 0) return@any false
                if (!guardNegation) return@any true
                // 方案 2-4：前向搜索最近的非空白字符，检查是否为否定词。
                // 修复原逻辑只检查前一个字符导致"不太喜欢"、"不是很喜欢"、
                // "不怎么好看"等被误判为正面。
                var scanIdx = idx - 1
                while (scanIdx >= 0 && lower[scanIdx].isWhitespace()) scanIdx--
                val precededByNegation = scanIdx >= 0 && lower[scanIdx] in negationChars
                !precededByNegation
            }
        }

        val strongPositive = listOf("太棒了", "完美", "绝了", "惊艳", "最喜欢", "第一", "最好")
        val mildPositive  = listOf("不错", "挺好", "喜欢", "还行", "可以", "好看", "有意思")
        val mildNegative  = listOf("一般", "不太", "有点", "稍微", "略显", "差一点")
        val strongNegative = listOf("很差", "不行", "失望", "最差", "不喜欢", "没意思")

        return when {
            matches(strongPositive, guardNegation = true) -> 95   // 强烈正面 90-100，取代表值95
            matches(mildPositive, guardNegation = true)  -> 79    // 温和正面 70-89，取79
            matches(strongNegative, guardNegation = false) -> 10  // 强烈负面 0-19，取10
            matches(mildNegative, guardNegation = false)  -> 30    // 温和负面 20-39，取30
            else -> 55                                              // 中性 40-69，取55
        }
    }

    /**
     * LLM 语义判断为主路径：把评语交给 LLM，让它给出一个 0-100 的情感倾向分数。
     *
     * 见《Window B 执行方案 v1.1》2.4.3。替代旧 sentimentToScore() 关键词匹配——
     * 关键词匹配对模式化评语有效，但无法处理"这次虽然保守但暗藏巧思"这类需要
     * 语义理解的评语。LLM 路径能更准确地捕捉情感倾向。
     *
     * 降级策略：如果 LLM 不可用（provider 为 null）、调用失败、或返回无法解析的结果，
     * 自动降级到 [sentimentToScoreFallback]，不影响竞赛流程的可用性。
     */
    private suspend fun judgeSentimentScore(comment: String): Int {
        return try {
            val response = AgentTool.callLlm(
                providerFn   = { provider },
                systemPrompt = "你是一个情感分析助手。给定一段评语，给出一个能代表这段评语情感倾向的 0-100 整数分（0=最负面，100=最正面，50=中性）。只回复数字，不要任何其他文字。",
                userPrompt   = comment,
                maxTokens    = 10,
                temperature  = 0.3f,
            )
            val score = response.trim().toIntOrNull()
            if (score != null && score in 0..100) {
                ZLog.d(TAG, "[judgeSentimentScore] LLM 分数=$score comment=${comment.take(50)}")
                score
            } else {
                ZLog.w(TAG, "[judgeSentimentScore] LLM 返回无法解析: '$response'，降级到 fallback")
                sentimentToScoreFallback(comment)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ZLog.w(TAG, "[judgeSentimentScore] LLM 调用失败，降级到 fallback: ${e.message}")
            sentimentToScoreFallback(comment)
        }
    }

    // ─────────────────────────────────────────────────────────
    //  5. finalizeRound — 算综合分，COMPLETED
    // ─────────────────────────────────────────────────────────

    /**
     * finalizeRound：
     *   ① 读取权重配置（competition_weight_configs）
     *   ② 读取裁判 maturityStage → 信任系数（动态折扣）
     *   ③ 对每条 entry 算 compositeScore，写库
     *   ④ 算裁判准确度（Spearman），写 judge_accuracy_log
     *   ⑤ 若连续多轮吻合度低于阈值，发圆桌提醒
     *   ⑥ status → COMPLETED
     */
    suspend fun finalizeRound(roundId: String): Boolean = withContext(Dispatchers.IO) {
        getRoundMutex(roundId).withLock {
        val round = db.competitionRoundDao().getById(roundId) ?: run {
            ZLog.w(TAG, "[finalizeRound] 轮次不存在 roundId=$roundId")
            return@withLock false
        }
        if (round.status != STATUS_AWAITING_USER) {
            ZLog.w(TAG, "[finalizeRound] 期望状态 AWAITING_USER，实际=${round.status}")
            return@withLock false
        }

        val entries = db.competitionEntryDao().getAllForRound(roundId)
        if (entries.isEmpty()) {
            ZLog.w(TAG, "[finalizeRound] 没有参赛条目 roundId=$roundId")
            return@withLock false
        }

        // 读权重配置（若无，使用默认值）
        val weightConfig = db.competitionWeightConfigDao().getByDomain(round.projectDomain)
        val userBaseW = weightConfig?.userBaseWeight ?: 50
        val judgeBaseW = weightConfig?.judgeBaseWeight ?: 40
        val selfBaseW = weightConfig?.selfBaseWeight ?: 10
        val dynamicEnabled = weightConfig?.judgeTrustDynamicEnabled ?: true

        // 裁判信任系数
        val judgeProfile = db.judgeProfileDao().getByCharacterAndDomain(
            round.judgeCharacterId, round.projectDomain
        )
        val trust = if (dynamicEnabled) trustFactor(judgeProfile?.maturityStage ?: "EXPLORING") else 1.0f

        // 动态权重：裁判折扣部分转移给用户
        val effectiveJudgeW = judgeBaseW * trust
        val effectiveUserW = userBaseW + (judgeBaseW - effectiveJudgeW)
        val effectiveSelfW = selfBaseW.toFloat()
        val totalW = effectiveUserW + effectiveJudgeW + effectiveSelfW

        ZLog.i(TAG, "[finalizeRound] 权重 user=${effectiveUserW}f judge=${effectiveJudgeW}f self=${effectiveSelfW}f total=${totalW}f")

        // W1 修复：多步写入用事务包裹——compositeScore 更新 + 裁判准确度 + markCompleted
        // 在同一事务内完成，确保轮次结算的原子性。任何一步失败整体回滚，
        // 不会出现"分数已更新但轮次状态仍是 AWAITING_USER"等不一致。
        db.withTransaction {
        for (entry in entries) {
            // 方案 4-1 补丁：userScore == null 表示用户主动跳过了该条目的打分，
            // 与"用户主动打了50分"是两种不同语义，不能用 ?: 50 兜底混为一谈——
            // 混同会让被跳过的条目获得不应有的排名权重，甚至可能被 applyCompetitionRewards
            // 误判为赢家、触发不该有的晋升反哺。
            // 处理方式：未打分条目直接跳过综合分计算，compositeScore 保持实体默认值 0f。
            // 这与 UI 层（CompetitionScreen）已有的判断完全对齐：
            //   - entry.userScore != null 才显示"你的评分"
            //   - entry.compositeScore > 0f 才显示"综合分"
            // 未打分条目因此不会出现在综合分展示里，排序时也会自然沉底，
            // 不需要新增数据库字段或改动 UI 判断逻辑。
            if (entry.userScore == null) {
                ZLog.d(TAG, "[finalizeRound] char=${entry.characterId} 未打分，跳过综合分计算（compositeScore 保持默认值）")
                continue
            }

            // 窗口05 新发现2 修复：judgeScore/selfScore 为 null 与 userScore 为
            // null 是同一类"评审未真正完成"语义（见 CompetitionEntryEntity
            // 字段注释：judgeScore "null 表示评审尚未完成"），此前却用
            // ?: 50 兜底参与综合分计算——与上面 userScore==null 时直接
            // continue 跳过的处理方式不一致。judgeScore 为 null 通常发生在
            // runJudging 中该条目未命中 verdict、或单条写回 try-catch 吞掉
            // 了 updateJudgeResult 异常（judgeResult.success 只要求
            // validVerdictCount > 0，不要求"每条都命中"，因此完全可能出现
            // judgeResult 整体成功、但个别条目 judgeScore 仍为 null 的情况）；
            // selfScore 为 null 同理（selfEvaluateEntry 调用失败或
            // updateSelfResult 写回失败）。兜底 50 分会让"实际未被评审"的
            // 条目获得不应有的中等权重，参与排名甚至可能被
            // applyCompetitionRewards 误判为赢家，与 userScore==null 时
            // 的处理逻辑自相矛盾。
            // 修复：judgeScore 或 selfScore 任一为 null，视为该条目本轮评审
            // 未完整完成，同样跳过综合分计算（保持 compositeScore 默认值
            // 0f），不再用兜底值参与加权——与 userScore==null 分支采用完全
            // 一致的"未完成即跳过"语义。
            if (entry.judgeScore == null || entry.selfScore == null) {
                ZLog.w(
                    TAG,
                    "[finalizeRound] char=${entry.characterId} judgeScore=${entry.judgeScore} " +
                        "selfScore=${entry.selfScore} 评审未完整完成，跳过综合分计算（compositeScore 保持默认值）"
                )
                continue
            }

            val uScore = entry.userScore.toFloat()
            val jScore = entry.judgeScore.toFloat()
            val sScore = entry.selfScore.toFloat()

            val composite = if (totalW > 0f) {
                (uScore * effectiveUserW + jScore * effectiveJudgeW + sScore * effectiveSelfW) / totalW
            } else 50f

            db.competitionEntryDao().updateCompositeScore(entry.id, composite)
            ZLog.d(TAG, "[finalizeRound] char=${entry.characterId} composite=${String.format("%.1f", composite)}")
        }

        // 裁判准确度：Spearman 秩相关（裁判排名 vs 用户最终排名/综合分排名）
        computeAndSaveJudgeAccuracy(round, judgeProfile?.id)

        // status → COMPLETED
        db.competitionRoundDao().markCompleted(roundId)
        ZLog.i(TAG, "[finalizeRound] 状态 → COMPLETED roundId=$roundId")
        } // end db.withTransaction

        // ── 节点④：最终排名记忆（综合分排名出炉，仅对已打分条目记录最终成绩）
        // 方案 4-1 补丁：userScore == null 的条目 compositeScore 保持默认 0f，
        // 不代表真实表现差——不应把"综合得分0.0、排名垫底"写成角色的永久记忆，
        // 这会误导角色对自己实力的认知。排名记忆只对真正参与了综合分计算的条目生成，
        // 名次按这个子集重新连续编号（避免出现"第3/5名"却跳过了未打分者的错位编号）。
        //
        // 窗口05 新发现2 修复：scoredFinal 的筛选条件同步补上 judgeScore/
        // selfScore 非空——原先只过滤 userScore != null，若某条目 userScore
        // 有值但 judgeScore/selfScore 为 null（本轮评审未完整完成，见上方
        // db.withTransaction 内的 continue 分支），其 compositeScore 会
        // 保持默认值 0f，却仍会混进 scoredFinal 参与排名记忆编号，且可能
        // 被 applyCompetitionRewards 的 sortedByComposite.first() 误判为
        // "赢家"（compositeScore=0f 反而排名最低，实际是相反：若其余条目
        // 综合分同样偏低甚至该条目恰好是唯一一条落在 scoredFinal 里的记录，
        // 就会被当成赢家触发晋升反哺）。与 userScore==null 用同一套"评审
        // 未完成即不参与排名/反哺"的口径，避免自相矛盾。
        val freshEntries4 = db.competitionEntryDao().getAllForRound(roundId)
        val scoredFinal = freshEntries4
            .filter { it.userScore != null && it.judgeScore != null && it.selfScore != null }
            .sortedByDescending { it.compositeScore }
        val sortedFinal = freshEntries4.sortedByDescending { it.compositeScore }
        val judgeNameFinal = resolveCharacterName(round.judgeCharacterId)

        // W4-3 修复：裁判次数从 runJudging 移到这里，且只在本轮至少有一条
        // 有效用户打分（scoredFinal 非空）时才递增。全员未打分直接结算的
        // "空轮"不计入裁判的有效评判次数，避免 maturityStage 成熟度被
        // 没有真实反馈价值的空轮提前推高。
        if (scoredFinal.isNotEmpty()) {
            judgeProfile?.let {
                db.judgeProfileDao().incrementJudgeCount(it.id)
                ZLog.d(TAG, "[finalizeRound] 裁判次数+1 judgeProfileId=${it.id}（本轮有效打分数=${scoredFinal.size}）")
            }
        } else {
            ZLog.i(TAG, "[finalizeRound] 本轮全员未打分，裁判次数不计入 roundId=$roundId")
        }

        scoredFinal.forEachIndexed { idx, entry ->
            val rank = idx + 1
            val charName4 = resolveCharacterName(entry.characterId)
            recordCompetitionMemory(
                characterId = entry.characterId,
                content = "「${round.projectDomain}」竞赛（题目：「${round.topic}」）最终结算完成，" +
                    "${charName4} 综合得分 ${String.format("%.1f", entry.compositeScore)}，" +
                    "最终排名第 ${rank}/${scoredFinal.size}。裁判 ${judgeNameFinal}，" +
                    "共 ${sortedFinal.size} 位选手参赛。",
                keywords = buildCompetitionKeywords(round.projectDomain, round.topic, "排名 综合分 结果 竞赛 完赛"),
                roundId = roundId,
            )
            ZLog.d(TAG, "[节点④] 已记录最终排名记忆 char=${entry.characterId}($charName4) rank=$rank composite=${entry.compositeScore}")
        }
        // 裁判也记一条（以裁判视角）
        recordCompetitionMemory(
            characterId = round.judgeCharacterId,
            content = "主持完「${round.projectDomain}」竞赛（题目：「${round.topic}」）的评审，" +
                "共 ${sortedFinal.size} 位选手参赛，最终排名已结算完毕。",
            keywords = buildCompetitionKeywords(round.projectDomain, round.topic, "裁判 评审 完赛 排名"),
            roundId = roundId,
        )

        // ── 第6节：奖惩反哺（赢家候选观察 initialCount=2 + 稳定度加速；输家写 COMPETITION_FEEDBACK）
        // 方案 4-1 补丁：用 scoredFinal（仅真正打分的条目）而非 sortedFinal，
        // 避免全员未打分时把某个 compositeScore=0f 的默认值条目误判为"赢家"触发晋升反哺。
        applyCompetitionRewards(round, scoredFinal)

        // 方案 2-8：轮次完结后清理 Mutex，避免长期运行中内存累积
        roundMutexes.remove(roundId)
        judgeProfile?.id?.let { judgeCandidateMutexes.remove(it) }

        true
        } // end getRoundMutex(roundId).withLock — finalizeRound
    }

    /**
     * 取消竞赛轮次（W4-5）。
     *
     * 允许取消的状态：COLLECTING / COLLECTING_IN_PROGRESS / COLLECTED /
     * JUDGING / AWAITING_USER —— 也就是任何尚未走到终态的过程中状态。
     * 不允许取消：COMPLETED（已结算，取消没有意义，数据已落地）、
     * CANCELLED（已经是取消状态，重复调用直接返回 false，避免重复清理）。
     *
     * 与 finalizeRound 同款收尾：成功后清理该 roundId 对应的 Mutex，
     * 避免长期运行中 roundMutexes/judgeCandidateMutexes 无限增长。
     * 注意 judgeCandidateMutex 是按 judgeProfileId 维度的，不能仅凭
     * roundId 移除——若该裁判在其他轮次仍有候选池写入在途，直接移除
     * 会导致下一次写入重新创建一把新锁，从而绕开原有的串行化保护。
     * 因此这里只清理 roundMutex，judgeCandidateMutex 留给
     * finalizeRound（轮次真正结算完成时）统一处理。
     */
    suspend fun cancelRound(roundId: String): Boolean = withContext(Dispatchers.IO) {
        getRoundMutex(roundId).withLock {
            val round = db.competitionRoundDao().getById(roundId) ?: run {
                ZLog.w(TAG, "[cancelRound] 轮次不存在 roundId=$roundId")
                return@withLock false
            }
            if (round.status == STATUS_COMPLETED || round.status == STATUS_CANCELLED) {
                ZLog.w(TAG, "[cancelRound] 当前状态不可取消 roundId=$roundId status=${round.status}")
                return@withLock false
            }

            db.competitionRoundDao().updateStatus(roundId, STATUS_CANCELLED)
            ZLog.i(TAG, "[cancelRound] 状态 ${round.status} → CANCELLED roundId=$roundId")

            // 方案 2-8 同款：轮次已进入终态，清理该 roundId 的 Mutex
            roundMutexes.remove(roundId)

            true
        } // end getRoundMutex(roundId).withLock — cancelRound
    }

    // ─────────────────────────────────────────────────────────
    //  第6节：奖惩反哺
    // ─────────────────────────────────────────────────────────

    /**
     * 竞赛结算后的奖惩反哺（执行方案第6节）。
     *
     * 赢家反哺（两项，在有 specialtyProfileRepository 时生效）：
     *   ① recordCandidateObservation(..., initialCount=2)：赢一次比赛等价于
     *      该手法被自然观察到两次，加快转正路径而不绕过阈值；
     *   ② IdentityPromotionEvaluator.boostStability(...)：额外注入一次稳定确认，
     *      加速晋升评估中的连续稳定计数。
     *
     * 输家反哺（方案B——SELF_ADJUSTED 触发器尚未实现）：
     *   把 judgeReasoning（裁判指名道姓的问题 + 提升方向，质量足够好）写成
     *   一条 SystemSuggestionEntity，content 前缀用 "COMPETITION_FEEDBACK::"，
     *   复用 PENDING/ADOPTED/IGNORED 状态机，用户在专长档案页看到后自行决定
     *   是否据此让角色重新规划。
     *
     * specialtyProfileRepository 为 null 时：赢家候选观察和稳定度加速跳过，
     * 输家 SystemSuggestionEntity 仍然写入（通过 db.systemSuggestionDao() 直接访问）。
     */
    private suspend fun applyCompetitionRewards(
        round: CompetitionRoundEntity,
        sortedByComposite: List<CompetitionEntryEntity>,
    ) {
        if (sortedByComposite.isEmpty()) return
        val domain = round.projectDomain
        val now = System.currentTimeMillis()

        val winner = sortedByComposite.first()
        val losers = sortedByComposite.drop(1)

        // ── 赢家反哺 ────────────────────────────────────────────
        val repo = specialtyProfileRepository
        if (repo != null) {
            val winnerProfile = db.specialtyProfileDao().getByCharacterAndDomain(winner.characterId, domain)
            if (winnerProfile != null) {
                try {
                    // 空 reasoning 守卫：裁判未给出有效评语时跳过反哺（与输家反哺一致）
                    if (winner.judgeReasoning.isNotBlank()) {
                        // B-1 修复：①②两处消费的必须是同一个字符串。此前一处带"竞赛获奖："
                        // 前缀取150字喂给候选池，另一处不带前缀取100字喂给 boostStability，
                        // 导致同一次晋升事件产生两个不重合的字符串，分别进了两套独立的计数
                        // 系统——候选池那边的语义匹配能兜住"表述不同但意思相同"，但兜不住
                        // "同一份原文被截断成不同长度、加不加前缀"这种源头不一致，必须从源头
                        // 统一成一份 trait 字符串，两处都用它。
                        val winnerTrait = "竞赛获奖：${winner.judgeReasoning.take(150)}"
                        // ① 候选观察 initialCount=2（赢一次 = 被自然观察到两次）
                        // P1-9 修复：写入前先语义去重，命中已有候选则累加计数，
                        // 避免同一赢家多次参赛导致候选池里堆满语义重复的条目。
                        val latestProfile = repo.getProfile(winnerProfile.id)
                        val existingTraits = if (latestProfile != null) {
                            // 精确 catch CandidatePoolCorruptedException：损坏时记录原始 JSON
                            // 后以空列表继续，而不是让外层笼统的 catch(Exception) 吞掉整次
                            // 赢家反哺（那样会连①候选观察②boostStability都跳过，日志也只有
                            // "写入失败"一句话，损坏现场丢失）。与 DailyPracticeWorker.
                            // updateCandidatePool 同款处理：候选池视为空，本次观察当全新
                            // 候选写入，等价于损坏前的候选池被重置。
                            try {
                                repo.parseCandidateObservations(latestProfile.candidateObservationsJson)
                                    .map { it.trait }
                            } catch (e: SpecialtyProfileRepository.CandidatePoolCorruptedException) {
                                ZLog.e(TAG,
                                    "候选池JSON损坏，将以空池继续赢家反哺。profileId=${winnerProfile.id} " +
                                    "原始JSON（可从日志恢复）：${e.corruptedJson}", e)
                                emptyList()
                            }
                        } else emptyList()
                        val matchedTrait = if (existingTraits.isNotEmpty()) {
                            competitionEngine.matchJudgeCorrectionCandidate(winnerTrait, existingTraits)
                        } else null
                        repo.recordCandidateObservation(
                            profileId = winnerProfile.id,
                            newTrait = winnerTrait,
                            matchedExistingTrait = matchedTrait,
                            initialCount = 2,
                        )
                        // 方案 2-13：移除 boostStability 调用。
                        // boostStability 的 key 来自"裁判评语截断"，evaluate 的 key
                        // 来自"LLM 提取稳定特征"，两套 key 体系语义匹配概率极低，
                        // boosting 加速效果实际完全失效（见 IdentityPromotionEvaluator 已知限制）。
                        // 保留候选观察池记录（recordCandidateObservation）即可，
                        // 正常路径的蒸馏→评估仍会覆盖。
                        ZLog.i(TAG, "[奖惩反哺] 赢家 char=${winner.characterId} " +
                            "已记录候选观察(initialCount=2)")
                    } else {
                        ZLog.d(TAG, "[奖惩反哺] 赢家 judgeReasoning 为空，跳过反哺")
                    }
                } catch (e: Exception) {
                    ZLog.w(TAG, "[奖惩反哺] 赢家反哺写入失败 char=${winner.characterId}", e)
                }
            } else {
                ZLog.d(TAG, "[奖惩反哺] 赢家 char=${winner.characterId} 在 domain=$domain 无专长档案，跳过")
            }
        } else {
            ZLog.d(TAG, "[奖惩反哺] specialtyProfileRepository 未注入，跳过赢家候选观察和稳定度加速")
        }

        // ── 输家反哺（方案B：写 SystemSuggestionEntity）────────────
        for (loser in losers) {
            if (loser.judgeReasoning.isBlank()) continue
            val loserProfile = db.specialtyProfileDao().getByCharacterAndDomain(loser.characterId, domain)
            if (loserProfile == null) {
                ZLog.d(TAG, "[奖惩反哺] 输家 char=${loser.characterId} 在 domain=$domain 无专长档案，跳过 Suggestion")
                continue
            }
            try {
                db.systemSuggestionDao().insert(
                    SystemSuggestionEntity(
                        id = UUID.randomUUID().toString(),
                        characterId = loser.characterId,
                        specialtyId = loserProfile.id,
                        content = "COMPETITION_FEEDBACK::${loser.judgeReasoning}",
                        reasoning = "来自「${round.projectDomain}」竞赛（题目：「${round.topic}」）的裁判评语，" +
                            "综合得分 ${String.format("%.1f", loser.compositeScore)}",
                        status = "PENDING",
                        createdAt = now,
                    )
                )
                ZLog.d(TAG, "[奖惩反哺] 输家 char=${loser.characterId} 已写 COMPETITION_FEEDBACK Suggestion")
            } catch (e: Exception) {
                ZLog.w(TAG, "[奖惩反哺] 写 SystemSuggestionEntity 失败 char=${loser.characterId}", e)
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    //  裁判准确度：Spearman 秩相关
    // ─────────────────────────────────────────────────────────

    /**
     * 计算本轮裁判排名与用户最终排名（按 userScore 排序）的 Spearman 一致度，
     * 写入 judge_accuracy_log；若裁判 profileId 为 null 则跳过。
     *
     * 注意：对比基准是 userScore（用户原始评分，三种输入方式已在 submitUserScore
     * 统一换算到这个字段），**不是 compositeScore**——compositeScore 本身已经
     * 按权重混入了裁判自己的 judgeScore（裁判信任系数越高，混入比例越大），
     * 拿它去跟裁判排名做相关性，等于部分让裁判跟自己比，会系统性地推高一致度，
     * 完全背离"这个裁判的独立判断是否跟用户的真实想法吻合"这个指标本意。
     * 必须用用户独立给出的 userScore 排名，才是真正不掺裁判自己分数的基准。
     *
     * 连续 JUDGE_ACCURACY_ALERT_CONSECUTIVE 轮低于阈值时，向圆桌发提醒。
     */
    private suspend fun computeAndSaveJudgeAccuracy(
        round: CompetitionRoundEntity,
        judgeProfileId: String?,
    ) {
        if (judgeProfileId == null) return

        try {
            // 重新读取当前轮次所有条目（确保拿到最新的 userScore 和 compositeScore）
            val freshEntries = db.competitionEntryDao().getAllForRound(round.id)
            if (freshEntries.size < 2) return

            // 用户最终排序：按 userScore（不是 compositeScore）降序，第1名排名=1
            val sortedByUser = freshEntries.filter { it.userScore != null }.sortedByDescending { it.userScore }
            val userRanks = sortedByUser.mapIndexed { idx, e -> e.characterId to (idx + 1) }.toMap()

            // 裁判排名：取 judgeScore 降序，第1名=1
            val sortedByJudge = freshEntries.filter { it.judgeScore != null }.sortedByDescending { it.judgeScore }
            val judgeRanks = sortedByJudge.mapIndexed { idx, e -> e.characterId to (idx + 1) }.toMap()

            val commonIds = userRanks.keys.intersect(judgeRanks.keys).toList()
            if (commonIds.size < 2) return

            val spearman = spearmanCorrelation(
                x = commonIds.map { userRanks[it]!!.toDouble() },
                y = commonIds.map { judgeRanks[it]!!.toDouble() },
            )

            // [-1,1] → [0,1]：1.0=排名完全一致，0.5=无相关，0.0=排名完全相反
            val agreementScore = ((spearman + 1.0) / 2.0).toFloat().coerceIn(0f, 1f)

            db.judgeAccuracyLogDao().insert(
                com.zaijian.zhoumuyun.data.db.entity.JudgeAccuracyLogEntity(
                    id = UUID.randomUUID().toString(),
                    judgeProfileId = judgeProfileId,
                    roundId = round.id,
                    agreementScore = agreementScore,
                    createdAt = System.currentTimeMillis(),
                )
            )
            ZLog.i(TAG, "[accuracy] judgeProfileId=$judgeProfileId agreementScore=$agreementScore")

            // 连续低吻合度检测
            val recentLogs = db.judgeAccuracyLogDao().getRecentForJudge(
                judgeProfileId, JUDGE_ACCURACY_ALERT_CONSECUTIVE
            )
            if (recentLogs.size >= JUDGE_ACCURACY_ALERT_CONSECUTIVE &&
                recentLogs.all { it.agreementScore < JUDGE_ACCURACY_ALERT_THRESHOLD }
            ) {
                val judgeName = resolveCharacterName(round.judgeCharacterId)
                postJudgeLowAccuracyAlert(round.judgeCharacterId, judgeName)
            }
        } catch (e: Exception) {
            ZLog.w(TAG, "[accuracy] 计算裁判准确度失败", e)
        }
    }

    /**
     * Spearman 秩相关系数（手写，不引入新库）。
     * x / y 为等长排名列表，返回 -1.0 ~ 1.0。
     */
    private fun spearmanCorrelation(x: List<Double>, y: List<Double>): Double {
        val n = x.size
        if (n < 2) return 0.0
        val d2Sum = x.zip(y).sumOf { (a, b) -> (a - b) * (a - b) }
        return 1.0 - 6.0 * d2Sum / (n * (n.toLong() * n - 1).toDouble())
    }

    // ─────────────────────────────────────────────────────────
    //  圆桌播报（suspend，所有角色名解析在内部完成）
    // ─────────────────────────────────────────────────────────

    /**
     * JUDGING 完成后：向裁判最近活跃的圆桌发送评审结果播报。
     * 以裁判身份发言，摘要各参赛者的裁判点评 + 分数。
     *
     * 注意：所有方法均为 suspend，resolveCharacterName 在 suspend 上下文内调用，
     * 不存在 non-suspend 函数调 suspend 函数的编译错误（执行方案用户备注问题的解决方式）。
     */
    private suspend fun postJudgeResultToRoundtable(
        round: CompetitionRoundEntity,
        judgeName: String,
        judgeResult: CompetitionEngine.JudgeRoundResult,
        entries: List<com.zaijian.zhoumuyun.data.db.entity.CompetitionEntryEntity>,
    ) {
        try {
            val roundtableId = db.roundtableMessageDao()
                .findMostRecentRoundtableIdForSpeaker(round.judgeCharacterId.toString())
                ?: run {
                    ZLog.d(TAG, "[播报] 裁判${round.judgeCharacterId}没有圆桌历史，跳过播报")
                    return
                }

            val content = buildJudgeReportText(
                judgeName = judgeName,
                round = round,
                judgeResult = judgeResult,
                entries = entries,
            )

            db.roundtableMessageDao().insert(
                RoundtableMessageEntity(
                    id = UUID.randomUUID().toString(),
                    roundtableId = roundtableId,
                    speakerId = round.judgeCharacterId.toString(),
                    speakerName = judgeName,
                    content = content,
                    createdAt = System.currentTimeMillis(),
                )
            )
            ZLog.i(TAG, "[播报] 裁判评审结果已发送圆桌 roundtableId=$roundtableId")
        } catch (e: Exception) {
            ZLog.w(TAG, "[播报] 圆桌播报失败", e)
        }
    }

    /**
     * 构建裁判评审报告文案（不调 LLM，直接拼结构化文字）。
     */
    private suspend fun buildJudgeReportText(
        judgeName: String,
        round: CompetitionRoundEntity,
        judgeResult: CompetitionEngine.JudgeRoundResult,
        entries: List<com.zaijian.zhoumuyun.data.db.entity.CompetitionEntryEntity>,
    ): String = buildString {
        append("【${round.projectDomain}竞赛评审结果】题目：「${round.topic}」\n\n")

        // 排名顺序输出（按裁判排名）
        val sortedVerdicts = judgeResult.verdicts.sortedBy { it.rank }
        sortedVerdicts.forEachIndexed { idx, verdict ->
            val charName = resolveCharacterName(verdict.characterId)
            append("第${idx + 1}名 $charName — ${verdict.score}分\n")
            if (verdict.issues.isNotBlank()) {
                append("  问题：${verdict.issues.take(60)}${if (verdict.issues.length > 60) "…" else ""}\n")
            }
            if (verdict.improvementDirection.isNotBlank()) {
                append("  建议：${verdict.improvementDirection.take(60)}${if (verdict.improvementDirection.length > 60) "…" else ""}\n")
            }
            append("\n")
        }

        if (judgeResult.overallComment.isNotBlank()) {
            append("裁判${judgeName}总评：${judgeResult.overallComment}\n\n")
        }

        append("请你对每位参赛者打分（直接评分/排名/评语均可）。")
    }

    /**
     * 向裁判最近活跃的圆桌发送吻合度低告警（以系统/裁判身份发言）。
     * 所有字段均在 suspend 上下文构建，无 non-suspend 调 suspend 问题。
     */
    private suspend fun postJudgeLowAccuracyAlert(
        judgeCharacterId: Int,
        judgeName: String,
    ) {
        try {
            val roundtableId = db.roundtableMessageDao()
                .findMostRecentRoundtableIdForSpeaker(judgeCharacterId.toString())
                ?: return

            // P2-21 冷却：7天内同一圆桌已发过低吻合度告警则跳过，避免每次 finalize 都发
            val cooldownMs = 7L * 24 * 60 * 60 * 1000
            val recentMessages = db.roundtableMessageDao().getByRoundtable(roundtableId)
            val lastAlertTime = recentMessages
                .filter { it.speakerId == "system" && it.content.contains("评判排名") }
                .maxOfOrNull { it.createdAt } ?: 0L
            if (System.currentTimeMillis() - lastAlertTime < cooldownMs) {
                ZLog.i(TAG, "[accuracy-alert] 冷却中，跳过告警 judge=$judgeName")
                return
            }

            val content = "最近几轮竞赛中，${judgeName}的评判排名和你的最终评分有些偏差——" +
                    "要不要找个时间让她回顾一下评审标准，或者再训练一下？"

            db.roundtableMessageDao().insert(
                RoundtableMessageEntity(
                    id = UUID.randomUUID().toString(),
                    roundtableId = roundtableId,
                    speakerId = "system",
                    speakerName = "系统提示",
                    content = content,
                    createdAt = System.currentTimeMillis(),
                )
            )
            ZLog.i(TAG, "[accuracy-alert] 低吻合度提醒已发送 judge=$judgeName roundtableId=$roundtableId")
        } catch (e: Exception) {
            ZLog.w(TAG, "[accuracy-alert] 告警播报失败", e)
        }
    }

    // ─────────────────────────────────────────────────────────
    //  辅助：角色名解析（suspend，避免 non-suspend 调 suspend 问题）
    // ─────────────────────────────────────────────────────────

    /**
     * 解析角色显示名：预设角色（1-9）查 DefaultCharacters，
     * 女儿角色（1000+）查 DaughterCharacterRepository。
     *
     * 本方法为 suspend，只能在 suspend 上下文中调用——这正是避免
     * "non-suspend 函数调 suspend resolveCharacterName"编译错误的根本设计。
     * CompetitionRoundManager 中所有调用点均在 suspend 方法内，合规。
     */
    private suspend fun resolveCharacterName(characterId: Int): String {
        DefaultCharacters.firstOrNull { it.id == characterId }?.let { return it.name }
        return try {
            daughterRepo.getCharacterConfig(characterId)?.name ?: "角色${characterId}"
        } catch (e: Exception) {
            ZLog.w(TAG, "[resolveCharacterName] 查询失败 char=$characterId", e)
            "角色${characterId}"
        }
    }

    /**
     * 查该角色在当前 projectDomain 的 styleNotes（从 specialty_profiles 取）。
     * 查不到或没有对应 profile 时返回空字符串（执行方案允许 styleNotes 为空）。
     */
    private suspend fun resolveStyleNotes(characterId: Int, domain: String): String {
        return try {
            db.specialtyProfileDao()
                .getByCharacterAndDomain(characterId, domain)
                ?.styleNotes
                ?: ""
        } catch (e: Exception) {
            ZLog.w(TAG, "[resolveStyleNotes] 查询失败 char=$characterId domain=$domain", e)
            ""
        }
    }

    // ─────────────────────────────────────────────────────────
    //  辅助：竞赛记忆写入（第9节第5步）
    //
    //  在四个关键节点（开赛/产出完成/裁判公布结果/最终排名）向 MemoryEntity 写
    //  一条竞赛感知记忆，让角色在日常聊天中被问起比赛时能通过 FTS 检索回忆起来。
    //
    //  设计决策：
    //  - domain = WORLD（竞赛属于世界事件，与现有 MemoryDomain 枚举对齐）
    //  - scope = PERSONAL（searchByFts 限定 scope=PERSONAL，必须如此才能被召回）
    //  - importance = 3（中等档，7天内若被召回则保留，足够满足"问起来答得上"）
    //  - isCore = false（按执行方案要求：不需要每次必带，只需能被检索到）
    //  - keywords 包含"比赛 竞赛"等检索词 + projectDomain 词，
    //    保证用户问"你那次比赛怎么样"时 FTS 能命中
    // ─────────────────────────────────────────────────────────

    /**
     * 向指定角色写入一条竞赛感知 Memory（scope=PERSONAL, domain=WORLD, importance=3）。
     * 使用 memoryRepo.save() 保证主表与 FTS4 虚拟表原子同步写入。
     * 写入失败只记录警告日志，不中止主流程。
     *
     * @param characterId 目标角色 ID
     * @param content     记忆内容（自然语言描述，注入 Prompt 时原文展示）
     * @param keywords    FTS4 检索关键词（空格分隔，由 buildCompetitionKeywords 生成）
     * @param roundId     来源竞赛轮次 ID（填入 sourceEventId 供追溯）
     */
    private suspend fun recordCompetitionMemory(
        characterId: Int,
        content: String,
        keywords: String,
        roundId: String,
    ) {
        try {
            val now = System.currentTimeMillis()
            val memory = MemoryEntity(
                id            = MemoryRepository.newId(),
                characterId   = characterId,
                domain        = MemoryDomain.WORLD.name,
                content       = content,
                importance    = 3,
                keywords      = keywords,
                sourceEventId = roundId,
                isCore        = false,
                createdAt     = now,
                updatedAt     = now,
                lastAccessedAt = now,
            )
            memoryRepo.save(memory)
        } catch (e: Exception) {
            ZLog.w(TAG, "[recordCompetitionMemory] 写入失败 char=$characterId", e)
        }
    }

    /**
     * 构建竞赛记忆的 FTS4 关键词串。
     *
     * FTS4 使用 TOKENIZER_UNICODE61，该 tokenizer 不对连续中文做分词（E1 审计
     * 报告任务1 实测证实）。buildFtsQuery 现已改用 ChineseTokenizer 分词后对
     * 每个词加 * 做前缀匹配，因此这里把关键词用空格分隔，每个词才会被 unicode61
     * 索引为独立 token，前缀匹配才能命中。
     *
     * 关键词组成：
     * - 固定竞赛词："比赛 竞赛"（保证"你那次比赛怎么样"能命中）
     * - projectDomain 字符串（按字拆开，让"短篇小说"等三字词也能前缀匹配）
     * - topic 首若干字（让用户问"那道题"时也能命中）
     * - 调用方传入的额外场景词（如"开赛 参赛 评审 排名"）
     *
     * @param domain    projectDomain 字符串（如"短篇小说"）
     * @param topic     竞赛题目（取首20字）
     * @param extraTags 额外场景关键词（空格分隔）
     */
    private fun buildCompetitionKeywords(
        domain: String,
        topic: String,
        extraTags: String,
    ): String {
        val base = setOf("比赛", "竞赛")
        // 把 domain 整体 + 每个字拆开，让前缀匹配更可靠
        val domainTokens = setOf(domain) + domain.map { it.toString() }
        // topic 前20字
        val topicTokens = setOf(topic.take(20)) + topic.take(10).map { it.toString() }
        val extras = extraTags.split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
        return (base + domainTokens + topicTokens + extras)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    // ─────────────────────────────────────────────────────────
    //  辅助：解析参赛者 ID 列表
    // ─────────────────────────────────────────────────────────

    private fun parseParticipantIds(json: String): List<Int> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getInt(it) }
        } catch (e: Exception) {
            ZLog.w(TAG, "[parseParticipantIds] JSON 解析失败: $json", e)
            emptyList()
        }
    }

    // ─────────────────────────────────────────────────────────
    //  辅助：将 runJudging 产生的 judgeReasoning 喂给候选修正池
    //
    //  设计决策：
    //  - 复用 SpecialtyProfileRepository 的 CandidateObservation 数据结构
    //    （trait/firstSeenAt/occurrenceCount/lastSeenAt），序列化逻辑在本方法内自持，
    //    不依赖 SpecialtyProfileRepository（那个类操作 specialty_profiles 表，
    //    而这里操作的是 judge_profiles.candidateCorrectionsJson，独立存储）。
    //  - 每条 reasoning = issues + improvementDirection 合并后作为一个 trait 候选，
    //    先通过 CompetitionEngine.matchJudgeCorrectionCandidate 语义去重，
    //    命中则累加 occurrenceCount，未命中则新增条目。
    //  - 写入失败只记录 warning，不中止 runJudging 主流程。
    // ─────────────────────────────────────────────────────────

    /**
     * 将本轮裁判对每位参赛者的评审意见（issues + improvementDirection）
     * 提炼为候选修正条目，写入 [judgeProfileId] 对应档案的 candidateCorrectionsJson。
     *
     * 调用时机：runJudging ③ 写完裁判次数之后，④ 状态流转之前。
     */
    private suspend fun feedJudgeCorrectionCandidates(
        judgeProfileId: String,
        judgeCharacterId: Int,
        domain: String,
        entries: List<CompetitionEntryEntity>,
        judgeResult: CompetitionEngine.JudgeRoundResult,
    ) {
        try {
            // B-2 修复：整个循环体（跨所有 entry）用同一把按 judgeProfileId 维度的
            // 锁包住，而不是循环内部逐条加锁又解锁——否则两个并发调用仍可能在
            // entry 与 entry 之间交替读写同一份 candidateCorrectionsJson，
            // 出现部分交织的 lost update。必须整段串行化。
            getJudgeCandidateMutex(judgeProfileId).withLock {
                // 一次性读取 + 损坏检测/修复，提到循环外。
                // profile 不存在：静默用空列表，不写回（没有行可更新，语义等价于原来的 ?: continue）。
                // JSON 损坏：立即写回空数组，不依赖后续是否有非空 reasoning 的循环体来触发修复。
                val profile = db.judgeProfileDao().getById(judgeProfileId)
                val candidates: MutableList<CorrectionCandidate> = if (profile == null) {
                    mutableListOf()
                } else {
                    try {
                        parseCorrectionCandidates(profile.candidateCorrectionsJson)
                    } catch (e: CorrectionPoolCorruptedException) {
                        ZLog.e(TAG, "裁判修正池JSON损坏，将以空池继续并立即写回。judgeProfileId=$judgeProfileId " +
                            "原始JSON（可从日志恢复）：${e.corruptedJson}", e)
                        val empty = mutableListOf<CorrectionCandidate>()
                        db.judgeProfileDao().updateCandidateCorrections(
                            judgeProfileId,
                            serializeCorrectionCandidates(empty),
                        )
                        empty
                    }
                }

                for (entry in entries) {
                    val verdict = judgeResult.verdicts.find { it.characterId == entry.characterId }
                        ?: continue

                    // 拼合 reasoning：issues + improvementDirection（两者均不为空才有意义）
                    val reasoning = buildString {
                        if (verdict.issues.isNotBlank()) append(verdict.issues)
                        if (verdict.issues.isNotBlank() && verdict.improvementDirection.isNotBlank()) append("\n")
                        if (verdict.improvementDirection.isNotBlank()) append(verdict.improvementDirection)
                    }.trim()

                    if (reasoning.isBlank()) continue

                    if (profile == null) continue

                    // 语义匹配：判断这条 reasoning 是否命中已有候选
                    val matchedTrait: String? = if (candidates.isEmpty()) {
                        null
                    } else {
                        competitionEngine.matchJudgeCorrectionCandidate(
                            newCorrection   = reasoning,
                            existingCorrections = candidates.map { it.trait },
                        )
                    }

                    // 累加命中次数，或新增候选条目
                    val now = System.currentTimeMillis()
                    if (matchedTrait != null) {
                        val existing = candidates.find { it.trait == matchedTrait }
                        if (existing != null) {
                            existing.occurrenceCount += 1
                            existing.lastSeenAt = now
                        } else {
                            // 极端情况：matchedTrait 已不在列表（不应发生），降级为新增
                            candidates.add(CorrectionCandidate(reasoning, now, 1, now))
                        }
                    } else {
                        candidates.add(CorrectionCandidate(reasoning, now, 1, now))
                    }

                    ZLog.d(TAG, "[feedJudgeCorrection] char=${entry.characterId} " +
                        "matched=${matchedTrait != null} " +
                        "poolSize=${candidates.size} " +
                        "trait前30字=${reasoning.take(30)}")
                }

                // P2-1 修复：DB 写入从循环内移到循环外（仍在锁内），
                // 将 N 次独立写合并为单次写，减少 DB I/O。
                if (candidates.isNotEmpty()) {
                    val updatedJson = serializeCorrectionCandidates(candidates)
                    db.judgeProfileDao().updateCandidateCorrections(judgeProfileId, updatedJson)
                }
            }
        } catch (e: Exception) {
            ZLog.w(TAG, "[feedJudgeCorrection] 候选修正池写入失败 judgeId=$judgeCharacterId domain=$domain", e)
        }
    }

    // ─────────────────────────────────────────────────────────
    //  辅助：CorrectionCandidate 本地数据结构 + JSON 序列化
    //  （与 SpecialtyProfileRepository.CandidateObservation 同款结构，
    //   独立持有，不跨层依赖）
    // ─────────────────────────────────────────────────────────

    /** 裁判修正池中的单条候选观察，结构与 CandidateObservation 完全对齐 */
    private data class CorrectionCandidate(
        val trait: String,
        val firstSeenAt: Long,
        var occurrenceCount: Int,
        var lastSeenAt: Long,
    )

    /** 裁判修正池 JSON 损坏时抛出的专用异常，携带原始 JSON 以便日志恢复 */
    class CorrectionPoolCorruptedException(
        val corruptedJson: String,
        cause: Throwable,
    ) : Exception("裁判修正池JSON损坏: ${cause.message}", cause)

    private fun parseCorrectionCandidates(json: String): MutableList<CorrectionCandidate> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                CorrectionCandidate(
                    trait           = obj.getString("trait"),
                    firstSeenAt     = obj.getLong("firstSeenAt"),
                    occurrenceCount = obj.getInt("occurrenceCount"),
                    lastSeenAt      = obj.getLong("lastSeenAt"),
                )
            }.toMutableList()
        } catch (e: Exception) {
            throw CorrectionPoolCorruptedException(corruptedJson = json, cause = e)
        }
    }

    private fun serializeCorrectionCandidates(list: List<CorrectionCandidate>): String {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(org.json.JSONObject().apply {
                put("trait",           c.trait)
                put("firstSeenAt",     c.firstSeenAt)
                put("occurrenceCount", c.occurrenceCount)
                put("lastSeenAt",      c.lastSeenAt)
            })
        }
        return arr.toString()
    }
}
