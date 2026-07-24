package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.zaijian.zhoumuyun.data.db.entity.MemoryTagEntity

/**
 * L2 记忆索引 DAO（Window A-1）。
 *
 * 提供 tag 精确匹配查询和索引维护方法。
 */
@Dao
abstract class MemoryTagDao {

    /**
     * 按角色 ID 和标签列表查询，返回 memoryId + weight，按 weight 降序。
     * L2 检索的核心查询。
     *
     * E1 审计报告任务2 修复：JOIN memories 表并增加 `m.scope = 'PERSONAL'` 过滤。
     * 原查询只有 `WHERE characterId=? AND tag IN (:tags)`，没有 scope 过滤——
     * 当一个角色同时在圆桌中发言（GROUP scope）和私聊（PERSONAL scope）写入
     * 记忆时，个人检索路径会通过 L2 tag 匹配召回 GROUP scope 的群记忆，导致
     * 角色在私聊中"知道"圆桌讨论内容。本文件中所有其他个人侧查询
     *（getCoreMemories/getEternalMemories/observeAll/searchByFts 等）
     * 均已显式加了 `scope = 'PERSONAL'` 过滤（W3-5 系列修复），唯独
     * Window A-1 新加的这条 L2 查询遗漏了。
     *
     * JOIN memories 表的性能影响可忽略：memory_tags 有 (characterId, tag)
     * 索引完成筛选，memories.id 是主键，JOIN 为主键查找。
     *
     * @param characterId 角色 ID
     * @param tags       标签列表（任意一个匹配即命中，OR 语义）
     * @param limit      最多返回条数
     */
    @Query("""
        SELECT mt.memoryId, MAX(mt.weight) as maxWeight
        FROM memory_tags mt
        INNER JOIN memories m ON mt.memoryId = m.id
        WHERE mt.characterId = :characterId AND mt.tag IN (:tags) AND m.scope = 'PERSONAL'
        GROUP BY mt.memoryId
        ORDER BY maxWeight DESC
        LIMIT :limit
    """)
    abstract suspend fun searchByTags(
        characterId: Int,
        tags: List<String>,
        limit: Int,
    ): List<TagSearchResult>

    /** 查询某条记忆的全部标签。 */
    @Query("SELECT * FROM memory_tags WHERE memoryId = :memoryId")
    abstract suspend fun getTagsForMemory(memoryId: String): List<MemoryTagEntity>

    /** 删除某条记忆的全部标签（更新索引时先删后插）。 */
    @Query("DELETE FROM memory_tags WHERE memoryId = :memoryId")
    abstract suspend fun deleteByMemoryId(memoryId: String)

    /** 批量插入标签。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(tags: List<MemoryTagEntity>)

    /**
     * 原子替换某条记忆的全部标签：先删后插，同一事务内执行。
     */
    @Transaction
    open suspend fun replaceTagsForMemory(tags: List<MemoryTagEntity>) {
        if (tags.isEmpty()) return
        val memoryId = tags.first().memoryId
        deleteByMemoryId(memoryId)
        insertAll(tags)
    }

    /** 统计某角色的标签总数（调试/监控用）。 */
    @Query("SELECT COUNT(*) FROM memory_tags WHERE characterId = :characterId")
    abstract suspend fun countForCharacter(characterId: Int): Int
}

/**
 * L2 tag 检索结果。
 *
 * @param memoryId  命中的记忆 ID
 * @param maxWeight 该记忆在所有命中 tag 中的最高权重
 */
data class TagSearchResult(
    val memoryId: String,
    val maxWeight: Int,
)
