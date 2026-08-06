package com.zaijian.zhoumuyun.data.agent

import android.content.Context
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.db.entity.PrivateChatPairEntity
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.privatechat.PrivateChatEngine
import com.zaijian.zhoumuyun.data.privatechat.SessionTriggerOutcome
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
 * 下指令（"去找 B 聊聊"），A 通过工具调用识别意图并主动触发。
 *
 * 【实时化重构】private_chat_send 此前 suspend 直接调用 PrivateChatEngine.runSession()
 * 同步跑完整段 A↔B 多轮对话，等它真正结束后才把逐字记录放进 ToolResult 返回——
 * 单次调用内部是多轮 LLM 往返（对话本身 + 施压/越界分类 + 收尾记忆生成），
 * 耗时可达数分钟，工具调用方（ChatScreen 这一轮生成）被迫全程等待，
 * 不满足"拨号即返回"的实时交互要求。
 *
 * 现在 execute() 改为只调用 PrivateChatEngine.triggerSession()（本地校验，
 * 不碰 LLM，毫秒级返回），校验通过后把真正的事件循环（runSession()）交给
 * enqueuePrivateChatSession()/WorkManager 在后台异步执行——execute() 成功
 * 返回只代表"已经联系上对方，对话开始在后台推进"，不代表聊完了、也不知道
 * 聊了什么。PrivateChatViewModel.triggerSession（PrivateChatScreen 管理面板
 * 的手动入口）此前就已经是这条异步路径，现在两条入口共用同一个
 * PrivateChatEngine.triggerSession() 做前置校验，不再各写一份规则。
 *
 * 两个工具：
 *   - private_chat_send    A 主动发起对 B 的私聊，"拨号"即返回，真正的对话
 *                          在后台异步推进，这一轮拿不到聊天内容
 *   - private_chat_history A 查询自己和 B 的私聊逐条原文，供用户事后追问
 *                          "你们聊了什么"时一字不差引用
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
    // 修复（性能优化）：首次遍历时缓存所有成功读取的 config，避免模糊匹配阶段
    // 对同一个 id 再次调用 getCharacterConfig——此前精确匹配遍历一遍、模糊匹配
    // 又遍历一遍，N 个女儿角色就是 2N 次 DAO 调用。
    // 逐个 id 单独 try/catch：getCharacterConfig 对数据损坏的女儿会主动抛
    // DaughterDataException（按项目既定原则，不在 Repository 层吞掉）。这里的
    // 场景是"扫描找名字"而非"进入某个女儿的对话"，不应该让一条损坏记录
    // 拖累其余女儿角色都查不到——单条失败跳过，继续扫描其余 id。
    val cachedConfigs = mutableListOf<Pair<Int, String>>() // (id, name)
    for (id in daughterIds) {
        val config = try {
            daughterRepo.getCharacterConfig(id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            continue
        }
        if (config != null) {
            cachedConfigs.add(config.id to config.name)
            if (config.name.equals(trimmed, ignoreCase = true)) {
                result.add(config.id)
            }
        }
    }
    // 修复：精确匹配未命中时，尝试模糊匹配（包含关系）作为后备。
    // LLM 可能使用角色简称（如"蒂"代替"蒂法"）或全名（如"蒂法·洛克哈特"），
    // 仅靠精确 equals 会静默失败，导致用户被告知"找不到这个人"。
    // 只在精确匹配结果为空时触发，避免与精确匹配结果混合产生歧义。
    // 复用 cachedConfigs，不再重复调用 DAO。
    if (result.isEmpty()) {
        DefaultCharacters.filter {
            it.name.contains(trimmed, ignoreCase = true) || trimmed.contains(it.name, ignoreCase = true)
        }.forEach { result.add(it.id) }
        if (result.isEmpty()) {
            for ((id, daughterName) in cachedConfigs) {
                if (daughterName.contains(trimmed, ignoreCase = true) ||
                    trimmed.contains(daughterName, ignoreCase = true)
                ) {
                    result.add(id)
                }
            }
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
    override val description = "主动去找另一个角色私聊（如用户说\"去攻略/试探/安慰某角色\"时）。" +
        "这是一个\"拨号\"动作——立刻返回，真正的对话在后台由 A、B 双方各自的推理" +
        "逐句进行，不会在这次工具调用里就聊完"
    override val usageNotes =
        "target 填对方角色的名字（不是ID，工具内部会自动查找）。directive 用一两句话描述这次私聊的" +
        "意图/要聊的方向（如\"试探一下对方对你的态度\"），会作为开场立场传给私聊引擎。" +
        "这不是同步操作——execute 成功返回只代表\"已经联系上对方，对话开始在后台推进\"，" +
        "此刻对话还没有发生，你还不知道会聊些什么、对方会怎么回应，不要在这一轮编造" +
        "或预告任何具体对话内容。之后可以继续和用户正常聊天，私聊会在后台独立进行。" +
        "如果执行失败/被跳过，说明这次私聊没有发生，不要说自己已经去联系了。" +
        "等对话真正发生后，用户问起聊了什么，用 private_chat_history 查真实的逐字记录再回答，" +
        "不要凭空回忆或推测。"
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

        // 快速失败：全局开关关闭时，不必先建档/开启配对就能直接拒绝。
        // 与 runSession() 内部 globalKillSwitchOff() 读的是同一个 SharedPreferences，
        // 这里只是提前短路，不是重复实现一套判断标准。
        if (PrivateChatEngine.isKillSwitchOn(context)) {
            return ToolResult(name, false, "", error = "私聊功能目前被关掉了，暂时去不了。")
        }

        val pairId = PrivateChatPairRepository.generatePairId(selfId, targetId)
        val now = System.currentTimeMillis()

        return try {
            // 建档/开启是 runSession() 的前置条件（runSession 只接受已存在的 pair，
            // 不会自己建档），必须在调用引擎前做好——这不是重复业务规则，
            // 而是"配对是否存在"这件事只有这里（工具入口）该负责。
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

            // 实时化重构：不再同步调用 runSession() 等它跑完整段对话——现在只
            // "拨号"（triggerSession() 做本地校验，毫秒级返回），校验通过后把真正
            // 的 A↔B 事件循环交给 enqueuePrivateChatSession()/WorkManager 在后台跑，
            // 与 PrivateChatViewModel.triggerSession（管理面板入口）走同一条异步路径。
            // execute() 成功返回不再代表"聊完了"，只代表"这次发起动作本身成立、
            // 已经联系上对方"——对齐上面 usageNotes 里向 LLM 说明的新语义。
            when (val outcome = AppContainer.instance.privateChatEngine.triggerSession(
                pairId = pairId,
                initiatorCharacterId = selfId,
            )) {
                is SessionTriggerOutcome.Started -> {
                    enqueuePrivateChatSession(context, pairId, selfId, directive)
                    ToolResult(
                        toolName = name,
                        success  = true,
                        content  = "已经联系上${targetName}了，对话正在后台推进，" +
                            "此刻还不知道会聊些什么、对方会怎么回应，不要编造或预告具体内容。" +
                            "之后用户问起聊了什么，用 private_chat_history 查真实的逐字记录再回答。",
                        userHint = "正在联系${targetName}…",
                    )
                }
                is SessionTriggerOutcome.Skipped -> {
                    // 把引擎内部的判断原因翻译成用户能理解的话术，与
                    // PrivateChatWorker.notifySkipped 用的是同一套映射标准，
                    // 避免同一件事在两处出现不同措辞。
                    val friendlyReason = when {
                        outcome.reason.contains("全局开关") -> "私聊功能目前被关掉了，暂时去不了。"
                        outcome.reason.contains("上限") -> "今天已经找${targetName}聊过好多次了，明天再去吧。"
                        outcome.reason.contains("冷却") -> "刚聊过没多久，还得等一会儿才能再去找${targetName}。"
                        outcome.reason.contains("下线") -> "${targetName}最近不太想理人，暂时联系不上。"
                        outcome.reason.contains("未开启") -> "你和${targetName}的私聊还没开启。"
                        outcome.reason.contains("配对不存在") -> "角色对建档失败，请稍后重试。"
                        outcome.reason.contains("发起者不属于该配对") -> "私聊对象不匹配，请稍后重试。"
                        // 修复 #4：此前没有这条映射，"会话进行中"会落到 else 分支直接
                        // 透传内部原因字符串；现在给出与其它跳过原因一致风格的友好文案，
                        // 且这条分支本身能触发，前提是 checkCanStart 已经补上了会话进行中检测。
                        outcome.reason.contains("会话进行中") -> "你和${targetName}已经在聊了，等这次聊完再去找TA吧。"
                        else -> outcome.reason
                    }
                    ToolResult(name, false, "", error = friendlyReason)
                }
            }
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
                // 修复：区分"私聊正在进行中"与"从未发生过"。
                // private_chat_send（ChatScreen 工具调用入口）已改为同步执行，本工具被
                // 调用时那次会话早已跑完，不会撞上这个中间态。但 PrivateChatViewModel
                // .triggerSession（PrivateChatScreen 管理面板的手动入口）仍是异步走
                // enqueuePrivateChatSession + Worker，用户仍可能在那条路径的会话执行期间
                // 于 ChatScreen 追问 A"你们聊了什么"，此时消息列表可能仍为空但私聊正在进行。
                // 直接返回"还没有私聊记录"会让 LLM 告诉用户"我们还没聊过"——与事实矛盾，
                // 因此仍需要这层"in_progress"检测。
                val inProgress = try {
                    AppContainer.instance.privateChatSessionRepo.getAllByPair(pairId)
                        .any { it.status == "in_progress" }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    false
                }
                return ToolResult(
                    name, true,
                    if (inProgress) "和${targetName}的私聊正在进行中，还没聊完，稍等一下再问。"
                    else "你和${targetName}还没有私聊记录。"
                )
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
