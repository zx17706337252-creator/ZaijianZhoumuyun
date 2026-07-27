package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.db.entity.SkillEntity
import com.zaijian.zhoumuyun.data.db.entity.SkillSourceType
import com.zaijian.zhoumuyun.data.db.entity.SkillStatus
import com.zaijian.zhoumuyun.data.repository.SkillRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.zaijian.zhoumuyun.util.ZLog
import java.util.Calendar
import java.util.UUID

/**
 * Window C · Agent 自主技能系统 —— 5 个 AgentTool + 目录生成器。
 *
 * 设计来源：Window C 技能系统设计方案 v1.2 §4.2 / §3。
 *
 * ## 与设计方案 v1.2 §4.2 草案的差异（以源码为准，详见交付变更说明）
 *
 * 1. **每个工具构造函数新增 `characterId: () -> Int`**：技能是纯角色私有的
 *    （§1.3 已拍板），工具必须知道"当前是哪个角色"才能读/写该角色的技能。
 *    草案 `SkillCreateTool(private val repo: SkillRepository)` 缺这一参，无法落地。
 *    核实到的真实范式是 [MemoryWriteTool]（`AgentCoreTools.kt:135`），其构造函数即
 *    `(memoryRepository, characterId: () -> Int)`（`AgentCoreTools.kt:137`），并在
 *    `execute()` 开头做 `charId < 0` 兜底校验（`AgentCoreTools.kt:150`）。本文件 5 个
 *    工具完全照抄该范式。相应地注册方式也跟随 [MemoryWriteTool]：先在
 *    [com.zaijian.zhoumuyun.ZaijianApp.registerAgentTools] 里以 `{-1}` 静态占位注册，
 *    再在 `ChatToolRegistrar.registerCharacterTools(currentCharacterId)` 里按真实角色
 *    覆盖注册（与 `MemoryWriteTool` 在 `ChatToolRegistrar.kt:143` 同款两阶段注册）。
 *
 * 2. **全部使用标准 `<tool:name key="val"/>` 格式**，不新增标签语法（§3 v1.1 修正），
 *    复用现成 `ToolParser` / `ToolCallInterceptor` 的 `while(round<maxRounds)` 循环
 *    （`ToolCallInterceptor.kt:202`）分发，结果按现有机制自动回注对话（§3.5 步骤 3-4）。
 *
 * 3. **`SkillRegistry.buildSkillCatalogBlock` 改为 `suspend`**：它内部调用
 *    `repo.getActiveSkills()`（suspend），调用点（`ChatMessageOrchestrator` 拼 prompt 处）
 *    本就在协程上下文里，加 suspend 不影响调用方。
 *
 * 4. **目录块每条附带 `skill_id`**：§3 目录示例只列 `name（shortDescriptor）`，但 §3.5
 *    要求端到端无缺环——`skill_expand` 的入参是 `skill_id`，目录若不给 id，Agent 无从
 *    调起展开。故每行追加 `id=...`，闭合"看到目录→能展开"这一环。
 *
 * 5 个工具内部全部调用 [SkillRepository]，不直接碰 DAO——"Agent 自己改的"和"用户在面板
 * 手动改的"走同一写入路径，UI 侧只接一次 `observeSkills()` Flow 即可（§4.1）。
 */

// ─────────────────────────────────────────────────────────────
//  skill_create —— 把刚完成的复杂任务方法沉淀成一条可复用技能
// ─────────────────────────────────────────────────────────────

/**
 * §2 创建 / §3.5 步骤 5。
 *
 * 标签格式：`<tool:skill_create name=".." short_desc=".." full_content=".." category=".."/>`
 *
 * 执行顺序（§3.5 步骤 5）：①角色校验 ②字段非空校验 ③§5 节流（单角色单日 ≤5 条）
 * ④去重检查（与当前角色 ACTIVE 技能关键词重叠则拒绝，提示改用 skill_edit）
 * ⑤落库 sourceType=AGENT_AUTONOMOUS。
 */
