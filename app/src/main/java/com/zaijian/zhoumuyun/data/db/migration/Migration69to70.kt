package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v69 → v70：新增 `memory_tags` 表（Window A-1 · L2 记忆索引层）。
 *
 * 为每条记忆建立结构化标签索引，支持按 tag 快速定位——比 FTS4 全文检索
 * 更精准、更快。检索路由优先查 L2（tag 精确匹配），未命中再降级到
 * L1（FTS4 全文匹配）。
 *
 * 纯 `CREATE TABLE` + 索引，不涉及任何已有表改动，参照
 * [Migration68to69] 的写法，风险最低。
 *
 * ## 列类型严格对照 MemoryTagEntity
 *
 *   `String`  → TEXT NOT NULL，`Int`/`Long` → INTEGER NOT NULL。
 * 索引名严格对照 Room 自动生成格式 `index_<表名>_<列名...>`。
 */
internal val MIGRATION_69_70 = object : Migration(69, 70) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_tags` (
                `id` TEXT NOT NULL,
                `memoryId` TEXT NOT NULL,
                `characterId` INTEGER NOT NULL,
                `tag` TEXT NOT NULL,
                `weight` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_tags_characterId` ON `memory_tags` (`characterId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_tags_characterId_tag` ON `memory_tags` (`characterId`, `tag`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_tags_memoryId` ON `memory_tags` (`memoryId`)")
    }
}
