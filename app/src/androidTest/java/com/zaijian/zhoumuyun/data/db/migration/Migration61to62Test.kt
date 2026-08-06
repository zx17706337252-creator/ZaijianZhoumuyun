package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zaijian.zhoumuyun.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 批次0 防复发测试：验证 MIGRATION_61_62（补建12张表31个历史遗留缺失索引）的正确性。
 *
 * ## 为什么需要这个测试
 *
 * MIGRATION_46_47 删除了8张表21个旧命名索引后，新命名索引从未被任何迁移 CREATE，
 * 另有4张表10个索引从未被任何迁移创建。合计31个索引缺失。MIGRATION_61_62 补建它们。
 * 若未来有人误删 MIGRATION_61_62、或删其中某些 CREATE INDEX 语句，升级用户会因
 * Room `validateMigration()` 校验索引缺失而崩溃。本测试守护这条迁移路径不被回归。
 *
 * ## 测试策略（三个方法，各自独立验证一个维度）
 *
 * 1. **testAllMigrations58to62Validate**：标准 `runMigrationsAndValidate`，从 v58 跑到
 *    v62，验证全链不抛异常且迁移后数据库结构与 62.json 期望 schema 一致。覆盖
 *    "迁移执行不崩溃"维度。依赖 schemas/58.json 和 schemas/62.json。
 *
 * 2. **testMigration61to62RecreatesDroppedIndexes**（防复发核心）：`createDatabase(58)`
 *    会按 58.json 建表+建索引（包括31个目标索引，因为 Entity 类早就声明了它们）。本测试
 *    在建库后主动 DROP 这31个索引，模拟"迁移链缺失索引"的真实状态，再跑 v58→v62 全部
 *    迁移，最后查库确认31个索引全部被 MIGRATION_61_62 重建。若 MIGRATION_61_62 被删除
 *    或其 CREATE INDEX 语句被删，DROP 后索引不会被重建，本测试失败。覆盖"防复发"维度。
 *
 * 3. **testMigration61to62DeduplicatesRelationshipStates**：验证 MIGRATION_61_62 建唯一
 *    索引前的去重逻辑。先插入 relationship_states 的重复行（同 fromId+toId 不同 id），
 *    跑迁移后确认重复行被删除（保留 updatedAt 最新的一行），且唯一索引创建成功。覆盖
 *    "去重逻辑正确"维度。
 *
 * ## 运行环境
 *
 * MigrationTestHelper 依赖 Android 框架的 SupportSQLiteOpenHelper，故放在 androidTest
 * 目录，需在真机/模拟器上以 `./gradlew :app:connectedAndroidTest` 或 Android Studio
 * 的 androidTest 配置运行。schemas/62.json 由 Room KSP 在编译时自动生成；若手动构建
 * 环境（无 Android SDK）可基于 58.json 程序化推导（见批次0修复说明）。
 */
@RunWith(AndroidJUnit4::class)
class Migration61to62Test {

