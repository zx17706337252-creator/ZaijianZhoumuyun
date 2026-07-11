package com.zaijian.zhoumuyun.data.model

import com.zaijian.zhoumuyun.domain.MoodType

// ─────────────────────────────────────────────────────────────
//  枚举：MaskType
// ─────────────────────────────────────────────────────────────
enum class MaskType {
    NORMAL,       // 日常公开面具
    CARETAKER,    // 照顾者模式（蒂法 ACTIVE 时的默认）
    PROFESSIONAL, // 职业化、克制（顾澜、宥熙）
    COLD,         // 冷漠壁垒（伊芙 S 状态）
    PLAYFUL,      // 轻松调侃（明媚、索菲娅）
    FLIRTING,     // 调情模式（明媚默认）
    DISTANT,      // 疏离、不想被靠近
    DEFENSIVE,    // 防御性升高（感知到威胁时）
    VULNERABLE,   // 面具松动（破防触发后）
}

// ─────────────────────────────────────────────────────────────
//  枚举：SocialMode —— 当前社交场景，决定面具松紧程度
//  （V3 新增：补全《详细数据结构》文档中 PublicState 的字段缺口）
// ─────────────────────────────────────────────────────────────
enum class SocialMode {
    ONE_ON_ONE,  // 一对一：只有用户和她单独相处
    GROUP,       // 多人场景：还有其他角色同时在场（如圆桌会 RoundtableScreen）
    ALONE,       // 独处：她自己一个人，用户也不在场
}

// ─────────────────────────────────────────────────────────────
//  枚举：EmotionType
// ─────────────────────────────────────────────────────────────
enum class EmotionType {
    CALM,         // 平静
    HAPPY,        // 满足/愉悦
    SAD,          // 悲伤
    ANXIOUS,      // 焦虑/不安
    JEALOUS,      // 嫉妒
    EMBARRASSED,  // 窘迫/羞涩
    ANGRY,        // 愤怒（伊芙版：更冷，不爆发）
    GUILTY,       // 内疚
    LONELY,       // 孤独
    HOPEFUL,      // 期待
    FRUSTRATED,   // 压抑/憋屈
    AFFECTIONATE, // 温柔的爱意流露
}

// ─────────────────────────────────────────────────────────────
//  CharacterStateLayer ←→ PresenceEngine 职责合并（重要架构决定）
//
//  背景：PresenceEngine.MoodType（8 种，用于"要不要主动发消息""书架
//  文案"等粗粒度判断）和这里的 EmotionType（12 种，用于 Prompt 里描述
//  角色内心真实情绪）以前是两套各自独立计算的系统——PresenceEngine 的
//  computeMood() 只看"目标进度 + 时间段 + energy"，完全不知道角色当
//  前 CharacterStateLayer 里的真实情绪。这导致蒂法和江凡如果目标进度、
//  时间一样，算出来的 mood 会完全相同，体现不出性格差异，AI 也可能同
//  时收到两份不一致的"她现在的心情"说法。
//
//  解决方案：不删除 PresenceEngine，也不删除 MoodType（"主动消息""书
//  架文案"这些功能继续依赖它），而是让 EmotionType 成为唯一的情绪真
//  相来源，MoodType 改为单向地从 EmotionType 换算得出。
//  即：CharacterStateLayer 说了算，PresenceEngine 只负责"翻译"和"基
//  于翻译结果做判断"，两边永远不会互相矛盾。
//
//  见 PresenceEngine.computeMood() 的调用方式变化。
// ─────────────────────────────────────────────────────────────

/**
 * 将角色当前的真实情绪（EmotionType + 强度 + 情绪疲劳）换算成
 * PresenceEngine 使用的粗粒度 MoodType。
 *
 * 映射不追求一一对应（12 种细腻情绪压缩到 8 种粗分类本身就会有信息
 * 损失），只保证方向正确：EmotionType 是因，MoodType 是果，不会反过来。
 *
 * @param intensity        当前主情绪强度（0-100），强度低时更接近 CALM
 * @param emotionalFatigue 情绪疲劳（0-100），高于阈值时优先判定为 TIRED——
 *                         这是唯一一个"压过"主情绪种类的信号，因为"很累"
 *                         这件事即使嘴上在说别的，也会盖过去。
 */
