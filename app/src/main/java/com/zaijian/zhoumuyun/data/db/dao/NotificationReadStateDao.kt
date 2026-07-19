package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.NotificationReadStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationReadStateDao {

    /** 标记单条已读。REPLACE：重复标记同一 itemKey 时更新 readAt，不报错。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markRead(entity: NotificationReadStateEntity)

    /** 取全部已读 itemKey 集合，供 ViewModel 在内存里跟 attentionItems 做差集。 */
    @Query("SELECT itemKey FROM notification_read_state")
    suspend fun getAllReadKeys(): List<String>

    /** 响应式版本：MansionHeader 角标需要随已读表变化自动刷新，用 Flow。 */
    @Query("SELECT itemKey FROM notification_read_state")
    fun observeAllReadKeys(): Flow<List<String>>

    /**
     * 清理孤儿数据：删除所有不在 [stillValidKeys] 里的已读记录。
     * 由 NotificationRepository 在每次聚合完成后调用，传入本次
     * "需要关注"区块实际产出的 itemKey 全集——不在这个集合里的
     * 说明根因已消失，对应的已读行可以清掉，避免表无限增长。
     *
     * Room 不支持在 @Query 里直接传 List 做 NOT IN 的同时保证生成
     * 正确的 IN 展开 SQL，这里用 stillValidKeys 为空时的特判 +
     * 非空时的 NOT IN，两种情况都要覆盖（为空时不能生成
     * "NOT IN ()"，SQLite 对空 IN 列表的处理不是所有版本都稳定）。
     */
    @Query("DELETE FROM notification_read_state WHERE itemKey NOT IN (:stillValidKeys)")
    suspend fun deleteNotIn(stillValidKeys: List<String>)

    @Query("DELETE FROM notification_read_state")
    suspend fun deleteAll()
}