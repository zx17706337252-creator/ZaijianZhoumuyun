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

    /**
     * 日程系统第七节新增：批量按 ID 查询项目。
     *
     * 供 ScheduleListTool 展示"关联项目: xxx"用——一次列出多个日程任务时，
     * 先 collect 所有非空 projectId 再一次性查回，避免 N+1 查询。
     * （ScheduleGetTool 是查单条，仍用上面的 getById。）
     *
     * IN (:ids) 在 ids 为空时 Room 会生成 `IN ()` 导致 SQL 语法错误，
     * 调用方需自行保证 ids 非空（见 ProjectRepository.getByIds 的空列表短路）。
     */
    @Query("SELECT * FROM projects WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<ProjectEntity>

    @Query("UPDATE projects SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, now: Long = System.currentTimeMillis())

    // ── goalId 反向关联（S8-窗口07 结论5修复）───────────────────
    // relatedProjectId（Goal→Project，character_goals 表）此前已接入；
    // goalId（Project→Goal，本表）字段建表时已加但从未被任何代码写入/查询。

    /** 设置/清空某项目挂载的目标；goalId=null 表示解除关联（回到"独立项目"）。 */
    @Query("UPDATE projects SET goalId = :goalId, updatedAt = :now WHERE id = :id")
    suspend fun setGoalId(id: String, goalId: String?, now: Long = System.currentTimeMillis())

    /** 反查某个目标当前挂载的是哪个项目（用于切换/清空关联目标时找到旧项目解绑）。 */
    @Query("SELECT * FROM projects WHERE goalId = :goalId LIMIT 1")
    suspend fun getByGoalId(goalId: String): ProjectEntity?

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
    suspend fun removeMember(projectId: String, characterId: Int)

    /**
     * 给定角色 ID，找到其参与的所有活跃项目。
     */
    @Query("""
        SELECT p.* FROM projects p
        JOIN project_members m ON p.id = m.projectId
        WHERE m.characterId = :characterId AND p.status = 'ACTIVE'
        ORDER BY p.updatedAt DESC
    """)
    suspend fun getActiveProjectsForCharacter(characterId: Int): List<ProjectEntity>

    /**
     * 批次4 4-3修复：getActiveProjectsForCharacter 的响应式版本。
     * LearningGoalViewModel 的 growthSummary 原先用 suspend 一次性取值，
     * 项目状态变化时概览卡数字不刷新。改为 Flow 订阅，projects 表或
     * project_members 表任何变更都自动刷新。
     */
    @Query("""
        SELECT p.* FROM projects p
        JOIN project_members m ON p.id = m.projectId
        WHERE m.characterId = :characterId AND p.status = 'ACTIVE'
        ORDER BY p.updatedAt DESC
    """)
    fun observeActiveForCharacter(characterId: Int): Flow<List<ProjectEntity>>
}
