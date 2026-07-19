package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zaijian.zhoumuyun.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 日程系统批次1 测试：验证 MIGRATION_62_63（scheduled_jobs 表新增 description 列）的正确性。
 *
 * ## 为什么需要这个测试
 *
 * ScheduledJobEntity 新增了 `description: String? = null` 字段（工单型任务 mode B 专用），
 * 对应的 MIGRATION_62_63 用 `ALTER TABLE scheduled_jobs ADD COLUMN description TEXT DEFAULT NULL`
 * 补这一列。若未来有人误删 MIGRATION_62_63 或改动了 ALTER 语句，从 v62 升级到 v63 的用户
 * 会因 Room `validateMigration()` 校验列缺失而崩溃（`fallbackToDestructiveMigration()` 不兜底
 * schema 校验失败，仅兜底迁移路径缺失/执行异常）。本测试守护这条迁移路径不被回归。
 *
 * ## 测试策略（两个方法，各自独立验证一个维度）
 *
 * 1. **testAllMigrations58to63Validate**：标准 `runMigrationsAndValidate`，从 v58 跑到
 *    v63，验证全链不抛异常且迁移后数据库结构与 63.json 期望 schema 一致。覆盖
 *    "迁移执行不崩溃 + schema 校验通过"维度。依赖 schemas/58.json 和 schemas/63.json。
 *
 * 2. **testMigration62to63AddsDescriptionColumn**（防复发核心）：`createDatabase(62)`
 *    会按 62.json 建表（此时 description 列尚不存在，因为 62.json 对应的 Entity 还没
 *    这个字段）。本测试在建库后查 `PRAGMA table_info(scheduled_jobs)` 确认 description
 *    列不存在，再跑 MIGRATION_62_63，最后查库确认 description 列被正确创建——类型为
 *    TEXT、可空（notnull=0）、默认值为 NULL。若 MIGRATION_62_63 被删除或其 ALTER 语句
 *    被改坏，迁移后查不到该列，本测试失败。覆盖"列被正确添加"维度。
 *
 * ## 运行环境
 *
 * MigrationTestHelper 依赖 Android 框架的 SupportSQLiteOpenHelper，故放在 androidTest
 * 目录，需在真机/模拟器上以 `./gradlew :app:connectedAndroidTest` 或 Android Studio
 * 的 androidTest 配置运行。schemas/63.json 由 Room KSP 在编译时自动生成（Entity 改动后
 * 首次编译触发）；schemas/58.json 已存在于仓库中，用于 createDatabase(58) 建初始库。
 *
 * 比照 Migration61to62Test.kt 的结构写法，但因 62→63 只是纯新增可空列（没有 61→62 的
 * 索引重建/去重逻辑），测试方法适配为"列存在性 + 列属性"校验，无需 31 个索引全量核对。
 */
@RunWith(AndroidJUnit4::class)
class Migration62to63Test {

    companion object {
        private const val TEST_DB_NAME = "migration-test-62-63.db"
    }

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    /**
     * 测试1：v58→v63 全链 runMigrationsAndValidate。
     *
     * 验证 MIGRATION_58_59 / 59_60 / 60_61 / 61_62 / 62_63 五个迁移连跑不抛异常，
     * 且迁移后数据库结构与 63.json 期望 schema 完全一致（Room 逐表比对表/列/索引/外键）。
     * 这是验收标准第 10 条"数据库 migration 62→63 在已有测试数据的设备上执行不报错"
     * 的自动化对应——runMigrationsAndValidate 内部已逐表校验，执行到此行无异常即通过。
     */
    @Test
    fun testAllMigrations58to63Validate() {
        // createDatabase(58) 按 58.json 建表+建索引，模拟 v58 理想状态
        helper.createDatabase(TEST_DB_NAME, 58).close()

        // 跑 v58→v63 全部迁移，并用 63.json 验证结构一致性
        val db = helper.runMigrationsAndValidate(
            TEST_DB_NAME,
            63,
            /* expectMigrations = */ true,
            MIGRATION_58_59,
            MIGRATION_59_60,
            MIGRATION_60_61,
            MIGRATION_61_62,
            MIGRATION_62_63,
        )
        db.close()
        // 若执行到此行无异常，即验证通过（runMigrationsAndValidate 内部已逐表校验）
    }

