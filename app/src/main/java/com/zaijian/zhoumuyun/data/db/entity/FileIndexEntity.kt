package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 文件索引主表（file_search 用）。
 *
 * 不存文件本身，只存路径 + 可搜索文本 + 元数据。
 * filePath 用 vault 相对路径（如 "vault/personal/1/notes.pdf"）做主键，
 * 改名/移动时旧记录先删再插（主键变），覆盖写入用 upsert（INSERT OR REPLACE）。
 */
@Entity(
    tableName = "file_index",
    indices = [
        Index("fileType"),
        Index("indexedAt"),
    ],
)
data class FileIndexEntity(
    @PrimaryKey val filePath: String,
    val fileName: String,
    val fileType: String,        // pdf/image/audio/video/docx/txt/md/...
    val extractedText: String?,  // PDF/docx 等可提取文本全文，图片/音视频为 null
    val sizeBytes: Long,
    val createdAt: Long,
    val indexedAt: Long,
    // 修复 #6：中文全文检索误判——FTS4 默认 tokenizer（simple/unicode61）都不对
    // 连续中文字符做切分，一段没有标点/空格的中文会被整体索引成一个 token，
    // 前缀匹配（query*）要求整段查询是该 token 的前缀，几乎永远不命中中间的关键词
    // （实测：整句"这是一份关于房屋永久使用权的合同文本"能整句前缀匹配，但查询
    // "合同"完全搜不到）。与 MemoryEntity/ChineseTokenizer 采用同一套已验证方案：
    // 写入侧用 ChineseTokenizer.tokenizeJoined(fileName + extractedText) 生成
    // 空格分隔的真实分词结果存入本列，FTS 表改为对本列（而非原始 fileName/
    // extractedText）做 MATCH，查询侧同样用 ChineseTokenizer 分词后各词加 *
    // 做前缀 OR 匹配——写时怎么切、查时就怎么切，前缀匹配才能真正生效。
    val keywords: String = "",
)

/**
 * file_index 的 FTS4 外部内容虚拟表。
 *
 * 使用 @Fts4(contentEntity = FileIndexEntity::class)，
 * Room 自动管理 content 同步触发器，DAO 无需手动 insert/delete FTS 行。
 * JOIN 条件用 fi.rowid = fts.rowid。
 *
 * 修复 #6：显式指定 tokenizer = TOKENIZER_UNICODE61（此前未指定，Room/SQLite
 * 默认落到 simple tokenizer，对中文的处理效果与 unicode61 相同——都不切分连续
 * 中文，仅在此处统一显式声明，与 MemoryFtsEntity 保持同一写法）。新增 keywords
 * 列承接 [FileIndexEntity.keywords]（写入侧已分词、空格分隔），fileName/
 * extractedText 两列继续保留（原始文本，供非中文内容通过 unicode61 天然分词
 * 命中，不影响中文路径——中文查询词经 ChineseTokenizer 分词后只会匹配到
 * keywords 列里对应的词，不依赖这两列的切分效果）。
 */
@Fts4(contentEntity = FileIndexEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "file_index_fts")
data class FileIndexFtsEntity(
    val fileName: String,
    val extractedText: String?,
    val keywords: String,
)
