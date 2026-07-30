package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.FileIndexEntity

/**
 * file_index 表的 DAO。
 *
 * FTS 采用外部内容表模式（@Fts4(contentEntity)），Room 自动管理触发器，
 * DAO 只需对主表做 insert/delete，FTS 表自动同步。
 */
@Dao
interface FileIndexDao {

    /** upsert（INSERT OR REPLACE 语义，覆盖写入场景直接调这个）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FileIndexEntity)

    /** 按路径删除索引（文件被删除/改名时同步删）。 */
    @Query("DELETE FROM file_index WHERE filePath = :filePath")
    suspend fun deleteByPath(filePath: String)

    /** 按路径查单条（检查是否已索引）。 */
    @Query("SELECT * FROM file_index WHERE filePath = :filePath")
    suspend fun getByPath(filePath: String): FileIndexEntity?

    /**
     * FTS 全文检索 + 路径前缀权限过滤（方案 §4.5：先圈定可见范围再搜）。
     *
     * pathPrefix1/2/3 为当前角色可见的目录前缀（vault 相对路径如
     * "vault/personal/1/"），不可见的 scope 传 null。
     * SQLite 中 `x LIKE null || '%'` 结果为 null（即 false），天然过滤掉不可见路径。
     *
     * JOIN 条件用 fi.rowid = fts.rowid（外部内容 FTS 表的 rowid 与主表一致）。
     */
    @Query("""
        SELECT fi.* FROM file_index fi
        INNER JOIN file_index_fts fts ON fi.rowid = fts.rowid
        WHERE fts.file_index_fts MATCH :query
          AND (:fileType IS NULL OR fi.fileType = :fileType)
          AND (
              fi.filePath LIKE :prefix1 || '%'
              OR fi.filePath LIKE :prefix2 || '%'
              OR fi.filePath LIKE :prefix3 || '%'
          )
        ORDER BY fi.indexedAt DESC
        LIMIT :limit
    """)
    suspend fun search(
        query: String,
        fileType: String?,
        prefix1: String?,
        prefix2: String?,
        prefix3: String?,
        limit: Int = 20,
    ): List<FileIndexEntity>

    @Query("SELECT COUNT(*) FROM file_index")
    suspend fun count(): Int

    /**
     * 一次性拿到全部已索引路径（补建索引用）。
     *
     * filePath 是主键、天然有索引，单次查询开销小。用于在内存里与磁盘
     * 全量文件列表做差集，避免对每个文件逐个 getByPath 造成 N 次数据库往返。
     */
    @Query("SELECT filePath FROM file_index")
    suspend fun getAllPaths(): List<String>
}
