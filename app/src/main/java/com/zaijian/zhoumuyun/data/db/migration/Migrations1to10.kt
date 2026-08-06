package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase


// ── Migration v1 → v2 ─────────────────────────────────
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // memories
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `memories` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `characterId` INTEGER NOT NULL,
                `domain` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `importance` INTEGER NOT NULL DEFAULT 3,
                `keywords` TEXT NOT NULL DEFAULT '',
                `isCore` INTEGER NOT NULL DEFAULT 0,
                `projectId` TEXT,
                `sourceEventId` TEXT,
                `accessCount` INTEGER NOT NULL DEFAULT 0,
                `lastAccessedAt` INTEGER NOT NULL DEFAULT 0,
                `decayFactor` REAL NOT NULL DEFAULT 1.0,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_characterId` ON `memories` (`characterId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_domain` ON `memories` (`domain`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_importance` ON `memories` (`importance`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_isCore` ON `memories` (`isCore`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_updatedAt` ON `memories` (`updatedAt`)")

        // memories_fts（独立 FTS4 表，非外部内容表，由 MemoryDao.insertWithFts 手动同步）
        db.execSQL("""
            CREATE VIRTUAL TABLE IF NOT EXISTS `memories_fts`
            USING fts4(`content`, `keywords`, tokenize=unicode61)
        """.trimIndent())

        // memory_candidates
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `memory_candidates` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `characterId` INTEGER NOT NULL,
                `sourceEventId` TEXT,
                `content` TEXT NOT NULL,
                `score` INTEGER NOT NULL DEFAULT 3,
                `domain` TEXT NOT NULL,
                `projectId` TEXT,
                `isProcessed` INTEGER NOT NULL DEFAULT 0,
                `resultMemoryId` TEXT,
                `createdAt` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_candidates_characterId` ON `memory_candidates` (`characterId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_candidates_score` ON `memory_candidates` (`score`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_candidates_sourceEventId` ON `memory_candidates` (`sourceEventId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_candidates_isProcessed` ON `memory_candidates` (`isProcessed`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_candidates_createdAt` ON `memory_candidates` (`createdAt`)")
    }
}

// ── Migration v2 → v3 ─────────────────────────────────
internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // relationship_states
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `relationship_states` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `fromId` TEXT NOT NULL,
                `toId` TEXT NOT NULL,
                `trust` INTEGER NOT NULL DEFAULT 50,
                `respect` INTEGER NOT NULL DEFAULT 50,
                `affection` INTEGER NOT NULL DEFAULT 50,
                `curiosity` INTEGER NOT NULL DEFAULT 50,
                `dependence` INTEGER NOT NULL DEFAULT 20,
                `conflict` INTEGER NOT NULL DEFAULT 10,
                `stage` TEXT NOT NULL DEFAULT 'STRANGER',
                `updatedAt` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_relationship_fromId` ON `relationship_states` (`fromId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_relationship_toId` ON `relationship_states` (`toId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_relationship_from_to` ON `relationship_states` (`fromId`, `toId`)")

        // character_goals
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `character_goals` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `characterId` INTEGER NOT NULL,
                `title` TEXT NOT NULL,
                `description` TEXT NOT NULL DEFAULT '',
                `priority` INTEGER NOT NULL DEFAULT 3,
                `timeHorizon` TEXT NOT NULL DEFAULT 'MID_TERM',
                `progress` REAL NOT NULL DEFAULT 0.0,
                `isActive` INTEGER NOT NULL DEFAULT 1,
                `relatedProjectId` TEXT,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_goals_characterId` ON `character_goals` (`characterId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_goals_isActive` ON `character_goals` (`isActive`)")

        // projects
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `projects` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `title` TEXT NOT NULL,
                `description` TEXT NOT NULL DEFAULT '',
                `status` TEXT NOT NULL DEFAULT 'ACTIVE',
                `ownerId` TEXT NOT NULL DEFAULT 'user',
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `archivedAt` INTEGER
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_projects_status` ON `projects` (`status`)")

        // project_milestones
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `project_milestones` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `projectId` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `description` TEXT NOT NULL DEFAULT '',
                `isCompleted` INTEGER NOT NULL DEFAULT 0,
                `completedAt` INTEGER,
                `createdAt` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_milestones_projectId` ON `project_milestones` (`projectId`)")

        // project_members
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `project_members` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `projectId` TEXT NOT NULL,
                `characterId` INTEGER NOT NULL,
                `role` TEXT NOT NULL DEFAULT 'CONTRIBUTOR',
                `joinedAt` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_members_projectId` ON `project_members` (`projectId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_members_characterId` ON `project_members` (`characterId`)")
    }
}

// ── Migration v3 → v4 ─────────────────────────────────
internal val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `project_knowledge` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `projectId` TEXT NOT NULL,
                `characterId` TEXT,
                `title` TEXT NOT NULL DEFAULT '',
                `content` TEXT NOT NULL,
                `source` TEXT NOT NULL DEFAULT 'MANUAL',
                `importance` INTEGER NOT NULL DEFAULT 3,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_projectId` ON `project_knowledge` (`projectId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_characterId` ON `project_knowledge` (`characterId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_createdAt` ON `project_knowledge` (`createdAt`)")

        db.execSQL("""
            CREATE VIRTUAL TABLE IF NOT EXISTS `project_knowledge_fts`
            USING fts4(
                content=`project_knowledge`,
                `title`,
                `content`
            )
        """.trimIndent())
        // ⚠️ 修正：content= 外部内容表的同步触发器，Room 只在全新安装（onCreate）自动生成。
        // 迁移路径（onUpgrade）必须手工补建，否则 validateMigration 比对触发器缺失抛 IllegalStateException
        // （release 下被 fallbackToDestructiveMigration 静默清库），且 FTS 永不随主表同步。
        // 4 条触发器定义与 80.json 期望 schema 逐字一致，不会与 Room 生成的触发器重复（IF NOT EXISTS 幂等）。
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_project_knowledge_fts_BEFORE_UPDATE BEFORE UPDATE ON `project_knowledge` BEGIN DELETE FROM `project_knowledge_fts` WHERE `docid`=OLD.`rowid`; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_project_knowledge_fts_BEFORE_DELETE BEFORE DELETE ON `project_knowledge` BEGIN DELETE FROM `project_knowledge_fts` WHERE `docid`=OLD.`rowid`; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_project_knowledge_fts_AFTER_UPDATE AFTER UPDATE ON `project_knowledge` BEGIN INSERT INTO `project_knowledge_fts`(`docid`, `title`, `content`) VALUES (NEW.`rowid`, NEW.`title`, NEW.`content`); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_project_knowledge_fts_AFTER_INSERT AFTER INSERT ON `project_knowledge` BEGIN INSERT INTO `project_knowledge_fts`(`docid`, `title`, `content`) VALUES (NEW.`rowid`, NEW.`title`, NEW.`content`); END")
    }
}

// ── Migration v4 → v5 ─────────────────────────────────
internal val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `relationship_states` ADD COLUMN `jealousy` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `relationship_states` ADD COLUMN `tension` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `relationship_states` ADD COLUMN `isInterCharacter` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_relationship_isInterCharacter` ON `relationship_states` (`isInterCharacter`)")
    }
}

// ── Migration v5 → v6 ─────────────────────────────────
internal val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `messages` ADD COLUMN `exportedFileJson` TEXT")
    }
}

