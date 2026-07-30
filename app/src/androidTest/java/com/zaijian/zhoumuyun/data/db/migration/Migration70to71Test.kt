package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zaijian.zhoumuyun.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration 70 → 71 测试：角色间私聊功能（方案_角色间私聊_v2-5）
 *
 * 验证 MIGRATION_70_71 新增三张独立表：
 * - private_chat_pairs（配对配置）
 * - private_chat_messages（消息本体）
 * - private_chat_sessions（会话状态）
 *
 * 结构照抄 [Migration69to70Test] 三段式：
 *
 * 1. **testAllMigrations58to71Validate**：标准 `runMigrationsAndValidate`，从 v58 跑到
 *    v71，验证全链不抛异常且迁移后数据库结构与 71.json 期望 schema 一致。
 *    依赖 schemas/58.json + schemas/71.json（由 Room KSP 在编译时生成）。
 *
 * 2. **testMigration70to71CreatesPrivateChatTables**（防复发核心）：从 v62 库起步，
 *    先跑 62→70 链到达 v70 状态（此时三张私聊表不存在），确认迁移前这些表不存在，
 *    再跑 MIGRATION_70_71，最后查库确认表/列/索引被正确创建，并做一次插入回读验证
 *    表结构可正常写入与查询。
 *
 * 3. **testMigration70to71PreservesExistingRows**：验证 MIGRATION_70_71 对现存行的
 *    行为——纯新增表不应改动任何现有数据。在 v62 库里插入 messages /
 *    roundtable_messages 历史行，跑 62→70 链 + 70→71 后确认历史行依然存在。
 *
 * ## 运行环境
 *
 * androidTest 目录，需真机/模拟器 `./gradlew :app:connectedAndroidTest`。
 * schemas/71.json 由 Room KSP 在编译时自动生成。
 */
@RunWith(AndroidJUnit4::class)
class Migration70to71Test {

    companion object {
        private const val TEST_DB_NAME = "migration-test-70-71.db"
    }

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    /**
     * 测试1：v58→v71 全链 runMigrationsAndValidate。
     *
     * 依赖：schemas/58.json + schemas/71.json（应用本批次后 build 生成）。
     */
    @Test
    fun testAllMigrations58to71Validate() {
        helper.createDatabase(TEST_DB_NAME, 58).close()

        val db = helper.runMigrationsAndValidate(
            TEST_DB_NAME,
            71,
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
        )
        db.close()
    }