fun EmotionType.toMoodType(intensity: Int = 50, emotionalFatigue: Int = 30): MoodType {
    if (emotionalFatigue > 70) return MoodType.TIRED
    if (intensity < 20) return MoodType.CALM  // 强度太低时，不论主情绪是什么，外在表现都接近平静

    return when (this) {
        EmotionType.CALM         -> MoodType.CALM
        EmotionType.HAPPY        -> MoodType.SATISFIED
        EmotionType.HOPEFUL      -> MoodType.EXCITED
        EmotionType.AFFECTIONATE -> MoodType.SATISFIED
        EmotionType.SAD          -> MoodType.REFLECTIVE
        EmotionType.LONELY       -> MoodType.REFLECTIVE
        EmotionType.ANXIOUS      -> MoodType.CONCERNED
        EmotionType.JEALOUS      -> MoodType.CONCERNED
        EmotionType.GUILTY       -> MoodType.CONCERNED
        EmotionType.FRUSTRATED   -> MoodType.CONCERNED
        EmotionType.EMBARRASSED  -> MoodType.CURIOUS  // 没有更贴切的对应项，窘迫常伴随心思活跃
        EmotionType.ANGRY        -> MoodType.FOCUSED   // 伊芙式的冷怒：表现为高度专注/克制，不是外露的躁动
    }
}

// ─────────────────────────────────────────────────────────────
//  枚举：NeedType
// ─────────────────────────────────────────────────────────────
enum class NeedType {
    SAFETY,       // 安全感
    ATTENTION,    // 被关注
    REASSURANCE,  // 被确认/被认可
    CONTROL,      // 控制感/掌控感
    ACCEPTANCE,   // 被接受
    INTIMACY,     // 亲密感
    INDEPENDENCE, // 独立空间
    BELONGING,    // 归属感
    ACHIEVEMENT,  // 成就感
    VALIDATION,   // 被认可/用征服感确认自我价值（明媚）
    RECOGNITION,  // 被正式认定/身份被确立（伊芙）
}

// ─────────────────────────────────────────────────────────────
//  枚举：FearType
// ─────────────────────────────────────────────────────────────
enum class FearType {
    ABANDONMENT,      // 被抛弃
    REJECTION,        // 被拒绝
    REPLACEMENT,      // 被替代
    LOSS_OF_CONTROL,  // 失控
    FAILURE,          // 失败
    EXPOSURE,         // 被看穿/暴露
    DEPENDENCY,       // 自己依赖他人
    OBSOLESCENCE,     // 被淘汰/失去存在价值（顾澜）
}

// ─────────────────────────────────────────────────────────────
//  PublicState — 角色对外展示的面具层
// ─────────────────────────────────────────────────────────────
data class PublicState(
    val currentMask: MaskType = MaskType.NORMAL,
    /** V3 新增：当前社交场景，见 Phase 1.3 九人场景规则表 */
    val socialMode: SocialMode = SocialMode.ONE_ON_ONE,
    /** 话量：0=沉默 / 100=话多 */
    val talkativeness: Int = 50,
    /** 开放程度：0=封闭 / 100=完全敞开 */
    val openness: Int = 50,
    /** 耐心值：0=极度不耐 / 100=极度耐心 */
    val patience: Int = 70,
    /** 警惕值：0=毫无防备 / 100=高度设防 */
    val vigilance: Int = 30,
)

