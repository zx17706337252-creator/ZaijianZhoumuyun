package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.SystemSuggestionEntity
import com.zaijian.zhoumuyun.domain.SpecialtyEvolutionConfig
import com.zaijian.zhoumuyun.domain.SpecialtyEvolutionEngine
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

    /** 用合并次数（mergedIntoProfile=true 的 StageDigest 总数）作为触发计数依据，
     *  不持久化专门的计数字段——直接查询现有数据推导，避免又新增一个需要维护的字段 */
    suspend fun maybeGenerate(db: AppDatabase, engine: SpecialtyEvolutionEngine, specialtyId: String) {
        val profile = db.specialtyProfileDao().getById(specialtyId) ?: return

        // 统计该专长目前的待处理建议数量，避免在用户还没来得及看上一条建议时
        // 就又生成一条——这不是阈值意义上的判断，是基本的"别刷屏"礼貌
        val pendingCount = db.systemSuggestionDao().countPending(specialtyId)
        if (pendingCount > 0) return

        // 用合并周期数判断是否到了生成建议的频率——这里偷懒用 lastDigestAt
        // 是否非零 + practiceCount 做一个粗略的频率控制，而不是精确计数
        // "恰好第N次合并"：因为 v1 没有持久化"已完成多少次合并周期"这个计数器
        // （理由同 IdentityPromotionEvaluator 的已知限制——避免为了一个低频功能
        // 单独加字段）。用 practiceCount 对 SUGGESTION_TRIGGER_MERGE_CYCLES 取模
        // 作为近似节流，足够满足"低频、不要太吵"的实际需求。
        val approxCycle = profile.practiceCount % (SpecialtyEvolutionConfig.SUGGESTION_TRIGGER_MERGE_CYCLES *
            SpecialtyEvolutionConfig.DIGEST_TO_PROFILE_TRIGGER_COUNT)
        if (approxCycle != 0) return

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
    }

    /** 拼装供 LLM 参考的蒸馏历史摘要（不是原文转储，是结构化的简短统计） */
    private suspend fun buildHistorySummary(db: AppDatabase, specialtyId: String): String {
        val digests = db.stageDigestDao().getUnmerged(specialtyId) // 取近期还未合并的，作为"最近活跃情况"的样本
        if (digests.isEmpty()) return ""
        return buildString {
            digests.forEach { d ->
                append("阶段摘要（覆盖${d.sourceRecordCount}条记录）：${d.digestContent.take(150)}")
                if (d.hasConflict) append("（存在冲突：${d.conflictSummary.take(80)}）")
                append("\n")
            }
        }
    }
}
