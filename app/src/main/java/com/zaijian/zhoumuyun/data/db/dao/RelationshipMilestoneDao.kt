package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.RelationshipMilestoneEntity

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
}