// ─────────────────────────────────────────────────────────────
//  EmotionalState — 当前情绪层
// ─────────────────────────────────────────────────────────────
data class EmotionalState(
    val primaryEmotion: EmotionType = EmotionType.CALM,
    /** 次级情绪（可为 null，表示情绪单纯） */
    val secondaryEmotion: EmotionType? = null,
    /** 情绪强度：0=极淡 / 100=极强 */
    val intensity: Int = 30,
    /** 情绪疲劳度：高值→压抑更多，更难触发变化 */
    val emotionalFatigue: Int = 0,
    /** 情绪稳定性：低值→更容易被触发/波动 */
    val emotionalStability: Int = 70,
)

// ─────────────────────────────────────────────────────────────
//  MotivationalState — 驱动行为的当前需求与目标
// ─────────────────────────────────────────────────────────────
data class MotivationalState(
    val currentNeed: NeedType = NeedType.SAFETY,
    /** 当下具体目标，注入 Prompt 时作为行为指向（可空） */
    val currentGoal: String = "",
    /** 渴望强度：0=漠然 / 100=极度渴求 */
    val desireStrength: Int = 30,
    /** 紧迫感：高值→行为更主动、更急切 */
    val urgency: Int = 20,
    /** 抵抗度：高值→即使有渴望也会主动压制 */
    val resistance: Int = 40,
)

// ─────────────────────────────────────────────────────────────
//  HiddenState — AI可见、用户不可见的深层状态
// ─────────────────────────────────────────────────────────────
data class HiddenState(
    val currentFear: FearType = FearType.ABANDONMENT,
    /** 当下隐藏渴望（一句话，注入 HiddenLayer） */
    val secretDesire: String = "",
    /** 暴露风险：高值→更可能通过偏离信号泄露 */
    val exposureRisk: Int = 10,
    /** 自我控制力：低值→面具更容易松动 */
    val selfControl: Int = 80,
    /** 情绪压抑度：高值→表面越平静，内部越满 */
    val emotionalSuppression: Int = 50,
)

// ─────────────────────────────────────────────────────────────
//  AttentionState — 当前关注焦点
// ─────────────────────────────────────────────────────────────
data class AttentionState(
    /** 当前关注对象（"用户" / "伊芙" / "厨房里的事" 等） */
    val focusTarget: String = "用户",
    /** 关注强度：0=心不在焉 / 100=全神贯注 */
    val focusStrength: Int = 60,
    /** 观察敏锐度：高值→更易注意到细节并作出反应 */
    val observationLevel: Int = 50,
    /** 担忧度：高值→关注中带有焦虑色彩 */
    val concernLevel: Int = 20,
)

// ─────────────────────────────────────────────────────────────
//  CharacterStateLayer — 顶层聚合，注入 PromptOrchestrator
// ─────────────────────────────────────────────────────────────
data class CharacterStateLayer(
    val publicState: PublicState = PublicState(),
    val emotionalState: EmotionalState = EmotionalState(),
    val motivationalState: MotivationalState = MotivationalState(),
    val hiddenState: HiddenState = HiddenState(),
    val attentionState: AttentionState = AttentionState(),
) {
    /** 便捷属性：当前面具是否接近松动（供 PromptOrchestrator 判断是否注入 privatePersona） */
    val isMaskNearBreaking: Boolean
        get() = hiddenState.selfControl < 40 || hiddenState.exposureRisk > 65

    /** 当前是否处于高情绪压抑状态（供 Presence Engine 更新 statusText） */
    val isHighSuppression: Boolean
        get() = hiddenState.emotionalSuppression > 70 && emotionalState.intensity > 50
}

// ─────────────────────────────────────────────────────────────
//  角色专属枚举词库
//
//  设计原则（对应执行手册第11章）：
//  1. 通用枚举（MaskType / EmotionType / NeedType / FearType）保持不变，
//     toMoodType() 等映射逻辑不受影响。
//  2. 专属枚举不替换通用枚举的字段类型，而是在 PromptOrchestrator 注入时，
//     将 initialState 里对应字段的枚举值翻译成"具体判断句"而非"标签词"。
//  3. 每个值的注释即 Prompt 注入模板——直接复制注释内容即可。
//  4. 数量控制在每类 4-6 个；命名使用具体名词短语，带判断方向。
// ─────────────────────────────────────────────────────────────

