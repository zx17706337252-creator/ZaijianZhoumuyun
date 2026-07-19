package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// ── Migration v11 → v12（Phase 31）────────────────────
// project_knowledge 表新增 charCount 列
internal val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `project_knowledge` ADD COLUMN `charCount` INTEGER NOT NULL DEFAULT 0"
        )
    }
}

// ── Migration v12 → v13（Bugfix）─────────────────────
// relationship_states 补 sourceEventId 列（Entity 字段早于 migration 加入，导致 schema 不一致）
internal val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `relationship_states` ADD COLUMN `sourceEventId` TEXT"
        )
    }
}

internal val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `coreWound` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `coreDesire` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `maskTrigger` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `privatePersona` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `privateStyle` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `privateExamples` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `situationRules` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `deviationSignals` TEXT NOT NULL DEFAULT ''")
    }
}

internal val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `relationship_states` ADD COLUMN `suppression` INTEGER NOT NULL DEFAULT 50"
        )
    }
}

// ── 附加（NyxChat V18 A.1/A.2）：likes / dislikes / relationships ──
internal val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `likes` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `dislikes` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `relationships` TEXT NOT NULL DEFAULT ''")
    }
}

// ── Migration v16 → v17 ───────────────────────────────
// 新增 character_state 表（CharacterStateLayer 持久化）。
// 字段与 CharacterStateEntity.kt 一一对应；socialMode 不入库
// （实时计算，见 CharacterStateRepository.applySocialMode）。
internal val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `character_state` (
                `characterId` INTEGER NOT NULL PRIMARY KEY,
                `maskType` TEXT NOT NULL,
                `talkativeness` INTEGER NOT NULL,
                `openness` INTEGER NOT NULL,
                `patience` INTEGER NOT NULL,
                `vigilance` INTEGER NOT NULL,
                `primaryEmotion` TEXT NOT NULL,
                `secondaryEmotion` TEXT,
                `intensity` INTEGER NOT NULL,
                `emotionalFatigue` INTEGER NOT NULL,
                `emotionalStability` INTEGER NOT NULL,
                `currentNeed` TEXT NOT NULL,
                `currentGoal` TEXT NOT NULL,
                `desireStrength` INTEGER NOT NULL,
                `urgency` INTEGER NOT NULL,
                `resistance` INTEGER NOT NULL,
                `currentFear` TEXT NOT NULL,
                `secretDesire` TEXT NOT NULL,
                `exposureRisk` INTEGER NOT NULL,
                `selfControl` INTEGER NOT NULL,
                `emotionalSuppression` INTEGER NOT NULL,
                `focusTarget` TEXT NOT NULL,
                `focusStrength` INTEGER NOT NULL,
                `observationLevel` INTEGER NOT NULL,
                `concernLevel` INTEGER NOT NULL,
                `lastUpdated` INTEGER NOT NULL
            )
        """.trimIndent())
    }
}
// ── Migration v17 → v18 ───────────────────────────────
// P3+P4.0（V5 执行方案，合并迁移）：
//  ① relationship_milestones：关系转折点追加式历史记录（P3）
//  ② pregnancy_state：怀孕状态展示（P4.0），不接入 CharacterStateLayer
//  ③ birth_records：生育记录（P4.0）
internal val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `relationship_milestones` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `fromId` TEXT NOT NULL,
                `toId` TEXT NOT NULL,
                `direction` TEXT NOT NULL,
                `description` TEXT NOT NULL,
                `sourceEventId` TEXT,
                `createdAt` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE INDEX IF NOT EXISTS `index_relationship_milestones_fromId_toId`
            ON `relationship_milestones` (`fromId`, `toId`)
        """.trimIndent())
        db.execSQL("""
            CREATE INDEX IF NOT EXISTS `index_relationship_milestones_createdAt`
            ON `relationship_milestones` (`createdAt`)
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `pregnancy_state` (
                `characterId` INTEGER NOT NULL PRIMARY KEY,
                `isPregnant` INTEGER NOT NULL,
                `pregnancyStartedAt` INTEGER,
                `cycleDays` INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `birth_records` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT,
                `characterId` INTEGER NOT NULL,
                `bornAt` INTEGER NOT NULL,
                `isDaughter` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE INDEX IF NOT EXISTS `index_birth_records_characterId`
            ON `birth_records` (`characterId`)
        """.trimIndent())
    }
}

// ── Migration v18 → v19 ───────────────────────────────
// character_identity 表新增两个字段（V18 关系结构层借鉴）：
// ① relationAssumption：她对关系阶段/性质的默认认知前提（内核字段）
// ② conflictStrategy：摩擦/误会场景下她的第一反应模式（行为规则字段）
internal val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `relationAssumption` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `conflictStrategy` TEXT NOT NULL DEFAULT ''")
    }
}

// ── Migration v19 → v20 ────────────────────────────────
internal val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `menstrual_cycle` (
                `characterId`     INTEGER NOT NULL,
                `cycleAnchorAt`   INTEGER,
                `cycleLengthDays` INTEGER NOT NULL DEFAULT 28,
                `menstrualDays`   INTEGER NOT NULL DEFAULT 5,
                `fertileDays`     INTEGER NOT NULL DEFAULT 6,
                PRIMARY KEY(`characterId`)
            )
            """.trimIndent()
        )
    }
}

// ── Migration v20 → v21 ────────────────────────────────
internal val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // agent_relation：女儿 Agent 与用户的关系阶段
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `agent_relation` (
                `daughterId`          INTEGER NOT NULL,
                `motherCharacterId`   INTEGER NOT NULL,
                `stage`               TEXT NOT NULL DEFAULT 'STAGE_1_INITIAL',
                `interactionCount`    INTEGER NOT NULL DEFAULT 0,
                `createdAt`           INTEGER NOT NULL,
                `lastStageUpAt`       INTEGER,
                PRIMARY KEY(`daughterId`)
            )
            """.trimIndent()
        )
        // pregnancy_answers：孕期共设问答记录
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `pregnancy_answers` (
                `id`                  INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `motherCharacterId`   INTEGER NOT NULL,
                `pregnancyStartedAt`  INTEGER NOT NULL,
                `questionType`        TEXT NOT NULL,
                `questionText`        TEXT NOT NULL,
                `answerText`          TEXT NOT NULL,
                `answeredAt`          INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}

internal val MIGRATIONS_11_20 = arrayOf(
    MIGRATION_11_12,
    MIGRATION_12_13,
    MIGRATION_13_14,
    MIGRATION_14_15,
    MIGRATION_15_16,
    MIGRATION_16_17,
    MIGRATION_17_18,
    MIGRATION_18_19,
    MIGRATION_19_20,
    MIGRATION_20_21
)
