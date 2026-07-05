package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.zaijian.zhoumuyun.data.model.AttentionState
import com.zaijian.zhoumuyun.data.model.CharacterStateLayer
import com.zaijian.zhoumuyun.data.model.EmotionalState
import com.zaijian.zhoumuyun.data.model.EmotionType
import com.zaijian.zhoumuyun.data.model.FearType
import com.zaijian.zhoumuyun.data.model.HiddenState
import com.zaijian.zhoumuyun.data.model.MaskType
import com.zaijian.zhoumuyun.data.model.MotivationalState
import com.zaijian.zhoumuyun.data.model.NeedType
import com.zaijian.zhoumuyun.data.model.PublicState
import com.zaijian.zhoumuyun.data.model.SocialMode

// ─────────────────────────────────────────────────────────────
//  CharacterStateEntity
//  Phase 4 新增：将 CharacterStateLayer 持久化到 Room。
//
//  ★ socialMode 不入库（实时计算，见 V3 Phase 1.3 持久化范围说明）。
//  ★ 字段全部使用基础类型（Int / String / String?），避免 Room 需要
//    额外 TypeConverter 的情况：枚举存 .name，toDomain() 时 valueOf 还原。
//  ★ lastUpdated 用于未来"状态过期"判断（Phase 5 扩展）。
// ─────────────────────────────────────────────────────────────

@Entity(tableName = "character_state")
data class CharacterStateEntity(

    @PrimaryKey
    val characterId: Int,

    // ── PublicState（socialMode 不入库，实时计算）──────────────
    val maskType: String,           // MaskType.name
    val talkativeness: Int,
    val openness: Int,
    val patience: Int,
    val vigilance: Int,

    // ── EmotionalState ────────────────────────────────────────
    val primaryEmotion: String,     // EmotionType.name
    val secondaryEmotion: String?,  // EmotionType.name 或 null
    val intensity: Int,
    val emotionalFatigue: Int,
    val emotionalStability: Int,

    // ── MotivationalState ─────────────────────────────────────
    val currentNeed: String,        // NeedType.name
    val currentGoal: String,
    val desireStrength: Int,
    val urgency: Int,
    val resistance: Int,

    // ── HiddenState ───────────────────────────────────────────
    val currentFear: String,        // FearType.name
    val secretDesire: String,
    val exposureRisk: Int,
    val selfControl: Int,
    val emotionalSuppression: Int,

    // ── AttentionState ────────────────────────────────────────
    val focusTarget: String,
    val focusStrength: Int,
    val observationLevel: Int,
    val concernLevel: Int,

    val lastUpdated: Long = System.currentTimeMillis(),
)

// ─────────────────────────────────────────────────────────────
//  Entity → Domain
//
//  socialMode 统一默认 ONE_ON_ONE；调用方（ChatViewModel）在组装
//  Prompt 前通过 CharacterStateRepository.applySocialMode() 实时覆盖。
// ─────────────────────────────────────────────────────────────

fun CharacterStateEntity.toDomain(): CharacterStateLayer = CharacterStateLayer(
    publicState = PublicState(
        currentMask = MaskType.valueOf(maskType),
        socialMode = SocialMode.ONE_ON_ONE,   // 不持久化，调用方按场景覆盖
        talkativeness = talkativeness,
        openness = openness,
        patience = patience,
        vigilance = vigilance,
    ),
    emotionalState = EmotionalState(
        primaryEmotion = EmotionType.valueOf(primaryEmotion),
        secondaryEmotion = secondaryEmotion?.let { EmotionType.valueOf(it) },
        intensity = intensity,
        emotionalFatigue = emotionalFatigue,
        emotionalStability = emotionalStability,
    ),
    motivationalState = MotivationalState(
        currentNeed = NeedType.valueOf(currentNeed),
        currentGoal = currentGoal,
        desireStrength = desireStrength,
        urgency = urgency,
        resistance = resistance,
    ),
    hiddenState = HiddenState(
        currentFear = FearType.valueOf(currentFear),
        secretDesire = secretDesire,
        exposureRisk = exposureRisk,
        selfControl = selfControl,
        emotionalSuppression = emotionalSuppression,
    ),
    attentionState = AttentionState(
        focusTarget = focusTarget,
        focusStrength = focusStrength,
        observationLevel = observationLevel,
        concernLevel = concernLevel,
    ),
)

// ─────────────────────────────────────────────────────────────
//  Domain → Entity
//
//  socialMode 无对应列，不写入。
// ─────────────────────────────────────────────────────────────

fun CharacterStateLayer.toEntity(characterId: Int): CharacterStateEntity =
    CharacterStateEntity(
        characterId = characterId,
        // PublicState（socialMode 跳过）
        maskType = publicState.currentMask.name,
        talkativeness = publicState.talkativeness,
        openness = publicState.openness,
        patience = publicState.patience,
        vigilance = publicState.vigilance,
        // EmotionalState
        primaryEmotion = emotionalState.primaryEmotion.name,
        secondaryEmotion = emotionalState.secondaryEmotion?.name,
        intensity = emotionalState.intensity,
        emotionalFatigue = emotionalState.emotionalFatigue,
        emotionalStability = emotionalState.emotionalStability,
        // MotivationalState
        currentNeed = motivationalState.currentNeed.name,
        currentGoal = motivationalState.currentGoal,
        desireStrength = motivationalState.desireStrength,
        urgency = motivationalState.urgency,
        resistance = motivationalState.resistance,
        // HiddenState
        currentFear = hiddenState.currentFear.name,
        secretDesire = hiddenState.secretDesire,
        exposureRisk = hiddenState.exposureRisk,
        selfControl = hiddenState.selfControl,
        emotionalSuppression = hiddenState.emotionalSuppression,
        // AttentionState
        focusTarget = attentionState.focusTarget,
        focusStrength = attentionState.focusStrength,
        observationLevel = attentionState.observationLevel,
        concernLevel = attentionState.concernLevel,
    )
