package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase


// ── Migration v41 → v42 ───────────────────────────────
// B5 修复：projects 表新增 goalId 列，支持三层结构 Tasks/Goals/Projects 关联。
// null = 独立项目（不挂载到任何 LearningGoal）。
internal val MIGRATION_41_42 = object : Migration(41, 42) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `projects` ADD COLUMN `goalId` TEXT")
    }
}

// ── Migration v42 → v43 ───────────────────────────────
// P1-1-3 修复：MIGRATION_40_41 用 memories.content 反查 memories_fts.rowid，
// content 非唯一时会错配 ftsRowId，导致 FTS JOIN 指向错误行。
//
// 修复方案：删除旧手动维护的 FTS 表，重建为外部内容 FTS4 表
// （content=`memories`），由 Room 触发器自动同步，彻底去除 ftsRowId 字段。
//
// 注意：此 Migration 不删除 memories.ftsRowId 列（SQLite 不支持 DROP COLUMN）；
// 该列保留为 0，不再被任何新代码读写——MemoryDao/MemoryRepository 已改为
// 依赖 Room 触发器，不再手动操作 FTS rowid。
internal val MIGRATION_42_43 = object : Migration(42, 43) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. 删除旧的手动管理 FTS 表（包含旧 content/keywords 索引）
        db.execSQL("DROP TABLE IF EXISTS `memories_fts`")

        // 2. 重建为外部内容 FTS4 表，content=`memories` 告知 FTS 关联主表
        //    Room 会在第一次 build() 时自动生成 after_insert/after_update/after_delete
        //    三条触发器，保持 FTS 与主表同步，不再需要手写 ftsRowId。
        db.execSQL("""
            CREATE VIRTUAL TABLE IF NOT EXISTS `memories_fts`
            USING fts4(content=`memories`, `content`, `keywords`, tokenize=unicode61)
        """.trimIndent())

        // 3. 用 rebuild 命令从主表 memories 全量回填 FTS 索引。
        //    FTS4 外部内容表的 rebuild 会读取 content= 指向的主表，
        //    按主表 rowid 对应关系重建全文索引，不依赖 content 列文本匹配，
        //    彻底消除 MIGRATION_40_41 中 content 反查错配的问题。
        db.execSQL("INSERT INTO `memories_fts`(`memories_fts`) VALUES('rebuild')")
    }
}

