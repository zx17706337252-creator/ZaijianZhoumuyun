package com.zaijian.zhoumuyun.data.db.migration

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration

/**
 * Migration 76 → 77：角色间关系头衔系统（方案_角色间关系头衔系统_实施方案 二节）。
 *
 * 新建两张表：
 * 1. character_title_relations：方向性头衔关系（from 对 to 的称呼），
 *    to 一方可以是真实角色（toCharacterId）或假扮预设名单里的虚构身份
 *    （toPresetName），两列互斥，业务层保证有且仅有一个非空。
 * 2. impersonation_presets：假扮身份识别用的独立预设名单，不绑定 DefaultCharacters。
 *
 * 种子数据（初代 9 人两两之间，共 72 行有向记录）：
 * 口径来自与用户逐项确认后的最终结果，**不是**从 DefaultCharacters 硬编码
 * relationships 文本提取——那段文本经核对后确认与实际设定不符（例如原文写
 * "蒂法/露娜/伊芙互为姐妹"，但实际设定姐妹关系不含蒂法），故本次种子数据
 * 完全按用户提供的口径重新生成，不沿用旧文本。
 *
 * 分组：
 * - 姐妹（互认）：露娜(2) / 伊芙(3) / 宥熙(4) 三人两两互认"姐妹"
 * - 女主人：索菲娅(5) / 顾澜(6) 对 露娜(2) / 伊芙(3) / 宥熙(4) 三人的称呼
 * - 女仆：露娜(2) / 伊芙(3) / 宥熙(4) 对 索菲娅(5) / 顾澜(6) 的称呼（反向对称词）
 * - 蒂法(1) 的全部对外关系（对 2-9 号）：一律留空，用户后续在管理页手填，
 *   不做任何假设或臆测。
 * - 其余全部组合（明媚(7) / 莫婉凝(8) / 江凡(9) 三人之间，以及她们与
 *   1-6 号之间）：一律"同伴"，不特殊化。
 *
 * 女儿/孙女角色（characterId >= 1000）没有对应的硬编码关系文本，本次
 * migration 不预填，管理页按需手动录入。
 */
internal val MIGRATION_76_77 = object : Migration(76, 77) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `character_title_relations` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `fromCharacterId` INTEGER NOT NULL,
                `toCharacterId` INTEGER,
                `toPresetName` TEXT,
                `title` TEXT NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_character_title_relations_fromCharacterId_toCharacterId` " +
                "ON `character_title_relations` (`fromCharacterId`, `toCharacterId`)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_character_title_relations_fromCharacterId_toPresetName` " +
                "ON `character_title_relations` (`fromCharacterId`, `toPresetName`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_character_title_relations_toCharacterId` " +
                "ON `character_title_relations` (`toCharacterId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_character_title_relations_toPresetName` " +
                "ON `character_title_relations` (`toPresetName`)"
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `impersonation_presets` (
                `name` TEXT PRIMARY KEY NOT NULL,
                `createdAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // 种子数据：初代 9 人两两之间，72 行有向记录（口径见类注释）。
        // 用参数化 SQL（? 占位符）写入，不做手工字符串拼接，避免转义问题
        // （沿用 Migration75to76 的教训——手搓 JSON/字符串拼接易产生损坏数据）。
        val now = System.currentTimeMillis()
        fun insertSeed(db: SupportSQLiteDatabase, fromId: Int, toId: Int, title: String) {
            db.execSQL(
                "INSERT INTO character_title_relations (fromCharacterId, toCharacterId, toPresetName, title, updatedAt) " +
                    "VALUES (?, ?, NULL, ?, ?)",
                arrayOf<Any>(fromId, toId, title, now),
            )
        }

            insertSeed(db, 1, 2, "")
            insertSeed(db, 1, 3, "")
            insertSeed(db, 1, 4, "")
            insertSeed(db, 1, 5, "")
            insertSeed(db, 1, 6, "")
            insertSeed(db, 1, 7, "")
            insertSeed(db, 1, 8, "")
            insertSeed(db, 1, 9, "")
            insertSeed(db, 2, 1, "")
            insertSeed(db, 2, 3, "姐妹")
            insertSeed(db, 2, 4, "姐妹")
            insertSeed(db, 2, 5, "女仆")
            insertSeed(db, 2, 6, "女仆")
            insertSeed(db, 2, 7, "同伴")
            insertSeed(db, 2, 8, "同伴")
            insertSeed(db, 2, 9, "同伴")
            insertSeed(db, 3, 1, "")
            insertSeed(db, 3, 2, "姐妹")
            insertSeed(db, 3, 4, "姐妹")
            insertSeed(db, 3, 5, "女仆")
            insertSeed(db, 3, 6, "女仆")
            insertSeed(db, 3, 7, "同伴")
            insertSeed(db, 3, 8, "同伴")
            insertSeed(db, 3, 9, "同伴")
            insertSeed(db, 4, 1, "")
            insertSeed(db, 4, 2, "姐妹")
            insertSeed(db, 4, 3, "姐妹")
            insertSeed(db, 4, 5, "女仆")
            insertSeed(db, 4, 6, "女仆")
            insertSeed(db, 4, 7, "同伴")
            insertSeed(db, 4, 8, "同伴")
            insertSeed(db, 4, 9, "同伴")
            insertSeed(db, 5, 1, "")
            insertSeed(db, 5, 2, "女主人")
            insertSeed(db, 5, 3, "女主人")
            insertSeed(db, 5, 4, "女主人")
            insertSeed(db, 5, 6, "同伴")
            insertSeed(db, 5, 7, "同伴")
            insertSeed(db, 5, 8, "同伴")
            insertSeed(db, 5, 9, "同伴")
            insertSeed(db, 6, 1, "")
            insertSeed(db, 6, 2, "女主人")
            insertSeed(db, 6, 3, "女主人")
            insertSeed(db, 6, 4, "女主人")
            insertSeed(db, 6, 5, "同伴")
            insertSeed(db, 6, 7, "同伴")
            insertSeed(db, 6, 8, "同伴")
            insertSeed(db, 6, 9, "同伴")
            insertSeed(db, 7, 1, "")
            insertSeed(db, 7, 2, "同伴")
            insertSeed(db, 7, 3, "同伴")
            insertSeed(db, 7, 4, "同伴")
            insertSeed(db, 7, 5, "同伴")
            insertSeed(db, 7, 6, "同伴")
            insertSeed(db, 7, 8, "同伴")
            insertSeed(db, 7, 9, "同伴")
            insertSeed(db, 8, 1, "")
            insertSeed(db, 8, 2, "同伴")
            insertSeed(db, 8, 3, "同伴")
            insertSeed(db, 8, 4, "同伴")
            insertSeed(db, 8, 5, "同伴")
            insertSeed(db, 8, 6, "同伴")
            insertSeed(db, 8, 7, "同伴")
            insertSeed(db, 8, 9, "同伴")
            insertSeed(db, 9, 1, "")
            insertSeed(db, 9, 2, "同伴")
            insertSeed(db, 9, 3, "同伴")
            insertSeed(db, 9, 4, "同伴")
            insertSeed(db, 9, 5, "同伴")
            insertSeed(db, 9, 6, "同伴")
            insertSeed(db, 9, 7, "同伴")
            insertSeed(db, 9, 8, "同伴")
    }
}
