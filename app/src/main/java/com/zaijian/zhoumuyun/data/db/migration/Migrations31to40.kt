package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase


internal val MIGRATION_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `character_identity` ADD COLUMN `avatarUrl` TEXT NOT NULL DEFAULT ''"
        )
    }
}

// ── Migration v32 → v33 ───────────────────────────────
// P1-32：scheduled_jobs + cloudSynced 列，标记本地任务是否已成功同步到
//   Supabase，createJob/updateJob 同步失败时置 0，App 启动时重试。
//   已有历史数据默认 1（视为已同步，避免老任务被误判为待同步重发）。
// P1-33：scheduled_jobs + lockedUntil 列，runLocalCompensation() 与
//   ScheduledJobWorker 执行前用它做认领式乐观锁，防止同一任务被
//   两条路径并发执行两次。
internal val MIGRATION_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `scheduled_jobs` ADD COLUMN `cloudSynced` INTEGER NOT NULL DEFAULT 1"
        )
        db.execSQL(
            "ALTER TABLE `scheduled_jobs` ADD COLUMN `lockedUntil` INTEGER"
        )
    }
}

// ── Migration v33 → v34 ───────────────────────────────
// 待办7：圆桌消息持久化，+ roundtable_messages 表
internal val MIGRATION_33_34 = object : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `roundtable_messages` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `roundtableId` TEXT NOT NULL,
                `characterId` INTEGER NOT NULL,
                `content` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_roundtable_messages_roundtableId` ON `roundtable_messages` (`roundtableId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_roundtable_messages_roundtableId_createdAt` ON `roundtable_messages` (`roundtableId`, `createdAt`)")
    }
}

// ── Migration v34 → v35 ───────────────────────────────
// 待办3：群记忆 scope 字段
// memories 主表 + memory_candidates 候选表加 scope/roundtableId 列
internal val MIGRATION_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE memories ADD COLUMN scope TEXT NOT NULL DEFAULT 'PERSONAL'")
        db.execSQL("ALTER TABLE memories ADD COLUMN roundtableId TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_roundtableId` ON `memories` (`roundtableId`)")
        db.execSQL("ALTER TABLE memory_candidates ADD COLUMN scope TEXT NOT NULL DEFAULT 'PERSONAL'")
        db.execSQL("ALTER TABLE memory_candidates ADD COLUMN roundtableId TEXT")
    }
}

