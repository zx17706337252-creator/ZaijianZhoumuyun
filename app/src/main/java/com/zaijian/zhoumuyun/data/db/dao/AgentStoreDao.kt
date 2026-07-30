package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import com.zaijian.zhoumuyun.data.db.entity.AgentStoreRecordEntity

@Dao
interface AgentStoreDao {

    /**
     * Upsert 单条记录。REPLACE 策略配合 (ownerCharacterId, collection, key) 唯一索引，
     * 天然实现"存在则更新，不存在则插入"，不需要工具层先查后写（避免竞态）。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: AgentStoreRecordEntity)

    @Query("""
        SELECT * FROM agent_store_records
        WHERE ownerCharacterId = :characterId AND collection = :collection AND `key` = :key
    """)
    suspend fun get(characterId: Int, collection: String, key: String): AgentStoreRecordEntity?

    /**
     * 列出某个 collection 下全部记录，按 updatedAt 倒序（最近更新的在前）。
     * limit 硬上限由调用方（Repository）传入，防止 LLM 传一个超大 limit 把整表读进内存。
     */
    @Query("""
        SELECT * FROM agent_store_records
        WHERE ownerCharacterId = :characterId AND collection = :collection
        ORDER BY updatedAt DESC LIMIT :limit
    """)
    suspend fun listByCollection(characterId: Int, collection: String, limit: Int): List<AgentStoreRecordEntity>

    /**
     * key 前缀匹配（LIKE prefix%）。用于"查一下 2026-07 开头的所有预算记录"这类场景，
     * 不支持任意 LIKE 通配符（prefix 由调用方转义 % 和 _，见 Repository 层）。
     */
    @Query("""
        SELECT * FROM agent_store_records
        WHERE ownerCharacterId = :characterId AND collection = :collection AND `key` LIKE :prefixPattern ESCAPE '\'
        ORDER BY `key` ASC LIMIT :limit
    """)
    suspend fun listByKeyPrefix(characterId: Int, collection: String, prefixPattern: String, limit: Int): List<AgentStoreRecordEntity>

    @Query("""
        DELETE FROM agent_store_records
        WHERE ownerCharacterId = :characterId AND collection = :collection AND `key` = :key
    """)
    suspend fun delete(characterId: Int, collection: String, key: String): Int

    @Query("""
        SELECT COUNT(*) FROM agent_store_records
        WHERE ownerCharacterId = :characterId AND collection = :collection
    """)
    suspend fun countByCollection(characterId: Int, collection: String): Int

    /** 全局记录数（配额检查用，见第四节安全边界）。 */
    @Query("SELECT COUNT(*) FROM agent_store_records WHERE ownerCharacterId = :characterId")
    suspend fun countByOwner(characterId: Int): Int
}
