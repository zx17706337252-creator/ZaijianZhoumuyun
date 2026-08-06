package com.zaijian.zhoumuyun.data.prompt

import com.zaijian.zhoumuyun.data.db.entity.MemoryDomain
import com.zaijian.zhoumuyun.data.db.entity.MemoryEntity

/**
 * Memory Layer 构建器（从 PromptOrchestrator 拆出，沿用 object 单例写法）。
 *
 * 从 PromptOrchestrator.kt 迁移的记忆类零外部依赖函数：
 * selectByCharBudget / buildMemoryBlock / buildGroupMemoryBlock /
 * buildNarrativeMemoryBlock / buildMemoryGuidelineBlock。
 */
object MemoryPromptBuilder {

    // ── A-4 isCore 预算上限 ──────────────────────────────────
    //
    // 原 take(5) 改为按字符预算累加。预算值依据：
    //   - isCore 记忆产品设计为"稀疏、慎重"（MemoryDao.kt:94 注释），量级天然可控
    //   - 单条核心记忆平均约 30-60 字符，5 条 ≈ 150-300 字符
    //   - 预算设为 500 字符，可容纳 8-15 条短记忆或 3-5 条长记忆，
    //     比固定 5 条更灵活：短记忆多塞几条，长记忆不会被截断
    //   - 500 字符 ≈ 250-350 token（中文约 1.5 字符/token），占 prompt 比例 <1%
    /** 核心记忆注入的字符预算上限（个人记忆 + 群体共识共用同一预算值） */
    private const val CORE_MEMORY_CHAR_BUDGET = 500

    /**
     * 按字符预算累加核心记忆，替代原来的 take(N)。
     *
     * 逐条累加 content 长度，超出 [CORE_MEMORY_CHAR_BUDGET] 时停止。
     * 保证至少注入第 1 条（即使单条就超预算），避免核心记忆完全丢失。
     *
     * @return 筛选后的记忆列表 + 实际使用的字符数
     */
    fun selectByCharBudget(
        memories: List<MemoryEntity>,
        budget: Int = CORE_MEMORY_CHAR_BUDGET,
    ): List<MemoryEntity> {
        if (memories.isEmpty()) return emptyList()
        val result = mutableListOf<MemoryEntity>()
        var used = 0
        for (m in memories) {
            val len = m.content.length
            if (result.isNotEmpty() && used + len > budget) break
            result.add(m)
            used += len
        }
        return result
    }

    fun buildMemoryBlock(
        coreMemories: List<MemoryEntity>,
        relevantMemories: List<MemoryEntity>,
    ): String {
        if (coreMemories.isEmpty() && relevantMemories.isEmpty()) return ""

        return buildString {
            if (coreMemories.isNotEmpty()) {
                appendLine("核心记忆（必须记住）：")
                // A-4：按字符预算累加，替代原 take(5)
                selectByCharBudget(coreMemories).forEachIndexed { i, m -> appendLine("${i + 1}. ${m.content}") }
            }
            if (relevantMemories.isNotEmpty()) {
                if (coreMemories.isNotEmpty()) appendLine()
                appendLine("相关记忆（本次对话相关）：")
                val coreIds = coreMemories.map { it.id }.toSet()
                relevantMemories
                    .filter { it.id !in coreIds }
                    .take(10)
                    .forEachIndexed { i, m ->
                        // Phase 5（zaijian）：INFERENCE 类型记忆加「（我的猜测）」前缀
                        val prefix = if (m.domain == MemoryDomain.INFERENCE.name) "（我的猜测）" else ""
                        appendLine("${i + 1}. $prefix${m.content}")
                    }
            }
        }.trimEnd()
    }

