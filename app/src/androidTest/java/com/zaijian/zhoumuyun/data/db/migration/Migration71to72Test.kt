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
 * Migration 71 → 72 测试：Agent 结构化存储（方案_Agent结构化存储_最终版）
 *
 * 验证 MIGRATION_71_72 新增一张独立表：agent_store_records（通用结构化记录表）。
 *
 * 结构照抄 [Migration70to71Test] 三段式 + 两个业务专项用例（共五段）：
 *
 * 1. **testAllMigrations58to72Validate**：标准 `runMigrationsAndValidate`，从 v58 跑到
 *    v72，验证全链不抛异常且迁移后数据库结构与 72.json 期望 schema 一致。
 *    依赖 schemas/58.json + schemas/72.json（由 Room KSP 在编译时生成）。
 *
 * 2. **testMigration71to72CreatesAgentStoreTable**（防复发核心 + 自增主键回归）：从 v62 库起步，
 *    先跑 62→71 链到达 v71 状态（此时 agent_store_records 表不存在），确认迁移前该表不存在，
 *    再跑 MIGRATION_71_72，最后查库确认表/列/索引被正确创建，并做插入回读验证表结构可正常
 *    写入与查询。**自增主键回归**嵌在本方法内部（不拆独立方法，与 Migration70to71Test
 *    253-272 行范本一致）：插入两条不指定 id 的记录，断言拿到两个不同且递增的 id，
 *    防止 `INTEGER NOT NULL` + 表级 `PRIMARY KEY(id)` 这种"看着像主键、实际不是
 *    rowid 别名"的写法悄悄回归（见方案 8.2 节）。
 *
 * 3. **testMigration71to72PreservesExistingRows**：验证 MIGRATION_71_72 对现存行的
 *    行为——纯新增表不应改动任何现有数据。在 v62 库里插入 messages /
 *    roundtable_messages 历史行，跑 62→71 链 + 71→72 后确认历史行依然存在。
 *
 * 4. **testUpsertOverwritesExistingKey**（业务专项 - Upsert 覆盖行为）：对同一个
 *    (ownerCharacterId, collection, key) 组合写入两次不同的 value，验证查询结果只有
 *    一条记录、内容是第二次写入的值。用 INSERT OR REPLACE 模拟 Room
 *    @Insert(onConflict = REPLACE) 的真实落库语义，验证唯一索引 + 覆盖语义均生效。
 *
 * ## 运行环境
 *
 * androidTest 目录，需真机/模拟器 `./gradlew :app:connectedAndroidTest`。
 * schemas/72.json 由 Room KSP 在编译时自动生成。
 */
@RunWith(AndroidJUnit4::class)
class Migration71to72Test {

    companion object {
        private const val TEST_DB_NAME = "migration-test-71-72.db"
    }

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    /**
     * 测试1（改）：v58→v72 全链手动迁移 + 结构断言。
     *
     * 原用 runMigrationsAndValidate 对比 72.json 的 identityHash 做 schema 校验，
     * 但 72.json 属于历史中间版本，KSP 只在编译期导出当前 @Database version 对应的
     * 一个版本快照，72.json 无法在不回退历史代码的情况下重新生成（项目无 git 历史，
     * 见《测试基建问题_剩余问题_解决方案.md》问题 A）。
     *
     * 改为：createDatabase(58) 后手动顺序跑 58→72 全部迁移，确认链条本身不抛异常
     * （覆盖原 validate 的"迁移执行不崩溃"维度），再对 71→72 唯一引入的变更
     * （新增 agent_store_records 表）做存在性断言，作为"结构符合预期"维度的
     * 轻量替代——完整的列级/索引级断言已在 testMigration71to72CreatesAgentStoreTable
     * 里覆盖，这里不重复。
     */
    @Test
    fun testAllMigrations58to72Validate() {
        val db = helper.createDatabase(TEST_DB_NAME, 58)

        // 全链手动跑，任何一步抛异常测试直接失败，等价于原 validate 的"迁移不崩溃"维度
        MIGRATION_58_59.migrate(db)
        MIGRATION_59_60.migrate(db)
        MIGRATION_60_61.migrate(db)
        MIGRATION_61_62.migrate(db)
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

        // 轻量结构校验：确认链条终点确实到达了 v72 该有的状态
        assertTrue(
            "v58→v72 全链后 agent_store_records 表应存在",
            tableExists(db, "agent_store_records"),
        )

        db.close()
    }

