package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase


// ── Migration v21 → v22 ────────────────────────────────
// D2.5 排卵期失败概率系统：
//   - 删除 pregnancy_state.cycle_days 列（改为 PregnancyState.CYCLE_DAYS = 30 常量）
//   - 新增 consecutive_fail_count（连续排卵期失败次数，成功怀孕后归零）
//   - 新增 last_failure_injected_at（跨周期背景情绪注入冷却时间戳）
//
// SQLite 不支持 DROP COLUMN（API 34 以下），用重建表方式删除 cycle_days。
internal val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. 建临时表（无 cycle_days，含新两列）
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `pregnancy_state_new` (
                `characterId`             INTEGER NOT NULL PRIMARY KEY,
                `isPregnant`              INTEGER NOT NULL DEFAULT 0,
                `pregnancyStartedAt`      INTEGER,
                `consecutiveFailCount`    INTEGER NOT NULL DEFAULT 0,
                `lastFailureInjectedAt`   INTEGER
            )
        """.trimIndent())

        // 2. 迁移存量数据（cycle_days 直接丢弃）
        db.execSQL("""
            INSERT INTO `pregnancy_state_new`
                (`characterId`, `isPregnant`, `pregnancyStartedAt`)
            SELECT `characterId`, `isPregnant`, `pregnancyStartedAt`
            FROM `pregnancy_state`
        """.trimIndent())

        // 3. 替换旧表
        db.execSQL("DROP TABLE `pregnancy_state`")
        db.execSQL("ALTER TABLE `pregnancy_state_new` RENAME TO `pregnancy_state`")
    }
}

// ── Migration v22 → v23 ────────────────────────────────
// D2.6 孕期体验与生命事件系统：
//   - pregnancy_state 新增 miscarried_at（流产时间戳，null=未流产）
//   - memories 新增 is_eternal（永恒状态记忆标记，生育记录用，永不蒸馏删除）
//
// 开发阶段亦可直接 fallbackToDestructiveMigration 清库重建。
internal val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // pregnancy_state：加 miscarried_at 列
        db.execSQL(
            "ALTER TABLE `pregnancy_state` ADD COLUMN `miscarriedAt` INTEGER"
        )

        // memories：加 is_eternal 列（Boolean 存为 INTEGER，0=false，1=true）
        db.execSQL(
            "ALTER TABLE `memories` ADD COLUMN `isEternal` INTEGER NOT NULL DEFAULT 0"
        )

        // 为 is_eternal 建索引，加速每次 Prompt 注入时的永恒记忆查询
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memories_characterId_isEternal` ON `memories` (`characterId`, `isEternal`)"
        )
    }
}

// ── Migration v23 → v24 ────────────────────────────────
// D3 孕期共设系统：
//   - pregnancy_answers 新增 slotIndex（槽位序号，WORLDVIEW/PERSONA
//     拆两条用 0/1，NAME_PREF/WORRY 固定 0）
//   - pregnancy_answers 新增 isLocked（槽位是否已锁定，取代原先
//     用 pregnancyStartedAt 做边界判定的逻辑）
//   - 新建 pregnancy_pending_question 表（问答配对状态追踪，
//     单行覆盖写，PK = motherCharacterId）
internal val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // pregnancy_answers：加 slotIndex / isLocked 两列
        db.execSQL(
            "ALTER TABLE `pregnancy_answers` ADD COLUMN `slotIndex` INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "ALTER TABLE `pregnancy_answers` ADD COLUMN `isLocked` INTEGER NOT NULL DEFAULT 0"
        )

        // pregnancy_pending_question：新建表
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `pregnancy_pending_question` (
                `motherCharacterId` INTEGER NOT NULL PRIMARY KEY,
                `questionType`      TEXT NOT NULL,
                `slotIndex`         INTEGER NOT NULL,
                `questionText`      TEXT NOT NULL,
                `askedAt`           INTEGER NOT NULL
            )
        """.trimIndent())
    }
}