    /**
     * 群记忆块（圆桌专用，scope=GROUP）。
     *
     * 格式：
     * ```
     * [群体记忆（这个圆桌共同经历过的）]
     * 核心共识（必须记住）：
     * 1. …
     * 2. …
     *
     * 相关群体记忆：
     * 1. …
     * ```
     *
     * 与个人 buildMemoryBlock 平行，但标题不同，语义身份独立：
     * 个人记忆 = 当前角色视角的私人历史；
     * 群体记忆 = 这个圆桌组合共同形成的事实/共识。
     *
     * E1 审计报告 §2.5 修复：新增 [excludeIds] 参数做跨块去重。
     * 个人记忆块已渲染的记忆 id 集合传入此处，群体块在渲染前过滤掉
     * 已在个人块中出现的记忆，防止同一条记忆在最终 Prompt 中重复两次。
     * 正常情况下个人检索只返回 PERSONAL scope、群体检索只返回 GROUP scope，
     * 不会重叠；此参数是防御性兜底，防止 scope 串场或数据异常导致重复。
     */
    fun buildGroupMemoryBlock(
        groupCoreMemories: List<MemoryEntity>,
        groupRelevantMemories: List<MemoryEntity>,
        excludeIds: Set<String> = emptySet(),
    ): String {
        // 跨块去重：过滤掉已在个人记忆块中渲染的记忆
        val filteredCore = groupCoreMemories.filter { it.id !in excludeIds }
        val filteredRelevant = groupRelevantMemories.filter { it.id !in excludeIds }
        if (filteredCore.isEmpty() && filteredRelevant.isEmpty()) return ""

        return buildString {
            appendLine("[群体记忆（这个圆桌共同经历过的）]")
            if (filteredCore.isNotEmpty()) {
                appendLine("核心共识（必须记住）：")
                // A-4：按字符预算累加，替代原 take(5)
                selectByCharBudget(filteredCore).forEachIndexed { i, m -> appendLine("${i + 1}. ${m.content}") }
            }
            if (filteredRelevant.isNotEmpty()) {
                if (filteredCore.isNotEmpty()) appendLine()
                appendLine("相关群体记忆：")
                val coreIds = filteredCore.map { it.id }.toSet()
                filteredRelevant
                    .filter { it.id !in coreIds }
                    .take(8)
                    .forEachIndexed { i, m -> appendLine("${i + 1}. ${m.content}") }
            }
        }.trimEnd()
    }

    fun buildNarrativeMemoryBlock(narrativeMemory: String): String {
        if (narrativeMemory.isEmpty()) return ""
        return "【叙事记忆 —— 她完整保留的过去】\n$narrativeMemory"
    }

    /**
     * 记忆使用准则（常驻注入，不依赖是否有记忆数据）。
     *
     * 给 Agent 的"四个记忆工具怎么分工"指引，对应 redesign v1.0 §2.1/2.2
     * + 补充文档 §6.1/§6.3。工具 description 讲"单个工具怎么用"，这里讲"整体分工"。
     *
     * P1-1 修复（Window A 验收待办）：补充文档 §6.1 要求写进 narrative_memory_update
     * description 的完整阶段日志写法说明，实际被放进了 usageNotes 字段（不注入 prompt）。
     * 本常驻块此前只有浓缩版，遗漏了三条关键细节：①与 memory_write 的分工边界
     * （大多数值得记住的内容改写进这里，不单独建条）；②旧阶段压缩策略；③字数上限。
     * 现将这三条补入，使 LLM 无需依赖 usageNotes 即可获得完整写法指引。控制在
     * 200 字以内（原 150 字基础上 +50 字用于补全遗漏细节）。
     */
    fun buildMemoryGuidelineBlock(): String =
        "【记忆使用准则】memory_write 仅写锚点：身份硬事实、有明确时间/行为的承诺、" +
        "关系重大转折、他要求记住的事；日常情绪/偏好/寒暄改写进 narrative_memory_update " +
        "或 user_impression_update，不单独建条。多数轮次什么都不用记是默认状态。" +
        "narrative_memory_update 是阶段日志（≤1500字）：延续话题扩写最新一条，换话题追加" +
        "新条目并标时间段，不每轮整段重写；旧阶段随篇幅需要自行压缩成一两句话。"
}