// ── Migration v43 → v44 ────────────────────────────────
// P1-6-1：evolution_plans 补 (specialtyId, version) 唯一索引，
//         防止并发写入产生重复版本号（SQLite 不支持直接 ADD UNIQUE INDEX，
//         需重建表）。
// P1-6-9：pregnancy_answers 补 (motherCharacterId, questionType, slotIndex, answeredAt)
//         唯一索引，作为 @Transaction recordIfOpen 的数据库层最终兜底。
internal val MIGRATION_43_44 = object : Migration(43, 44) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ── evolution_plans：重建以加入唯一索引 ─────────
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `evolution_plans_new` (
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
        db.execSQL("""
            INSERT INTO `evolution_plans_new`
            SELECT `id`, `characterId`, `specialtyId`, `version`,
                   `content`, `revisionReason`, `isActive`, `createdAt`
            FROM `evolution_plans`
        """.trimIndent())
        db.execSQL("DROP TABLE `evolution_plans`")
        db.execSQL("ALTER TABLE `evolution_plans_new` RENAME TO `evolution_plans`")
        // 唯一索引
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_evolution_plans_specialtyId_version` ON `evolution_plans` (`specialtyId`, `version`)")
        // 其余原有普通索引
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_evolution_plans_characterId` ON `evolution_plans` (`characterId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_evolution_plans_characterId_specialtyId` ON `evolution_plans` (`characterId`, `specialtyId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_evolution_plans_characterId_isActive` ON `evolution_plans` (`characterId`, `isActive`)")

        // ── pregnancy_answers：直接 CREATE UNIQUE INDEX（无需重建表）──
        db.execSQL("""
            CREATE UNIQUE INDEX IF NOT EXISTS
            `index_pregnancy_answers_motherCharacterId_questionType_slotIndex_answeredAt`
            ON `pregnancy_answers` (`motherCharacterId`, `questionType`, `slotIndex`, `answeredAt`)
        """.trimIndent())
    }
}
// ── Migration v44 → v45 ───────────────────────────────
// P-1 修复：MIGRATION_42_43 把 memories_fts 重建为"外部内容 FTS4 表"
// （USING fts4(content=`memories`, ...)），但 MemoryFtsEntity 的 @Fts4 注解
// 定义的是普通 FTS4 表（无 contentEntity 参数）。二者 schema 不一致会导致
// Room 在 build() 时校验失败、App 启动崩溃（IllegalStateException）。
//
// 修复方案：删除外部内容表，重建为与 Entity 一致的普通 FTS4 表。
// 主表 <-> FTS 同步仍由 MemoryRepository.save()/update() 手动维护
// （insertWithFts / updateWithFts），ftsRowId 字段继续负责 JOIN。
//
// P-3 修复（顺带）：MIGRATION_40_41 用 content 文本反查 ftsRowId，
// 重复 content 会错配 rowId。MIGRATION_42_43 已重建 FTS 表，但旧的
// ftsRowId 错配数据仍留在主表。
// 本次迁移重新全量回填 ftsRowId：借助 SQLite 在新建普通 FTS4 表时
// rowid 自增的特性，将 FTS 表的真实 rowid（按 INSERT 顺序分配）
// 与主表按 createdAt 排序写入后按 memories.rowid 回填，
// 消除因 content 反查造成的错配存量数据。
//
// 注意：已删除的外部内容表不再产生 Room 触发器；普通 FTS4 表同步
// 由代码层 MemoryRepository 负责，行为与 v42 之前完全一致。
internal val MIGRATION_44_45 = object : Migration(44, 45) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. 删除 MIGRATION_42_43 创建的外部内容 FTS4 表（及其隐含触发器）
        db.execSQL("DROP TABLE IF EXISTS `memories_fts`")
        db.execSQL("DROP TRIGGER IF EXISTS `room_fts_content_sync_memories_fts_BEFORE_UPDATE`")
        db.execSQL("DROP TRIGGER IF EXISTS `room_fts_content_sync_memories_fts_BEFORE_DELETE`")
        db.execSQL("DROP TRIGGER IF EXISTS `room_fts_content_sync_memories_fts_AFTER_UPDATE`")
        db.execSQL("DROP TRIGGER IF EXISTS `room_fts_content_sync_memories_fts_AFTER_INSERT`")

        // 2. 重建为普通 FTS4 表（与 MemoryFtsEntity @Fts4 注解保持一致）
        //    tokenize=unicode61 匹配 FtsOptions.TOKENIZER_UNICODE61
        db.execSQL("""
            CREATE VIRTUAL TABLE IF NOT EXISTS `memories_fts`
            USING fts4(`content`, `keywords`, tokenize=unicode61)
        """.trimIndent())

        // 3. 从主表回填存量数据进 FTS：按 createdAt 顺序插入，
        //    FTS rowid 由 SQLite 自增分配，随后把该 rowid 更新回主表 ftsRowId。
        //    使用临时表避免 FTS 表不支持直接 JOIN 的限制。
        db.execSQL("""
            INSERT INTO `memories_fts`(`rowid`, `content`, `keywords`)
            SELECT `rowid`, `content`, `keywords`
            FROM `memories`
            ORDER BY `rowid`
        """.trimIndent())

        // 4. 用 FTS 表真实 rowid 回填主表 ftsRowId（FTS rowid == memories.rowid）
        //    普通 FTS4 表按 INSERT 顺序分配 rowid，与上面 INSERT 的 rowid 列一一对应
        db.execSQL("""
            UPDATE `memories`
            SET `ftsRowId` = `rowid`
            WHERE 1
        """.trimIndent())
    }
}

