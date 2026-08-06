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
 * Migration 79 → 80 测试：私聊实时同步修复——private_chat_sessions 新增
 * notifiedCharacterIds 列。
 *
 * 验证 MIGRATION_79_80 为 private_chat_sessions 表新增
 * TEXT NOT NULL DEFAULT '' 列，且不破坏存量数据。
 *
 * 背景（详见 Migration79to80.kt 注释）：私聊动态播报此前用"近2小时内"的时间窗口
 * 判断要不要向角色播报"你最近和XX私聊过"，超窗口后只能靠角色自己检索跨 session
 * 记忆兜底，被动一方可能完全错过被告知的机会。新增 notifiedCharacterIds 列
 * （逗号分隔的 characterId 列表）后，播报逻辑从"按时间窗口查"改为"按未告知查"，
 * 存量会话默认回填为空字符串（视为"尚未告知任何角色"，迁移后首次对话补播一次）。
 *
 * 结构照抄 [Migration77to78Test]/[Migration78to79Test] 三段式，但因
 * private_chat_sessions 表在 Migration70to71.kt 才建表（v62 时尚不存在），
 * 测试2/3 从 v71（建表完成后的最早可用版本）起步，而非沿用其他迁移测试常见的 v62。
 *
 * 1. **testAllMigrations58to80Validate**：标准 `runMigrationsAndValidate`，从 v58 跑到
 *    v80，验证全链不抛异常且迁移后数据库结构与 80.json 期望 schema 一致。
 *    依赖 schemas/58.json + schemas/80.json（由 Room KSP 在编译时生成）。
 *
 * 2. **testMigration79to80AddsColumnWithDefaults**（防复发核心）：从 v71 库起步
 *    （private_chat_sessions 建表完成的最早版本），跑 71→79 链到达 v79 状态，
 *    确认新列不存在，再跑 MIGRATION_79_80，最后查库确认
 *    private_chat_sessions.notifiedCharacterIds 被正确添加且默认值为空字符串。
 *
 * 3. **testMigration79to80PreservesExistingRows**：验证 MIGRATION_79_80 对现存行的
 *    行为——纯 ADD COLUMN 不应改动任何现有数据，存量会话回填为空字符串
 *    （视为"尚未告知任何角色"，详见 Migration79to80.kt 注释）。
 *
 * ## 运行环境
 *
 * androidTest 目录，需真机/模拟器 `./gradlew :app:connectedAndroidTest`。
 * schemas/80.json 由 Room KSP 在编译时自动生成，需先跑一次编译确保
 * KSP 已生成到 v80+，否则 testAllMigrations58to80Validate 无法运行。
 * 其余测试不依赖 schemas/，可直接运行。
 */
@RunWith(AndroidJUnit4::class)
class Migration79to80Test {

    companion object {
        private const val TEST_DB_NAME = "migration-test-79-80.db"
    }

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    /**
     * 测试1：v58→v80 全链 runMigrationsAndValidate。
     *
     * 依赖：schemas/58.json + schemas/80.json（应用本批次后 build 生成）。
     */
    @Test
    fun testAllMigrations58to80Validate() {
        helper.createDatabase(TEST_DB_NAME, 58).close()

        val db = helper.runMigrationsAndValidate(
            TEST_DB_NAME,
            80,
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
            MIGRATION_78_79,
            MIGRATION_79_80,
        )
        db.close()
    }

