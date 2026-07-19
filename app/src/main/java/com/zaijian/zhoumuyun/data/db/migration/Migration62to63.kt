package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v62 → v63：日程系统批次1——scheduled_jobs 表新增 `description` 列。
 *
 * ## 背景
 *
 * 日程系统「AI 可创建 / 查询 / 编辑」改造（见《日程系统_AI创建查询编辑_实现方案_v2.md》）
 * 引入"工单型"日程（mode B）：toolName == "agent_task" 的任务用自由文本 description
 * 描述"要做的事"，到点后注入角色对话管线走一次完整 LLM 推理（角色自己判断要不要
 * 调工具、要说什么），而不是像现有工具型任务那样直接调用某个预注册工具。
 *
 * ScheduledJobEntity 此前只有 toolName + toolParamsJson 两个"内容"载体，两者都要求
 * 创建时精确命中一个已注册工具名，无法装下"提醒喝水"这类模糊、需要角色语言表达
 * 的任务。本列就是补的这个自由文本字段。
 *
 * ## 改动
 *
 * scheduled_jobs 表新增 `description TEXT DEFAULT NULL` 列：
 * - 工具型任务（mode A，现状）description 永远为 NULL，不受影响。
 * - 工单型任务（mode B，本批后续批次接入执行链路与 CRUD 工具）description 承载
 *   自由文本，到点由 AgentTaskJobExecutor 读取并注入对话管线。
 *
 * 纯新增可空列，不改动任何现有表结构、不改动任何业务数据；现存行的 description
 * 一律为 NULL（历史任务没有 description 数据，属于正常状态，不需要回填）。与
 * MIGRATION_60_61（roundtable_messages 新增 thinkingText/psychText 列）同范式，
 * 风险极低。
 *
 * ## 云端同步
 *
 * Supabase 端 `scheduled_jobs` 表结构不在本仓库内（本地代码库里未发现 schema 定义
 * 文件，无 .sql / supabase/ / migrations/ 目录）。需手动在 Supabase 控制台
 * （Table Editor 或 SQL Editor）同步加一列：
 *
 * ```sql
 * ALTER TABLE scheduled_jobs ADD COLUMN description TEXT;
 * ```
 *
 * 否则模式 B 任务云同步后云端会丢失描述，换设备 / 重装后变成执行时报错的空壳任务。
 */
internal val MIGRATION_62_63 = object : Migration(62, 63) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `scheduled_jobs` ADD COLUMN `description` TEXT DEFAULT NULL")
    }
}
