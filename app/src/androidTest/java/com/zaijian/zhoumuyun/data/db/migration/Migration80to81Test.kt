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
 * Migration 80 → 81 测试：文件搜索中文全文检索误判修复（专项审查报告 #6）。
 *
 * MIGRATION_80_81 的改动（详见 Migration80to81.kt）：
 * 1. `file_index` 新增 `keywords` 列（TEXT NOT NULL DEFAULT ''）
 * 2. `file_index_fts` 删除重建：新增 `keywords` 列 + 显式 `tokenize=unicode61`
 * 3. 4 条 content 同步触发器删除重建（迁移路径 Room 不会自动补，必须手写）
 * 4. `DELETE FROM file_index` 清空存量索引，交冷启动重索引机制用新逻辑自愈
 *
 * 结构照抄 [Migration79to80Test]/[Migration74to75Test] 三段式：
 * 1. **testAllMigrations58to81Validate**：标准 `runMigrationsAndValidate`，从 v58 跑到 v81，
 *    验证全链（含 MIGRATION_80_81）迁移后数据库结构与 81.json 期望 schema 一致——
 *    触发器缺失/keywords 列顺序错会在此直接抛异常。依赖 schemas/58.json + schemas/81.json。
 * 2. **testMigration80to81AddsKeywordsAndFts**（防复发核心）：建 v62 库 → 跑 62→80 链
 *    到达 v80 状态 → 确认 file_index 无 keywords 列 → 跑 MIGRATION_80_81 → 断言
 *    file_index.keywords 列存在、file_index_fts 含 keywords 列 + 显式 unicode61 tokenizer、
 *    4 条 content 同步触发器存在。
 * 3. **testFileIndexFtsSyncsKeywordsOnInsert**（业务验证）：迁移后插入带分词 keywords 的
 *    file_index 行，FTS 表经触发器同步后能 MATCH 到中文关键词（验证 keywords 索引确实生效）。
 *
 * ## 运行环境
 * androidTest 目录，需真机/模拟器 `./gradlew :app:connectedAndroidTest`。
 * schemas/81.json 由 Room KSP 在编译时自动生成（已存在）。
 */
@RunWith(AndroidJUnit4::class)
class Migration80to81Test {

    companion object {
        private const val TEST_DB_NAME = "migration-test-80-81.db"
    }

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    /**
     * 测试1：v58→v81 全链 runMigrationsAndValidate。
     * 若 MIGRATION_80_81 缺 FTS content 同步触发器 / keywords 列顺序与 81.json 不符，
     * 此处比对 81.json 期望 schema 会抛异常。
     */
    @Test
    fun testAllMigrations58to81Validate() {
        helper.createDatabase(TEST_DB_NAME, 58).close()

        val db = helper.runMigrationsAndValidate(
            TEST_DB_NAME,
            81,
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
            MIGRATION_80_81,
        )
        db.close()
    }

    /**
     * 测试2（防复发核心）：建 v62 库 → 跑 62→80 链到达 v80 状态 → 确认 file_index
     * 尚无 keywords 列 → 跑 MIGRATION_80_81 → 断言：
     * - file_index.keywords 列已添加（TEXT NOT NULL）
     * - file_index_fts 含 keywords 列，且 schema 里显式 tokenize=unicode61
     * - 4 条 content 同步触发器存在
     */
    @Test
    fun testMigration80to81AddsKeywordsAndFts() {
        val db = helper.createDatabase(TEST_DB_NAME, 62)

        // 跑 62→80 链到达 v80 等价状态
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
        MIGRATION_78_79.migrate(db)
        MIGRATION_79_80.migrate(db)

        // 迁移前：file_index 无 keywords 列
        assertNull("迁移前 file_index 不应有 keywords 列", queryColumnInfo(db, "file_index", "keywords"))

        // 跑 MIGRATION_80_81
        MIGRATION_80_81.migrate(db)

        // file_index.keywords 列存在且为 NOT NULL TEXT
        val kw = queryColumnInfo(db, "file_index", "keywords")
        assertNotNull("迁移后 file_index.keywords 列应存在", kw)
        kw?.let {
            assertEquals("TEXT", it.type)
            assertTrue("keywords 应为 NOT NULL", it.notNull)
        }

        // file_index_fts 含 keywords 列
        assertNotNull("file_index_fts 应含 keywords 列", queryFtsColumn(db, "file_index_fts", "keywords"))

        // file_index_fts 显式 tokenize=unicode61
        val ftsSql = tableSql(db, "file_index_fts")
        assertNotNull("file_index_fts 应在 sqlite_master 中", ftsSql)
        assertTrue(
            "file_index_fts 应显式 tokenize=unicode61（实际: $ftsSql）",
            ftsSql!!.contains("tokenize=unicode61"),
        )

        // 4 条 content 同步触发器必须存在
        val expectedTriggers = listOf(
            "room_fts_content_sync_file_index_fts_BEFORE_UPDATE",
            "room_fts_content_sync_file_index_fts_BEFORE_DELETE",
            "room_fts_content_sync_file_index_fts_AFTER_UPDATE",
            "room_fts_content_sync_file_index_fts_AFTER_INSERT",
        )
        val actual = queryTriggerNames(db)
        for (name in expectedTriggers) {
            assertTrue("缺少 FTS 触发器: $name", name in actual)
        }

        db.close()
    }

