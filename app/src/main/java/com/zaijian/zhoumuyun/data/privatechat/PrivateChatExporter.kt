package com.zaijian.zhoumuyun.data.privatechat

import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.repository.DaughterCharacterRepository
import com.zaijian.zhoumuyun.data.repository.PrivateChatMessageRepository
import com.zaijian.zhoumuyun.data.repository.PrivateChatSessionRepository
import com.zaijian.zhoumuyun.util.ZLog
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
 * 不伪装成正常收尾的完整对话（对应 3.2.1 节的承诺）。v2.7 同样补充
 * disconnected（角色主动下线）的标记，见 statusSuffixFor()。
 *
 * 关于 appendRelationshipSection() 与类头"私聊与关系值体系双向隔离"的措辞：
 * "双向隔离"指的是 PrivateChatEngine 运行时不读取/不写入 RelationshipEngine——
 * 私聊内容本身不会被拿去改变信任/好感等数值，数值也不会反过来影响私聊生成
 * （见 PrivateChatEngine 类头 2.1 节说明）。导出时附加的关系值快照是只读展示，
 * 发生在会话结束之后、导出这个单独的动作里，不经过引擎、不影响会话本身，
 * 与"运行时双向隔离"不是同一件事，两者不冲突。
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
                val statusSuffix = statusSuffixFor(sessions[currentSession]?.status)
                sb.appendLine("## 会话（$time）$statusSuffix")
                sb.appendLine()
            }
            sb.appendLine("**${nameOf(msg.senderCharacterId, nameCache)}**：${msg.content}")
            sb.appendLine()
        }

        // A9-5 修复：私聊导出附带双方与 owner 的关系值快照
        appendRelationshipSection(sb, pairId)
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
                val statusSuffix = statusSuffixFor(sessions[currentSession]?.status)
                sb.appendLine("--- 会话（$time）$statusSuffix ---")
                sb.appendLine()
            }
            sb.appendLine("${nameOf(msg.senderCharacterId, nameCache)}：${msg.content}")
            sb.appendLine()
        }

        // A9-5 修复：私聊导出附带双方与 owner 的关系值快照
        appendRelationshipSection(sb, pairId)
        return sb.toString()
    }

    /**
     * A9-5 修复：向导出文本末尾追加私聊双方与 owner 的关系值快照。
     * 从 PrivateChatPairRepository 查出 characterIdA / characterIdB，
     * 再用 RelationshipEngine.getOrCreate("user", cid) 获取各自关系数据。
     * 关系数据获取失败不阻断导出，仅跳过该板块。
     */
    private suspend fun appendRelationshipSection(sb: StringBuilder, pairId: String) {
        runCatching {
            // 从 pairId 解析出两个角色 ID（pairId 格式为 "min_max"）
            val parts = pairId.split("_")
            if (parts.size != 2) return@runCatching
            val cidA = parts[0].toIntOrNull() ?: return@runCatching
            val cidB = parts[1].toIntOrNull() ?: return@runCatching
            val relEngine = AppContainer.instance.relationshipEngine
            val nameCache = mutableMapOf<Int, String>()

            sb.appendLine("---")
            sb.appendLine("## 关系数值快照")
            for (cid in listOf(cidA, cidB)) {
                val name = nameOf(cid, nameCache)
                val rel = runCatching { relEngine.getOrCreate("user", cid.toString()) }.getOrNull()
                if (rel != null) {
                    sb.appendLine("### $name")
                    sb.appendLine("- 信任：${rel.trust}/100")
                    sb.appendLine("- 好感：${rel.affection}/100")
                    sb.appendLine("- 冲突：${rel.conflict}/100")
                    sb.appendLine("- 压抑：${rel.suppression}/100")
                    sb.appendLine()
                }
            }
        }.onFailure { e ->
            ZLog.w("PrivateChatExporter", "追加关系值快照失败，跳过该板块", e)
        }
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

    /**
     * 会话标题后缀，两种导出格式共用（v2.7 抽出，此前两处各自写一份相同的
     * 三段式判断，容易改一处漏一处——正好和"10/12/无限制"三处数字对不上是
     * 同一类风险）。
     *
     * interrupted：系统异常中断，标"（未完成）"，不伪装成完整对话。
     * disconnected：角色主动下线导致会话结束，标"（对方中断）"——同样不是
     * 双方自然聊完的结果，但也不是系统故障，用不同措辞区分这两种情形。
     */
    private fun statusSuffixFor(status: String?): String = when (status) {
        "interrupted" -> "（未完成）"
        "disconnected" -> "（对方中断）"
        else -> ""
    }
}