    /**
     * 测试2（防复发核心）：建 v71 库（private_chat_sessions 建表完成的最早版本）→
     * 跑 71→79 链到达 v79 状态 → 确认新列不存在 → 跑 MIGRATION_79_80 →
     * 验证 private_chat_sessions.notifiedCharacterIds 被正确添加且默认值为空字符串。
     */
    @Test
    fun testMigration79to80AddsColumnWithDefaults() {
        val db = helper.createDatabase(TEST_DB_NAME, 62)

        // 起点从 71 改为 62：schemas 无 71.json（历史版本快照缺失，identityHash 无法重建）。
        // 先手动跑 62→71 链建出 private_chat_sessions 等表（v70→71 才建表），得到等价于 v71 的状态。
        MIGRATION_62_63.migrate(db)
        MIGRATION_63_64.migrate(db)
        MIGRATION_64_65.migrate(db)
        MIGRATION_65_66.migrate(db)
        MIGRATION_66_67.migrate(db)
        MIGRATION_67_68.migrate(db)
        MIGRATION_68_69.migrate(db)
        MIGRATION_69_70.migrate(db)
        MIGRATION_70_71.migrate(db)
        // 继续跑 71→79 链到达 v79 等价状态
        MIGRATION_71_72.migrate(db)
        MIGRATION_72_73.migrate(db)
        MIGRATION_73_74.migrate(db)
        MIGRATION_74_75.migrate(db)
        MIGRATION_75_76.migrate(db)
        MIGRATION_76_77.migrate(db)
        MIGRATION_77_78.migrate(db)
        MIGRATION_78_79.migrate(db)

        // 确认迁移前新列不存在
        assertNullColumn(db, "private_chat_sessions", "notifiedCharacterIds")

        // 跑 MIGRATION_79_80
        MIGRATION_79_80.migrate(db)

        // 验证新列存在且类型/约束正确
        val col = queryColumnInfo(db, "private_chat_sessions", "notifiedCharacterIds")
        assertNotNull("迁移后 private_chat_sessions.notifiedCharacterIds 列应存在", col)
        col?.let {
            assertEquals("TEXT", it.type)
            assertTrue("notifiedCharacterIds 应为 NOT NULL", it.notNull)
        }

        // 默认值验证：插入新行不指定新列，查默认值
        db.execSQL(
            """INSERT INTO `private_chat_sessions` (
                   `sessionId`, `pairId`, `startedAt`, `status`, `turnCount`
               ) VALUES (
                   'test-session-1', 'test-pair-1', 1700000000000, 'ACTIVE', 3
               )""".trimIndent()
        )
        db.query(
            "SELECT `notifiedCharacterIds` FROM `private_chat_sessions` WHERE `sessionId` = 'test-session-1'"
        ).use { c ->
            assertTrue("应查到插入行", c.moveToFirst())
            assertEquals(
                "notifiedCharacterIds 默认值应为空字符串",
                "",
                c.getString(0),
            )
        }

        db.close()
    }

    /**
     * 测试3：验证 MIGRATION_79_80 对现存行的行为——纯 ADD COLUMN 不破坏现有数据，
     * 存量会话回填为空字符串（视为"尚未告知任何角色"，迁移后首次对话触发补播）。
     *
     * 在 v71 库里插入 private_chat_sessions 历史行，跑 71→79 链 + MIGRATION_79_80 后
     * 确认历史行依然存在、内容未被篡改、且 notifiedCharacterIds 回填为空字符串。
     */
    @Test
    fun testMigration79to80PreservesExistingRows() {
        val db = helper.createDatabase(TEST_DB_NAME, 62)

        // 先跑 62→71 链建出 private_chat_sessions 等表（schemas 无 71.json，起点从 71 改为 62），
        // 再插入历史数据——否则表不存在，INSERT 会失败
        MIGRATION_62_63.migrate(db)
        MIGRATION_63_64.migrate(db)
        MIGRATION_64_65.migrate(db)
        MIGRATION_65_66.migrate(db)
        MIGRATION_66_67.migrate(db)
        MIGRATION_67_68.migrate(db)
        MIGRATION_68_69.migrate(db)
        MIGRATION_69_70.migrate(db)
        MIGRATION_70_71.migrate(db)

        // 插入一条历史私聊会话（此时 private_chat_sessions 已建表）
        db.execSQL(
            """INSERT INTO `private_chat_sessions` (
                   `sessionId`, `pairId`, `startedAt`, `status`, `turnCount`
               ) VALUES (
                   'session-history-1', 'pair-history-1', 1700000000000, 'COMPLETED', 5
               )""".trimIndent()
        )
        assertEquals(1, db.query("SELECT COUNT(*) FROM `private_chat_sessions`").use {
            it.moveToFirst(); it.getInt(0)
        })

        // 跑 71→79 链 + MIGRATION_79_80
        MIGRATION_71_72.migrate(db)
        MIGRATION_72_73.migrate(db)
        MIGRATION_73_74.migrate(db)
        MIGRATION_74_75.migrate(db)
        MIGRATION_75_76.migrate(db)
        MIGRATION_76_77.migrate(db)
        MIGRATION_77_78.migrate(db)
        MIGRATION_78_79.migrate(db)
        MIGRATION_79_80.migrate(db)

        // 历史行依然存在
        assertEquals(1, db.query("SELECT COUNT(*) FROM `private_chat_sessions`").use {
            it.moveToFirst(); it.getInt(0)
        })
        // 历史行内容未被篡改，且 notifiedCharacterIds 回填为空字符串
        // （视为"尚未告知任何角色"，迁移后首次对话会补播一次，符合设计预期）
        db.query(
            "SELECT `status`, `turnCount`, `notifiedCharacterIds` FROM `private_chat_sessions` WHERE `sessionId` = 'session-history-1'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("COMPLETED", c.getString(0))
            assertEquals(5, c.getInt(1))
            assertEquals(
                "存量行 notifiedCharacterIds 应回填为空字符串，触发迁移后首次补播",
                "",
                c.getString(2),
            )
        }

        db.close()
    }

    // ── 内部工具方法（照抄 Migration77to78Test/Migration78to79Test）──────

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
