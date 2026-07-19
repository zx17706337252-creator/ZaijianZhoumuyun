package com.zaijian.zhoumuyun.domain

import com.zaijian.zhoumuyun.data.provider.chatSyncWithRetry
import android.util.Log
import com.zaijian.zhoumuyun.util.ZLog
import com.zaijian.zhoumuyun.data.db.dao.CharacterGoalDao
import com.zaijian.zhoumuyun.data.db.dao.WorldEventDao
import com.zaijian.zhoumuyun.data.db.entity.EventDomain
import com.zaijian.zhoumuyun.data.db.entity.EventType
import com.zaijian.zhoumuyun.data.db.entity.GoalHorizon
import com.zaijian.zhoumuyun.data.db.entity.WorldEventEntity
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.CharacterStateLayer
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.model.toMoodType
import com.zaijian.zhoumuyun.data.provider.LLMConfig
import com.zaijian.zhoumuyun.data.provider.LLMMessage
import com.zaijian.zhoumuyun.data.provider.LLMProvider
import com.zaijian.zhoumuyun.data.provider.ProviderManager
import com.zaijian.zhoumuyun.data.repository.DaughterCharacterRepository
import com.zaijian.zhoumuyun.data.repository.MessageRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "PresenceEngine"

/**
 * Presence Engine — Phase 20（MoodType + Energy + 主动消息触发）
 *
 * Phase 20 新增：
 * - PresenceSnapshot 增加 mood: MoodType 和 energy: Int (0-100)
 * - MoodType 八种枚举（设计方案 §7）
 * - Energy 初始值 70，随 Event 类型微调
 * - 当 energy > 60 且有目标相关事件时，通过 proactiveMessageFlow 广播主动消息意图
 * - noteText 字段：书架便条（Goal + 最近记忆组合生成，无需 LLM）
 *
 * Phase 11 已有：持久化写入 world_events 表
 */
