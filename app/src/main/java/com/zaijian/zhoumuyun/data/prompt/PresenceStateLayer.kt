package com.zaijian.zhoumuyun.data.prompt

/**
 * P0-4 PR2：PresenceStateLayer 试点（风险最低）。
 *
 * 把「当前存在状态」（活动/焦点/心情/能量）封装为带版本令牌的 prompt 层，
 * 接入 [VersionedPromptLayer] + [InjectionDecider] 的变化检测机制：
 *  - 版本令牌取 `data class` 自带的 `hashCode()`（v10 风险点 2 裁定：presenceSnap 用 hashCode）。
 *  - 当存在状态发生变化（hashCode 变化）时，层标记为已变化，[InjectionDecider] 判定应重新注入。
 *
 * 纯计算、零外部依赖，可被纯 JVM 单测覆盖。试点范围仅限本层，不改变既有 State Layer 注入逻辑。
 */
data class PresenceStateLayer(
    val activity: String = "",
    val focus: String = "",
    val mood: String = "",
    val energy: Int = -1,
) {
    /** 版本令牌：data class 自带 hashCode（v10 风险点 2 裁定）。 */
    val versionToken: VersionToken get() = VersionToken.Hash(hashCode())

    /** 层内容是否为空（空层不注入，零开销原则）。 */
    val isEmpty: Boolean
        get() = activity.isBlank() && focus.isBlank() && mood.isBlank() && energy < 0

    /** 渲染为 prompt 块文本；空层返回空串。 */
    fun toPromptBlock(): String {
        if (isEmpty) return ""
        return buildString {
            append("【当前状态】")
            if (activity.isNotBlank()) append("活动：").append(activity).append("；")
            if (focus.isNotBlank()) append("焦点：").append(focus).append("；")
            if (mood.isNotBlank()) append("心情：").append(mood).append("；")
            if (energy >= 0) append("能量：").append(energy)
        }
    }

    /** 包装为版本化 prompt 层，供 [InjectionDecider] 做变化检测。 */
    fun toVersionedLayer(): VersionedPromptLayer =
        VersionedPromptLayer(
            name = "PresenceState",
            content = toPromptBlock(),
            versionToken = versionToken,
        )
}