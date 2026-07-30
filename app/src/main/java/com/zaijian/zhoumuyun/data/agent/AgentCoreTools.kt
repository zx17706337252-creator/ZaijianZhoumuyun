package com.zaijian.zhoumuyun.data.agent

/**
 * AgentCoreTools.kt — Agent 核心记忆 / 目标 / 规则工具
 *
 * 包含 5 个工具：
 *   ⑰ PlanSaveTool     — Agent 写入进化方案（plan_save）
 *   ⑱ MemoryWriteTool  — Agent 主动写入记忆（memory_write）
 *   ⑲ MemoryQueryTool  — Agent 主动检索记忆（memory_query）
 *   ⑳ GoalUpdateTool   — 推进学习目标进度（goal_update）
 *   ㉑ RuleDistillTool — 主动触发规则提炼（rule_distill）
 *
 * 注册入口：
 *   ZaijianApp.onCreate() 中分段 registerAll(...) / register(...)，
 *   见该文件的 "Phase 22 · Agent 进化方案" 注释段。
 *   （MemoryWriteTool 依赖 MemoryRepository，需在 AppDatabase 初始化后手动装配，
 *     故无法用单一 register*() 扩展函数统一封装，保持现有内联注册方式。）
 */

import com.zaijian.zhoumuyun.data.db.entity.AgentPlanEntity
import com.zaijian.zhoumuyun.data.db.entity.MemoryDomain
import com.zaijian.zhoumuyun.data.db.entity.MemoryEntity
import com.zaijian.zhoumuyun.data.db.entity.MemoryScope
import com.zaijian.zhoumuyun.data.provider.chatSyncWithRetry
import com.zaijian.zhoumuyun.data.repository.AgentPlanRepository
import com.zaijian.zhoumuyun.data.repository.LearningGoalRepository
import com.zaijian.zhoumuyun.data.repository.MemoryRepository
import com.zaijian.zhoumuyun.domain.currentSpeakerContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import com.zaijian.zhoumuyun.data.provider.LLMProvider
import com.zaijian.zhoumuyun.data.provider.LLMMessage
import com.zaijian.zhoumuyun.data.provider.LLMConfig
import com.zaijian.zhoumuyun.util.ChineseTokenizer
import com.zaijian.zhoumuyun.util.ZLog

// ─────────────────────────────────────────────────────────────
//  ⑰ PlanSaveTool — 保存 Agent 进化方案
// ─────────────────────────────────────────────────────────────

/**
 * Agent 写入自己进化方案的工具。
 *
 * 标签格式：<tool:plan_save title="方案标题" content="方案正文"/>
 *
 * 行为：
 *   1. 将当前角色的旧方案全部归档（isActive=false）
 *   2. 写入新方案（isActive=true）
 *   3. 返回成功提示，方案在下次对话时注入 AgentPlan Layer
 *
 * AgentPlan Layer 注入位置：Memory Layer 之上，World Layer 之前（第5层）。
 * 内容截取前 500 tokens（约 1500 字），超出部分截断。
 */
