package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.repository.IdentityRepository
import com.zaijian.zhoumuyun.domain.currentSpeakerContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_SOUL_CHARS = 1000
private const val MAX_MEMORY_CHARS = 1500
private const val MAX_USER_CHARS = 1000

private fun truncateAtSentenceBoundary(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val cut = text.take(maxChars)
    // #51 修复：原先只认中文全角标点和换行，纯英文内容超长时找不到边界，
    // 会退化成硬截断。补上英文句末标点（. ! ?），两套标点都能识别。
    val lastBoundary = cut.lastIndexOfAny(charArrayOf('。', '！', '？', '\n', '.', '!', '?'))
    return if (lastBoundary > maxChars / 2) cut.take(lastBoundary + 1) else cut
}

/**
 * 场景一记忆隔离修复：soul_update / narrative_memory_update / user_impression_update
 * 三个工具写的是 CharacterIdentityEntity 上的单值字段（非逐行记忆表），没有
 * MemoryEntity.isNarrativeOnly 那种"按行打标记，交给读取侧过滤"的空间——
 * 一次 upsert 就是整字段覆盖，尤其 narrativeMemory 本身就是"关系记忆摘要"，
 * owner 冒充角色 B 撩本角色产生的内容一旦覆盖进去，无法只挑出"这部分是冒充"
 * 单独隔离。因此这三个工具选择拦截（返回失败 ToolResult），而非 memory_write
 * 那种"写入但打标记"。
 *
 * 读取 currentSpeakerContext() 而非直接判断 __character_id 是否存在：工作流
 * 后台执行（WorkflowEngine）没有活的协程上下文链路，currentSpeakerContext()
 * 读不到 Element 时按约定回退 OWNER_DIRECT，天然不受此拦截影响，无需额外分支。
 */
private fun blockedForNonOwner(toolName: String, fieldLabel: String): ToolResult = ToolResult(
    toolName = toolName,
    success  = false,
    content  = "",
    error    = "当前对话疑似非 owner 本人（可能是在冒充第三方角色），为避免污染${fieldLabel}，本次未写入。",
)

class SoulUpdateTool(
    private val identityDao: IdentityRepository,
    private val characterId: () -> Int,
) : AgentTool {
    override val name = "soul_update"
    override val description = "更新角色自己的人设备忘录（自我认知），用于角色自我成长演化"
    override val usageNotes = "content 最长 1000 字，超长按句子边界截断并提示"
    override val paramKeys = listOf("content")
    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        // P2 修复：优先读取工作流注入的 __character_id（参照 WorkbenchTaskTools.kt:75），
        // 工作流场景下任务本就绑定角色；前台聊天场景回退到闭包 characterId()。
        val charId = params["__character_id"]?.toIntOrNull() ?: characterId()
        if (charId < 0) return@withContext ToolResult(name, false, "", "角色未初始化")
        val content = params["content"]?.trim() ?: return@withContext ToolResult(name, false, "", "需要 content 参数")
        // 场景一记忆隔离修复：owner 冒充第三方时不写入人设备忘录。
        if (currentSpeakerContext().isNonOwner) return@withContext blockedForNonOwner(name, "人设备忘录")
        val truncated = truncateAtSentenceBoundary(content, MAX_SOUL_CHARS)
        // P2 修复（批次3审查报告问题2）：句子边界截断比 translate 的裸 take 优雅，
        // 但 ToolResult 此前仍只说"已更新"，不提示内容被截断——角色的人设/记忆
        // 静默存了截断版。现在截断发生时显式附加提示。
        val truncateNotice = if (truncated.length < content.length) {
            "（提示：内容超过 $MAX_SOUL_CHARS 字，已截断至最近句子边界，丢弃了 ${content.length - truncated.length} 字）"
        } else ""
        try {
            // P0-2 修复：改用事务化单列 upsert，消除并发 REPLACE 整行覆盖竞态
            identityDao.upsertSoulNote(charId, truncated)
            ToolResult(name, true, "✅ 已更新人设备忘录$truncateNotice", userHint = "正在更新自我认知…")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            toolFailure(name, "更新人设备忘录失败，请稍后重试。", "soul_update_failed", e)
        }
    }
}

