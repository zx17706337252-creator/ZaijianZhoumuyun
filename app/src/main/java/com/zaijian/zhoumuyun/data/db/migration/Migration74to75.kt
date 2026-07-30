package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 74 → 75：文件搜索 · 新增 file_index / file_index_fts 表。
 *
 * 纯新增表，不改动任何既有 schema。file_index 存文件索引记录，
 * file_index_fts 是 FTS4 外部内容虚拟表（content=`file_index`），
 * Room 通过 @Fts4(contentEntity) 自动管理同步触发器，无需手写。
 */
internal val MIGRATION_74_75 = object : Migration(74, 75) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // file_index 主表
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `file_index` (
                `filePath` TEXT NOT NULL PRIMARY KEY,
                `fileName` TEXT NOT NULL,
                `fileType` TEXT NOT NULL,
                `extractedText` TEXT,
                `sizeBytes` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `indexedAt` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_file_index_fileType` ON `file_index` (`fileType`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_file_index_indexedAt` ON `file_index` (`indexedAt`)")

        // file_index_fts FTS4 外部内容虚拟表
        // content=`file_index` → 外部内容表，Room 自动管理 content 触发器
        db.execSQL("""
            CREATE VIRTUAL TABLE IF NOT EXISTS `file_index_fts`
            USING fts4(
                content=`file_index`,
                `fileName`,
                `extractedText`,
                tokenize=unicode61
            )
        """.trimIndent())
    }
}
