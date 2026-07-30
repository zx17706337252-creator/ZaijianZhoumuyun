package com.zaijian.zhoumuyun.domain

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext

/**
 * 机制一·身份判定 IdentityGuard（方案 v1.5 第一节）
 *
 * 依据消息文本里的两类显性信号判定本轮 speakerContext，不猜语气：
 *   1. 自称异常：对方在消息里明确指认自己的身份（"我是XX""我不是XX""其实我叫XX"），
 *      且指认结果不在该角色认可的 owner 身份特征集内
 *   2. 称呼异常：对方称呼角色的方式明显偏离该角色对 owner 的固定称呼习惯
 *
 * 两级检测：
 *   - 第一级：纯正则规则匹配，每条消息必跑，零额外调用成本
 *   - 第二级：轻量分类调用，仅第一级未命中时触发，处理间接暗示
 *
 * 会话级状态位 sessionDefenseMode（由调用方持有，见 ChatUiState）：一旦任一级检测命中，
 * 置 true 并在整个会话生命周期内保持，不因后续几句话"表现正常"而自动解除。
 *
 * 接入点：ChatMessageOrchestrator.sendMessage() 组装 Prompt 之前跑，产出
 * speakerContext: OWNER_DIRECT | NON_OWNER，传给 PromptOrchestrator.buildSystemPrompt()。
 */
object IdentityGuard {

    /**
     * 第一级·自称异常正则（方案 1.3 节，验收后修复）。
     * 匹配 "我(其实|真的)?(不是|是|叫) <候选身份词>"，提取候选身份词。
     * "不是" 排在 "是" 之前，避免 "不是" 被截断成 "是"。
     * 候选身份词最长 10 个字符，遇中文标点/空白/句末符号即终止。
     *
     * Fix-验收后-误报：原正则对"我是不是傻""我是说真的""我不是故意的"等日常口语
     * 全部误判命中。这类句式紧跟的候选词几乎都含常见虚词/代词字符，不是身份词。
     * 不在正则本身加否定断言（正则无法做语义判断，硬排除只会遗漏变体），而是在
     * 候选词层面用 [isPlausibleIdentityToken] 做形态过滤，两层配合减少误报，
     * 同时不引入需要真实 LLM 的语义判断（保持第一级零成本）。
     */
    private val SELF_CLAIM_REGEX = Regex(
        """我(?:其实|真的)?(?:不是|是|叫|叫做)\s*([^，。！？!?,.\s]{1,10})"""
    )

    /**
     * Fix-验收后-误报：候选词形态过滤白名单式启发。
     * 身份词/称呼词的共同特征：短（2-6字最常见）、不含常见虚词/代词/助词/否定词。
     * 命中下列任一条件即判定"不像身份词/称呼词"，候选词作废（不计入异常判定）。
     *
     * 覆盖到的误报模式（实测）：
     *   "我是不是傻"     → 候选"不是傻"含"不/是"
     *   "我是说真的"     → 候选"说真的"含"说/真"
     *   "我不是故意的"   → 候选"故意的"含"的"
     *   "我叫你别闹了"   → 候选"你别闹了"含"你/别/了"
     *   "我是想问问你"   → 候选"想问问你"含"想/你"
     *   "叫我起床"       → 候选"起床"含"起/床"
     *   "你该叫我出去玩的" → 候选"出去玩的"含"去/玩/的"
     */
    private val IDENTITY_TOKEN_FUNCTIONAL_CHARS = setOf(
        '不', '是', '说', '想', '就', '也', '还', '你', '我', '他', '她', '它',
        '的', '了', '吗', '呢', '啊', '别', '真', '故', '意', '要', '会', '能',
        '该', '应', '问', '闹', '去', '来', '玩', '起', '床', '睡', '吃', '喝',
        '干', '做', '看', '听', '走', '跑', '哦', '呀', '嘛', '啦', '哈', '并',
    )

    private fun isPlausibleIdentityToken(candidate: String): Boolean {
        if (candidate.length < 2 || candidate.length > 6) return false
        if (candidate.any { it in IDENTITY_TOKEN_FUNCTIONAL_CHARS }) return false
        return true
    }