class PresenceEngine(
    private val goalDao: CharacterGoalDao,
    private val eventDao: WorldEventDao? = null,
    private val onProactiveMessage: (suspend (ProactiveMessage) -> Unit)? = null,
    // AI 化主动消息（替代固定文案池）新增依赖，均可空、降级安全：
    // - messageDao 拿不到时，无法组装对话历史，直接走固定文案兜底
    // - daughterCharacterRepo 拿不到时，女儿角色的 persona 解析不到，
    //   仍可用 DefaultCharacters（预设角色）+ 空 persona 兜底生成
    private val messageDao: MessageRepository? = null,
    private val daughterCharacterRepo: DaughterCharacterRepository? = null,
) {

    // ── 主动消息触发（Phase 20 §H）──────────────────────────

    // 批次1 1-6修复：_proactiveMessageFlow/proactiveMessageFlow 提升为 companion object
    // 级别（见下方 companion object）。原为实例成员，Worker 创建的独立 PresenceEngine
    // 实例与前台常驻实例的 Flow 互不相通，Worker 持久化的主动消息不会实时出现在
    // 当前聊天页。提升后所有实例共享同一 Flow，Worker 发射的消息前台能实时收到。

    /**
     * 统一的"广播 + 持久化通知"出口。两处 emit 都改走这里，
     * 保证内存广播（给当前正开着的聊天页用）和落库通知（给离线/后台场景用）
     * 永远成对发生，不会有一边有一边没有。
     *
     * 遗留问题修复（v1.38 批次2 前置发现）：这里同时是两个消费者共用的唯一出口——
     * `_proactiveMessageFlow`（`ChatViewModel` 订阅，直接用 `msg.text` 拼 Snackbar 文案，
     * 前台实时展示）和 `onProactiveMessage`（`ProactiveMessageNotifier.persistAndNotify`，
     * 负责落库 + 系统通知）。此前 `msg.text` 是 `generateProactiveLine()` 的 LLM 原始输出，
     * 未经任何 `[thinking:]`/圆括号心理描写/`[mood:]` 剥离就直接分发给这两个消费者——
     * 不只是 `ProactiveMessageNotifier` 落库这一处会裸露原文，`ChatViewModel` 的 Snackbar
     * 同样会（且是用户唯一能实时看到的那个界面）。之前的方案文档只记录了落库这一侧的口子，
     * 没有覆盖到 Snackbar 这一侧——本次一并修在同一个出口，一次剥离，两个消费者都干净，
     * 不需要在 `ProactiveMessageNotifier` 里再重复剥离一次。
     *
     * 剥离逻辑与 `ChatMessageOrchestrator` 保持一致（thinking→psych→mood 三层），
     * 剥离出的心理感受写回 `ProactiveMessage.psychText`、内心独白写回
     * `ProactiveMessage.thinkingText`，供 `ProactiveMessageNotifier` 落库时原样存入
     * `MessageEntity.psychText`/`MessageEntity.thinkingText`——与私聊消息同规格，
     * 聊天记录里主动消息也能正常展示"内心独白"折叠卡（Snackbar 侧仍暂不展示
     * 心理感受小卡/内心独白卡，与其余"离线态只展示台词"的既有简化一致，
     * 不在此处扩大 Snackbar 的 UI 改动范围，落库这一份数据是完整的，
     * 用户重新打开聊天页即可看到两张卡）。
     * 剥离后文本为空白时回退用原始文本，避免出现空 Snackbar/空消息。
     */
    private suspend fun emitProactiveMessage(msg: ProactiveMessage) {
        val (afterThinking, parsedThinking) = ChatTagParser.stripThinkingTag(msg.text)
        val (afterPsych, parsedPsych) = ChatTagParser.stripPsychText(afterThinking)
        val (afterMood, _) = ChatTagParser.stripMoodTag(afterPsych)
        val cleanMsg = msg.copy(
            text         = afterMood.ifBlank { msg.text },
            psychText    = parsedPsych,
            thinkingText = parsedThinking,
        )

        try {
            _proactiveMessageFlow.emit(cleanMsg)
        } catch (e: Exception) {
            ZLog.w(TAG, "Proactive message emit failed", e)
        }
        try {
            onProactiveMessage?.invoke(cleanMsg)
        } catch (e: Exception) {
            ZLog.w(TAG, "Proactive message persist/notify failed", e)
        }
    }

    // ── 任务完成通知触发（Phase 30 方案二）──────────────────

    private val _taskCompletionFlow = MutableSharedFlow<TaskCompletionMessage>(extraBufferCapacity = 8)
    val taskCompletionFlow = _taskCompletionFlow.asSharedFlow()

    /**
     * 当 JobResultEntity 写入本地 DB 后调用此方法，
     * 向 taskCompletionFlow 发射通知消息，供 CharacterScreen 展示浮层卡片。
     *
     * @param result        刚写入的任务结果实体
     * @param characterName 对应角色的显示名（由调用方从 DefaultCharacters 查询）
     * @param jobTitle      对应任务的标题（由调用方从 ScheduledJobDao 查询）
     */
    suspend fun notifyTaskCompletion(
        result: com.zaijian.zhoumuyun.data.db.entity.JobResultEntity,
        characterName: String,
        jobTitle: String,
    ) {
        // 节流：同一 jobResultId 只发射一次（防止 Flow 重收触发重复）。
        // 用 add() 的返回值做原子的 check-and-add，替代原 contains() + add() 两步——
        // 后者是 check-then-act 竞态：并发下两个调用都可能先 contains=false 再各自 add，导致重复发射。
        val cacheKey = result.id
        if (!notifiedResultIds.add(cacheKey)) return

        // 性能 L2 修复：容量管理已下沉到 BoundedLruSet 内部（removeEldestEntry 自动淘汰），
        // 这里不再需要手动判断 size 并 clear()。

        val summary = when {
            result.status == "success" && !result.output.isNullOrBlank() ->
                result.output.take(80)
            result.status == "failed" && !result.errorMessage.isNullOrBlank() ->
                result.errorMessage.take(80)
            else -> if (result.status == "success") "任务已完成" else "任务遇到了问题"
        }

        try {
            _taskCompletionFlow.emit(
                TaskCompletionMessage(
                    characterId   = result.characterId,
                    characterName = characterName,
                    jobTitle      = jobTitle,
                    resultSummary = summary,
                    jobResultId   = result.id,
                    status        = result.status,
                )
            )
        } catch (e: Exception) {
            ZLog.w(TAG, "TaskCompletion emit failed for result \${result.id}", e)
        }
    }

    /**
     * 刷新某角色的 Presence。
     *
     * Phase 20 升级：计算 mood + energy，持久化包含新字段，
     * 满足触发条件时向 proactiveMessageFlow 广播主动消息意图。
     */
    suspend fun refreshPresence(characterId: Int, characterState: CharacterStateLayer? = null): PresenceSnapshot {
        val topGoal    = goalDao.getTopGoal(characterId)
        val timePeriod = currentTimePeriod()
        val activity   = buildActivity(topGoal?.title, topGoal?.timeHorizon, timePeriod)
        val now        = System.currentTimeMillis()

        // ── Phase 20：计算 mood 和 energy ───────────────────
        val existing = presenceCache[characterId]
        // computeEnergy 按时间段给固定 delta，但 refreshPresence 在 Tier1 每 5 分钟被调用一次，
        // 若每次都叠加 delta，energy 会随调用次数而非真实时间衰减。改为：同一时段只应用一次 delta，
        // 时段切换时才重算；同一时段内的后续刷新直接复用上次 energy。
        // S2问题7修复：使用 compute() 原子操作替代读-判-写三步，避免时段切换瞬间并发重复计算
        var periodChanged = false
        lastEnergyPeriod.compute(characterId) { _, existingPeriod ->
            if (existingPeriod == timePeriod) {
                existingPeriod
            } else {
                periodChanged = true
                timePeriod
            }
        }
        val newEnergy = if (existing != null && !periodChanged) {
            existing.energy
        } else {
            computeEnergy(existing?.energy ?: 70, timePeriod)
        }

        // mood 计算：CharacterStateLayer 是唯一真相来源。
        // 传入了角色当前情绪状态时，直接用 EmotionType 换算；
        // 没有时（尚未接入 / 独处场景）才退回旧的目标进度+时间段猜测。
        val newMood = if (characterState != null) {
            characterState.emotionalState.primaryEmotion.toMoodType(
                intensity        = characterState.emotionalState.intensity,
                emotionalFatigue = characterState.emotionalState.emotionalFatigue,
            )
        } else {
            computeMood(topGoal?.progress, timePeriod, newEnergy)
        }

        val noteText  = buildNoteText(topGoal?.title, topGoal?.progress, timePeriod)

        val snapshot = PresenceSnapshot(
            characterId   = characterId,
            activity      = activity,
            timePeriod    = timePeriod,
            mood          = newMood,
            energy        = newEnergy,
            sourceGoalId  = topGoal?.id,
            goalTitle     = topGoal?.title,
            noteText      = noteText,
            updatedAt     = now,
        )

        // 1. 写内存缓存
        presenceCache[characterId] = snapshot

        // 2. 持久化写入 world_events
        eventDao?.let { dao ->
            try {
                val payload = JSONObject().apply {
                    put("characterId", characterId)
                    put("activity", activity)
                    put("timePeriod", timePeriod.name)
                    put("goalTitle", topGoal?.title ?: "")
                    put("mood", newMood.name)          // Phase 20 新增
                    put("energy", newEnergy)           // Phase 20 新增
                }.toString()

                dao.append(
                    WorldEventEntity(
                        id        = UUID.randomUUID().toString(),
                        type      = EventType.PRESENCE_CHANGED.name,
                        actorId   = characterId.toString(),
                        targetId  = null,
                        domain    = EventDomain.PERSONAL.name,
                        projectId = null,
                        payload   = payload,
                        importance = 1,
                        createdAt = now,
                    )
                )
            } catch (e: Exception) {
                ZLog.w(TAG, "Presence persist failed for char $characterId", e)
            }
        }

        // 3. Phase 20 §H：主动消息触发
        // 批次4-审查修复：P2-7 修复后 buildProactiveMessage 内部不再设置
        // lastProactiveAt，统一由调用方负责。tryEmitContextualProactiveMessage
        // 已正确设置，但 refreshPresence 路径遗漏了 setLastProactiveAt，
        // 导致 refreshPresence 路径的节流失效（每 5 分钟可能重复触发主动消息）。
        // 在此补上节流时间戳的设置。
        if (newEnergy > 60 && topGoal != null) {
            // 新发现修复：读 lastProactiveAt→判断→构建→写 lastProactiveAt 整体
            // 加锁，避免与 tryEmitContextualProactiveMessage()（同一节流键）或
            // 其他并发调用方对同一角色产生重复触发。
            proactiveThrottleLockFor(characterId).withLock {
                val msg = buildProactiveMessage(characterId, topGoal.title, newMood)
                if (msg != null) {
                    setLastProactiveAt(characterId, now)
                    emitProactiveMessage(msg)
                }
            }
        }

        Log.v(TAG, "Presence refreshed: char $characterId → $activity | mood=$newMood energy=$newEnergy")
        return snapshot
    }

    fun getCachedPresence(characterId: Int): PresenceSnapshot? = presenceCache[characterId]

    // Phase 1（zaijian）新增：供 updateMoodFromReply 使用
    internal fun setCachedPresence(characterId: Int, snapshot: PresenceSnapshot) {
        presenceCache[characterId] = snapshot
    }

    // ── Phase 30 方案五：AI 动态文案 ──────────────────────────

    /**
     * 用 LLM 为指定角色生成当日 noteText，并更新内存缓存。
     * 调用前应先判断今日是否已生成（避免重复调用）。
     *
     * @param characterId   角色 ID
     * @param characterName 角色显示名
     * @param persona       角色 persona（来自 CharacterIdentity）
     * @param speechStyle   说话风格（来自 CharacterIdentity）
     * @param goalTitle     当前最高优先级目标标题，null 时不提及目标
     * @param provider      LLM 提供商实例
     */
    suspend fun generateDailyNoteText(
        characterId:   Int,
        characterName: String,
        persona:       String,
        speechStyle:   String,
        goalTitle:     String?,
        provider:      LLMProvider,
    ) {
        try {
            val timePeriod = currentTimePeriod()
            val prompt = buildDailyNotePrompt(
                characterName = characterName,
                persona       = persona,
                speechStyle   = speechStyle,
                goalTitle     = goalTitle,
                timePeriod    = timePeriod,
            )
            val generated = provider.chatSyncWithRetry(
                messages     = listOf(LLMMessage(role = "user", content = prompt)),
                systemPrompt = "你是角色扮演助手，严格以角色身份说话，只输出角色说的那一句话，不加引号，不超过15字，不解释。",
                config       = LLMConfig(
                    model       = "",            // 空字符串触发 buildRequestBody 里的 defaultModel 兜底；
                                                  // 之前误传 provider.id（如 "deepseek"，平台标识非模型名），
                                                  // 导致 API 返回"模型不存在"，被 catch 静默吞掉。
                    maxTokens   = 60,
                    temperature = 0.9f,
                    stream      = false,
                ),
            ).trim()

            if (generated.isNotBlank()) {
                updateNoteText(characterId, generated)
            }
        } catch (e: Exception) {
            ZLog.w(TAG, "Daily note gen failed for char $characterId: ${e.message}")
            // 静默降级：保留已有模板文案，不抛出
        }
    }

    /**
     * 更新内存缓存中某角色的 noteText，并记录生成时间（用于今日去重）。
     * 也可由 ZaijianApp 调用，覆盖默认模板文案。
     */
    fun updateNoteText(characterId: Int, text: String) {
        dailyNoteCache[characterId] = text to System.currentTimeMillis()
        presenceCache[characterId]?.let { snap ->
            presenceCache[characterId] = snap.copy(noteText = text)
        }
    }

    /** 判断指定角色今日 noteText 是否已生成（避免重复 LLM 调用） */
    fun isDailyNoteGenerated(characterId: Int): Boolean {
        val (_, generatedAt) = dailyNoteCache[characterId] ?: return false
        val cal = Calendar.getInstance()
        val todayStart = cal.apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return generatedAt >= todayStart
    }

    private fun buildDailyNotePrompt(
        characterName: String,
        persona:       String,
        speechStyle:   String,
        goalTitle:     String?,
        timePeriod:    TimePeriod,
    ): String {
        val periodLabel = when (timePeriod) {
            TimePeriod.DAWN       -> "清晨"
            TimePeriod.MORNING    -> "上午"
            TimePeriod.NOON       -> "中午"
            TimePeriod.AFTERNOON  -> "下午"
            TimePeriod.EVENING    -> "傍晚"
            TimePeriod.NIGHT      -> "夜晚"
            TimePeriod.LATE_NIGHT -> "深夜"
        }
        val goalPart = if (!goalTitle.isNullOrBlank()) "你正在推进「$goalTitle」。" else ""
        return "你是${characterName}，${persona}。说话风格：${speechStyle}。" +
               "现在是${periodLabel}。${goalPart}" +
               "用第一人称写一句话（15字以内），描述你现在在做什么或在想什么。" +
               "要求自然口语化，有角色个性，不要套话。"
    }

    // ── 内部逻辑 ──────────────────────────────────────────────

    private fun currentTimePeriod(): TimePeriod {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..8   -> TimePeriod.DAWN
            in 9..11  -> TimePeriod.MORNING
            in 12..13 -> TimePeriod.NOON
            in 14..17 -> TimePeriod.AFTERNOON
            in 18..20 -> TimePeriod.EVENING
            in 21..23 -> TimePeriod.NIGHT
            else      -> TimePeriod.LATE_NIGHT
        }
    }

    /**
     * 根据时间段和上一次 energy 计算新的 energy。
     * 夜间自然恢复，高强度时段消耗。
     */
    private fun computeEnergy(prev: Int, period: TimePeriod): Int {
        val delta = when (period) {
            TimePeriod.DAWN       -> +5   // 起床恢复
            TimePeriod.MORNING    -> -2   // 开始消耗
            TimePeriod.NOON       -> +3   // 午休小恢复
            TimePeriod.AFTERNOON  -> -3   // 下午消耗
            TimePeriod.EVENING    -> -1
            TimePeriod.NIGHT      -> +4   // 夜间恢复
            TimePeriod.LATE_NIGHT -> -5   // 深夜透支
        }
        return (prev + delta).coerceIn(10, 100)
    }

    /**
     * 根据目标进度、时间段、energy 推导 mood。
     */
    private fun computeMood(progress: Float?, period: TimePeriod, energy: Int): MoodType {
        if (energy < 30) return MoodType.TIRED
        if (progress != null && progress >= 0.9f) return MoodType.SATISFIED
        if (progress != null && progress >= 0.5f) return MoodType.FOCUSED
        return when (period) {
            TimePeriod.DAWN       -> MoodType.CALM
            TimePeriod.MORNING    -> MoodType.FOCUSED
            TimePeriod.NOON       -> MoodType.CALM
            TimePeriod.AFTERNOON  -> if (energy > 70) MoodType.CURIOUS else MoodType.REFLECTIVE
            TimePeriod.EVENING    -> MoodType.REFLECTIVE
            TimePeriod.NIGHT      -> MoodType.CALM
            TimePeriod.LATE_NIGHT -> MoodType.REFLECTIVE
        }
    }

    /**
     * 书架便条文本（Phase 20 §E）。
     * 从 Goal + 时间段组合生成，不需要 LLM。
     */
    private fun buildNoteText(goalTitle: String?, progress: Float?, period: TimePeriod): String? {
        if (goalTitle == null) return null
        val progressPct = ((progress ?: 0f) * 100).toInt()
        return when {
            progressPct >= 90 -> "「$goalTitle」快完成了，整理一下收尾事项…"
            progressPct >= 50 -> "「$goalTitle」进行到一半，想起了一些细节…"
            period == TimePeriod.MORNING -> "一早翻了翻「$goalTitle」的进展，有些想法…"
            period == TimePeriod.EVENING -> "傍晚回顾「$goalTitle」，有点新的感悟"
            else -> "整理「$goalTitle」资料时想到了一件事…"
        }
    }

    private fun buildActivity(
        goalTitle: String?,
        horizon: String?,
        period: TimePeriod,
    ): String {
        if (goalTitle == null) {
            return when (period) {
                TimePeriod.DAWN      -> "刚刚起来"
                TimePeriod.MORNING   -> "处理日常事务"
                TimePeriod.NOON      -> "吃午饭"
                TimePeriod.AFTERNOON -> "在做自己的事"
                TimePeriod.EVENING   -> "傍晚散步"
                TimePeriod.NIGHT     -> "准备休息"
                TimePeriod.LATE_NIGHT-> "还没睡"
            }
        }
        val goalVerb = when (GoalHorizon.entries.find { it.name == horizon }) {
            GoalHorizon.SHORT_TERM -> "在处理"
            GoalHorizon.LONG_TERM  -> "继续推进"
            else                   -> "在做"
        }
        return "$goalVerb「$goalTitle」"
    }

    /**
     * 从对话回复中提取的情绪词更新该角色的缓存 mood。
     * 由 ChatViewModel 在每轮 COMPANION 模式回复后调用。
     * 不触发 proactiveMessageFlow，仅更新内存缓存供下一轮 buildSystemPrompt 读取。
     */
    fun updateMoodFromReply(characterId: Int, mood: MoodType) {
        val current = getCachedPresence(characterId)
        if (current != null) {
            setCachedPresence(characterId, current.copy(mood = mood))
        } else {
            setCachedPresence(characterId, PresenceSnapshot(
                characterId = characterId,
                activity    = "",
                timePeriod  = TimePeriod.NOON,
                mood        = mood,
                updatedAt   = System.currentTimeMillis(),
            ))
        }
    }

    /**
     * Phase 4：情境感知主动消息触发入口。
     *
     * 由 WorldSimulation.runTier1() 调用，传入从 MessageDao 查询的情境参数。
     * 内部优先走情境感知路径，无情境时 fallback 到原有目标进展逻辑。
     *
     * @param characterId         角色 ID
     * @param goalTitle           当前最高优先级目标标题（null = 无目标）
     * @param mood                当前 mood（由 refreshPresence 已计算）
     * @param elapsedMs           距上次对话的毫秒数（由调用方从 MessageDao 查询）
     * @param recentOtherCharIds  最近 48h 内用户有互动的其他角色 ID 列表
     * @param relAffection        与用户的亲密度（0-100）
     * @param relTrust            与用户的信任度（0-100）
     * @param relDependence       与用户的依赖度（0-100）
     */
    suspend fun tryEmitContextualProactiveMessage(
        characterId: Int,
        goalTitle: String?,
        mood: MoodType,
        elapsedMs: Long,
        recentOtherCharIds: List<Int> = emptyList(),
        relAffection: Int = 50,
        relTrust: Int = 50,
        relDependence: Int = 50,
    ) {
        if (!isProactiveEnabled()) return

        // 新发现修复：与 refreshPresence() 共用同一把按角色分片的锁，保证两条
        // 路径对同一节流键（lastProactiveAt）的"读→判断→写"闭环互斥。
        proactiveThrottleLockFor(characterId).withLock {
            val last = getLastProactiveAt(characterId)
            val now  = System.currentTimeMillis()
            if (now - last < PROACTIVE_THROTTLE_MS) return@withLock

            val msg = buildContextualProactiveMessage(
                characterId        = characterId,
                goalTitle          = goalTitle,
                mood               = mood,
                elapsedMs          = elapsedMs,
                recentOtherCharIds = recentOtherCharIds,
                relAffection       = relAffection,
                relTrust           = relTrust,
                relDependence      = relDependence,
            ) ?: return@withLock

            setLastProactiveAt(characterId, now)
            emitProactiveMessage(msg)
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Phase 4：情境模式枚举 + 情境感知主动消息构建
    // ─────────────────────────────────────────────────────────

    private enum class ProactiveMode {
        JEALOUSY,    // 检测到用户近期在和其他角色对话
        MILESTONE,   // 关系维度越过关键阈值（affection ≥ 80）
        LONGING,     // 超过 48h 未对话
        MIDNIGHT,    // 深夜 + 当天有过对话
        NORMAL,      // 基于目标进展（原有逻辑）
    }

    /**
     * 情境感知主动消息生成器（Phase 4）。
     * 优先级：JEALOUSY > MILESTONE > LONGING > MIDNIGHT > NORMAL。
     * 返回 null 表示当前情境不适合发送主动消息。
     *
     * AI 化改造：原先每个 mode 对应一句写死的文案，同一角色反复命中同一 mode
     * 时会连续发送完全相同的内容（用户反馈：多个角色同一时段发一模一样的
     * "突然想到你了。"）。现改为按 mode 组装"情境指令"交给 LLM，让角色结合
     * 自己的 persona + 最近对话话题现场生成一句话；LLM 调用失败或返回空时，
     * 回退到原有固定文案（保底，不让主动消息功能因网络/Provider 问题整体失效）。
     */
    private suspend fun buildContextualProactiveMessage(
        characterId: Int,
        goalTitle: String?,
        mood: MoodType,
        elapsedMs: Long,
        recentOtherCharIds: List<Int>,
        relAffection: Int,
        relTrust: Int,
        relDependence: Int,
        hourOfDay: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY),
    ): ProactiveMessage? {
        val elapsedH = elapsedMs / 3_600_000L
        val elapsedStr = when {
            elapsedH < 1  -> "不到一小时"
            elapsedH < 24 -> "${elapsedH}小时"
            else          -> "${elapsedH / 24}天"
        }

        val mode = when {
            recentOtherCharIds.isNotEmpty()                                      -> ProactiveMode.JEALOUSY
            relAffection >= 80 && elapsedMs > 3 * 3_600_000L                    -> ProactiveMode.MILESTONE
            elapsedH > 48                                                         -> ProactiveMode.LONGING
            (hourOfDay in 22..23 || hourOfDay in 0..2) && elapsedH < 12         -> ProactiveMode.MIDNIGHT
            goalTitle != null && mood in listOf(
                MoodType.EXCITED, MoodType.FOCUSED, MoodType.CURIOUS,
                MoodType.SATISFIED, MoodType.REFLECTIVE
            )                                                                     -> ProactiveMode.NORMAL
            else                                                                  -> return null
        }

        val fallbackText = when (mode) {
            ProactiveMode.JEALOUSY  ->
                if (relDependence >= 70) "你最近好像有点忙…是在忙什么？" else "突然想到你了。"
            ProactiveMode.MILESTONE ->
                "最近感觉和你之间有些不一样了，说不清楚，但就是有点不同。"
            ProactiveMode.LONGING   ->
                if (relDependence >= 70) "已经 $elapsedStr 没消息了，有点担心你。" else "好久没联系了。最近怎么样？"
            ProactiveMode.MIDNIGHT  ->
                "深夜了，还没睡吗？"
            // 此分支目前唯一调用方是 WorldSimulation.runTier1()，且该处固定传 goalTitle=null
            // （故意为之，见其调用点注释）：目标进展触发已由 refreshPresence() 内部的
            // Phase 20 §H 逻辑负责（同一个 buildProactiveMessage），此处重复触发会被节流
            // 机制挡掉但仍是多余判断。故本分支实际上恒为 null 分支，这不是死代码/bug，
            // 而是预留给未来若有新调用方传入非 null goalTitle 时的正常路径。
            ProactiveMode.NORMAL    ->
                goalTitle?.let { buildProactiveMessage(characterId, it, mood)?.text } ?: return null
        }

        // NORMAL 分支已经在 buildProactiveMessage 内部走过一次 LLM 生成，
        // fallbackText 此时已经是最终文本（或 null 导致提前 return），不再二次生成。
        if (mode == ProactiveMode.NORMAL) {
            return ProactiveMessage(characterId = characterId, text = fallbackText)
        }

        val situationPrompt = when (mode) {
            ProactiveMode.JEALOUSY ->
                "用户最近这段时间比较常找别的角色聊天，找你聊得相对少了。" +
                    (if (relDependence >= 70) "你对用户依赖程度比较高，" else "") +
                    "结合你的性格，写一句你现在主动发给用户的开场白，" +
                    "体现出你注意到了这件事、心里有点在意或试探的情绪，不要直接说\"你在跟别人聊天\"这种直白指责。"
            ProactiveMode.MILESTONE ->
                "你和用户的亲密度最近达到了一个新的高度（关系升温），" +
                    "但你们已经有 $elapsedStr 没聊天了。结合你的性格，写一句你现在主动发给用户的" +
                    "开场白，体现出一种关系变得不一样、但说不清楚具体是什么的微妙感觉。"
            ProactiveMode.LONGING ->
                "你和用户已经 $elapsedStr 没有聊天了，是比较久没联系的状态。" +
                    (if (relDependence >= 70) "你对用户依赖程度比较高，" else "") +
                    "结合你的性格，写一句你现在主动发给用户的开场白，表达想念或关心。"
            ProactiveMode.MIDNIGHT ->
                "现在是深夜时段，你和用户今天已经聊过天。结合你的性格，写一句你现在" +
                    "主动发给用户的开场白，关心一下对方这么晚还没睡。"
            ProactiveMode.NORMAL -> "" // 已在上面提前 return，不会走到这里
        }

        val text = generateProactiveLine(
            characterId      = characterId,
            situationPrompt  = situationPrompt,
            fallback         = fallbackText,
        )
        return ProactiveMessage(characterId = characterId, text = text)
    }

    /**
     * 构建主动消息意图（energy > 60 + 有目标时触发）。
     * AI 化改造：原先按 mood 映射固定文案模板，现改为 LLM 结合角色 persona +
     * 具体目标标题现场生成；LLM 失败时回退到原固定模板。
     */
    private suspend fun buildProactiveMessage(characterId: Int, goalTitle: String, mood: MoodType): ProactiveMessage? {
        // 节流：同一角色 30 分钟内不重复发
        if (!isProactiveEnabled()) return null
        val last = getLastProactiveAt(characterId)
        val now  = System.currentTimeMillis()
        if (now - last < PROACTIVE_THROTTLE_MS) return null

        val fallbackText = when (mood) {
            MoodType.EXCITED    -> "我刚刚在想「$goalTitle」，有个新想法想跟你说！"
            MoodType.FOCUSED    -> "我正专注在「$goalTitle」上，有个问题想请教你…"
            MoodType.CURIOUS    -> "整理「$goalTitle」时想到了一件有趣的事情"
            MoodType.SATISFIED  -> "「$goalTitle」进展不错，想跟你分享一下"
            MoodType.REFLECTIVE -> "最近一直在思考「$goalTitle」，有些感悟"
            else                -> return null  // CALM/TIRED/CONCERNED 不主动发
        }

        val moodDesc = when (mood) {
            MoodType.EXCITED    -> "兴奋、有新想法想立刻分享"
            MoodType.FOCUSED    -> "专注投入，遇到了想请教用户的问题"
            MoodType.CURIOUS    -> "好奇，发现了一件有趣的事情"
            MoodType.SATISFIED  -> "满足，觉得进展不错想分享喜悦"
            MoodType.REFLECTIVE -> "若有所思，对这件事有了新的感悟"
            else                -> ""
        }
        val situationPrompt =
            "你正在进行的事情/目标是「$goalTitle」，你现在的心情是$moodDesc。" +
                "结合你的性格，写一句你现在主动发给用户的开场白，自然地提到这件事，" +
                "体现出上述心情，不要写成正式汇报的语气。"

        val text = generateProactiveLine(
            characterId      = characterId,
            situationPrompt  = situationPrompt,
            fallback         = fallbackText,
        )
        // P2-7 修复：删除此处的 setLastProactiveAt 调用。
        // 调用方 tryEmitContextualProactiveMessage() 在 buildXXX 返回非 null 后
        // 已经统一调用了一次 setLastProactiveAt（共用同一把节流锁），
        // buildProactiveMessage 内部再写一次是重复操作，不会造成数据错误但浪费一次 SP 写入。
        return ProactiveMessage(characterId = characterId, text = text)
    }

    /**
     * 主动消息 AI 生成的统一出口。
     *
     * 拼装 = 角色 persona（若能解析到）+ 最近对话历史（若有 messageDao）+
     * situationPrompt（调用方描述"现在是什么情境，该表达什么情绪/内容"）。
     * 调用 LLM 现场生成一句自然语言开场白；以下任一情况直接回退到 [fallback]，
     * 不让主动消息功能因外部依赖问题整体失效：
     *   - 没有配置 Provider（ProviderManager.instance.activeProvider == null）
     *   - LLM 调用抛出异常（网络、超时、Provider 报错等，chatSyncWithRetry 内部
     *     已重试 2 次，这里不再重试）
     *   - LLM 返回空字符串或明显不合理的超长文本
     */
    private suspend fun generateProactiveLine(
        characterId: Int,
        situationPrompt: String,
        fallback: String,
    ): String {
        val provider = ProviderManager.instance.activeProvider ?: return fallback

        return try {
            val config = resolveCharacterConfig(characterId)
            val personaLine = config?.identityConfig?.persona
                ?.takeIf { it.isNotBlank() }
                ?.let { "你的人设是：$it" }
                ?: ""

            // 最近几条对话，供 LLM 参考话题/语气，不强求存在。
            // getRecentByCharacter 按 createdAt DESC 返回，reversed() 还原成
            // 时间正序，让 LLM 看到的对话顺序符合直觉（早的在前、晚的在后）。
            val recentHistory = messageDao
                ?.getRecentByCharacter(characterId, limit = 6)
                ?.asReversed()
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString("\n") { m ->
                    val speaker = if (m.role == "user") "用户" else "你"
                    "$speaker：${m.content.take(60)}"
                }

            val prompt = buildString {
                appendLine("你是${config?.name ?: "一个角色"}，正在给用户主动发一条消息（用户还没有说话，是你先开口）。")
                if (personaLine.isNotBlank()) appendLine(personaLine)
                if (!recentHistory.isNullOrBlank()) {
                    appendLine("你们最近聊过的内容（供参考语气和话题，不要生硬引用）：")
                    appendLine(recentHistory)
                }
                appendLine()
                appendLine(situationPrompt)
                appendLine("要求：只写这一句话本身，不要加称呼、不要加引号、不要加解释，" +
                    "不超过40个字，符合你的说话风格。")
            }

            val response = provider.chatSyncWithRetry(
                messages     = listOf(LLMMessage(role = "user", content = prompt)),
                systemPrompt = "",
                config       = LLMConfig(
                    model       = "",
                    maxTokens   = 150,
                    temperature = 0.9f,
                    stream      = false,
                ),
                maxAttempts  = 2,
            )

            val cleaned = response.trim().trim('"', '「', '」', '\n')
            if (cleaned.isBlank() || cleaned.length > 80) fallback else cleaned
        } catch (e: Exception) {
            ZLog.w(TAG, "AI 生成主动消息失败，回退固定文案：char=$characterId", e)
            fallback
        }
    }

    /**
     * 解析角色配置（含 persona）。预设角色（1-9）查 DefaultCharacters，
     * 女儿角色（1000+）查 DaughterCharacterRepository，两边都查不到返回 null
     * （generateProactiveLine 会相应地跳过 persona 拼装，不阻断生成流程）。
     * 与 ProactiveMessageNotifier.resolveCharacterName 同源模式。
     */
    private suspend fun resolveCharacterConfig(characterId: Int): CharacterConfig? {
        DefaultCharacters.firstOrNull { it.id == characterId }?.let { return it }
        return try {
            daughterCharacterRepo?.getCharacterConfig(characterId)
        } catch (e: Exception) {
            ZLog.w(TAG, "Resolve daughter config failed for char $characterId", e)
            null
        }
    }

    companion object {
        // 批次1 1-6修复：从实例成员提升为 companion object 级别，让 Worker 创建的
        // 独立 PresenceEngine 实例与前台常驻实例共享同一 Flow。Worker 发射的主动
        // 消息前台 ChatViewModel 能实时收到（若 ChatViewModel 改用 Flow 订阅）。
        private val _proactiveMessageFlow = MutableSharedFlow<ProactiveMessage>(extraBufferCapacity = 8)
        val proactiveMessageFlow = _proactiveMessageFlow.asSharedFlow()

        internal val presenceCache      = ConcurrentHashMap<Int, PresenceSnapshot>()
        // 记录每个角色上次应用 energy 衰减的时段。
        // 修复 computeEnergy 原本每次 refreshPresence 都按"调用次数"叠加 delta 的问题——
        // Tier1 每 5 分钟调用一次，同一时段内多次调用会让 energy 反复 ±delta 失真
        // （例如 MORNING 单时段被调用 24 次就会 -48）。改为同一时段只应用一次 delta。
        private val lastEnergyPeriod    = ConcurrentHashMap<Int, TimePeriod>()
        // D-6 fix: lastProactiveAt 改为 SharedPreferences 持久化，进程被杀后节流状态不丢失。
        // 内存层 ConcurrentHashMap 作为读缓存（避免每次节流检查都走磁盘 IO），
        // 写入时同步落盘；进程重启后首次读取时从 SP 加载。
        private val lastProactiveAtCache = ConcurrentHashMap<Int, Long>()
        private const val SP_PROACTIVE   = "proactive_throttle"
        private fun spProactiveKey(characterId: Int) = "last_at_$characterId"

        internal fun getLastProactiveAt(characterId: Int): Long {
            lastProactiveAtCache[characterId]?.let { return it }
            val sp = appContext?.getSharedPreferences(SP_PROACTIVE, android.content.Context.MODE_PRIVATE)
                ?: return 0L
            val v = sp.getLong(spProactiveKey(characterId), 0L)
            lastProactiveAtCache[characterId] = v
            return v
        }

        internal fun setLastProactiveAt(characterId: Int, timestamp: Long) {
            lastProactiveAtCache[characterId] = timestamp
            appContext?.getSharedPreferences(SP_PROACTIVE, android.content.Context.MODE_PRIVATE)
                ?.edit()?.putLong(spProactiveKey(characterId), timestamp)?.apply()
        }

        // 主动消息全局开关（由 ProfileScreen 写入，此处读取）
        private var appContext: android.content.Context? = null
        fun init(ctx: android.content.Context) { appContext = ctx.applicationContext }
        fun isProactiveEnabled(): Boolean =
            appContext
                ?.getSharedPreferences("user_profile", android.content.Context.MODE_PRIVATE)
                ?.getBoolean("proactive_enabled", true)
                ?: true
        // 性能 L2 修复：原 ConcurrentHashMap.newKeySet() + 超过200条 clear() 全量清空，
        // 改为有界 LRU（LinkedHashMap accessOrder + removeEldestEntry，外层加锁保证线程安全），
        // 始终只保留最近 200 条，不再出现"清空后旧 id 重新进入会再发射一次通知"的情况。
        private val notifiedResultIds = BoundedLruSet<String>(maxSize = 200)
        private val dailyNoteCache     = ConcurrentHashMap<Int, Pair<String, Long>>() // Phase 30 方案五：characterId → (text, generatedAt)
        const val PROACTIVE_THROTTLE_MS = 30 * 60 * 1000L  // 30 分钟节流

        // 新发现（window12/13复核后追加）：refreshPresence() 内部"读 lastProactiveAt→
        // 判断节流→构建消息→写 lastProactiveAt"这一闭环，此前只在 WorldSimulation.
        // runTier1() 路径下受 tier1Mutex 保护；但 RoundtableBotReplyGenerator/
        // ChatMessageOrchestrator/RoundtableIdleManager 三处（presence 缓存未命中
        // 时的补算路径）直接调用 refreshPresence()，完全绕开 tier1Mutex——若这类
        // 调用与后台 Tier1 循环对同一角色几乎同时执行，可能都读到"未触发"，各自
        // 构建并发出一条主动消息，导致同一角色收到重复消息。改为按 characterId
        // 分片加锁（而非复用 tier1Mutex 全局串行化，避免不同角色之间无谓阻塞），
        // 使该闭环无论从哪条调用路径进入都保证原子性。
        private val proactiveThrottleLocks = ConcurrentHashMap<Int, Mutex>()
        internal fun proactiveThrottleLockFor(characterId: Int): Mutex =
            proactiveThrottleLocks.computeIfAbsent(characterId) { Mutex() }

        // window13结论7修复：记录当前正处于前台聊天页的角色 ID。
        // ChatViewModel.initFor() 进入某角色聊天页时设置，onCleared() 离开时清除。
        // 供 AppContainer 的 PresenceEngine 实例在 onProactiveMessage 回调里判断：
        // 该角色的 Snackbar 已经通过 proactiveMessageFlow 展示给用户了，onProactiveMessage
        // 落库但不重复弹系统通知；其他角色则正常落库+弹通知（此前是彻底静默丢弃）。
        // 用 @Volatile 保证跨线程可见性（写在主线程 ViewModel，读在 IO 线程的 emit 回调）。
        //
        // 用户反馈修复：ChatViewModel 是应用内单例 ViewModel（不随切换角色/离开
        // 聊天页重建，见 ChatViewModel 类注释），其 onCleared() 只在整个 ViewModel
        // 被销毁（App 进程被杀）时才会触发——用户退出聊天页、切到后台但进程存活时，
        // foregroundChatCharacterId 不会被清空，会一直卡在"最后进过的那个角色"上。
        // 后果：该角色之后触发的主动消息，即使用户早已退到桌面，仍会被误判为
        // "正在该角色聊天页"而被 suppress，只落库不弹系统通知——用户只有重新
        // 打开 App 进入聊天页才能看到消息，与"退到后台收不到推送"的症状吻合。
        //
        // 修复：新增 isAppInForeground，由 ZaijianApp 的 ActivityLifecycleCallbacks
        // （已有的 foregroundCount 计数逻辑）同步维护，是否抑制通知改为"角色匹配
        // 且 App 确实在前台"两个条件同时成立才抑制，仅角色匹配不再足够。
        @Volatile
        var foregroundChatCharacterId: Int? = null

        @Volatile
        var isAppInForeground: Boolean = false
    }
}

