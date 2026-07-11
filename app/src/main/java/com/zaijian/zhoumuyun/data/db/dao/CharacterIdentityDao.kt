package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.zaijian.zhoumuyun.data.db.entity.CharacterIdentityEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CharacterIdentityDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(identity: CharacterIdentityEntity)

    /**
     * 在同一 DB 事务内先读后写，消除 save() 里 getById + upsert 两步之间的 TOCTOU 竞态。
     * 并发写入（如头像上传）不再有机会在两步之间插队，avatarUrl 等字段不会被覆盖。
     */
    @Transaction
    open suspend fun getAndUpsert(identity: CharacterIdentityEntity) {
        val existing = getById(identity.characterId)
        upsert(
            identity.copy(
                avatarUrl             = existing?.avatarUrl             ?: identity.avatarUrl,
                name                  = existing?.name                  ?: identity.name,
                relationAssumption    = existing?.relationAssumption    ?: identity.relationAssumption,
                conflictStrategy      = existing?.conflictStrategy      ?: identity.conflictStrategy,
                lastEditedNoteField   = existing?.lastEditedNoteField   ?: identity.lastEditedNoteField,
                lastEditedNoteAt      = existing?.lastEditedNoteAt      ?: identity.lastEditedNoteAt,
                soulNoteBackup        = existing?.soulNoteBackup        ?: identity.soulNoteBackup,
                narrativeMemoryBackup = existing?.narrativeMemoryBackup ?: identity.narrativeMemoryBackup,
                userImpressionBackup  = existing?.userImpressionBackup  ?: identity.userImpressionBackup,
            )
        )
    }

    @Query("SELECT * FROM character_identity WHERE characterId = :characterId")
    abstract suspend fun getById(characterId: Int): CharacterIdentityEntity?

    @Query("SELECT * FROM character_identity WHERE characterId = :characterId")
    abstract fun observeById(characterId: Int): Flow<CharacterIdentityEntity?>

    @Query("UPDATE character_identity SET avatarUrl = :url WHERE characterId = :characterId")
    abstract suspend fun updateAvatarUrl(characterId: Int, url: String): Int

    // v46 头像重新设计：上传新原图时，同时重置两套裁剪参数为居中/不缩放，
    // 避免沿用上一张图的偏移量套到新图上出现错位。
    @Query("""
        UPDATE character_identity
        SET avatarUrl = :url,
            avatarCropCircleOffsetX = 0, avatarCropCircleOffsetY = 0, avatarCropCircleScale = 1,
            avatarCropTallOffsetX = 0, avatarCropTallOffsetY = 0, avatarCropTallScale = 1
        WHERE characterId = :characterId
    """)
    abstract suspend fun updateAvatarSource(characterId: Int, url: String): Int

    @Query("""
        UPDATE character_identity
        SET avatarCropCircleOffsetX = :offsetX, avatarCropCircleOffsetY = :offsetY, avatarCropCircleScale = :scale
        WHERE characterId = :characterId
    """)
    abstract suspend fun updateAvatarCropCircle(characterId: Int, offsetX: Float, offsetY: Float, scale: Float): Int

    @Query("""
        UPDATE character_identity
        SET avatarCropTallOffsetX = :offsetX, avatarCropTallOffsetY = :offsetY, avatarCropTallScale = :scale
        WHERE characterId = :characterId
    """)
    abstract suspend fun updateAvatarCropTall(characterId: Int, offsetX: Float, offsetY: Float, scale: Float): Int

    @Query("UPDATE character_identity SET name = :name, updatedAt = :updatedAt WHERE characterId = :characterId")
    abstract suspend fun updateName(characterId: Int, name: String, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM character_identity")
    abstract suspend fun getAll(): List<CharacterIdentityEntity>

    @Query("SELECT * FROM character_identity")
    abstract fun observeAll(): Flow<List<CharacterIdentityEntity>>

    @Query("SELECT characterId FROM character_identity")
    abstract suspend fun getAllIds(): List<Int>

    // ── Soul/Memory/User 单列更新（备份旧值 → 写新值，同一 SQL）──

    @Query("""
        UPDATE character_identity
        SET soulNoteBackup = soulNote, soulNote = :value,
            lastEditedNoteField = 'soul', lastEditedNoteAt = :now
        WHERE characterId = :characterId
    """)
    abstract suspend fun updateSoulNote(characterId: Int, value: String, now: Long = System.currentTimeMillis()): Int

    @Query("""
        UPDATE character_identity
        SET narrativeMemoryBackup = narrativeMemory, narrativeMemory = :value,
            lastEditedNoteField = 'memory', lastEditedNoteAt = :now
        WHERE characterId = :characterId
    """)
    abstract suspend fun updateNarrativeMemory(characterId: Int, value: String, now: Long = System.currentTimeMillis()): Int

    @Query("""
        UPDATE character_identity
        SET userImpressionBackup = userImpression, userImpression = :value,
            lastEditedNoteField = 'user', lastEditedNoteAt = :now
        WHERE characterId = :characterId
    """)
    abstract suspend fun updateUserImpression(characterId: Int, value: String, now: Long = System.currentTimeMillis()): Int

    @Query("""
        UPDATE character_identity
        SET soulNote = soulNoteBackup, soulNoteBackup = '',
            lastEditedNoteField = NULL
        WHERE characterId = :characterId
    """)
    abstract suspend fun undoSoulNote(characterId: Int)

    @Query("""
        UPDATE character_identity
        SET narrativeMemory = narrativeMemoryBackup, narrativeMemoryBackup = '',
            lastEditedNoteField = NULL
        WHERE characterId = :characterId
    """)
    abstract suspend fun undoNarrativeMemory(characterId: Int)

    @Query("""
        UPDATE character_identity
        SET userImpression = userImpressionBackup, userImpressionBackup = '',
            lastEditedNoteField = NULL
        WHERE characterId = :characterId
    """)
    abstract suspend fun undoUserImpression(characterId: Int)

    // ── P0-2 修复：单列事务化 upsert（消除并发 REPLACE 竞态）──────
    //
    // 旧模式（SoulMemoryUserTools.kt）：
    //   updateSoulNote() 返回 0 → upsert(CharacterIdentityEntity(soulNote=v))
    // 问题：upsert 是 OnConflictStrategy.REPLACE 整行替换。
    //   两个工具并发时（如 soul_update + user_impression_update）：
    //   A 先 upsert(soulNote=x) → B upsert(userImpression=y) REPLACE 整行
    //   → A 的 soulNote 被置 null，字段静默消失。
    //
    // 修复：在 @Transaction 内串行化「读-合并-写」，保证单列更新不干扰其他列。
    // SoulMemoryUserTools 中的三个工具改为调用这三个方法，不再直接 upsert。

    /**
     * 事务化单列 upsert：仅更新 soulNote，不影响其他列。
     * 行存在 → 调用已有的 updateSoulNote（单列 UPDATE）；
     * 行不存在 → 先 INSERT IGNORE 空行，再 UPDATE（避免整行 REPLACE 覆盖并发写入）。
     */
    @Transaction
    open suspend fun upsertSoulNote(characterId: Int, value: String) {
        val now = System.currentTimeMillis()
        if (updateSoulNote(characterId, value, now) == 0) {
            insertIgnore(CharacterIdentityEntity(characterId = characterId))
            updateSoulNote(characterId, value, now)
        }
    }

    /**
     * 事务化单列 upsert：仅更新 narrativeMemory，不影响其他列。
     */
    @Transaction
    open suspend fun upsertNarrativeMemory(characterId: Int, value: String) {
        val now = System.currentTimeMillis()
        if (updateNarrativeMemory(characterId, value, now) == 0) {
            insertIgnore(CharacterIdentityEntity(characterId = characterId))
            updateNarrativeMemory(characterId, value, now)
        }
    }

    /**
     * 事务化单列 upsert：仅更新 userImpression，不影响其他列。
     */
    @Transaction
    open suspend fun upsertUserImpression(characterId: Int, value: String) {
        val now = System.currentTimeMillis()
        if (updateUserImpression(characterId, value, now) == 0) {
            insertIgnore(CharacterIdentityEntity(characterId = characterId))
            updateUserImpression(characterId, value, now)
        }
    }

    /**
     * INSERT OR IGNORE：行已存在时静默跳过，保证其他列不被覆盖。
     * 仅供上方三个事务化 upsert 内部调用。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnore(identity: CharacterIdentityEntity)
}
