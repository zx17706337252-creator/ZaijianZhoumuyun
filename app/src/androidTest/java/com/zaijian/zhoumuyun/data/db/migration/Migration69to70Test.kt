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
 * Window A-1 · L2 记忆索引层迁移测试：验证 MIGRATION_69_70（新增 `memory_tags` 表）。
 *
 * 结构照抄 [Migration68to69Test] 三段式：
 *
 * 1. **testAllMigrations58to70Validate**：标准 `runMigrationsAndValidate`，从 v58 跑到
 *    v70，验证全链不抛异常且迁移后数据库结构与 70.json 期望 schema 一致。
 *    依赖 schemas/58.json（已提交）+ schemas/70.json（由 Room KSP 在编译时生成）。
 *
 * 2. **testMigration69to70CreatesMemoryTagsTable**（防复发核心）：从 v62 库起步，
 *    先跑 62→69 链到达 v69 状态（此时 memory_tags 表不存在），确认迁移前该表不存在，
 *    再跑 MIGRATION_69_70，最后查库确认表/列/索引被正确创建，并做一次插入回读验证
 *    表结构可正常写入与查询。
 *
 * 3. **testMigration69to70PreservesExistingRows**：验证 MIGRATION_69_70 对现存行的
 *    行为——纯新增表不应改动任何现有数据。在 v62 库里插入 messages /
 *    roundtable_messages 历史行，跑 62→69 链 + 69→70 后确认历史行依然存在。
 *
 * ## 运行环境
 *
 * androidTest 目录，需真机/模拟器 `./gradlew :app:connectedAndroidTest`。
 * schemas/70.json 由 Room KSP 在编译时自动生成。
 */
@RunWith(AndroidJUnit4::class)
class Migration69to70Test {

    companion object {
        private const val TEST_DB_NAME = "migration-test-69-70.db"
    }

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    /**
     * 测试1：v58→v70 全链 runMigrationsAndValidate。
     *
     * 依赖：schemas/58.json（已提交）+ schemas/70.json（应用本批次后 build 生成）。
     */
    @Test
    fun testAllMigrations58to70Validate() {
        helper.createDatabase(TEST_DB_NAME, 58).close()

        val db = helper.runMigrationsAndValidate(
            TEST_DB_NAME,
            70,
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
        )
        db.close()
    }

    /**
     * 测试2（防复发核心）：建 v62 库 → 跑 62→69 链到达 v69 状态 → 确认
     * memory_tags 表不存在 → 跑 MIGRATION_69_70 → 验证表/列/索引被正确创建，
     * 并插入一行回读，确认表可正常写入与按索引列查询。
     */
    @Test
    fun testMigration69to70CreatesMemoryTagsTable() {
        val db = helper.createDatabase(TEST_DB_NAME, 62)

        // 跑 62→69 链到达 v69 等价状态（memory_tags 表尚不存在）
        MIGRATION_62_63.migrate(db)
        MIGRATION_63_64.migrate(db)
        MIGRATION_64_65.migrate(db)
        MIGRATION_65_66.migrate(db)
        MIGRATION_66_67.migrate(db)
        MIGRATION_67_68.migrate(db)
        MIGRATION_68_69.migrate(db)

        assertFalse(
            "迁移前 memory_tags 表应不存在",
            tableExists(db, "memory_tags"),
        )

        // 跑 MIGRATION_69_70（CREATE TABLE + 3 个索引）
        MIGRATION_69_70.migrate(db)

        // 表已创建
        assertTrue("迁移后 memory_tags 表应存在", tableExists(db, "memory_tags"))

        // ── memory_tags 表逐列校验 ──────────────────────────────
        // 列名 / 类型 / 可空性，严格对照 MemoryTagEntity
        val expectedColumns = listOf(
            ColumnExpectation("id", "TEXT", notNull = true),
            ColumnExpectation("memoryId", "TEXT", notNull = true),
            ColumnExpectation("characterId", "INTEGER", notNull = true),
            ColumnExpectation("tag", "TEXT", notNull = true),
            ColumnExpectation("weight", "INTEGER", notNull = true),
            ColumnExpectation("createdAt", "INTEGER", notNull = true),
        )
        for (expect in expectedColumns) {
            val col = queryColumnInfo(db, "memory_tags", expect.name)
            assertNotNull("迁移后 memory_tags.${expect.name} 列应存在", col)
            col?.let {
                assertEquals(expect.name, it.name)
                assertEquals(expect.type, it.type)
                assertEquals(
                    "memory_tags.${expect.name} notNull 应为 ${expect.notNull}",
                    expect.notNull,
                    it.notNull,
                )
            }
        }

        // ── 索引校验（索引名严格对照 Room 自动生成格式 index_<表名>_<列名...>）──
        for (indexName in listOf(
            "index_memory_tags_characterId",
            "index_memory_tags_characterId_tag",
            "index_memory_tags_memoryId",
        )) {
            assertTrue(
                "迁移后索引 $indexName 应存在",
                indexExists(db, indexName),
            )
        }

        // ── 功能性回读：插入一行并按索引列查询，确认表结构可正常读写 ──
        db.execSQL(
            """INSERT INTO `memory_tags` (
                  `id`,`memoryId`,`characterId`,`tag`,`weight`,`createdAt`
               ) VALUES (
                  'mt-test-1', 'mem-test-1', 7, '角色互动', 9, 1700000000000
               )""".trimIndent()
        )
        db.query(
            "SELECT `memoryId`,`characterId`,`tag`,`weight` FROM `memory_tags` WHERE `characterId` = 7 AND `tag` = '角色互动'"
        ).use { c ->
            assertTrue("按 (characterId, tag) 索引应查到插入行", c.moveToFirst())
            assertEquals("mem-test-1", c.getString(0))
            assertEquals(7, c.getInt(1))
            assertEquals("角色互动", c.getString(2))
            assertEquals(9, c.getInt(3))
            assertFalse("应仅有一行", c.moveToNext())
        }

        db.close()
    }

    /**
     * 测试3：验证 MIGRATION_69_70 对现存行的行为——纯新增表不破坏现有数据。
     *
     * 在 v62 库里插入 messages / roundtable_messages 历史行，跑 62→69 链 +
     * MIGRATION_69_70 后确认历史行依然存在。
     */
    @Test
    fun testMigration69to70PreservesExistingRows() {
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

        // 跑 62→69 链 + MIGRATION_69_70（纯新增表，不删除/重建任何行）
        MIGRATION_62_63.migrate(db)
        MIGRATION_63_64.migrate(db)
        MIGRATION_64_65.migrate(db)
        MIGRATION_65_66.migrate(db)
        MIGRATION_66_67.migrate(db)
        MIGRATION_67_68.migrate(db)
        MIGRATION_68_69.migrate(db)
        MIGRATION_69_70.migrate(db)

        // 历史行依然存在
        assertEquals(1, db.query("SELECT COUNT(*) FROM `messages`").use {
            it.moveToFirst(); it.getInt(0)
        })
        assertEquals(1, db.query("SELECT COUNT(*) FROM `roundtable_messages`").use {
            it.moveToFirst(); it.getInt(0)
        })

        db.close()
    }

    // ── 内部工具方法（照抄 Migration68to69Test）──────────────────

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