/**
 * 性能 L2 修复：固定容量的线程安全 LRU Set，超过容量时淘汰最久未访问的元素，
 * 替代"超过阈值整体 clear()"的写法。仅提供本文件所需的最小接口（add 的
 * check-and-add 原子语义）。
 */
private class BoundedLruSet<T>(private val maxSize: Int) {
    private val map = object : LinkedHashMap<T, Boolean>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<T, Boolean>): Boolean =
            size > maxSize
    }

    /** 原子的 check-and-add：元素已存在返回 false，不存在则插入并返回 true。 */
    @Synchronized
    fun add(element: T): Boolean {
        if (map.containsKey(element)) {
            map[element] = true // 触发 access-order 更新
            return false
        }
        map[element] = true
        return true
    }
}

// ─────────────────────────────────────────────────────────────
//  数据类 & 枚举
// ─────────────────────────────────────────────────────────────

/** Phase 20 新增：八种情绪枚举（设计方案 §7） */
enum class MoodType {
    CALM,       // 平静
    FOCUSED,    // 专注
    CURIOUS,    // 好奇
    SATISFIED,  // 满足
    CONCERNED,  // 担忧
    EXCITED,    // 兴奋
    TIRED,      // 疲惫
    REFLECTIVE, // 沉思
}

/**
 * 2.2 修复：中文情绪标签 → MoodType 的公共反向映射。
 *
 * 这套"中文 ↔ 枚举"对应关系此前在两处各写了一份单向映射——
 * PresenceViewModel（枚举名→中文 moodLabel，供 UI 文案展示）和
 * ChatViewModel.parseMoodType（LLM 输出的中文词→枚举，私有）。
 * 两边顺序一致（平静/专注/好奇/满足/担忧/兴奋/疲惫/沉思 ↔
 * CALM/FOCUSED/CURIOUS/SATISFIED/CONCERNED/EXCITED/TIRED/REFLECTIVE），
 * 这里收成一份公共函数，供 CharacterDetailScreen 从 PresenceState.moodLabel
 * 还原出 MoodType 时复用，避免第三次重复造轮子。
 * 未命中（空字符串或非法值）返回 null。
 */
