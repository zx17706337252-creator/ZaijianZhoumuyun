package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zaijian.zhoumuyun.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration 77 → 78 测试：A8-1 修复——competition_rounds 新增 judgeRoundtableBroadcastSkipped 列。
 *
 * 验证 MIGRATION_77_78 为 competition_rounds 表新增 INTEGER NOT NULL DEFAULT 0 列，
 * 且不破坏存量数据。
 *
 * 结构照抄 [Migration75to76Test] 三段式：
 *
 * 1. **testAllMigrations58to78Validate**：标准 `runMigrationsAndValidate`，从 v58 跑到
 *    v78，验证全链不抛异常且迁移后数据库结构与 78.json 期望 schema 一致。
 *    依赖 schemas/58.json + schemas/78.json（由 Room KSP 在编译时生成）。
 *
 * 2. **testMigration77to78AddsColumnWithDefaults**（防复发核心）：从 v62 库起步，
 *    先跑 62→77 链到达 v77 状态，确认新列不存在，再跑 MIGRATION_77_78，
 *    最后查库确认 competition_rounds.judgeRoundtableBroadcastSkipped 被正确添加
 *    且默认值为 0。
 *
 * 3. **testMigration77to78PreservesExistingRows**：验证 MIGRATION_77_78 对现存行的
 *    行为——纯 ADD COLUMN 不应改动任何现有数据。
 *
 * ## 运行环境
 *
 * androidTest 目录，需真机/模拟器 `./gradlew :app:connectedAndroidTest`。
 * schemas/78.json 由 Room KSP 在编译时自动生成。
 *
 * **注意**：压缩包未附 schemas/ 目录（Room KSP 生成），需先跑一次编译确保
 * KSP 已生成到 v78+，否则 testAllMigrations58to78Validate 无法运行。
 * 其余测试不依赖 schemas/，可直接运行。
 */
@RunWith(AndroidJUnit4::class)
class Migration77to78Test {

    companion object {
        private const val TEST_DB_NAME = "migration-test-77-78.db"
    }

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    /**
     * 测试1：v58→v78 全链 runMigrationsAndValidate。
     *
     * 依赖：schemas/58.json + schemas/78.json（应用本批次后 build 生成）。
     */
    @Test
    fun testAllMigrations58to78Validate() {
        helper.createDatabase(TEST_DB_NAME, 58).close()

        val db = helper.runMigrationsAndValidate(
            TEST_DB_NAME,
            78,
            /* expectMigrations = */ true,
            MIGRATION_58_59,
            MIGRATION_59_60,
            MIGRATION_60_61,
            MIGRATION_61_62,
            MIGRATION_62_63,
            MIGRATION_63_64,
            MIGRATION_64_65,
            MIGRATION_65_66,
            MIGRATION_66_67,
            MIGRATION_67_68,
            MIGRATION_68_69,
            MIGRATION_69_70,
            MIGRATION_70_71,
            MIGRATION_71_72,
            MIGRATION_72_73,
            MIGRATION_73_74,
            MIGRATION_74_75,
            MIGRATION_75_76,
            MIGRATION_76_77,
            MIGRATION_77_78,
        )
        db.close()
    }

    /**
     * 测试2（防复发核心）：建 v62 库 → 跑 62→77 链到达 v77 状态 → 确认新列不存在 →
     * 跑 MIGRATION_77_78 → 验证 competition_rounds.judgeRoundtableBroadcastSkipped
     * 列被正确添加且默认值为 0。
     */
    @Test
    fun testMigration77to78AddsColumnWithDefaults() {
        val db = helper.createDatabase(TEST_DB_NAME, 62)

        // 跑 62→77 链到达 v77 等价状态
        MIGRATION_62_63.migrate(db)
        MIGRATION_63_64.migrate(db)
        MIGRATION_64_65.migrate(db)
        MIGRATION_65_66.migrate(db)
        MIGRATION_66_67.migrate(db)
        MIGRATION_67_68.migrate(db)
        MIGRATION_68_69.migrate(db)
        MIGRATION_69_70.migrate(db)
        MIGRATION_70_71.migrate(db)
        MIGRATION_71_72.migrate(db)
        MIGRATION_72_73.migrate(db)
        MIGRATION_73_74.migrate(db)
        MIGRATION_74_75.migrate(db)
        MIGRATION_75_76.migrate(db)
        MIGRATION_76_77.migrate(db)

        // 确认迁移前新列不存在
        assertNullColumn(db, "competition_rounds", "judgeRoundtableBroadcastSkipped")

        // 跑 MIGRATION_77_78
        MIGRATION_77_78.migrate(db)

        // 验证新列存在且类型/约束正确
        val col = queryColumnInfo(db, "competition_rounds", "judgeRoundtableBroadcastSkipped")
        assertNotNull("迁移后 competition_rounds.judgeRoundtableBroadcastSkipped 列应存在", col)
        col?.let {
            assertEquals("INTEGER", it.type)
            assertTrue("judgeRoundtableBroadcastSkipped 应为 NOT NULL", it.notNull)
        }

        // 默认值验证：插入新行不指定新列，查默认值
        db.execSQL(
            """INSERT INTO `competition_rounds` (
                   `id`, `projectDomain`, `topic`, `judgeCharacterId`,
                   `participantIdsJson`, `status`, `createdAt`
               ) VALUES (
                   'test-round-1', '测试方向', '测试命题', 1,
                   '[1,3,5]', 'COMPLETED', 1700000000000
               )""".trimIndent()
        )
        db.query(
            "SELECT `judgeRoundtableBroadcastSkipped` FROM `competition_rounds` WHERE `id` = 'test-round-1'"
        ).use { c ->
            assertTrue("应查到插入行", c.moveToFirst())
            assertEquals(
                "judgeRoundtableBroadcastSkipped 默认值应为 0（false）",
                0,
                c.getInt(0),
            )
        }

        db.close()
    }

