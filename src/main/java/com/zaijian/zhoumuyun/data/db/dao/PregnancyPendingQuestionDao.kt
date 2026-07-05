package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.PregnancyPendingQuestionEntity

@Dao
interface PregnancyPendingQuestionDao {

    /** 单行覆盖写：新问题直接覆盖该母亲角色的旧待确认问题 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PregnancyPendingQuestionEntity)

    /** 读取某母亲角色当前待确认的问题（用户回答后用于配对回填） */
    @Query("SELECT * FROM pregnancy_pending_question WHERE motherCharacterId = :motherCharacterId")
    suspend fun getByMother(motherCharacterId: Int): PregnancyPendingQuestionEntity?

    /** 配对完成（已写入正式答案）后清空待确认状态 */
    @Query("DELETE FROM pregnancy_pending_question WHERE motherCharacterId = :motherCharacterId")
    suspend fun clearByMother(motherCharacterId: Int)
}
