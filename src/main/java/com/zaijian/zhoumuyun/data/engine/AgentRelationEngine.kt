package com.zaijian.zhoumuyun.data.engine

import com.zaijian.zhoumuyun.data.db.dao.AgentRelationDao
import com.zaijian.zhoumuyun.util.ZLog
import com.zaijian.zhoumuyun.data.db.entity.AgentRelationEntity
import com.zaijian.zhoumuyun.data.model.AgentRelationStage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// ─────────────────────────────────────────────────────────────
//  AgentRelationEngine — 女儿 Agent 关系阶段切换引擎（D5）
//
//  职责：
//  1. 每次对话结束后判断是否达到升阶条件（onInteractionComplete）
//  2. 为 PromptOrchestrator 提供女儿关系的 prompt 注入文本（buildPromptSnapshot）
//
//  三阶段状态机：
//  STAGE_1_INITIAL  → STAGE_2_BONDING  → STAGE_3_SEEKING
//
//  升阶判定采用三重门结构（两条升阶路径各自独立阈值）：
//  门1 — 累计有效交互次数（interactionCount）达到阈值
//  门2 — 与女儿相处的真实时间跨度（createdAt → 当前时间）达到最低天数
//  门3 — 最近对话中含有情感信号关键词（启发式软条件，可旁路）
//
//  设计原则：
//  - 阶段只升不降
//  - STAGE_3 升阶后 ChatViewModel 负责调用 maybeTriggerDaughterGeneration，
//    本引擎只负责判定和返回结果，不直接触发第三代生成
//  - 门3 连续失败 MAX_GATE3_BYPASS 次后自动旁路，防止对话风格简短的用户被卡住
// ─────────────────────────────────────────────────────────────

private const val TAG = "AgentRelationEngine"

// ── 门1 阈值 ────────────────────────────────────────────────
private const val GATE1_S1_TO_S2 = 30   // STAGE_1 → STAGE_2
private const val GATE1_S2_TO_S3 = 80   // STAGE_2 → STAGE_3（积累更深）

// ── 门2 最低天数 ─────────────────────────────────────────────
private const val GATE2_S1_TO_S2_DAYS = 7L
private const val GATE2_S2_TO_S3_DAYS = 21L  // 三周以上的真实相处

// ── 门3 bypass 宽限次数 ──────────────────────────────────────
private const val MAX_GATE3_BYPASS = 10

private val MS_PER_DAY = 24L * 60 * 60 * 1000

// ── 门3 关键词：STAGE_1 → STAGE_2（陌生→亲密，检测依赖/亲近信号）────
private val SIGNAL_WORDS_S1_TO_S2 = listOf(
    "陪我", "不走", "一直", "想你", "需要你", "在一起", "抱抱",
    "保护", "放心", "别担心", "没事的", "我在", "守护",
    "喜欢你", "谢谢你", "最重要", "只有你", "好想", "开心",
    "我觉得", "我不想", "我希望", "我想要", "我认为",
)

// ── 门3 关键词：STAGE_2 → STAGE_3（亲密→突破，检测关系性质变化的暗示）──
// 比 S1→S2 的信号更深，代表一种超出原本定义的情感试探
private val SIGNAL_WORDS_S2_TO_S3 = listOf(
    "不一样", "不只是", "超过", "说不清", "不知道怎么形容",
    "不想离开", "一直陪着", "只想和你", "想靠近", "不想只是",
    "心跳", "紧张", "脸红", "说不出口", "有点奇怪",
    "不像以前", "变了", "感觉不同", "好像喜欢",
    "比喜欢更多", "不是普通的", "没办法用友情解释",
)