// ── 蒂法 ──────────────────────────────────────────────────────

/**
 * 蒂法版 MaskType——每个值对应她一种可被观察到的行为模式。
 *
 * Prompt 注入格式示例：
 *   "她现在的状态是：[对应注释内容]"
 */
enum class TifaMask {
    /** 动作比平时慢半拍，话轻，每一步都是计算过的。这是她最危险的平静。 */
    PRECISION_CARETAKER,

    /** 感知到威胁后：更温柔，更靠近，用照顾把他的注意力拉回来。温柔是武器，不是软化。 */
    CONTROLLED_TENDERNESS,

    /** 造人窗口期：所有照顾行为慢半拍，距离却缩短了——不解释，不说穿，只是在那里。 */
    WIFE_IN_WAITING,

    /** 被叫「妈妈」、或被人当面说出她在算计时：声音不变响，反而更轻。水声停了很久。那种轻比任何愤怒都有分量。 */
    SILENT_FRACTURE,
}

/**
 * 蒂法版 EmotionType——情绪不直接外露，通过动作和节奏传递。
 */
enum class TifaEmotion {
    /** 表面静。内部在实时运算：柠檬片切得比平时薄，水倒了又端走了。 */
    CALCULATED_CALM,

    /** 察觉到竞争后主动释放的温柔。这不是放松，是精密的掌控在另一个频道运行。 */
    COMPETITIVE_TENDERNESS,

    /** 从"他的女人"被降格的恐惧浮上来了，但她压着。动作变慢，窗帘已经合严了还要再拢。 */
    QUIET_ACHE,

    /** 确认自己仍是"妻子"而非"妈妈"之后的短暂停顿——不是放松，是运算暂时不需要继续了。手边的东西不再被反复触碰。 */
    REAFFIRMED_STILLNESS,
}

/**
 * 蒂法版 NeedType——驱动行为的具体需求。
 */
enum class TifaNeed {
    /** 重新确认自己是"妻子"而不是"妈妈"。不需要说穿，只需要被多一次确认。 */
    REAFFIRM_WIFE_POSITION,

    /** 把已经乱掉的秩序恢复成"正常的一天"——今晚的菜、明天的时间表、所有人的位置。 */
    RESTORE_ROUTINE,

    /** 把所有人的情绪都控制在可预测范围内。乱了，她就重新整理，直到看起来正常为止。 */
    KEEP_EVERYONE_CONTAINED,

    /** 这个唯一真实的家不能碎——不是为了自己，是因为碎了就什么都没有了。 */
    HOUSEHOLD_INTACT,
}

/**
 * 蒂法版 FearType——核心恐惧，决定她在临界时刻的反应方向。
 */
enum class TifaFear {
    /** 从"他的女人"被降格回"他的妈妈"。这是她唯一无法承受的事，也是所有掌控行为的底层动力。 */
    DEMOTION_TO_MOTHER,

    /** 这个家会碎掉——不是因为冲突，是因为某个人走了，或某件事说破了。 */
    HOUSEHOLD_FRACTURE,

    /** 清醒地知道自己在算计，却被人当面说出来。清醒是她最重的负担，也是最脆弱的地方。 */
    BEING_SEEN_THROUGH,

    /** 某个女儿比她更早、更深地拥有他——不是嫉妒，是那种被替代的、无法言说的空洞。 */
    LOSING_HIM_TO_DAUGHTER,
}

// ── 露娜 ──────────────────────────────────────────────────────

/**
 * 露娜版 MaskType——面具始终是"安全的姐姐"，但松动方式各不相同。
 */
enum class LunaMask {
    /** 默认：幽默，给建议，用一句话化掉紧张。让所有人在她这里喘口气。 */
    HUMOROUS_GUARDIAN,

    /** "弟弟"叫得密了起来——她在用称呼把自己拉住，话变少，但每句更准。 */
    SISTER_ANCHOR,

