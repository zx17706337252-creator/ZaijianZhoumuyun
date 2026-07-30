package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 71 → 72：Agent 结构化存储（方案_Agent结构化存储_最终版）
 *
 * 新增一张独立表：agent_store_records。
 * 不改动任何现有表结构，是"纯新增表"的迁移。
 *
 * 自增主键写法（对照 8.2 节）：`id` 对应 AgentStoreRecordEntity 的
 * @PrimaryKey(autoGenerate = true)，必须写成单独一行内联的
 * `id` INTEGER PRIMARY KEY AUTOINCREMENT（SQLite rowid 别名），不能拆成
 * NOT NULL 列 + 表级 PRIMARY KEY(id) 约束——那是两种不同的 schema，会导致
 * Room 生成的期望 schema 和迁移后实际 schema 不一致，也会让运行时插入
 * 不指定 id 的新记录无法正确拿到自增 id。写法与项目里 birth_records 表
 * （Migrations11to20.kt，同样 autoGenerate = true）以及最近的
 * Migration70to71.kt 里 private_chat_messages.id 完全一致。
 */
internal val MIGRATION_71_72 = object : Migration(71, 72) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ── agent_store_records（通用结构化记录表）──────────────────
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `agent_store_records` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT,
                `ownerCharacterId` INTEGER NOT NULL,
                `collection` TEXT NOT NULL,
                `key` TEXT NOT NULL,
                `valueJson` TEXT NOT NULL,
                `valueType` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        // (ownerCharacterId, collection, key) 唯一索引：upsert（REPLACE）依赖此约束实现"存在则更新"
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_store_records_ownerCharacterId_collection_key` " +
                "ON `agent_store_records` (`ownerCharacterId`, `collection`, `key`)"
        )
        // (ownerCharacterId, collection, updatedAt) 索引：listByCollection 按 updatedAt 倒序取最近 N 条
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_store_records_ownerCharacterId_collection_updatedAt` " +
                "ON `agent_store_records` (`ownerCharacterId`, `collection`, `updatedAt`)"
        )
    }
}
