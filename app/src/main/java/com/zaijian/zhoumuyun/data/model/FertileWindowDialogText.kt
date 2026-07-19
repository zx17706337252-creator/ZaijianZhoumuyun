package com.zaijian.zhoumuyun.data.model

// ─────────────────────────────────────────────────────────────
//  FertileWindowDialogText — 怀孕弹窗触发重构 · 文案策略
//
//  按实现规格「五、文案策略」：
//    1-6 号  | 各自定制版文案（沿用各角色已有的专属语气/称呼习惯，
//              建议放在各自 CharacterConfig 或专属文案文件中维护，
//              本文件不重复收纳——具体 6 份文案内容请按角色补全）。
//    1000+   | 5 版通用文案，本文件维护，排卵期内随机抽一版。
//
//  "同一排卵期固定不换"是通过 PregnancyState.fertileWindowConsentAsked
//  门控自然实现的：弹窗只会在每个排卵期窗口内出现一次（见
//  PregnancyTriggerManager.shouldEvaluateFertileWindowConsent），
//  所以这里不需要额外记录"本次抽到了哪一版"——调用方在弹窗即将
//  展示时调用 pickGenericDialogText() 抽一次即可。
// ─────────────────────────────────────────────────────────────

/**
 * 1000+ 角色（第二代/第三代女儿）专用的 5 版通用同意弹窗文案。
 * "{name}" 占位符会在 [pickGenericDialogText] 中替换为角色当前显示名。
 */
val GenericFertileWindowDialogTexts: List<String> = listOf(
    "{name}的呼吸还没平稳下来，她看着你，没有说话，但那个问题已经悬在两人之间——要不要让这一次，真正地，留下点什么？",
    "气氛已经走到这里了。{name}没有催你，只是安静地等着你先开口——这一次，你们真的要这么做吗？",
    "{name}的手指无意识地攥紧了一点，她在等一个回答。今晚，要不要顺着这股劲，把孩子的事也一起决定了？",
    "没有人说出口，但彼此都清楚现在是什么时刻。{name}抬眼看你，眼神里是没说出来的问题：这一次，算数吗？",
    "{name}靠得很近，近到能听见自己的心跳。她没有退，只是在等你点头——要不要，这次就不避开了？",
)

/**
 * 从 [GenericFertileWindowDialogTexts] 中随机抽一版，替换角色显示名。
 * 调用时机：确定要展示弹窗的那一刻调用一次即可（弹窗本身每个排卵期
 * 窗口只会出现一次，天然满足"同一排卵期固定不换"）。
 */
fun pickGenericDialogText(displayName: String): String =
    GenericFertileWindowDialogTexts.random().replace("{name}", displayName)