    /**
     * 测试2（防复发核心）：建 v62 库 → 跑 62→70 链到达 v70 状态 → 确认
     * 三张私聊表不存在 → 跑 MIGRATION_70_71 → 验证表/列/索引被正确创建，
     * 并插入行回读，确认表可正常写入与按索引列查询。
     */
    @Test
    fun testMigration70to71CreatesPrivateChatTables() {
        val db = helper.createDatabase(TEST_DB_NAME, 62)

        // 跑 62→70 链到达 v70 等价状态（三张私聊表尚不存在）
        MIGRATION_62_63.migrate(db)
        MIGRATION_63_64.migrate(db)
        MIGRATION_64_65.migrate(db)
        MIGRATION_65_66.migrate(db)
        MIGRATION_66_67.migrate(db)
        MIGRATION_67_68.migrate(db)
        MIGRATION_68_69.migrate(db)
        MIGRATION_69_70.migrate(db)

        assertFalse(
            "迁移前 private_chat_pairs 表应不存在",
            tableExists(db, "private_chat_pairs"),
        )
        assertFalse(
            "迁移前 private_chat_messages 表应不存在",
            tableExists(db, "private_chat_messages"),
        )
        assertFalse(
            "迁移前 private_chat_sessions 表应不存在",
            tableExists(db, "private_chat_sessions"),
        )

        // 跑 MIGRATION_70_71（3 张 CREATE TABLE + 2 个索引）
        MIGRATION_70_71.migrate(db)

        // ── private_chat_pairs 表 ──────────────────────────────
        assertTrue("迁移后 private_chat_pairs 表应存在", tableExists(db, "private_chat_pairs"))

        val expectedPairColumns = listOf(
            ColumnExpectation("pairId", "TEXT", notNull = true),
            ColumnExpectation("characterIdA", "INTEGER", notNull = true),
            ColumnExpectation("characterIdB", "INTEGER", notNull = true),
            ColumnExpectation("enabled", "INTEGER", notNull = true),
            ColumnExpectation("maxTurnsPerSession", "INTEGER", notNull = true),
            ColumnExpectation("maxSessionsPerDay", "INTEGER", notNull = true),
            ColumnExpectation("cooldownMinutes", "INTEGER", notNull = true),
            ColumnExpectation("sessionsUsedToday", "INTEGER", notNull = true),
            ColumnExpectation("usedTodayResetAt", "INTEGER", notNull = true),
            ColumnExpectation("lastSessionAt", "INTEGER", notNull = true),
        )
        for (expect in expectedPairColumns) {
            val col = queryColumnInfo(db, "private_chat_pairs", expect.name)
            assertNotNull("迁移后 private_chat_pairs.${expect.name} 列应存在", col)
            col?.let {
                assertEquals(expect.name, it.name)
                assertEquals(expect.type, it.type)
                assertEquals(
                    "private_chat_pairs.${expect.name} notNull 应为 ${expect.notNull}",
                    expect.notNull,
                    it.notNull,
                )
            }
        }

        // ── private_chat_pairs 唯一索引 ────────────────────────
        assertTrue(
            "迁移后唯一索引 index_private_chat_pairs_characterIdA_characterIdB 应存在",
            indexExists(db, "index_private_chat_pairs_characterIdA_characterIdB"),
        )

        // ── private_chat_messages 表 ───────────────────────────
        assertTrue("迁移后 private_chat_messages 表应存在", tableExists(db, "private_chat_messages"))

        val expectedMessageColumns = listOf(
            ColumnExpectation("id", "INTEGER", notNull = true),
            ColumnExpectation("pairId", "TEXT", notNull = true),
            ColumnExpectation("senderCharacterId", "INTEGER", notNull = true),
            ColumnExpectation("content", "TEXT", notNull = true),
            ColumnExpectation("timestamp", "INTEGER", notNull = true),
            ColumnExpectation("sessionId", "TEXT", notNull = true),
            ColumnExpectation("turnIndexInSession", "INTEGER", notNull = true),
            ColumnExpectation("triggerSource", "TEXT", notNull = true),
        )
        for (expect in expectedMessageColumns) {
            val col = queryColumnInfo(db, "private_chat_messages", expect.name)
            assertNotNull("迁移后 private_chat_messages.${expect.name} 列应存在", col)
            col?.let {
                assertEquals(expect.name, it.name)
                assertEquals(expect.type, it.type)
                assertEquals(
                    "private_chat_messages.${expect.name} notNull 应为 ${expect.notNull}",
                    expect.notNull,
                    it.notNull,
                )
            }
        }

        // ── private_chat_messages 索引 ─────────────────────────
        assertTrue(
            "迁移后索引 index_private_chat_messages_pairId_timestamp 应存在",
            indexExists(db, "index_private_chat_messages_pairId_timestamp"),
        )

        // ── private_chat_sessions 表 ───────────────────────────
        assertTrue("迁移后 private_chat_sessions 表应存在", tableExists(db, "private_chat_sessions"))

        val expectedSessionColumns = listOf(
            ColumnExpectation("sessionId", "TEXT", notNull = true),
            ColumnExpectation("pairId", "TEXT", notNull = true),
            ColumnExpectation("startedAt", "INTEGER", notNull = true),
            ColumnExpectation("status", "TEXT", notNull = true),
            ColumnExpectation("turnCount", "INTEGER", notNull = true),
            ColumnExpectation("errorMessage", "TEXT", notNull = false),
        )
        for (expect in expectedSessionColumns) {
            val col = queryColumnInfo(db, "private_chat_sessions", expect.name)
            assertNotNull("迁移后 private_chat_sessions.${expect.name} 列应存在", col)
            col?.let {
                assertEquals(expect.name, it.name)
                assertEquals(expect.type, it.type)
                assertEquals(
                    "private_chat_sessions.${expect.name} notNull 应为 ${expect.notNull}",
                    expect.notNull,
                    it.notNull,
                )
            }
        }

        // ── 功能性回读：插入行并查询，确认表结构可正常读写 ──

        // private_chat_pairs
        db.execSQL(
            """INSERT INTO `private_chat_pairs` (
                  `pairId`,`characterIdA`,`characterIdB`,`enabled`,`maxTurnsPerSession`,
                  `maxSessionsPerDay`,`cooldownMinutes`,`sessionsUsedToday`,`usedTodayResetAt`,`lastSessionAt`
               ) VALUES (
                  '1_7', 1, 7, 1, 6, 8, 10, 0, 1700000000000, 0
               )""".trimIndent()
        )
        db.query(
            "SELECT `pairId`,`characterIdA`,`characterIdB`,`enabled` FROM `private_chat_pairs` WHERE `characterIdA` = 1 AND `characterIdB` = 7"
        ).use { c ->
            assertTrue("按 (characterIdA, characterIdB) 索引应查到插入行", c.moveToFirst())
            assertEquals("1_7", c.getString(0))
            assertEquals(1, c.getInt(1))
            assertEquals(7, c.getInt(2))
            assertEquals(1, c.getInt(3))
            assertFalse("应仅有一行", c.moveToNext())
        }

        // private_chat_messages
        db.execSQL(
            """INSERT INTO `private_chat_messages` (
                  `id`,`pairId`,`senderCharacterId`,`content`,`timestamp`,`sessionId`,`turnIndexInSession`,`triggerSource`
               ) VALUES (
                  1, '1_7', 1, '你好呀', 1700000000000, 'sess-1', 0, 'manual'
               )""".trimIndent()
        )
        db.query(
            "SELECT `content`,`senderCharacterId` FROM `private_chat_messages` WHERE `pairId` = '1_7' ORDER BY `timestamp`"
        ).use { c ->
            assertTrue("按 (pairId, timestamp) 索引应查到插入行", c.moveToFirst())
            assertEquals("你好呀", c.getString(0))
            assertEquals(1, c.getInt(1))
            assertFalse("应仅有一行", c.moveToNext())
        }

        // private_chat_messages：验收修复回归测试——id 是 autoGenerate 主键，
        // 不显式指定 id 连续插入两行，验证 SQLite rowid 自增真正生效（不会因为
        // 两行都拿到默认值/相同值而唯一约束冲突）。这条用例专门用来防止
        // `INTEGER NOT NULL` + 表级 `PRIMARY KEY(id)` 这种"看着像主键、实际不是
        // rowid 别名"的写法悄悄回归——那种写法下这里会插入失败或两行拿到同一个 id。
        db.execSQL(
            """INSERT INTO `private_chat_messages` (
                  `pairId`,`senderCharacterId`,`content`,`timestamp`,`sessionId`,`turnIndexInSession`,`triggerSource`
               ) VALUES (
                  '1_7', 7, '你也好呀', 1700000001000, 'sess-1', 1, 'reply_chain'
               )""".trimIndent()
        )
        db.query(
            "SELECT DISTINCT `id` FROM `private_chat_messages` WHERE `pairId` = '1_7' ORDER BY `id`"
        ).use { c ->
            val ids = mutableListOf<Long>()
            while (c.moveToNext()) ids.add(c.getLong(0))
            assertEquals("两次不指定 id 的插入应各自拿到不同的自增 id", 2, ids.size)
            assertTrue("自增 id 应严格递增", ids[1] > ids[0])
        }

        // private_chat_sessions
        db.execSQL(
            """INSERT INTO `private_chat_sessions` (
                  `sessionId`,`pairId`,`startedAt`,`status`,`turnCount`,`errorMessage`
               ) VALUES (
                  'sess-1', '1_7', 1700000000000, 'in_progress', 0, NULL
               )""".trimIndent()
        )
        db.query(
            "SELECT `status`,`errorMessage` FROM `private_chat_sessions` WHERE `sessionId` = 'sess-1'"
        ).use { c ->
            assertTrue("应查到插入的 session 行", c.moveToFirst())
            assertEquals("in_progress", c.getString(0))
            assertTrue("errorMessage 应为 null", c.isNull(1))
            assertFalse("应仅有一行", c.moveToNext())
        }

        db.close()
    }