class SoulClearTool(
    private val identityDao: IdentityRepository,
    private val characterId: () -> Int,
) : AgentTool {
    override val name = "soul_clear"
    override val description = "清空角色的人设备忘录"
    override val paramKeys = emptyList<String>()
    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        // P2 修复：优先读取工作流注入的 __character_id（参照 WorkbenchTaskTools.kt:75），
        // 工作流场景下任务本就绑定角色；前台聊天场景回退到闭包 characterId()。
        val charId = params["__character_id"]?.toIntOrNull() ?: characterId()
        if (charId < 0) return@withContext ToolResult(name, false, "", "角色未初始化")
        try {
            identityDao.updateSoulNote(charId, "")
            ToolResult(name, true, "✅ 已清空人设备忘录")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            toolFailure(name, "清空人设备忘录失败，请稍后重试。", "soul_clear_failed", e)
        }
    }
}

class NarrativeMemoryUpdateTool(
    private val identityDao: IdentityRepository,
    private val characterId: () -> Int,
) : AgentTool {
    override val name = "narrative_memory_update"
    override val description = "更新角色与用户之间的关系记忆摘要，按阶段记录关系发展"
    override val usageNotes = "大多数值得记住但不需要单独摘出的内容应改写进这里，而不是调用 memory_write 新增条目。用时间标签标注阶段（如\"7月上旬起，持续讨论了XX\"），当前阶段延续时修订/扩写最新一条，出现新话题时追加新条目而不是删除旧的，旧阶段随篇幅需要自行压缩成一两句话。content 最长 1500 字，超长按句子边界截断并提示"
    override val paramKeys = listOf("content")
    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        // P2 修复：优先读取工作流注入的 __character_id（参照 WorkbenchTaskTools.kt:75），
        // 工作流场景下任务本就绑定角色；前台聊天场景回退到闭包 characterId()。
        val charId = params["__character_id"]?.toIntOrNull() ?: characterId()
        if (charId < 0) return@withContext ToolResult(name, false, "", "角色未初始化")
        val content = params["content"]?.trim() ?: return@withContext ToolResult(name, false, "", "需要 content 参数")
        // 场景一记忆隔离修复：owner 冒充第三方时不写入关系记忆摘要——这是本轮
        // 排查中风险最高的一处，narrativeMemory 本身就是"关系发展阶段摘要"，
        // 冒充产生的内容一旦写入，语义上就是把冒充当成真实关系发展记录。
        if (currentSpeakerContext().isNonOwner) return@withContext blockedForNonOwner(name, "关系记忆摘要")
        val truncated = truncateAtSentenceBoundary(content, MAX_MEMORY_CHARS)
        // P2 修复（批次3审查报告问题2）：同 soul_update，截断发生时显式提示。
        val truncateNotice = if (truncated.length < content.length) {
            "（提示：内容超过 $MAX_MEMORY_CHARS 字，已截断至最近句子边界，丢弃了 ${content.length - truncated.length} 字）"
        } else ""
        try {
            // P0-2 修复：改用事务化单列 upsert，消除并发 REPLACE 整行覆盖竞态
            identityDao.upsertNarrativeMemory(charId, truncated)
            ToolResult(name, true, "✅ 已更新关系记忆摘要$truncateNotice", userHint = "正在更新长期记忆…")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            toolFailure(name, "更新关系记忆摘要失败，请稍后重试。", "narrative_memory_update_failed", e)
        }
    }
}

class NarrativeMemoryClearTool(
    private val identityDao: IdentityRepository,
    private val characterId: () -> Int,
) : AgentTool {
    override val name = "narrative_memory_clear"
    override val description = "清空角色与用户之间的关系记忆摘要"
    override val paramKeys = emptyList<String>()
    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        // P2 修复：优先读取工作流注入的 __character_id（参照 WorkbenchTaskTools.kt:75），
        // 工作流场景下任务本就绑定角色；前台聊天场景回退到闭包 characterId()。
        val charId = params["__character_id"]?.toIntOrNull() ?: characterId()
        if (charId < 0) return@withContext ToolResult(name, false, "", "角色未初始化")
        try {
            identityDao.updateNarrativeMemory(charId, "")
            ToolResult(name, true, "✅ 已清空关系记忆摘要")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            toolFailure(name, "清空关系记忆摘要失败，请稍后重试。", "narrative_memory_clear_failed", e)
        }
    }
}

