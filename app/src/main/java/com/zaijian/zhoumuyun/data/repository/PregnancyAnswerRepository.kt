package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.db.dao.PregnancyAnswerDao
import com.zaijian.zhoumuyun.domain.pregnancy.ConsistencyResult
import com.zaijian.zhoumuyun.domain.pregnancy.PregnancyAnswerConsistencyChecker
import com.zaijian.zhoumuyun.util.ZLog
import com.zaijian.zhoumuyun.data.db.dao.PregnancyPendingQuestionDao
import com.zaijian.zhoumuyun.data.db.entity.PregnancyAnswerEntity
import com.zaijian.zhoumuyun.data.db.entity.PregnancyPendingQuestionEntity
import com.zaijian.zhoumuyun.data.db.entity.PregnancyQuestionType
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────────────────────────────────────────
//  PregnancyAnswerRepository
//
//  D3 孕期共设系统 · 槽位收敛链状态机
//
//  职责：
//    1. 记录 AI 已发出的待确认问题（upsert pending）
//    2. 接收用户回答，执行收敛链逻辑：
//       - 收到第 1 条答案 → 存入，等待下次确认
//       - 收到第 2+ 条答案 → 调用 ConsistencyChecker 判定
//         ├── CONSISTENT    → 存入 + lockSlot()
//         └── CONTRADICTORY → 存入（继续等下次机会）
//       - 累计达到收敛上限（MAX_SLOT_ANSWERS）→ 强制 lockSlot() 取最新值
//    3. 提供给 ChatViewModel 查询接口（槽位是否已锁定、下一个未锁定槽位等）
//
//  不做的事：
//    - 不决定「什么时候触发提问」——提问时机由 D3 门控逻辑（ChatViewModel）负责
//    - 不构建 Prompt——Prompt 模板由 PromptOrchestrator 和情境化提问模板负责
//
//  修正说明（v2）：
//    - 移除 @Singleton / @Inject（项目未引入 Hilt/Dagger，所有 Repository
//      均为普通构造函数，在 ChatViewModel 中手动实例化）。
//    - ConsistencyChecker 作为普通构造参数传入（与其他 Repository 依赖方式一致）。
//      ChatViewModel 侧负责将 providerFn 传给 Checker 后再传入本类。
// ─────────────────────────────────────────────────────────────────────────────

/** 单槽位收敛上限：跨孕期累计超过此次数后强制锁定 */
private const val MAX_SLOT_ANSWERS = 3

/** 按 (questionType, slotIndex) 描述一个槽位 */
data class QuestionSlot(
    val questionType: PregnancyQuestionType,
    val slotIndex: Int,
)

/**
 * [recordAnswer] 的返回值：本次存入后该槽位的收敛结果。
 * ChatViewModel 根据此结果决定是否还需要继续追问。
 */
sealed interface SlotRecordResult {
    /** 第一条答案，已存入，等待下一次确认 */
    data object FirstAnswer : SlotRecordResult

    /**
     * 语义一致，槽位已锁定。
     * @param answerCount 本槽位历史答案总数（含本次）
     */
    data class Locked(val answerCount: Int) : SlotRecordResult

    /**
     * 语义矛盾，槽位仍开放，保留本次答案等待下次机会。
     * @param answerCount 本槽位历史答案总数（含本次）
     */
    data class StillOpen(val answerCount: Int) : SlotRecordResult

    /**
     * 达到收敛上限，强制锁定（取本次最新答案）。
     * @param answerCount 本槽位历史答案总数（含本次），应为 MAX_SLOT_ANSWERS
     */
    data class ForceLocked(val answerCount: Int) : SlotRecordResult
}

