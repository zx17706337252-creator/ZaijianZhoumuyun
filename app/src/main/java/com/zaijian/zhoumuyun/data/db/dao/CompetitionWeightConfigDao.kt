package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.CompetitionWeightConfigEntity
import kotlinx.coroutines.flow.Flow

/**
 * CompetitionWeightConfig DAO（裁判与竞争机制 · 项目级评分权重配置）
 *
 * 一个 projectDomain 一条配置（unique 索引），覆盖写即更新，无版本历史。
 * finalizeRound 时读取本配置取基础权重，
 * 再结合 judge_profiles.maturityStage 计算动态信任折扣。
 */
@Dao
interface CompetitionWeightConfigDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(config: CompetitionWeightConfigEntity)

    @Query("SELECT * FROM competition_weight_configs WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CompetitionWeightConfigEntity?

    /** 取某项目方向的权重配置（finalizeRound 主路径） */
    @Query("SELECT * FROM competition_weight_configs WHERE projectDomain = :domain LIMIT 1")
    suspend fun getByDomain(domain: String): CompetitionWeightConfigEntity?

    @Query("SELECT * FROM competition_weight_configs WHERE projectDomain = :domain LIMIT 1")
    fun observeByDomain(domain: String): Flow<CompetitionWeightConfigEntity?>

    @Query("SELECT * FROM competition_weight_configs ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<CompetitionWeightConfigEntity>>

    // ── 权重更新（用户在界面调整后整条覆盖写，直接 insert REPLACE 即可） ──
    // 如需单字段更新：

    @Query("""
        UPDATE competition_weight_configs
        SET userBaseWeight = :userW, judgeBaseWeight = :judgeW, selfBaseWeight = :selfW,
            judgeTrustDynamicEnabled = :dynamicEnabled, updatedAt = :timestamp
        WHERE projectDomain = :domain
    """)
    suspend fun updateWeights(
        domain: String,
        userW: Int,
        judgeW: Int,
        selfW: Int,
        dynamicEnabled: Boolean,
        timestamp: Long = System.currentTimeMillis(),
    )

    @Query("DELETE FROM competition_weight_configs WHERE id = :id")
    suspend fun deleteById(id: String)
}
