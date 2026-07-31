package com.zaijian.zhoumuyun.domain

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext

/**
 * 机制一·身份判定 IdentityGuard（方案 v1.5 第一节 → 清理后）
 *
 * ⚠️ 清理记录（方案_角色间关系头衔系统_实施方案 六/七节）：
 * 原本的正则+启发式"自称异常/称呼异常"判定（detectSelfClaimAnomaly /
 * detectAddressAnomaly / detectIdentityAnomaly / detectSpeakerContext，以及
 * SELF_CLAIM_REGEX / IDENTITY_TOKEN_FUNCTIONAL_CHARS / isPlausibleIdentityToken /
 * OwnerIdentityProfile）已整体删除——这套"靠猜语气/称呼判断眼前是不是主人"的
 * 机制，功能上被头衔系统接入点2的 ImpersonationDetector 取代：新逻辑只做精确
 * 字符串匹配"我不是主人，我是XX"，XX 命中 impersonation_presets 名单才算数，
 * 不做模糊匹配/语气推断，判定更明确、误报更少。
 *
 * 自称异常和称呼异常是同一套防冒充体系下的两个检测点，废弃方向一致，两者
 * 一起删除（不保留半套），调用方（ChatMessageOrchestrator.kt 440-466行、
 * AgentCoreTools.kt:217）已改为读 ImpersonationDetector 产出的
 * impersonationByCharacter 状态位。
 *
 * 本文件保留的是 SpeakerContext 协程局部状态基础设施（enum/Element/
 * currentSpeakerContext/withSpeakerContext）——这套不是"防冒充判定逻辑"本身，
 * 而是"判定结果的传递通道"，被 AgentCoreTools、SoulMemoryUserTools、
 * PrivateChatEngine、PromptOrchestrator、RelationshipEngine 等多处广泛依赖
 * （工具执行时用于记忆隔离判断），删除会牵连这些无关模块，不在本次清理范围内。
 * ChatMessageOrchestrator 现在直接依据 ImpersonationDetector 的判定结果构造
 * SpeakerContext 并 withSpeakerContext(...) 传下去，而不是依赖本文件曾经的
 * detectSpeakerContext()。
 */

/**
 * speakerContext 标记（原机制一产出，贯穿机制二三四五）。
 * - OWNER_DIRECT：owner 本人直接对话，不注入叙事主权指令，保持原有自由互动逻辑
 * - NON_OWNER：非 owner（owner 扮演的第三方 / 角色间私聊），触发机制三叙事主权 + 机制四状态隔离
 *
 * ⚠️ PromptOrchestrator.buildSystemPrompt() speakerContext 参数的默认值
 *    必须为 OWNER_DIRECT——圆桌（RoundtableBotReplyGenerator/RoundtableIdleManager）、
 *    后台工单（AgentTaskJobExecutor）等未适配的调用方不显式传参，吃默认值，
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
 * ChatMessageOrchestrator.sendMessage() 组装 speakerContext 后，
 * withVaultContext 包裹的作用域内会跑 ToolCallInterceptor.streamWithTools，
 * 里面才是 memory_write/soul_update/narrative_memory_update/user_impression_update
 * 等工具真正 execute() 的地方。这些工具需要知道 speakerContext 是什么，
 * 避免把"owner 冒充角色 B 撩角色 A"这轮对话产生的记忆当成"owner 与本角色的正常
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
 * [SpeakerContext.OWNER_DIRECT]——未显式标注身份的调用方，视为正常对话，
 * 不被误伤。
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
