package com.zaijian.zhoumuyun.data.prompt

/**
 * P0-4 PR1：Prompt 上下文注入策略（纯新增，不改变现有行为）。
 *
 * 为后续 WARM/COLD 分层改造（PR3~PR6）提供三个基础构件：
 *  - [VersionedPromptLayer]：带版本令牌（versionToken）的 prompt 层，支持变化检测。
 *  - [InjectionDecider]：按版本令牌变化决定「是否注入某层内容」。
 *  - [IntentRouter]：把用户输入路由到意图分类（PR3 接入完整关键词分类器）。
 *
 * 版本令牌（versionToken）约定：一律取 `updatedAt`（Long）或 `hashCode()`（Int），
 * 见 v10 风险点 2 裁定：`identityEntity.updatedAt` / `goal.updatedAt` /
 * `project.updatedAt` / `plan.version` / `presenceSnap.hashCode()`。
 * 本文件所有类型均为纯计算、零外部依赖，可被纯 JVM 单测直接覆盖。
 */

/** 版本令牌：统一包裹 Long 时间戳或 Int 哈希，用不可变相等比较做变化检测。 */
sealed interface VersionToken {
    /** 时间戳令牌（对应 `updatedAt`）。 */
    data class Timestamp(val value: Long) : VersionToken

    /** 哈希令牌（对应 `hashCode()`）。 */
    data class Hash(val value: Int) : VersionToken

    /** 无令牌（首次轮次 / 无上一轮状态时使用）。 */
    data object Unset : VersionToken

    /** 令牌底层值，用于日志/调试。 */
    fun display(): String = when (this) {
        is Timestamp -> "ts:$value"
        is Hash -> "hash:$value"
        Unset -> "unset"
    }
}

/**
 * 一个带版本令牌的 prompt 注入层。
 *
 * @param name 层名（如 "PresenceState"/"Identity"/"Memory"），用于日志与报告。
 * @param content 渲染后的 prompt 块内容；空串表示该层为空（零开销原则）。
 * @param versionToken 本层内容的版本令牌；后续轮次若令牌变化，说明内容可能已更新。
 */
data class VersionedPromptLayer(
    val name: String,
    val content: String,
    val versionToken: VersionToken,
) {
    /** 层内容是否为空（空层不注入，零开销）。 */
    val isEmpty: Boolean get() = content.isEmpty()

    /** 与上一轮令牌比较，判断该层内容是否已变化。 */
    fun isChangedSince(previous: VersionToken): Boolean =
        previous != versionToken
}

/** 注入决策器：根据版本令牌变化决定是否注入。 */
object InjectionDecider {
    /**
     * 决定是否注入某层。
     *
     * @param layer 待注入的层。
     * @param previous 上一轮该层的版本令牌；首次轮次传 null。
     * @return 层内容非空，且（首次 或 与上一轮令牌不同）时注入。
     */
    fun shouldInject(layer: VersionedPromptLayer, previous: VersionToken?): Boolean {
        if (layer.isEmpty) return false
        return previous == null || layer.isChangedSince(previous)
    }
}

/** 意图分类（PR3 将在此接入完整关键词分类器；PR1 仅提供骨架与路由接口）。 */
enum class IntentCategory {
    /** 默认：未命中任何专用分类。 */
    GENERAL,

    /** 关系/情感类意图（PR3 接入关键词表；裁定不含"他"/"她"）。 */
    RELATION,

    /** 孕期/身体状态类意图。 */
    PREGNANCY,

    /** 身份/角色扮演类意图。 */
    IDENTITY,
}

/**
 * 意图路由：把用户输入路由到 [IntentCategory]。
 *
 * PR3 接入关键词分类器（v10 风险点 4 裁定）：
 *  - RELATION 分类关键词表**不含**"他"/"她"（避免误触发过于频繁）；如需覆盖"提到第三方"
 *    语义，改用"和他"/"跟她"/"他们俩"等更具体搭配词。
 *  - 分类按「先最具体」顺序：PREGNANCY → IDENTITY → RELATION → GENERAL。
 *  - 纯字符串匹配、零外部依赖，可被纯 JVM 单测覆盖。
 */
class IntentRouter {

    /** RELATION 关键词表——不含"他"/"她"（v10 风险点 4 裁定）。 */
    private val relationKeywords = listOf(
        "喜欢", "爱你", "讨厌", "想念", "在乎", "吃醋", "心动", "暧昧",
        "分手", "复合", "在一起", "恋人", "伴侣", "老公", "老婆",
        "和他", "跟她", "他们俩", "感情", "亲密",
    )

    /** PREGNANCY 关键词表——孕期/身体状态类。 */
    private val pregnancyKeywords = listOf(
        "怀孕", "宝宝", "产检", "预产期", "孕吐", "胎动", "流产", "保胎", "妊娠",
    )

    /** IDENTITY 关键词表——身份/角色扮演类。 */
    private val identityKeywords = listOf(
        "你是谁", "我是谁", "扮演", "人设", "角色设定", "是不是AI", "是不是机器人", "你真名",
    )

    /**
     * 将用户输入路由到意图分类。
     *
     * @return 命中任一分类关键词返回对应分类；空输入或未命中返回 [IntentCategory.GENERAL]。
     */
    fun route(userInput: String): IntentCategory {
        if (userInput.isBlank()) return IntentCategory.GENERAL
        return when {
            containsAny(userInput, pregnancyKeywords) -> IntentCategory.PREGNANCY
            containsAny(userInput, identityKeywords) -> IntentCategory.IDENTITY
            containsAny(userInput, relationKeywords) -> IntentCategory.RELATION
            else -> IntentCategory.GENERAL
        }
    }

    private fun containsAny(text: String, keywords: List<String>): Boolean =
        keywords.any { text.contains(it) }
}