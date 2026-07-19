package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v61 → v62：批次0修复——补建 12 张表 31 个历史遗留缺失索引。
 *
 * ## 背景
 *
 * MIGRATION_46_47（v46→v47）以"清理历史遗留索引"为由，DROP 了 8 张表 21 个
 * 旧命名索引，注释假定"新名称索引已由别的迁移 CREATE"。但全项目搜索确认：
 * 任何迁移都没有 CREATE 这些新命名索引。另有 4 张表 10 个索引从未被任何迁移
 * 创建过。合计 12 张表 31 个索引缺失。
 *
 * 新装用户不受影响（Room 建表时按 Entity 声明自动创建全部索引）；但所有从
 * 老版本升级的用户，迁移完成后 Room `validateMigration()` 会逐表比对索引，
 * 缺失时抛 `IllegalStateException` 崩溃，`fallbackToDestructiveMigration()`
 * 不兜底这种场景（仅兜底迁移路径缺失/执行异常，不兜底迁移成功后的 schema
 * 校验失败）。影响：所有升级用户 App 首次启动打开数据库即崩溃，无法进入应用。
 *
 * ## 缺失索引来源分类
 *
 * - **A类（8表21个）**：MIGRATION_46_47 删了旧名、新名从未补建。
 *   旧命名索引在 Migrations1to10.kt 建表时创建，v47 被当作"改名式冗余"删除，
 *   注释标注了对应的新名称，但新名称索引从未在任何迁移中 CREATE。
 *
 * - **B类（4表10个）**：从未被任何迁移创建过。涉及 messages / world_events /
 *   workflow_step_results / workflow_jobs 四张表，其中 messages / world_events
 *   是高频写入表。
 *
 * ## 核实方法
 *
 * 用 Room 在 `app/schemas/.../58.json` 里导出的每张表期望索引全量列表，逐个用
 * `grep -rE "CREATE (UNIQUE )?INDEX.*\`索引名\`"` 在全部 Migrations1to10.kt ~
 * Migration60to61.kt 里查证。v59/v60/v61 三次迁移只涉及新增列（psychText /
 * thinkingText），不涉及本条任何表的索引，schema 58.json 的索引期望在 v61
 * 仍然有效。31 个索引全部经 58.json 交叉核实为 Room 期望索引，且全部在迁移链
 * 中缺失。
 *
 * ## relationship_states 唯一索引去重
 *
 * `index_relationship_states_fromId_toId` 是 UNIQUE 索引。旧唯一索引
 * `index_relationship_from_to` 在 v47 被删除后到 v62 期间，(fromId, toId) 列
 * 组合上没有任何数据库层唯一约束。RelationshipDao.upsert() 的
 * OnConflictStrategy.REPLACE 基于主键 `id` 而非 (fromId, toId)，若代码在
 * get-then-upsert 路径外有直接 upsert 新 id 的写入，可能产生重复行。
 * 建唯一索引前先按 (fromId, toId) 去重，保留 updatedAt 最新的一行（关系状态
 * 的最新值），避免 CREATE UNIQUE INDEX 因重复行失败。无重复时 DELETE 为空操作。
 * 去重范式与 MIGRATION_43_44（evolution_plans / pregnancy_answers）一致。
 *
 * ## 验收
 *
 * 所有 CREATE INDEX 均使用 IF NOT EXISTS，对已有索引（如新装用户由 Room 建表
 * 自动创建的）是幂等空操作。本迁移不改动任何表结构、不改动任何业务数据（除
 * relationship_states 去重删除重复行外），风险极低。
 */