// ── Migration v24 → v25 ───────────────────────────────
// daughter_character 表：D4 女儿人格系统
// 三列 JSON 分开存储（identity / stateLayer / customEnums），
// 方便运行时只更新 stateLayerJson 不重写整张卡。
internal val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `daughter_character` (
                `motherCharacterId` INTEGER NOT NULL PRIMARY KEY,
                `daughterName`      TEXT NOT NULL,
                `identityJson`      TEXT NOT NULL,
                `stateLayerJson`    TEXT NOT NULL,
                `customEnumsJson`   TEXT NOT NULL,
                `generatedAt`       INTEGER NOT NULL
            )
        """.trimIndent())
    }
}

// ── Migration v25 → v26 ───────────────────────────────
// daughter_id_allocator 表：女儿角色编号发号器（D4 触发点接入 Part 2）。
// 单行表（id 恒为 0），nextId 记录下一个可分配的女儿 characterId，
// 起始值 1000，避免和预设角色（1-9）冲突。见
// DaughterIdAllocatorEntity 文件头注释。
internal val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `daughter_id_allocator` (
                `id`     INTEGER NOT NULL PRIMARY KEY,
                `nextId` INTEGER NOT NULL
            )
        """.trimIndent())
    }
}

// ── Migration v26 → v27 ───────────────────────────────
// character_identity 表：新增 name 列（D4 触发点接入 Part 3）。
//
// 背景：预设角色（蒂法/露娜等 1-9 号）的名字此前硬编码在
// CharacterConfig.kt 的 DefaultCharacters 列表里，不在数据库中，
// 用户无法在 app 内修改；女儿角色的名字（daughterName）此前只
// 存在 daughter_character 表，没有同步到 character_identity，
// UI 读不到。这次统一加一列 name，作为所有角色（预设 + 女儿）
// 名字的唯一持久化存储位置，写一次、改一次都直接落库，
// 不依赖每次启动时重新计算/拼装。
//
// 回填策略：
//   1) ALTER TABLE 加列，默认空字符串；
//   2) 对 1-9 号预设角色，先 UPDATE 已存在的行；
//      再 INSERT 那些用户从未打开过"角色管理"页、
//      表里还没有对应行的角色（避免回填漏掉）。
//   3) 女儿角色（1000+）此次不需要回填：Part 2 接入时女儿的
//      identity 行是和这次迁移同批生成的代码一起上线的，
//      翻译函数 toCharacterIdentityEntity() 已经同步补上了
//      name 字段的写入（见 DaughterIdentity.kt），新生成的
//      女儿会自带正确的名字，不存在迁移前的历史脏数据。
internal val MIGRATION_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `character_identity` ADD COLUMN `name` TEXT NOT NULL DEFAULT ''"
        )

        val presetNames = listOf(
            1 to "蒂法",
            2 to "露娜",
            3 to "伊芙",
            4 to "宥熙",
            5 to "索菲娅",
            6 to "顾澜",
            7 to "明媚",
            8 to "莫婉凝",
            9 to "江凡",
        )

        presetNames.forEach { (id, name) ->
            // 已有行：回填名字（参数化查询，避免 name 含特殊字符导致 SQL 解析异常）
            db.execSQL(
                "UPDATE `character_identity` SET `name` = ? WHERE `characterId` = ?",
                // 编译修复：name(String) 与 id(Int) 混合，显式标注 Array<Any?> 避免泛型推断警告
                arrayOf<Any?>(name, id)
            )
            // 还没有行（用户从没存过这个角色的设置）：补一行，
            // 其余字段使用表定义的默认值（均为 NOT NULL DEFAULT ''）。
            // M-10 修复：原先用字符串模板把 $name 直接拼入 SQL，presetNames 当前
            // 虽全为不含单引号的中文，但属于隐患——一旦未来加入含特殊字符的名字会
            // 触发 SQLiteException 导致迁移失败。改为与上面 UPDATE 一致的参数化查询。
            db.execSQL(
                """
                INSERT INTO `character_identity` (`characterId`, `name`)
                SELECT ?, ?
                WHERE NOT EXISTS (
                    SELECT 1 FROM `character_identity` WHERE `characterId` = ?
                )
                """.trimIndent(),
                // 编译修复：id(Int) 与 name(String) 混合，显式标注 Array<Any?> 避免泛型推断警告
                arrayOf<Any?>(id, name, id)
            )
        }
    }
}

// ── Migration v27 → v28 ───────────────────────────────
//
// D4 触发点接入 Part 4：daughter_character 表新增 daughterCharacterId 列。
//
// 背景：女儿注册成角色资料表（character_identity）独立一行时，会从
// DaughterIdAllocator 拿到一个全新编号（1000+）。但这个编号此前只写进
// character_identity 表，没有回写到 daughter_character 表——也就是说，
// 给定女儿自己的 characterId，没有任何路径能反查到她在 daughter_character
// 表里的那一行（拿不到 stateLayerJson / customEnumsJson）。
//
// ChatViewModel.sendMessage() 组装 CharacterConfig 时，currentCharacterId
// 就是女儿自己的 ID，查 DefaultCharacters（预设角色固定列表）必然查不到，
// 必须有这一列才能继续往 daughter_character 表反查。
//
// 历史数据：本次迁移前如果已经生成过女儿（daughter_character 表有行），
// 这些行的 daughterCharacterId 会是 NULL——因为她们注册时这一列还不存在，
// 没有回填来源。新增女儿（迁移之后生成）会在 ChatViewModel.onIdentityRegister
// 回调里自动回填，不受影响。
internal val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `daughter_character` ADD COLUMN `daughterCharacterId` INTEGER DEFAULT NULL"
        )
    }
}

