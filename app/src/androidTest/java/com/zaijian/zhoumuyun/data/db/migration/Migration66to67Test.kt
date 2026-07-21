package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zaijian.zhoumuyun.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 表格直传方案 W1 测试：验证 MIGRATION_66_67（messages / roundtable_messages 两表
 * 新增 `tableDataJson` 列）的正确性。
 *
 * ## 为什么需要这个测试
 *
 * "表格/结构化数据直传"方案（见《表格结构化数据直传_完整设计方案.md》）让 Agent
 * 通过新工具 `table_export` 直接从真实数据源产出表格，数据体不再经过 LLM 逐字
 * 生成/复述。表格内容需要一个落库载体：`MessageEntity` / `RoundtableMessageEntity`
 * 各新增 `tableDataJson: String? = null` 字段，存储 JSON 序列化后的 `TablePayload`。
 * 对应的 MIGRATION_66_67 用 `ALTER TABLE ... ADD COLUMN tableDataJson TEXT DEFAULT NULL`
 * 给两张表补这一列。若未来有人误删 MIGRATION_66_67 或改动了 ALTER 语句，升级用户
 * 会因 Room `validateMigration()` 校验列缺失而崩溃。本测试守护这条迁移路径不被回归。
 *
 * ## 测试策略（三个方法，各自独立验证一个维度，照抄 Migration62to63Test 的三方法结构）
 *
 * 1. **testAllMigrations58to67Validate**：标准 `runMigrationsAndValidate`，从 v58 跑到
 *    v67，验证全链不抛异常且迁移后数据库结构与 67.json 期望 schema 一致。覆盖
 *    "迁移执行不崩溃 + schema 校验通过"维度。依赖 schemas/58.json（已提交）和
 *    schemas/67.json（由 Room KSP 在编译时自动生成，见下方「运行环境」）。
 *
 * 2. **testMigration66to67AddsTableDataJsonColumn**（防复发核心）：从 v62 库起步，
 *    先跑 62→66 链到达 v66 状态（此时两表有 exportedFilesJson、仍无 tableDataJson），
 *    确认迁移前两表都没有 tableDataJson 列，再跑 MIGRATION_66_67，最后查库确认
 *    messages / roundtable_messages 两表的 tableDataJson 列被正确创建——类型 TEXT、
 *    可空（notnull=0）、默认值 NULL。同时确认 practice_records **没有**被误加（设计
 *    文档 3.3 只点名两个实体）。若 MIGRATION_66_67 被删除或其 ALTER 语句被改坏，
 *    迁移后查不到该列，本测试失败。覆盖"列被正确添加"维度。
 *
 * 3. **testMigration66to67PreservesExistingRows**：验证 MIGRATION_66_67 对现存行的
 *    行为——历史行的 tableDataJson 应为 NULL。在 v62 库里插入 messages /
 *    roundtable_messages 历史行，跑 62→66 链 + 66→67 后确认：历史行依然存在（不被
 *    删除/重建）、其 tableDataJson 为 NULL（DEFAULT NULL 回填）。覆盖"纯新增列不
 *    破坏现有数据"维度。
 *
 * ## 为什么测试 2/3 从 createDatabase(62) 起步而不是 createDatabase(66)
 *
 * schemas 目录只提交了 58.json / 62.json 两个历史快照（当前 DB 已是 v66，但 66.json
 * 未提交——schemas 由 KSP 在 build 时自动生成，未逐版本提交）。`createDatabase(66)`
 * 会因找不到 66.json 而失败，故测试 2/3 改从已提交的 createDatabase(62) 起步，先跑
 * 62→66 链到达 v66 等价状态，再测 66→67。这样只需已提交的 62.json + 一次 build 生成
 * 的 67.json，不需要 66.json（详见交付说明"schema 处理"段）。测试 1 照抄
 * Migration62to63Test.testAllMigrations58to63Validate，从 createDatabase(58) 起步，
 * 58→67 链里包含 MIGRATION_59_60 / 60_61（给 messages/roundtable_messages 补
 * psychText），所以不存在 62.json 的 psychText 不一致问题。
 *
 * ## 运行环境
 *
 * MigrationTestHelper 依赖 Android 框架的 SupportSQLiteOpenHelper，故放在 androidTest
 * 目录，需在真机/模拟器上以 `./gradlew :app:connectedAndroidTest` 或 Android Studio
 * 的 androidTest 配置运行。schemas/67.json 由 Room KSP 在编译时自动生成（Entity
 * 改动后首次 build 触发，即应用本 W1 批次后 build 一次即可生成）；schemas/58.json、
 * schemas/62.json 已存在于仓库中，分别用于 createDatabase(58) / createDatabase(62)。
 *
 * 比照 Migration62to63Test.kt 的结构写法，但因 66→67 给**两张**表加列（messages +
 * roundtable_messages），列级测试在两张表上各做一遍校验，并额外断言 practice_records
 * 不被误加（设计决定，见 Migration66to66.kt KDoc）。
 */