    /**
     * 测试2（防复发核心）：建 v62 库（无 description 列）→ 跑 MIGRATION_62_63 → 验证列被添加。
     *
     * createDatabase(62) 按 62.json 建表，此时 ScheduledJobEntity 还没有 description 字段，
     * 故 scheduled_jobs 表里没有 description 列。手动跑 MIGRATION_62_63 后，ALTER TABLE
     * 应新增这一列。查 PRAGMA table_info(scheduled_jobs) 确认：
     * - 列存在
     * - 类型为 TEXT
     * - notnull=0（可空，工单型任务 description 必填由业务层校验，DB 层允许 NULL
     *   以兼容工具型任务）
     * - dflt_value=NULL（默认 NULL，历史行回填 NULL）
     *
     * 防复发机制：若有人删除 MIGRATION_62_63 或其 ALTER 语句被改坏，迁移后查不到
     * description 列，本测试的 assertNotNull(col) 会失败。
     */
    @Test
    fun testMigration62to63AddsDescriptionColumn() {
        // 1. 建 v62 库（description 列尚不存在）
        val db = helper.createDatabase(TEST_DB_NAME, 62)
        assertFalse(
            "迁移前 scheduled_jobs.description 列应不存在",
            columnExists(db, "scheduled_jobs", "description"),
        )

        // 2. 跑 MIGRATION_62_63（ALTER TABLE ADD COLUMN description TEXT DEFAULT NULL）
        MIGRATION_62_63.migrate(db)

        // 3. 查库验证 description 列已创建且属性正确
        val col = queryColumnInfo(db, "scheduled_jobs", "description")
        assertNotNull("迁移后 scheduled_jobs.description 列应存在", col)
        col?.let {
            assertEquals("description", it.name)
            assertEquals("TEXT", it.type)
            assertFalse(
                "description 应可空（notnull=0），工具型任务此字段为 NULL",
                it.notNull,
            )
            assertNull(
                "description 默认值应为 NULL（dflt_value=null）",
                it.defaultValue,
            )
        }

        db.close()
    }

    /**
     * 测试3：验证 MIGRATION_62_63 对现存行的行为——历史行的 description 应为 NULL。
     *
     * 纯新增可空列不应改动任何现有数据。本测试在 v62 库里插入一条 scheduled_jobs
     * 历史行（模拟升级前已存在的任务），跑 MIGRATION_62_63 后确认：
     * - 历史行依然存在（不被删除/重建）
     * - 历史行的 description 字段为 NULL（DEFAULT NULL 回填）
     * 这条覆盖了"纯新增列不破坏现有数据"的维度，是 schema 变更类迁移的标准验收点。
     */
    @Test
    fun testMigration62to63PreservesExistingRows() {
        val db = helper.createDatabase(TEST_DB_NAME, 62)

        // 插入一条历史 scheduled_jobs 行（62.json 的 createSql 不含 description 列，
        // 也不含 DEFAULT 子句，所有 NOT NULL 列必须显式提供值）：
        // - characterId / title / toolName / toolParamsJson / nextRunAt / createdAt 必填
        // - enabled / cloudSynced 是 INTEGER（Boolean 存为 0/1）
        // - repeatIntervalMs / lastRunAt / executedBy / lockedUntil 可空
        db.execSQL(
            """INSERT INTO `scheduled_jobs` (
                  `id`,`characterId`,`title`,`toolName`,`toolParamsJson`,
                  `enabled`,`repeatIntervalMs`,`nextRunAt`,`lastRunAt`,
                  `executedBy`,`createdAt`,`cloudSynced`,`lockedUntil`
               ) VALUES (
                  'job-history-1', 1, '每日天气', 'web_search', '{}',
                  1, 86400000, 1700000000000, NULL,
                  'local', 1700000000000, 1, NULL
               )""".trimIndent()
        )
        // 确认插入成功
        assertEquals(1, db.query("SELECT COUNT(*) FROM `scheduled_jobs`").use {
            it.moveToFirst(); it.getInt(0)
        })

        // 跑 MIGRATION_62_63
        MIGRATION_62_63.migrate(db)

        // 历史行依然存在（1 条，未被删除/重建）
        assertEquals(1, db.query("SELECT COUNT(*) FROM `scheduled_jobs`").use {
            it.moveToFirst(); it.getInt(0)
        })
        // 历史行的 description 为 NULL（DEFAULT NULL 回填，工具型任务本就无描述）
        val desc = db.query(
            "SELECT `description` FROM `scheduled_jobs` WHERE `id`='job-history-1'"
        ).use {
            it.moveToFirst(); if (it.isNull(0)) null else it.getString(0)
        }
        assertNull("历史行的 description 应为 NULL", desc)

        db.close()
    }

    // ── 内部工具方法 ──────────────────────────────────────────

    /**
     * 查询 sqlite_master / PRAGMA table_info 确认指定表的指定列是否存在。
     */
    private fun columnExists(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        tableName: String,
        columnName: String,
    ): Boolean = queryColumnInfo(db, tableName, columnName) != null

    /**
     * 查询 PRAGMA table_info(tableName)，返回指定列的属性（不存在返回 null）。
     *
     * PRAGMA table_info 返回字段：cid / name / type / notnull / dflt_value / pk。
     * - notnull: 0=可空, 1=NOT NULL
     * - dflt_value: 默认值字符串，无默认值时为 null（注意：DEFAULT NULL 在
     *   SQLite 中 dflt_value 也是 null，与"无默认值"在 PRAGMA 层面无法区分，
     *   但行为等价——都是 NULL）
     */
    private fun queryColumnInfo(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        tableName: String,
        columnName: String,
    ): ColumnInfo? = db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
        val nameIdx = cursor.getColumnIndex("name")
        if (nameIdx < 0) return@use null
        var result: ColumnInfo? = null
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIdx) == columnName) {
                result = ColumnInfo(
                    name         = cursor.getString(nameIdx),
                    type         = cursor.getString(cursor.getColumnIndex("type")) ?: "",
                    notNull      = cursor.getInt(cursor.getColumnIndex("notnull")) == 1,
                    defaultValue = cursor.getString(cursor.getColumnIndex("dflt_value")),
                )
                break
            }
        }
        result
    }

    private data class ColumnInfo(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val defaultValue: String?,
    )
}
