package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.SystemSuggestionEntity
import com.zaijian.zhoumuyun.domain.SpecialtyEvolutionConfig
import com.zaijian.zhoumuyun.domain.SpecialtyEvolutionEngine
import com.zaijian.zhoumuyun.util.ZLog
import java.util.UUID

/**
 * SystemSuggestionGenerator — P6 专长进化系统第8节"AI 自我提案"
 *
 * 每完成 SpecialtyEvolutionConfig.SUGGESTION_TRIGGER_MERGE_CYCLES 次
 * "阶段摘要并入styleNotes"的合并周期，触发一次低频建议生成。
 *
 * 严格遵守方案约定：本类只产出建议文本写入 SystemSuggestionEntity，
 * status 永远以 "PENDING" 写入，不存在任何让建议自动变成参数变更的路径。
 * 用户在专长档案页采纳/忽略，采纳后是用户自己去改
 * SpecialtyEvolutionConfig 里的某个常量（v1 没有做"一键应用"的自动化，
 * 这是刻意的——见方案第8节"游戏规则的修改权始终在用户手上"）。
 */
object SystemSuggestionGenerator {

    // #28 修复：buildHistorySummary 原来对 getUnmerged() 返回的全部未合并摘要
    // 无条数上限地拼接，单条摘要虽各自 take(150/80) 截断，但摘要数量本身不受
    // 控制——长期不触发合并周期时未合并摘要可持续累积，拼出的 historySummary
    // 可能超出 LLM 单次调用的输入长度限制导致调用失败。取最近 N 条即可代表
    // "最近活跃情况"，getUnmerged() 按 createdAt ASC 排序，故取尾部最新的 N 条。
    private const val MAX_DIGESTS_IN_SUMMARY = 20

    /** 用合并次数（mergedIntoProfile=true 的 StageDigest 总数）作为触发计数依据，
     *  不持久化专门的计数字段——直接查询现有数据推导，避免又新增一个需要维护的字段 */
    suspend fun maybeGenerate(db: AppDatabase, engine: SpecialtyEvolutionEngine, specialtyId: String) {
        // P1 修复：顶层 try-catch，防止异常中断 DailyPracticeWorker
        try {
        val profile = db.specialtyProfileDao().getById(specialtyId) ?: return

        // 统计该专长目前的待处理建议数量，避免在用户还没来得及看上一条建议时
        // 就又生成一条——这不是阈值意义上的判断，是基本的"别刷屏"礼貌
        val pendingCount = db.systemSuggestionDao().countPending(specialtyId)
        if (pendingCount > 0) return

        // 用合并周期数判断是否到了生成建议的频率——
        // 方案 3-10：用已合并的 StageDigest 数量精确计算合并周期数，
        // 不再依赖 practiceCount 的间接映射（取模 % 9 在 practiceCount 初始偏移
        // 或 RAW_TO_DIGEST_TRIGGER_COUNT 变化时可能永久不对齐）。
        val mergedDigestCount = db.stageDigestDao().countMerged(specialtyId)
        val mergeCycles = mergedDigestCount / SpecialtyEvolutionConfig.DIGEST_TO_PROFILE_TRIGGER_COUNT
        if (mergeCycles == 0 || mergeCycles % SpecialtyEvolutionConfig.SUGGESTION_TRIGGER_MERGE_CYCLES != 0) return

        val historySummary = buildHistorySummary(db, specialtyId)
        if (historySummary.isBlank()) return

        val result = engine.generateSystemSuggestion(profile.domain, historySummary)
        if (!result.hasSuggestion || result.suggestion.isBlank()) return

        db.systemSuggestionDao().insert(
            SystemSuggestionEntity(
                id = UUID.randomUUID().toString(),
                characterId = profile.characterId,
                specialtyId = specialtyId,
                content = result.suggestion,
                reasoning = result.reasoning,
                status = "PENDING",
                createdAt = System.currentTimeMillis(),
            )
        )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.e("SystemSuggestionGenerator", "maybeGenerate 异常 specialtyId=$specialtyId", e)
        }
    }

    /** 拼装供 LLM 参考的蒸馏历史摘要（不是原文转储，是结构化的简短统计） */
    private suspend fun buildHistorySummary(db: AppDatabase, specialtyId: String): String {
        val allDigests = db.stageDigestDao().getUnmerged(specialtyId) // 取近期还未合并的，作为"最近活跃情况"的样本
        if (allDigests.isEmpty()) return ""
        // 按 createdAt ASC 排序，取尾部最新的 MAX_DIGESTS_IN_SUMMARY 条
        val digests = allDigests.takeLast(MAX_DIGESTS_IN_SUMMARY)
        return buildString {
            digests.forEach { d ->
                append("阶段摘要（覆盖${d.sourceRecordCount}条记录）：${d.digestContent.take(150)}")
                if (d.hasConflict) append("（存在冲突：${d.conflictSummary.take(80)}）")
                append("\n")
            }
        }
    }
}