class PregnancyAnswerRepository(
    private val answerDao: PregnancyAnswerDao,
    private val pendingDao: PregnancyPendingQuestionDao,
    private val consistencyChecker: PregnancyAnswerConsistencyChecker,
) {

    companion object {
        private const val TAG = "PregnancyAnswerRepo"

        /**
         * D3 六个槽位的完整定义（固定顺序，D4 生成时按此遍历）。
         * NAME_PREF / WORRY：各 1 个槽位（slotIndex = 0）
         * WORLDVIEW / PERSONA：各 2 个槽位（slotIndex = 0 / 1）
         */
        val ALL_SLOTS: List<QuestionSlot> = listOf(
            QuestionSlot(PregnancyQuestionType.NAME_PREF,  slotIndex = 0),
            QuestionSlot(PregnancyQuestionType.WORLDVIEW,  slotIndex = 0),
            QuestionSlot(PregnancyQuestionType.WORLDVIEW,  slotIndex = 1),
            QuestionSlot(PregnancyQuestionType.WORRY,      slotIndex = 0),
            QuestionSlot(PregnancyQuestionType.PERSONA,    slotIndex = 0),
            QuestionSlot(PregnancyQuestionType.PERSONA,    slotIndex = 1),
        )
    }

    // ── 待确认问题（Pending）管理 ─────────────────────────────────────────

    /**
     * AI 向用户提问后记录待确认状态。
     * 单行覆盖写：每个母亲角色同一时间只保留一个待确认问题。
     *
     * @param motherCharacterId 母亲角色 ID
     * @param questionType 问题类型
     * @param slotIndex 槽位序号
     * @param questionText AI 实际说出的问题原文（存档/调试用）
     */
    suspend fun recordPendingQuestion(
        motherCharacterId: Int,
        questionType: PregnancyQuestionType,
        slotIndex: Int,
        questionText: String,
    ) {
        pendingDao.upsert(
            PregnancyPendingQuestionEntity(
                motherCharacterId = motherCharacterId,
                questionType      = questionType.name,
                slotIndex         = slotIndex,
                questionText      = questionText,
                askedAt           = System.currentTimeMillis(),
            )
        )
    }

    /**
     * 读取某母亲角色当前的待确认问题。
     * 返回 null 表示目前没有挂起的问题（AI 尚未发问，或已配对完成）。
     */
    suspend fun getPendingQuestion(motherCharacterId: Int): PregnancyPendingQuestionEntity? =
        pendingDao.getByMother(motherCharacterId)

    /** 配对完成（已写入正式答案）后清空待确认状态 */
    suspend fun clearPendingQuestion(motherCharacterId: Int) =
        pendingDao.clearByMother(motherCharacterId)

    // ── 收敛链核心逻辑 ────────────────────────────────────────────────────

    /**
     * 用户给出回答后调用。执行完整的收敛链逻辑：
     * 存入答案 → 判定一致性（如有历史）→ 决定是否锁定槽位。
     *
     * @param motherCharacterId 母亲角色 ID
     * @param pregnancyStartedAt 当前孕期起始时间戳（关联用）
     * @param questionType 槽位问题类型
     * @param slotIndex 槽位序号
     * @param questionText 母亲的问题原文（来自 pending 记录）
     * @param answerText 用户本次回答原文
     * @return [SlotRecordResult] 供调用方判断后续处理
     */
    suspend fun recordAnswer(
        motherCharacterId: Int,
        pregnancyStartedAt: Long,
        questionType: PregnancyQuestionType,
        slotIndex: Int,
        questionText: String,
        answerText: String,
    ): SlotRecordResult {

        // P1-6-9 修复：原四步（isSlotLocked→insert→getBySlot→lockSlot）无事务保护，
        // 存在 TOCTOU 竞态（两次并发调用均通过 isSlotLocked=false 检查后双写）。
        // 修复方案：
        //   - DAO 新增 @Transaction recordIfOpen()，将"检查锁定→插入→计数"合并为原子操作。
        //   - LLM 一致性判定（耗时 IO）仍在事务外完成，避免长事务阻塞数据库。
        //   - lockSlot 调用后续仍独立执行（幂等，重复 UPDATE SET isLocked=1 无副作用）。

        val entity = PregnancyAnswerEntity(
            motherCharacterId  = motherCharacterId,
            pregnancyStartedAt = 0L,  // 方案 5-7：废弃，D3 收敛链不再按孕期切割
            questionType       = questionType.name,
            slotIndex          = slotIndex,
            questionText       = questionText,
            answerText         = answerText,
            answeredAt         = System.currentTimeMillis(),
            isLocked           = false,
        )

        // 原子操作：检查锁定 + 插入 + 返回历史快照（单事务）
        val (inserted, totalCount, history) = answerDao.recordIfOpen(entity)
        if (!inserted) {
            // 问题28修复：此前硬编码 answerCount = MAX_SLOT_ANSWERS（即固定返回3），
            // 但槽位被锁定的真实原因有两种：①达到收敛上限强制锁定（此时历史确实是
            // MAX_SLOT_ANSWERS 条，硬编码恰好"蒙对"）；②语义一致性判定提前锁定
            // （见下方 totalCount==2 时的 CONSISTENT 分支，历史可能只有2条就被锁定，
            // 硬编码为3与实际不符）。调用方（目前只关心 Locked 状态本身，不消费
            // answerCount 数值）虽然暂无实际影响，但字段语义应如实反映数据库真实状态，
            // 不应该在能拿到准确值的情况下继续编造一个可能错误的数字——
            // countBySlot() 是已有的只读查询，复用即可，不引入新查询方法。
            val realCount = answerDao.countBySlot(motherCharacterId, questionType.name, slotIndex)
            ZLog.w(TAG, "Slot already locked: $questionType[$slotIndex], ignoring")
            return SlotRecordResult.Locked(answerCount = realCount)
        }

        ZLog.d(TAG, "Slot $questionType[$slotIndex] now has $totalCount answer(s)")

        return when {
            // 2a. 第一条答案：无历史可比，等待下次确认
            totalCount == 1 -> {
                SlotRecordResult.FirstAnswer
            }

            // 2b. 达到收敛上限：强制锁定，取最新一条（即本次刚插入的）
            totalCount >= MAX_SLOT_ANSWERS -> {
                answerDao.lockSlot(motherCharacterId, questionType.name, slotIndex)
                ZLog.i(TAG, "Force-locked slot $questionType[$slotIndex] after $totalCount answers")
                SlotRecordResult.ForceLocked(answerCount = totalCount)
            }

            // 2c. 第 2 条及中间：与最近一条历史做语义判定（LLM，事务外）
            else -> {
                // P1-6 修复：history 来自 recordIfOpen 的事务内快照，
                // 不再在事务外调用 getBySlot，消除 insert→getBySlot 间的并发窗口。
                val previousAnswer = history.getOrNull(history.size - 2)?.answerText
                    ?: return SlotRecordResult.FirstAnswer  // 防御性兜底

                // 问题13修复：consistencyChecker.check() 是 LLM 调用（耗时 IO），
                // 此前外层无 try-catch——一旦异常穿透，答案已通过 recordIfOpen 原子
                // 写入数据库，但调用方收到异常无法判断操作是否部分成功，槽位会卡在
                // "有答案但未锁定"的状态。这里捕获异常并按保守策略视为 CONTRADICTORY，
                // 自然落入下方的 StillOpen 分支——不锁定槽位，保留本次答案等待下次机会。
                val consistency = try {
                    consistencyChecker.check(
                        questionType    = questionType,
                        previousAnswer  = previousAnswer,
                        newAnswer       = answerText,
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    ZLog.e(TAG, "consistencyChecker.check() failed for $questionType[$slotIndex], treating as StillOpen", e)
                    ConsistencyResult.CONTRADICTORY
                }

                if (consistency == ConsistencyResult.CONSISTENT) {
                    answerDao.lockSlot(motherCharacterId, questionType.name, slotIndex)
                    ZLog.i(TAG, "Locked slot $questionType[$slotIndex]: answers consistent")
                    SlotRecordResult.Locked(answerCount = totalCount)
                } else {
                    ZLog.d(TAG, "Slot $questionType[$slotIndex]: answers contradictory, staying open")
                    SlotRecordResult.StillOpen(answerCount = totalCount)
                }
            }
        }
    }

    // ── 查询接口（供 ChatViewModel / D4 生成器使用）─────────────────────

    /**
     * 返回该母亲角色下一个「尚未锁定」的槽位，供门控逻辑决定下次该问哪题。
     * 按 [ALL_SLOTS] 顺序遍历，返回第一个未锁定的槽位；全部锁定则返回 null。
     *
     * 问题13修复：原先对 6 个槽位逐个调用 isSlotLocked()（最多 6 次查询），
     * 改为先用 getLockedSlotKeys() 一次查询取回全部已锁定槽位集合，再在内存中比对。
     */
    suspend fun nextUnlockedSlot(motherCharacterId: Int): QuestionSlot? {
        val lockedKeys = answerDao.getLockedSlotKeys(motherCharacterId)
            .map { it.questionType to it.slotIndex }
            .toSet()
        return ALL_SLOTS.firstOrNull { slot ->
            (slot.questionType.name to slot.slotIndex) !in lockedKeys
        }
    }

    /**
     * 是否所有 6 个槽位均已锁定（D4 生成器就绪的前提条件）。
     *
     * 问题13修复：同上，改为 1 次批量查询 + 内存比对，替代 6 次独立查询。
     */
    suspend fun isAllSlotsLocked(motherCharacterId: Int): Boolean {
        val lockedKeys = answerDao.getLockedSlotKeys(motherCharacterId)
            .map { it.questionType to it.slotIndex }
            .toSet()
        return ALL_SLOTS.all { slot ->
            (slot.questionType.name to slot.slotIndex) in lockedKeys
        }
    }

    /**
     * 获取某槽位的最终锁定答案（最近一条 isLocked 行的 answerText）。
     * D4 角色卡生成器调用，槽位未锁定时返回 null。
     *
     * 问题13修复：lockSlot() 会把该槽位所有历史行统一置为 isLocked=1，因此最新
     * 一行的 isLocked 字段即可代表整个槽位的锁定状态。原先 getBySlot()（取全部历史）
     * + isSlotLocked()（再查一次）两次独立查询，改为 getLatestBySlot() 单次查询。
     */
    suspend fun getLockedAnswer(
        motherCharacterId: Int,
        questionType: PregnancyQuestionType,
        slotIndex: Int,
    ): String? {
        val latest = answerDao.getLatestBySlot(motherCharacterId, questionType.name, slotIndex)
        return if (latest != null && latest.isLocked) latest.answerText else null
    }

    /**
     * 获取某母亲角色某次孕期的全部已回答问答（供 D4 生成器一次性读取）。
     * 注意：D3 收敛链按槽位维度跨孕期累计，D4 读取时应用 [getLockedAnswer]
     * 按槽位维度拿最终锁定值，而非按 pregnancyStartedAt 过滤。
     * 本方法保留供档案/日志使用。
     */
    fun observeAllAnswers(motherCharacterId: Int): Flow<List<PregnancyAnswerEntity>> =
        answerDao.observeAllByMother(motherCharacterId)
}
