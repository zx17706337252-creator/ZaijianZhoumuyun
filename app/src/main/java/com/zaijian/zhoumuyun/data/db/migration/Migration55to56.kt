package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * W1 事务安全排查修复：competition_entries 添加 (roundId, characterId) UNIQUE 约束。
 *
 * 背景：CompetitionRoundManager.runCollecting() 存在"先查后写"模式——
 * getByRoundAndCharacter → 判空 → insert(entry)。虽然外层有 getRoundMutex
 * 做协程级串行化，但 W1 指令要求所有"先查后写"必须同时有 DB 层唯一约束兜底，
 * 防止多进程 / 极端并发场景下的重复参赛条目。
 *
 * 修复方案：与 MIGRATION_43_44 同款——先 DEDUP 删除重复行，再重建表加唯一索引。
 * 重复时保留 createdAt 最早的一条（即首次生成的作品）。
 */
internal val MIGRATION_55_56 = object : Migration(55, 56) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ── 去重：每组 (roundId, characterId) 只保留 createdAt 最早的一条 ──
        db.execSQL("""
            DELETE FROM competition_entries
            WHERE id NOT IN (
                SELECT keep_id FROM (
                    SELECT id AS keep_id
                    FROM competition_entries AS ce
                    WHERE ce.id = (
                        SELECT id FROM competition_entries AS inner_ce
                        WHERE inner_ce.roundId = ce.roundId
                          AND inner_ce.characterId = ce.characterId
                        ORDER BY inner_ce.createdAt ASC, inner_ce.id ASC
                        LIMIT 1
                    )
                )
            )
        """.trimIndent())

        // ── 重建表以添加 UNIQUE 约束 ──
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `competition_entries_new` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `roundId` TEXT NOT NULL,
                `characterId` INTEGER NOT NULL,
                `content` TEXT NOT NULL,
                `judgeScore` INTEGER,
                `judgeReasoning` TEXT NOT NULL DEFAULT '',
                `selfScore` INTEGER,
                `selfReasoning` TEXT NOT NULL DEFAULT '',
                `userScore` INTEGER,
                `userComment` TEXT NOT NULL DEFAULT '',
                `userRank` INTEGER,
                `compositeScore` REAL NOT NULL DEFAULT 0.0,
                `createdAt` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO `competition_entries_new`
            SELECT `id`, `roundId`, `characterId`, `content`,
                   `judgeScore`, `judgeReasoning`, `selfScore`, `selfReasoning`,
                   `userScore`, `userComment`, `userRank`, `compositeScore`, `createdAt`
            FROM `competition_entries`
        """.trimIndent())
        db.execSQL("DROP TABLE `competition_entries`")
        db.execSQL("ALTER TABLE `competition_entries_new` RENAME TO `competition_entries`")

        // ── 唯一索引（去重后创建，不会再因重复行失败）──
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_competition_entries_roundId_characterId` ON `competition_entries` (`roundId`, `characterId`)")

        // ── 其余原有普通索引 ──
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_competition_entries_roundId` ON `competition_entries` (`roundId`)")
    }
}