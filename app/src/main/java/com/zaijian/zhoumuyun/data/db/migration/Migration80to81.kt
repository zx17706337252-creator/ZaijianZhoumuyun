package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 80 → 81：修复 #6【中危】文件搜索中文全文检索误判。
 *
 * 背景：`file_index_fts`（Migration74to75 建表）未显式指定 tokenizer，
 * Room/SQLite 落到默认的 simple tokenizer；即便改用 unicode61，效果对中文
 * 也一样——两者都不对连续中文字符做任何切分，一段没有标点/空格的中文
 * 会被整体索引成一个 token。FTS4 前缀匹配（query*）要求整段查询是该 token
 * 的前缀，导致查询"合同"这类出现在句子中间的关键词，即使文件内容确实
 * 包含"合同"二字，也几乎永远搜不到（表现为"误判为未找到"）。
 *
 * 与 memories_fts（MemoryDao/ChineseTokenizer，同类问题的既有修复）采用
 * 同一套已验证方案：写入侧（FileIndexWorker）用 ChineseTokenizer 对文件名+
 * 正文分词后空格拼接，存入新增的 file_index.keywords 列；file_index_fts
 * 新增 keywords 列并纳入 MATCH 索引；查询侧（FileSearchTool）同样用
 * ChineseTokenizer 分词后各词加 * 做前缀 OR 匹配——写时怎么切、查时就怎么切，
 * 前缀匹配才能真正对中文生效。
 *
 * 本迁移只做 schema 层面的改动：
 * 1. file_index 新增 keywords 列（TEXT NOT NULL DEFAULT ''）
 * 2. file_index_fts 删除重建：新增 keywords 列 + 显式 tokenize=unicode61
 *    （与 fileName/extractedText 保持同一 tokenizer，不引入行为不一致）
 * 3. 外部内容 FTS 表的 4 条同步触发器同步删除重建，补上 keywords 列
 *    （迁移路径 Room 不会自动补触发器，必须手工建，做法与 Migration74to75
 *    建表时的注释一致）
 *
 * 关于存量数据的 keywords 回填：分词依赖 ICU BreakIterator（Kotlin/Android
 * 运行时能力），SQL 迁移语句本身做不到，因此这里不在迁移里做"从旧
 * extractedText 计算 keywords"这类计算型回填，避免在 DB 迁移里引入
 * 平台相关的重逻辑。
 *
 * 改为清空 file_index（DELETE，不清 vault 里的真实文件——数据源仍在磁盘，
 * 不会丢失），借助已有的"冷启动全量补建索引"机制（ZaijianApp.kt 每次冷
 * 启动都调用 reindexAllVaultFilesOnColdStart() → reindexUnindexedFilesUnder()，
 * 按"磁盘文件路径 - 已索引路径"差集入队 FileIndexWorker）自然重新索引
 * 全部文件——新一轮索引会用本次改造后的 FileIndexWorker，正确写入
 * keywords。不新增一次性回填任务，复用已存在的机制，迁移后第一次冷启动
 * 即可自愈，用户唯一能感知的影响是"升级后短暂时间内文件搜索结果会逐步
 * 恢复"，不会有数据丢失。
 */
internal val MIGRATION_80_81 = object : Migration(80, 81) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. file_index 新增 keywords 列
        db.execSQL("ALTER TABLE `file_index` ADD COLUMN `keywords` TEXT NOT NULL DEFAULT ''")

        // 2. 删除旧 FTS 表与其同步触发器
        db.execSQL("DROP TABLE IF EXISTS `file_index_fts`")
        db.execSQL("DROP TRIGGER IF EXISTS `room_fts_content_sync_file_index_fts_BEFORE_UPDATE`")
        db.execSQL("DROP TRIGGER IF EXISTS `room_fts_content_sync_file_index_fts_BEFORE_DELETE`")
        db.execSQL("DROP TRIGGER IF EXISTS `room_fts_content_sync_file_index_fts_AFTER_UPDATE`")
        db.execSQL("DROP TRIGGER IF EXISTS `room_fts_content_sync_file_index_fts_AFTER_INSERT`")

        // 3. 重建 FTS4 外部内容虚拟表：新增 keywords 列 + 显式 tokenize=unicode61
        db.execSQL("""
            CREATE VIRTUAL TABLE IF NOT EXISTS `file_index_fts`
            USING fts4(
                content=`file_index`,
                `fileName`,
                `extractedText`,
                `keywords`,
                tokenize=unicode61
            )
        """.trimIndent())
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_file_index_fts_BEFORE_UPDATE BEFORE UPDATE ON `file_index` BEGIN DELETE FROM `file_index_fts` WHERE `docid`=OLD.`rowid`; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_file_index_fts_BEFORE_DELETE BEFORE DELETE ON `file_index` BEGIN DELETE FROM `file_index_fts` WHERE `docid`=OLD.`rowid`; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_file_index_fts_AFTER_UPDATE AFTER UPDATE ON `file_index` BEGIN INSERT INTO `file_index_fts`(`docid`, `fileName`, `extractedText`, `keywords`) VALUES (NEW.`rowid`, NEW.`fileName`, NEW.`extractedText`, NEW.`keywords`); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_file_index_fts_AFTER_INSERT AFTER INSERT ON `file_index` BEGIN INSERT INTO `file_index_fts`(`docid`, `fileName`, `extractedText`, `keywords`) VALUES (NEW.`rowid`, NEW.`fileName`, NEW.`extractedText`, NEW.`keywords`); END")

        // 4. 清空存量索引，交给已有的冷启动全量补建机制用新逻辑重新索引
        //    （file_index_fts 是外部内容表，DELETE file_index 会经 BEFORE_DELETE
        //    触发器自动清掉对应的 FTS 行，不需要再手动清 file_index_fts）
        db.execSQL("DELETE FROM `file_index`")
    }
}
