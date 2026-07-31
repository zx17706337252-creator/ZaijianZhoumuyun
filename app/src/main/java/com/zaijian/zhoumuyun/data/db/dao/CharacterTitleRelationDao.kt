package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.zaijian.zhoumuyun.data.db.entity.CharacterTitleRelationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CharacterTitleRelationDao {

    @Upsert
    suspend fun upsert(relation: CharacterTitleRelationEntity)

    @Upsert
    suspend fun upsertAll(relations: List<CharacterTitleRelationEntity>)

    /** from 对 to（真实角色）的头衔，如"姐妹" "女仆"。查不到返回 null。 */
    @Query("SELECT title FROM character_title_relations WHERE fromCharacterId = :fromId AND toCharacterId = :toId LIMIT 1")
    suspend fun getTitle(fromId: Int, toId: Int): String?

    /** from 对 to（预设身份名字，无对应 characterId）的头衔。 */
    @Query("SELECT title FROM character_title_relations WHERE fromCharacterId = :fromId AND toPresetName = :toName LIMIT 1")
    suspend fun getTitleForPresetName(fromId: Int, toName: String): String?

    @Query("SELECT * FROM character_title_relations WHERE fromCharacterId = :fromId AND toCharacterId = :toId LIMIT 1")
    suspend fun getRelation(fromId: Int, toId: Int): CharacterTitleRelationEntity?

    @Query("SELECT * FROM character_title_relations WHERE fromCharacterId = :fromId AND toPresetName = :toName LIMIT 1")
    suspend fun getRelationForPresetName(fromId: Int, toName: String): CharacterTitleRelationEntity?

    /** 管理页用：某角色对其余所有目标（真实角色 + 预设身份）的全部头衔行。 */
    @Query("SELECT * FROM character_title_relations WHERE fromCharacterId = :characterId ORDER BY toCharacterId ASC, toPresetName ASC")
    suspend fun getAllForCharacter(characterId: Int): List<CharacterTitleRelationEntity>

    @Query("SELECT * FROM character_title_relations WHERE fromCharacterId = :characterId ORDER BY toCharacterId ASC, toPresetName ASC")
    fun observeAllForCharacter(characterId: Int): Flow<List<CharacterTitleRelationEntity>>

    @Query("SELECT * FROM character_title_relations ORDER BY fromCharacterId ASC")
    fun observeAll(): Flow<List<CharacterTitleRelationEntity>>

    @Query("DELETE FROM character_title_relations WHERE id = :id")
    suspend fun deleteById(id: Long)
}