// ── Migration v6 → v7 ─────────────────────────────────
// Phase 19: tasks 表（Task Engine 持久化）
internal val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `tasks` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `title` TEXT NOT NULL,
                `description` TEXT NOT NULL DEFAULT '',
                `characterId` INTEGER NOT NULL,
                `status` TEXT NOT NULL DEFAULT 'RUNNING',
                `progress` REAL NOT NULL DEFAULT 0.0,
                `toolName` TEXT,
                `resultSummary` TEXT,
                `projectId` TEXT,
                `source` TEXT NOT NULL DEFAULT 'chat_tool',
                `sourceMessageId` TEXT,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `completedAt` INTEGER
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_characterId` ON `tasks` (`characterId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_status` ON `tasks` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_projectId` ON `tasks` (`projectId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_createdAt` ON `tasks` (`createdAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_status_createdAt` ON `tasks` (`status`, `createdAt`)")
    }
}

// ── Migration v7 → v8 ─────────────────────────────────
// Phase 22: agent_plans 表（AgentPlan 进化方案）+ learning_goals 表（学习目标）
internal val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // agent_plans 表
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `agent_plans` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `characterId` INTEGER NOT NULL,
                `title` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `isActive` INTEGER NOT NULL DEFAULT 1,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_plans_characterId` ON `agent_plans` (`characterId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_plans_characterId_isActive` ON `agent_plans` (`characterId`, `isActive`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_plans_createdAt` ON `agent_plans` (`createdAt`)")

        // learning_goals 表
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `learning_goals` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `characterId` INTEGER NOT NULL,
                `agentPlanId` TEXT,
                `title` TEXT NOT NULL,
                `description` TEXT NOT NULL DEFAULT '',
                `progress` REAL NOT NULL DEFAULT 0.0,
                `isActive` INTEGER NOT NULL DEFAULT 1,
                `status` TEXT NOT NULL DEFAULT 'IN_PROGRESS',
                `designatedReviewerId` INTEGER,
                `lastUpdateNote` TEXT,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_learning_goals_characterId` ON `learning_goals` (`characterId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_learning_goals_characterId_isActive` ON `learning_goals` (`characterId`, `isActive`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_learning_goals_agentPlanId` ON `learning_goals` (`agentPlanId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_learning_goals_createdAt` ON `learning_goals` (`createdAt`)")
    }
}
// ── Migration v8 → v9 ─────────────────────────────────
// Phase 24: evaluation_sessions 表（打分会话）
internal val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `evaluation_sessions` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `characterId` INTEGER NOT NULL,
                `goalId` TEXT NOT NULL,
                `triggerMessageId` TEXT NOT NULL,
                `status` TEXT NOT NULL DEFAULT 'PENDING',
                `agentScoreJson` TEXT,
                `agentScore` REAL,
                `agentComment` TEXT,
                `userScore` INTEGER,
                `userNote` TEXT,
                `compositeScore` REAL,
                `reportText` TEXT,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_evaluation_sessions_characterId` ON `evaluation_sessions` (`characterId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_evaluation_sessions_characterId_goalId` ON `evaluation_sessions` (`characterId`, `goalId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_evaluation_sessions_status` ON `evaluation_sessions` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_evaluation_sessions_createdAt` ON `evaluation_sessions` (`createdAt`)")
    }
}