    /**
     * 测试3：验证 MIGRATION_77_78 对现存行的行为——纯 ADD COLUMN 不破坏现有数据。
     *
     * 在 v62 库里插入 competition_rounds 历史行，跑 62→77 链 + MIGRATION_77_78 后
     * 确认历史行依然存在且内容未被篡改。
     */
    @Test
    fun testMigration77to78PreservesExistingRows() {
        val db = helper.createDatabase(TEST_DB_NAME, 62)

        // 插入一条历史竞赛轮次（competition_rounds 在 v31-40 建表，v62 时已存在）
        db.execSQL(
            """INSERT INTO `competition_rounds` (
                   `id`, `projectDomain`, `topic`, `judgeCharacterId`,
                   `participantIdsJson`, `status`, `createdAt`
               ) VALUES (
                   'round-history-1', '历史方向', '历史命题', 2,
                   '[2,4,6]', 'COMPLETED', 1700000000000
               )""".trimIndent()
        )
        assertEquals(1, db.query("SELECT COUNT(*) FROM `competition_rounds`").use {
            it.moveToFirst(); it.getInt(0)
        })

        // 跑 62→77 链 + MIGRATION_77_78
        MIGRATION_62_63.migrate(db)
        MIGRATION_63_64.migrate(db)
        MIGRATION_64_65.migrate(db)
        MIGRATION_65_66.migrate(db)
        MIGRATION_66_67.migrate(db)
        MIGRATION_67_68.migrate(db)
        MIGRATION_68_69.migrate(db)
        MIGRATION_69_70.migrate(db)
        MIGRATION_70_71.migrate(db)
        MIGRATION_71_72.migrate(db)
        MIGRATION_72_73.migrate(db)
        MIGRATION_73_74.migrate(db)
        MIGRATION_74_75.migrate(db)
        MIGRATION_75_76.migrate(db)
        MIGRATION_76_77.migrate(db)
        MIGRATION_77_78.migrate(db)

        // 历史行依然存在
        assertEquals(1, db.query("SELECT COUNT(*) FROM `competition_rounds`").use {
            it.moveToFirst(); it.getInt(0)
        })
        // 历史行内容未被篡改
        db.query(
            "SELECT `topic`, `status`, `judgeRoundtableBroadcastSkipped` FROM `competition_rounds` WHERE `id` = 'round-history-1'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("历史命题", c.getString(0))
            assertEquals("COMPLETED", c.getString(1))
            assertEquals(
                "存量行 judgeRoundtableBroadcastSkipped 应回填为默认值 0",
                0,
                c.getInt(2),
            )
        }

        db.close()
    }

    // ── 内部工具方法（照抄 Migration75to76Test）──────────────────

    private data class ColumnInfo(
        val name: String,
        val type: String,
        val notNull: Boolean,
    )

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
                    name    = cursor.getString(nameIdx),
                    type    = cursor.getString(cursor.getColumnIndex("type")) ?: "",
                    notNull = cursor.getInt(cursor.getColumnIndex("notnull")) == 1,
                )
                break
            }
        }
        result
    }

    private fun assertNullColumn(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        tableName: String,
        columnName: String,
    ) {
        val col = queryColumnInfo(db, tableName, columnName)
        assertNull("迁移前 $tableName.$columnName 列应不存在", col)
    }
}