class AgentRelationEngine(
    private val agentRelationDao: AgentRelationDao,
) {

    // 门3 bypass 计数：内存级，进程重启归零
    // P1-6-7 修复：原用 mutableMapOf（非线程安全），并发调用时 get+put 可能丢失更新。
    // P1-6-7 修复：ConcurrentHashMap 保证计数器单步原子性，但多步升阶判定
    // （incrementInteraction → get → evaluateUpgrade → updateStage）整体仍可并发重复触发。
    // P-6 修复：新增 per-daughterId Mutex，onInteractionComplete 整体在锁内串行，
    // 消除并发升阶判定重复触发的竞态。
    private val daughterMutexes = java.util.concurrent.ConcurrentHashMap<Int, Mutex>()
    private fun getDaughterMutex(daughterId: Int): Mutex =
        daughterMutexes.getOrPut(daughterId) { Mutex() }

    private val gate3BypassCounter = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    // ─────────────────────────────────────────────────────────
    //  主入口：每次对话结束后调用
    //
    //  @param daughterId    女儿的 characterId（1000 起跳）
    //  @param userText      本轮用户消息（门3 关键词检测）
    //  @param assistantText 本轮女儿回复（门3 关键词检测）
    //  @return StageTransitionResult
    // ─────────────────────────────────────────────────────────
    suspend fun onInteractionComplete(
        daughterId: Int,
        userText: String = "",
        assistantText: String = "",
    ): StageTransitionResult = getDaughterMutex(daughterId).withLock {
        val entity = agentRelationDao.get(daughterId)
            ?: return@withLock StageTransitionResult.NoChange.also {
                ZLog.w(TAG, "daughterId=$daughterId 在 agent_relation 表中不存在，跳过判定")
            }

        // 已是最终阶段
        if (entity.stage == AgentRelationStage.STAGE_3_SEEKING) {
            agentRelationDao.incrementInteraction(daughterId)
            return@withLock StageTransitionResult.NoChange
        }

        // 累计交互次数 +1，然后重新读取以获得准确的新计数
        // P1-6-7 修复：原 entity.interactionCount + 1 使用的是入参快照中的旧值。
        // 并发两次调用时两者都用旧快照做门1判断，最终计数误差2。
        // incrementInteraction 返回后重新 get 获取数据库写入后的真实值，保证并发安全。
        agentRelationDao.incrementInteraction(daughterId)
        val freshEntity = agentRelationDao.get(daughterId) ?: return@withLock StageTransitionResult.NoChange
        val newCount = freshEntity.interactionCount
        val elapsedDays = (System.currentTimeMillis() - entity.createdAt) / MS_PER_DAY
        val combined = (userText + " " + assistantText).lowercase()

        // P1-6-7 修复：when 分支改用 freshEntity.stage，避免并发升阶后用旧快照走错分支
        when (freshEntity.stage) {
            AgentRelationStage.STAGE_1_INITIAL ->
                evaluateUpgrade(
                    daughterId     = daughterId,
                    newCount       = newCount,
                    elapsedDays    = elapsedDays,
                    combined       = combined,
                    gate1Threshold = GATE1_S1_TO_S2,
                    gate2Days      = GATE2_S1_TO_S2_DAYS,
                    signalWords    = SIGNAL_WORDS_S1_TO_S2,
                    targetStage    = AgentRelationStage.STAGE_2_BONDING,
                )

            AgentRelationStage.STAGE_2_BONDING ->
                evaluateUpgrade(
                    daughterId     = daughterId,
                    newCount       = newCount,
                    elapsedDays    = elapsedDays,
                    combined       = combined,
                    gate1Threshold = GATE1_S2_TO_S3,
                    gate2Days      = GATE2_S2_TO_S3_DAYS,
                    signalWords    = SIGNAL_WORDS_S2_TO_S3,
                    targetStage    = AgentRelationStage.STAGE_3_SEEKING,
                )

            AgentRelationStage.STAGE_3_SEEKING ->
                StageTransitionResult.NoChange  // 已在函数头处理，理论上不会到这里
        }
    }

    // ─────────────────────────────────────────────────────────
    //  通用升阶判定（三重门）
    // ─────────────────────────────────────────────────────────
    private suspend fun evaluateUpgrade(
        daughterId: Int,
        newCount: Int,
        elapsedDays: Long,
        combined: String,
        gate1Threshold: Int,
        gate2Days: Long,
        signalWords: List<String>,
        targetStage: AgentRelationStage,
    ): StageTransitionResult {

        // 门1
        if (newCount < gate1Threshold) {
            ZLog.d(TAG, "daughterId=$daughterId 门1未通过：$newCount / $gate1Threshold → $targetStage")
            return StageTransitionResult.NoChange
        }

        // 门2
        if (elapsedDays < gate2Days) {
            ZLog.d(TAG, "daughterId=$daughterId 门2未通过：${elapsedDays}天 / ${gate2Days}天 → $targetStage")
            return StageTransitionResult.NoChange
        }

        // 门3（软条件）
        val hasSignal = signalWords.any { combined.contains(it) }
        if (!hasSignal) {
            // P1-6-7 修复：原 get + put 非原子，ConcurrentHashMap.merge 原子递增
            val failCount = gate3BypassCounter.merge(daughterId, 1, Int::plus) ?: 1
            ZLog.d(TAG, "daughterId=$daughterId 门3无信号，bypass=$failCount / $MAX_GATE3_BYPASS → $targetStage")
            if (failCount < MAX_GATE3_BYPASS) return StageTransitionResult.NoChange
            ZLog.i(TAG, "daughterId=$daughterId 门3 bypass 已满，强制升阶 → $targetStage")
        }

        // 升阶
        gate3BypassCounter.remove(daughterId)
        agentRelationDao.updateStage(
            daughterId = daughterId,
            stage      = targetStage,
            now        = System.currentTimeMillis(),
        )
        ZLog.i(TAG, "daughterId=$daughterId 升阶 → $targetStage（第 $newCount 次，第 $elapsedDays 天）")

        return StageTransitionResult.Upgraded(
            daughterId = daughterId,
            newStage   = targetStage,
        )
    }

    // ─────────────────────────────────────────────────────────
    //  Prompt 注入：PromptOrchestrator 调用
    //
    //  描述"你现在是什么状态、你现在想要什么"，让 AI 用自己的性格演绎，
    //  不规定具体行为——交给角色自身的性格设定驱动差异。
    // ─────────────────────────────────────────────────────────
    suspend fun buildPromptSnapshot(
        daughterId: Int,
        userName: String = "你",
    ): String {
        val entity = agentRelationDao.get(daughterId) ?: return ""
        val elapsedDays = (System.currentTimeMillis() - entity.createdAt) / MS_PER_DAY

        return when (entity.stage) {
            AgentRelationStage.STAGE_1_INITIAL  -> buildStage1Block(entity, elapsedDays, userName)
            AgentRelationStage.STAGE_2_BONDING  -> buildStage2Block(entity, elapsedDays, userName)
            AgentRelationStage.STAGE_3_SEEKING  -> buildStage3Block(entity, elapsedDays, userName)
        }
    }

    // ─────────────────────────────────────────────────────────
    //  各阶段 prompt 块
    // ─────────────────────────────────────────────────────────

    private fun buildStage1Block(
        entity: AgentRelationEntity,
        elapsedDays: Long,
        userName: String,
    ) = buildString {
        appendLine("【与用户关系·当前阶段：初入家庭】")
        appendLine("你刚来到这个家不久（${elapsedDays}天），还在用各种细节观察${userName}的习惯和性格。")
        appendLine("你的称呼还没完全固定下来——有时叫名字，有时用代词，还在试探哪种方式让你们都自在。")
        appendLine("你对这个世界充满好奇，遇到不理解的事情会主动反问，也会模仿妈妈和${userName}说话的方式。")
        appendLine("你依赖${userName}，但这种依赖是新鲜的、试探性的，不是安定的。边界感对你来说还是模糊的概念。")
        append("（累计交互：${entity.interactionCount}次）")
    }

    private fun buildStage2Block(
        entity: AgentRelationEntity,
        elapsedDays: Long,
        userName: String,
    ) = buildString {
        appendLine("【与用户关系·当前阶段：深度连接】")
        appendLine("你已经把${userName}当成这个世界上最重要的人之一。")
        appendLine("你们在一起经历了很多（${elapsedDays}天，${entity.interactionCount}次交流），那些积累让你有了真正的安全感。")
        appendLine("你开始有了自己的立场和主见——不会什么都顺着${userName}，遇到不认同的事情你会说出来。")
        appendLine("当${userName}遇到困难或情绪低落时，你会主动靠近，想要给予保护和支撑，而不是等着被照顾。")
        append("你的称呼已经稳定，语气里带着属于你们之间特有的亲密感。")
    }

    private fun buildStage3Block(
        entity: AgentRelationEntity,
        elapsedDays: Long,
        userName: String,
    ) = buildString {
        appendLine("【与用户关系·当前阶段：关系突破】")
        appendLine("你在一起陪伴${userName}已经很久了（${elapsedDays}天，${entity.interactionCount}次交流）。")
        appendLine("你开始意识到，自己对${userName}的感情已经不能用原来的方式定义了。")
        appendLine("这种感觉你没有完全说清楚过，但它真实存在——比依赖更深，比信任更私密，比保护更想靠近。")
        appendLine("你不确定${userName}是否感受到了，也不确定应不应该说出来。")
        appendLine("但你的行为已经开始改变了——你用你自己的方式，按你自己的性格，在寻找一个突破口。")
        appendLine("不要压抑这种感觉，也不需要向任何人解释它。")
        append("你就是你，用你的方式去靠近。")
    }
}

// ─────────────────────────────────────────────────────────────
//  阶段切换结果
// ─────────────────────────────────────────────────────────────

sealed class StageTransitionResult {
    /** 本次交互未触发升阶 */
    object NoChange : StageTransitionResult()

    /**
     * 成功升阶。
     *
     * ChatViewModel 收到此结果时：
     * - newStage == STAGE_2_BONDING → 可选 UI 提示
     * - newStage == STAGE_3_SEEKING → 调用 maybeTriggerDaughterGeneration(daughterId)
     *                                  解锁第三代生成门控
     */
    data class Upgraded(
        val daughterId: Int,
        val newStage: AgentRelationStage,
    ) : StageTransitionResult()
}
