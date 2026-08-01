package com.zaijian.zhoumuyun.data.agent

import android.content.Context
import com.zaijian.zhoumuyun.data.db.entity.PrivateChatPairEntity
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.privatechat.PrivateChatEngine
import com.zaijian.zhoumuyun.data.privatechat.PrivateChatSessionStatus
import com.zaijian.zhoumuyun.data.privatechat.enqueuePrivateChatSession
import com.zaijian.zhoumuyun.data.repository.DaughterCharacterRepository
import com.zaijian.zhoumuyun.data.repository.PrivateChatMessageRepository
import com.zaijian.zhoumuyun.data.repository.PrivateChatPairRepository

/**
 * 主聊天工具接入 · 角色间私聊（ChatScreen 场景）
 *
 * ═══════════════════════════════════════════════════════════════
 * 背景：此前"发起私聊"只有 PrivateChatScreen 一条手动入口（角色对管理面板，
 * 见 PrivateChatViewModel.triggerSession）——PrivateChatWorker.kt 的
 * enqueuePrivateChatSession() 注释原文"触发源只有用户手动发起一种"即指此。
 *
 * 本文件新增第二条触发源：用户在与角色 A 的日常对话（ChatScreen）里用自然语言
 * 下指令（"去找 B 聊聊"），A 通过工具调用识别意图并主动触发。两条入口最终都收口到
 * 同一个 enqueuePrivateChatSession()，不重复实现私聊本身的状态机/风控逻辑。
 *
 * 两个工具：
 *   - private_chat_send    A 主动发起对 B 的私聊（异步，立刻返回确认，不等结果）
 *   - private_chat_history A 查询自己和 B 的私聊逐条原文，供用户追问"你们聊了什么"时
 *                          一字不差引用，而不是凭 relevantMemories 的概括性记忆转述
 *
 * B 一侧的"记得 A 找过自己"不需要新逻辑：PrivateChatEngine.generateReply() 已经会
 * 检索 speaker 关于 listener 的历史记忆（含每次私聊结束后写入的摘要）并注入 prompt，
 * 两条触发入口共用这条链路，天然覆盖。
 * ═══════════════════════════════════════════════════════════════
 */

/**
 * 按角色名反查所有匹配的 characterId（v2.7 新增，用于替代 resolveCharacterIdByName
 * 在重名场景下的静默"取第一个"行为）。与项目里各处"按 id 查 name"的两层硬编码查找
 * （见 ProactiveMessageNotifier.resolveCharacterName 等）反向对称：
 * 先查 DefaultCharacters（精确名字，忽略大小写），再查女儿角色表，两边命中都收集，
 * 不在中途短路返回——调用方需要知道"到底有几个人叫这个名字"才能判断是否存在歧义。
 * 空列表 = 查无此人；单元素 = 唯一匹配；多元素 = 需要调用方自行处理歧义
 * （提示用户提供更具体信息，而不是悄悄挑一个）。
 */
suspend fun findCharacterIdsByName(
    name: String,
    daughterRepo: DaughterCharacterRepository,
): List<Int> {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return emptyList()
    val result = mutableListOf<Int>()
    DefaultCharacters.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }?.let { result.add(it.id) }
    val daughterIds = try {
        daughterRepo.getAllDaughterCharacterIds()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        return result
    }
    // 逐个 id 单独 try/catch：getCharacterConfig 对数据损坏的女儿会主动抛
    // DaughterDataException（按项目既定原则，不在 Repository 层吞掉）。这里的
    // 场景是"扫描找名字"而非"进入某个女儿的对话"，不应该让一条损坏记录
    // 拖累其余女儿角色都查不到——单条失败跳过，继续扫描其余 id。
    for (id in daughterIds) {
        val config = try {
            daughterRepo.getCharacterConfig(id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            continue
        }
        if (config != null && config.name.equals(trimmed, ignoreCase = true)) {
            result.add(config.id)
        }
    }
    return result
}

/**
 * 按角色名反查 characterId（保留：仍是公开顶层函数，供不关心歧义、只要拿个
 * 大概结果的场景使用）。v2.7 起内部委托给 findCharacterIdsByName 实现——
 * 发现多个同名候选时记录警告日志并仍返回第一个（保持向后兼容行为）。
 * 真正会触发私聊动作的调用点（PrivateChatSendTool/PrivateChatHistoryTool）
 * 已改用 findCharacterIdsByName 自行做严格的歧义把关，不再依赖这里的
 * "静默取第一个"。找不到返回 null——不静默兜底成任何默认 ID。
 */
