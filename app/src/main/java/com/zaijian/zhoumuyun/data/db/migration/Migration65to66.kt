package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v65 → v66：messages / roundtable_messages / practice_records 三表新增
 * `exportedFilesJson` 列，支持单条消息挂载多个文件附件。
 *
 * ## 背景（Agent附件下发方案 v2.0 · 1.7 P3 遗留项之一）
 *
 * P0-1 落地时，`exportedFileJson` 是单文件字段：一轮回复内若连续调用多个
 * 文件类工具（如先 `file_export` 又 `excel_gen`），后一次会覆盖前一次，
 * 消息上只挂得住最后一个文件。当时记录为已知限制，列入 P3 待办。
 *
 * ## 改动
 *
 * 三表新增 `exportedFilesJson TEXT DEFAULT NULL` 列，存储 JSON 数组，
 * 数组元素结构与 `exportedFileJson` 单对象完全一致
 * （fileName/mimeType/sizeBytes/absolutePath/openHint）。
 *
 * 例：`[{"fileName":"a.xlsx",...},{"fileName":"b.zip",...}]`
 *
 * ## 兼容策略（不做破坏性改动）
 *
 * - 旧字段 `exportedFileJson` 三表都保留，不删除、不迁移历史数据。
 *   历史消息只有旧字段有值，新字段为 NULL，这是正常状态。
 * - 采集端（ChatMessageOrchestrator / RoundtableBotReplyGenerator /
 *   RoundtableIdleManager）落库时两个字段都写：`exportedFileJson` 写
 *   本轮最后一个文件（保持旧路径行为不变，任何还没升级读取新字段的
 *   代码继续可用），`exportedFilesJson` 写本轮全部文件的数组。
 * - 读取端（ChatMessage.exportedFiles / RoundtableMessage 等价 getter）
 *   优先解析 `exportedFilesJson`；为空时退化为把 `exportedFileJson`
 *   包成单元素 list——历史消息不会因为这次迁移丢失已有的文件卡片。
 * - practice_records 补发路径（DailyPracticeWorker）目前每次 practice
 *   只产出一个文件，`exportedFilesJson` 随手一起写（单元素数组），
 *   不强求，但保持三表列结构对称，便于未来扩展。
 */
internal val MIGRATION_65_66 = object : Migration(65, 66) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `messages` ADD COLUMN `exportedFilesJson` TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE `roundtable_messages` ADD COLUMN `exportedFilesJson` TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE `practice_records` ADD COLUMN `exportedFilesJson` TEXT DEFAULT NULL")
    }
}
