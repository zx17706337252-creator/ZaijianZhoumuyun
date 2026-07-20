package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v64 → v65：practice_records 表新增 exportedFileJson 列。
 *
 * ## 背景
 *
 * 圆桌工具调用接线（v1.39）过程中排查 WorldOS 相关的 Entity↔Model 映射路径时
 * 发现的独立问题：DailyPracticeWorker.runSinglePractice() 生成修炼产出文件
 * （writePracticeFile() → ExportMeta）后，这份元数据只临时传给
 * postToRoundtable() 用于首次播报，PracticeRecordEntity 本身从未保存。
 *
 * 若进程在"PracticeRecordEntity 已落库（roundtablePosted=false）"之后、
 * "postToRoundtable() 首次播报成功"之前被杀，repostPendingRecords() 补发
 * 播报时，record 里没有文件信息可用，只能发一条不带文件卡片的纯文字播报——
 * 文件本身已经安全写在磁盘上（specialty_practices/ 目录），但这次播报里的
 * 下载入口永久丢失，用户只能通过其他方式才能找回这个文件。
 *
 * ## 改动
 *
 * practice_records 表新增 `exportedFileJson TEXT DEFAULT NULL` 列：
 * - 历史记录没有回填数据的手段（写文件时的 ExportMeta 早已只存在于运行时
 *   变量里，没有持久化），一律为 NULL，属于正常状态，不需要（也无法）回填。
 *   历史记录若恰好处于"待补发"状态，补发时会退化为此前的行为（无文件卡片
 *   的纯文字播报），不是本次修复引入的新问题，只是没有变得更好。
 * - 纯新增可空列，不改动任何现有表结构、不改动任何业务数据。
 * - 与 roundtable_messages 表当初新增 exportedFileJson 列（Migrations31to40.kt）
 *   同范式、同字段名、同格式（JSON 内 fileName/mimeType/sizeBytes/absolutePath）。
 */
internal val MIGRATION_64_65 = object : Migration(64, 65) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `practice_records` ADD COLUMN `exportedFileJson` TEXT DEFAULT NULL")
    }
}
