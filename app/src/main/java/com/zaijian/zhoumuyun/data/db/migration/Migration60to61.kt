package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v60 → v61：圆桌场景补齐 [thinking:]/[mood:] 标签解析能力（三层分离扩展到圆桌）。
 *
 * 背景：v1.36 问题2（内心独白/心理感受/台词 三层分离）批次1只覆盖了私聊
 * （`ChatMessageOrchestrator.kt`），圆桌场景（`RoundtableBotReplyGenerator.kt`/
 * `RoundtableIdleManager.kt`）从未接入过 `ChatTagParser` 的任何 strip 函数——
 * 圆桌 Bot 回复里的 [thinking:...] 决策思考、圆括号心理描写、[mood:xxx] 系统
 * 情绪标记，此前全部原样泄漏在正文里展示给用户，且原样落库、原样喂回下一轮
 * LLM 上下文。本次补齐圆桌消息表的 thinkingText/psychText 两列，与
 * `messages` 表（MessageEntity，v59→v60 已加）同规格，供圆桌场景解析后的
 * 内心独白/心理感受分别落库。
 *
 * 纯新增列，不改动任何现有数据；roundtable_messages 表现存行的
 * thinkingText/psychText 一律为 NULL（历史消息没有解析数据，属于正常状态，
 * 不需要回填）。
 */
internal val MIGRATION_60_61 = object : Migration(60, 61) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `roundtable_messages` ADD COLUMN `thinkingText` TEXT")
        db.execSQL("ALTER TABLE `roundtable_messages` ADD COLUMN `psychText` TEXT")
    }
}
