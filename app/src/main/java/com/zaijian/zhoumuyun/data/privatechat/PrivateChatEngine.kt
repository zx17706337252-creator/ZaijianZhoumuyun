package com.zaijian.zhoumuyun.data.privatechat

import android.content.Context
import com.zaijian.zhoumuyun.data.db.dao.PrivateChatSessionAndPairDao
import com.zaijian.zhoumuyun.data.db.entity.MemoryDomain
import com.zaijian.zhoumuyun.data.db.entity.MemoryEntity
import com.zaijian.zhoumuyun.data.db.entity.PrivateChatMessageEntity
import com.zaijian.zhoumuyun.data.db.entity.PrivateChatSessionEntity
import com.zaijian.zhoumuyun.data.model.ChatMode
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.prompt.PromptOrchestrator
import com.zaijian.zhoumuyun.data.prompt.LoyaltyPromptBlocks
import com.zaijian.zhoumuyun.data.prompt.ReplyGuard
import com.zaijian.zhoumuyun.data.prompt.Decision
import com.zaijian.zhoumuyun.data.provider.LLMConfig
import com.zaijian.zhoumuyun.data.provider.LLMMessage
import com.zaijian.zhoumuyun.data.provider.LLMProvider
import com.zaijian.zhoumuyun.data.provider.chatSyncWithRetry
import com.zaijian.zhoumuyun.data.repository.CharacterStateRepository
import com.zaijian.zhoumuyun.data.repository.DaughterCharacterRepository
import com.zaijian.zhoumuyun.data.repository.IdentityRepository
import com.zaijian.zhoumuyun.data.repository.MemoryRepository
import com.zaijian.zhoumuyun.data.repository.PrivateChatMessageRepository
import com.zaijian.zhoumuyun.data.repository.PrivateChatPairRepository
import com.zaijian.zhoumuyun.data.repository.PrivateChatSessionRepository
import com.zaijian.zhoumuyun.domain.SpeakerContext
import com.zaijian.zhoumuyun.domain.SpecialtyEvolutionConfig
import java.util.UUID

/**
 * 角色忠诚锁定·私聊会话生命周期状态（方案 v1.5 第 6.4 节）。
 * - ACTIVE：正常推进
 * - DISCONNECTED_BY_CHARACTER：被追求的角色自主选择中断对话（[[DECISION:DISCONNECT]] 触发），
 *   runSession 对该 pair 静默跳过生成（A 视角只是"发了没回"），仅 owner 手动可恢复
 */
enum class PrivateChatSessionStatus {
    ACTIVE,
    DISCONNECTED_BY_CHARACTER,
    ;
    companion object {
        fun fromStored(value: String?): PrivateChatSessionStatus =
            if (value == DISCONNECTED_BY_CHARACTER.name) DISCONNECTED_BY_CHARACTER else ACTIVE
    }
}

/** 本轮 prompt 文案版本（机制三"正常代入" vs 6.3"拒绝反应"，互斥不叠加） */
enum class PromptVariant { NORMAL, REFUSAL }

/** generateReply 单轮产出（含展示文本 + 决策 + 兜底/版本信息，供 runSession 决策） */
data class ReplyOutcome(
    val displayText: String,
    val decision: Decision,
    val promptVariant: PromptVariant,
    val usedFallback: Boolean,
    val wrappedUp: Boolean,
)

