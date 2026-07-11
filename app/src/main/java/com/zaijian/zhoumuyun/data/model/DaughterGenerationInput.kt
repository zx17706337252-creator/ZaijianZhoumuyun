package com.zaijian.zhoumuyun.data.model

import com.zaijian.zhoumuyun.data.db.entity.PregnancyQuestionType

// ─────────────────────────────────────────────────────────────
//  DaughterGenerationInput — D4 生成器的完整输入包（D4）
//
//  D4 生成器（DaughterCharacterGenerator）接收这个数据包，
//  组装 Prompt，调用 LLM，解析返回的 JSON，写入数据库。
//
//  来源：
//  - motherConfig        → CharacterConfig（从 DefaultCharacters 读取）
//  - lockedAnswers       → PregnancyAnswerRepository.getLockedAnswers()
//  - differenceTypes     → 至少 2 项，由 D4 生成器根据母亲角色卡预设
// ─────────────────────────────────────────────────────────────

data class DaughterGenerationInput(

    /** 母亲完整角色配置（含 identityConfig 和 initialState） */
    val motherConfig: CharacterConfig,

    /**
     * D3 锁定的 6 个槎位答案。
     * key = "${questionType}_${slotIndex}"（如 "WORLDVIEW_0"、"PERSONA_1"）
     * value = 用户最终回答的原文
     */
    val lockedAnswers: Map<String, String>,

    /**
     * 强制差异规则：至少命中 2 项。
     * 由 D4 生成器根据母亲角色 ID 预设，确保女儿不是母亲的简单复制。
     * 例：蒂法 → [REVERSAL(话量/开放), AMPLIFY(coreWound外显)]
     */
    val differenceTypes: List<DaughterDifferenceType>,
)

/**
 * 槎位答案 key 的构造辅助函数。
 * 与 PregnancyAnswerEntity 的 (questionType, slotIndex) 对应。
 */
fun slotKey(questionType: PregnancyQuestionType, slotIndex: Int): String =
    "${questionType.name}_$slotIndex"
