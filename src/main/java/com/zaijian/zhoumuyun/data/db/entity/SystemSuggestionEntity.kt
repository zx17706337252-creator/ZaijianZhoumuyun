package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 系统优化建议实体（P6 专长进化系统 · 第8节 AI 自我提案机制）
 *
 * 每完成若干次"阶段摘要并入styleNotes"的合并周期，触发一次低频的建议生成。
 * 这张表只存"建议"，不存在任何自动应用路径——status 字段只会被用户操作
 * 改变（ADOPTED/IGNORED），系统自身不会读取 status="PENDING" 的记录去
 * 自动调整任何配置常量。这是刻意的设计：AI 能分析自己的学习过程并提出看法，
 * 但游戏规则（蒸馏阈值、晋升门槛等）的修改权始终在用户手上。
 */
@Entity(
    tableName = "system_suggestions",
    indices = [
        Index(value = ["specialtyId"]),
        Index(value = ["specialtyId", "status"]),
    ]
)
data class SystemSuggestionEntity(
    @PrimaryKey val id: String,

    val characterId: Int,

    val specialtyId: String,

    /** 具体建议内容 */
    val content: String,

    /** 为什么这样建议（LLM给出的依据） */
    val reasoning: String,

    /**
     * 状态：
     *   "PENDING"  待用户查看，专长档案页"系统建议"角标会计数这类记录
     *   "ADOPTED"  用户已采纳（实际参数调整是用户去配置常量里手动改，本字段只做记录）
     *   "IGNORED"  用户已忽略
     */
    val status: String = "PENDING",

    val createdAt: Long,
)
