package com.zaijian.zhoumuyun.data.model

// ─────────────────────────────────────────────────────────────
//  MenstrualCycleState — 生理周期状态机
//
//  取代原 D1 设计里"九人各自第几天"的写死数字方案（PregnancyCycle +
//  isInFertileWindow）。改为真实生理期 App 式的周期状态机：给定一个
//  周期起点，状态随当前日期自动滚动，不需要任何人去记"蒂法第3天"
//  这种内部数字——计算逻辑统一，只有起点因人而异。
//
//  范围边界（与 PregnancyState 同样的隔离原则）：
//  - 不修改 CharacterStateLayer 任何数值字段
//  - 不接入 Presence 行为联动、不接入 WorldSimulation
//  - 怀孕期间这套周期状态机直接停用，由 PregnancyState 接管显示
//    （见 CyclePhase.PREGNANT 说明）
//
//  适用范围：1-6 号（蒂法/露娜/伊芙/宥熙/索菲娅/顾澜，生女儿、参与
//  Agent 生成判定的六人）。7-9 号（明媚/莫婉凝/江凡）是否也接入这套
//  周期显示，是纯 UI/沉浸感选项，不影响 isDaughterMother() 判定本身
//  ——该函数的输入只有 characterId，和这套周期机制完全解耦。
// ─────────────────────────────────────────────────────────────

/**
 * 周期阶段。四态对应头像状态环旁的指示点颜色：
 * 🔴 经期 / 🟢 安全期 / 🟡 排卵期 / 👶 怀孕中
 */
enum class CyclePhase {
    /** 经期，红点 */
    MENSTRUAL,
    /** 安全期，绿点 */
    SAFE,
    /** 排卵期（易孕窗口），黄点 */
    FERTILE,
    /**
     * 怀孕中，宝宝图标。
     * 这个状态不是周期计算出来的，而是外部传入 PregnancyState.isPregnant
     * 之后整套机制直接切到这个态——怀孕期间没有"周期"这回事，所以
     * MenstrualCycleState.currentPhase() 必须先检查怀孕状态，怀孕时
     * 周期计算逻辑直接跳过，不做任何日期推算。
     */
    PREGNANT,
}

/**
 * 单人周期状态。只存一个"周期起点日"，其余全部由日期自动算出，
 * 不存储、不暴露"今天是第几天"这种内部数字——调用方只关心
 * currentPhase() 的结果。
 *
 * 标准生理周期模型（28 天一轮，循环）：
 * - 第 1-5 天：经期（MENSTRUAL）
 * - 第 6-12 天：安全期（SAFE）
 * - 第 13-18 天：排卵期（FERTILE，含排卵前后窗口）
 * - 第 19-28 天：安全期（SAFE）
 * 周期长度和各阶段天数允许逐人微调（见 cycleLengthDays 等参数），
 * 默认值符合常见医学参考范围。
 */
data class MenstrualCycleState(
    val characterId: Int,
    /**
     * 周期起点——本轮经期第一天的时间戳。只需要设置一次，后续
     * 状态完全由"现在距起点过了几天 mod 周期长度"自动滚动。
     * 为 null 表示尚未初始化（如角色第一次进入游戏前）。
     */
    val cycleAnchorAt: Long? = null,
    val cycleLengthDays: Int = 28,
    val menstrualDays: Int = 5,
    val fertileDays: Int = 6,
) {
    /**
     * 计算当前周期阶段。
     * @param isPregnant 外部传入的怀孕状态（来自 PregnancyState），
     *   为 true 时直接返回 PREGNANT，不做任何周期推算——怀孕期间
     *   这套状态机完全让位给 PregnancyState。
     */
    fun currentPhase(
        isPregnant: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): CyclePhase {
        if (isPregnant) return CyclePhase.PREGNANT

        val anchor = cycleAnchorAt ?: return CyclePhase.SAFE // 未初始化时默认安全期，不报错
        val elapsedDays = ((now - anchor) / 86_400_000L).toInt()
        val dayInCycle = ((elapsedDays % cycleLengthDays) + cycleLengthDays) % cycleLengthDays + 1 // 1-indexed，处理负数兜底

        val fertileStart = menstrualDays + ((cycleLengthDays - menstrualDays - fertileDays) / 2) + 1
        val fertileEnd = fertileStart + fertileDays - 1

        return when {
            dayInCycle <= menstrualDays -> CyclePhase.MENSTRUAL
            dayInCycle in fertileStart..fertileEnd -> CyclePhase.FERTILE
            else -> CyclePhase.SAFE
        }
    }

    /** 是否处于易孕窗口——D2 解锁触发的周期判定直接调用这个，不用关心内部天数。 */
    fun isInFertileWindow(isPregnant: Boolean = false, now: Long = System.currentTimeMillis()): Boolean =
        currentPhase(isPregnant, now) == CyclePhase.FERTILE
}

/**
 * 九人默认周期起点偏移（单位：天，相对同一个基准日期错开）。
 * 取代原方案"蒂法第3天/伊芙第10天"这种写死数字——这里只是给
 * 每人一个不同的起点偏移，让九人状态环在视觉上不会同步滚动，
 * 具体偏移值无设定意义，纯粹为了视觉错落。
 *
 * 初始化时：cycleAnchorAt = 基准时间戳 - 偏移天数 × 86_400_000L
 */
val DefaultCycleOffsetDays: Map<Int, Int> = mapOf(
    1 to 0,   // 蒂法
    2 to 5,   // 露娜
    3 to 11,  // 伊芙
    4 to 17,  // 宥熙
    5 to 2,   // 索菲娅
    6 to 9,   // 顾澜
    7 to 14,  // 明媚（如启用周期显示）
    8 to 20,  // 莫婉凝（如启用周期显示）
    9 to 25,  // 江凡（如启用周期显示）
)

// ─────────────────────────────────────────────────────────────
//  UI 接入说明（本次未改 BookCard.kt，留给下一步专门接 UI 时用）
//
//  头像状态环旁挂一个小圆点，颜色取 CyclePhase 对应色值：
//    MENSTRUAL → 红色（如 Color(0xFFE57373)）
//    SAFE      → 绿色（如 Color(0xFF81C784)）
//    FERTILE   → 黄色（如 Color(0xFFFFD54F)）
//    PREGNANT  → 不画圆点，换成宝宝图标（已有 Pregnancy 相关 UI 可参考）
//
//  调用方式：
//    val phase = cycleState.currentPhase(isPregnant = pregnancyState.isPregnant)
//    // 按 phase 选色 / 选图标，渲染在 BookCard 头像角标位置
// ─────────────────────────────────────────────────────────────