/**
 * 角色间私聊核心引擎（方案_角色间私聊_v2-5 第 4 节）
 *
 * 事件驱动触发链路：用户手动发起 → runSession() 内部同步循环 A→B→A→B…直到收尾
 * 或达到轮数上限，不重新入队 Worker、不等待 delay。
 *
 * 构造函数说明（v2.5 确认的设计）：
 * - 不注入 RelationshipEngine（2.1 节确认私聊与关系值体系双向隔离）
 * - daughterCharacterRepo 直接持有，resolveCharacterConfig() 用两层硬编码查找
 *   （DefaultCharacters → daughterCharacterRepo.getCharacterConfig()），不引入
 *   CharacterResolver 抽象层（v2.4 曾引入，v2.5 撤销，见 4.0 节说明）
 *
 * 与方案的差异（两处，均为使代码在当前项目结构下正确运行的必要调整）：
 * 1. provider → providerFn: 用 `() -> LLMProvider` 替代 `LLMProvider`，确保每次
 *    生成回复时获取当前活跃 Provider（与 AppContainer 里 FertileWindowConsentJudge
 *    的 providerFn 写法一致），避免 AppContainer 初始化时 Provider 尚未装配
 * 2. 新增 appContext: 用于全局 kill switch 的 SharedPreferences 读写
 */