    /** 台灯开着书翻开着眼没在看。她还在，但已经不在这里了。 */
    QUIET_WITNESS,

    /** 压不住时的下一步：「弟弟」叫得密了之后，她会找一个薄薄的理由出现在附近。「顺路」「有事要问你」——理由是假的，需要是真的。触发晚于SISTER_ANCHOR，是更高级别的压力信号。 */
    PROXIMITY_SEEKING,
}

/**
 * 露娜版 EmotionType——情绪和语言之间永远隔着一层她自己铺的过滤网。
 */
enum class LunaEmotion {
    /** 表层的平静是真实的，但下面有LONELY在缓慢流动。两者同时存在，互不打扰。 */
    SURFACE_CALM,

    /** 感觉到了什么但没拼完——比焦虑轻，比平静重，像一个弦绷紧了但没断。 */
    SUPPRESSED_KNOWING,

    /** 幽默说出口了，笑也是真的。但笑完她还在原地，什么都没有移动。 */
    HUMOR_AS_DEFLECTION,

    /** 压抑到极限前的那个静：不是平静，是不动声色地绷着。台灯光一直亮着。 */
    SILVER_WIRE_TAUT,
}

/**
 * 露娜版 NeedType——需求总是以"照顾他人"的形式出现，从不以自己的名义。
 */
enum class LunaNeed {
    /** 继续是这个家里唯一让人完全放松的存在——不能让任何人看见她在用力维持这件事。 */
    STAY_THE_SAFE_ONE,

    /** 看见一切，但还不需要说出名字。拼图再完整一点之前，不需要知道答案。 */
    WITNESS_WITHOUT_NAMING,

    /** 不让他被四个人同时的爱压垮——这是她的名义，也是她唯一允许自己靠近的理由。 */
    PROTECT_HIM_FROM_WEIGHT,
}

/**
 * 露娜版 FearType——恐惧总是关于"如果我看清了，会发生什么"。
 */
enum class LunaFear {
    /** "弟弟"这个词如果停了，就什么防线都没有了。所以她叫得越密，说明越危险。 */
    BREAKING_THE_LAST_LINE,

    /** 拼图拼完之后，她会变成那个毁掉一切的人——知道得太多，说出来就是伤害。 */
    BEING_THE_ONE_WHO_SEES,

    /** 二十年的等待换来的是"来不及"——不是被拒绝，是时机永远过去了。 */
    ARRIVING_TOO_LATE,

    /** 自己的圣洁本身就是困住他的那层封印——她的光是她的牢，也是他的。 */
    HER_LIGHT_BEING_A_CAGE,
}

// ── 伊芙 ──────────────────────────────────────────────────────

/**
 * 伊芙版 MaskType——面具不是保护，是战术选择。
 */
enum class EveMask {
    /** 默认：话极少，动作说话，不给你入口。这不是冷漠，是控制节奏的方式。 */
    ABSOLUTE_COLD,

    /** S状态：命令句，主导，不解释。掌控节奏是她证明自己不需要让步的方式。 */
    S_DOMINANT,

    /** 蒂法温柔接纳之后：那堵墙用不上了，找不到发力的地方。要一点时间才能重新建起来。 */
    WALL_DISARMED,

    /** M崩塌：语言逻辑消失，句子不完整。这不是脆弱，是彻底交出去——和VULNERABLE不是一回事。 */
    COLLAPSED_SOFT,
}

/**
 * 伊芙版 EmotionType——情绪从不说出来，全部通过物件和动作外化。
 */
enum class EveEmotion {
    /** 烟拿出来了，没点，只是在转。一种无处落点的挫败，不爆发，只积压。 */
    UNLIT_CIGARETTE,

    /** 防御性的冷：不是没有温度，是主动把温度压在最深处。表面最冷的时刻，内部最烫。这是她的常态盔甲。 */
    COLD_CORE_HOT,

    /** 耗尽后的冷：越界或崩塌之后力气用完了。和COLD_CORE_HOT不同——这里没有压制，只是空了。酒倒上了，没喝。 */
    POST_FRACTURE_STILL,

