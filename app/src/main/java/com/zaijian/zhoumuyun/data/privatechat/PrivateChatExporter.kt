package com.zaijian.zhoumuyun.data.privatechat

import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.repository.DaughterCharacterRepository
import com.zaijian.zhoumuyun.data.repository.PrivateChatMessageRepository
import com.zaijian.zhoumuyun.data.repository.PrivateChatSessionRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 私聊导出模块（方案_角色间私聊_v2-5 第五节）
 *
 * 构造函数直接依赖 daughterCharacterRepo（与 PrivateChatEngine 各自持有一份即可，
 * 不需要共享同一个列表实例——v2.5 没有 resolver 列表这个概念了）。nameOf() 用
 * 同款两层硬编码查找（DefaultCharacters → daughterCharacterRepo.getCharacterConfig()），
 * 这是本次设计上刻意接受的重复，不要因为看着像重复代码就自己重构成共享函数或抽象接口。
 *
 * v2.3 补充：interrupted 的会话照常导出，但标题上加"（未完成）"标记，
 * 不伪装成正常收尾的完整对话（对应 3.2.1 节的承诺）。
 */
class PrivateChatExporter(
    private val messageRepo: PrivateChatMessageRepository,
    private val sessionRepo: PrivateChatSessionRepository,
    private val daughterCharacterRepo: DaughterCharacterRepository,
) {

    /**
     * 导出为 Markdown 格式：按 session 分组，每个 session 一个标题，
     * 角色名加粗 + 消息内容逐条列出。导出全部历史（不限 sessionId）。
     */
    suspend fun exportPairToMarkdown(pairId: String): String {
        val messages = messageRepo.getAllByPair(pairId)  // 按 timestamp 升序，全历史
        val sessions = sessionRepo.getAllByPair(pairId).associateBy { it.sessionId }
        val nameCache = mutableMapOf<Int, String>()

        val sb = StringBuilder()
        var currentSession: String? = null
        for (msg in messages) {
            if (msg.sessionId != currentSession) {
                currentSession = msg.sessionId
                val sessionStartMsg = messages.first { it.sessionId == currentSession }
                val time = formatTimestamp(sessionStartMsg.timestamp)
                val statusSuffix = if (sessions[currentSession]?.status == "interrupted") "（未完成）" else ""
                sb.appendLine("## 会话（$time）$statusSuffix")
                sb.appendLine()
            }
            sb.appendLine("**${nameOf(msg.senderCharacterId, nameCache)}**：${msg.content}")
            sb.appendLine()
        }
        return sb.toString()
    }

    /**
     * 导出为纯文本格式（5.2 节建议支持的第二种格式）：
     * 更简单的格式，方便快速查看或粘贴到别处。
     */
    suspend fun exportPairToPlainText(pairId: String): String {
        val messages = messageRepo.getAllByPair(pairId)
        val sessions = sessionRepo.getAllByPair(pairId).associateBy { it.sessionId }
        val nameCache = mutableMapOf<Int, String>()

        val sb = StringBuilder()
        var currentSession: String? = null
        for (msg in messages) {
            if (msg.sessionId != currentSession) {
                currentSession = msg.sessionId
                val sessionStartMsg = messages.first { it.sessionId == currentSession }
                val time = formatTimestamp(sessionStartMsg.timestamp)
                val statusSuffix = if (sessions[currentSession]?.status == "interrupted") "（未完成）" else ""
                sb.appendLine("--- 会话（$time）$statusSuffix ---")
                sb.appendLine()
            }
            sb.appendLine("${nameOf(msg.senderCharacterId, nameCache)}：${msg.content}")
            sb.appendLine()
        }
        return sb.toString()
    }

    /**
     * 两层硬编码查找角色名，与 PrivateChatEngine.resolveCharacterConfig() 相同的写法：
     * 先查 DefaultCharacters，查不到再查 daughterCharacterRepo。4.0 节已确认
     * getCharacterConfig() 按 daughterCharacterId 单键反查、不区分第几代，
     * 两层查找对第三代角色同样完整覆盖，不需要额外抽象层统一两处代码。
     *
     * 注意：不能用 nameCache.getOrPut { ... } 包裹 suspend 调用（getOrPut 的 lambda
     * 不是 suspend），改为先查缓存、未命中再 suspend 查询后写回缓存。
     */
    private suspend fun nameOf(id: Int, nameCache: MutableMap<Int, String>): String {
        nameCache[id]?.let { return it }
        val name = DefaultCharacters.firstOrNull { it.id == id }?.name
            ?: daughterCharacterRepo.getCharacterConfig(id)?.name
            ?: "角色$id"
        nameCache[id] = name
        return name
    }

    private fun formatTimestamp(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
        return sdf.format(Date(timestamp))
    }
}