class SkillCreateTool(
    private val repo: SkillRepository,
    private val characterId: () -> Int,
) : AgentTool {
    override val name = "skill_create"
    override val description = "把刚完成的复杂任务方法沉淀成一条可复用技能"
    override val paramKeys = listOf("name", "short_desc", "full_content", "category")

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val charId = characterId()
            if (charId < 0) return@withContext ToolResult(
                name, success = false, content = "", error = "角色未初始化，无法创建技能"
            )
            val skillName = params["name"]?.trim().orEmpty()
            val shortDesc = params["short_desc"]?.trim().orEmpty()
            val fullContent = params["full_content"]?.trim().orEmpty()
            val category = params["category"]?.trim()?.takeIf { it.isNotEmpty() }
            if (skillName.isEmpty() || shortDesc.isEmpty() || fullContent.isEmpty()) {
                return@withContext ToolResult(
                    name, success = false, content = "",
                    error = "name/short_desc/full_content 均不可为空",
                )
            }
            try {
                // ③ §5 节流：单角色单日 Agent 自主新建 ≤5 条
                val startOfToday = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                val todayCount = repo.countAgentCreatedSince(charId, startOfToday)
                if (todayCount >= DAILY_CREATE_LIMIT) {
                    return@withContext ToolResult(
                        name, success = false, content = "",
                        error = "今日已新建 $todayCount 条技能，达到单角色单日 $DAILY_CREATE_LIMIT 条上限，" +
                            "明日再沉淀或用 skill_edit 修订已有技能",
                    )
                }
                // ④ 去重：与当前角色 ACTIVE 技能做关键词重叠比对（§5）
                val actives = repo.getActiveSkills(charId)
                val dup = findSimilarSkill(skillName, shortDesc, actives)
                if (dup != null) {
                    return@withContext ToolResult(
                        name, success = false, content = "",
                        error = "已有相似技能「${dup.name}」（id=${dup.id}），建议改用 skill_edit 修订而非重复创建",
                    )
                }
                // ⑤ 落库
                val now = System.currentTimeMillis()
                val id = repo.create(
                    skill = SkillEntity(
                        id = UUID.randomUUID().toString(),
                        characterId = charId,
                        name = skillName,
                        shortDescriptor = shortDesc,
                        fullContent = fullContent,
                        category = category,
                        status = SkillStatus.ACTIVE.name,
                        sourceType = SkillSourceType.AGENT_AUTONOMOUS.name,
                        version = 1,
                        createdAt = now,
                        updatedAt = now,
                    ),
                    actor = ACTOR_AGENT,
                )
                ToolResult(
                    name, success = true,
                    content = "已沉淀技能「$skillName」（id=$id）。下次同类任务可直接 skill_expand 复用。",
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                toolFailure(name, "创建技能失败，请稍后重试。", "skill_create_failed", t)
            }
        }

    companion object {
        /** §5 节流阈值：单角色单日 Agent 自主新建技能上限（v1.2 已拍板，写死常量）。 */
        const val DAILY_CREATE_LIMIT = 5

        /**
         * 轻量去重（§5）：命中返回相似技能，否则 null。
         * 规则：① 名称忽略大小写完全相同；② 或一者名称包含另一者（长度≥2，处理"整理旅行行程"
         * vs"整理旅行行程的方法"这类）；③ 或 short_desc 与已有完全相同。不接 LLM 判断，
         * 个人项目数据量小，关键词重叠足够，避免为去重额外消耗一次模型调用。
         */
        internal fun findSimilarSkill(
            newName: String,
            newShort: String,
            existing: List<SkillEntity>,
        ): SkillEntity? {
            val n = newName.lowercase().trim()
            val s = newShort.lowercase().trim()
            return existing.firstOrNull { e ->
                val en = e.name.lowercase().trim()
                val es = e.shortDescriptor.lowercase().trim()
                n == en ||
                    (n.length >= 2 && en.length >= 2 && (n.contains(en) || en.contains(n))) ||
                    (s.isNotEmpty() && s == es)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  skill_edit —— 修订已存在技能的描述或步骤，写入变更日志
// ─────────────────────────────────────────────────────────────

class SkillEditTool(
    private val repo: SkillRepository,
    private val characterId: () -> Int,
) : AgentTool {
    override val name = "skill_edit"
    override val description = "修订一条已存在技能的描述或步骤，写入变更日志"
    override val paramKeys = listOf("skill_id", "full_content", "reason")

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val charId = characterId()
            if (charId < 0) return@withContext ToolResult(
                name, success = false, content = "", error = "角色未初始化"
            )
            val skillId = params["skill_id"]?.trim().orEmpty()
            val fullContent = params["full_content"]?.trim().orEmpty()
            val reason = params["reason"]?.trim().orEmpty()
            if (skillId.isEmpty() || fullContent.isEmpty()) {
                return@withContext ToolResult(
                    name, success = false, content = "", error = "skill_id/full_content 不可为空"
                )
            }
            try {
                // 越权校验：只能编辑当前角色自己的技能
                val existing = repo.getById(skillId)
                if (existing == null || existing.characterId != charId) {
                    return@withContext ToolResult(
                        name, success = false, content = "", error = "技能不存在或不属于当前角色"
                    )
                }
                val ok = repo.edit(skillId, fullContent, reason.ifEmpty { "Agent 主动修订" }, ACTOR_AGENT)
                ToolResult(
                    name, success = ok,
                    content = if (ok) "已修订技能「${existing.name}」并写入变更日志" else "",
                    error = if (ok) null else "修订失败",
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                toolFailure(name, "编辑技能失败，请稍后重试。", "skill_edit_failed", t)
            }
        }
}

// ─────────────────────────────────────────────────────────────
//  skill_deprecate —— 标记长期无效或被替代的技能为废弃，不物理删除
// ─────────────────────────────────────────────────────────────

class SkillDeprecateTool(
    private val repo: SkillRepository,
    private val characterId: () -> Int,
) : AgentTool {
    override val name = "skill_deprecate"
    override val description = "把长期无效或被替代的技能标记为废弃，不物理删除"
    override val paramKeys = listOf("skill_id", "reason")

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val charId = characterId()
            if (charId < 0) return@withContext ToolResult(
                name, success = false, content = "", error = "角色未初始化"
            )
            val skillId = params["skill_id"]?.trim().orEmpty()
            val reason = params["reason"]?.trim().orEmpty()
            if (skillId.isEmpty()) {
                return@withContext ToolResult(
                    name, success = false, content = "", error = "skill_id 不可为空"
                )
            }
            try {
                val existing = repo.getById(skillId)
                if (existing == null || existing.characterId != charId) {
                    return@withContext ToolResult(
                        name, success = false, content = "", error = "技能不存在或不属于当前角色"
                    )
                }
                val ok = repo.deprecate(skillId, reason.ifEmpty { "Agent 判断长期无效/被替代" }, ACTOR_AGENT)
                ToolResult(
                    name, success = ok,
                    content = if (ok) "已废弃技能「${existing.name}」（保留记录，可恢复）" else "",
                    error = if (ok) null else "废弃失败",
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                toolFailure(name, "废弃技能失败，请稍后重试。", "skill_deprecate_failed", t)
            }
        }
}

// ─────────────────────────────────────────────────────────────
//  skill_expand —— 按需读取某条技能的完整方法内容，仅本轮注入不常驻
// ─────────────────────────────────────────────────────────────

/**
 * §3 第二级"按需展开" / §3.5 步骤 3。读 fullContent 一次性注入当前上下文，同时
 * `usageCount+1`、`lastUsedAt` 更新（§3.5 步骤 3）。结果由 `ToolCallInterceptor` 现有
 * 机制自动回注对话（§3.5 步骤 4），技能系统不另起注入通道。
 */
class SkillExpandTool(
    private val repo: SkillRepository,
    private val characterId: () -> Int,
) : AgentTool {
    override val name = "skill_expand"
    override val description = "按需读取某条技能的完整方法内容，仅本轮注入不常驻"
    override val paramKeys = listOf("skill_id")

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val charId = characterId()
            if (charId < 0) return@withContext ToolResult(
                name, success = false, content = "", error = "角色未初始化"
            )
            val skillId = params["skill_id"]?.trim().orEmpty()
            if (skillId.isEmpty()) {
                return@withContext ToolResult(
                    name, success = false, content = "", error = "skill_id 不可为空"
                )
            }
            try {
                val existing = repo.getById(skillId)
                if (existing == null || existing.characterId != charId) {
                    return@withContext ToolResult(
                        name, success = false, content = "", error = "技能不存在或不属于当前角色"
                    )
                }
                repo.recordUsage(skillId) // §3.5 步骤 3：计数器 +1、lastUsedAt 刷新
                // #47 修复：数据库层 incrementUsage 走的是原子 SQL
                // "UPDATE ... SET usageCount = usageCount + 1"，计数本身没问题；
                // 但此前展示用的是调用前读到的 existing.usageCount 现算 +1，
                // 并发场景下（同一技能被多次几乎同时展开）这个现算值可能比
                // 数据库里的实际值小，是纯展示滞后。改为 recordUsage 落库后
                // 重新查一次最新记录，展示真实计数，不再自己推算。
                val updated = repo.getById(skillId) ?: existing
                ToolResult(
                    name, success = true,
                    content = "【技能：${existing.name}】（v${existing.version}，已用 ${updated.usageCount} 次）\n${existing.fullContent}",
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                toolFailure(name, "展开技能失败，请稍后重试。", "skill_expand_failed", t)
            }
        }
}

// ─────────────────────────────────────────────────────────────
//  skill_feedback —— 对刚应用过的技能显式打点：是否达到预期效果
// ─────────────────────────────────────────────────────────────

/**
 * §3.5 步骤 6（v1.1 补上）：闭合"谁来判定 successCount/failureCount"这个 v1.0 缺口。
 * 不强制（§10 决策4），Agent 觉得效果明显时顺手记一笔；不记则计数器允许长期为 0。
 */
class SkillFeedbackTool(
    private val repo: SkillRepository,
    private val characterId: () -> Int,
) : AgentTool {
    override val name = "skill_feedback"
    override val description = "对刚应用过的技能显式打点：这次用下来是否达到预期效果"
    override val paramKeys = listOf("skill_id", "outcome") // outcome: "success" | "failure"
    override val usageNotes = "outcome 只接受 success 或 failure，不接受中文"

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val charId = characterId()
            if (charId < 0) return@withContext ToolResult(
                name, success = false, content = "", error = "角色未初始化"
            )
            val skillId = params["skill_id"]?.trim().orEmpty()
            val outcome = params["outcome"]?.trim()?.lowercase().orEmpty()
            if (skillId.isEmpty() || outcome !in setOf("success", "failure")) {
                return@withContext ToolResult(
                    name, success = false, content = "",
                    error = "skill_id 不可为空，outcome 必须为 success 或 failure",
                )
            }
            try {
                val existing = repo.getById(skillId)
                if (existing == null || existing.characterId != charId) {
                    return@withContext ToolResult(
                        name, success = false, content = "", error = "技能不存在或不属于当前角色"
                    )
                }
                repo.recordFeedback(skillId, success = outcome == "success")
                ToolResult(
                    name, success = true,
                    content = "已记录技能「${existing.name}」本次使用反馈：$outcome",
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                toolFailure(name, "技能反馈记录失败，请稍后重试。", "skill_feedback_failed", t)
            }
        }
}

// ─────────────────────────────────────────────────────────────
//  SkillRegistry —— 目录生成（第一级加载，§3）
// ─────────────────────────────────────────────────────────────

/**
 * §3 第一级"目录注入"。范式对齐 [AgentToolRegistry.buildToolDescriptionBlock]
 * （`AgentTool.kt:227`）：只放 shortDescriptor 列表 + 触发提示，控制 token 成本；
 * Agent 判断某条适用时用 skill_expand 按需展开 fullContent（第二级）。
 *
 * 规模预案（§3）：≤300 条全量注入；>300 条按 category 分级目录；再大才接 Window A 的
 * L3 语义检索（Window A 当前未实现 L3，本系统暂用全量，符合 §3 判断线）。
 */
object SkillRegistry {
    /**
     * 生成某角色 ACTIVE 技能的目录块。无技能时返回空串（调用方据此跳过 Skill Layer 注入）。
     * @return 目录文本，可能为空
     */
    suspend fun buildSkillCatalogBlock(characterId: Int, repo: SkillRepository): String {
        if (characterId < 0) return ""
        // P2 修复：包裹 try-catch，repo.getActiveSkills() 抛异常时不再让整个消息流崩溃，
        // 降级为跳过技能层注入（返回空串），与"无技能时返回空串"的调用方契约一致。
        return try {
            val skills = repo.getActiveSkills(characterId)
            if (skills.isEmpty()) return ""
            val lines = skills.joinToString("\n") { s ->
                "- ${s.name}（${s.shortDescriptor}）  id=${s.id}"
            }
            buildString {
                appendLine("[可用技能]")
                appendLine("需要某条技能的完整方法时，用 <tool:skill_expand skill_id=\"xxx\"/> 展开读取。")
                appendLine("完成一个复杂任务后，若这个做法值得下次复用，用 <tool:skill_create .../> 沉淀成技能。")
                appendLine()
                append(lines)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w("SkillRegistry", "构建技能目录块失败，跳过技能层注入: ${e.message}")
            ""
        }
    }
}

/** 变更日志 actor 常量：Agent 侧统一用 "AGENT"，UI 侧用 "USER"（§1.2）。 */
internal const val ACTOR_AGENT = "AGENT"