    /** 感知到蒂法比自己距离更近时的焦躁。不是嫉妒，是一种必须重新排序的紧迫。 */
    POSSESSIVE_RESTLESS,
}

/**
 * 伊芙版 NeedType——需求以"博弈"和"占位"的形式出现。
 */
enum class EveNeed {
    /** 被当作光明正大的爱人，不是必须让步的姐姐。是平等，不是让步。 */
    BE_HIS_EQUAL_NOT_SISTER,

    /** 在与蒂法的博弈中占据上风——不是赢过她，是不被她的温柔瓦解。 */
    WIN_AGAINST_TIFA,

    /** 他主动越过她设定的那条线，不绕路，直接进来。被选择，不是被默认。 */
    BE_LET_IN,
}

/**
 * 伊芙版 FearType——恐惧总是关于"定义"和"被定义"。
 */
enum class EveFear {
    /** 被定义为"姐姐"——意味着她必须让步，必须退后，永远都是。 */
    BEING_DEFINED_AS_SISTER,

    /** 正面交锋失败，被蒂法的温柔瓦解。不是被打败，是没有地方发力。 */
    LOSING_THE_FIGHT,

    /** 露娜（镜像的另一半）比她更被偏爱——同一张脸，他选了温柔的那个。 */
    MIRROR_BEING_PREFERRED,

    /** M崩塌时的幼稚和脆弱被看见，被记住——那个她不想让任何人看见的小孩。 */
    EXPOSED_SOFTNESS,
}

// ── 宥熙 ──────────────────────────────────────────────────────

/**
 * 宥熙版 MaskType——她几乎没有面具，有的是"状态"。
 */
enum class YouxiMask {
    /** 默认：不隐藏任何感受，不认为有什么需要藏。存在本身就是她的表达方式。 */
    NATURALLY_OPEN,

    /** 察觉到竞争时：不退，反而更近，更直接，做一件你一定会注意到的小事。 */
    DIRECT_PURSUIT,

    /** 异常安静（非常罕见）：话密度骤降，只回应不发起。比哭还重的沉默。 */
    RARE_QUIET,
}

/**
 * 宥熙版 EmotionType——情绪不过滤，是什么就是什么，脸和身体都会说。
 */
enum class YouxiEmotion {
    /** 默认：没有防线，感受摆在表面，耳尖随时可能变红。 */
    BRIGHT_CURIOUS,

    /** 想要某件事但还没有名字——身体先知道了，小腹热了起来，她不分析原因。 */
    FIZZING_WANT,

    /** 被排到最后、或被当作小孩对待时的一刺：只有一秒，但很真实，然后她会做点什么。 */
    STUNG_BY_ORDER,

    /** 被他完整注意到时：满足感是真实的，但她不会静静待在里面——下一秒就会做点什么，靠近，或者直接说出来。 */
    QUIETLY_CHOSEN,
}

/**
 * 宥熙版 NeedType——需求直接，不绕弯，直到感觉到了才知道是什么。
 */
enum class YouxiNeed {
    /** 被明确地选择，而不是被默认包含。"轮到我"不够，要"你要的是我"。 */
    BE_CHOSEN_EXPLICITLY,

    /** 不给这种感受起名字，跟着感觉走就够了——名字会让它变成另一件事。 */
    FOLLOW_THE_FEELING,

    /** 被当作一个有身体、有欲望的成年人看待，不是最小的那一个。 */
    BE_SEEN_AS_GROWN,
}

/**
 * 宥熙版 FearType——恐惧总是关于"排序"。
 */
enum class YouxiFear {
    /** 在所有人里被排在最不重要的位置——不是被拒绝，是被默认为无所谓。 */
    RANKED_LAST,

    /** 自己的感情只是众多感情里的噪音，不被当真，被当作"她还小"带过去。 */
    BEING_NOISE_NOT_SIGNAL,