class UserImpressionUpdateTool(
    private val identityDao: IdentityRepository,
    private val characterId: () -> Int,
) : AgentTool {
    override val name = "user_impression_update"
    override val description = "更新角色对用户的印象描述"
    override val usageNotes = "content 最长 1000 字，超长按句子边界截断并提示"
    override val paramKeys = listOf("content")
    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        // P2 修复：优先读取工作流注入的 __character_id（参照 WorkbenchTaskTools.kt:75），
        // 工作流场景下任务本就绑定角色；前台聊天场景回退到闭包 characterId()。
        val charId = params["__character_id"]?.toIntOrNull() ?: characterId()
        if (charId < 0) return@withContext ToolResult(name, false, "", "角色未初始化")
        val content = params["content"]?.trim() ?: return@withContext ToolResult(name, false, "", "需要 content 参数")
        // 场景一记忆隔离修复：owner 冒充第三方时不写入对用户的印象。
        if (currentSpeakerContext().isNonOwner) return@withContext blockedForNonOwner(name, "对用户的印象")
        val truncated = truncateAtSentenceBoundary(content, MAX_USER_CHARS)
        // P2 修复（批次3审查报告问题2）：同 soul_update，截断发生时显式提示。
        val truncateNotice = if (truncated.length < content.length) {
            "（提示：内容超过 $MAX_USER_CHARS 字，已截断至最近句子边界，丢弃了 ${content.length - truncated.length} 字）"
        } else ""
        try {
            // P0-2 修复：改用事务化单列 upsert，消除并发 REPLACE 整行覆盖竞态
            identityDao.upsertUserImpression(charId, truncated)
            ToolResult(name, true, "✅ 已更新对他的印象$truncateNotice", userHint = "正在更新对你的理解…")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            toolFailure(name, "更新印象失败，请稍后重试。", "user_impression_update_failed", e)
        }
    }
}

class UserImpressionClearTool(
    private val identityDao: IdentityRepository,
    private val characterId: () -> Int,
) : AgentTool {
    override val name = "user_impression_clear"
    override val description = "清空角色对用户的印象描述"
    override val paramKeys = emptyList<String>()
    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        // P2 修复：优先读取工作流注入的 __character_id（参照 WorkbenchTaskTools.kt:75），
        // 工作流场景下任务本就绑定角色；前台聊天场景回退到闭包 characterId()。
        val charId = params["__character_id"]?.toIntOrNull() ?: characterId()
        if (charId < 0) return@withContext ToolResult(name, false, "", "角色未初始化")
        try {
            identityDao.updateUserImpression(charId, "")
            ToolResult(name, true, "✅ 已清空对他的印象")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            toolFailure(name, "清空印象失败，请稍后重试。", "user_impression_clear_failed", e)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  模块注册入口
// ─────────────────────────────────────────────────────────────

/**
 * 问题39修复：统一注册入口，取代此前在 ZaijianApp.kt（-1 静态占位）和
 * ChatViewModel.kt（currentCharacterId 动态覆盖）两处各自手写同一份
 * 6 个工具实例化代码的重复写法——两处任何一处新增/改参数都要记得同步改
 * 另一处，容易漏改（`registerAll` 对同名工具"后注册覆盖先注册"，两阶段
 * 注册的顺序依赖仍然保留，本次只收敛"怎么构造这6个工具"这一份重复代码，
 * 不改变调用顺序/时机）。
 *
 * @param characterId 由调用方决定绑定策略：
 *   - ZaijianApp.onCreate() 传 `{ -1 }`（App 启动阶段占位，工具"先存在"）
 *   - ChatViewModel.init() 传 `{ currentCharacterId }`（覆盖为真实会话角色，
 *     否则 updateSoulNote/updateNarrativeMemory/updateUserImpression 全部
 *     打到 characterId=-1 的行，永远改不了实际角色的数据——同 Fix-ToolWire
 *     注释描述的原因，未改变）
 */
fun AgentToolRegistry.registerSoulMemoryUserTools(
    identityDao: IdentityRepository,
    characterId: () -> Int,
) {
    registerAll(
        SoulUpdateTool(identityDao = identityDao, characterId = characterId),
        SoulClearTool(identityDao = identityDao, characterId = characterId),
        NarrativeMemoryUpdateTool(identityDao = identityDao, characterId = characterId),
        NarrativeMemoryClearTool(identityDao = identityDao, characterId = characterId),
        UserImpressionUpdateTool(identityDao = identityDao, characterId = characterId),
        UserImpressionClearTool(identityDao = identityDao, characterId = characterId),
    )
}