class PlanSaveTool(
    private val agentPlanDao: AgentPlanRepository,
    private val characterId: () -> Int,
) : AgentTool {

    override val name = "plan_save"
    override val description = "保存/归档Agent自身的进化方案，下次对话自动注入System Prompt"
    override val paramKeys = listOf("title", "content")

    companion object {
        const val MAX_CONTENT_CHARS = 1500  // 约 500 tokens
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val charId  = characterId()
        if (charId < 0) return@withContext ToolResult(name, false, "", "角色未初始化")

        val title   = params["title"]?.trim()?.take(50)
        val content = params["content"]?.trim()

        if (title.isNullOrEmpty()) {
            return@withContext ToolResult(name, false, "", "需要 title 参数")
        }
        if (content.isNullOrEmpty()) {
            return@withContext ToolResult(name, false, "", "需要 content 参数")
        }

        val truncatedContent = if (content.length > MAX_CONTENT_CHARS) {
            content.take(MAX_CONTENT_CHARS) + "\n（内容过长，已截断）"
        } else content

        val now = System.currentTimeMillis()

        try {
            // 归档旧方案
            agentPlanDao.archiveActive(charId, now)

            // 写入新方案
            val plan = AgentPlanEntity(
                id          = UUID.randomUUID().toString(),
                characterId = charId,
                title       = title,
                content     = truncatedContent,
                isActive    = true,
                createdAt   = now,
                updatedAt   = now,
            )
            agentPlanDao.insert(plan)

            ToolResult(
                toolName = name,
                success  = true,
                content  = "✅ 进化方案「$title」已保存，将在下次对话时生效。",
                userHint = "正在保存进化方案…",
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 收尾修复：e.message 可能含数据库/文件路径细节，改用 toolFailure 统一脱敏
            toolFailure(name, "保存进化方案失败，请稍后重试。", "plan_save_failed", e)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ⑱ MemoryWriteTool — Agent 主动写入记忆
// ─────────────────────────────────────────────────────────────

/**
 * Agent 主动向自己的记忆库写入一条记忆（稀疏锚点）。
 *
 * 标签格式：
 *   <tool:memory_write content="记忆内容" domain="WORK|PERSONAL|WORLD" importance="1-5"/>
 *
 * 可选参数：
 *   keywords="关键词1 关键词2"（空格分隔，用于 FTS4 检索；不填则自动提取前5个词）
 *   scope="PERSONAL|GROUP"（默认 PERSONAL；圆桌全员共享的群体事实传 GROUP）
 *   roundtableId="圆桌ID"（scope=GROUP 时必填，否则群记忆无法归属）
 *
 * 行为：
 *   - 直接写入 memories 表（跳过 MemoryCandidate 层，Agent 主动写入无需评分）
 *   - importance 默认为 3（长期记忆）；=5 时自动标记 isCore（永不衰减）
 *   - domain 默认为 WORK
 *   - scope=GROUP 时校验 roundtableId 非空，缺失则写入失败（不静默降级为 PERSONAL）
 */
class MemoryWriteTool(
    private val memoryRepository: MemoryRepository,
    private val characterId: () -> Int,
) : AgentTool {

    override val name = "memory_write"
    // P3-6 修复（Window A 验收待办）：description 收敛为一句简述（≤40字），
    // 详细的适用范围 / 参数格式 / 与 narrative_memory_update 的分工移入 usageNotes。
    // 分工边界已在 PromptOrchestrator.buildMemoryGuidelineBlock() 常驻注入，此处不重复。
    override val description = "写入一条独立检索用的记忆锚点（非日常叙事类）"
    override val usageNotes = "仅用于：身份类硬事实、有具体时间/行为的明确承诺、关系的重大转折点、" +
        "用户明确要求记住的事实。日常情绪、偏好、寒暄不要用这个工具，应通过 " +
        "narrative_memory_update / user_impression_update 融入整体叙事。" +
        "圆桌场景下，若这条信息是全员共享的群体事实，传 scope=GROUP + roundtableId。" +
        "importance 默认 3（长期记忆），=5 时自动标记 isCore（永不衰减）。" +
        "content 最长 500 字。"
    override val paramKeys = listOf("content", "domain", "importance", "keywords", "scope", "roundtableId")

    companion object {
        const val MAX_CONTENT_CHARS = 500
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = try {
        withContext(Dispatchers.IO) {
        val charId = characterId()
        if (charId < 0) return@withContext ToolResult(name, false, "", "角色未初始化")

        val content = params["content"]?.trim()
        if (content.isNullOrEmpty()) {
            return@withContext ToolResult(name, false, "", "需要 content 参数")
        }

        val truncatedContent = content.take(MAX_CONTENT_CHARS)

        // 解析 domain
        val domainStr = params["domain"]?.uppercase()?.trim() ?: "WORK"
        val domain = try {
            MemoryDomain.valueOf(domainStr)
        } catch (e: IllegalArgumentException) {
            MemoryDomain.WORK
        }

        // 解析 importance（1-5，默认 3）
        val importance = params["importance"]?.toIntOrNull()?.coerceIn(1, 5) ?: 3

        // 关键词：用户提供 or 自动从内容中提取
        val keywords = params["keywords"]?.trim()?.ifEmpty { null }
            ?: extractKeywords(truncatedContent)

        // 解析 scope：缺省/非法值一律按 PERSONAL（不静默降级 GROUP→PERSONAL，
        // 仅对非法的 scope 取值宽松处理）；scope=GROUP 时必须带 roundtableId，
        // 缺失则直接返回失败——避免群记忆被误写成无归属的个人记忆。
        val scopeStr = params["scope"]?.uppercase()?.trim() ?: "PERSONAL"
        val isGroup = scopeStr == "GROUP"
        val roundtableId: String? = if (isGroup) {
            val rtId = params["roundtableId"]?.trim()
            if (rtId.isNullOrEmpty()) {
                return@withContext ToolResult(
                    name, false, "",
                    "scope=GROUP 时必须提供 roundtableId（圆桌 ID），群记忆需要明确归属",
                )
            }
            rtId
        } else {
            null
        }

        val now = System.currentTimeMillis()
        val memoryId = UUID.randomUUID().toString()

        // 场景一记忆隔离修复（方案 v1.5 第 4.2 节 isNarrativeOnly，此前建了列但
        // 从未有代码实际写入 true）：读取本轮协程绑定的 speakerContext——
        // NON_OWNER 表示 owner 本人正在单聊窗口里冒充第三方（角色 B）撩本角色，
        // 这轮对话产生的记忆不该被当成"owner 与本角色的正常互动"一样对待。
        // 不阻断写入（工具调用失败会让 LLM 困惑/重试），而是打标记，交给
        // MemoryEntity.isNarrativeOnly 文档里说的"读取侧过滤层"处理——与
        // messages.speakerContext 落库时的处理方式（打标记而非拒绝）保持一致。
        val isNarrativeOnly = currentSpeakerContext().isNonOwner

        try {
            // 写入主表
            val entity = MemoryEntity(
                id             = memoryId,
                characterId    = charId,
                domain         = domain.name,
                content        = truncatedContent,
                importance     = importance,
                keywords       = keywords,
                sourceEventId  = null,
                isCore         = (importance == 5),
                createdAt      = now,
                updatedAt      = now,
                lastAccessedAt = now,
                scope          = if (isGroup) MemoryScope.GROUP.name else MemoryScope.PERSONAL.name,
                roundtableId   = roundtableId,
                isNarrativeOnly = isNarrativeOnly,
            )
            // saveOrMerge：先找同角色同关键词的相似记忆，有则合并，无则新建。
            // FTS 同步由 MemoryRepository.save() / update() 内部处理，无需手动维护。
            memoryRepository.saveOrMerge(entity)

            ToolResult(
                toolName = name,
                success  = true,
                content  = "✅ 已记录：「${truncatedContent.take(30)}…」（${domain.name}，重要度 $importance）" +
                    if (isNarrativeOnly) "（标记为叙事记忆，不计入长期归纳）" else "",
                userHint = "正在写入记忆…",
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            toolFailure(name, "写入记忆失败，请稍后重试。", "memory_write_failed", e)
        }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        toolFailure(name, "记忆写入工具执行失败，请稍后重试。", "memory_write_tool_failed", e)
    }

    /**
     * 从内容中提取关键词，写入 keywords 字段（同步进 FTS4 + L2 标签索引）。
     *
     * E1 审计报告任务1 修复：原实现按标点/空白切分取长度≥2的前5段。中文口语
     * 句子内部没有空格，一句话如果中间没有逗号，切出来的"关键词"就是整句话
     * 本身；如果有逗号，切出来的是每个分句的完整文本（而非分句内的词）——
     * 都不是词粒度，FTS 前缀匹配和 L2 tag 精确匹配双双失效。
     * 改为委托 [ChineseTokenizer] 做真正的中文分词，与查询侧共用同一分词器。
     */
    private fun extractKeywords(content: String): String = ChineseTokenizer.tokenizeJoined(content)
}

// ─────────────────────────────────────────────────────────────
//  ⑲ MemoryQueryTool — Agent 主动检索历史记忆
// ─────────────────────────────────────────────────────────────

/**
 * Agent 主动检索自己历史记忆的工具。
 *
 * 标签格式：
 *   <tool:memory_query query="检索关键词" domain="WORK|PERSONAL|WORLD" limit="5"/>
 *
 * 可选参数：
 *   domain  — 限定检索域（不填则全域检索）
 *   limit   — 返回条数（1-10，默认 5）
 *
 * 行为：
 *   - 使用 FTS4 全文检索（searchByFts），精确召回相关记忆
 *   - 结果按 importance DESC, lastAccessedAt DESC 排序
 *   - 召回成功后更新 accessCount（记录被 Agent 主动检索次数）
 *
 * 返回格式：
 *   [记忆检索结果: "query"]
 *   1. [WORK] 记忆内容… (重要度: 4)
 *   2. [PERSONAL] 记忆内容… (重要度: 3)
 *   （无结果时提示「未找到相关记忆」）
 */
class MemoryQueryTool(
    private val memoryRepo: MemoryRepository,
    private val characterId: () -> Int,
) : AgentTool {

    override val name = "memory_query"
    override val description = "Agent主动全文检索自己的历史记忆，按重要度和时间排序返回结果"
    override val paramKeys = listOf("query", "domain", "limit")

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val charId = characterId()
        if (charId < 0) return@withContext ToolResult(name, false, "", "角色未初始化")

        val query = params["query"]?.trim()
        if (query.isNullOrEmpty()) {
            return@withContext ToolResult(name, false, "", "需要 query 参数")
        }

        val domainFilter = params["domain"]?.uppercase()?.trim()
        val limit = params["limit"]?.toIntOrNull()?.coerceIn(1, 10) ?: 5

        try {
            // E1 审计报告任务1 修复：原实现裸 query.split(" ") 按 空白切分构造
            // FTS 查询，对无空格的连续中文失效。改为复用 memoryRepo.buildFtsQuery()，
            // 与 searchRelevant / searchRelevantWithRouting 共用同一套（基于
            // ChineseTokenizer 的）查询分词 + 前缀匹配构造，保证一致性。
            val ftsQuery = memoryRepo.buildFtsQuery(query)

            val results = memoryRepo.searchByFts(charId, ftsQuery, limit * 2)  // 多取一些再过滤

            val filtered = if (domainFilter != null) {
                results.filter { it.domain.equals(domainFilter, ignoreCase = true) }
            } else {
                results
            }.take(limit)

            if (filtered.isEmpty()) {
                return@withContext ToolResult(
                    toolName = name,
                    success  = true,
                    content  = "[记忆检索结果: \"$query\"]\n未找到相关记忆。",
                )
            }

            // 更新访问记录（异步，不阻塞结果返回）
            filtered.forEach { memory ->
                try { memoryRepo.recordAccess(memory.id) } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    ZLog.w("AgentCoreTools", "更新记忆访问记录失败 id=${memory.id}: ${e.message}")
                }
            }

            val resultText = buildString {
                appendLine("[记忆检索结果: \"$query\"]")
                filtered.forEachIndexed { i, memory ->
                    val preview = memory.content.take(100).let {
                        if (memory.content.length > 100) "$it…" else it
                    }
                    appendLine("${i + 1}. [${memory.domain}] $preview（重要度: ${memory.importance}）")
                }
            }.trimEnd()

            ToolResult(
                toolName = name,
                success  = true,
                content  = resultText,
                userHint = "正在检索记忆…",
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            toolFailure(name, "检索记忆失败，请稍后重试。", "memory_query_failed", e)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ⑳ GoalUpdateTool — 推进学习目标进度
// ─────────────────────────────────────────────────────────────

/**
 * 推进学习目标进度的工具。
 *
 * 标签格式：
 *   <tool:goal_update goal_id="目标UUID" delta="0.1" note="本次进步描述"/>
 *
 * 参数说明：
 *   goal_id — 学习目标的 UUID（由用户在目标管理 UI 中创建，Agent 从 Prompt 上下文获取）
 *   delta   — 进度增量（0.0–1.0，代表进度百分比，如 0.1 = 进度增加 10%）
 *   note    — 本次进步描述（可选，≤100字，记录在 lastUpdateNote 中）
 *
 * 行为：
 *   - 验证 goal_id 属于当前 characterId（安全隔离）
 *   - 更新 learning_goals 表的 progress 字段（不超过 1.0）
 *   - 若 progress 达到 1.0，自动将 status 设为 COMPLETED
 *   - 返回当前进度百分比
 *
 * Phase 22 限制：
 *   - 目标需由用户事先创建（Phase 23 新增创建 UI）
 *   - Agent 只能推进进度，不能创建或删除目标
 */
class GoalUpdateTool(
    // P3-14 修复：字段名从 goalDao 改为 goalRepo——类型是 LearningGoalRepository
    // 而非 DAO，原名与实际类型不符（与 ChatViewModel 内 learningGoalRepo 字段
    // 是同一处命名不一致问题，二者一并修正）。
    private val goalRepo: LearningGoalRepository,
    private val characterId: () -> Int,
) : AgentTool {

    override val name = "goal_update"
    override val description = "推进用户学习目标的进度百分比，达到100%自动标记完成"
    override val paramKeys = listOf("goal_id", "delta", "note")
    override val usageNotes = "delta 须为 0.01~1.0 之间的小数（如 0.1 代表10%进度增量），不是整数"

    companion object {
        const val MAX_DELTA = 1.0f
        const val MIN_DELTA = 0.01f
        const val MAX_NOTE_CHARS = 100
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val charId = characterId()
        if (charId < 0) return@withContext ToolResult(name, false, "", "角色未初始化")

        val goalId = params["goal_id"]?.trim()
        if (goalId.isNullOrEmpty()) {
            return@withContext ToolResult(name, false, "", "需要 goal_id 参数")
        }

        val delta = params["delta"]?.toFloatOrNull()
        if (delta == null || delta < MIN_DELTA || delta > MAX_DELTA) {
            return@withContext ToolResult(
                name, false, "",
                "delta 参数无效，需为 $MIN_DELTA–$MAX_DELTA 之间的小数（如 0.1 代表 10%）"
            )
        }

        val note = params["note"]?.trim()?.take(MAX_NOTE_CHARS)

        try {
            // 验证目标存在且属于当前角色
            val goal = goalRepo.getById(goalId)
            if (goal == null) {
                // P2-09 修复：统一错误信息仅放入 error 字段，content 留空，
                // 与早期校验分支（charId/goalId/delta）的返回格式一致。
                return@withContext ToolResult(name, false, "", "目标不存在：$goalId")
            }
            if (goal.characterId != charId) {
                return@withContext ToolResult(name, false, "", "目标 $goalId 不属于当前角色")
            }
            if (!goal.isActive) {
                return@withContext ToolResult(name, false, "", "目标「${goal.title}」已停用，无法更新进度")
            }

            // 更新进度
            goalRepo.incrementProgress(
                goalId      = goalId,
                characterId = charId,
                delta       = delta,
                note        = note,
            )

            // 读取更新后的目标（用于显示最新进度）
            val updated = goalRepo.getById(goalId)
            val newProgress = updated?.progress ?: (goal.progress + delta).coerceAtMost(1.0f)
            val progressPct = "%.0f%%".format(newProgress * 100)

            val isCompleted = newProgress >= 1.0f
            val statusMsg = if (isCompleted) "🎉 目标已完成！" else "当前进度：$progressPct"

            ToolResult(
                toolName = name,
                success  = true,
                content  = "✅ 目标「${goal.title}」进度 +${(delta * 100).toInt()}%。$statusMsg${if (note != null) "\n备注：$note" else ""}",
                userHint = "正在更新学习目标…",
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // P2-09 修复：统一错误信息仅放入 error 字段；收尾修复：改用 toolFailure 避免 e.message 泄露
            toolFailure(name, "更新目标进度失败，请稍后重试。", "goal_update_failed", e)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ㉑ RuleDistillTool — 规则提炼（写入 RULE 域记忆，isLocked=false）
// ─────────────────────────────────────────────────────────────

/**
 * Agent 主动触发规则提炼工具。
 *
 * 标签格式：
 *   <tool:rule_distill goal_id="{目标ID}" rules="{规则1|规则2|规则3}"/>
 *
 * 参数：
 *   - goal_id  关联的激活学习目标 ID（必填）
 *   - rules    提炼出的规则列表，多条用 | 分隔，最多 3 条，每条 ≤100 字
 *
 * 行为：
 *   1. 验证 goal_id 对应的目标存在且处于激活状态
 *   2. 检查该目标下现有 RULE 数量（防止规则爆炸，上限 30 条）
 *   3. 调用 LLM 精简/规范化规则表述（temperature=0.1，去除冗余）
 *   4. 将规则写入 memories 表（domain=RULE, isLocked=false, importance=3）
 *   5. 返回已写入的规则列表
 *
 * Phase 26 依赖：
 *   Phase 26 的提炼引擎会读取 domain=RULE, isLocked=false 的记忆，
 *   统计其在高分 Session 中的出现频次；达到锁定条件后调用 lockRule()。
 */
class RuleDistillTool(
    // S8-窗口11 P1-8-7 修复：原 `provider: LLMProvider` 直接持有实例，
    // 与其他多参数 LLM 工具（SelfReflectTool/RuleReviewTool/RuleConflictCheckTool
    // 等）统一使用 `providerFn: () -> LLMProvider?` 闭包延迟获取的模式不一致。
    // 两阶段注册（ZaijianApp 占位 + ChatToolRegistrar 覆盖）只在角色切换时
    // 重注册，若用户仅切换 Provider/Key 而不切换角色，旧实例会一直被使用
    // 直到下次角色切换；更严重的是原实现两处注册点都写成
    // `xxxProvider?.let { p -> register(...) }`，若注册时刻 activeProvider
    // 恰好为 null（如首次启动未配置 Key），rule_distill 会直接跳过注册、
    // 完全不可用，需要等待角色切换才能补上。改为 providerFn 闭包后，工具
    // 本身可以无条件注册，execute() 时才动态取最新 Provider，与其余 LLM
    // 工具行为一致。不给默认值，与 SelfReflectTool/RuleConflictCheckTool
    // 等同样"providerFn + 多个必填参数"的构造惯例保持一致（唯一使用默认值
    // 的 CodeGenTool/CodeReviewTool 是单参数构造，不适用于此处）。
    private val providerFn: () -> LLMProvider?,
    private val memoryRepo: MemoryRepository,
    // P3-14 修复：字段名从 goalDao 改为 goalRepo——类型是 LearningGoalRepository 而非 DAO
    private val goalRepo: LearningGoalRepository,
    private val characterId: () -> Int,
) : AgentTool {

    override val name = "rule_distill"
    override val description = "从当前学习目标的对话经验中提炼出可复用的行为规则并写入记忆"
    override val paramKeys = listOf("goal_id", "rules")
    override val usageNotes = "rules 用 | 分隔多条规则，每次最多3条，每条≤100字"

    companion object {
        const val MAX_RULES_PER_CALL = 3        // 每次最多提炼条数
        const val MAX_RULES_PER_GOAL = 30       // 每目标总规则上限
        const val MAX_RULE_CHARS = 100          // 每条规则最大字符数
        const val RULE_IMPORTANCE = 3           // 初始 importance（未锁定规则）
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = try {
        withContext(Dispatchers.IO) {
        val charId = characterId()
        if (charId < 0) return@withContext ToolResult(name, false, "", "角色未初始化")

        val goalId = params["goal_id"]?.trim()
            ?: return@withContext ToolResult(name, false, "", "需要 goal_id 参数")

        val rulesRaw = params["rules"]?.trim()
            ?: return@withContext ToolResult(name, false, "", "需要 rules 参数")

        // ── 1. 验证目标是否存在且激活 ────────────────────────
        // P1 修复：getById 移入 try，避免数据库异常导致未捕获崩溃
        val goal = try { goalRepo.getById(goalId) }
            catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                return@withContext toolFailure(name, "读取学习目标失败，请稍后重试。", "goal_read_failed", e)
            }
        if (goal == null || !goal.isActive) {
            return@withContext ToolResult(
                name, false, "",
                "目标 $goalId 不存在或已停用，无法关联规则"
            )
        }
        // P1-14 修复（窗口3 P1-7）：原实现未校验 goal.characterId 与当前角色是否一致，
        // 当前角色的 LLM 传入其他角色名下的 goal_id 时可越权提炼/写入规则。
        if (goal.characterId != charId) {
            return@withContext ToolResult(
                name, false, "",
                "目标 $goalId 不属于当前角色，无法关联规则"
            )
        }

        // ── 2. 检查现有规则数量上限 ───────────────────────────
        // 修复：原实现只把 goalRepo.getById() 包了 try-catch，这两条查询仍在
        // try 外——虽然外层 ToolCallInterceptor/WorkflowEngine 有 catch-all
        // 兜底不会真崩，但异常会以未处理形式穿透 execute()，报错信息不友好。
        // 补齐同样的 try-catch 覆盖，与上面 goalRepo.getById() 保持一致。
        val existingCount = try {
            memoryRepo.countLockedRules(charId, goalId) +
                // 也统计未锁定规则，防止 Phase 26 前的候选规则过多
                // 性能 M2 修复：getRulesByGoal 在数据库层按 goalId 过滤，替代全量加载
                memoryRepo.getRulesByGoal(charId, goalId).count { !it.isLocked }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            return@withContext toolFailure(name, "读取现有规则数量失败，请稍后重试。", "rule_count_failed", e)
        }
        if (existingCount >= MAX_RULES_PER_GOAL) {
            return@withContext ToolResult(
                name, false, "",
                "目标「${goal.title}」下已有 $existingCount 条规则（上限 $MAX_RULES_PER_GOAL），" +
                    "请先通过 rule_review 清理冗余规则"
            )
        }

        // ── 3. 解析输入规则，截断过长内容 ────────────────────
        val inputRules = rulesRaw.split("|")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_RULES_PER_CALL)
            .map { if (it.length > MAX_RULE_CHARS) it.take(MAX_RULE_CHARS) else it }

        if (inputRules.isEmpty()) {
            return@withContext ToolResult(name, false, "", "rules 参数内容为空，请用 | 分隔多条规则")
        }

        // ── 4. LLM 规范化规则表述（精简去冗，temperature=0.1）
        val normalizedRules = try {
            val provider = providerFn()
                ?: throw IllegalStateException("当前未配置 API，请在设置中填写 API Key。")
            val prompt = buildString {
                appendLine("以下是从对话中提炼的能力规则候选，请逐条精简为简洁的行为准则（每条≤${MAX_RULE_CHARS}字）。")
                appendLine("要求：保留核心指导意义，去除重复表述，输出格式为每条一行，不加编号。")
                appendLine()
                inputRules.forEach { appendLine(it) }
            }
            val resp = provider.chatSyncWithRetry(
                messages = listOf(LLMMessage("user", prompt)),
                systemPrompt = "你是规则提炼助手，输出简洁精准的能力规则，每条≤${MAX_RULE_CHARS}字，每行一条，不加序号或标点前缀。",
                config = LLMConfig(model = "", maxTokens = 300, temperature = 0.1f, stream = false),
            )
            resp.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(MAX_RULES_PER_CALL)
                .map { if (it.length > MAX_RULE_CHARS) it.take(MAX_RULE_CHARS) else it }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // LLM 精简失败时直接使用原始输入（审查项 2.19：补充日志，便于排查精简失败原因）
            ZLog.w("AgentCoreTools", "规则精简调用LLM失败，降级使用原始输入: ${e.message}")
            inputRules
        }

        // ── 5. 写入 DB（domain=RULE, isLocked=false）─────────
        // M3修复：改用 memoryRepo.save()，保证主表与 FTS4 虚拟表原子同步写入，
        // 避免规则记忆绕过 FTS 索引导致后续全文检索漏召回。
        val now = System.currentTimeMillis()
        val written = mutableListOf<String>()

        for (ruleContent in normalizedRules) {
            try {
                val memoryId = UUID.randomUUID().toString()
                memoryRepo.save(
                    MemoryEntity(
                        id             = memoryId,
                        characterId    = charId,
                        domain         = MemoryDomain.RULE.name,
                        content        = ruleContent,
                        importance     = RULE_IMPORTANCE,
                        keywords       = goal.title,       // 以目标标题为关键词，方便 FTS4 检索
                        sourceEventId  = null,
                        isCore         = false,
                        isLocked       = false,            // Phase 26 达标后才锁定
                        goalId         = goalId,
                        accessCount    = 0,
                        createdAt      = now,
                        updatedAt      = now,
                        lastAccessedAt = now,
                    )
                )
                written.add(ruleContent)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 单条写入失败不影响其他条目（审查项 2.19：补充日志，便于排查具体哪条规则写入失败）
                ZLog.w("AgentCoreTools", "规则写入失败，跳过该条: ${e.message}")
            }
        }

        if (written.isEmpty()) {
            return@withContext ToolResult(name, false, "", "规则写入失败，请重试")
        }

        val resultText = buildString {
            appendLine("已为目标「${goal.title}」提炼 ${written.size} 条候选规则：")
            written.forEachIndexed { i, r -> appendLine("${i + 1}. $r") }
            append("（规则将在积累足够高分对话后自动锁定并生效）")
        }

        ToolResult(name, true, resultText.trim())
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        // P1-1 修复：补齐顶层 catch——此前 rule_distill 的 execute 仅对个别 DB/LLM
        // 调用包了 try-catch，参数解析、规则文本构建等路径若抛异常（含 Error 子类）
        // 会直接穿透 execute()，违反 AgentTool 契约。顶层 try-catch 兜底确保任何
        // 未预见异常都走 toolFailure，不向上抛出。
        toolFailure(name, "规则提炼失败，请稍后重试。", "rule_distill_failed", e)
    }
}

// ─────────────────────────────────────────────────────────────
//  注：本文件工具的注册见 ZaijianApp.kt「Phase 22 · Agent 进化方案」段落，
//      Phase 25 的 RuleDistillTool 紧随其后。
// ─────────────────────────────────────────────────────────────
