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
 * Window C · 技能系统迁移测试：验证 MIGRATION_68_69（新增 `skills` / `skill_edit_log` 两张表）。
 *
 * 结构照抄 [Migration67to68Test] 三段式：
 *
 * 1. **testAllMigrations58to69Validate**：标准 `runMigrationsAndValidate`，从 v58 跑到
 *    v69，验证全链不抛异常且迁移后数据库结构与 69.json 期望 schema 一致。
 *    依赖 schemas/58.json（已提交）+ schemas/69.json（由 Room KSP 在编译时生成）。
 *
 * 2. **testMigration68to69CreatesSkillTables**（防复发核心）：从 v62 库起步，
 *    先跑 62→68 链到达 v68 状态（此时 skills / skill_edit_log 两表不存在），确认迁移前
 *    这些表不存在，再跑 MIGRATION_68_69，最后查库确认表/列/索引被正确创建。
 *
 * 3. **testMigration68to69PreservesExistingRows**：验证 MIGRATION_68_69 对现存行的
 *    行为——纯新增表不应改动任何现有数据。在 v62 库里插入 messages /
 *    roundtable_messages 历史行，跑 62→68 链 + 68→69 后确认历史行依然存在。
 *
 * ## 运行环境
 *
 * androidTest 目录，需真机/模拟器 `./gradlew :app:connectedAndroidTest`。
 * schemas/69.json 由 Room KSP 在编译时自动生成。
 */
@RunWith(AndroidJUnit4::class)
class Migration68to69Test {

    companion object {
        private const val TEST_DB_NAME = "migration-test-68-69.db"
    }

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    /**
     * 测试1：v58→v69 全链 runMigrationsAndValidate。
     *
     * 依赖：schemas/58.json（已提交）+ schemas/69.json（应用本批次后 build 生成）。
     */
    @Test
    fun testAllMigrations58to69Validate() {
        helper.createDatabase(TEST_DB_NAME, 58).close()

        val db = helper.runMigrationsAndValidate(
            TEST_DB_NAME,
            69,
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
        )
        db.close()
    }

