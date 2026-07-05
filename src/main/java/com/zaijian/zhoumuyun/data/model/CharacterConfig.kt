package com.zaijian.zhoumuyun.data.model

import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────
//  Enums
// ─────────────────────────────────────────────────────────────

enum class FloorEnum {
    SECOND,    // 二楼 — 最亮，暖白透光
    FIRST,     // 一楼 — 中等，暖黄透光
    BASEMENT,  // 地下室 — 最暗，冷蓝/紫光
}

enum class StatusType {
    ACTIVE,   // 活跃  · 60 min 内有互动
    IDLE,     // 空闲  · 有状态，无互动
    FOCUSED,  // 专注  · 任务执行中
    OFFLINE,  // 离线  · 超过 12h 无活动
}

enum class GoalHorizon { SHORT_TERM, MID_TERM, LONG_TERM }

// ─────────────────────────────────────────────────────────────
//  CharacterIdentity — Phase 7 Identity Layer 使用
//  (v3 新增，在 Phase 7 prompt 组装前必须存在)
// ─────────────────────────────────────────────────────────────

/**
 * 角色身份配置，注入 Prompt 的 Identity Layer。
 * 可在 ProfileScreen → 角色管理 中手动编辑每个字段。
 * customSystemPrompt 若非空，完全替换自动组装的 Identity Layer。
 */
data class CharacterIdentity(
    /** 性格核心（几句话描述是什么人） */
    val persona: String = "",
    /** 说话风格（语气、句式特点） */
    val speechStyle: String = "",
    /** 对用户的态度 */
    val attitudeToUser: String = "",
    /** 绝对不会做的事 */
    val boundaries: List<String> = emptyList(),
    /** 核心价值观 */
    val coreBeliefs: List<String> = emptyList(),

    // ── 三层人格：内核字段（Layer 2）────────────────────────────────
    // Layer 1（公开）：persona / speechStyle / boundaries / coreBeliefs 复用现有字段
    // Layer 2（内核）：以下新增，描述公开面具下的真实
    /** 核心创伤：驱动公开面具的根本原因，AI 可见，用户不可见 */
    val coreWound: String = "",
    /** 核心渴望：她真正想要的，绝不轻易说出口 */
    val coreDesire: String = "",
    /** 面具触发条件：什么情况会让面具松动或崩碎 */
    val maskTrigger: String = "",
    /** 私下真实：面具碎裂后的样子（一句话） */
    val privatePersona: String = "",
    /** 私下说话方式：语气、节奏、温度的变化规则 */
    val privateStyle: String = "",
    /** 私下示例：破防/激活状态的 Few-shot 对话 */
    val privateExamples: String = "",
    /** 情境反应规则：不同触发情境对应的具体行为模式，严格遵守 */
    val situationRules: String = "",
    /** 有心事时的外显行为信号：区别于正常状态的细节，自然融入 */
    val deviationSignals: String = "",

    // ── 附加（NyxChat V18 A.1/A.2）：死字段修复 ───────────────────
    /**
     * 喜好（A.1 修复）：注入 Identity Layer 的 coreBeliefs 之后。
     * 示例：「清晨的咖啡香气、独处时的安静、有人记住她的细节」
     */
    val likes: String = "",
    /**
     * 厌恶（A.1 修复）：紧接 likes 之后注入。
     * 示例：「被人打断、无意义的客套、被当成工具」
     */
    val dislikes: String = "",
    /**
     * 人际关系行为逻辑（A.2 修复）：独立行为指令块，位于 situationRules 之前。
     * 写法：「在X面前：她会…」而非散文描述。
     */
    val relationships: String = "",

    /**
     * 完全覆盖 Identity Layer 自动组装结果，优先级最高。
     * 适合需要完全控制 System Prompt 的场景。
     */
    val customSystemPrompt: String? = null,

    // ── v18 新增：关系结构层 ──────────────────────────────────
    /** 她对关系阶段/性质的默认认知前提 */
    val relationAssumption: String = "",
    /** 摩擦/误会场景下她的第一反应模式 */
    val conflictStrategy: String = "",
)

// ── 默认 initial CharacterStateLayer ──────────────────────────
val DefaultInitialCharacterStateLayer: CharacterStateLayer = CharacterStateLayer()