    /**
     * 测试2（防复发核心 + 自增主键回归）：建 v62 库 → 跑 62→71 链到达 v71 状态 → 确认
     * agent_store_records 表不存在 → 跑 MIGRATION_71_72 → 验证表/列/索引被正确创建，
     * 并插入行回读，确认表结构可正常写入与按索引列查询。末尾嵌入自增主键回归测试。
     */
    @Test
    fun testMigration71to72CreatesAgentStoreTable() {
        val db = helper.createDatabase(TEST_DB_NAME, 62)

        // 跑 62→71 链到达 v71 等价状态（agent_store_records 表尚不存在）
        MIGRATION_62_63.migrate(db)
        MIGRATION_63_64.migrate(db)
        MIGRATION_64_65.migrate(db)
        MIGRATION_65_66.migrate(db)
        MIGRATION_66_67.migrate(db)
        MIGRATION_67_68.migrate(db)
        MIGRATION_68_69.migrate(db)
        MIGRATION_69_70.migrate(db)
        MIGRATION_70_71.migrate(db)

        assertFalse(
            "迁移前 agent_store_records 表应不存在",
            tableExists(db, "agent_store_records"),
        )

        // 跑 MIGRATION_71_72（1 张 CREATE TABLE + 2 个索引）
        MIGRATION_71_72.migrate(db)

        // ── agent_store_records 表 ──────────────────────────────
        assertTrue("迁移后 agent_store_records 表应存在", tableExists(db, "agent_store_records"))

        val expectedColumns = listOf(
            ColumnExpectation("id", "INTEGER", notNull = true),
            ColumnExpectation("ownerCharacterId", "INTEGER", notNull = true),
            ColumnExpectation("collection", "TEXT", notNull = true),
            ColumnExpectation("key", "TEXT", notNull = true),
            ColumnExpectation("valueJson", "TEXT", notNull = true),
            ColumnExpectation("valueType", "TEXT", notNull = true),
            ColumnExpectation("createdAt", "INTEGER", notNull = true),
            ColumnExpectation("updatedAt", "INTEGER", notNull = true),
        )
        for (expect in expectedColumns) {
            val col = queryColumnInfo(db, "agent_store_records", expect.name)
            assertNotNull("迁移后 agent_store_records.${expect.name} 列应存在", col)
            col?.let {
                assertEquals(expect.name, it.name)
                assertEquals(expect.type, it.type)
                assertEquals(
                    "agent_store_records.${expect.name} notNull 应为 ${expect.notNull}",
                    expect.notNull,
                    it.notNull,
                )
            }
        }

        // ── 唯一索引 (ownerCharacterId, collection, key) ──────────
        assertTrue(
            "迁移后唯一索引 index_agent_store_records_ownerCharacterId_collection_key 应存在",
            indexExists(db, "index_agent_store_records_ownerCharacterId_collection_key"),
        )
        // ── 普通索引 (ownerCharacterId, collection, updatedAt) ──────
        assertTrue(
            "迁移后索引 index_agent_store_records_ownerCharacterId_collection_updatedAt 应存在",
            indexExists(db, "index_agent_store_records_ownerCharacterId_collection_updatedAt"),
        )

        // ── 功能性回读：插入行并查询，确认表结构可正常读写 ──
        db.execSQL(
            """INSERT INTO `agent_store_records` (
                  `ownerCharacterId`,`collection`,`key`,`valueJson`,`valueType`,`createdAt`,`updatedAt`
               ) VALUES (
                  1, 'budget_items', '2026-07', '{"amount":500,"note":"房租"}', 'object', 1700000000000, 1700000000000
               )""".trimIndent()
        )
        db.query(
            "SELECT `key`,`valueJson` FROM `agent_store_records` WHERE `ownerCharacterId` = 1 AND `collection` = 'budget_items' AND `key` = '2026-07'"
        ).use { c ->
            assertTrue("按 (ownerCharacterId, collection, key) 唯一索引应查到插入行", c.moveToFirst())
            assertEquals("2026-07", c.getString(0))
            assertEquals("{\"amount\":500,\"note\":\"房租\"}", c.getString(1))
            assertFalse("应仅有一行", c.moveToNext())
        }

        // ── 自增主键回归测试（嵌在测试2内部，范本 Migration70to71Test 253-272 行）──
        // id 是 autoGenerate 主键，不显式指定 id 连续插入两行，验证 SQLite rowid 自增真正生效
        // （不会因为两行都拿到默认值/相同值而唯一约束冲突）。专门防止 `INTEGER NOT NULL` +
        // 表级 `PRIMARY KEY(id)` 这种"看着像主键、实际不是 rowid 别名"的写法悄悄回归——
        // 那种写法下这里会插入失败或两行拿到同一个 id。见方案 8.2 节。
        db.execSQL(
            """INSERT INTO `agent_store_records` (
                  `ownerCharacterId`,`collection`,`key`,`valueJson`,`valueType`,`createdAt`,`updatedAt`
               ) VALUES (
                  1, 'budget_items', '2026-08', '{"amount":600}', 'object', 1700000001000, 1700000001000
               )""".trimIndent()
        )
        db.execSQL(
            """INSERT INTO `agent_store_records` (
                  `ownerCharacterId`,`collection`,`key`,`valueJson`,`valueType`,`createdAt`,`updatedAt`
               ) VALUES (
                  1, 'budget_items', '2026-09', '{"amount":700}', 'object', 1700000002000, 1700000002000
               )""".trimIndent()
        )
        db.query(
            "SELECT DISTINCT `id` FROM `agent_store_records` WHERE `ownerCharacterId` = 1 AND `collection` = 'budget_items' ORDER BY `id`"
        ).use { c ->
            val ids = mutableListOf<Long>()
            while (c.moveToNext()) ids.add(c.getLong(0))
            assertEquals("三次不指定 id 的插入应各自拿到不同的自增 id", 3, ids.size)
            assertTrue("自增 id 应严格递增", ids[1] > ids[0])
            assertTrue("自增 id 应严格递增", ids[2] > ids[1])
        }

        db.close()
    }

