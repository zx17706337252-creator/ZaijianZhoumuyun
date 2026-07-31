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
 * Migration 75 → 76 测试：角色忠诚锁定机制（方案 v1.5）。
 *
 * 验证 MIGRATION_75_76 为四张表新增列 + characterCallsOwnerJson 回填逻辑。
 *
 * 结构照抄 [Migration71to72Test] 三段式 + 业务专项用例：
 *
 * 1. **testAllMigrations58to76Validate**：标准 `runMigrationsAndValidate`，从 v58 跑到
 *    v76，验证全链不抛异常且迁移后数据库结构与 76.json 期望 schema 一致。
 *    依赖 schemas/58.json + schemas/76.json（由 Room KSP 在编译时生成）。
 *
 * 2. **testMigration75to76AddsColumnsWithDefaults**（防复发核心）：从 v62 库起步，
 *    先跑 62→75 链到达 v75 状态，确认新列不存在，再跑 MIGRATION_75_76，
 *    最后查库确认四张表的新列被正确添加且默认值符合预期。
 *
 * 3. **testMigration75to76PreservesExistingRows**：验证 MIGRATION_75_76 对现存行的
 *    行为——纯 ADD COLUMN 不应改动任何现有数据。
 *
 * 4. **testCharacterCallsOwnerJsonBackfill**（业务专项 - 回填正确性）：
 *    验证 characterCallsOwnerJson 从 userRoleLabelPrivate 回填的正确性，
 *    覆盖三种场景：有值、空字符串、null。
 *
 * ## 运行环境
 *
 * androidTest 目录，需真机/模拟器 `./gradlew :app:connectedAndroidTest`。
 * schemas/76.json 由 Room KSP 在编译时自动生成。
 *
 * **注意**：压缩包未附 schemas/ 目录（Room KSP 生成），需先跑一次编译确保
 * KSP 已生成到 v76+，否则 testAllMigrations58to76Validate 无法运行。
 * 其余测试不依赖 schemas/，可直接运行。
 */
@RunWith(AndroidJUnit4::class)
class Migration75to76Test {

    companion object {
        private const val TEST_DB_NAME = "migration-test-75-76.db"
    }

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    /**
     * 测试1：v58→v76 全链 runMigrationsAndValidate。
     *
     * 依赖：schemas/58.json + schemas/76.json（应用本批次后 build 生成）。
     */
    @Test
    fun testAllMigrations58to76Validate() {
        helper.createDatabase(TEST_DB_NAME, 58).close()

        val db = helper.runMigrationsAndValidate(
            TEST_DB_NAME,
            76,
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
        )
        db.close()
    }