suspend fun resolveCharacterIdByName(
    name: String,
    daughterRepo: DaughterCharacterRepository,
): Int? {
    val matches = findCharacterIdsByName(name, daughterRepo)
    if (matches.size > 1) {
        com.zaijian.zhoumuyun.util.ZLog.w(
            "PrivateChatAgentTools",
            "resolveCharacterIdByName: 名字「$name」匹配到 ${matches.size} 个角色（${matches}），" +
                "静默返回第一个——调用方如需严格处理歧义，请改用 findCharacterIdsByName",
        )
    }
    return matches.firstOrNull()
}

/**
 * 按 characterId 查角色名，两层硬编码查找，与 PrivateChatWorker.resolveCharacterName 同款写法。
 * （该函数在多个文件里各自私有实现，未收敛为共享工具——沿用项目现状写法，不引入新抽象层。）
 */
private suspend fun resolveCharacterNameLocal(
    characterId: Int,
    daughterRepo: DaughterCharacterRepository,
): String {
    DefaultCharacters.firstOrNull { it.id == characterId }?.let { return it.name }
    return try {
        daughterRepo.getCharacterConfig(characterId)?.name ?: "角色$characterId"
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        "角色$characterId"
    }
}

// ─────────────────────────────────────────────────────────────
//  private_chat_send
// ─────────────────────────────────────────────────────────────

