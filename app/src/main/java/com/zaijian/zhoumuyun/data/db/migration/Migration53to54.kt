package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration v53 → v54：W1 数据库与迁移完整性审查修复
 *
 * 本次变更汇总（对应《W1 数据库与迁移完整性审查报告》问题1/2/3）：
 *
 *   - 问题1（僵尸列）：memories 表自 v1 建表起存在 `decayFactor` 列
 *     （REAL NOT NULL DEFAULT 1.0），但 MemoryEntity 从未声明该字段，
 *     全项目无任何代码读写。SQLite 不支持 ALTER TABLE DROP COLUMN，
 *     采用"建新表 → 拷贝数据 → 删旧表 → 改名"的标准重建流程移除该列。
 *
 *   - 问题2（缺失索引，随重建一并处理）：MemoryEntity 当前声明的 13 个索引中，
 *     以下 6 个从未在迁移链中显式创建（新装用户由 Room 建表自动生成，
 *     不受影响；老版本升级用户缺失，其中 3 个原有单列替代索引已在
 *     v46→v47 被当作孤儿索引清理，现无任何索引覆盖）：
 *       index_memories_characterId_domain
 *       index_memories_characterId_importance
 *       index_memories_characterId_isCore
 *       index_memories_projectId
 *       index_memories_createdAt
 *       index_memories_lastAccessedAt
 *     表重建后一次性重新创建 MemoryEntity 声明的全部 13 个索引。
 *
 *   - 问题3：character_goals 表的 `relatedProjectId` 列自 v2→v3 建表起
 *     从未创建索引（当时只建了 characterId 与 isActive 两个），但
 *     CharacterGoalDao 中存在按 relatedProjectId 查询的 SQL，
 *     现补建该索引。
 *
 * 风险与验证：
 *   - memories 表重建：拷贝时显式列出目标表的全部 18 列（不含 decayFactor），
 *     使用 INSERT INTO ... SELECT 按列名而非 SELECT * 以避免列顺序问题。
 *   - memories_fts 虚拟表及其与主表的 rowid 关联不受影响（重建只针对主表，
 *     rowid 生成方式不变，ftsRowId 列的值随行一起拷贝）。
 *   - 所有 CREATE INDEX 均使用 IF NOT EXISTS，character_goals 索引补建幂等。
 *   - 本迁移不改变任何业务数据的语义，仅去除死列与补齐索引。
 */
internal val MIGRATION_53_54 = object : Migration(53, 54) {
    override fun migrate(db: SupportSQLiteDatabase) {

        // ────────────────────────────────────────────────────
        // 问题1 + 问题2：memories 表重建（去 decayFactor + 补全 13 索引）
        // ────────────────────────────────────────────────────

        // 1. 建新表：与当前 MemoryEntity 完全一致的 18 列，不含 decayFactor
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memories_new` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `characterId` INTEGER NOT NULL,
                `domain` TEXT NOT NULL,
                `scope` TEXT NOT NULL DEFAULT 'PERSONAL',
                `roundtableId` TEXT,
                `content` TEXT NOT NULL,
                `importance` INTEGER NOT NULL,
                `keywords` TEXT NOT NULL,
                `sourceEventId` TEXT,
                `isCore` INTEGER NOT NULL DEFAULT 0,
                `isEternal` INTEGER NOT NULL DEFAULT 0,
                `isLocked` INTEGER NOT NULL DEFAULT 0,
                `goalId` TEXT,
                `projectId` TEXT,
                `accessCount` INTEGER NOT NULL DEFAULT 0,
                `ftsRowId` INTEGER NOT NULL DEFAULT 0,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `lastAccessedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // 2. 按列名显式拷贝数据（不选 decayFactor，不依赖列顺序）
        db.execSQL(
            """
            INSERT INTO `memories_new` (
                `id`, `characterId`, `domain`, `scope`, `roundtableId`, `content`,
                `importance`, `keywords`, `sourceEventId`, `isCore`, `isEternal`,
                `isLocked`, `goalId`, `projectId`, `accessCount`, `ftsRowId`,
                `createdAt`, `updatedAt`, `lastAccessedAt`
            )
            SELECT
                `id`, `characterId`, `domain`, `scope`, `roundtableId`, `content`,
                `importance`, `keywords`, `sourceEventId`, `isCore`, `isEternal`,
                `isLocked`, `goalId`, `projectId`, `accessCount`, `ftsRowId`,
                `createdAt`, `updatedAt`, `lastAccessedAt`
            FROM `memories`
            """.trimIndent()
        )

        // 3. 删旧表、新表改名
        db.execSQL("DROP TABLE `memories`")
        db.execSQL("ALTER TABLE `memories_new` RENAME TO `memories`")

        // 4. 重建 MemoryEntity 声明的全部 13 个索引
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_characterId` ON `memories` (`characterId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_characterId_domain` ON `memories` (`characterId`, `domain`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_characterId_importance` ON `memories` (`characterId`, `importance`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_characterId_isCore` ON `memories` (`characterId`, `isCore`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_characterId_isEternal` ON `memories` (`characterId`, `isEternal`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_projectId` ON `memories` (`projectId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_createdAt` ON `memories` (`createdAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_lastAccessedAt` ON `memories` (`lastAccessedAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_roundtableId` ON `memories` (`roundtableId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_ftsRowId` ON `memories` (`ftsRowId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_characterId_scope` ON `memories` (`characterId`, `scope`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_characterId_domain_goalId_isLocked` ON `memories` (`characterId`, `domain`, `goalId`, `isLocked`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_isCore_isEternal_lastAccessedAt` ON `memories` (`isCore`, `isEternal`, `lastAccessedAt`)")

        // ────────────────────────────────────────────────────
        // 问题3：character_goals.relatedProjectId 补建索引
        // ────────────────────────────────────────────────────
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_character_goals_relatedProjectId` ON `character_goals` (`relatedProjectId`)")
    }
}