    /**
     * 测试3：验证 MIGRATION_70_71 对现存行的行为——纯新增表不破坏现有数据。
     *
     * 在 v62 库里插入 messages / roundtable_messages 历史行，跑 62→70 链 +
     * MIGRATION_70_71 后确认历史行依然存在。
     */
    @Test
    fun testMigration70to71PreservesExistingRows() {
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

        // 跑 62→70 链 + MIGRATION_70_71（纯新增表，不删除/重建任何行）
        MIGRATION_62_63.migrate(db)
        MIGRATION_63_64.migrate(db)
        MIGRATION_64_65.migrate(db)
        MIGRATION_65_66.migrate(db)
        MIGRATION_66_67.migrate(db)
        MIGRATION_67_68.migrate(db)
        MIGRATION_68_69.migrate(db)
        MIGRATION_69_70.migrate(db)
        MIGRATION_70_71.migrate(db)

        // 历史行依然存在
        assertEquals(1, db.query("SELECT COUNT(*) FROM `messages`").use {
            it.moveToFirst(); it.getInt(0)
        })
        assertEquals(1, db.query("SELECT COUNT(*) FROM `roundtable_messages`").use {
            it.moveToFirst(); it.getInt(0)
        })

        db.close()
    }

    // ── 内部工具方法（照抄 Migration69to70Test）──────────────────

    private data class ColumnExpectation(
        val name: String,
        val type: String,
        val notNull: Boolean,
    )

    private fun tableExists(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        tableName: String,
    ): Boolean = db.query(
        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='$tableName'"
    ).use { it.moveToFirst(); it.getInt(0) > 0 }

    private fun indexExists(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        indexName: String,
    ): Boolean = db.query(
        "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='$indexName'"
    ).use { it.moveToFirst(); it.getInt(0) > 0 }

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
