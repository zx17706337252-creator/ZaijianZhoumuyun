package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

// ─────────────────────────────────────────────────────────────
//  PregnancyPendingQuestionEntity — 孕期共设"待确认问题"追踪表
//                                    （D3，v23→v24 新建）
//
//  D3 问答配对状态机用：AI 问出一个问题后，先写入本表一行，
//  等用户在后续消息里给出回答，再由配对逻辑读取本表拿到
//  questionType / slotIndex / questionText，写入正式的
//  PregnancyAnswerEntity，随后清空（或覆盖）本表对应行。
//
//  单行覆盖写：每个母亲角色同一时间只可能有一个"待确认问题"，
//  以 motherCharacterId 作为主键，新问题直接覆盖旧问题
//  （Dao 用 OnConflictStrategy.REPLACE 实现 upsert）。
// ─────────────────────────────────────────────────────────────

@Entity(tableName = "pregnancy_pending_question")
data class PregnancyPendingQuestionEntity(
    /** 单行覆盖写：每个母亲角色固定一行，新问题覆盖旧问题 */
    @PrimaryKey val motherCharacterId: Int,
    /** 问题类型（存 PregnancyQuestionType.name） */
    val questionType: String,
    /** 槽位序号：WORLDVIEW / PERSONA 为 0 或 1，NAME_PREF / WORRY 固定 0 */
    val slotIndex: Int,
    /** AI 这次实际问出的问题原文（用于日志/调试，也供配对逻辑回填问答记录） */
    val questionText: String,
    /** 提问时间戳 */
    val askedAt: Long,
)
