package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zaijian.zhoumuyun.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration 74 → 75 测试：文件搜索 · file_index / file_index_fts 建表。
 *
 * 重点回归：**[P0-1] file_index_fts 是 FTS4 外部内容表（content=`file_index`），其 4 条
 * content 同步触发器（room_fts_content_sync_file_index_fts_*）在迁移路径上必须手工补建。**
 *
 * 背景：Room 只在全新安装（onCreate）自动生成 content 同步触发器，迁移（onUpgrade）不会。
 * 若迁移只建虚拟表而缺触发器，`validateMigration` 比对触发器缺失会抛 IllegalStateException
 * （release 下被 fallbackToDestructiveMigration 静默清库），且 FTS 永不随主表 INSERT/UPDATE/DELETE 同步。
 *
 * 结构照抄 [Migration75to76Test]：
 *
 * 1. **testAllMigrations58to80Validate**：标准 `runMigrationsAndValidate`，从 v58 跑到 v80，
 *    验证全链（含 MIGRATION_74_75）迁移后数据库结构与 80.json 期望 schema 一致——触发器缺失
 *    会在此直接抛异常，捕获 P0 回归。依赖 schemas/58.json + schemas/80.json。
 *
 * 2. **testMigration74to75CreatesFtsTriggers**（防复发核心）：建 v62 库 → 跑 62→75 链 →
 *    确认 file_index / file_index_fts 均已建，且 4 条 content 同步触发器存在。
 *
 * 3. **testFileIndexFtsSyncsOnInsert**（业务验证）：插入 file_index 主表行后，FTS 表经触发器
 *    自动同步，可被 MATCH 检索到。
 *
 * ## 运行环境
 *
 * androidTest 目录，需真机/模拟器 `./gradlew :app:connectedAndroidTest`。
 * schemas/58.json + schemas/80.json 由 Room KSP 在编译时生成。
 */
@RunWith(AndroidJUnit4::class)
class Migration74to75Test {

    companion object {
        private const val TEST_DB_NAME = "migration-test-74-75.db"
    }

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    /**
     * 测试1：v58→v80 全链 runMigrationsAndValidate。
     * 若 MIGRATION_74_75 缺 FTS content 同步触发器，此处比对 80.json 期望 schema 会抛异常。
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
     * 测试2（防复发核心）：建 v62 库 → 跑 62→75 链 → 确认 file_index / file_index_fts
     * 均已建，且 4 条 content 同步触发器存在。
     */
    @Test
    fun testMigration74to75CreatesFtsTriggers() {
        val db = helper.createDatabase(TEST_DB_NAME, 62)

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

        // 主表与 FTS 虚拟表均已建
        assertTrue("file_index 表应存在", tableExists(db, "file_index"))
        assertTrue("file_index_fts 虚拟表应存在", tableExists(db, "file_index_fts"))

        // 4 条 content 同步触发器必须存在（P0-1 回归）
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
     * 测试3（业务验证）：插入 file_index 主表行后，FTS 表经触发器自动同步，
     * 可被 MATCH 检索到（验证触发器确实生效，而非仅存在）。
     */
    @Test
    fun testFileIndexFtsSyncsOnInsert() {
        val db = helper.createDatabase(TEST_DB_NAME, 62)

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

        db.execSQL(
            """INSERT INTO `file_index` (`filePath`, `fileName`, `fileType`, `extractedText`, `sizeBytes`, `createdAt`, `indexedAt`)
               VALUES ('/docs/notes.txt', 'notes.txt', 'TEXT', '这是一份关于月球与潮汐的笔记', 100, 1700000000000, 1700000000000)"""
                .trimIndent()
        )

        // 经触发器同步后，FTS 表应能通过 MATCH 检索到该行
        db.query("SELECT `fileName` FROM `file_index_fts` WHERE `file_index_fts` MATCH '月球'").use { c ->
            assertTrue("FTS 应检索到插入的行（触发器同步生效）", c.moveToFirst())
            assertEquals("notes.txt", c.getString(0))
        }

        db.close()
    }

    // ── 内部工具方法（照抄 Migration75to76Test）──────────────────

    private fun tableExists(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        tableName: String,
    ): Boolean = db.query(
        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='$tableName'"
    ).use { it.moveToFirst(); it.getInt(0) > 0 }

    private fun queryTriggerNames(db: androidx.sqlite.db.SupportSQLiteDatabase): Set<String> =
        db.query("SELECT `name` FROM sqlite_master WHERE type='trigger'").use { c ->
            val set = mutableSetOf<String>()
            while (c.moveToNext()) set.add(c.getString(0))
            set
        }
}