    /**
     * 测试2（防复发核心）：建 v62 库 → 跑 62→68 链到达 v68 状态 → 确认
     * skills / skill_edit_log 表不存在 → 跑 MIGRATION_68_69 → 验证表/列/索引被正确创建。
     */
    @Test
    fun testMigration68to69CreatesSkillTables() {
        val db = helper.createDatabase(TEST_DB_NAME, 62)

        // 跑 62→68 链到达 v68 等价状态（skills / skill_edit_log 表尚不存在）
        MIGRATION_62_63.migrate(db)
        MIGRATION_63_64.migrate(db)
        MIGRATION_64_65.migrate(db)
        MIGRATION_65_66.migrate(db)
        MIGRATION_66_67.migrate(db)
        MIGRATION_67_68.migrate(db)

        assertFalse(
            "迁移前 skills 表应不存在",
            tableExists(db, "skills"),
        )
        assertFalse(
            "迁移前 skill_edit_log 表应不存在",
            tableExists(db, "skill_edit_log"),
        )

        // 跑 MIGRATION_68_69（CREATE TABLE + 4 个索引）
        MIGRATION_68_69.migrate(db)

        // 表已创建
        assertTrue("迁移后 skills 表应存在", tableExists(db, "skills"))
        assertTrue("迁移后 skill_edit_log 表应存在", tableExists(db, "skill_edit_log"))

        // ── skills 表逐列校验 ──────────────────────────────────
        // 列名 / 类型 / 可空性，严格对照 SkillEntity
        val expectedSkillColumns = listOf(
            ColumnExpectation("id", "TEXT", notNull = true),
            ColumnExpectation("characterId", "INTEGER", notNull = true),
            ColumnExpectation("name", "TEXT", notNull = true),
            ColumnExpectation("shortDescriptor", "TEXT", notNull = true),
            ColumnExpectation("fullContent", "TEXT", notNull = true),
            ColumnExpectation("category", "TEXT", notNull = false),
            ColumnExpectation("status", "TEXT", notNull = true),
            ColumnExpectation("sourceType", "TEXT", notNull = true),
            ColumnExpectation("version", "INTEGER", notNull = true),
            ColumnExpectation("usageCount", "INTEGER", notNull = true),
            ColumnExpectation("successCount", "INTEGER", notNull = true),
            ColumnExpectation("failureCount", "INTEGER", notNull = true),
            ColumnExpectation("lastUsedAt", "INTEGER", notNull = false),
            ColumnExpectation("relatedSkillIds", "TEXT", notNull = false),
            ColumnExpectation("createdAt", "INTEGER", notNull = true),
            ColumnExpectation("updatedAt", "INTEGER", notNull = true),
        )
        for (expect in expectedSkillColumns) {
            val col = queryColumnInfo(db, "skills", expect.name)
            assertNotNull("迁移后 skills.${expect.name} 列应存在", col)
            col?.let {
                assertEquals(expect.name, it.name)
                assertEquals(expect.type, it.type)
                assertEquals(
                    "skills.${expect.name} notNull 应为 ${expect.notNull}",
                    expect.notNull,
                    it.notNull,
                )
            }
        }

        // ── skill_edit_log 表逐列校验 ──────────────────────────
        val expectedLogColumns = listOf(
            ColumnExpectation("id", "TEXT", notNull = true),
            ColumnExpectation("skillId", "TEXT", notNull = true),
            ColumnExpectation("changeSummary", "TEXT", notNull = true),
            ColumnExpectation("actor", "TEXT", notNull = true),
            ColumnExpectation("reason", "TEXT", notNull = false),
            ColumnExpectation("timestamp", "INTEGER", notNull = true),
        )
        for (expect in expectedLogColumns) {
            val col = queryColumnInfo(db, "skill_edit_log", expect.name)
            assertNotNull("迁移后 skill_edit_log.${expect.name} 列应存在", col)
            col?.let {
                assertEquals(expect.name, it.name)
                assertEquals(expect.type, it.type)
                assertEquals(
                    "skill_edit_log.${expect.name} notNull 应为 ${expect.notNull}",
                    expect.notNull,
                    it.notNull,
                )
            }
        }

        // ── 索引校验 ──────────────────────────────────────────
        for (indexName in listOf(
            "index_skills_characterId",
            "index_skills_characterId_status",
            "index_skill_edit_log_skillId",
            "index_skill_edit_log_skillId_timestamp",
        )) {
            assertTrue(
                "迁移后索引 $indexName 应存在",
                indexExists(db, indexName),
            )
        }

        db.close()
    }

    /**
     * 测试3：验证 MIGRATION_68_69 对现存行的行为——纯新增表不破坏现有数据。
     *
     * 在 v62 库里插入 messages / roundtable_messages 历史行，跑 62→68 链 +
     * MIGRATION_68_69 后确认历史行依然存在。
     */
    @Test
    fun testMigration68to69PreservesExistingRows() {
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

        // 跑 62→68 链 + MIGRATION_68_69（纯新增表，不删除/重建任何行）
        MIGRATION_62_63.migrate(db)
        MIGRATION_63_64.migrate(db)
        MIGRATION_64_65.migrate(db)
        MIGRATION_65_66.migrate(db)
        MIGRATION_66_67.migrate(db)
        MIGRATION_67_68.migrate(db)
        MIGRATION_68_69.migrate(db)

        // 历史行依然存在
        assertEquals(1, db.query("SELECT COUNT(*) FROM `messages`").use {
            it.moveToFirst(); it.getInt(0)
        })
        assertEquals(1, db.query("SELECT COUNT(*) FROM `roundtable_messages`").use {
            it.moveToFirst(); it.getInt(0)
        })

        db.close()
    }

    // ── 内部工具方法（照抄 Migration67to68Test）──────────────────

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
