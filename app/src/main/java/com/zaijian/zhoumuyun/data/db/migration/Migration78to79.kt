package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * B5 问题2修复: Migration v78 → v79——job_results 新增 cloudMarkReadSynced 列
 *
 * 背景：ScheduleRepository.syncCloudResults() 对每条拉取到的云端结果调用
 * SupabaseClient.markResultRead() 标记云端已读，但此前完全不检查返回值。
 * 失败时（网络抖动/超时）云端 is_read 永久停留在 false，且本地 syncCloudResults
 * 用 existingIds 去重跳过 insert，导致该结果每次冷启动都被重新拉取一次，
 * 但因为 existingIds 命中而不会重复写入本地——问题不是重复入库，而是这条
 * "已处理"的云端记录永远占着 is_read=false 的名额，且每次冷启动都要白白拉取一次。
 *
 * 新增 job_results.cloudMarkReadSynced 列（INTEGER/Boolean，默认值 1/true）。
 * markResultRead 失败时置为 0（false），下次启动由
 * ScheduleRepository.retryPendingCloudMarkRead() 扫描并补重试，成功后置回 1。
 *
 * 存量数据默认回填为 1（true）：迁移前的历史记录无法追溯当时 markResultRead
 * 是否成功，保守起见不对存量数据发起重试风暴，只对迁移后新产生的失败生效。
 *
 * 纯新增非空默认列（ADD COLUMN ... DEFAULT 1），不涉及表重建。
 */
internal val MIGRATION_78_79 = object : Migration(78, 79) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `job_results` ADD COLUMN `cloudMarkReadSynced` INTEGER NOT NULL DEFAULT 1"
        )
    }
}
