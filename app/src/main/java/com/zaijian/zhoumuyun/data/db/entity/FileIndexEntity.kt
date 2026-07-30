package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 文件索引主表（file_search 用）。
 *
 * 不存文件本身，只存路径 + 可搜索文本 + 元数据。
 * filePath 用 vault 相对路径（如 "vault/personal/1/notes.pdf"）做主键，
 * 改名/移动时旧记录先删再插（主键变），覆盖写入用 upsert（INSERT OR REPLACE）。
 */
@Entity(
    tableName = "file_index",
    indices = [
        Index("fileType"),
        Index("indexedAt"),
    ],
)
data class FileIndexEntity(
    @PrimaryKey val filePath: String,
    val fileName: String,
    val fileType: String,        // pdf/image/audio/video/docx/txt/md/...
    val extractedText: String?,  // PDF/docx 等可提取文本全文，图片/音视频为 null
    val sizeBytes: Long,
    val createdAt: Long,
    val indexedAt: Long,
)

/**
 * file_index 的 FTS4 外部内容虚拟表。
 *
 * 使用 @Fts4(contentEntity = FileIndexEntity::class)，
 * Room 自动管理 content 同步触发器，DAO 无需手动 insert/delete FTS 行。
 * JOIN 条件用 fi.rowid = fts.rowid。
 */
@Fts4(contentEntity = FileIndexEntity::class)
@Entity(tableName = "file_index_fts")
data class FileIndexFtsEntity(
    val fileName: String,
    val extractedText: String?,
)
