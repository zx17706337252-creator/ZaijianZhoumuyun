package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v63 → v64：日程系统第七节——scheduled_jobs 表新增关联项目列。
 *
 * ⚠️ 列名命名约定：本地 Room 用 camelCase（`projectId`），云端 Supabase 用
 * snake_case（`project_id`），与项目既有约定一致（如 characterId ↔ character_id）。
 * 本 migration 只动本地 Room 列（`projectId`）；云端 Supabase 列需在控制台手动
 * 加（`project_id`），见下方「云端同步」段。
 *
 * ## 背景
 *
 * 日程系统「关联项目」增强（见《日程系统_AI创建查询编辑_实现方案_v2.md》第七节）：
 * 让一条定时任务挂载到某个具体项目上（`ProjectEntity` 的主键），用于展示侧
 * 附加"关联项目: xxx"、创建/编辑侧校验项目存在性。null = 独立日程。
 *
 * 与 `description`（批次1新增）完全正交：工具型与工单型任务都可关联项目，
 * 关联后不影响执行链路（`ScheduledJobWorker` / `AgentTaskJobExecutor` 不读此字段）。
 *
 * ## 改动
 *
 * 本地 Room scheduled_jobs 表新增 `projectId TEXT DEFAULT NULL` 列：
 * - 历史任务没有关联项目数据，一律为 NULL，属于正常状态，不需要回填。
 * - 纯新增可空列，不改动任何现有表结构、不改动任何业务数据。
 * - 与 `MIGRATION_62_63`（新增 description 列）同范式，风险极低。
 *
 * ## 云端同步
 *
 * Supabase 端 `scheduled_jobs` 表需手动在控制台同步加一列（列名用 snake_case）：
 *
 * ```sql
 * ALTER TABLE scheduled_jobs ADD COLUMN project_id TEXT;
 * ```
 *
 * 注意：此处列名是 `project_id`（snake_case），与 SupabaseClient 实际写入 body
 * 用的 key `project_id` 严格一致；不要写成 `projectId`（camelCase，那是本地 Room
 * 的列名，不是云端列名）。两者不匹配时 PostgREST 对未知字段静默处理，不会报错，
 * 但云端这个字段会一直存不进去，要等到换设备/重装恢复测试时才会发现关联项目
 * 信息全部丢失——这正是本字段设计文档里反复强调要避免的"空壳"问题。
 *
 * 否则关联项目字段云同步后云端会丢失，换设备 / 重装后变成悬空引用
 * （本地查不到对应项目，展示侧只能 fallback 显示 projectId 前 8 位）。
 */
internal val MIGRATION_63_64 = object : Migration(63, 64) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 本地 Room 列名用 camelCase（projectId），与 Entity 字段名一致；
        // 云端 Supabase 列名用 snake_case（project_id），见上方「云端同步」段。
        db.execSQL("ALTER TABLE `scheduled_jobs` ADD COLUMN `projectId` TEXT DEFAULT NULL")
    }
}
