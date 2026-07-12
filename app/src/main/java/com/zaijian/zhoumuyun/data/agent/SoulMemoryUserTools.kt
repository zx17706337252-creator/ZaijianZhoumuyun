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
    override val paramKeys = listOf("content")
    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val charId = characterId()
        if (charId < 0) return@withContext ToolResult(name, false, "", "角色未初始化")
        val content = params["content"]?.trim() ?: return@withContext ToolResult(name, false, "", "需要 content 参数")
        val truncated = truncateAtSentenceBoundary(content, MAX_SOUL_CHARS)
        try {
            // P0-2 修复：改用事务化单列 upsert，消除并发 REPLACE 整行覆盖竞态
            identityDao.upsertSoulNote(charId, truncated)
            ToolResult(name, true, "✅ 已更新人设备忘录", userHint = "正在更新自我认知…")
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
    override val paramKeys = listOf("content")
    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val charId = characterId()
        if (charId < 0) return@withContext ToolResult(name, false, "", "角色未初始化")
        val content = params["content"]?.trim() ?: return@withContext ToolResult(name, false, "", "需要 content 参数")
        val truncated = truncateAtSentenceBoundary(content, MAX_MEMORY_CHARS)
        try {
            // P0-2 修复：改用事务化单列 upsert，消除并发 REPLACE 整行覆盖竞态
            identityDao.upsertNarrativeMemory(charId, truncated)
            ToolResult(name, true, "✅ 已更新关系记忆摘要", userHint = "正在更新长期记忆…")
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
    override val paramKeys = listOf("content")
    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val charId = characterId()
        if (charId < 0) return@withContext ToolResult(name, false, "", "角色未初始化")
        val content = params["content"]?.trim() ?: return@withContext ToolResult(name, false, "", "需要 content 参数")
        val truncated = truncateAtSentenceBoundary(content, MAX_USER_CHARS)
        try {
            // P0-2 修复：改用事务化单列 upsert，消除并发 REPLACE 整行覆盖竞态
            identityDao.upsertUserImpression(charId, truncated)
            ToolResult(name, true, "✅ 已更新对用户的印象", userHint = "正在更新对你的理解…")
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
