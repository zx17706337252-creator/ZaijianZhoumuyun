package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration v52 → v53：批次1 数据层修复
 *
 * 本次变更汇总：
 *   - project_members.characterId：Entity 类型从 String 纠正为 Int（与 SQL 列 INTEGER 一致），
 *     实际的 SQLite 列类型从未变化（建表起就是 INTEGER），无需重建表。
 *   - 新增索引（projects / tasks / memories / messages / roundtable_messages /
 *     competition_rounds / workflow_jobs / scheduled_jobs 共 8 张表）。
 *
 * 注意事项：
 *   - 所有索引创建均使用 IF NOT EXISTS，迁移幂等。
 *   - 不涉及任何表结构变更（无 ALTER TABLE / CREATE TABLE），仅新增索引。
 *   - 老版本数据库（v52 及以下）的 project_members.characterId 列已是 INTEGER 类型，
 *     本次迁移仅修正 Room Entity 层面的类型声明，不需要数据迁移。
 */
internal val MIGRATION_52_53 = object : Migration(52, 53) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ── projects：status + updatedAt 复合索引 ──────────────
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_projects_status_updatedAt` ON `projects` (`status`, `updatedAt`)")

        // ── tasks：projectId + source + createdAt 复合索引 ─────
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_projectId_source_createdAt` ON `tasks` (`projectId`, `source`, `createdAt`)")

        // ── memories：4 个新索引 ───────────────────────────────
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_ftsRowId` ON `memories` (`ftsRowId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_characterId_scope` ON `memories` (`characterId`, `scope`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_characterId_domain_goalId_isLocked` ON `memories` (`characterId`, `domain`, `goalId`, `isLocked`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_isCore_isEternal_lastAccessedAt` ON `memories` (`isCore`, `isEternal`, `lastAccessedAt`)")

        // ── messages：createdAt 索引 ───────────────────────────
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_createdAt` ON `messages` (`createdAt`)")

        // ── roundtable_messages：speakerId + createdAt 索引 ────
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_roundtable_messages_speakerId_createdAt` ON `roundtable_messages` (`speakerId`, `createdAt`)")

        // ── competition_rounds：judgeCharacterId + createdAt 索引
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_competition_rounds_judgeCharacterId_createdAt` ON `competition_rounds` (`judgeCharacterId`, `createdAt`)")

        // ── workflow_jobs：isReported + status 索引 ────────────
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_workflow_jobs_isReported_status` ON `workflow_jobs` (`isReported`, `status`)")

        // ── scheduled_jobs：cloudSynced + characterId/enabled/nextRunAt 索引 ──
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_scheduled_jobs_cloudSynced` ON `scheduled_jobs` (`cloudSynced`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_scheduled_jobs_characterId_enabled_nextRunAt` ON `scheduled_jobs` (`characterId`, `enabled`, `nextRunAt`)")
    }
}