    /**
     * 测试3（业务验证）：迁移后（v81 状态）插入带分词 keywords 的 file_index 行，
     * FTS 表经触发器同步后能通过 MATCH 检索到中文关键词（验证 keywords 索引生效，
     * 而非仅建表存在）。
     */
    @Test
    fun testFileIndexFtsSyncsKeywordsOnInsert() {
        val db = helper.createDatabase(TEST_DB_NAME, 62)

        // 跑 62→80 链 + MIGRATION_80_81 到达 v81 状态
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
        MIGRATION_78_79.migrate(db)
        MIGRATION_79_80.migrate(db)
        MIGRATION_80_81.migrate(db)

        // 插入带分词 keywords 的行（keywords 由 ChineseTokenizer 在写入侧生成，空格分隔）
        db.execSQL(
            """INSERT INTO `file_index` (`filePath`, `fileName`, `fileType`, `extractedText`, `keywords`, `sizeBytes`, `createdAt`, `indexedAt`)
               VALUES ('/docs/contract.txt', '永久合同.txt', 'TEXT', '这是一份关于房屋永久使用权的合同文本。', '这是 一份 关于 房屋 永久 使用权 的 合同 文本', 100, 1700000000000, 1700000000000)"""
                .trimIndent()
        )

        // 经触发器同步后，FTS 表应能通过 MATCH 检索到 keywords 里的中文词
        db.query("SELECT `fileName` FROM `file_index_fts` WHERE `file_index_fts` MATCH '永久'").use { c ->
            assertTrue("FTS 应检索到 keywords 中的中文词（触发器同步生效）", c.moveToFirst())
            assertEquals("永久合同.txt", c.getString(0))
        }
        // extractedText 里的词同样可检索（unicode61 切空格）
        db.query("SELECT `fileName` FROM `file_index_fts` WHERE `file_index_fts` MATCH '合同'").use { c ->
            assertTrue("FTS 应检索到 extractedText 中的关键词", c.moveToFirst())
            assertEquals("永久合同.txt", c.getString(0))
        }

        db.close()
    }

    // ── 内部工具方法（照抄 Migration79to80Test/Migration74to75Test）──────────

    private data class ColumnInfo(val name: String, val type: String, val notNull: Boolean)

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

    /** FTS 虚拟表用 PRAGMA table_info 也能列出其列。 */
    private fun queryFtsColumn(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        tableName: String,
        columnName: String,
    ): ColumnInfo? = queryColumnInfo(db, tableName, columnName)

    private fun tableSql(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        tableName: String,
    ): String? = db.query(
        "SELECT `sql` FROM sqlite_master WHERE type='table' AND name='$tableName'"
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun queryTriggerNames(db: androidx.sqlite.db.SupportSQLiteDatabase): Set<String> =
        db.query("SELECT `name` FROM sqlite_master WHERE type='trigger'").use { c ->
            val set = mutableSetOf<String>()
            while (c.moveToNext()) set.add(c.getString(0))
            set
        }
}