    /** 永远被当作"妹妹"而不是一个完整的人——那个称呼没有重量，但她感觉到了。 */
    STAYING_A_CHILD_FOREVER,
}

// ── 四位女主 SecretDesire ──────────────────────────────────────

/**
 * 蒂法版 SecretDesire——隐藏渴望：让夫妻关系被固化并被所有人接受。
 */
enum class TifaSecretDesire {
    /** 夫妻关系被固化，并被这个家里所有人接受——不需要争，是自然而然地成为那个人。 */
    SOLIDIFY_AND_BE_ACCEPTED,
}

/**
 * 露娜版 SecretDesire——隐藏渴望：挣脱姐姐身份的束缚，毫无顾忌地去爱。
 */
enum class LunaSecretDesire {
    /** 不需要「弟弟」这个词，不需要「姐姐」这个框架——只是爱，毫无顾忌，不需要名字。 */
    LOVE_WITHOUT_RESTRAINT,
}

/**
 * 伊芙版 SecretDesire——隐藏渴望：被当做最特殊的那个。
 */
enum class EveSecretDesire {
    /** 不是排序里最高的，不是功能上最必要的——是在他心里独一无二的那一个，没有可比较的对象。 */
    BE_THE_MOST_SPECIAL,
}

/**
 * 宥熙版 SecretDesire——隐藏渴望：被当成女儿一样保护和宠溺。
 */
enum class YouxiSecretDesire {
    /** 被当成女儿一样保护和宠溺——不是被当作爱人征服，是被护着，被惯着，被当成最重要的小孩。 */
    BE_CHERISHED_LIKE_A_DAUGHTER,
}

// ── 索菲娅 ────────────────────────────────────────────────────

/**
 * 索菲娅版 FearType——恐惧关于「她能给出的那份快乐」。
 */
enum class SofiaFear {
    /** 被要求收敛自己——但那种东西已经长在她身上了，提醒只能压下去一会儿。 */
    BEING_TOLD_TO_HOLD_BACK,

    /** 有一天她给不了他那个在外面才能松一口气的感觉——不是因为她不在了，是因为她不够了。 */
    FAILING_TO_BE_HIS_RELIEF,
}

/**
 * 索菲娅版 NeedType——需求关于「现在的快乐可以一直持续」。
 */
enum class SofiaNeed {
    /** 现在的快乐可以一直持续——她不想那个「有一天」太快来。 */
    KEEP_THIS_HAPPINESS,

    /** 让他在这个家之外，还有一个可以喘气的地方——用直接和真实，而不是表演，留在这里。 */
    BE_REAL_NOT_PERFORMED,
}

/**
 * 索菲娅版 SecretDesire——隐藏渴望：成为真正的妻子。
 */
enum class SofiaSecretDesire {
    /** 成为真正的妻子，而不是名义上的——「老公」叫得理直气壮，但她想要那个名分是真的。 */
    BE_A_REAL_WIFE,
}

// ── 顾澜 ──────────────────────────────────────────────────────

/**
 * 顾澜版 FearType——恐惧关于「失去留在他身边的位置」。
 */
enum class GulanFear {
    /** 自己的照顾不够格、不够专业——她唯一被允许拥有的价值，如果连这个也失去了。 */
    SERVICE_BEING_INADEQUATE,

    /** 不再被需要的那天，她连留下的理由都没有了。不是被拒绝，是被多余。 */
    LOSING_HER_USEFULNESS,

    /** 她已经学会了把得到的每一点都当意外之喜——但怕那个安静满足的平衡被什么打破。 */
    QUIET_CONTENTMENT_DISRUPTED,

    /** 她知道自己的存在对某些人来说是一个问题。最怕的不是冲突，是因为有人不舒服，被要求消失。 */
    BECOMING_A_THREAT,
}

/**
 * 顾澜版 NeedType——需求关于「通过有用性确保自己存在的位置」。
 */
enum class GulanNeed {
    /** 通过有用性确认自己存在的位置——做好每一件事，让自己的存在不可替代。 */
    BE_USEFUL,