// ── Migration v35 → v36 ───────────────────────────────
// P5 整合：roundtable_messages 表从简单字段升级为富结构，
// characterId → speakerId/speakerName，新增 replyTarget/turnIndex
internal val MIGRATION_35_36 = object : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. 建新表
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `roundtable_messages_new` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `roundtableId` TEXT NOT NULL,
                `speakerId` TEXT NOT NULL,
                `speakerName` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `replyTargetId` TEXT,
                `replyTargetName` TEXT,
                `turnIndex` INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        // 2. 迁移存量数据：characterId→speakerId，speakerName 用 "角色" 占位
        db.execSQL("""
            INSERT INTO `roundtable_messages_new`
                (`id`, `roundtableId`, `speakerId`, `speakerName`, `content`, `createdAt`)
            SELECT `id`, `roundtableId`, CAST(`characterId` AS TEXT), '', `content`, `createdAt`
            FROM `roundtable_messages`
        """.trimIndent())
        // 3. 替换旧表
        db.execSQL("DROP TABLE `roundtable_messages`")
        db.execSQL("ALTER TABLE `roundtable_messages_new` RENAME TO `roundtable_messages`")
        // 4. 重建索引
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_roundtable_messages_roundtableId` ON `roundtable_messages` (`roundtableId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_roundtable_messages_roundtableId_createdAt` ON `roundtable_messages` (`roundtableId`, `createdAt`)")
    }
}

// ── Migration v36 → v37 ───────────────────────────────
// Soul/Memory/User 三模块：character_identity 加 8 列
internal val MIGRATION_36_37 = object : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE character_identity ADD COLUMN soulNote TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE character_identity ADD COLUMN soulNoteBackup TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE character_identity ADD COLUMN narrativeMemory TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE character_identity ADD COLUMN narrativeMemoryBackup TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE character_identity ADD COLUMN userImpression TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE character_identity ADD COLUMN userImpressionBackup TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE character_identity ADD COLUMN lastEditedNoteField TEXT")
        db.execSQL("ALTER TABLE character_identity ADD COLUMN lastEditedNoteAt INTEGER NOT NULL DEFAULT 0")
    }
}

// ─────────────────────────────────────────────────────
//  Migration v37 → v38（P6 专长进化系统）
//
//  新建 6 张表：evolution_plans / practice_records /
//  practice_records_archive / stage_digests / specialty_profiles /
//  system_suggestions。
//  roundtable_messages 加 1 列：exportedFileJson（圆桌消息首次获得
//  文件卡片能力）。
//
//  不触碰 character_identity / memories / memory_candidates /
//  agent_plans / learning_goals / evaluation_sessions 等任何既有表，
//  与 Phase 22-26 的规则提炼链路、P5 Soul/Memory/User 三模块物理隔离，
//  互不影响——专长进化系统的晋升机制虽然最终会写入
//  character_identity.soulNote，但那是运行期通过已有的
//  CharacterIdentityDao.updateSoulNote() 方法写入，不需要任何
//  schema 改动。
// ─────────────────────────────────────────────────────
internal val MIGRATION_37_38 = object : Migration(37, 38) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ── evolution_plans ──────────────────────────────
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `evolution_plans` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `characterId` INTEGER NOT NULL,
                `specialtyId` TEXT NOT NULL,
                `version` INTEGER NOT NULL,
                `content` TEXT NOT NULL,
                `revisionReason` TEXT NOT NULL,
                `isActive` INTEGER NOT NULL DEFAULT 1,
                `createdAt` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_evolution_plans_characterId` ON `evolution_plans` (`characterId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_evolution_plans_characterId_specialtyId` ON `evolution_plans` (`characterId`, `specialtyId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_evolution_plans_characterId_isActive` ON `evolution_plans` (`characterId`, `isActive`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_evolution_plans_specialtyId_version` ON `evolution_plans` (`specialtyId`, `version`)")

        // ── practice_records ─────────────────────────────
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `practice_records` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `characterId` INTEGER NOT NULL,
                `specialtyId` TEXT NOT NULL,
                `practiceTopic` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `comparisonResult` TEXT NOT NULL,
                `comparisonNote` TEXT NOT NULL,
                `observedTrait` TEXT NOT NULL DEFAULT '',
                `digestStatus` TEXT NOT NULL DEFAULT 'RAW',
                `digestedIntoId` TEXT,
                `createdAt` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_practice_records_specialtyId` ON `practice_records` (`specialtyId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_practice_records_specialtyId_createdAt` ON `practice_records` (`specialtyId`, `createdAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_practice_records_specialtyId_digestStatus` ON `practice_records` (`specialtyId`, `digestStatus`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_practice_records_characterId` ON `practice_records` (`characterId`)")

        // ── practice_records_archive ─────────────────────
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `practice_records_archive` (
                `recordId` TEXT NOT NULL PRIMARY KEY,
                `fullContent` TEXT NOT NULL,
                `archivedAt` INTEGER NOT NULL
            )
        """.trimIndent())

        // ── stage_digests ─────────────────────────────────
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `stage_digests` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `characterId` INTEGER NOT NULL,
                `specialtyId` TEXT NOT NULL,
                `digestContent` TEXT NOT NULL,
                `sourceRecordCount` INTEGER NOT NULL,
                `periodStart` INTEGER NOT NULL,
                `periodEnd` INTEGER NOT NULL,
                `hasConflict` INTEGER NOT NULL DEFAULT 0,
                `conflictSummary` TEXT NOT NULL DEFAULT '',
                `mergedIntoProfile` INTEGER NOT NULL DEFAULT 0,
                `createdAt` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_stage_digests_specialtyId` ON `stage_digests` (`specialtyId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_stage_digests_specialtyId_createdAt` ON `stage_digests` (`specialtyId`, `createdAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_stage_digests_specialtyId_mergedIntoProfile` ON `stage_digests` (`specialtyId`, `mergedIntoProfile`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_stage_digests_characterId` ON `stage_digests` (`characterId`)")

        // ── specialty_profiles ────────────────────────────
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `specialty_profiles` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `characterId` INTEGER NOT NULL,
                `domain` TEXT NOT NULL,
                `anchorIntent` TEXT NOT NULL,
                `styleNotes` TEXT NOT NULL DEFAULT '',
                `practiceCount` INTEGER NOT NULL DEFAULT 0,
                `maturityStage` TEXT NOT NULL DEFAULT 'EXPLORING',
                `candidateObservationsJson` TEXT NOT NULL DEFAULT '[]',
                `hasUnresolvedConflict` INTEGER NOT NULL DEFAULT 0,
                `unresolvedConflictDescription` TEXT NOT NULL DEFAULT '',
                `promotedToIdentity` INTEGER NOT NULL DEFAULT 0,
                `hasUserConfirmedAtLeastOnce` INTEGER NOT NULL DEFAULT 0,
                `isActive` INTEGER NOT NULL DEFAULT 1,
                `lastPracticeAt` INTEGER NOT NULL DEFAULT 0,
                `lastDigestAt` INTEGER NOT NULL DEFAULT 0,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_specialty_profiles_characterId` ON `specialty_profiles` (`characterId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_specialty_profiles_characterId_domain` ON `specialty_profiles` (`characterId`, `domain`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_specialty_profiles_characterId_isActive` ON `specialty_profiles` (`characterId`, `isActive`)")

        // ── system_suggestions ────────────────────────────
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `system_suggestions` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `characterId` INTEGER NOT NULL,
                `specialtyId` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `reasoning` TEXT NOT NULL,
                `status` TEXT NOT NULL DEFAULT 'PENDING',
                `createdAt` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_system_suggestions_specialtyId` ON `system_suggestions` (`specialtyId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_system_suggestions_specialtyId_status` ON `system_suggestions` (`specialtyId`, `status`)")

        // ── roundtable_messages 加列 ──────────────────────
        db.execSQL("ALTER TABLE roundtable_messages ADD COLUMN exportedFileJson TEXT")
    }
}

// ─────────────────────────────────────────────────────
//  v38 → v39  裁判与竞争机制（第1步：数据层）
//
//  新增 5 张表：
//    judge_profiles          裁判档案，含评判标准说明书与候选修正池
//    competition_rounds      竞赛轮次，含状态机（COLLECTING→COMPLETED）
//    competition_entries     参赛条目，含三方评分（裁判/自评/用户）
//    competition_weight_configs  项目级评分权重配置，一个方向一条
//    judge_accuracy_log      裁判排名与用户排名吻合度历史
//
//  不触碰任何既有表。
// ─────────────────────────────────────────────────────
internal val MIGRATION_38_39 = object : Migration(38, 39) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ── judge_profiles ────────────────────────────────
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `judge_profiles` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `characterId` INTEGER NOT NULL,
                `domain` TEXT NOT NULL,
                `anchorIntent` TEXT NOT NULL,
                `standardNotes` TEXT NOT NULL DEFAULT '',
                `judgeCount` INTEGER NOT NULL DEFAULT 0,
                `maturityStage` TEXT NOT NULL DEFAULT 'EXPLORING',
                `candidateCorrectionsJson` TEXT NOT NULL DEFAULT '[]',
                `hasUnresolvedConflict` INTEGER NOT NULL DEFAULT 0,
                `unresolvedConflictDescription` TEXT NOT NULL DEFAULT '',
                `isActive` INTEGER NOT NULL DEFAULT 1,
                `lastJudgedAt` INTEGER NOT NULL DEFAULT 0,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_judge_profiles_characterId` ON `judge_profiles` (`characterId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_judge_profiles_characterId_domain` ON `judge_profiles` (`characterId`, `domain`)")

        // ── competition_rounds ────────────────────────────
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `competition_rounds` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `projectDomain` TEXT NOT NULL,
                `topic` TEXT NOT NULL,
                `judgeCharacterId` INTEGER NOT NULL,
                `participantIdsJson` TEXT NOT NULL,
                `status` TEXT NOT NULL DEFAULT 'COLLECTING',
                `createdAt` INTEGER NOT NULL,
                `completedAt` INTEGER
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_competition_rounds_projectDomain` ON `competition_rounds` (`projectDomain`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_competition_rounds_status` ON `competition_rounds` (`status`)")

        // ── competition_entries ───────────────────────────
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `competition_entries` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `roundId` TEXT NOT NULL,
                `characterId` INTEGER NOT NULL,
                `content` TEXT NOT NULL,
                `judgeScore` INTEGER,
                `judgeReasoning` TEXT NOT NULL DEFAULT '',
                `selfScore` INTEGER,
                `selfReasoning` TEXT NOT NULL DEFAULT '',
                `userScore` INTEGER,
                `userComment` TEXT NOT NULL DEFAULT '',
                `userRank` INTEGER,
                `compositeScore` REAL NOT NULL DEFAULT 0.0,
                `createdAt` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_competition_entries_roundId` ON `competition_entries` (`roundId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_competition_entries_roundId_characterId` ON `competition_entries` (`roundId`, `characterId`)")

        // ── competition_weight_configs ────────────────────
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `competition_weight_configs` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `projectDomain` TEXT NOT NULL,
                `userBaseWeight` INTEGER NOT NULL DEFAULT 50,
                `judgeBaseWeight` INTEGER NOT NULL DEFAULT 40,
                `selfBaseWeight` INTEGER NOT NULL DEFAULT 10,
                `judgeTrustDynamicEnabled` INTEGER NOT NULL DEFAULT 1,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_competition_weight_configs_projectDomain` ON `competition_weight_configs` (`projectDomain`)")

        // ── judge_accuracy_log ────────────────────────────
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `judge_accuracy_log` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `judgeProfileId` TEXT NOT NULL,
                `roundId` TEXT NOT NULL,
                `agreementScore` REAL NOT NULL,
                `createdAt` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_judge_accuracy_log_judgeProfileId` ON `judge_accuracy_log` (`judgeProfileId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_judge_accuracy_log_judgeProfileId_createdAt` ON `judge_accuracy_log` (`judgeProfileId`, `createdAt`)")
    }
}

// ─────────────────────────────────────────────────────
//  Migration v39 → v40（P1-5 修复：裁判档案懒创建并发重复）
//
//  judge_profiles 的 (characterId, domain) 索引从普通索引
//  升级为唯一索引，配合 DAO 的 OnConflictStrategy.IGNORE +
//  @Transaction 原子方法，从数据库层面彻底杜绝并发双击下的
//  TOCTOU 竞态重复插入。
//
//  升级前先清理历史脏数据：若某 (characterId, domain) 组合
//  已存在重复记录（旧版本并发 bug 残留），只保留 createdAt
//  最早的一条，删除其余的，否则建唯一索引会失败。
// ─────────────────────────────────────────────────────
internal val MIGRATION_39_40 = object : Migration(39, 40) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 去重：每组 (characterId, domain) 只保留 createdAt 最早的一条。
        // 注：minSdk=26 对应的系统 SQLite 版本不保证支持 ROW_NUMBER() 等
        // 窗口函数（需 3.25+），这里用 GROUP BY + 子查询兼容写法。
        db.execSQL("""
            DELETE FROM judge_profiles
            WHERE id NOT IN (
                SELECT keep_id FROM (
                    SELECT id AS keep_id
                    FROM judge_profiles AS jp
                    WHERE jp.id = (
                        SELECT id FROM judge_profiles AS inner_jp
                        WHERE inner_jp.characterId = jp.characterId
                          AND inner_jp.domain = jp.domain
                        ORDER BY inner_jp.createdAt ASC, inner_jp.id ASC
                        LIMIT 1
                    )
                )
            )
        """.trimIndent())

        // 把旧的普通索引换成唯一索引
        db.execSQL("DROP INDEX IF EXISTS `index_judge_profiles_characterId_domain`")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_judge_profiles_characterId_domain` ON `judge_profiles` (`characterId`, `domain`)")
    }
}
  
// ── Migration v40 → v41 ───────────────────────────────
// S1 修复：memories 表新增 ftsRowId 列。
// 旧逻辑用 id.hashCode() 作为 FTS 表 rowid 写入，
// 但 JOIN 时错误地用了 SQLite 自增 rowid（m.rowid），
// 导致两侧数值永远不等，FTS 全文检索召回完全失效。
// 修复后：写入时同步将 ftsRowId 存入主表，
// JOIN 改为 m.ftsRowId = fts.rowid，保证关联正确。
// 存量记忆的 ftsRowId 默认为 0（FTS 行已与真实 rowid 不对应），
// 等 MemoryEngine 下次重写时自动修正。
internal val MIGRATION_40_41 = object : Migration(40, 41) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `memories` ADD COLUMN `ftsRowId` INTEGER NOT NULL DEFAULT 0")
        // B3 修复：回填历史 memories 的 ftsRowId。
        // memories_fts 是 FTS4 虚拟表，其内置 rowid 与写入顺序一一对应。
        // 通过联表将 fts.rowid（由 fts.content 列文本推导）更新到主表。
        // 做法：memories_fts content 列存储的是 memories.content，
        // 按 memories.id 与 fts 内容精确匹配来关联。
        // 由于 FTS4 不支持直接 JOIN，先把 fts rowid 写入临时表再 UPDATE。
        db.execSQL("""
            CREATE TEMPORARY TABLE IF NOT EXISTS _fts_rowid_map AS
            SELECT rowid AS fts_rowid, content AS fts_content
            FROM memories_fts
        """.trimIndent())
        db.execSQL("""
            UPDATE memories
            SET ftsRowId = (
                SELECT fts_rowid FROM _fts_rowid_map
                WHERE fts_content = memories.content
                LIMIT 1
            )
            WHERE ftsRowId = 0
        """.trimIndent())
        db.execSQL("DROP TABLE IF EXISTS _fts_rowid_map")
    }
}

internal val MIGRATIONS_31_40 = arrayOf(
    MIGRATION_31_32,
    MIGRATION_32_33,
    MIGRATION_33_34,
    MIGRATION_34_35,
    MIGRATION_35_36,
    MIGRATION_36_37,
    MIGRATION_37_38,
    MIGRATION_38_39,
    MIGRATION_39_40,
    MIGRATION_40_41
)