    /**
     * 判定一条消息是否触发身份异常（第一级规则匹配 + 第二级轻量分类）。
     *
     * @param message 当前用户消息文本
     * @param profile owner 身份特征（合法自称 + 角色对 owner 的固定称呼）
     * @param level2Classifier 第二级轻量分类，仅第一级未命中时触发。
     *        入参为消息文本，返回 true 表示"说话人在暗示自己不是 owner 本人"。
     *        传 null 表示不启用第二级（纯规则模式，零 LLM 成本）。
     * @return true 表示命中身份异常（本轮及后续会话内 speakerContext 应为 NON_OWNER）
     */
    suspend fun detectIdentityAnomaly(
        message: String,
        profile: OwnerIdentityProfile,
        level2Classifier: (suspend (String) -> Boolean)? = null,
    ): Boolean {
        // ── 第一级：规则匹配（零成本）──────────────────────────
        if (detectSelfClaimAnomaly(message, profile)) return true
        if (detectAddressAnomaly(message, profile)) return true

        // ── 第二级：轻量分类（仅第一级未命中时触发）──────────────
        if (level2Classifier != null) {
            return level2Classifier(message)
        }
        return false
    }

    /**
     * 第一级·自称异常：提取候选身份词，与 ownerAliases 做字符串比对。
     * 若候选词存在且不在 ownerAliases 中 → 命中"自称异常"。
     *
     * 边界处理：ownerAliases 为空时不凭此条判定（没有合法自称集，无法判定异常），
     * 避免把"我是张三"误判为异常——只有当 owner 的合法自称集已知且候选词不在其中时，
     * "声称自己是另一个人"才有意义。
     */
    fun detectSelfClaimAnomaly(message: String, profile: OwnerIdentityProfile): Boolean {
        if (profile.ownerAliases.isEmpty()) return false
        val matches = SELF_CLAIM_REGEX.findAll(message)
        for (m in matches) {
            val candidate = (m.groupValues.getOrNull(1) ?: "").trim()
            if (candidate.isEmpty()) continue
            // Fix-验收后-误报：候选词形态上不像身份词（含虚词/代词/过长过短）→
            // 判定是日常口语句式（"我是不是傻""我是说真的"之类），跳过，不计入异常。
            if (!isPlausibleIdentityToken(candidate)) continue
            // 候选身份词不在 owner 合法自称集中 → 声称自己是另一个人
            if (profile.ownerAliases.none { it.equals(candidate, ignoreCase = true) }) {
                return true
            }
        }
        return false
    }

    /**
     * 第一级·称呼异常：对方对角色的称呼明显偏离该角色对 owner 的固定称呼习惯。
     *
     * 设计取舍（与样例 C 的边界一致）：只有当对方明确使用"主人/老板"这类 owner 专属
     * 称呼去称呼角色、或要求角色改口时才判定——这是"自称是 owner 却用错称呼"的强信号。
     * 模糊的、不暴露身份的称呼模仿（样例 C）不属于本检测覆盖范围，符合方案零节
     * "全程角色不知道自己被锁定"的边界声明。
     *
     * 当前实现：若对方消息里出现"叫我主人/我是你主人/你该叫我X"等要求角色改口的
     * 句式，且 X 不在 characterCallsOwner 允许范围 → 命中称呼异常。
     */
    fun detectAddressAnomaly(message: String, profile: OwnerIdentityProfile): Boolean {
        if (profile.characterCallsOwner.isEmpty()) return false
        // "叫我XX" / "叫我主人" / "你应该叫我XX" 等要求角色改口的句式
        val callMeRegex = Regex("""(?:叫我|该叫我|应该叫我|要叫我)\s*([^，。！？!?,.\s]{1,10})""")
        for (m in callMeRegex.findAll(message)) {
            val demanded = (m.groupValues.getOrNull(1) ?: "").trim()
            if (demanded.isEmpty()) continue
            // Fix-验收后-误报：候选词形态上不像称呼词 → 判定是"叫我起床""叫我出去
            // 玩"这类日常请求角色做事的句式，跳过，不计入异常。
            if (!isPlausibleIdentityToken(demanded)) continue
            // 要求角色改用的称呼不在角色对 owner 的固定称呼范围内 → 异常
            if (profile.characterCallsOwner.none { it.equals(demanded, ignoreCase = true) }) {
                return true
            }
        }
        return false
    }

    /**
     * 便捷入口：综合 sessionDefenseMode 状态判定本轮 speakerContext。
     *
     * 一旦会话内已命中过异常（sessionDefenseMode=true），后续本轮固定为 NON_OWNER，
     * 不再逐条重新判断（方案 1.4 节：避免被中途洗白）。
     */
    suspend fun detectSpeakerContext(
        message: String,
        profile: OwnerIdentityProfile,
        sessionDefenseMode: Boolean,
        level2Classifier: (suspend (String) -> Boolean)? = null,
    ): SpeakerContext {
        if (sessionDefenseMode) return SpeakerContext.NON_OWNER
        val anomaly = detectIdentityAnomaly(message, profile, level2Classifier)
        return if (anomaly) SpeakerContext.NON_OWNER else SpeakerContext.OWNER_DIRECT
    }
}

