package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.RoundtableMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RoundtableMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: RoundtableMessageEntity)

    /**
     * 获取指定圆桌的全部历史消息（按时间升序）。
     *
     * ⚠️ 注意：此方法返回全量数据，适用于消息量可控的场景（如首屏初始化时
     * 仅加载最近 N 条）。对于历史消息量较大的圆桌，应使用分页版本
     * [getByRoundtablePaged]，避免一次性加载导致内存压力和 ANR 风险。
     */
    @Query("SELECT * FROM roundtable_messages WHERE roundtableId = :roundtableId ORDER BY createdAt ASC")
    suspend fun getByRoundtable(roundtableId: String): List<RoundtableMessageEntity>

    /**
     * 分页加载圆桌历史消息（按时间升序）。
     *
     * 推荐用于大量历史消息场景，替代 [getByRoundtable] 全量加载。
     *
     * 使用模式（首屏加载最近 50 条，然后向上滚动触发加载更早的）：
     * ```
     * // 第 1 页（最近 50 条）：offset = 0
     * dao.getByRoundtablePaged(roundtableId, limit = 50, offset = 0)
     * // 第 2 页（更早的 50 条）：offset = 50
     * dao.getByRoundtablePaged(roundtableId, limit = 50, offset = 50)
     * ```
     *
     * @param roundtableId 圆桌 ID
     * @param limit        每页条数，建议 50
     * @param offset       跳过的条数（= pageIndex * limit）
     */
    @Query("""
        SELECT * FROM roundtable_messages
        WHERE roundtableId = :roundtableId
        ORDER BY createdAt ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getByRoundtablePaged(
        roundtableId: String,
        limit: Int,
        offset: Int,
    ): List<RoundtableMessageEntity>

    /**
     * 实时观察指定圆桌的全部消息（Flow，适合 UI 层订阅）。
     *
     * 对于消息量可能持续增长的圆桌，建议结合 [getByRoundtablePaged] 做
     * 分页初始化，此 Flow 仅用于观察新消息追加（新增行触发 collect）。
     */
    @Query("SELECT * FROM roundtable_messages WHERE roundtableId = :roundtableId ORDER BY createdAt ASC")
    fun observeByRoundtable(roundtableId: String): Flow<List<RoundtableMessageEntity>>

    @Query("DELETE FROM roundtable_messages WHERE roundtableId = :roundtableId")
    suspend fun deleteByRoundtable(roundtableId: String)

    @Query("SELECT COUNT(*) FROM roundtable_messages WHERE roundtableId = :roundtableId")
    suspend fun countByRoundtable(roundtableId: String): Int

    /**
     * P6 专长进化系统新增：反查某角色最近一次发言所属的圆桌 ID。
     *
     * 背景：一个角色理论上可能同时存在于多个不同的圆桌（比如角色A
     * 单独和用户的圆桌、角色A和角色B一起的圆桌），系统没有"角色的默认
     * 圆桌"这个概念，roundtableId 本质是"进入圆桌时的成员集合排序拼接"
     * （见 RoundtableViewModel），不是角色的固有属性。
     *
     * DailyPracticeWorker 播报每日修炼时用本方法取"最近一次活跃的圆桌"，
     * 作为播报落点的合理默认——如果该角色从未出现在任何圆桌（用户只跟
     * 它单聊过），返回 null，调用方应跳过本次播报（不创建圆桌、不强行
     * 拼一个只有它自己的圆桌，这超出了本次设计的范围）。
     */
    @Query("""
        SELECT roundtableId FROM roundtable_messages
        WHERE speakerId = :characterId
        ORDER BY createdAt DESC
        LIMIT 1
    """)
    suspend fun findMostRecentRoundtableIdForSpeaker(characterId: String): String?
}
