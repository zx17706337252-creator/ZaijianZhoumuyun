package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v59 → v60：内心独白 / 心理感受 / 台词 三层分离（v1.36 三项修复方案问题2）。
 *
 * 修复"内心想法折叠功能看起来没实现"——根因是模型输出的心理描写用中文全角圆括号
 * （　），而不是解析器认识的 [thinking:...] 标签，导致解析器完全没识别到，圆括号
 * 内容原样混在台词正文里展示。本次新增独立的 psychText 字段承接圆括号内容，与已有
 * 的 thinkingText（[thinking:] 标签内容）区分：thinkingText 是折叠的"内心独白"（决策
 * 推理，戏外内容，默认不展示），psychText 是不折叠的"心理感受"（戏内内容，用户会想
 * 直接看到）。
 *
 * 纯新增列，不改动任何现有数据；messages 表现存行的 psychText 一律为 NULL
 * （历史消息没有心理描写数据，属于正常状态，不需要回填）。
 */
internal val MIGRATION_59_60 = object : Migration(59, 60) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `messages` ADD COLUMN `psychText` TEXT")
    }
}