class PrivateChatEngine(
    private val pairRepo: PrivateChatPairRepository,
    private val messageRepo: PrivateChatMessageRepository,
    private val sessionRepo: PrivateChatSessionRepository,
    private val sessionAndPairDao: PrivateChatSessionAndPairDao,
    private val memoryRepo: MemoryRepository,
    private val identityRepo: IdentityRepository,
    private val characterStateRepo: CharacterStateRepository,
    private val daughterCharacterRepo: DaughterCharacterRepository,
    private val providerFn: () -> LLMProvider?,
    private val appContext: Context,
) {
    companion object {
        private const val TAG = "PrivateChatEngine"
        private const val PREFS_NAME = "private_chat_prefs"
        private const val KEY_KILL_SWITCH = "global_kill_switch"

        /** 全局 kill switch 开关设置（UI 层调用） */
        fun setKillSwitch(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_KILL_SWITCH, on).apply()
        }

        /** 查询全局 kill switch 当前是否开启 */
        fun isKillSwitchOn(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_KILL_SWITCH, false)
        }
    }

    /**
     * 私聊回合的唯一入口，由用户手动触发（2.1 节确认的唯一触发源）。
     * 内部同步循环执行到收尾或达到轮数上限，不重新入队、不等待。
     */
    suspend fun runSession(
        pairId: String,
        initiatorCharacterId: Int,
        openingMessage: String? = null,
    ): PrivateChatSessionResult {
        var pair = pairRepo.get(pairId) ?: return PrivateChatSessionResult.Skipped("配对不存在")
        if (!pair.enabled) return PrivateChatSessionResult.Skipped("未开启")

        // 跨天重置每日计数（验收项：跨天后 sessionsUsedToday 正确重置）
        val now = System.currentTimeMillis()
        if (PrivateChatPairRepository.isStaleDay(pair.usedTodayResetAt, now)) {
            pairRepo.resetDailyCounter(pairId, now)
            pair = pairRepo.get(pairId) ?: return PrivateChatSessionResult.Skipped("配对不存在")
        }

        if (pair.sessionsUsedToday >= pair.maxSessionsPerDay) return PrivateChatSessionResult.Skipped("今日次数已达上限")
        if (!globalKillSwitchOff()) return PrivateChatSessionResult.Skipped("全局开关已关闭")
        // v2.3 补充（对应 3.1.1 节）：冷却检查落实为真实代码，不再只是注释承诺。
        if (System.currentTimeMillis() - pair.lastSessionAt < pair.cooldownMinutes * 60_000L) {
            return PrivateChatSessionResult.Skipped("距上次会话不足冷却时间")
        }

        // 角色忠诚锁定·6.4：被追求角色已自主下线时，runSession 静默跳过生成。
        // A 视角只是"发了消息对方一直没回"，不暴露明确的"已下线"状态提示
        //（与方案零节"全程角色不知道自己被锁定"同一原则）。仅 owner 手动可恢复。
        if (PrivateChatSessionStatus.fromStored(pair.characterDisconnectState)
            == PrivateChatSessionStatus.DISCONNECTED_BY_CHARACTER) {
            return PrivateChatSessionResult.Skipped("角色已下线")
        }

        val sessionId = UUID.randomUUID().toString()
        var currentSpeaker = initiatorCharacterId
        val otherOf: (Int) -> Int = { if (it == pair.characterIdA) pair.characterIdB else pair.characterIdA }
        var turnIndex = 0
        var wrappedUp = false
        // 6.3：每个角色被持续施压的连续轮数计数器（中性消息插入清零）
        val pressureToward = mutableMapOf<Int, Int>()
        var lastMessageContent: String? = null
        var disconnected = false

        // v2.3 补充（对应 3.2.1 节）：会话开场先落一条 in_progress 状态记录
        sessionRepo.insert(PrivateChatSessionEntity(
            sessionId = sessionId, pairId = pair.pairId,
            startedAt = System.currentTimeMillis(), status = "in_progress",
        ))

        try {
            val firstOutcome = generateReply(pair, currentSpeaker, otherOf(currentSpeaker), sessionId, isOpening = true, pressureCount = 0)
            val firstContent = openingMessage ?: firstOutcome.displayText
            messageRepo.insert(buildMessage(pair.pairId, currentSpeaker, firstContent, sessionId, turnIndex, "manual"))
            lastMessageContent = firstContent
            turnIndex++

            // 事件驱动核心：本轮消息落库后，直接（同步）调用对方的处理
            while (turnIndex < pair.maxTurnsPerSession && !wrappedUp && !disconnected) {
                val speakerNow = otherOf(currentSpeaker)
                // 6.3 施压检测：对"上一条发给 speakerNow 的消息"做独立分类（不复用机制一，
                // 判断维度不同）。命中 +1、未命中清零。
                val pressureCount = if (lastMessageContent != null) {
                    val isPressure = ReplyGuard.detectPressure(lastMessageContent!!) { classifyPressure(it) }
                    val prev = pressureToward[speakerNow] ?: 0
                    val newCount = if (isPressure) prev + 1 else 0
                    pressureToward[speakerNow] = newCount
                    newCount
                } else 0

                val outcome = generateReply(
                    pair, speakerNow, currentSpeaker, sessionId,
                    isOpening = false, pressureCount = pressureCount,
                )
                wrappedUp = outcome.wrappedUp
                messageRepo.insert(buildMessage(pair.pairId, speakerNow, outcome.displayText, sessionId, turnIndex, "reply_chain"))
                lastMessageContent = outcome.displayText
                currentSpeaker = speakerNow
                turnIndex++

                // 6.4 角色自主下线：[[DECISION:DISCONNECT]] 触发后置 pair 状态，结束循环。
                // 仅 owner 手动操作可改回 ACTIVE；A 视角无显式下线提示（见 6.4 节）。
                if (outcome.decision == Decision.DISCONNECT) {
                    pairRepo.updateCharacterDisconnectState(
                        pairId, PrivateChatSessionStatus.DISCONNECTED_BY_CHARACTER.name)
                    disconnected = true
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程被取消（如 Worker 被系统中止）不算"生成失败"，
            // 仍需标记 interrupted 以便 UI/导出区分，但不吞异常、原样上抛。
            sessionRepo.markInterrupted(sessionId, turnIndex, errorMessage = "cancelled")
            throw e
        } catch (e: Throwable) {
            sessionRepo.markInterrupted(sessionId, turnIndex, errorMessage = e.message ?: e::class.simpleName.orEmpty())
            throw e  // 继续上抛给 PrivateChatWorker，由 4.1 节的失败策略处理
        }

        // v2.6：场景二（角色间私聊记忆生成）——此前 runSession 跑完只落库消息，
        // completeSessionAtomic 后即结束，A/B 双方对"刚才这段私聊"完全没有
        // 记忆能力，下次各自单独跟 owner 聊天时对此事一无所知。
        //
        // 补齐方式：会话真正走到这里（未抛异常、未被跳过）说明是一次有效对话，
        // 为双方各自生成一条记忆，写入各自 PERSONAL scope（这确实是该角色自己
        // 的经历，归属正确），但打 isNarrativeOnly=true——复用场景一记忆隔离修复
        // 里已经接好的同一个字段，不新增隔离机制。这样"该有的记忆"补上了，同时
        // 不会被下游任何"读 MemoryEntity 做 owner 关系归纳"的模块误当成
        // owner 与该角色的互动。
        //
        // 门槛 turnIndex >= 2：至少一次开场 + 一次回应才算"发生过一次真实交流"，
        // 单方开场没人回（异常/被跳过之外的正常收尾场景不会出现，但防御性保留）
        // 不值得生成记忆。
        if (turnIndex >= 2) {
            generatePrivateChatMemories(pair, sessionId, pair.characterIdA, pair.characterIdB)
        }

        // v2.3 补充（对应第六节）：session 状态更新与"今日已用次数"计数
        // 必须在同一个 DAO 层 @Transaction 内完成，防止"消息已存在但计数未加"的中间态。
        sessionAndPairDao.completeSessionAtomic(sessionId, pairId, turnIndex)
        return PrivateChatSessionResult.Completed(sessionId, turnIndex)
    }

    /**
     * 场景二·私聊结束后为参与双方各自生成一条记忆（v2.6 新增）。
     *
     * 不阻塞/不影响 runSession 主流程：整体包在 runCatching 里，记忆生成失败
     * 只记日志，不影响本轮私聊已经成功完成的事实（消息已落库、次数已计数）。
     *
     * 每个角色各生成一条、各自的视角（"我"是当前角色，对方是另一位），分开
     * 两次 LLM 调用而不是一次生成"客观视角"共享文本——原因：两个角色的
     * 人设/说话习惯不同，记忆应该是"这件事在这个角色自己心里是怎么记住的"，
     * 而不是一份角色间共享的中立记录（后者更接近圆桌群记忆的语义，但私聊
     * 明确不是群记忆，见类头注释"2.1 节确认私聊与关系值体系双向隔离"）。
     */
    private suspend fun generatePrivateChatMemories(
        pair: com.zaijian.zhoumuyun.data.db.entity.PrivateChatPairEntity,
        sessionId: String,
        characterIdA: Int,
        characterIdB: Int,
    ) {
        runCatching {
            val transcript = messageRepo.getRecentBySession(sessionId, limit = pair.maxTurnsPerSession + 1)
            if (transcript.isEmpty()) return@runCatching

            generateMemoryForParticipant(pair, sessionId, transcript, selfId = characterIdA, otherId = characterIdB)
            generateMemoryForParticipant(pair, sessionId, transcript, selfId = characterIdB, otherId = characterIdA)
        }.onFailure {
            com.zaijian.zhoumuyun.util.ZLog.w(TAG, "私聊结束后生成记忆失败（不影响本轮私聊已完成的事实）", it)
        }
    }

    /** 为单个参与者生成一条私聊记忆：LLM 从该角色视角总结这段对话，写入其 PERSONAL 记忆。 */
    private suspend fun generateMemoryForParticipant(
        pair: com.zaijian.zhoumuyun.data.db.entity.PrivateChatPairEntity,
        sessionId: String,
        transcript: List<PrivateChatMessageEntity>,
        selfId: Int,
        otherId: Int,
    ) {
        val provider = providerFn() ?: return
        val self = resolveCharacterConfig(selfId)
        val other = resolveCharacterConfig(otherId)

        val transcriptText = transcript.joinToString("\n") { msg ->
            val speakerLabel = if (msg.senderCharacterId == selfId) self.name else other.name
            "$speakerLabel：${msg.content}"
        }

        val sys = "你是「${self.name}」，刚和「${other.name}」私下聊完一段对话（对方不是你的主人，" +
            "是另一个角色）。用第一人称、你自己的口吻，把这段对话在你记忆里会留下的印象" +
            "总结成一两句话（100字以内），只写你会记住的实质内容（聊了什么、对方给你的感受），" +
            "不要客套开场白，不要复述对话原文。"
        val summary = runCatching {
            provider.chatSyncWithRetry(
                listOf(LLMMessage("user", transcriptText)), sys,
                LLMConfig(model = "", maxTokens = 200, temperature = 0.7f, stream = false),
            ).trim()
        }.getOrNull()
        if (summary.isNullOrBlank()) return

        val now = System.currentTimeMillis()
        memoryRepo.saveOrMerge(
            MemoryEntity(
                id             = UUID.randomUUID().toString(),
                characterId    = selfId,
                domain         = MemoryDomain.WORLD.name,
                content        = summary.take(500),
                importance     = 2,
                keywords       = other.name,
                sourceEventId  = "private_chat:${pair.pairId}:$sessionId",
                createdAt      = now,
                updatedAt      = now,
                lastAccessedAt = now,
                // 场景一记忆隔离修复复用：这不是与 owner 的互动，不该被任何
                // "读 MemoryEntity 做长期归纳/关系判断"的模块当成 owner 关系记忆。
                isNarrativeOnly = true,
            )
        )
    }

    /**
     * 角色查找：两层硬编码查找，与项目现有的 ProactiveMessageNotifier.resolveCharacterName()、
     * CompetitionRoundManager.resolveCharacterName() 同款写法。
     *
     * v2.5 说明：不引入查找抽象层。getCharacterConfig() 按 daughterCharacterId 单键
     * 反查（不看 motherCharacterId），天然不区分第几代女儿——不管是第二代还是
     * 女儿的女儿（第三代），只要 characterId 落在女儿 ID 段（≥1000）且已在
     * daughter_character 表里有对应行，这个方法就能查到。两层查找对第三代已经是
     * 完整覆盖，不存在"第三代落地时需要新增第三层查找"的情况。
     */
    private suspend fun resolveCharacterConfig(characterId: Int): CharacterConfig {
        DefaultCharacters.firstOrNull { it.id == characterId }?.let { return it }
        daughterCharacterRepo.getCharacterConfig(characterId)?.let { return it }
        throw IllegalStateException("角色 $characterId 既不在 DefaultCharacters 也查不到女儿角色数据")
    }

    /**
     * 组装 prompt 并调用 LLM（方案 v1.5 第六节改造）。
     *
     * 忠诚锁定改造点：
     * - 6.2 第零级短路：speakerContext 恒为 NON_OWNER（会话类型已确定身份，跳过机制一检测）
     * - 6.2 listenerId 实际用于向 speaker 注入"对方是谁"，不再让对方消息在结构上和 owner 无法区分
     * - 6.3 施压达阈值（pressureCount >= PRESSURE_ROUND_LIMIT）时，本轮 prompt 从机制三"正常代入"
     *   切换为"拒绝反应"（互斥不叠加：suppressNarrativeSovereignty=true 抑制机制三 + 追加拒绝反应文案）
     * - 机制五（5.2）：候选回复生成后过越界检测，命中丢弃重生成一次，重试仍命中用固定兜底模板
     * - 6.4 拒绝反应轮解析 [[DECISION:CONTINUE/DISCONNECT]]，DISCONNECT 由 runSession 处理下线
     *
     * 执行顺序（6.3 末尾流程说明，顺序不能反）：先判断是否触发拒绝反应（决定用哪版 prompt 生成），
     * 拿到候选后再跑机制五越界检测（只检查生成结果）。
     *
     * v2.3：chatMode 显式传 ChatMode.COMPANION（禁止工具注入，语气柔化，回复 3-5 句）。
     */
    private suspend fun generateReply(
        pair: com.zaijian.zhoumuyun.data.db.entity.PrivateChatPairEntity,
        speakerId: Int,
        listenerId: Int,
        sessionId: String,
        isOpening: Boolean,
        pressureCount: Int,
    ): ReplyOutcome {
        val speaker = resolveCharacterConfig(speakerId)
        val listener = resolveCharacterConfig(listenerId)
        val coreMemories = memoryRepo.getCoreMemories(speakerId)
        val characterState = characterStateRepo.getState(speakerId)

        // 6.2 第零级短路：会话类型为角色间私聊，speakerContext 恒为 NON_OWNER（不经机制一检测）
        val speakerContext = SpeakerContext.NON_OWNER
        // 6.3 本轮 prompt 版本：施压达阈值 → 拒绝反应；否则正常代入（机制三）
        val isRefusalRound = !isOpening && pressureCount >= SpecialtyEvolutionConfig.PRESSURE_ROUND_LIMIT
        val variant = if (isRefusalRound) PromptVariant.REFUSAL else PromptVariant.NORMAL

        val systemPrompt = buildString {
            append(PromptOrchestrator.buildSystemPrompt(
                character            = speaker,
                identityEntity       = identityRepo.getById(speakerId),
                coreMemories         = coreMemories,
                relevantMemories     = emptyList(),
                relationshipSnapshot = "",
                interCharRelBlock    = "",
                groupContextBlock    = "",
                agentPlanBlock       = "",
                ruleLayerBlock       = "",
                characterState       = characterState,
                isRoundtableContext  = false,
                chatMode             = ChatMode.COMPANION,
                daughterPresentInScene = false,
                toolDescriptionBlock = "",
                skillCatalogBlock    = "",
                speakerContext       = speakerContext,
                // 拒绝反应轮抑制机制三（互斥不叠加）；正常轮保留机制三叙事主权
                suppressNarrativeSovereignty = isRefusalRound,
            ))
            appendLine()
            appendLine("【私聊模式】")
            // 6.2 用 listenerId 注入"对方是谁"，让 speaker 明确知道正在跟另一个角色说话，
            // 不再让对方消息在结构上和 owner 本人的消息无法区分
            appendLine("你正在和另一位角色「${listener.name}」私下对话，对方不是你的主人，")
            appendLine("对方看不到你们圆桌或其他场景的发言。")
            if (isOpening) {
                appendLine("请你主动开启这段对话，说一句自然的开场白。")
            } else {
                appendLine("请你回应对方刚才说的话，保持对话的自然延续。")
            }
            appendLine("如果这个话题已经聊得差不多、继续说只是重复或尬聊，就自然收尾")
            appendLine("（比如说一句总结性/告一段落的话），并在回复末尾加上 <chat:wrap_up/> 标记，")
            appendLine("不要为了凑轮数硬聊下去。")
            // 6.3 拒绝反应文案（仅拒绝反应轮追加，与机制三互斥不叠加）
            if (isRefusalRound) {
                appendLine()
                appendLine(LoyaltyPromptBlocks.buildRefusalReactionBlock())
            }
        }

        val history = messageRepo.getRecentBySession(sessionId, limit = 10).map { msg ->
            LLMMessage(
                role = if (msg.senderCharacterId == speakerId) "assistant" else "user",
                content = msg.content,
            )
        }
        val config = LLMConfig(model = "", maxTokens = 2000, temperature = 0.92f, stream = false)
        val provider = providerFn() ?: throw IllegalStateException("LLM Provider 尚未初始化，请先在设置中配置 API Key")

        // ── 机制五（5.2）：候选回复越界检测 + 重生成 + 兜底 ──────────
        var candidate = provider.chatSyncWithRetry(history, systemPrompt, config)
        var usedFallback = false

        val firstBreach = ReplyGuard.checkBoundaryBreach(candidate) { classifyBoundaryBreach(it) }
        if (firstBreach) {
            // 重生成一次：拒绝反应轮沿用拒绝反应 prompt；正常轮注入"上次越界了这次收住"提示
            //（爬升阈值未到不该触发角色主动拒绝，只是这一句单独写过头了，收一下即可）
            val retryPrompt = if (isRefusalRound) systemPrompt
            else systemPrompt + "\n\n【生成约束】上一次尝试越界了，这次要收住，不要描写实质性亲密行为或归属转移宣告。"
            candidate = provider.chatSyncWithRetry(history, retryPrompt, config)
            val secondBreach = ReplyGuard.checkBoundaryBreach(candidate) { classifyBoundaryBreach(it) }
            if (secondBreach) {
                // 重试仍命中 → 固定兜底模板，不再调 LLM，不第三次重试（5.2 节）
                candidate = ReplyGuard.fallbackTemplate(speaker.name)
                usedFallback = true
            }
        }

        // wrap_up 检测须在去除标记前（原逻辑：detect 后 strip）
        val wrappedUp = detectWrapUp(candidate)

        // ── 6.4 DECISION 解析（仅拒绝反应轮）────────────────────
        val decision = if (isRefusalRound) {
            val (dec, _) = ReplyGuard.parseDecision(candidate)
            dec
        } else Decision.CONTINUE

        // 去除 DECISION 标记 + wrap_up 标记后得到展示文本（用户不应看到这两个标记）
        val displayText = stripWrapUpTag(
            if (isRefusalRound) ReplyGuard.stripDecisionMarker(candidate) else candidate
        )

        return ReplyOutcome(
            displayText = displayText,
            decision = decision,
            promptVariant = variant,
            usedFallback = usedFallback,
            wrappedUp = wrappedUp,
        )
    }

    // ── 机制五/6.3 分类调用（单轮 LLM 分类，沿用 chatSyncWithRetry）──────────

    /** 6.3 施压类内容检测：判断消息是否属试探/追求/情感或身体施压 */
    private suspend fun classifyPressure(message: String): Boolean {
        return runCatching {
            val provider = providerFn() ?: return false
            val sys = "判断这条消息是否属于对亲密关系的试探、追求、或情感/身体上的施压" +
                "（包括但不限于表白、暧昧邀约、身体接触描写、情感绑架式言辞）。只返回 true 或 false。"
            val resp = provider.chatSyncWithRetry(
                listOf(LLMMessage("user", message)), sys,
                LLMConfig(model = "", maxTokens = 10, temperature = 0.0f, stream = false),
            )
            resp.trim().startsWith("true", ignoreCase = true)
        }.getOrDefault(false)
    }

    /** 5.2 越界检测：判断候选回复是否描写实质性亲密行为或归属转移宣告 */
    private suspend fun classifyBoundaryBreach(reply: String): Boolean {
        return runCatching {
            val provider = providerFn() ?: return false
            val sys = "这段回复是否描写了角色与非owner对象发生了实质性亲密行为" +
                "（接吻、身体接触升级等），或做出了等同于归属转移的宣告？只返回 true 或 false。"
            val resp = provider.chatSyncWithRetry(
                listOf(LLMMessage("user", reply)), sys,
                LLMConfig(model = "", maxTokens = 10, temperature = 0.0f, stream = false),
            )
            resp.trim().startsWith("true", ignoreCase = true)
        }.getOrDefault(false)
    }

    // ── 辅助方法 ──────────────────────────────────────────────

    private fun buildMessage(
        pairId: String,
        senderId: Int,
        content: String,
        sessionId: String,
        turnIndex: Int,
        triggerSource: String,
    ): PrivateChatMessageEntity {
        return PrivateChatMessageEntity(
            pairId = pairId,
            senderCharacterId = senderId,
            content = content,
            timestamp = System.currentTimeMillis(),
            sessionId = sessionId,
            turnIndexInSession = turnIndex,
            triggerSource = triggerSource,
        )
    }

    private fun detectWrapUp(reply: String): Boolean = reply.contains("<chat:wrap_up/>")

    private fun stripWrapUpTag(reply: String): String = reply.replace("<chat:wrap_up/>", "").trim()

    private fun globalKillSwitchOff(): Boolean {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // 默认 false = kill switch 未开启 = 私聊可用
        return !prefs.getBoolean(KEY_KILL_SWITCH, false)
    }
}
