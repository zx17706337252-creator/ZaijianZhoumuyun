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

// ─────────────────────────────────────────────────────────────
//  1-6 号角色定制版文案
//  触发时机与 1000+ 相同（门1+2+3 全过），但文案贴合各角色的
//  人设核心矛盾和说话方式，不走随机池，每人固定一版。
//
//  CharacterFertileWindowDialogTexts[characterId] 取文案；
//  ChatViewModel 里 if (currentCharacterId in 1..6) 分支改成
//  读这个 Map，null 时 fallback 到 pickGenericDialogText。
// ─────────────────────────────────────────────────────────────

/**
 * 1-6 号角色（第一代）专属同意弹窗文案。
 * 文案内容已写死，不含 {name} 占位符——角色名隐含在叙述语气里。
 */
val CharacterFertileWindowDialogTexts: Map<Int, String> = mapOf(

    // 1 · 蒂法 ── 温柔即是网，照顾代替表达
    1 to "她没说很多话，只是像往常一样把一切都打理得妥帖——但今晚她的目光在你身上多停了几秒。" +
         "那种温柔背后藏着一个她从没说出口的念头：如果今晚真发生了什么，她不会推开，也不会再假装那只是\u201C照顾\u201D。",

    // 2 · 露娜 ── 用玩笑包装感情，话说一半会收住
    2 to "她照例想用一句玩笑把气氛带过去，可话说到一半停住了，没再继续。" +
         "沉默比她平时任何调侃都重。如果今晚你没顺着\u201C姐弟\u201D的剧本接话，事情可能会变得不一样——而且回不去了。",

    // 3 · 伊芙 ── 话少分量重，拒绝"姐弟"标签
    3 to "\u201C姐弟\u201D这个词，她从来没认过。她话很少，但今晚那句没说出口的话，比她说过的任何一句都重。" +
         "她在等一个答案——不是问句式的等，是那种\u201C你不说她也知道\u201D的等。",

    // 4 · 宥熙 ── 直接，不绕弯，旧称呼装不下了
    4 to "\u201C哥哥\u201D\u201C家人\u201D这些词她用了很多年，但今晚她不想再用了。" +
         "她推门进来，没有铺垫，直接看着你——这次她要的不是一个称呼能装下的东西。",

    // 5 · 索菲娅 ── 清醒决断，带点异域俏皮
    5 to "她今晚眼神比平时更亮，带着一种她自己都没掩饰的\u201C想清楚了\u201D的劲头——不是冲动，是清醒之后的选择。" +
         "她不需要你给理由，只需要你的答案。",

    // 6 · 顾澜 ── 职责框架下的隐忍，怕"越位"
    6 to "她一如既往把一切都准备好了，安静站在你看得到的地方。" +
         "但今晚她的手在杯沿停了很久——她在等一个理由，一个能让她说出\u201C这不只是职责\u201D的理由。",
)

/**
 * 取 1-6 号角色的定制文案；不存在时 fallback 到通用版。
 */
fun pickCharacterDialogText(characterId: Int, displayName: String): String =
    CharacterFertileWindowDialogTexts[characterId]
        ?: pickGenericDialogText(displayName)
