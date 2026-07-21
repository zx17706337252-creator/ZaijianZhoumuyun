package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v66 → v67：messages / roundtable_messages 两表新增 `tableDataJson` 列，
 * 支持"表格/结构化数据直传"方案（见《表格结构化数据直传_完整设计方案.md》）。
 *
 * ## 背景（表格直传方案 · W1 数据模型批次）
 *
 * 现有架构里所有表格类工具（`table_gen` / `excel_gen`）的数据体都必须经过一次
 * LLM completion 的 token 预算——无论数据是不是已经真实存在（用户上传的 CSV、
 * App 内部的日程/关系数据）。这是"大数据传不出去"的结构性原因。
 *
 * 新方案让 Agent 通过新工具 `table_export` 直接从真实数据源产出表格，数据体
 * 不再经过 LLM 逐字生成/复述。LLM 只负责"决定意图和参数 + 给用户写一句自然
 * 语言摘要"，真正的表格内容（哪怕上万行）由 Kotlin 代码直接产出，不占用任何
 * LLM completion 的 token 预算。
 *
 * 表格内容需要一个落库载体：本字段 `tableDataJson`，存储 JSON 序列化后的
 * `TablePayload`（title / columns / rows / rowCountTotal / generatedAt）。
 *
 * ## 改动
 *
 * messages / roundtable_messages 两表新增 `tableDataJson TEXT DEFAULT NULL` 列，
 * 与既有的 `exportedFileJson` / `exportedFilesJson` 同级、同生命周期、同兼容策略
 * （同样挂在"Agent附件下发方案 v2.0"打通的管线上，不平行造一套）。
 *
 * ⚠️ 仅改两张表，**不动 `practice_records`**——`Migration65to66` 给三表都加了
 * `exportedFilesJson`，是因为文件附件在修炼播报（DailyPracticeWorker）路径也
 * 会产出。而表格直传只发生在聊天/圆桌消息流（`table_export` 工具的调用结果只
 * 挂在消息上），修炼播报是独立的纯文件下发流程，不会产出表格。设计文档 3.3
 * 节也只点名 `MessageEntity` / `RoundtableMessageEntity` 两个实体，故本迁移
 * 与 `Migration65to66` 的三表范围不同，是设计决定，不是遗漏。
 *
 * 例：`{"title":"本月排班表","columns":["姓名","日期","班次"],"rows":[...],"rowCountTotal":47,"generatedAt":1784000000000}`
 *
 * ## 兼容策略（不做破坏性改动，与 exportedFilesJson 完全一致）
 *
 * - 纯新增可空列 `DEFAULT NULL`，历史消息一律回填 NULL，属于正常状态
 *   （null = 该消息没有表格）。不改动任何现有表结构、不改动任何业务数据。
 * - 历史消息只有 `exportedFileJson`/`exportedFilesJson`，没有 `tableDataJson`，
 *   读取端判空即可，与 `exportedFilesJson` 上线时的兼容路径一个模式。
 * - 三级阈值策略（设计文档 3.4 节）：≤50 行走 Markdown 表格不落本字段；
 *   50~500 行本字段存全量；>500 行本字段只存前 10 行预览，同时自动生成完整
 *   `.xlsx` 走 `exportedFilesJson` 挂下载附件。即"一次工具调用 → 一条消息 →
 *   一个完整可视化/下载入口"，不再出现同一份数据被拆成多条消息/多个文件。
 *
 * ## 范围说明（W1 只动数据层）
 *
 * 本批次（W1）只做数据模型：本迁移 + `MessageEntity`/`RoundtableMessageEntity`
 * 双写新字段 + 迁移测试。`table_export` 工具（W2）、`TableCard` UI（W3）、
 * 三处调用点打通（W4）在后续批次接入，本字段先以 null 状态存在，不影响任何
 * 现有路径。
 */
internal val MIGRATION_66_67 = object : Migration(66, 67) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `messages` ADD COLUMN `tableDataJson` TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE `roundtable_messages` ADD COLUMN `tableDataJson` TEXT DEFAULT NULL")
    }
}