@RunWith(AndroidJUnit4::class)
class Migration66to67Test {

    companion object {
        private const val TEST_DB_NAME = "migration-test-66-67.db"
    }

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    /**
     * 测试1：v58→v67 全链 runMigrationsAndValidate。
     *
     * 验证 MIGRATION_58_59 / 59_60 / 60_61 / 61_62 / 62_63 / 63_64 / 64_65 / 65_66 /
     * 66_67 九个迁移连跑不抛异常，且迁移后数据库结构与 67.json 期望 schema 完全一致
     * （Room 逐表比对表/列/索引/外键）。从 createDatabase(58) 起步（照抄
     * Migration62to63Test.testAllMigrations58to63Validate 的起步点），58→67 链里包含
     * MIGRATION_59_60 / 60_61，故 messages/roundtable_messages 的 psychText 由迁移链
     * 正确补上，不存在 62.json 的 psychText 不一致问题。
     *
     * 依赖：schemas/58.json（已提交）+ schemas/67.json（应用 W1 后 build 生成）。
     */
    @Test
    fun testAllMigrations58to67Validate() {
        // createDatabase(58) 按 58.json 建表+建索引，模拟 v58 理想状态
        helper.createDatabase(TEST_DB_NAME, 58).close()

        // 跑 v58→v67 全部迁移，并用 67.json 验证结构一致性
        val db = helper.runMigrationsAndValidate(
            TEST_DB_NAME,
            67,
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
        )
        db.close()
        // 若执行到此行无异常，即验证通过（runMigrationsAndValidate 内部已逐表校验）
    }

    /**
     * 测试2（防复发核心）：建 v62 库 → 跑 62→66 链到达 v66 状态 → 确认无 tableDataJson →
     * 跑 MIGRATION_66_67 → 验证两表 tableDataJson 列被正确添加。
     *
     * createDatabase(62) 按 62.json 建表（此时 tableDataJson 列尚不存在，该字段 v67 才
     * 加）。先跑 62→66 链（MIGRATION_62_63/63_64/64_65/65_66，只 ADD COLUMN，到达 v66
     * 等价状态：messages/roundtable_messages 已有 exportedFilesJson、仍无 tableDataJson）。
     * 确认迁移前两表都没有 tableDataJson 列，再跑 MIGRATION_66_67，查 PRAGMA table_info
     * 确认两表的 tableDataJson 列：
     * - 列存在
     * - 类型为 TEXT
     * - notnull=0（可空，历史消息无表格时为 NULL）
     * - dflt_value=NULL（默认 NULL，历史行回填 NULL）
     *
     * 同时断言 practice_records **没有**被加 tableDataJson——设计文档 3.3 只点名
     * MessageEntity / RoundtableMessageEntity 两个实体（practice_records 是修炼播报的
     * 独立文件下发流程，不产出表格），这是设计决定，不是遗漏。
     *
     * 防复发机制：若有人删除 MIGRATION_66_67 或其 ALTER 语句被改坏，迁移后查不到该列，
     * 本测试的 assertNotNull(col) 会失败。
     */
    @Test
    fun testMigration66to67AddsTableDataJsonColumn() {
        // 1. 建 v62 库（tableDataJson 列尚不存在）
        val db = helper.createDatabase(TEST_DB_NAME, 62)

        // 2. 跑 62→66 链到达 v66 等价状态（有 exportedFilesJson，仍无 tableDataJson）。
        //    选用 createDatabase(62) 而非 createDatabase(66)：schemas 目录只提交了
        //    58.json/62.json，无 66.json（见类 KDoc「为什么测试 2/3 从 62 起步」段）。
        MIGRATION_62_63.migrate(db)
        MIGRATION_63_64.migrate(db)
        MIGRATION_64_65.migrate(db)
        MIGRATION_65_66.migrate(db)

        // 3. 迁移前两表都没有 tableDataJson 列
        assertFalse(
            "迁移前 messages.tableDataJson 列应不存在",
            columnExists(db, "messages", "tableDataJson"),
        )
        assertFalse(
            "迁移前 roundtable_messages.tableDataJson 列应不存在",
            columnExists(db, "roundtable_messages", "tableDataJson"),
        )

        // 4. 跑 MIGRATION_66_67（ALTER TABLE ADD COLUMN tableDataJson TEXT DEFAULT NULL ×2）
        MIGRATION_66_67.migrate(db)

        // 5. 查库验证两表的 tableDataJson 列已创建且属性正确
        for (table in listOf("messages", "roundtable_messages")) {
            val col = queryColumnInfo(db, table, "tableDataJson")
            assertNotNull("迁移后 $table.tableDataJson 列应存在", col)
            col?.let {
                assertEquals("tableDataJson", it.name)
                assertEquals("TEXT", it.type)
                assertFalse(
                    "$table.tableDataJson 应可空（notnull=0），无表格时为 NULL",
                    it.notNull,
                )
                assertNull(
                    "$table.tableDataJson 默认值应为 NULL（dflt_value=null）",
                    it.defaultValue,
                )
            }
        }

        // 6. 设计决定校验：practice_records 不应被误加 tableDataJson
        //    （设计文档 3.3 只点名 MessageEntity/RoundtableMessageEntity 两个实体）
        assertFalse(
            "practice_records 不应被加 tableDataJson（设计文档 3.3 未点名该实体）",
            columnExists(db, "practice_records", "tableDataJson"),
        )

        db.close()
    }

