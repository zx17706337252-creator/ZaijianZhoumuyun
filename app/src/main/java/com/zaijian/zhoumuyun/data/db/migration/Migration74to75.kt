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
        // content=`file_index` → 外部内容表。注意：迁移路径（onUpgrade）Room 不会自动补 content 同步触发器，
        // 触发器只在全新安装（onCreate）由 Room 生成。必须在此手工补建，否则 validateMigration 比对触发器缺失
        // 会抛 IllegalStateException（release 下被 fallbackToDestructiveMigration 静默清库），且 FTS 永不随主表同步。
        db.execSQL("""
            CREATE VIRTUAL TABLE IF NOT EXISTS `file_index_fts`
            USING fts4(
                content=`file_index`,
                `fileName`,
                `extractedText`
            )
        """.trimIndent())
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_file_index_fts_BEFORE_UPDATE BEFORE UPDATE ON `file_index` BEGIN DELETE FROM `file_index_fts` WHERE `docid`=OLD.`rowid`; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_file_index_fts_BEFORE_DELETE BEFORE DELETE ON `file_index` BEGIN DELETE FROM `file_index_fts` WHERE `docid`=OLD.`rowid`; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_file_index_fts_AFTER_UPDATE AFTER UPDATE ON `file_index` BEGIN INSERT INTO `file_index_fts`(`docid`, `fileName`, `extractedText`) VALUES (NEW.`rowid`, NEW.`fileName`, NEW.`extractedText`); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_file_index_fts_AFTER_INSERT AFTER INSERT ON `file_index` BEGIN INSERT INTO `file_index_fts`(`docid`, `fileName`, `extractedText`) VALUES (NEW.`rowid`, NEW.`fileName`, NEW.`extractedText`); END")
    }
}
