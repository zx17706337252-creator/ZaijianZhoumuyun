package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.db.dao.CharacterIdentityDao
import com.zaijian.zhoumuyun.data.db.entity.CharacterIdentityEntity
import kotlinx.coroutines.flow.Flow

/**
 * 角色身份 Repository（Phase 3 修复手册第3条）。
 *
 * 包装 [CharacterIdentityDao]，逐方法透传，不改变任何行为。
 * `ChatViewModel`/`RoundtableViewModel` 原先裸持有 `identityDao` 字段，
 * 现在改走这层薄包装。方法集合覆盖 `CharacterIdentityDao` 全部接口
 * （不只是两个 ViewModel 用到的 upsert/getById/observeById/getAll/
 * observeAll），供其他仍裸持有该 DAO 的调用点（如 `SoulMemoryUserTools`）
 * 未来收敛时复用；本次改动本身只涉及 `ChatViewModel`/`RoundtableViewModel`，
 * 不改动这些调用点。
 */
class IdentityRepository(private val dao: CharacterIdentityDao) {

    suspend fun upsert(identity: CharacterIdentityEntity) = dao.upsert(identity)

    suspend fun getAndUpsert(identity: CharacterIdentityEntity) = dao.getAndUpsert(identity)

    suspend fun getById(characterId: Int): CharacterIdentityEntity? = dao.getById(characterId)

    fun observeById(characterId: Int): Flow<CharacterIdentityEntity?> = dao.observeById(characterId)

    suspend fun updateAvatarUrl(characterId: Int, url: String): Int = dao.updateAvatarUrl(characterId, url)

    suspend fun updateAvatarSource(characterId: Int, url: String): Int = dao.updateAvatarSource(characterId, url)

    suspend fun updateAvatarCropCircle(characterId: Int, offsetX: Float, offsetY: Float, scale: Float): Int =
        dao.updateAvatarCropCircle(characterId, offsetX, offsetY, scale)

    suspend fun updateAvatarCropTall(characterId: Int, offsetX: Float, offsetY: Float, scale: Float): Int =
        dao.updateAvatarCropTall(characterId, offsetX, offsetY, scale)

    suspend fun updateName(characterId: Int, name: String, updatedAt: Long = System.currentTimeMillis()) =
        dao.updateName(characterId, name, updatedAt)

    suspend fun getAll(): List<CharacterIdentityEntity> = dao.getAll()

    fun observeAll(): Flow<List<CharacterIdentityEntity>> = dao.observeAll()

    suspend fun getAllIds(): List<Int> = dao.getAllIds()

    suspend fun updateSoulNote(characterId: Int, value: String, now: Long = System.currentTimeMillis()): Int =
        dao.updateSoulNote(characterId, value, now)

    suspend fun updateNarrativeMemory(characterId: Int, value: String, now: Long = System.currentTimeMillis()): Int =
        dao.updateNarrativeMemory(characterId, value, now)

    suspend fun updateUserImpression(characterId: Int, value: String, now: Long = System.currentTimeMillis()): Int =
        dao.updateUserImpression(characterId, value, now)

    suspend fun undoSoulNote(characterId: Int) = dao.undoSoulNote(characterId)

    suspend fun undoNarrativeMemory(characterId: Int) = dao.undoNarrativeMemory(characterId)

    suspend fun undoUserImpression(characterId: Int) = dao.undoUserImpression(characterId)

    suspend fun upsertSoulNote(characterId: Int, value: String) = dao.upsertSoulNote(characterId, value)

    suspend fun upsertNarrativeMemory(characterId: Int, value: String) = dao.upsertNarrativeMemory(characterId, value)

    suspend fun upsertUserImpression(characterId: Int, value: String) = dao.upsertUserImpression(characterId, value)
}