// ── Migration v28 → v29 ───────────────────────────────
//
// 怀孕弹窗触发重构：pregnancy_state 新增 fertileWindowConsentAsked 列。
//
// 背景：原 D2 判定链对 1-6 号角色用关键词触发 + 静默判定，全程不弹窗。
// 新方案改为三重门（关系阶段 CORE + 排卵期 FERTILE + AI 语义判定 YES）
// 全部满足才弹出确认弹窗，仅适用于 characterId >= 1000（第二代/第三代
// 女儿）；1-6 号保留原关键词链路作兜底，不读写这一列。
//
// 这一列标记"本次排卵期窗口内是否已经弹过同意弹窗"，防止同一排卵期
// 内反复弹窗打扰；离开排卵期窗口后由调用方（PregnancyTriggerManager.
// shouldEvaluateFertileWindowConsent）负责清回 false，供下次排卵期
// 重新判定。默认 0（false），存量数据不受影响。
internal val MIGRATION_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `pregnancy_state` ADD COLUMN `fertileWindowConsentAsked` INTEGER NOT NULL DEFAULT 0"
        )
    }
}

// ── Migration v29 → v30 ───────────────────────────────
//
// 多步骤工作流系统 Step 1（数据层）：
//   ① workflow_jobs：工作流任务主表（状态机 RUNNING/COMPLETED/FAILED/TIMEOUT，
//      currentStep/maxSteps/deadlineAt 双重防护）
//   ② workflow_step_results：单步执行记录表，供引擎续跑回放 + TaskCenterScreen 展示
//
// 详见 WorkflowJobEntity.kt / WorkflowStepResultEntity.kt 文件头注释。
internal val MIGRATION_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `workflow_jobs` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `characterId` INTEGER NOT NULL,
                `goal` TEXT NOT NULL,
                `status` TEXT NOT NULL DEFAULT 'RUNNING',
                `currentStep` INTEGER NOT NULL DEFAULT 0,
                `maxSteps` INTEGER NOT NULL DEFAULT 8,
                `startedAt` INTEGER NOT NULL,
                `deadlineAt` INTEGER NOT NULL,
                `completedAt` INTEGER,
                `resultSummary` TEXT,
                `failReason` TEXT,
                `isReported` INTEGER NOT NULL DEFAULT 0,
                `createdAt` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_workflow_jobs_characterId_status` ON `workflow_jobs` (`characterId`, `status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_workflow_jobs_status_createdAt` ON `workflow_jobs` (`status`, `createdAt`)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `workflow_step_results` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `jobId` TEXT NOT NULL,
                `stepIndex` INTEGER NOT NULL,
                `toolName` TEXT,
                `toolParamsJson` TEXT NOT NULL DEFAULT '{}',
                `success` INTEGER NOT NULL,
                `output` TEXT,
                `errorMessage` TEXT,
                `decidedNextAction` TEXT,
                `startedAt` INTEGER NOT NULL,
                `completedAt` INTEGER,
                `createdAt` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_workflow_step_results_jobId_stepIndex` ON `workflow_step_results` (`jobId`, `stepIndex`)")
    }
}

// ── Migration v30 → v31 ───────────────────────────────
//
// daughter_character + generatorVersion 列：
// 纯回溯/调试字段，记录某条女儿记录是用哪一版 D4 生成器/Prompt
// 产出的，不影响任何运行时业务逻辑。已有历史数据回填默认值
// "d4-v1"（当前唯一存在过的生成器版本，回填后语义准确）。
internal val MIGRATION_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `daughter_character` ADD COLUMN `generatorVersion` TEXT NOT NULL DEFAULT 'd4-v1'"
        )
    }
}

internal val MIGRATIONS_21_30 = arrayOf(
    MIGRATION_21_22,
    MIGRATION_22_23,
    MIGRATION_23_24,
    MIGRATION_24_25,
    MIGRATION_25_26,
    MIGRATION_26_27,
    MIGRATION_27_28,
    MIGRATION_28_29,
    MIGRATION_29_30,
    MIGRATION_30_31
)
