package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

// ─────────────────────────────────────────────────────────────
//  Project Knowledge — Phase 31
//
//  每个 Project 可绑定若干知识条目（文字片段、链接摘要、文件全文）。
//  全文注入策略：content 不截断，完整写入，靠缓存命中控制成本。
//  charCount 冗余字段，避免 UI 展示时每次 count content 长度。
// ─────────────────────────────────────────────────────────────

enum class KnowledgeSource {
    MANUAL,       // 用户手动输入
    FILE_IMPORT,  // 文件导入（PDF/MD/TXT/DOCX）
    URL_IMPORT,   // URL 网页摘要
    AUTO_EXTRACT, // 对话中 AI 自动提取
}

@Entity(
    tableName = "project_knowledge",
    indices = [
        Index("projectId"),
        Index("characterId"),
        Index("createdAt"),
    ],
)
data class ProjectKnowledgeEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    /** 关联角色（可空，null = 全项目共享） */
    val characterId: String? = null,
    val title: String = "",
    val content: String,
    val source: String = KnowledgeSource.MANUAL.name,
    /** 重要度 1-5，影响 Prompt 注入优先级 */
    val importance: Int = 3,
    /** 字数缓存，UI 展示用，避免每次 count content.length */
    val charCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

// FTS4 虚拟表（Room 自动管理内容触发器）
@Fts4(contentEntity = ProjectKnowledgeEntity::class)
@Entity(tableName = "project_knowledge_fts")
data class ProjectKnowledgeFtsEntity(
    val title: String,
    val content: String,
)