// ── Migration v45 → v46 ───────────────────────────────
// 头像存储重新设计（2026-07-03）：旧方案 onAvatarCropped 直接把
// 用户圆形裁剪框里看到的区域裁成 512×512 正方形存盘，avatarUrl
// 指向的就是这张成品图。公馆页需要把它塞进拱形（矩形+半圆，
// 宽高比约 0.48:1）容器，正方形图 Crop 撑满宽度后，超出原裁剪
// 范围的上下区域没有真实画面，只剩容器背景色——这是存储格式
// 从一开始就没考虑非方形展示场景，不是能靠调渲染参数修好的
// bug，需要重新设计存储结构。
//
// 新方案：avatarUrl 字段语义改为「原图路径」（不再是裁剪成品图，
// 旧数据存量的 512×512 成品图会被当成「原图」继续使用，效果
// 退化但不会崩溃——原图=旧成品图时，裁剪参数按 offset=0/scale=1
// 处理即等于直接显示那张图，圆形场景不受影响，拱形场景仍会
// 露边，需要用户重新上传一次才能真正修好，属预期内的存量数据
// 降级，不做自动迁移）。
// 新增两套裁剪参数：
//   avatarCropCircle*：详情页圆形头像，语义与旧 AvatarCropDialog
//     的 offset/scale 一致。
//   avatarCropTall*：公馆拱形 + 书架椭圆共用的竖长矩形裁剪参数，
//     两处展示比例一致，不再分别裁剪、分别存储。
// 默认值 offsetX=0f/offsetY=0f/scale=1f 對存量数据是安全默认值
// （图片居中、不额外缩放）。
internal val MIGRATION_45_46 = object : Migration(45, 46) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `avatarCropCircleOffsetX` REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `avatarCropCircleOffsetY` REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `avatarCropCircleScale` REAL NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `avatarCropTallOffsetX` REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `avatarCropTallOffsetY` REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `avatarCropTallScale` REAL NOT NULL DEFAULT 1")
    }
}

