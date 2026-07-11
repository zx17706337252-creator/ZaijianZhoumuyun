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
