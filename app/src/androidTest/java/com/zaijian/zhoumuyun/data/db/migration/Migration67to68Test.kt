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
 * 「心迹」数据层迁移测试：验证 MIGRATION_67_68（新增 `agent_activity_events` 表）。
 *
 * 见《Window B 执行方案 v1.1》2.2.2。三方法结构照抄 [Migration66to67Test]：
 *
 * 1. **testAllMigrations58to68Validate**：标准 `runMigrationsAndValidate`，从 v58 跑到
 *    v68，验证全链不抛异常且迁移后数据库结构与 68.json 期望 schema 一致。
 *    依赖 schemas/58.json（已提交）+ schemas/68.json（由 Room KSP 在编译时生成，
 *    应用本批次后 build 一次即可生成）。
 *
 * 2. **testMigration67to68CreatesAgentActivityEventsTable**（防复发核心）：从 v62 库起步，
 *    先跑 62→67 链到达 v67 状态（此时 agent_activity_events 表不存在），确认迁移前
 *    该表不存在，再跑 MIGRATION_67_68，最后查库确认表与各列/索引被正确创建。
 *
 * 3. **testMigration67to68PreservesExistingRows**：验证 MIGRATION_67_68 对现存行的
 *    行为——纯新增表不应改动任何现有数据。在 v62 库里插入 messages /
 *    roundtable_messages 历史行，跑 62→67 链 + 67→68 后确认历史行依然存在。
 *
 * ## 为什么从 createDatabase(62) 起步
 *
 * schemas 目录只提交了 58.json / 62.json 两个历史快照（67.json 已提交但 68.json
 * 待 build 生成）。`createDatabase(67)` 可行（67.json 已提交），但 62→67 链已经
 * 在 Migration66to67Test 里验证过，沿用 createDatabase(62) 起步可保证与该测试同一
 * 起点口径，减少分歧。详见 Migration66to67Test 类 KDoc「为什么测试 2/3 从 62 起步」。
 *
 * ## 运行环境
 *
 * androidTest 目录，需真机/模拟器 `./gradlew :app:connectedAndroidTest`。
 * schemas/68.json 由 Room KSP 在编译时自动生成（Entity 改动后首次 build 触发）。
 */
@RunWith(AndroidJUnit4::class)
class Migration67to68Test {

    companion object {
        private const val TEST_DB_NAME = "migration-test-67-68.db"
    }

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    /**
     * 测试1：v58→v68 全链 runMigrationsAndValidate。
     *
     * 依赖：schemas/58.json（已提交）+ schemas/68.json（应用本批次后 build 生成）。
     */
    @Test
    fun testAllMigrations58to68Validate() {
        helper.createDatabase(TEST_DB_NAME, 58).close()

        val db = helper.runMigrationsAndValidate(
            TEST_DB_NAME,
            68,
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
        )
        db.close()
    }

    /**
     * 测试2（防复发核心）：建 v62 库 → 跑 62→67 链到达 v67 状态 → 确认
     * agent_activity_events 表不存在 → 跑 MIGRATION_67_68 → 验证表/列/索引被正确创建。
     *
     * 防复发：若有人删除 MIGRATION_67_68 或其 CREATE 语句被改坏，迁移后查不到表/列，
     * 本测试失败。
     */
    @Test
    fun testMigration67to68CreatesAgentActivityEventsTable() {
        val db = helper.createDatabase(TEST_DB_NAME, 62)

        // 跑 62→67 链到达 v67 等价状态（agent_activity_events 表尚不存在）
        MIGRATION_62_63.migrate(db)
        MIGRATION_63_64.migrate(db)
        MIGRATION_64_65.migrate(db)
        MIGRATION_65_66.migrate(db)
        MIGRATION_66_67.migrate(db)

        assertFalse(
            "迁移前 agent_activity_events 表应不存在",
            tableExists(db, "agent_activity_events"),
        )

        // 跑 MIGRATION_67_68（CREATE TABLE + 4 个索引）
        MIGRATION_67_68.migrate(db)

        // 表已创建
        assertTrue(
            "迁移后 agent_activity_events 表应存在",
            tableExists(db, "agent_activity_events"),
        )

        // 逐列校验：列名 / 类型 / 可空性，严格对照 AgentActivityEventEntity
        // String? → TEXT 可空；String/Int/Long（非空）→ TEXT/INTEGER NOT NULL；Long? → INTEGER 可空
        val expectedColumns = listOf(
            ColumnExpectation("id", "TEXT", notNull = true),
            ColumnExpectation("characterId", "INTEGER", notNull = true),
            ColumnExpectation("sessionRef", "TEXT", notNull = true),
            ColumnExpectation("sceneType", "TEXT", notNull = true),
            ColumnExpectation("eventType", "TEXT", notNull = true),
            ColumnExpectation("toolName", "TEXT", notNull = false),
            ColumnExpectation("toolParamsJson", "TEXT", notNull = false),
            ColumnExpectation("attemptIndex", "INTEGER", notNull = true),
            ColumnExpectation("outcome", "TEXT", notNull = false),
            ColumnExpectation("outputSummary", "TEXT", notNull = false),
            ColumnExpectation("errorMessage", "TEXT", notNull = false),
            ColumnExpectation("decisionNote", "TEXT", notNull = false),
            ColumnExpectation("startedAt", "INTEGER", notNull = true),
            ColumnExpectation("completedAt", "INTEGER", notNull = false),
            ColumnExpectation("createdAt", "INTEGER", notNull = true),
        )
        for (expect in expectedColumns) {
            val col = queryColumnInfo(db, "agent_activity_events", expect.name)
            assertNotNull("迁移后 agent_activity_events.${expect.name} 列应存在", col)
            col?.let {
                assertEquals(expect.name, it.name)
                assertEquals(expect.type, it.type)
                assertEquals(
                    "agent_activity_events.${expect.name} notNull 应为 ${expect.notNull}",
                    expect.notNull,
                    it.notNull,
                )
            }
        }

        // 索引校验：命名严格对照 Room 自动生成格式 index_<表名>_<列名...>
        for (indexName in listOf(
            "index_agent_activity_events_characterId",
            "index_agent_activity_events_characterId_createdAt",
            "index_agent_activity_events_sessionRef",
            "index_agent_activity_events_eventType",
        )) {
            assertTrue(
                "迁移后索引 $indexName 应存在",
                indexExists(db, indexName),
            )
        }

        db.close()
    }