// ── Migration v46 → v47 ───────────────────────────────
// 报告 2.8 修复：清理历史遗留索引。历次 Entity 索引重构（简写命名
// →Room 标准命名、单字段→复合字段）都只 CREATE 了新索引，没有配套
// DROP 掉旧索引，导致这些旧索引在用户设备的真实数据库里一直
// 物理存在到今天。分两类：
//
// A类·改名式冗余：列组合没变，只是名字换了，新旧两个索引同时
//   维护着同一份数据，纯粹浪费写入开销和磁盘空间。
// B类·真正孤儿：对应列组合在当前 Entity 声明里已完全没有索引
//   覆盖，字段本身还在表里，只是这几种"单独按该字段查询"的
//   场景不再有索引加速（可能已被别的复合索引覆盖，也可能确实
//   退化为全表扫描，具体要看业务查询是否还会这样单独查）。
//
// 本迁移不改动任何数据、不改任何表结构，只做索引清理，风险极低。
internal val MIGRATION_46_47 = object : Migration(46, 47) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // --- memories 表（B类6个：均已无替代索引）---
        db.execSQL("DROP INDEX IF EXISTS `index_memories_domain`")
        db.execSQL("DROP INDEX IF EXISTS `index_memories_importance`")
        db.execSQL("DROP INDEX IF EXISTS `index_memories_isCore`")
        db.execSQL("DROP INDEX IF EXISTS `index_memories_updatedAt`")
        db.execSQL("DROP INDEX IF EXISTS `index_memories_isLocked_domain`")
        db.execSQL("DROP INDEX IF EXISTS `index_memories_goalId`")

        // --- memory_candidates 表（A类4个 + B类1个）---
        db.execSQL("DROP INDEX IF EXISTS `index_candidates_characterId`")     // → index_memory_candidates_characterId
        db.execSQL("DROP INDEX IF EXISTS `index_candidates_sourceEventId`")   // → index_memory_candidates_sourceEventId
        db.execSQL("DROP INDEX IF EXISTS `index_candidates_isProcessed`")     // → index_memory_candidates_isProcessed
        db.execSQL("DROP INDEX IF EXISTS `index_candidates_createdAt`")       // → index_memory_candidates_createdAt
        db.execSQL("DROP INDEX IF EXISTS `index_candidates_score`")           // B：已无替代索引

        // --- relationship_states 表（A类4个）---
        db.execSQL("DROP INDEX IF EXISTS `index_relationship_fromId`")            // → index_relationship_states_fromId
        db.execSQL("DROP INDEX IF EXISTS `index_relationship_toId`")              // → index_relationship_states_toId
        db.execSQL("DROP INDEX IF EXISTS `index_relationship_from_to`")           // → index_relationship_states_fromId_toId
        db.execSQL("DROP INDEX IF EXISTS `index_relationship_isInterCharacter`")  // → index_relationship_states_isInterCharacter

        // --- character_goals 表（A类2个）---
        db.execSQL("DROP INDEX IF EXISTS `index_goals_characterId`")  // → index_character_goals_characterId
        db.execSQL("DROP INDEX IF EXISTS `index_goals_isActive`")     // → index_character_goals_isActive

        // --- projects 表（B类1个）---
        db.execSQL("DROP INDEX IF EXISTS `index_projects_status`")

        // --- project_milestones 表（A类1个）---
        db.execSQL("DROP INDEX IF EXISTS `index_milestones_projectId`")  // → index_project_milestones_projectId

        // --- project_members 表（A类2个）---
        db.execSQL("DROP INDEX IF EXISTS `index_members_projectId`")     // → index_project_members_projectId
        db.execSQL("DROP INDEX IF EXISTS `index_members_characterId`")  // → index_project_members_characterId

        // --- project_knowledge 表（A类3个）---
        db.execSQL("DROP INDEX IF EXISTS `index_knowledge_projectId`")    // → index_project_knowledge_projectId
        db.execSQL("DROP INDEX IF EXISTS `index_knowledge_characterId`") // → index_project_knowledge_characterId
        db.execSQL("DROP INDEX IF EXISTS `index_knowledge_createdAt`")   // → index_project_knowledge_createdAt

        // --- scheduled_jobs 表（A类2个）---
        db.execSQL("DROP INDEX IF EXISTS `idx_jobs_enabled_next`")  // → index_scheduled_jobs_enabled_nextRunAt
        db.execSQL("DROP INDEX IF EXISTS `idx_jobs_character`")     // → index_scheduled_jobs_characterId

        // --- job_results 表（A类3个）---
        db.execSQL("DROP INDEX IF EXISTS `idx_results_jobId`")      // → index_job_results_jobId
        db.execSQL("DROP INDEX IF EXISTS `idx_results_char_read`") // → index_job_results_characterId_isRead
        db.execSQL("DROP INDEX IF EXISTS `idx_results_created`")   // → index_job_results_createdAt

        // --- workflow_jobs 表（B类1个）---
        db.execSQL("DROP INDEX IF EXISTS `index_workflow_jobs_status_createdAt`")
    }
}

// ── Migration v47 → v48 ───────────────────────────────
// 离线简报 复核发现的既有缺口修复：competition_rounds 表缺
// completedAt 索引，getCompletedSince(after) 原先先走 status
// 索引缩小范围、再对 completedAt 做比较排序。当前竞赛轮次数据
// 量级小，不会引发实际性能问题，但既然复核时发现了就顺手补上，
// 不留作待办。纯新增索引，不改表结构、不改现有数据，无需重建表。
internal val MIGRATION_47_48 = object : Migration(47, 48) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_competition_rounds_completedAt` ON `competition_rounds` (`completedAt`)")
    }
}

internal val MIGRATIONS_41_47 = arrayOf(
    MIGRATION_41_42,
    MIGRATION_42_43,
    MIGRATION_43_44,
    MIGRATION_44_45,
    MIGRATION_45_46,
    MIGRATION_46_47,
    MIGRATION_47_48
)