    companion object {
        private const val TEST_DB_NAME = "migration-test-61-62.db"

        /**
         * MIGRATION_61_62 补建的31个索引全量清单（与 Migration61to62.kt 逐一对应）。
         * 用于 DROP 模拟缺失状态、以及迁移后查库验证重建。按表分组。
         */
        private val INDEXESByTable: Map<String, List<String>> = mapOf(
            "memory_candidates" to listOf(
                "index_memory_candidates_characterId",
                "index_memory_candidates_sourceEventId",
                "index_memory_candidates_isProcessed",
                "index_memory_candidates_createdAt",
            ),
            "relationship_states" to listOf(
                "index_relationship_states_fromId",
                "index_relationship_states_toId",
                "index_relationship_states_fromId_toId",
                "index_relationship_states_isInterCharacter",
            ),
            "character_goals" to listOf(
                "index_character_goals_characterId",
                "index_character_goals_isActive",
            ),
            "project_milestones" to listOf(
                "index_project_milestones_projectId",
            ),
            "project_members" to listOf(
                "index_project_members_projectId",
                "index_project_members_characterId",
            ),
            "project_knowledge" to listOf(
                "index_project_knowledge_projectId",
                "index_project_knowledge_characterId",
                "index_project_knowledge_createdAt",
            ),
            "scheduled_jobs" to listOf(
                "index_scheduled_jobs_enabled_nextRunAt",
                "index_scheduled_jobs_characterId",
            ),
            "job_results" to listOf(
                "index_job_results_jobId",
                "index_job_results_characterId_isRead",
                "index_job_results_createdAt",
            ),
            "messages" to listOf(
                "index_messages_characterId",
                "index_messages_characterId_createdAt",
            ),
            "world_events" to listOf(
                "index_world_events_actorId",
                "index_world_events_domain",
                "index_world_events_projectId",
                "index_world_events_createdAt",
                "index_world_events_type_createdAt",
            ),
            "workflow_step_results" to listOf(
                "index_workflow_step_results_jobId",
            ),
            "workflow_jobs" to listOf(
                "index_workflow_jobs_characterId",
                "index_workflow_jobs_status",
            ),
        )

        /** 31个索引的扁平列表（用于计数校验）。 */
        private val ALL_31_INDEXES: List<String> = INDEXESByTable.values.flatten()
    }

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    /**
     * 测试1：v58→v62 全链 runMigrationsAndValidate。
     *
     * 验证 MIGRATION_58_59 / 59_60 / 60_61 / 61_62 四个迁移连跑不抛异常，
     * 且迁移后数据库结构与 62.json 期望 schema 完全一致（Room 逐表比对表/列/索引/外键）。
     * 报告验收要求"用 runMigrationsAndValidate 跑一遍确认不抛异常"，本方法即此。
     */
    @Test
    fun testAllMigrations58to62Validate() {
        // createDatabase(58) 按 58.json 建表+建索引，模拟 v58 理想状态
        helper.createDatabase(TEST_DB_NAME, 58).close()

        // 跑 v58→v62 全部迁移，并用 62.json 验证结构一致性
        val db = helper.runMigrationsAndValidate(
            TEST_DB_NAME,
            62,
            /* expectMigrations = */ true,
            MIGRATION_58_59,
            MIGRATION_59_60,
            MIGRATION_60_61,
            MIGRATION_61_62,
        )
        db.close()
        // 若执行到此行无异常，即验证通过（runMigrationsAndValidate 内部已逐表校验）
    }

    /**
     * 测试2（防复发核心）：DROP 31个索引模拟缺失状态，跑迁移后验证被 MIGRATION_61_62 重建。
     *
     * createDatabase(58) 会建全部索引（因为 Entity 类声明了它们），所以必须主动 DROP
     * 才能模拟"迁移链缺失索引"的真实状态。DROP 后跑 v58→v62 全部迁移，MIGRATION_61_62
     * 用 `CREATE INDEX IF NOT EXISTS` 重建这31个索引。查库确认全部重建。
     *
     * 防复发机制：若有人删除 MIGRATION_61_62 或其 CREATE INDEX 语句，DROP 后索引不会被
     * 重建，本测试的 assertTrue(indexExists) 会失败。
     */
    @Test
    fun testMigration61to62RecreatesDroppedIndexes() {
        // 1. 建 v58 库（含全部索引）
        val db = helper.createDatabase(TEST_DB_NAME, 58)

        // 2. DROP 31个目标索引，模拟迁移链缺失状态
        for (indexName in ALL_31_INDEXES) {
            db.execSQL("DROP INDEX IF EXISTS `$indexName`")
        }
        // 验证 DROP 生效：抽查3个索引已不存在
        for (spotCheck in listOf(
            "index_messages_characterId",
            "index_world_events_type_createdAt",
            "index_relationship_states_fromId_toId",
        )) {
            assertFalse(
                "DROP 后 $spotCheck 应不存在",
                indexExists(db, spotCheck),
            )
        }

        // 3. 跑 v58→v62 全部迁移（MIGRATION_61_62 会补建31个索引）
        //    手动执行 migrate（不用 runMigrationsAndValidate，因后者期望 schema 完全匹配，
        //    但 DROP 后到 MIGRATION_61_62 执行前，索引确实缺失——我们只验证 MIGRATION_61_62
        //    执行后索引被补建）。
        MIGRATION_58_59.migrate(db)
        MIGRATION_59_60.migrate(db)
        MIGRATION_60_61.migrate(db)
        MIGRATION_61_62.migrate(db)

        // 4. 查库验证31个索引全部被重建
        for (indexName in ALL_31_INDEXES) {
            assertTrue(
                "MIGRATION_61_62 执行后 $indexName 应被重建",
                indexExists(db, indexName),
            )
        }
        assertEquals(31, ALL_31_INDEXES.size)
        db.close()
    }