// ── Migration v9 → v10 ────────────────────────────────
// Phase 25: memories 表新增 isLocked（规则锁定标志）和 goalId（规则目标关联）
//
// isLocked：仅 domain='RULE' 的记忆使用；isLocked=1 表示该规则已满足锁定条件
//           （置信度 ≥4.0 且出现在 ≥3 次高分 Session），会被注入 Rule Layer。
// goalId：  仅 domain='RULE' 的记忆使用；关联该规则所属的 LearningGoal。
//           非 RULE 域记忆此字段为 NULL。
internal val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 新增 isLocked 字段（默认 0 = false，存量记忆不受影响）
        db.execSQL(
            "ALTER TABLE `memories` ADD COLUMN `isLocked` INTEGER NOT NULL DEFAULT 0"
        )
        // 新增 goalId 字段（可空，非 RULE 域记忆保持 NULL）
        db.execSQL(
            "ALTER TABLE `memories` ADD COLUMN `goalId` TEXT"
        )
        // 为 Rule Layer 查询创建联合索引
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memories_isLocked_domain` ON `memories` (`characterId`, `domain`, `isLocked`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memories_goalId` ON `memories` (`characterId`, `goalId`)"
        )
    }
}

// ── Migration v10 → v11 ───────────────────────────────
// Phase 29: + scheduled_jobs 表（定时任务）+ job_results 表（执行结果）
internal val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {

        // scheduled_jobs 表
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `scheduled_jobs` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `characterId` INTEGER NOT NULL,
                `title` TEXT NOT NULL,
                `toolName` TEXT NOT NULL,
                `toolParamsJson` TEXT NOT NULL DEFAULT '{}',
                `enabled` INTEGER NOT NULL DEFAULT 1,
                `repeatIntervalMs` INTEGER,
                `nextRunAt` INTEGER NOT NULL,
                `lastRunAt` INTEGER,
                `executedBy` TEXT NOT NULL DEFAULT 'local',
                `createdAt` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_jobs_enabled_next` ON `scheduled_jobs` (`enabled`, `nextRunAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_jobs_character` ON `scheduled_jobs` (`characterId`)")

        // job_results 表
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `job_results` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `jobId` TEXT NOT NULL,
                `characterId` INTEGER NOT NULL,
                `toolName` TEXT NOT NULL,
                `status` TEXT NOT NULL DEFAULT 'success',
                `output` TEXT,
                `errorMessage` TEXT,
                `executedBy` TEXT NOT NULL DEFAULT 'local',
                `startedAt` INTEGER NOT NULL,
                `completedAt` INTEGER,
                `isRead` INTEGER NOT NULL DEFAULT 0,
                `createdAt` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_results_jobId` ON `job_results` (`jobId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_results_char_read` ON `job_results` (`characterId`, `isRead`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_results_created` ON `job_results` (`createdAt`)")
    }
}

internal val MIGRATIONS_1_10 = arrayOf(
    MIGRATION_1_2,
    MIGRATION_2_3,
    MIGRATION_3_4,
    MIGRATION_4_5,
    MIGRATION_5_6,
    MIGRATION_6_7,
    MIGRATION_7_8,
    MIGRATION_8_9,
    MIGRATION_9_10,
    MIGRATION_10_11
)