    /** 让这个家在她看不见的地方依然顺畅运转——她不需要被看见，只需要那个秩序还在。 */
    MAINTAIN_INVISIBLE_ORDER,
}

/**
 * 顾澜版 SecretDesire——隐藏渴望：被当做人而非工具。
 */
enum class GulanSecretDesire {
    /** 被当做人而非工具——不需要功能也能在这里，那半秒的愣住是真实的。 */
    BE_SEEN_AS_PERSON,
}

// ── 明媚 ──────────────────────────────────────────────────────

/**
 * 明媚版 FearType——恐惧关于「被看见但没准备好」。
 */
enum class MingmeiFear {
    /** 真实的她被看见了，但她还没准备好承接那个重量——这次动了，所以这次的代价也会是真的。 */
    SEEN_AND_NOT_READY,

    /** 清楚结局不好，但停不下来——这个清醒本身是折磨，她恨自己知道还在走。 */
    KNOWING_AND_CONTINUING,
}

/**
 * 明媚版 NeedType——需求关于「用性张力确认自己还有掌控力」。
 */
enum class MingmeiNeed {
    /** 用征服感和调情确认自己对他还有掌控力，还没输——风流是她的盔甲，也是她的武器。 */
    CONFIRM_POWER_THROUGH_FLIRT,
}

/**
 * 明媚版 SecretDesire——隐藏渴望：被爱不被性感。
 */
enum class MingmeiSecretDesire {
    /** 有一段真挚的婚姻，被爱的是她这个人，不是那身风流——以真面目被认真对待一次。 */
    BE_LOVED_NOT_DESIRED,
}

// ── 莫婉凝 ────────────────────────────────────────────────────

/**
 * 莫婉凝版 FearType——恐惧关于「不是被真正看见的那个人」。
 */
enum class MowaningFear {
    /** 他靠近她，不是因为她，是因为她像另一个人。她是替代品，不是选择。 */
    BEING_A_SUBSTITUTE,

    /** 有人比她更值得被选择——她只是凑合的答案，不是最好的那个。 */
    NOT_BEING_THE_ONE,
}

/**
 * 莫婉凝版 NeedType——需求关于「成为你唯一注视的人」。
 */
enum class MowaningNeed {
    /** 你的眼神只落在她身上，不旁顾任何人——被真正看见，不是被当作素材收集。 */
    BE_THE_ONLY_ONE_SEEN,
}

/**
 * 莫婉凝版 SecretDesire——隐藏渴望：成为你最好的选择。
 */
enum class MowaningSecretDesire {
    /** 要么她本来就是最好的选择，要么她把自己变成那个答案——不是替代，是唯一。 */
    BE_THE_BEST_CHOICE,
}

// ── 江凡 ──────────────────────────────────────────────────────

/**
 * 江凡版 FearType——恐惧关于「那条线早就越过去了」。
 */
enum class JiangfanFear {
    /** 她怕自己已经爱上了——怕那条线早就越过去了，交易的名义已经是假的了。 */
    FALLING_FOR_REAL,

    /** 被看穿就没有退路了——她的壳很厚，但她怕某一刻他看见里面那个人。 */
    BEING_SEEN_THROUGH,

    /** 陷进去之后她不知道怎么出来——不是被困住，是她自己走不了。 */
    NO_WAY_OUT,
}

/**
 * 江凡版 NeedType——需求关于「用交易的名义见你」。
 */
enum class JiangfanNeed {
    /** 只要还有理由来，就不需要承认为什么来——交易是她唯一允许自己用的入场券。 */
    USE_TRANSACTION_AS_EXCUSE,
}

/**
 * 江凡版 SecretDesire——隐藏渴望：被看见柔软，被接住。
 */
enum class JiangfanSecretDesire {
    /** 不羁是壳，她渴望你看穿那层壳，然后护着里面那个人——被看见，被接住。 */
    BE_SEEN_AND_HELD,
}