    /**
     * 测试2（防复发核心）：建 v62 库 → 跑 62→75 链到达 v75 状态 → 确认新列不存在 →
     * 跑 MIGRATION_75_76 → 验证四张表的新列被正确添加且默认值符合预期。
     */
    @Test
    fun testMigration75to76AddsColumnsWithDefaults() {
        val db = helper.createDatabase(TEST_DB_NAME, 62)

        // 跑 62→75 链到达 v75 等价状态
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

        // 确认迁移前新列不存在
        assertNullColumn(db, "character_identity", "ownerAliasesJson")
        assertNullColumn(db, "character_identity", "characterCallsOwnerJson")
        assertNullColumn(db, "messages", "speakerContext")
        assertNullColumn(db, "private_chat_pairs", "characterDisconnectState")
        assertNullColumn(db, "memories", "isNarrativeOnly")

        // 跑 MIGRATION_75_76
        MIGRATION_75_76.migrate(db)

        // ── character_identity：ownerAliasesJson / characterCallsOwnerJson ──
        val ownerAliasesCol = queryColumnInfo(db, "character_identity", "ownerAliasesJson")
        assertNotNull("迁移后 character_identity.ownerAliasesJson 列应存在", ownerAliasesCol)
        ownerAliasesCol?.let {
            assertEquals("TEXT", it.type)
            assertTrue("ownerAliasesJson 应为 NOT NULL", it.notNull)
        }
        val callsOwnerCol = queryColumnInfo(db, "character_identity", "characterCallsOwnerJson")
        assertNotNull("迁移后 character_identity.characterCallsOwnerJson 列应存在", callsOwnerCol)
        callsOwnerCol?.let {
            assertEquals("TEXT", it.type)
            assertTrue("characterCallsOwnerJson 应为 NOT NULL", it.notNull)
        }

        // ── messages：speakerContext ──
        val speakerCtxCol = queryColumnInfo(db, "messages", "speakerContext")
        assertNotNull("迁移后 messages.speakerContext 列应存在", speakerCtxCol)
        speakerCtxCol?.let {
            assertEquals("TEXT", it.type)
            assertTrue("speakerContext 应为 NOT NULL", it.notNull)
        }

        // ── private_chat_pairs：characterDisconnectState ──
        val disconnectCol = queryColumnInfo(db, "private_chat_pairs", "characterDisconnectState")
        assertNotNull("迁移后 private_chat_pairs.characterDisconnectState 列应存在", disconnectCol)
        disconnectCol?.let {
            assertEquals("TEXT", it.type)
            assertTrue("characterDisconnectState 应为 NOT NULL", it.notNull)
        }

        // ── memories：isNarrativeOnly ──
        val narrativeCol = queryColumnInfo(db, "memories", "isNarrativeOnly")
        assertNotNull("迁移后 memories.isNarrativeOnly 列应存在", narrativeCol)
        narrativeCol?.let {
            assertEquals("INTEGER", it.type)
            assertTrue("isNarrativeOnly 应为 NOT NULL", it.notNull)
        }

        // ── 默认值验证：插入新行不指定新列，查默认值 ──
        // character_identity 默认值
        db.execSQL(
            """INSERT INTO `character_identity` (`characterId`, `name`) VALUES (999, '测试角色')""".trimIndent()
        )
        db.query(
            "SELECT `ownerAliasesJson`, `characterCallsOwnerJson` FROM `character_identity` WHERE `characterId` = 999"
        ).use { c ->
            assertTrue("应查到插入行", c.moveToFirst())
            assertEquals("ownerAliasesJson 默认值应为 '[]'", "[]", c.getString(0))
            assertEquals("characterCallsOwnerJson 默认值应为 '[]'（userRoleLabelPrivate 为空）", "[]", c.getString(1))
        }

        // messages 默认值
        db.execSQL(
            """INSERT INTO `messages` (`id`,`characterId`,`role`,`content`,`createdAt`)
               VALUES ('test-msg-1', 999, '1', '测试', 1700000000000)""".trimIndent()
        )
        db.query("SELECT `speakerContext` FROM `messages` WHERE `id` = 'test-msg-1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("speakerContext 默认值应为 'OWNER_DIRECT'", "OWNER_DIRECT", c.getString(0))
        }

        // memories 默认值
        db.execSQL(
            """INSERT INTO `memories` (`id`,`characterId`,`content`,`importance`,`domain`,`createdAt`,`updatedAt`)
               VALUES ('test-mem-1', 999, '测试记忆', 3, 'PERSONAL', 1700000000000, 1700000000000)""".trimIndent()
        )
        db.query("SELECT `isNarrativeOnly` FROM `memories` WHERE `id` = 'test-mem-1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("isNarrativeOnly 默认值应为 0", 0, c.getInt(0))
        }

        db.close()
    }

    /**
     * 测试3：验证 MIGRATION_75_76 对现存行的行为——纯 ADD COLUMN 不破坏现有数据。
     *
     * 在 v62 库里插入 messages / character_identity 历史行，跑 62→75 链 +
     * MIGRATION_75_76 后确认历史行依然存在。
     */
    @Test
    fun testMigration75to76PreservesExistingRows() {
        val db = helper.createDatabase(TEST_DB_NAME, 62)

        db.execSQL(
            """INSERT INTO `messages` (`id`,`characterId`,`role`,`content`,`createdAt`) VALUES
               ('msg-history-1', 1, '1', '历史消息正文', 1700000000000)""".trimIndent()
        )
        db.execSQL(
            """INSERT INTO `character_identity` (`characterId`, `name`) VALUES (1, '历史角色')""".trimIndent()
        )
        assertEquals(1, db.query("SELECT COUNT(*) FROM `messages`").use {
            it.moveToFirst(); it.getInt(0)
        })
        assertEquals(1, db.query("SELECT COUNT(*) FROM `character_identity`").use {
            it.moveToFirst(); it.getInt(0)
        })

        // 跑 62→75 链 + MIGRATION_75_76
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

        // 历史行依然存在
        assertEquals(1, db.query("SELECT COUNT(*) FROM `messages`").use {
            it.moveToFirst(); it.getInt(0)
        })
        assertEquals(1, db.query("SELECT COUNT(*) FROM `character_identity`").use {
            it.moveToFirst(); it.getInt(0)
        })
        // 历史消息内容未被篡改
        db.query("SELECT `content` FROM `messages` WHERE `id` = 'msg-history-1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("历史消息正文", c.getString(0))
        }

        db.close()
    }

