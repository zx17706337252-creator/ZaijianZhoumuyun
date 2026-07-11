package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.ProjectEntity
import com.zaijian.zhoumuyun.data.db.entity.ProjectMemberEntity
import com.zaijian.zhoumuyun.data.db.entity.ProjectMilestoneEntity
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────────────────────────
//  ProjectDao — Phase 9 预建（Phase 10 补充完整逻辑）
// ─────────────────────────────────────────────────────────────

@Dao
interface ProjectDao {

    // ── Project ───────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProject(project: ProjectEntity)

    @Query("SELECT * FROM projects WHERE status = 'ACTIVE' ORDER BY updatedAt DESC")
    fun observeActive(): Flow<List<ProjectEntity>>

    /** Phase 20：WorldSimulation Tier2 直接查询活跃项目（非 Flow） */
    @Query("SELECT * FROM projects WHERE status = 'ACTIVE' ORDER BY updatedAt DESC")
    suspend fun getActiveProjectsList(): List<ProjectEntity>

    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ProjectEntity?

    @Query("UPDATE projects SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, now: Long = System.currentTimeMillis())

    // ── Milestones ────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMilestone(milestone: ProjectMilestoneEntity)

    @Query("SELECT * FROM project_milestones WHERE projectId = :projectId ORDER BY createdAt ASC")
    fun observeMilestones(projectId: String): Flow<List<ProjectMilestoneEntity>>

    @Query("SELECT * FROM project_milestones WHERE projectId = :projectId ORDER BY createdAt ASC")
    suspend fun getMilestones(projectId: String): List<ProjectMilestoneEntity>

    @Query("""
        UPDATE project_milestones
        SET isCompleted = 1, completedAt = :completedAt
        WHERE id = :milestoneId
    """)
    suspend fun completeMilestone(milestoneId: String, completedAt: Long = System.currentTimeMillis())

    // ── Members ───────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMember(member: ProjectMemberEntity)

    @Query("SELECT * FROM project_members WHERE projectId = :projectId")
    suspend fun getMembers(projectId: String): List<ProjectMemberEntity>

    @Query("DELETE FROM project_members WHERE projectId = :projectId AND characterId = :characterId")
    suspend fun removeMember(projectId: String, characterId: String)

    /**
     * 给定角色 ID，找到其参与的所有活跃项目。
     */
    @Query("""
        SELECT p.* FROM projects p
        JOIN project_members m ON p.id = m.projectId
        WHERE m.characterId = :characterId AND p.status = 'ACTIVE'
        ORDER BY p.updatedAt DESC
    """)
    suspend fun getActiveProjectsForCharacter(characterId: String): List<ProjectEntity>
}