    /**
     * 测试3：验证 MIGRATION_66_67 对现存行的行为——历史行的 tableDataJson 应为 NULL。
     *
     * 纯新增可空列不应改动任何现有数据。本测试在 v62 库里分别插入一条 messages 历史
     * 行和一条 roundtable_messages 历史行（模拟升级前已存在的消息），跑 62→66 链 +
     * MIGRATION_66_67 后确认：
     * - 历史行依然存在（不被删除/重建，两张表各仍 1 条）
     * - 历史行的 tableDataJson 字段为 NULL（DEFAULT NULL 回填）
     * 这条覆盖了"纯新增列不破坏现有数据"的维度，是 schema 变更类迁移的标准验收点。
     * 两张受影响表都各插一条历史行，避免只验 messages 漏掉 roundtable_messages。
     */
    @Test
    fun testMigration66to67PreservesExistingRows() {
        val db = helper.createDatabase(TEST_DB_NAME, 62)

        // 插入历史 messages 行（62.json createSql 的 NOT NULL 列：id/characterId/role/
        // content/createdAt；eventId/exportedFileJson/thinkingText 可空，省略）：
        // - role 用角色 ID 字符串 "1"（与 MessageEntity.role 语义一致："user" 或角色 ID）
        db.execSQL(
            """INSERT INTO `messages` (`id`,`characterId`,`role`,`content`,`createdAt`) VALUES
               ('msg-history-1', 1, '1', '历史消息正文', 1700000000000)""".trimIndent()
        )
        // 插入历史 roundtable_messages 行（NOT NULL 列：id/roundtableId/speakerId/
        // speakerName/content/createdAt/turnIndex；replyTarget*/exportedFileJson 可空，省略）
        db.execSQL(
            """INSERT INTO `roundtable_messages` (
                  `id`,`roundtableId`,`speakerId`,`speakerName`,`content`,`createdAt`,`turnIndex`
               ) VALUES (
                  'rt-history-1', '1_2', '1', '角色一', '历史圆桌消息', 1700000000000, 0
               )""".trimIndent()
        )
        // 确认插入成功（两表各 1 条）
        assertEquals(1, db.query("SELECT COUNT(*) FROM `messages`").use {
            it.moveToFirst(); it.getInt(0)
        })
        assertEquals(1, db.query("SELECT COUNT(*) FROM `roundtable_messages`").use {
            it.moveToFirst(); it.getInt(0)
        })

        // 跑 62→66 链 + MIGRATION_66_67（纯 ADD COLUMN，不删除/重建任何行）
        MIGRATION_62_63.migrate(db)
        MIGRATION_63_64.migrate(db)
        MIGRATION_64_65.migrate(db)
        MIGRATION_65_66.migrate(db)
        MIGRATION_66_67.migrate(db)

        // 历史行依然存在（两表各 1 条，未被删除/重建）
        assertEquals(1, db.query("SELECT COUNT(*) FROM `messages`").use {
            it.moveToFirst(); it.getInt(0)
        })
        assertEquals(1, db.query("SELECT COUNT(*) FROM `roundtable_messages`").use {
            it.moveToFirst(); it.getInt(0)
        })

        // 历史 messages 行的 tableDataJson 为 NULL（DEFAULT NULL 回填）
        val msgTable = db.query(
            "SELECT `tableDataJson` FROM `messages` WHERE `id`='msg-history-1'"
        ).use {
            it.moveToFirst(); if (it.isNull(0)) null else it.getString(0)
        }
        assertNull("历史 messages 行的 tableDataJson 应为 NULL", msgTable)

        // 历史 roundtable_messages 行的 tableDataJson 为 NULL
        val rtTable = db.query(
            "SELECT `tableDataJson` FROM `roundtable_messages` WHERE `id`='rt-history-1'"
        ).use {
            it.moveToFirst(); if (it.isNull(0)) null else it.getString(0)
        }
        assertNull("历史 roundtable_messages 行的 tableDataJson 应为 NULL", rtTable)

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