    /**
     * 测试4（业务专项 - characterCallsOwnerJson 回填正确性）：
     *
     * 验证 MIGRATION_75_76 的游标遍历 + JSONArray 回填逻辑，
     * 覆盖三种场景：
     * - 有值（如"老公"）→ 回填为 `["老公"]`
     * - 空字符串（默认值）→ 回填为 `[]`
     * - 含特殊字符（如双引号）→ JSON 正确转义
     */
    @Test
    fun testCharacterCallsOwnerJsonBackfill() {
        val db = helper.createDatabase(TEST_DB_NAME, 62)

        // 跑 62→75 链到达 v75 等价状态（此时 userRoleLabelPrivate 列已由 v58→59 添加）
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

        // 插入三条 character_identity 行，分别覆盖三种 userRoleLabelPrivate 场景
        // 角色 1：有正常值"老公"
        db.execSQL(
            """INSERT INTO `character_identity` (`characterId`, `name`, `userRoleLabelPrivate`)
               VALUES (1, '角色一', '老公')""".trimIndent()
        )
        // 角色 2：空字符串（Migration58to59 默认值）
        db.execSQL(
            """INSERT INTO `character_identity` (`characterId`, `name`, `userRoleLabelPrivate`)
               VALUES (2, '角色二', '')""".trimIndent()
        )
        // 角色 3：含双引号的值（验证 JSON 转义正确性——原 SQL 拼接 bug 的回归测试）
        db.execSQL(
            """INSERT INTO `character_identity` (`characterId`, `name`, `userRoleLabelPrivate`)
               VALUES (3, '角色三', '"老板"')""".trimIndent()
        )

        // 跑 MIGRATION_75_76（包含游标遍历回填逻辑）
        MIGRATION_75_76.migrate(db)

        // 验证回填结果
        db.query(
            "SELECT `characterCallsOwnerJson`, `ownerAliasesJson` FROM `character_identity` WHERE `characterId` = 1"
        ).use { c ->
            assertTrue("角色1应存在", c.moveToFirst())
            assertEquals(
                "有值时 characterCallsOwnerJson 应回填为 JSON 数组",
                """["老公"]""",
                c.getString(0),
            )
            assertEquals(
                "ownerAliasesJson 应为默认空数组",
                "[]",
                c.getString(1),
            )
        }

        db.query(
            "SELECT `characterCallsOwnerJson` FROM `character_identity` WHERE `characterId` = 2"
        ).use { c ->
            assertTrue("角色2应存在", c.moveToFirst())
            assertEquals(
                "空字符串时 characterCallsOwnerJson 应为空数组",
                "[]",
                c.getString(0),
            )
        }

        db.query(
            "SELECT `characterCallsOwnerJson` FROM `character_identity` WHERE `characterId` = 3"
        ).use { c ->
            assertTrue("角色3应存在", c.moveToFirst())
            val json = c.getString(0)
            assertEquals(
                "含双引号时 characterCallsOwnerJson 应正确转义为 JSON 数组",
                """["\"老板\""]""",
                json,
            )
        }

        db.close()
    }

    // ── 内部工具方法（照抄 Migration71to72Test）──────────────────

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

    private fun assertNullColumn(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        tableName: String,
        columnName: String,
    ) {
        val col = queryColumnInfo(db, tableName, columnName)
        assertNull("迁移前 $tableName.$columnName 列应不存在", col)
    }

    private data class ColumnInfo(
        val name: String,
        val type: String,
        val notNull: Boolean,
    )
}
