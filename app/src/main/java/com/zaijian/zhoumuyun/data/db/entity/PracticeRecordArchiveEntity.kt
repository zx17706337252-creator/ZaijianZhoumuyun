package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 修炼记录归档表（P6 专长进化系统 · 蒸馏后的冷存储）
 *
 * PracticeRecordEntity.digestStatus 变为 "DIGESTED" 时，完整原文从主表
 * 迁移到这里。这张表只做归档查询用（专长档案页点开历史记录时反查），
 * 不参与任何 Prompt 注入、不参与日常蒸馏判断的主路径查询——
 * 这是"信息不丢失但不占用注意力"的具体落地：对用户而言原文仍然"看得到"，
 * 对模型和日常业务逻辑而言它已经"不在场"。
 *
 * 没有索引以外的额外字段或查询需求，保持表结构极简。
 */
@Entity(tableName = "practice_records_archive")
data class PracticeRecordArchiveEntity(
    /** 对应原 PracticeRecordEntity.id，一对一关系 */
    @PrimaryKey val recordId: String,

    /** 蒸馏前的完整原文 */
    val fullContent: String,

    val archivedAt: Long,
)
