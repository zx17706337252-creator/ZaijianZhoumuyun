package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 角色 Identity 持久化表。
 * 与内存中的 CharacterIdentity 对应，一个角色一条记录。
 * Phase 7 建表，ProfileScreen → 角色管理 中可编辑。
 */
@Entity(tableName = "character_identity")
data class CharacterIdentityEntity(
    /** characterId 作为主键（1-9） */
    @PrimaryKey val characterId: Int,
    val persona: String = "",
    val speechStyle: String = "",
    val attitudeToUser: String = "",
    /** JSON 数组字符串，存储 boundaries 列表 */
    val boundariesJson: String = "[]",
    /** JSON 数组字符串，存储 coreBeliefs 列表 */
    val corebeliefsJson: String = "[]",

    // ── Phase 14（zaijian）新增：三层内核字段 ──────────────────────
    val coreWound: String = "",
    val coreDesire: String = "",
    val maskTrigger: String = "",
    val privatePersona: String = "",
    val privateStyle: String = "",
    val privateExamples: String = "",
    val situationRules: String = "",
    val deviationSignals: String = "",

    // ── 附加（NyxChat V18 A.1/A.2）：死字段修复 ───────────────────
    /**
     * 喜好（A.1 修复）：填写后注入 Identity Layer，夹在 coreBeliefs 与 coreWound 之间。
     * 示例：「清晨的咖啡香气、独处时的安静、有人记住她的细节」
     */
    val likes: String = "",
    /**
     * 厌恶（A.1 修复）：填写后注入 Identity Layer，紧接 likes 之后。
     * 示例：「被人打断、无意义的客套、被当成工具」
     */
    val dislikes: String = "",
    /**
     * 人际关系行为逻辑（A.2 修复）：从 basicInfo 独立出来，作为行为指令块注入，
     * 位于 situationRules 之前。写法：「在X面前：她会…」
     */
    val relationships: String = "",

    // ── v31→v32 新增：头像本地路径 ──────────────────────────────
    // v45→v46 重新设计：头像改为「存原图 + 多套裁剪参数」，不再是单一
    // 512×512 成品图。原因见 2026-07-03 对话：旧方案存的是按圆形裁剪好
    // 的正方形成品图，塞进公馆拱形（细高比例）容器后，超出正方形之外
    // 的区域没有画面内容，只显示纯色——形状不匹配是设计问题，不是渲染
    // bug，修不出来，需要重新设计。
    //   avatarUrl：仅圆形（详情页）使用的原图路径。
    //   avatarCropCircle*：详情页圆形头像的裁剪参数（沿用 AvatarCropDialog
    //     的 offset/scale 语义）。
    //   avatarCropTall*：公馆拱形 + 书架椭圆共用的竖长矩形裁剪参数。
    //     两个场景比例一致，共用一套参数，不再分别裁剪。
    //
    // ── v56→v57 再次重新设计：公馆/书架头像独立化 ─────────────────
    // 此前公馆与书架共用同一张原图（avatarUrl）和同一套裁剪参数
    // （avatarCropTall*）。现在拆成三处完全独立——圆形（详情页）、
    // 公馆、书架各自单独选图、单独裁剪，互不影响。
    //   avatarUrlTall：新增，公馆专用原图路径。
    //   avatarCropTall*：字段名不变，语义收窄为「仅公馆」裁剪参数。
    //   avatarUrlShelf / avatarCropShelf*：全部新增，书架专用原图路径
    //     + 裁剪参数。取景框形状/比例沿用公馆同一套（TALL_RECT，
    //     2.171 高宽比），不单独定义新常量。
    val avatarUrl: String = "",
    val avatarCropCircleOffsetX: Float = 0f,
    val avatarCropCircleOffsetY: Float = 0f,
    val avatarCropCircleScale: Float = 1f,
    val avatarUrlTall: String = "",
    val avatarCropTallOffsetX: Float = 0f,
    val avatarCropTallOffsetY: Float = 0f,
    val avatarCropTallScale: Float = 1f,
    val avatarUrlShelf: String = "",
    val avatarCropShelfOffsetX: Float = 0f,
    val avatarCropShelfOffsetY: Float = 0f,
    val avatarCropShelfScale: Float = 1f,

    // ── v26→v27 新增：名字持久化 ─────────────────────────────────
    val name: String = "",

    // ── v18 新增：关系结构层 ─────────────────────────────────────
    val relationAssumption: String = "",
    val conflictStrategy: String = "",

    /** 若非空，完全替换 Identity Layer 自动组装结果 */
    val customSystemPrompt: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),

    // ── Soul/Memory/User 三模块（P5 Step2）────────────────────
    val soulNote: String = "",
    val soulNoteBackup: String = "",
    val narrativeMemory: String = "",
    val narrativeMemoryBackup: String = "",
    val userImpression: String = "",
    val userImpressionBackup: String = "",
    val lastEditedNoteField: String? = null,
    val lastEditedNoteAt: Long = 0,

    // ── v58→v59 新增：用户身份设定（性别 + 关系称谓，按角色区分）─────
    // 修复问题：提示词此前从未告知模型用户性别，导致角色统一用"她"称呼用户
    // （v1.36 三项修复方案问题3）。按角色单独配置，不做全局一份——不同角色
    // 与用户的关系性质不同（恋人/家人/朋友…），称谓自然也不同。
    //   userGender：MALE / FEMALE / UNSPECIFIED。默认 MALE——作为存量角色
    //     （此前从未配置过这个字段）的全局典型值，避免继续空值导致模型瞎猜；
    //     UNSPECIFIED 是用户显式选择"不指定"，与"没设置"不同，不会被当作默认值。
    //     用 String 而非 Kotlin enum 直接映射 Room 列，转换逻辑见
    //     domain/UserIdentity.kt 的 UserGenderType（与 ChatTagParser 里
    //     MoodType 的字符串<->枚举转换是同一风格）。
    //   userRoleLabelPrivate/Public：角色对用户的称谓（"老公"/"爸爸"/"朋友"…），
    //     支持预设 + 自定义文本。Private 是私聊场景用的称谓；Public 是圆桌
    //     （有其他角色在场）场景用的称谓，留空时 Prompt 组装层会自动回退到
    //     Private，不需要在这里存冗余值。
    //   publicPrivacyReason：可选，说明"公开场合为什么不用私下称谓"，供模型
    //     组织语言时有情理依据（比如"其他人还不知道你们的关系"），避免圆桌
    //     场景突然换称呼显得生硬。只有 Public 与 Private 不同且都非空时才有意义。
    val userGender: String = "MALE",
    val userRoleLabelPrivate: String = "",
    val userRoleLabelPublic: String = "",
    val publicPrivacyReason: String = "",

    // ── v75→v76 新增：角色忠诚锁定·owner 身份特征（方案 v1.5 第 1.2 节）─────
    // 机制一 IdentityGuard 的判定依据。存 JSON 数组字符串（与 boundariesJson/
    // corebeliefsJson 同风格），运行时由调用方解析为 List<String> 后构造
    // OwnerIdentityProfile（见 domain/IdentityGuard.kt）。
    //   ownerAliasesJson：owner 的合法自称，如 ["范佩西","小范"]。
    //     Migration 默认填当前 owner 昵称（单元素数组）。
    //   characterCallsOwnerJson：角色对 owner 的固定称呼，如 ["主人","老板"]。
    //     Migration 默认从角色卡已有的 userRoleLabelPrivate 回填（若有）。
    val ownerAliasesJson: String = "[]",
    val characterCallsOwnerJson: String = "[]",
)
