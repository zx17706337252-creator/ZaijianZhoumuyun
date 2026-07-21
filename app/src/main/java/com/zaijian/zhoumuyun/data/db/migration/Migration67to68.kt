package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v67 → v68：新增 `agent_activity_events` 表（Agent 过程可见层 ·「心迹」）。
 *
 * 见《Window B 执行方案 v1.1》2.2.2。纯 `CREATE TABLE` + 索引，不涉及任何已有
 * 表改动，参照 [Migration66to67] / [Migration57to58] 的写法，风险最低，可独立先行。
 *
 * ## 背景
 *
 * 现状（F4）：`StreamEvent.ToolStarted`/`ToolDone` 在三处消费点（私聊/圆桌被动
 * 回复/圆桌闲时主动发言）均未持久化，只用于临时 UI 提示和文件/表格产物提取，
 * 工具调用轨迹只存在于 `AgentLog`（纯文本文件，滚动清空，非结构化，不可查询）。
 * 这是 Window B 要补的真实空白。
 *
 * 新增 `agent_activity_events` 表统一承载"Agent 刚才做了什么、为什么这么做"的
 * 过程痕迹。降级策略状态机（2.1）与三处 UI 集成点（2.2.3）、WorkflowEngine 镜像
 * 埋点（2.1.4）**共用同一张事件表**，不是两套独立数据。
 *
 * ## 为什么不复用 workflow_step_results
 *
 * 见 [com.zaijian.zhoumuyun.data.db.entity.AgentActivityEventEntity] 类注释。
 * 两张表在查询层（[com.zaijian.zhoumuyun.data.repository.AgentActivityRepository]
 * 合并视图）做 UNION 呈现，不在存储层合并。
 *
 * ## 兼容策略
 *
 * 纯新增表，无任何已有表改动、无数据迁移。历史角色无「心迹」数据是正常空状态，
 * 读取端判空即可。索引名与 Room 自动生成的命名（`index_<表名>_<列名>`）严格
 * 一致，保证 `validateMigration()` 通过。
 */
internal val MIGRATION_67_68 = object : Migration(67, 68) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 列类型/可空性严格对照 AgentActivityEventEntity：
        //   String? → TEXT（可空），String → TEXT NOT NULL，
        //   Int → INTEGER NOT NULL，Long → INTEGER NOT NULL，Long? → INTEGER（可空）。
        // toolParamsJson 在实体层是 String? = "{}"（Kotlin 默认值不落为 SQL DEFAULT，
        // 列为可空 TEXT，由写入侧在 Kotlin 层提供 "{}"），与 WorkflowStepResultEntity
        // 的 toolParamsJson（String 非空）不同——这是方案 2.2.2 的明确字段定义。
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `agent_activity_events` (
                `id` TEXT NOT NULL,
                `characterId` INTEGER NOT NULL,
                `sessionRef` TEXT NOT NULL,
                `sceneType` TEXT NOT NULL,
                `eventType` TEXT NOT NULL,
                `toolName` TEXT,
                `toolParamsJson` TEXT,
                `attemptIndex` INTEGER NOT NULL,
                `outcome` TEXT,
                `outputSummary` TEXT,
                `errorMessage` TEXT,
                `decisionNote` TEXT,
                `startedAt` INTEGER NOT NULL,
                `completedAt` INTEGER,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        // 索引命名严格对照 Room 自动生成格式 index_<表名>_<列名...>，保证
        // validateMigration() 逐索引比对通过。与 @Entity(indices=[...]) 一一对应。
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_activity_events_characterId` ON `agent_activity_events` (`characterId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_activity_events_characterId_createdAt` ON `agent_activity_events` (`characterId`, `createdAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_activity_events_sessionRef` ON `agent_activity_events` (`sessionRef`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_activity_events_eventType` ON `agent_activity_events` (`eventType`)")
    }
}
