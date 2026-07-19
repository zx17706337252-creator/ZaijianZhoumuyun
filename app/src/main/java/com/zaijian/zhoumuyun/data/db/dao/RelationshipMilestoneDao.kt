package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.RelationshipMilestoneEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RelationshipMilestoneDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(milestone: RelationshipMilestoneEntity)

    /** 取一对角色（fromId→toId 单向）最近 [limit] 条转折点，按时间倒序 */
    @Query("""
        SELECT * FROM relationship_milestones
        WHERE fromId = :fromId AND toId = :toId
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun getRecent(fromId: String, toId: String, limit: Int = 2): List<RelationshipMilestoneEntity>

    /** 取所有角色在某时间点之后的关系转折点，供离线简报聚合用（整合方案 v2.1 4.10.1）。 */
    @Query("""
        SELECT * FROM relationship_milestones
        WHERE createdAt >= :after
        ORDER BY createdAt DESC
    """)
    suspend fun getAllSince(after: Long): List<RelationshipMilestoneEntity>

    /**
     * 角标 Flow 化改造第1步：与 getAllSince 同一条 SQL 的 Flow 版本，
     * 供 BriefingRepository.observeAttentionItems() 订阅使用。任意一条
     * 关系转折点的新增都会触发这里重新查询。
     */
    @Query("""
        SELECT * FROM relationship_milestones
        WHERE createdAt >= :after
        ORDER BY createdAt DESC
    """)
    fun observeAllSince(after: Long): Flow<List<RelationshipMilestoneEntity>>
}
