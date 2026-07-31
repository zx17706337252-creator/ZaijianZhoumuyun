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
                avatarUrl              = existing?.avatarUrl              ?: identity.avatarUrl,
                avatarUrlTall          = existing?.avatarUrlTall          ?: identity.avatarUrlTall,
                avatarUrlShelf         = existing?.avatarUrlShelf         ?: identity.avatarUrlShelf,
                avatarCropCircleOffsetX = existing?.avatarCropCircleOffsetX ?: identity.avatarCropCircleOffsetX,
                avatarCropCircleOffsetY = existing?.avatarCropCircleOffsetY ?: identity.avatarCropCircleOffsetY,
                avatarCropCircleScale   = existing?.avatarCropCircleScale   ?: identity.avatarCropCircleScale,
                avatarCropTallOffsetX  = existing?.avatarCropTallOffsetX  ?: identity.avatarCropTallOffsetX,
                avatarCropTallOffsetY  = existing?.avatarCropTallOffsetY  ?: identity.avatarCropTallOffsetY,
                avatarCropTallScale    = existing?.avatarCropTallScale    ?: identity.avatarCropTallScale,
                avatarCropShelfOffsetX = existing?.avatarCropShelfOffsetX ?: identity.avatarCropShelfOffsetX,
                avatarCropShelfOffsetY = existing?.avatarCropShelfOffsetY ?: identity.avatarCropShelfOffsetY,
                avatarCropShelfScale   = existing?.avatarCropShelfScale   ?: identity.avatarCropShelfScale,
                name                  = existing?.name                  ?: identity.name,
                relationAssumption    = existing?.relationAssumption    ?: identity.relationAssumption,
                conflictStrategy      = existing?.conflictStrategy      ?: identity.conflictStrategy,
                lastEditedNoteField   = existing?.lastEditedNoteField   ?: identity.lastEditedNoteField,
                lastEditedNoteAt      = existing?.lastEditedNoteAt      ?: identity.lastEditedNoteAt,
                soulNoteBackup        = existing?.soulNoteBackup        ?: identity.soulNoteBackup,
                narrativeMemoryBackup = existing?.narrativeMemoryBackup ?: identity.narrativeMemoryBackup,
                userImpressionBackup  = existing?.userImpressionBackup  ?: identity.userImpressionBackup,
                // C6+C8-#17 修复：这两个字段此前未在 copy() 合并列表中，IdentityViewModel.save()
                // 构造的 Entity 未传入它们（默认 "[]"），导致 Migration 从 userRoleLabelPrivate
                // 回填的 characterCallsOwnerJson 在用户首次保存资料后被覆写为 "[]"，
                // IdentityGuard 的自称/称呼异常检测从此失效。补齐与其他列相同的保留逻辑：
                // existing 有值时保留旧值，否则用新传入值（新建角色场景）。
                ownerAliasesJson        = existing?.ownerAliasesJson        ?: identity.ownerAliasesJson,
                characterCallsOwnerJson = existing?.characterCallsOwnerJson ?: identity.characterCallsOwnerJson,
            )
        )
    }

    @Query("SELECT * FROM character_identity WHERE characterId = :characterId")
    abstract suspend fun getById(characterId: Int): CharacterIdentityEntity?

    @Query("SELECT * FROM character_identity WHERE characterId = :characterId")
    abstract fun observeById(characterId: Int): Flow<CharacterIdentityEntity?>

    @Query("UPDATE character_identity SET avatarUrl = :url WHERE characterId = :characterId")
    abstract suspend fun updateAvatarUrl(characterId: Int, url: String): Int

    // v56→v57 公馆/书架头像独立化：原 updateAvatarSource 一次性重置圆形+公馆
    // 两套裁剪参数，语义已不对（三处头像互相独立后，上传圆形不该动公馆/书架）。
    // 拆成三个方法，各自只重置自己那一套裁剪参数为居中/不缩放，避免沿用
    // 上一张图的偏移量套到新图上出现错位，也不再互相影响。

    // 仅圆形：上传新原图时只重置圆形裁剪参数，不影响公馆/书架
    @Query("""
        UPDATE character_identity
        SET avatarUrl = :url,
            avatarCropCircleOffsetX = 0, avatarCropCircleOffsetY = 0, avatarCropCircleScale = 1
        WHERE characterId = :characterId
    """)
    abstract suspend fun updateAvatarSourceCircle(characterId: Int, url: String): Int

    // 仅公馆：上传新原图时只重置公馆裁剪参数，不影响圆形/书架
    @Query("""
        UPDATE character_identity
        SET avatarUrlTall = :url,
            avatarCropTallOffsetX = 0, avatarCropTallOffsetY = 0, avatarCropTallScale = 1
        WHERE characterId = :characterId
    """)
    abstract suspend fun updateAvatarSourceTall(characterId: Int, url: String): Int

    // 仅书架：上传新原图时只重置书架裁剪参数，不影响圆形/公馆
    @Query("""
        UPDATE character_identity
        SET avatarUrlShelf = :url,
            avatarCropShelfOffsetX = 0, avatarCropShelfOffsetY = 0, avatarCropShelfScale = 1
        WHERE characterId = :characterId
    """)
    abstract suspend fun updateAvatarSourceShelf(characterId: Int, url: String): Int

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

    @Query("""
        UPDATE character_identity
        SET avatarCropShelfOffsetX = :offsetX, avatarCropShelfOffsetY = :offsetY, avatarCropShelfScale = :scale
        WHERE characterId = :characterId
    """)
    abstract suspend fun updateAvatarCropShelf(characterId: Int, offsetX: Float, offsetY: Float, scale: Float): Int

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

    // ── 方案 2-3：CAS 乐观锁 upsert ──────────────────────────────

    /**
     * CAS 版本的 soulNote 更新：只有当当前 soulNote 等于 expectedOldValue
     * 时才写入，否则返回 0（表示有并发修改，需要重试或报错）。
     * 用于 IdentityPromotionEvaluator.executePromotion 的 lost update 防护。
     */
    @Query("""
        UPDATE character_identity
        SET soulNote = :value, updatedAt = :now
        WHERE characterId = :characterId AND soulNote = :expectedOldValue
    """)
    abstract suspend fun updateSoulNoteCas(characterId: Int, value: String, expectedOldValue: String, now: Long = System.currentTimeMillis()): Int

    /**
     * 事务化 CAS 版本的 upsert：与 upsertSoulNote 相同的事务包装，
     * 但 UPDATE 使用 CAS 条件。返回 true 表示写入成功，false 表示 CAS 失败。
     */
    @Transaction
    open suspend fun upsertSoulNoteCas(characterId: Int, value: String, expectedOldValue: String): Boolean {
        val now = System.currentTimeMillis()
        if (updateSoulNoteCas(characterId, value, expectedOldValue, now) == 0) {
            // affected rows = 0：可能是行不存在，也可能是 CAS 条件不满足
            // 如果行不存在，尝试插入后重试
            if (getById(characterId) == null) {
                insertIgnore(CharacterIdentityEntity(characterId = characterId))
                return updateSoulNoteCas(characterId, value, expectedOldValue, now) > 0
            }
            // 行存在但 CAS 失败：有并发写入
            return false
        }
        return true
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

    // ── 批次3 3-2修复：头像单列事务化 upsert（消除并发 REPLACE 竞态）─────
    //
    // 旧模式（IdentityViewModel.onAvatarCropXxxPicked）：
    //   updateAvatarSourceXxx() 返回 0 → upsert(CharacterIdentityEntity(avatarUrlXxx=url))
    // 问题：upsert 是 OnConflictStrategy.REPLACE 整行替换。
    //   新建角色尚未 save() 创建行即上传头像，或头像上传与 save()/后台工具并发时，
    //   REPLACE 会把该行其他所有列置空（persona/soulNote/narrativeMemory 等），
    //   用户人设内容静默丢失。代码库自己已把 soulNote 路径标为 P0-2 并用相同范式修复，
    //   但三处头像方法没同步迁移。这里补齐与 upsertSoulNote 完全同构的3个方法。

    /** 事务化单列 upsert：仅更新圆形头像原图 URL + 重置圆形裁剪参数，不影响其他列。 */
    @Transaction
    open suspend fun upsertAvatarSourceCircle(characterId: Int, url: String) {
        if (updateAvatarSourceCircle(characterId, url) == 0) {
            insertIgnore(CharacterIdentityEntity(characterId = characterId))
            updateAvatarSourceCircle(characterId, url)
        }
    }

    /** 事务化单列 upsert：仅更新公馆头像原图 URL + 重置公馆裁剪参数，不影响其他列。 */
    @Transaction
    open suspend fun upsertAvatarSourceTall(characterId: Int, url: String) {
        if (updateAvatarSourceTall(characterId, url) == 0) {
            insertIgnore(CharacterIdentityEntity(characterId = characterId))
            updateAvatarSourceTall(characterId, url)
        }
    }

    /** 事务化单列 upsert：仅更新书架头像原图 URL + 重置书架裁剪参数，不影响其他列。 */
    @Transaction
    open suspend fun upsertAvatarSourceShelf(characterId: Int, url: String) {
        if (updateAvatarSourceShelf(characterId, url) == 0) {
            insertIgnore(CharacterIdentityEntity(characterId = characterId))
            updateAvatarSourceShelf(characterId, url)
        }
    }

    /**
     * INSERT OR IGNORE：行已存在时静默跳过，保证其他列不被覆盖。
     * 仅供上方三个事务化 upsert 内部调用。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnore(identity: CharacterIdentityEntity)

    // ── 批次3 3-1修复：女儿生成回滚基础设施 ──────────────────────
    //
    // onIdentityRegister 按4步写入（character_identity→agent_relation→
    // daughter_character→menstrual_cycle），中途失败时回滚 deleteByMother()
    // 只删 daughter_character 表，character_identity/agent_relation 会残留
    // 孤儿行，且此前 CharacterIdentityDao 没有任何 delete 方法，无法清理。
    // 补一个按 ID delete 方法作为回滚基础设施。

    /**
     * 按 characterId 删除角色资料行。
     * 仅供女儿生成回滚事务 [deleteForRollback] 内部调用，不对外暴露为业务删除入口。
     */
    @Query("DELETE FROM character_identity WHERE characterId = :characterId")
    abstract suspend fun deleteById(characterId: Int)

    /**
     * 批次3 3-1修复：女儿生成注册失败时的整体回滚事务。
     *
     * 删除 character_identity 行（由 onIdentityRegister 第①步写入），
     * 并交由调用方继续清理 agent_relation / daughter_character / menstrual_cycle。
     * 这三张表的清理由各自的 DAO/Repository 方法完成（DaughterCharacterDao.deleteByMother
     * 已有，AgentRelationDao/MenstrualCycleDao 的删除在调用方按需补）。
     *
     * 放在 @Transaction 里：即使只删这一张表，也保证 delete 与后续回填的
     * allocatedId 释放在同一原子边界内，不会被并发写入插队。
     */
    @Transaction
    open suspend fun deleteForRollback(characterId: Int) {
        deleteById(characterId)
    }
}