    /**
     * 测试3：验证 MIGRATION_71_72 对现存行的行为——纯新增表不破坏现有数据。
     *
     * 在 v62 库里插入 messages / roundtable_messages 历史行，跑 62→71 链 +
     * MIGRATION_71_72 后确认历史行依然存在。
     */
    @Test
    fun testMigration71to72PreservesExistingRows() {
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

        // 跑 62→71 链 + MIGRATION_71_72（纯新增表，不删除/重建任何行）
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

        // 历史行依然存在
        assertEquals(1, db.query("SELECT COUNT(*) FROM `messages`").use {
            it.moveToFirst(); it.getInt(0)
        })
        assertEquals(1, db.query("SELECT COUNT(*) FROM `roundtable_messages`").use {
            it.moveToFirst(); it.getInt(0)
        })

        db.close()
    }

    /**
     * 测试4（业务专项 - Upsert 覆盖行为）：对同一个 (ownerCharacterId, collection, key)
     * 组合写入两次不同的 value，验证查询结果只有一条记录、内容是第二次写入的值。
     *
     * 用 INSERT OR REPLACE 模拟 Room @Insert(onConflict = OnConflictStrategy.REPLACE)
     * 的真实落库语义——Repository.upsert() 走的就是这条路径。验证唯一索引存在且
     * 覆盖语义生效：第二次写入不会产生重复行，而是覆盖第一次的值。
     */
    @Test
    fun testUpsertOverwritesExistingKey() {
        val db = helper.createDatabase(TEST_DB_NAME, 62)

        // 跑 62→71 链 + MIGRATION_71_72 到达 v72 状态
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

        // 第一次写入：(owner=1, collection=budget_items, key=2026-07, amount=300)
        db.execSQL(
            """INSERT OR REPLACE INTO `agent_store_records` (
                  `ownerCharacterId`,`collection`,`key`,`valueJson`,`valueType`,`createdAt`,`updatedAt`
               ) VALUES (
                  1, 'budget_items', '2026-07', '{"amount":300}', 'object', 1700000000000, 1700000000000
               )""".trimIndent()
        )
        // 同一个 (owner, collection, key) 第二次写入：amount 改为 600（模拟 store_put 覆盖更新）
        db.execSQL(
            """INSERT OR REPLACE INTO `agent_store_records` (
                  `ownerCharacterId`,`collection`,`key`,`valueJson`,`valueType`,`createdAt`,`updatedAt`
               ) VALUES (
                  1, 'budget_items', '2026-07', '{"amount":600}', 'object', 1700000000000, 1700000001000
               )""".trimIndent()
        )

        // 查询：应只有一条记录，且内容是第二次写入的值（600）
        db.query(
            "SELECT COUNT(*) FROM `agent_store_records` WHERE `ownerCharacterId` = 1 AND `collection` = 'budget_items' AND `key` = '2026-07'"
        ).use { c ->
            assertTrue("应能查到记录", c.moveToFirst())
            assertEquals("同一 (owner, collection, key) 覆盖后应只有一条记录", 1, c.getInt(0))
        }
        db.query(
            "SELECT `valueJson` FROM `agent_store_records` WHERE `ownerCharacterId` = 1 AND `collection` = 'budget_items' AND `key` = '2026-07'"
        ).use { c ->
            assertTrue("应查到记录", c.moveToFirst())
            assertEquals(
                "覆盖后内容应是第二次写入的值",
                "{\"amount\":600}",
                c.getString(0),
            )
        }

        // 同时确认全局没有产生重复行（另一个 key 不受影响、总量正确）
        db.query("SELECT COUNT(*) FROM `agent_store_records`").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("全表应只有一条记录（无重复脏数据）", 1, c.getInt(0))
        }

        db.close()
    }

    // ── 内部工具方法（照抄 Migration70to71Test）──────────────────

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