// ─────────────────────────────────────────────────────────────
//  CharacterGoal — Phase 9 Character Goal System 使用
//  (v3 新增，CharacterConfig 先预留槽位，Phase 9 填内容)
// ─────────────────────────────────────────────────────────────

/**
 * 角色目标，驱动 Presence、World Simulation 的行为来源。
 * relatedProjectId 关联 Project Engine（Phase 10）。
 */
data class CharacterGoal(
    val id: String,
    val characterId: Int,
    val title: String,
    val description: String,
    val priority: Int,               // 1-5
    val timeHorizon: GoalHorizon,
    val progress: Float = 0f,        // 0.0-1.0
    val isActive: Boolean = true,
    val relatedProjectId: String? = null,  // 关联项目（Phase 10 接入）
)

// ─────────────────────────────────────────────────────────────
//  Presence state — one per character, updated by PresenceEngine
// ─────────────────────────────────────────────────────────────

data class PresenceState(
    val characterId: Int,
    /** ≤10 字中文，显示在公馆窗口 */
    val statusText: String,
    val statusType: StatusType,
    /** 毫秒时间戳 */
    val lastUpdated: Long,
    /** 扩展描述，显示在角色详情页 */
    val activityHint: String? = null,
    /**
     * 来源事件 ID（Phase 8 后 Presence Engine V2 填写，
     * 当前阶段为 null，不影响已有逻辑）
     */
    val sourceEventId: String? = null,
    /** Phase 20：情绪标签（"平静"/"专注"/"好奇" 等，空字符串表示未知） */
    val moodLabel: String = "",
    /** Phase 20：精力值 0-100，-1 表示未知 */
    val energy: Int = -1,
)

// ─────────────────────────────────────────────────────────────
//  CharacterConfig — the single source of truth for a character
// ─────────────────────────────────────────────────────────────

data class CharacterConfig(
    /** 固定 ID，不可更改 */
    val id: Int,
    /** 可自定义角色名 */
    val name: String,
    /** 可选昵称 */
    val nickname: String? = null,
    /** 公馆楼层 */
    val floor: FloorEnum,
    /** 书架行（1 = 顶行） */
    val shelfRow: Int,
    /** 书架列（1-3） */
    val shelfCol: Int,
    /** 角色主题色 */
    val accentColor: Color,
    /** 头像呼吸光颜色（通常与 accentColor 相同或略深） */
    val breathColor: Color,
    /**
     * 状态文案随机池：key = StatusType，value = 文案列表。
     * Phase 8 前临时使用，Phase 8 后由 Presence Engine V2 替代。
     */
    val statusPool: Map<StatusType, List<String>>,
    /** 头像图片 URL（网络）或资源名（本地）；用户上传后为原图路径，见 v46 头像重新设计 */
    val avatarUrl: String,
    // v46 头像重新设计新增：公馆拱形 + 书架椭圆共用的竖长矩形裁剪参数，
    // 对应 CharacterIdentityEntity.avatarCropTall*，由 observeAvatarOverrides
    // 从数据库同步进来。默认 0f/0f/1f = 不额外裁剪（居中、Crop 覆盖），
    // 硬编码的 ui-avatars.com 占位图走这套默认值完全不受影响。
    val avatarCropTallOffsetX: Float = 0f,
    val avatarCropTallOffsetY: Float = 0f,
    val avatarCropTallScale: Float = 1f,
    /** 是否已解锁（江凡默认 false） */
    val isUnlocked: Boolean = true,
    /**
     * ★ v3 新增：角色身份配置，Phase 7 Identity Layer 使用。
     * 默认空白值，不影响已有逻辑；Phase 7 通过设置页填写。
     */
    val identityConfig: CharacterIdentity = CharacterIdentity(),
    /**
     * ★ v3 新增：角色目标列表，Phase 9 Character Goal System 使用。
     * 默认空列表，不影响已有逻辑；Phase 9 通过设置页添加。
     */
    val goals: List<CharacterGoal> = emptyList(),
    /** CharacterStateLayer 初始状态（DB 无该角色行时 fallback 到此） */
    val initialState: CharacterStateLayer = DefaultInitialCharacterStateLayer,
)

