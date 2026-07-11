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
    //   avatarUrl：保留字段名兼容旧调用点，语义变为「原图路径」
    //     （不再是裁剪成品图）。
    //   avatarCropCircle*：详情页圆形头像的裁剪参数（沿用 AvatarCropDialog
    //     的 offset/scale 语义）。
    //   avatarCropTall*：公馆拱形 + 书架椭圆共用的竖长矩形裁剪参数。
    //     两个场景比例一致，共用一套参数，不再分别裁剪。
    val avatarUrl: String = "",
    val avatarCropCircleOffsetX: Float = 0f,
    val avatarCropCircleOffsetY: Float = 0f,
    val avatarCropCircleScale: Float = 1f,
    val avatarCropTallOffsetX: Float = 0f,
    val avatarCropTallOffsetY: Float = 0f,
    val avatarCropTallScale: Float = 1f,

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
)
