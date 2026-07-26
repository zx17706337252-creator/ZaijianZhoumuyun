package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.ProjectKnowledgeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectKnowledgeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: ProjectKnowledgeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<ProjectKnowledgeEntity>)

    @Query("SELECT * FROM project_knowledge WHERE projectId = :projectId ORDER BY importance DESC, updatedAt DESC")
    fun observeByProject(projectId: String): Flow<List<ProjectKnowledgeEntity>>

    @Query("SELECT * FROM project_knowledge WHERE projectId = :projectId ORDER BY importance DESC, updatedAt DESC")
    suspend fun getByProject(projectId: String): List<ProjectKnowledgeEntity>

    /** 按 id 取单条，供编辑前读取（保留未编辑字段，如 characterId/source/createdAt） */
    @Query("SELECT * FROM project_knowledge WHERE id = :id")
    suspend fun getById(id: String): ProjectKnowledgeEntity?

    /** 按重要度取 Top-K，供 Prompt 注入使用 */
    @Query("""
        SELECT * FROM project_knowledge
        WHERE projectId = :projectId
        ORDER BY importance DESC, updatedAt DESC
        LIMIT :limit
    """)
    suspend fun getTopK(projectId: String, limit: Int = 5): List<ProjectKnowledgeEntity>

    /** FTS 全文检索（MATCH 语法） */
    @Query("""
        SELECT pk.* FROM project_knowledge pk
        INNER JOIN project_knowledge_fts fts ON pk.rowid = fts.rowid
        WHERE fts.project_knowledge_fts MATCH :query
          AND pk.projectId = :projectId
        ORDER BY pk.importance DESC
        LIMIT :limit
    """)
    suspend fun searchFts(projectId: String, query: String, limit: Int = 10): List<ProjectKnowledgeEntity>

    @Query("DELETE FROM project_knowledge WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM project_knowledge WHERE projectId = :projectId")
    suspend fun deleteByProject(projectId: String)

    @Query("SELECT COUNT(*) FROM project_knowledge WHERE projectId = :projectId")
    suspend fun countByProject(projectId: String): Int
}
