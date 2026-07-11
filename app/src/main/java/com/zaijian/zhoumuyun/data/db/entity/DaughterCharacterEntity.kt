package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

// ─────────────────────────────────────────────────────────────
//  DaughterCharacterEntity — 女儿角色完整人格存储（D4，v24→v25）
//
//  存储结构：三列 JSON，对应设计文档 v1.0 的三个顶层 key。
//  分列原因：StateLayer 由运行时引擎频繁更新（情绪/动机/注意力），
//  单独一列可以只 UPDATE state_layer_json，不重写 identity 和枚举词库。
//
//  主键：motherCharacterId（Int，与 pregnancy_answers 一致）
//  一位母亲同一时刻只有一个女儿，upsert 覆盖即可，不保留历史版本。
//  generatedAt 记录最后一次 D4 生成时间，generatorVersion 记录生成所用
//  Prompt 版本（v30→v31 新增），均用于调试和展示，不影响业务逻辑。
//
//  防御性要求（进对话前必须校验）：
//  - 三列均不能为空字符串
//  - identity_json 必须能解析出 persona 字段
//  - 空人格女儿不得进入对话（调用方强制检查，不在此层静默降级）
// ─────────────────────────────────────────────────────────────

@Entity(tableName = "daughter_character")
data class DaughterCharacterEntity(

    /**
     * 主键：母亲的 characterId（Int，与 PregnancyAnswerEntity.motherCharacterId 对应）。
     * 一位母亲对应唯一一条女儿记录，D4 重新生成时直接 upsert 覆盖。
     */
    @PrimaryKey
    val motherCharacterId: Int,

    /**
     * 女儿名字。
     * 从 NAME_PREF 槎位答案直接写入，独立存列便于快速读取，
     * 不需要解析 identity_json 就能拿到名字用于 UI 显示。
     */
    val daughterName: String,

    /**
     * CharacterIdentity 静态文本层（JSON）。
     * 包含 17 个字段：persona / speechStyle / attitudeToUser / boundaries /
     * coreBeliefs / coreWound / coreDesire / maskTrigger / privatePersona /
     * privateStyle / privateExamples / situationRules / deviationSignals /
     * likes / dislikes / relationships / relationAssumption / conflictStrategy。
     * 由 D4 生成器一次性填写，运行时不变。
     */
    val identityJson: String,

    /**
     * CharacterStateLayer 动态数值层（JSON）。
     * 包含 5 个子结构 24 个字段的初始值，以及当前运行时状态。
     * 由 D4 生成器从母亲 initialState 派生初始值，
     * 运行时由情绪引擎（PresenceEngine/EvaluationEngine）更新此列。
     * 更新时只写这一列，不碰 identity_json 和 custom_enums_json。
     */
    val stateLayerJson: String,

    /**
     * 专属枚举词库（JSON）。
     * 包含四套枚举：maskStates / emotionStates / needStates / fearStates，
     * 每套 4-6 个值，每个值有 key / label / description 三个字段。
     * description 直接用于 Prompt 注入（等价于母亲 enum 注释的文本）。
     * 由 D4 生成器生成，运行时不变。
     */
    val customEnumsJson: String,

    /**
     * D4 生成器最后一次写入的时间戳（毫秒）。
     * 用于调试和"女儿档案"展示，不影响业务逻辑。
     */
    val generatedAt: Long = System.currentTimeMillis(),

    /**
     * 生成本条记录所用的 D4 生成器/Prompt 版本号（v30→v31 新增）。
     *
     * 用途：纯回溯/调试字段——以后调整生成 Prompt 或做 A/B 测试时，
     * 能区分某个女儿当前的人格内容是哪一版生成逻辑产出的，不影响
     * 任何运行时业务逻辑（对话、Prompt 注入均不读取此字段）。
     * 取值见 [com.zaijian.zhoumuyun.data.manager.DaughterCharacterGenerator.GENERATOR_VERSION]。
     */
    val generatorVersion: String = "d4-v1",

    /**
     * 女儿自己的 characterId（D4 触发点接入 Part 4 新增，v27→v28）。
     *
     * 由 [com.zaijian.zhoumuyun.data.manager.DaughterIdAllocator.allocate] 分配，
     * 在 ChatViewModel.onIdentityRegister 回调里、identityDao.upsert() 成功后
     * 立即回填（见 DaughterCharacterDao.updateDaughterCharacterId）。
     *
     * 用途：ChatViewModel.sendMessage() 组装 CharacterConfig 时，
     * currentCharacterId（女儿自己的 ID，1000+）查不到预设角色，
     * 需要反过来用这个字段去 daughter_character 表按"女儿自己的 ID"查到
     * 对应的 motherCharacterId 这一行，取出 stateLayerJson/customEnumsJson
     * 拼出完整 CharacterConfig。
     *
     * 默认 null：D4 生成刚写库的瞬间还没分配号，注册成功后才回填。
     * 理论上不会有"长期为 null 但已在对话中使用"的女儿——注册回调
     * 是生成流程的最后一步，回填失败会在 Log 里留下痕迹（见 ChatViewModel）。
     */
    val daughterCharacterId: Int? = null,
)
