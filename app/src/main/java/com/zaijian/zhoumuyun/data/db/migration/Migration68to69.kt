package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v68 → v69：新增 `skills` 与 `skill_edit_log` 两张表（Window C · Agent 自主技能系统）。
 *
 * 见《Window C 技能系统设计方案 v1.2》§1 / §4.1 / §7。纯 `CREATE TABLE` + 索引，
 * 不涉及任何已有表改动，参照 [Migration67to68] 的写法，风险最低，可独立先行。
 *
 * ## 为什么走独立表（而非塞进 memories 表）
 *
 * 设计方案 §7 把"独立表 vs 共用记忆表"列为【待 Window A 对齐】的开放项。核实 Window A
 * 实际交付的存储底座后拍板走**独立表**：本工程 `AppDatabase` 里每类知识/产物都是独立
 * 表 + 独立 DAO + 独立迁移（Window B 的 `agent_activity_events` 即 [Migration67to68]
 * 同款做法），而 `usageCount`/`successCount`/`version`/`lastUsedAt` 这类效果追踪字段
 * 是技能特有的，塞进 `memories` 表会让记忆 schema 膨胀。这与 §7 的设计倾向一致。
 *
 * ## 列类型/可空性严格对照实体
 *
 *   `String`  → TEXT NOT NULL，`String?` → TEXT（可空），
 *   `Int`/`Long` → INTEGER NOT NULL，`Long?` → INTEGER（可空）。
 * Kotlin 默认值（`= 0`/`= 1`/`= null`）不落为 SQL DEFAULT，列仅 NOT NULL，
 * 由写入侧在 Kotlin 层提供值——与 [Migration67to68] 注释同一约束。
 * 索引名严格对照 Room 自动生成格式 `index_<表名>_<列名...>`，保证
 * `validateMigration()` 逐索引比对通过，与 `@Entity(indices=[...])` 一一对应。
 */
internal val MIGRATION_68_69 = object : Migration(68, 69) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ── skills 主表 ──────────────────────────────────────────
        // 列顺序与 SkillEntity 字段声明顺序一致（Room 导出 schema 按声明顺序排列）。
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `skills` (
                `id` TEXT NOT NULL,
                `characterId` INTEGER NOT NULL,
                `name` TEXT NOT NULL,
                `shortDescriptor` TEXT NOT NULL,
                `fullContent` TEXT NOT NULL,
                `category` TEXT,
                `status` TEXT NOT NULL,
                `sourceType` TEXT NOT NULL,
                `version` INTEGER NOT NULL,
                `usageCount` INTEGER NOT NULL,
                `successCount` INTEGER NOT NULL,
                `failureCount` INTEGER NOT NULL,
                `lastUsedAt` INTEGER,
                `relatedSkillIds` TEXT,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_skills_characterId` ON `skills` (`characterId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_skills_characterId_status` ON `skills` (`characterId`, `status`)")

        // ── skill_edit_log 变更日志表 ────────────────────────────
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `skill_edit_log` (
                `id` TEXT NOT NULL,
                `skillId` TEXT NOT NULL,
                `changeSummary` TEXT NOT NULL,
                `actor` TEXT NOT NULL,
                `reason` TEXT,
                `timestamp` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_skill_edit_log_skillId` ON `skill_edit_log` (`skillId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_skill_edit_log_skillId_timestamp` ON `skill_edit_log` (`skillId`, `timestamp`)")
    }
}