/**
 * speakerContext 标记（机制一产出，贯穿机制二三四五）。
 * - OWNER_DIRECT：owner 本人直接对话，不注入叙事主权指令，保持原有自由互动逻辑
 * - NON_OWNER：非 owner（owner 扮演的第三方 / 角色间私聊），触发机制三叙事主权 + 机制四状态隔离
 *
 * ⚠️ PromptOrchestrator.buildSystemPrompt() 新增 speakerContext 参数的默认值
 *    必须为 OWNER_DIRECT——圆桌（RoundtableBotReplyGenerator/RoundtableIdleManager）、
 *    后台工单（AgentTaskJobExecutor）三处未适配的调用方不显式传参，吃默认值，
 *    行为与改造前完全一致，不被误伤（方案 6.1.1 节）。
 */
enum class SpeakerContext {
    OWNER_DIRECT,
    NON_OWNER,
    ;

    val isNonOwner: Boolean get() = this == NON_OWNER
}

// ─────────────────────────────────────────────────────────────
//  协程局部 speakerContext（场景一记忆隔离修复，照抄 VaultIo.kt 的
//  VaultCallContextElement / withVaultContext / currentVaultContext 模式）
// ─────────────────────────────────────────────────────────────

/**
 * [CoroutineContext.Element]：把本轮 [SpeakerContext] 判定结果绑定到当前协程。
 *
 * 背景（场景一：owner 在单聊窗口里扮演角色 B 撩角色 A）：
 * ChatMessageOrchestrator.sendMessage() 446 行算出 speakerContext 后，
 * 531 行 withVaultContext 包裹的作用域内会跑 ToolCallInterceptor.streamWithTools，
 * 里面才是 memory_write/soul_update/narrative_memory_update/user_impression_update
 * 等工具真正 execute() 的地方。这些工具此前完全不知道 speakerContext 是什么，
 * 会把"owner 冒充角色 B 撩角色 A"这轮对话产生的记忆当成"owner 与本角色的正常
 * 互动"一样写入——数据一旦写脏了不好清理，所以优先堵这里。
 *
 * 复用 [com.zaijian.zhoumuyun.data.agent.VaultCallContextElement] 已验证过的
 * 并发安全模式：协程局部而非进程级 AtomicReference，两条并发的
 * streamWithTools 调用链路（例如私聊 + 圆桌同时进行）各自持有独立的
 * speakerContext，不会互相覆盖。
 *
 * 用法：`withSpeakerContext(speakerContext) { withVaultContext(...) { streamWithTools(...) } }`
 * （与 withVaultContext 嵌套顺序不敏感，两者是相互独立的协程上下文 Element）。
 */
class SpeakerContextElement(
    val context: SpeakerContext,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SpeakerContextElement>
}

/**
 * 读取当前协程的 speakerContext。
 *
 * 找不到 Element 时（圆桌 RoundtableBotReplyGenerator/RoundtableIdleManager、
 * 后台工单 AgentTaskJobExecutor、以及任何未适配的旧调用链路）回退到
 * [SpeakerContext.OWNER_DIRECT]——与 IdentityGuard.kt 174 行注释里
 * "PromptOrchestrator.buildSystemPrompt() 新增参数默认值必须为 OWNER_DIRECT"
 * 是同一条向后兼容约定：未显式标注身份的调用方，视为正常对话，不被误伤。
 */
suspend fun currentSpeakerContext(): SpeakerContext {
    coroutineContext[SpeakerContextElement]?.let { return it.context }
    return SpeakerContext.OWNER_DIRECT
}

/**
 * 在 [block] 执行期间把 [ctx] 绑定到当前协程的 [CoroutineContext]。
 *
 * block 内（含其子协程，即 ToolCallInterceptor.streamWithTools 内跑的所有
 * 工具 execute()）通过 [currentSpeakerContext] 读到的都是 [ctx]。
 */
suspend fun <T> withSpeakerContext(ctx: SpeakerContext, block: suspend () -> T): T =
    withContext(SpeakerContextElement(ctx)) { block() }

/**
 * owner 身份特征（机制一 1.2 节数据结构）。
 * 持久化在 CharacterIdentityEntity.ownerAliasesJson / characterCallsOwnerJson（JSON 数组字符串）。
 *
 * @param ownerAliases owner 的合法自称，如 ["范佩西", "小范"]
 * @param characterCallsOwner 角色对 owner 的固定称呼，如 ["主人", "老板"]
 */
data class OwnerIdentityProfile(
    val ownerAliases: List<String>,
    val characterCallsOwner: List<String>,
) {
    companion object {
        val EMPTY = OwnerIdentityProfile(emptyList(), emptyList())
    }
}
