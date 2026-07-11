package com.zaijian.zhoumuyun.data.model

// ─────────────────────────────────────────────────────────────
//  PregnancyState — D2.5 + D2.6（排卵期失败概率系统 + 孕期体验与生命事件系统）
//
//  变更（v21 → v22，D2.5）：
//  - cycleDays 字段移除，改为 companion object 常量 CYCLE_DAYS = 30
//  - 新增 consecutiveFailCount：连续排卵期尝试失败次数
//  - 新增 lastFailureInjectedAt：上次"失败背景情绪"跨周期注入的时间戳（门控冷却用）
//
//  变更（v22 → v23，D2.6）：
//  - 新增 miscarriedAt：流产时间戳（null 表示未流产；怀孕开始时清零）
//
//  变更（v23 → v24，怀孕弹窗触发重构）：
//  - 新增 fertileWindowConsentAsked：本次排卵期窗口内是否已经弹过
//    同意弹窗，防止同一排卵期内重复弹窗。离开排卵期（CyclePhase 不再是
//    FERTILE）时由调用方负责清回 false，供下次排卵期重新判定。
//    仅 1000+（第二代/第三代女儿）的 AI 语义判定弹窗链路使用此字段；
//    1-6 号保留的关键词触发兜底链路不读写此字段。
// ─────────────────────────────────────────────────────────────

data class PregnancyState(
    val characterId: Int,
    val isPregnant: Boolean = false,
    /** 怀孕开始的时间戳，用于计算第几天 */
    val pregnancyStartedAt: Long? = null,
    /** 连续排卵期尝试失败次数；成功怀孕后归零 */
    val consecutiveFailCount: Int = 0,
    /** 上次"失败背景情绪"跨周期注入的时间戳；门控冷却用（48h 内不重复注入） */
    val lastFailureInjectedAt: Long? = null,
    /**
     * D2.6：流产时间戳。
     * - null = 未流产过（或怀孕成功开始后被清零）
     * - 非 null = 最近一次流产的 Unix 时间戳（毫秒）
     * 5 天内跨周期流产悲伤余波注入依赖此字段。
     */
    val miscarriedAt: Long? = null,
    /**
     * 怀孕弹窗触发重构：本次排卵期窗口内是否已经弹过同意弹窗。
     * - false = 本排卵期窗口尚未弹过（或刚刚进入新的排卵期窗口）
     * - true  = 本排卵期窗口已经弹过一次，本窗口内不再重复弹窗
     * 默认 false。仅供 characterId >= 1000 的 AI 语义判定弹窗链路使用。
     */
    val fertileWindowConsentAsked: Boolean = false,
) {
    companion object {
        /** 统一孕期天数，不按角色差异化 */
        const val CYCLE_DAYS = 30
    }

    fun currentDay(now: Long = System.currentTimeMillis()): Int {
        val started = pregnancyStartedAt ?: return 0
        val elapsedDays = ((now - started) / 86_400_000L).toInt() + 1
        return elapsedDays.coerceIn(1, CYCLE_DAYS)
    }

    fun isDueToday(now: Long = System.currentTimeMillis()): Boolean =
        isPregnant && currentDay(now) >= CYCLE_DAYS

    /**
     * D2.6：距流产已过几天（整数天，向下取整）。
     * 未流产（miscarriedAt == null）时返回 Int.MAX_VALUE，
     * 供门控判断"是否在 5 天内"：miscarriageDaysAgo() <= 5。
     *
     * L-7 修复：原为计算属性，每次访问都重新调用 System.currentTimeMillis()，
     * 同一段逻辑内多次访问（例如跨午夜边界）可能得到不同结果。
     * 改为接受 now 参数的函数，由调用方传入统一的时间快照。
     */
    fun miscarriageDaysAgo(now: Long = System.currentTimeMillis()): Int =
        miscarriedAt?.let {
            ((now - it) / 86_400_000L).toInt()
        } ?: Int.MAX_VALUE
}

/** 单条生育记录，追加进角色档案 */
data class BirthRecord(
    val characterId: Int,
    val bornAt: Long,
    /** true=女孩，false=男孩 */
    val isDaughter: Boolean,
)

// ─────────────────────────────────────────────────────────────
//  性别规则（已拍板，写死）：
//  1蒂法/2露娜/3伊芙/4宥熙/5索菲娅/6顾澜 → 生女儿 → 触发新 Agent 生成
//  7明媚/8莫婉凝/9江凡 → 生男孩 → 不生成 Agent，仅档案记录
//
//  女儿系统门控扩展（第三代封顶接入，详见 11.1 决策 5）：
//  - 第二代女儿（characterId 1000+，母亲是 1-9 号原生角色）与
//    第三代女儿（characterId 1000+，母亲也是 1000+ 的女儿）
//    都要拥有完整的经期/排卵期/怀孕流程，否则永远无法触发任何后续机制。
//  - characterId 从 1000 开始只分配给「已生成的女儿」（见
//    DaughterIdAllocator.allocate()），不会被其他用途占用，所以
//    "characterId >= 1000" 本身就是"这是一位女儿"的充分条件，
//    判断是否要走怀孕全流程时不需要再查 daughter_character 表。
//  - 但"是不是第三代"（用于截断 D3 槎位问答 / D4 生成器，不让女儿
//    继续生第四代）无法只靠 characterId 范围判断，需要查
//    daughter_character 表确认这一行的 motherCharacterId 是否也
//    ≥ 1000（即母亲本身也是一位女儿）。这部分判断需要 DAO 访问，
//    本文件是纯数据模型、不持有 Repository 依赖，因此放在
//    DaughterCharacterRepository.isThirdGeneration() 里实现，
//    不在这里提供同名函数（避免出现"看起来能用但实际拿不到数据"的假函数）。
// ─────────────────────────────────────────────────────────────

fun isDaughterMother(characterId: Int): Boolean =
    characterId in setOf(1, 2, 3, 4, 5, 6) || characterId >= 1000