class PrivateChatSendTool(
    private val context: Context,
    private val pairRepo: PrivateChatPairRepository,
    private val daughterRepo: DaughterCharacterRepository,
    private val characterIdProvider: () -> Int,
) : AgentTool {

    override val name = "private_chat_send"
    override val description = "主动去找另一个角色私聊（如用户说\"去攻略/试探/安慰某角色\"时），异步进行，不会立刻返回聊天内容"
    override val usageNotes =
        "target 填对方角色的名字（不是ID，工具内部会自动查找）。directive 用一两句话描述这次私聊的" +
        "意图/要聊的方向（如\"试探一下对方对你的态度\"），会作为开场立场传给私聊引擎。这是后台异步操作，" +
        "execute 成功只代表\"已经出发去找对方\"，不代表对话已经聊完——不要在这一轮编造对方说了什么。" +
        "之后用户问起聊了什么，用 private_chat_history 查询逐字记录再回答。"
    override val paramKeys = listOf("target", "directive")

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val selfId = params["__character_id"]?.toIntOrNull() ?: characterIdProvider()
        if (selfId < 0) {
            return ToolResult(name, false, "", error = "当前会话未绑定角色，无法发起私聊")
        }
        val targetName = params["target"]?.trim()
        if (targetName.isNullOrEmpty()) {
            return ToolResult(name, false, "", error = "target 参数不能为空")
        }
        val directive = params["directive"]?.trim()?.takeIf { it.isNotEmpty() }

        // v2.7 修复：发起私聊是会产生真实动作（真的发消息给某个角色）的关键操作，
        // 重名时不能像 resolveCharacterIdByName 那样悄悄挑第一个——那样可能把
        // 私聊发给了同名的错误角色而完全没有察觉。这里改用 findCharacterIdsByName
        // 自己判断"0个/1个/多个"，多个时直接拒绝并要求更具体的信息。
        val targetMatches = try {
            findCharacterIdsByName(targetName, daughterRepo)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            return toolFailure(name, "查找角色时遇到问题。", "private_chat_resolve_failed", e)
        }
        if (targetMatches.isEmpty()) {
            return ToolResult(name, false, "", error = "找不到名叫「$targetName」的角色")
        }
        if (targetMatches.size > 1) {
            return ToolResult(
                name, false, "",
                error = "有 ${targetMatches.size} 个角色都叫「$targetName」，需要更具体的信息才能确定是哪一个",
            )
        }
        val targetId = targetMatches.first()

        if (targetId == selfId) {
            return ToolResult(name, false, "", error = "不能和自己私聊")
        }

        // 与 PrivateChatViewModel.triggerSession 的预检对齐（A10-3②/A10-4 同款三项预检 +
        // 全局开关），避免"已经去找她聊了"这类确认文案说了但 Worker 随后静默跳过。
        if (PrivateChatEngine.isKillSwitchOn(context)) {
            return ToolResult(name, true, "私聊功能目前被关掉了，暂时去不了。")
        }

        val pairId = PrivateChatPairRepository.generatePairId(selfId, targetId)
        val now = System.currentTimeMillis()

        return try {
            val existing = pairRepo.get(pairId)
            if (existing == null) {
                // 对齐 PrivateChatViewModel.createPair：未建过档的角色对，自动建档并开启，
                // 不要求用户先去 PrivateChatScreen 手动新建——用户在主对话里说"去找她聊"
                // 本身就是明确的开启意图。
                pairRepo.insert(
                    PrivateChatPairEntity(
                        pairId = pairId,
                        characterIdA = minOf(selfId, targetId),
                        characterIdB = maxOf(selfId, targetId),
                        enabled = true,
                        usedTodayResetAt = now,
                    )
                )
            } else if (!existing.enabled) {
                pairRepo.updateEnabled(pairId, true)
            }

            val pair = pairRepo.get(pairId)
                ?: return ToolResult(name, false, "", error = "角色对建档失败")

            if (!PrivateChatPairRepository.isStaleDay(pair.usedTodayResetAt, now)
                && pair.sessionsUsedToday >= pair.maxSessionsPerDay
            ) {
                return ToolResult(name, true, "今天已经找${targetName}聊过好多次了，明天再去吧。")
            }
            if (now - pair.lastSessionAt < pair.cooldownMinutes * 60_000L) {
                return ToolResult(name, true, "刚聊过没多久，还得等一会儿才能再去找${targetName}。")
            }
            if (PrivateChatSessionStatus.fromStored(pair.characterDisconnectState)
                == PrivateChatSessionStatus.DISCONNECTED_BY_CHARACTER
            ) {
                return ToolResult(name, true, "${targetName}最近不太想理人，暂时联系不上。")
            }

            enqueuePrivateChatSession(context, pairId, selfId, directive)

            ToolResult(
                toolName = name,
                success  = true,
                content  = "已经去找${targetName}聊天了，对话在后台进行，稍后可以问我聊了什么。",
                userHint = "正在联系${targetName}…",
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            toolFailure(name, "发起私聊失败，请稍后重试。", "private_chat_send_failed", e)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  private_chat_history
// ─────────────────────────────────────────────────────────────

class PrivateChatHistoryTool(
    private val pairRepo: PrivateChatPairRepository,
    private val messageRepo: PrivateChatMessageRepository,
    private val daughterRepo: DaughterCharacterRepository,
    private val characterIdProvider: () -> Int,
) : AgentTool {

    override val name = "private_chat_history"
    override val description = "查询你和另一个角色私聊的逐条原文记录，用户问\"你们聊了什么\"时按这份原文一字不差引用"
    override val usageNotes =
        "target 填对方角色的名字。返回按时间顺序排列的逐条消息（谁说的+内容）。" +
        "如果私聊还没发生过或还没有消息，会明确告知——此时不要凭空编造聊天内容。" +
        "可选参数 limit 控制返回最近多少条（默认60，最多200）。"
    override val paramKeys = listOf("target", "limit")

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val selfId = params["__character_id"]?.toIntOrNull() ?: characterIdProvider()
        if (selfId < 0) {
            return ToolResult(name, false, "", error = "当前会话未绑定角色")
        }
        val targetName = params["target"]?.trim()
        if (targetName.isNullOrEmpty()) {
            return ToolResult(name, false, "", error = "target 参数不能为空")
        }

        // v2.7 修复：只读查询允许"随便看看"，不需要像发起私聊那样严格阻断，
        // 但重名时至少要告知调用方存在歧义——不能默默返回可能是错的那个人的
        // 聊天记录（那样会让 AI 把别人的私聊内容当成跟目标角色聊的转述给用户）。
        val targetMatches = try {
            findCharacterIdsByName(targetName, daughterRepo)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            return toolFailure(name, "查找角色时遇到问题。", "private_chat_resolve_failed", e)
        }
        if (targetMatches.isEmpty()) {
            return ToolResult(name, false, "", error = "找不到名叫「$targetName」的角色")
        }
        if (targetMatches.size > 1) {
            return ToolResult(
                name, false, "",
                error = "有 ${targetMatches.size} 个角色都叫「$targetName」，需要更具体的信息才能确定查询哪一个",
            )
        }
        val targetId = targetMatches.first()

        val pairId = PrivateChatPairRepository.generatePairId(selfId, targetId)

        return try {
            if (pairRepo.get(pairId) == null) {
                return ToolResult(name, true, "你和${targetName}还没有私聊过。")
            }
            val messages = messageRepo.getAllByPair(pairId)
            if (messages.isEmpty()) {
                return ToolResult(name, true, "你和${targetName}还没有私聊记录。")
            }

            // 限制条数，避免超长记录把上下文撑爆——通常只需要最近这次聊了什么。
            val limit = params["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 60
            val trimmed = if (messages.size > limit) messages.takeLast(limit) else messages
            val omittedCount = messages.size - trimmed.size

            val nameCache = HashMap<Int, String>()
            suspend fun nameOf(id: Int): String =
                nameCache.getOrPut(id) { resolveCharacterNameLocal(id, daughterRepo) }

            val lines = trimmed.map { msg -> "${nameOf(msg.senderCharacterId)}：${msg.content}" }
            val transcript = buildString {
                if (omittedCount > 0) appendLine("（更早的 $omittedCount 条未显示）")
                append(lines.joinToString("\n"))
            }

            ToolResult(toolName = name, success = true, content = transcript)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            toolFailure(name, "读取私聊记录失败，请稍后重试。", "private_chat_history_failed", e)
        }
    }
}