    /**
     * 测试3：验证 MIGRATION_67_68 对现存行的行为——纯新增表不破坏现有数据。
     *
     * 在 v62 库里插入 messages / roundtable_messages 历史行，跑 62→67 链 +
     * MIGRATION_67_68 后确认历史行依然存在（不被删除/重建）。
     */
    @Test
    fun testMigration67to68PreservesExistingRows() {
        val db = helper.createDatabase(TEST_DB_NAME, 62)

        db.execSQL(
            """INSERT INTO `messages` (`id`,`characterId`,`role`,`content`,`createdAt`) VALUES
               ('msg-history-1', 1, '1', '历史消息正文', 1700000000000)""".trimIndent()
        )
        db.execSQL(
            """INSERT INTO `roundtable_messages` (
                  `id`,`roundtableId`,`speakerId`,`speakerName`,`content`,`createdAt`,`turnIndex`
               ) VALUES (
                  'rt-history-1', '1_2', '1', '角色一', '历史圆桌消息', 1700000000000, 0
               )""".trimIndent()
        )
        assertEquals(1, db.query("SELECT COUNT(*) FROM `messages`").use {
            it.moveToFirst(); it.getInt(0)
        })
        assertEquals(1, db.query("SELECT COUNT(*) FROM `roundtable_messages`").use {
            it.moveToFirst(); it.getInt(0)
        })

        // 跑 62→67 链 + MIGRATION_67_68（纯新增表，不删除/重建任何行）
        MIGRATION_62_63.migrate(db)
        MIGRATION_63_64.migrate(db)
        MIGRATION_64_65.migrate(db)
        MIGRATION_65_66.migrate(db)
        MIGRATION_66_67.migrate(db)
        MIGRATION_67_68.migrate(db)

        // 历史行依然存在
        assertEquals(1, db.query("SELECT COUNT(*) FROM `messages`").use {
            it.moveToFirst(); it.getInt(0)
        })
        assertEquals(1, db.query("SELECT COUNT(*) FROM `roundtable_messages`").use {
            it.moveToFirst(); it.getInt(0)
        })

        db.close()
    }

    // ── 内部工具方法 ──────────────────────────────────────────

    private data class ColumnExpectation(
        val name: String,
        val type: String,
        val notNull: Boolean,
    )

    /** 查询 sqlite_master 确认指定表是否存在。 */
    private fun tableExists(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        tableName: String,
    ): Boolean = db.query(
        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='$tableName'"
    ).use { it.moveToFirst(); it.getInt(0) > 0 }

    /** 查询 sqlite_master 确认指定索引是否存在。 */
    private fun indexExists(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        indexName: String,
    ): Boolean = db.query(
        "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='$indexName'"
    ).use { it.moveToFirst(); it.getInt(0) > 0 }

    /**
     * 查询 PRAGMA table_info(tableName)，返回指定列的属性（不存在返回 null）。
     * PRAGMA table_info 返回字段：cid / name / type / notnull / dflt_value / pk。
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
                    name    = cursor.getString(nameIdx),
                    type    = cursor.getString(cursor.getColumnIndex("type")) ?: "",
                    notNull = cursor.getInt(cursor.getColumnIndex("notnull")) == 1,
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
    )
}