internal val MIGRATION_61_62 = object : Migration(61, 62) {
    override fun migrate(db: SupportSQLiteDatabase) {

        // ────────────────────────────────────────────────────────
        // 前置：relationship_states (fromId, toId) 去重，
        // 为创建 UNIQUE 索引 index_relationship_states_fromId_toId 做准备。
        // 保留每组 (fromId, toId) 中 updatedAt 最新的一行（关系状态最新值），
        // id DESC 作为确定性 tiebreaker。无重复时 DELETE 为空操作。
        // 范式对齐 MIGRATION_43_44（GROUP BY + 相关子查询，不依赖窗口函数）。
        // ────────────────────────────────────────────────────────
        db.execSQL("""
            DELETE FROM relationship_states
            WHERE id NOT IN (
                SELECT keep_id FROM (
                    SELECT rs.id AS keep_id
                    FROM relationship_states AS rs
                    WHERE rs.id = (
                        SELECT inner_rs.id FROM relationship_states AS inner_rs
                        WHERE inner_rs.fromId = rs.fromId
                          AND inner_rs.toId = rs.toId
                        ORDER BY inner_rs.updatedAt DESC, inner_rs.id DESC
                        LIMIT 1
                    )
                )
            )
        """.trimIndent())

        // ════════════════════════════════════════════════════════
        // A类：MIGRATION_46_47 删了旧名、新名从未补建（8表21个）
        // ════════════════════════════════════════════════════════

        // --- memory_candidates（旧名 index_candidates_*，4个）---
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_candidates_characterId` ON `memory_candidates` (`characterId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_candidates_sourceEventId` ON `memory_candidates` (`sourceEventId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_candidates_isProcessed` ON `memory_candidates` (`isProcessed`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_candidates_createdAt` ON `memory_candidates` (`createdAt`)")

        // --- relationship_states（旧名 index_relationship_*，4个，含1个UNIQUE）---
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_relationship_states_fromId` ON `relationship_states` (`fromId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_relationship_states_toId` ON `relationship_states` (`toId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_relationship_states_fromId_toId` ON `relationship_states` (`fromId`, `toId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_relationship_states_isInterCharacter` ON `relationship_states` (`isInterCharacter`)")

        // --- character_goals（旧名 index_goals_*，2个）---
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_character_goals_characterId` ON `character_goals` (`characterId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_character_goals_isActive` ON `character_goals` (`isActive`)")

        // --- project_milestones（旧名 index_milestones_*，1个）---
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_project_milestones_projectId` ON `project_milestones` (`projectId`)")

        // --- project_members（旧名 index_members_*，2个）---
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_project_members_projectId` ON `project_members` (`projectId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_project_members_characterId` ON `project_members` (`characterId`)")

        // --- project_knowledge（旧名 index_knowledge_*，3个）---
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_project_knowledge_projectId` ON `project_knowledge` (`projectId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_project_knowledge_characterId` ON `project_knowledge` (`characterId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_project_knowledge_createdAt` ON `project_knowledge` (`createdAt`)")

        // --- scheduled_jobs（旧名 idx_jobs_*，2个）---
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_scheduled_jobs_enabled_nextRunAt` ON `scheduled_jobs` (`enabled`, `nextRunAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_scheduled_jobs_characterId` ON `scheduled_jobs` (`characterId`)")

        // --- job_results（旧名 idx_results_*，3个）---
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_job_results_jobId` ON `job_results` (`jobId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_job_results_characterId_isRead` ON `job_results` (`characterId`, `isRead`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_job_results_createdAt` ON `job_results` (`createdAt`)")

        // ════════════════════════════════════════════════════════
        // B类：从未被任何迁移创建过（4表10个，含高频表 messages/world_events）
        // ════════════════════════════════════════════════════════

        // --- messages（2个）---
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_characterId` ON `messages` (`characterId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_characterId_createdAt` ON `messages` (`characterId`, `createdAt`)")

        // --- world_events（5个）---
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_world_events_actorId` ON `world_events` (`actorId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_world_events_domain` ON `world_events` (`domain`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_world_events_projectId` ON `world_events` (`projectId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_world_events_createdAt` ON `world_events` (`createdAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_world_events_type_createdAt` ON `world_events` (`type`, `createdAt`)")

        // --- workflow_step_results（1个）---
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_workflow_step_results_jobId` ON `workflow_step_results` (`jobId`)")

        // --- workflow_jobs（2个）---
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_workflow_jobs_characterId` ON `workflow_jobs` (`characterId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_workflow_jobs_status` ON `workflow_jobs` (`status`)")
    }
}
