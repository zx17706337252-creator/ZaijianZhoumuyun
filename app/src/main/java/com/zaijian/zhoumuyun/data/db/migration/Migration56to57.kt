package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v56 → v57：公馆/书架头像独立化——此前两者共用同一张原图（avatarUrl）
 * 和同一套裁剪参数（avatarCropTall*）。现在拆成三张完全独立的头像，
 * 新增公馆专用原图字段 avatarUrlTall，以及书架专用的原图+裁剪参数四个字段。
 */
internal val MIGRATION_56_57 = object : Migration(56, 57) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `avatarUrlTall` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `avatarUrlShelf` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `avatarCropShelfOffsetX` REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `avatarCropShelfOffsetY` REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `avatarCropShelfScale` REAL NOT NULL DEFAULT 1")
    }
}