    /**
     * 测试3：验证 MIGRATION_61_62 建唯一索引前的去重逻辑。
     *
     * relationship_states 的 (fromId, toId) 唯一索引在 v47 被删除后到 v62 期间无约束，
     * 可能存在重复行。MIGRATION_61_62 在建唯一索引前先去重（保留 updatedAt 最新行）。
     * 本测试插入3行（2行重复 fromId+toId、1行不重复），跑迁移后验证重复行被删除且
     * 保留 updatedAt 最新的一行，唯一索引创建成功。
     */
    @Test
    fun testMigration61to62DeduplicatesRelationshipStates() {
        val db = helper.createDatabase(TEST_DB_NAME, 58)

        // 升级到 v61 结构（relationship_states 表结构在 v58 已定，后续迁移不改它）
        MIGRATION_58_59.migrate(db)
        MIGRATION_59_60.migrate(db)
        MIGRATION_60_61.migrate(db)

        // 58.json 反映的是 entity 期望终态，自带 index_relationship_states_fromId_toId
        // 唯一索引；但真实迁移历史上这个索引在 v47 被删、到 v62 才由 MIGRATION_61_62
        // 重新补建（见 Migration61to62.kt 注释）。v58→v61 期间这个唯一约束不应存在，
        // 否则下面插入 (charA,charB) 重复行这一步会在 INSERT 阶段就违反约束，测试
        // 走不到"验证 MIGRATION_61_62 去重逻辑"这一步。与 testMigration61to62RecreatesDroppedIndexes
        // 同款处理：先 DROP 掉这个索引，模拟"迁移链尚未补建索引"的真实历史状态。
        db.execSQL("DROP INDEX IF EXISTS `index_relationship_states_fromId_toId`")

        // 插入测试数据（58.json 的 createSql 不含 DEFAULT 子句，所有 NOT NULL 列
        // 必须显式提供值；isInterCharacter 是 INTEGER，Boolean 存为 0/1）：
        // - (charA→charB) 2行重复，updatedAt 分别为 100、200，应保留 updatedAt=200 的行
        // - (charC→charD) 1行不重复，应原样保留
        val cols = "(`id`,`fromId`,`toId`,`trust`,`respect`,`affection`,`curiosity`," +
            "`dependence`,`conflict`,`jealousy`,`tension`,`isInterCharacter`," +
            "`suppression`,`stage`,`updatedAt`)"
        db.execSQL(
            """INSERT INTO `relationship_states` $cols VALUES
               ('r1','charA','charB',50,50,50,50,20,10,0,0,1,50,'STRANGER',100),
               ('r2','charA','charB',50,50,50,50,20,10,0,0,1,50,'STRANGER',200),
               ('r3','charC','charD',50,50,50,50,20,10,0,0,0,50,'STRANGER',300)""".trimIndent()
        )
        // 确认插入前有3行
        assertEquals(3, db.query("SELECT COUNT(*) FROM `relationship_states`").use {
            it.moveToFirst(); it.getInt(0)
        })

        // 跑 MIGRATION_61_62（含去重 + 建唯一索引）
        MIGRATION_61_62.migrate(db)

        // 去重后应剩2行（r2 保留，r1 删除，r3 保留）
        assertEquals(2, db.query("SELECT COUNT(*) FROM `relationship_states`").use {
            it.moveToFirst(); it.getInt(0)
        })
        // 保留的是 updatedAt 最新的一行（r2，updatedAt=200）
        val keptRow = db.query(
            "SELECT `id`,`updatedAt` FROM `relationship_states` WHERE `fromId`='charA' AND `toId`='charB'"
        ).use {
            it.moveToFirst(); it.getString(0) to it.getInt(1)
        }
        assertEquals("r2", keptRow.first)
        assertEquals(200, keptRow.second)

        // 唯一索引创建成功（去重后无重复行，CREATE UNIQUE INDEX 不抛异常即成功）
        assertTrue(
            "index_relationship_states_fromId_toId 唯一索引应已创建",
            indexExists(db, "index_relationship_states_fromId_toId"),
        )
        db.close()
    }

    /** 查询 sqlite_master 确认指定名称的索引是否存在。 */
    private fun indexExists(db: androidx.sqlite.db.SupportSQLiteDatabase, indexName: String): Boolean {
        return db.query(
            "SELECT COUNT(*) FROM `sqlite_master` WHERE `type`='index' AND `name`=?",
            arrayOf(indexName),
        ).use {
            it.moveToFirst(); it.getInt(0) > 0
        }
    }
}