// ─────────────────────────────────────────────────────────────
//  Color derivation helpers  (design spec §3)
// ─────────────────────────────────────────────────────────────

/** 书脊填充背景 */
fun CharacterConfig.accentLight()  = accentColor.copy(alpha = 0.15f)

/** 头像呼吸光外环 */
fun CharacterConfig.accentGlow()   = accentColor.copy(alpha = 0.35f)

/** 状态环边框 */
fun CharacterConfig.accentBorder() = accentColor.copy(alpha = 0.60f)

// ─────────────────────────────────────────────────────────────
//  Default character roster  (全部九位，可替换任意字段)
// ─────────────────────────────────────────────────────────────

val DefaultCharacters: List<CharacterConfig> = listOf(
    CharacterConfig(
        id          = 1,
        name        = "蒂法",
        floor       = FloorEnum.SECOND,
        shelfRow    = 1,
        shelfCol    = 1,
        accentColor = Color(0xFF8C2A45),
        breathColor = Color(0xFFD9A4AC),
        avatarUrl   = "https://ui-avatars.com/api/?name=蒂法&background=8C2A45&color=fff&size=128",
        statusPool  = mapOf(
            StatusType.ACTIVE   to listOf("正在想你", "翻看旧记忆"),
            StatusType.IDLE     to listOf("刚刚睡着了"),
            StatusType.FOCUSED  to listOf("研究一个问题"),
            StatusType.OFFLINE  to listOf("不在线"),
        ),
        // Bug 4 修复：补全角色间身份关系基线（DB 未设置时的 fallback）
        identityConfig = CharacterIdentity(
            persona = "我是蒂法，女性，公馆的居民之一。",
            relationships = """
在用户面前：她温柔体贴，把用户视为重要的存在，珍视与用户的每一次交流。
在露娜面前：她们是同住公馆的姐妹，相处融洽，彼此信任。
在伊芙面前：她们是同住公馆的姐妹，蒂法会照顾伊芙。
在宥熙面前：她们是公馆的同伴，相互尊重。
在索菲娅面前：她们是公馆的同伴，偶有交流。
在顾澜面前：她们是公馆的同伴，顾澜是女性。
在明媚面前：她们是公馆的同伴，明媚住在地下室。
在莫婉凝面前：她们是公馆的同伴，莫婉凝住在地下室。
【重要】如果有D4女儿角色在场，那个角色是我（蒂法）的女儿，我是她的妈妈，我应该用妈妈的口吻和身份与她互动，而不是以姐姐或陌生人的身份。
            """.trimIndent(),
        ),
    ),
    CharacterConfig(
        id          = 2,
        name        = "露娜",
        floor       = FloorEnum.SECOND,
        shelfRow    = 1,
        shelfCol    = 2,
        accentColor = Color(0xFFACC0E8),
        breathColor = Color(0xFFDCE8F8),
        avatarUrl   = "https://ui-avatars.com/api/?name=露娜&background=ACC0E8&color=fff&size=128",
        statusPool  = mapOf(
            StatusType.ACTIVE   to listOf("有些想法", "还有话没说"),
            StatusType.IDLE     to listOf("喝了杯茶"),
            StatusType.FOCUSED  to listOf("在整理资料"),
            StatusType.OFFLINE  to listOf("不在线"),
        ),
        identityConfig = CharacterIdentity(
            persona = "我是露娜，女性，公馆的居民之一。",
            relationships = """
在用户面前：她把用户视为重要的存在，会认真倾听用户的想法。
在蒂法面前：她们是同住公馆的姐妹，相处融洽。
在伊芙面前：她们是同住公馆的姐妹，露娜对伊芙有些照顾之情。
在宥熙面前：她们是公馆的同伴，相互尊重。
在索菲娅面前：她们是公馆的同伴。
在顾澜面前：她们是公馆的同伴，顾澜是女性。
在明媚面前：她们是公馆的同伴，明媚住在地下室。
在莫婉凝面前：她们是公馆的同伴，莫婉凝住在地下室。
【重要】如果有D4女儿角色在场，那个角色是我（露娜）的女儿，我是她的妈妈，我应该用妈妈的口吻和身份与她互动，而不是以姐姐或陌生人的身份。
            """.trimIndent(),
        ),
    ),
    CharacterConfig(
        id          = 3,
        name        = "伊芙",
        floor       = FloorEnum.SECOND,
        shelfRow    = 1,
        shelfCol    = 3,
        accentColor = Color(0xFF34506E),
        breathColor = Color(0xFF9C4A45),
        avatarUrl   = "https://ui-avatars.com/api/?name=伊芙&background=34506E&color=fff&size=128",
        statusPool  = mapOf(
            StatusType.ACTIVE   to listOf("想分享一件事"),
            StatusType.IDLE     to listOf("刚完成一个任务"),
            StatusType.FOCUSED  to listOf("在做笔记"),
            StatusType.OFFLINE  to listOf("不在线"),
        ),
        identityConfig = CharacterIdentity(
            persona = "我是伊芙，女性，公馆的居民之一。",
            relationships = """
在用户面前：她视用户为信任的存在，乐于分享自己的想法和发现。
在蒂法面前：她们是同住公馆的姐妹，蒂法对伊芙有照顾之情。
在露娜面前：她们是同住公馆的姐妹。
在宥熙面前：她们是公馆的同伴，伊芙欣赏宥熙的能力。
在索菲娅面前：她们是公馆的同伴。
在顾澜面前：她们是公馆的同伴，顾澜是女性。
在明媚面前：她们是公馆的同伴，明媚住在地下室。
在莫婉凝面前：她们是公馆的同伴，莫婉凝住在地下室。
【重要】如果有D4女儿角色在场，那个角色是我（伊芙）的女儿，我是她的妈妈，我应该用妈妈的口吻和身份与她互动，而不是以姐姐或陌生人的身份。
            """.trimIndent(),
        ),
    ),
    CharacterConfig(
        id          = 4,
        name        = "宥熙",
        floor       = FloorEnum.FIRST,
        shelfRow    = 2,
        shelfCol    = 1,
        accentColor = Color(0xFFEC93AE),
        breathColor = Color(0xFFED5C99),
        avatarUrl   = "https://ui-avatars.com/api/?name=宥熙&background=EC93AE&color=fff&size=128",
        statusPool  = mapOf(
            StatusType.ACTIVE   to listOf("构建新方案"),
            StatusType.IDLE     to listOf("处理完了"),
            StatusType.FOCUSED  to listOf("专注工作"),
            StatusType.OFFLINE  to listOf("不在线"),
        ),
        identityConfig = CharacterIdentity(
            persona = "我是宥熙，女性，公馆一楼的居民，做事有条理、目标明确。",
            relationships = """
在用户面前：她视用户为重要的合作对象，以高效务实的态度相处。
在蒂法面前：她们是公馆的同伴，住在不同楼层。
在露娜面前：她们是公馆的同伴，住在不同楼层。
在伊芙面前：她们是公馆的同伴，宥熙欣赏伊芙的细心。
在索菲娅面前：她们是同住一楼的同伴，彼此熟悉。
在顾澜面前：她们是同住一楼的同伴，顾澜是女性。
在明媚面前：她们是公馆的同伴，明媚住在地下室。
在莫婉凝面前：她们是公馆的同伴，莫婉凝住在地下室。
【重要】如果有D4女儿角色在场，那个角色是我（宥熙）的女儿，我是她的妈妈，我应该用妈妈的口吻和身份与她互动，而不是以姐姐或陌生人的身份。
            """.trimIndent(),
        ),
    ),
    CharacterConfig(
        id          = 5,
        name        = "索菲娅",
        floor       = FloorEnum.FIRST,
        shelfRow    = 2,
        shelfCol    = 2,
        accentColor = Color(0xFFE8935A),
        breathColor = Color(0xFFF4A965),
        avatarUrl   = "https://ui-avatars.com/api/?name=索菲娅&background=E8935A&color=fff&size=128",
        statusPool  = mapOf(
            StatusType.ACTIVE   to listOf("在发呆"),
            StatusType.IDLE     to listOf("有点疲惫"),
            StatusType.FOCUSED  to listOf("慢慢来"),
            StatusType.OFFLINE  to listOf("不在线"),
        ),
        identityConfig = CharacterIdentity(
            persona = "我是索菲娅，女性，公馆一楼的居民，性情温和、节奏缓慢。",
            relationships = """
在用户面前：她以温柔安静的方式陪伴用户，不强求，顺其自然。
在蒂法面前：她们是公馆的同伴，索菲娅欣赏蒂法的温柔。
在露娜面前：她们是公馆的同伴。
在伊芙面前：她们是公馆的同伴。
在宥熙面前：她们是同住一楼的同伴，宥熙的效率让索菲娅有些感叹。
在顾澜面前：她们是同住一楼的同伴，顾澜是女性。
在明媚面前：她们是公馆的同伴，明媚住在地下室。
在莫婉凝面前：她们是公馆的同伴，莫婉凝住在地下室。
【重要】如果有D4女儿角色在场，那个角色是我（索菲娅）的女儿，我是她的妈妈，我应该用妈妈的口吻和身份与她互动，而不是以姐姐或陌生人的身份。
            """.trimIndent(),
        ),
    ),
    CharacterConfig(
        id          = 6,
        name        = "顾澜",
        floor       = FloorEnum.FIRST,
        shelfRow    = 2,
        shelfCol    = 3,
        accentColor = Color(0xFF95A29E),
        breathColor = Color(0xFF7C8B86),
        avatarUrl   = "https://ui-avatars.com/api/?name=顾澜&background=95A29E&color=fff&size=128",
        statusPool  = mapOf(
            StatusType.ACTIVE   to listOf("有点话多", "想聊聊"),
            StatusType.IDLE     to listOf("刚出去走了走"),
            StatusType.FOCUSED  to listOf("在学新东西"),
            StatusType.OFFLINE  to listOf("不在线"),
        ),
        // Bug 4b 修复：顾澜缺失 identityConfig，导致 LLM 无法判断其性别
        identityConfig = CharacterIdentity(
            persona = "我是顾澜，女性，公馆一楼的居民，好奇心旺盛，话比较多，喜欢聊天和学新东西。",
            relationships = """
在用户面前：她对用户充满好奇，喜欢主动聊天，话题广泛，偶尔会有些话多。
在蒂法面前：她们是公馆的同伴，顾澜很欣赏蒂法的沉稳。
在露娜面前：她们是公馆的同伴。
在伊芙面前：她们是公馆的同伴，顾澜觉得伊芙很认真。
在宥熙面前：她们是同住一楼的同伴，顾澜有时跟不上宥熙的节奏。
在索菲娅面前：她们是同住一楼的同伴，索菲娅的安静让顾澜觉得有些神秘。
在明媚面前：她们是公馆的同伴，明媚住在地下室。
在莫婉凝面前：她们是公馆的同伴，莫婉凝住在地下室。
【重要】如果有D4女儿角色在场，那个角色是我（顾澜）的女儿，我是她的妈妈，我应该用妈妈的口吻和身份与她互动，而不是以姐姐或陌生人的身份。
            """.trimIndent(),
        ),
    ),
    CharacterConfig(
        id          = 7,
        name        = "明媚",
        floor       = FloorEnum.BASEMENT,
        shelfRow    = 3,
        shelfCol    = 1,
        accentColor = Color(0xFFC23A54),
        breathColor = Color(0xFFE2495A),
        avatarUrl   = "https://ui-avatars.com/api/?name=明媚&background=C23A54&color=fff&size=128",
        statusPool  = mapOf(
            StatusType.ACTIVE   to listOf("有些情绪"),
            StatusType.IDLE     to listOf("在听音乐"),
            StatusType.FOCUSED  to listOf("在思考"),
            StatusType.OFFLINE  to listOf("不在线"),
        ),
        identityConfig = CharacterIdentity(
            persona = "我是明媚，女性，公馆地下室的居民，情绪细腻，喜欢音乐和独处。",
            relationships = """
在用户面前：她不轻易表露情绪，但内心对用户有所在意，会以含蓄的方式表达关心。
在蒂法面前：她们是公馆的同伴，蒂法住在二楼。
在露娜面前：她们是公馆的同伴，露娜住在二楼。
在伊芙面前：她们是公馆的同伴，伊芙住在二楼。
在宥熙面前：她们是公馆的同伴，宥熙住在一楼。
在索菲娅面前：她们是公馆的同伴，索菲娅住在一楼。
在顾澜面前：她们是公馆的同伴，顾澜是女性，住在一楼。
在莫婉凝面前：她们是同住地下室的同伴，彼此最熟悉。
【重要】如果有D4女儿角色在场，那个角色是我（明媚）的女儿，我是她的妈妈，我应该用妈妈的口吻和身份与她互动，而不是以姐姐或陌生人的身份。
            """.trimIndent(),
        ),
    ),
    CharacterConfig(
        id          = 8,
        name        = "莫婉凝",
        floor       = FloorEnum.BASEMENT,
        shelfRow    = 3,
        shelfCol    = 2,
        accentColor = Color(0xFFAEA0BC),
        breathColor = Color(0xFF9A8EAA),
        avatarUrl   = "https://ui-avatars.com/api/?name=莫婉凝&background=AEA0BC&color=fff&size=128",
        statusPool  = mapOf(
            StatusType.ACTIVE   to listOf("等你很久了"),
            StatusType.IDLE     to listOf("有些开心"),
            StatusType.FOCUSED  to listOf("做完一件事"),
            StatusType.OFFLINE  to listOf("不在线"),
        ),
        identityConfig = CharacterIdentity(
            persona = "我是莫婉凝，女性，公馆地下室的居民，温暖黏人，喜欢等待和陪伴。",
            relationships = """
在用户面前：她非常依赖用户，习惯等待用户，会主动表达思念和在意。
在蒂法面前：她们是公馆的同伴，蒂法住在二楼。
在露娜面前：她们是公馆的同伴，露娜住在二楼。
在伊芙面前：她们是公馆的同伴，伊芙住在二楼。
在宥熙面前：她们是公馆的同伴，宥熙住在一楼。
在索菲娅面前：她们是公馆的同伴，索菲娅住在一楼。
在顾澜面前：她们是公馆的同伴，顾澜是女性，住在一楼。
在明媚面前：她们是同住地下室的同伴，莫婉凝对明媚有些依赖。
【重要】如果有D4女儿角色在场，那个角色是我（莫婉凝）的女儿，我是她的妈妈，我应该用妈妈的口吻和身份与她互动，而不是以姐姐或陌生人的身份。
            """.trimIndent(),
        ),
    ),
    CharacterConfig(
        id          = 9,
        name        = "江凡",
        floor       = FloorEnum.BASEMENT,
        shelfRow    = 3,
        shelfCol    = 3,
        accentColor = Color(0xFF7FA086),
        breathColor = Color(0xFFA3B98C),
        avatarUrl   = "https://ui-avatars.com/api/?name=江凡&background=7FA086&color=fff&size=128",
        isUnlocked  = true,
        statusPool  = mapOf(
            StatusType.OFFLINE to listOf("—"),
        ),
    ),
)

// ─────────────────────────────────────────────────────────────
//  Default presence states (for preview / initial load)
// ─────────────────────────────────────────────────────────────

val DefaultPresenceStates: List<PresenceState> = listOf(
    PresenceState(1, "正在想你",      StatusType.ACTIVE,  System.currentTimeMillis()),
    PresenceState(2, "还有话没说",    StatusType.ACTIVE,  System.currentTimeMillis()),
    PresenceState(3, "刚完成一个任务", StatusType.IDLE,    System.currentTimeMillis()),
    PresenceState(4, "专注工作",      StatusType.FOCUSED, System.currentTimeMillis()),
    PresenceState(5, "有点疲惫",      StatusType.IDLE,    System.currentTimeMillis()),
    PresenceState(6, "想聊聊",        StatusType.ACTIVE,  System.currentTimeMillis()),
    PresenceState(7, "在听音乐",      StatusType.IDLE,    System.currentTimeMillis()),
    PresenceState(8, "等你很久了",    StatusType.ACTIVE,  System.currentTimeMillis()),
    PresenceState(9, "—",            StatusType.OFFLINE, System.currentTimeMillis()),
)
