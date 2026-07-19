package com.zaijian.zhoumuyun.domain

/**
 * 专长进化系统配置常量（P6，对应设计方案第3节"容量阈值与触发条件"）
 *
 * 所有数字集中在这一个文件，理由：
 *   - 方案讨论阶段已确认这些数字是"合理默认值，全部可调"，不是钉死的魔法数字
 *   - AI 自我提案机制（方案第8节）产出的建议，用户采纳后就是来改这里的某个值，
 *     不应该让同一个阈值分散在 Worker / Engine / Dao 好几个文件里改了一处漏了一处
 *   - 与 Phase 26 DistillationEngine 的 DISTILL_TRIGGER_COUNT 等既有常量保持同样的
 *     "命名+注释说明理由"风格，方便日后维护者一眼看懂为什么是这个数
 */
object SpecialtyEvolutionConfig {

    // ── 成熟度阶段边界（SpecialtyProfileEntity.maturityStage 判定）──────

    /** 摸索期上限：practiceCount <= 此值时仍为 EXPLORING */
    const val EXPLORING_MAX_COUNT = 5

    /** 成型期上限：practiceCount 在 (EXPLORING_MAX_COUNT, FORMING_MAX_COUNT] 之间为 FORMING */
    const val FORMING_MAX_COUNT = 15

    /** practiceCount > FORMING_MAX_COUNT 时进入 STABLE（稳定期） */

    // ── 候选观察池转正 ────────────────────────────────────────────

    /** 候选特征需要被观察到的次数，达到后才进入"转正流程"（摸索/成型期需用户确认，稳定期走强化/补充/冲突判断） */
    const val CANDIDATE_PROMOTION_THRESHOLD = 3

    // ── styleNotes 字数上限 ──────────────────────────────────────

    /** 风格说明书硬上限，与 character_identity.narrativeMemory 同量级 */
    const val STYLE_NOTES_MAX_CHARS = 1000

    /** 方案 2-12：soulNote 兜底拼接时长度上限，防止多轮晋升后膨胀 */
    const val SOUL_NOTE_MAX_CHARS = 2000

    // ── 蒸馏触发阈值（容量驱动，非定时驱动）──────────────────────

    /** 原始产出 → 阶段摘要：单专长累计 RAW 状态记录达到此数量时触发 */
    const val RAW_TO_DIGEST_TRIGGER_COUNT = 10

    /** 阶段摘要 → 并入styleNotes：未合并的 StageDigest 达到此数量时触发 */
    const val DIGEST_TO_PROFILE_TRIGGER_COUNT = 3

    // ── 晋升 Identity Layer 判定（方案第6.2节）────────────────────

    /**
     * 某条特征需要在 styleNotes 中跨越至少这么多次"并入合并"周期、
     * 始终未被判定为冲突或压缩掉，才满足晋升判定条件2（"已稳定存在"）。
     * 注意：这个计数目前在 v1 实现中由 Repository 层基于
     * StageDigestEntity.mergedIntoProfile 的历史记录人工核对，
     * 还没有自动化的"特征级别"追踪（见本文件末尾的已知限制说明）。
     */
    const val PROMOTION_MIN_STABLE_MERGE_CYCLES = 2

    // ── AI 自我提案频率（方案第8节）────────────────────────────────

    /** 每完成这么多次"阶段摘要并入styleNotes"的合并周期，才触发一次建议生成 */
    const val SUGGESTION_TRIGGER_MERGE_CYCLES = 3

    // ── LLM 调用参数（沿用 EvaluationEngine/DistillationEngine 既有风格）──

    /** 风格比对、蒸馏摘要等"判断/规划类"调用统一使用的低 temperature */
    const val JUDGMENT_TEMPERATURE = 0.4f

    /** 每日修炼"创作产出"本身使用的 temperature，比判断类调用更高，鼓励创作多样性 */
    const val PRACTICE_TEMPERATURE = 0.9f

    // ── 已知限制（诚实记录，不在注释里隐藏设计债）─────────────────
    //
    // 1. PROMOTION_MIN_STABLE_MERGE_CYCLES 目前依赖人工核对，没有做到
    //    "某条具体特征文字"级别的自动追踪——styleNotes 是整段覆盖写，
    //    无法用简单的字符串比较判断"这句话是不是从两轮前就一直存在"。
    //    v1 的晋升判定（见 IdentityPromotionEvaluator）采用的近似策略是：
    //    "本轮合并后 LLM 判断该特征是否在上一版本 styleNotes 中已有相近表述"，
    //    连续两次合并都判断为"是"，视为满足条件2。这是合理近似但不是严格追踪，
    //    后续如果需要更精确的版本，可以考虑给 styleNotes 里的每个"特征单元"
    //    单独建模（拆分成多条而不是一整段文本），但那是更大的结构改动，
    //    v1 不做，先用近似策略验证整体流程是否好用。
}
