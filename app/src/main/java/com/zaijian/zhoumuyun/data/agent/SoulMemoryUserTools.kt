package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.repository.IdentityRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_SOUL_CHARS = 1000
private const val MAX_MEMORY_CHARS = 1500
private const val MAX_USER_CHARS = 1000

private fun truncateAtSentenceBoundary(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val cut = text.take(maxChars)
    val lastBoundary = cut.lastIndexOfAny(charArrayOf('。', '！', '？', '\n'))
    return if (lastBoundary > maxChars / 2) cut.take(lastBoundary + 1) else cut
}

class SoulUpdateTool(
    private val identityDao: IdentityRepository,
    private val characterId: () -> Int,
) : AgentTool {
    override val name = "soul_update"
    override val description = "更新角色自己的人设备忘录（自我认知），用于角色自我成长演化（content 最长 1000 字，超长按句子边界截断并提示）"
    override val paramKeys = listOf("content")
    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val charId = characterId()
        if (charId < 0) return@withContext ToolResult(name, false, "", "角色未初始化")
        val content = params["content"]?.trim() ?: return@withContext ToolResult(name, false, "", "需要 content 参数")
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
        } catch (e: Exception) {
            ToolResult(name, false, "更新失败：${e.message?.take(80)}", e.message)
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
        val charId = characterId()
        if (charId < 0) return@withContext ToolResult(name, false, "", "角色未初始化")
        try {
            identityDao.updateSoulNote(charId, "")
            ToolResult(name, true, "✅ 已清空人设备忘录")
        } catch (e: Exception) {
            ToolResult(name, false, "清空失败：${e.message?.take(80)}", e.message)
        }
    }
}

class NarrativeMemoryUpdateTool(
    private val identityDao: IdentityRepository,
    private val characterId: () -> Int,
) : AgentTool {
    override val name = "narrative_memory_update"
    override val description = "更新角色与用户之间的关系记忆摘要（长期叙事记忆）（content 最长 1500 字，超长按句子边界截断并提示）"
    override val paramKeys = listOf("content")
    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val charId = characterId()
        if (charId < 0) return@withContext ToolResult(name, false, "", "角色未初始化")
        val content = params["content"]?.trim() ?: return@withContext ToolResult(name, false, "", "需要 content 参数")
        val truncated = truncateAtSentenceBoundary(content, MAX_MEMORY_CHARS)
        // P2 修复（批次3审查报告问题2）：同 soul_update，截断发生时显式提示。
        val truncateNotice = if (truncated.length < content.length) {
            "（提示：内容超过 $MAX_MEMORY_CHARS 字，已截断至最近句子边界，丢弃了 ${content.length - truncated.length} 字）"
        } else ""
        try {
            // P0-2 修复：改用事务化单列 upsert，消除并发 REPLACE 整行覆盖竞态
            identityDao.upsertNarrativeMemory(charId, truncated)
            ToolResult(name, true, "✅ 已更新关系记忆摘要$truncateNotice", userHint = "正在更新长期记忆…")
        } catch (e: Exception) {
            ToolResult(name, false, "更新失败：${e.message?.take(80)}", e.message)
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
        val charId = characterId()
        if (charId < 0) return@withContext ToolResult(name, false, "", "角色未初始化")
        try {
            identityDao.updateNarrativeMemory(charId, "")
            ToolResult(name, true, "✅ 已清空关系记忆摘要")
        } catch (e: Exception) {
            ToolResult(name, false, "清空失败：${e.message?.take(80)}", e.message)
        }
    }
}

class UserImpressionUpdateTool(
    private val identityDao: IdentityRepository,
    private val characterId: () -> Int,
) : AgentTool {
    override val name = "user_impression_update"
    override val description = "更新角色对用户的印象描述（content 最长 1000 字，超长按句子边界截断并提示）"
    override val paramKeys = listOf("content")
    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val charId = characterId()
        if (charId < 0) return@withContext ToolResult(name, false, "", "角色未初始化")
        val content = params["content"]?.trim() ?: return@withContext ToolResult(name, false, "", "需要 content 参数")
        val truncated = truncateAtSentenceBoundary(content, MAX_USER_CHARS)
        // P2 修复（批次3审查报告问题2）：同 soul_update，截断发生时显式提示。
        val truncateNotice = if (truncated.length < content.length) {
            "（提示：内容超过 $MAX_USER_CHARS 字，已截断至最近句子边界，丢弃了 ${content.length - truncated.length} 字）"
        } else ""
        try {
            // P0-2 修复：改用事务化单列 upsert，消除并发 REPLACE 整行覆盖竞态
            identityDao.upsertUserImpression(charId, truncated)
            ToolResult(name, true, "✅ 已更新对用户的印象$truncateNotice", userHint = "正在更新对你的理解…")
        } catch (e: Exception) {
            ToolResult(name, false, "更新失败：${e.message?.take(80)}", e.message)
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
        val charId = characterId()
        if (charId < 0) return@withContext ToolResult(name, false, "", "角色未初始化")
        try {
            identityDao.updateUserImpression(charId, "")
            ToolResult(name, true, "✅ 已清空对用户的印象")
        } catch (e: Exception) {
            ToolResult(name, false, "清空失败：${e.message?.take(80)}", e.message)
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