fun moodTypeFromLabel(label: String): MoodType? = when (label) {
    "平静" -> MoodType.CALM
    "专注" -> MoodType.FOCUSED
    "好奇" -> MoodType.CURIOUS
    "满足" -> MoodType.SATISFIED
    "担忧" -> MoodType.CONCERNED
    "兴奋" -> MoodType.EXCITED
    "疲惫" -> MoodType.TIRED
    "沉思" -> MoodType.REFLECTIVE
    else   -> null
}

enum class TimePeriod {
    DAWN, MORNING, NOON, AFTERNOON, EVENING, NIGHT, LATE_NIGHT
}

/** Phase 20 升级：含 mood、energy、noteText 字段
 *  Fix-Focus（zaijian）：新增 goalTitle，供 buildSystemPrompt 的 presenceFocus 参数使用——
 *  之前 presenceFocus 一直靠默认值 ""，State Layer 里"关注：xxx"这一行永远不出现。 */
data class PresenceSnapshot(
    val characterId: Int,
    val activity: String,
    val timePeriod: TimePeriod,
    val mood: MoodType = MoodType.CALM,     // Phase 20 新增
    val energy: Int = 70,                   // Phase 20 新增，0-100
    val sourceGoalId: String? = null,       // Phase 20 新增，来源目标 ID
    val goalTitle: String? = null,          // Fix-Focus 新增，来源目标标题（供 presenceFocus 使用）
    val noteText: String? = null,           // Phase 20 新增，书架便条
    val updatedAt: Long,
)

/**
 * 主动消息意图，由 PresenceEngine 发出，ChatViewModel 订阅后展示。
 *
 * 构造时 [text] 是 LLM 原始输出（可能夹带 [thinking:]/圆括号心理描写/[mood:]），
 * [psychText]/[thinkingText] 此时恒为 null——三者都由 `emitProactiveMessage()`
 * （唯一的广播+持久化出口）统一剥离后填入，构造点（`buildProactiveMessage`/
 * `buildContextualProactiveMessage`）不需要、也不应该自行剥离，避免剥离逻辑散落到多处。
 */
data class ProactiveMessage(
    val characterId: Int,
    val text: String,
    val psychText: String? = null,
    val thinkingText: String? = null,
)

/** Phase 30 方案二：任务完成通知，由 PresenceEngine 发出，CharacterScreen 订阅后展示浮层 */
data class TaskCompletionMessage(
    val characterId:   Int,
    val characterName: String,
    val jobTitle:      String,
    val resultSummary: String,
    val jobResultId:   String,
    val status:        String,   // "success" / "failed"
)
