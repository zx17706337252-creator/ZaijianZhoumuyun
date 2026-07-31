package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.db.dao.CharacterTitleRelationDao
import com.zaijian.zhoumuyun.data.db.dao.ImpersonationPresetDao
import com.zaijian.zhoumuyun.data.db.entity.CharacterTitleRelationEntity
import com.zaijian.zhoumuyun.data.db.entity.ImpersonationPresetEntity
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

/**
 * 角色间关系头衔 Repository（方案_角色间关系头衔系统_实施方案 二节）
 *
 * 统一读写入口，包一层 DAO + try-catch 兜底，风格对齐 RelationshipReadRepository：
 * 挂起查询失败时返回 null（语义等价于"未认定关系/查询失败"，调用方一律按
 * "关系不明确"分支处理，不阻断 prompt 生成），Flow 失败时 emit 空列表。
 */
class CharacterTitleRelationRepository(
    private val titleDao: CharacterTitleRelationDao,
    private val presetDao: ImpersonationPresetDao,
) {
    companion object {
        private const val TAG = "CharacterTitleRelationRepo"
    }

    // ── 头衔关系：读 ──────────────────────────────────────────────

    /** from 对 to（真实角色）的头衔。查不到或查询失败均返回 null，调用方按"关系不明确"处理。 */
    suspend fun getTitle(fromId: Int, toId: Int): String? = try {
        titleDao.getTitle(fromId, toId)?.takeIf { it.isNotBlank() }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        ZLog.e(TAG, "getTitle($fromId→$toId) 查询失败", e)
        null
    }

    /** from 对 to（预设身份名字，无对应 characterId）的头衔。 */
    suspend fun getTitleForPresetName(fromId: Int, toName: String): String? = try {
        titleDao.getTitleForPresetName(fromId, toName)?.takeIf { it.isNotBlank() }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        ZLog.e(TAG, "getTitleForPresetName($fromId→$toName) 查询失败", e)
        null
    }

    suspend fun getRelation(fromId: Int, toId: Int): CharacterTitleRelationEntity? = try {
        titleDao.getRelation(fromId, toId)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        ZLog.e(TAG, "getRelation($fromId→$toId) 查询失败", e)
        null
    }

    suspend fun getAllForCharacter(characterId: Int): List<CharacterTitleRelationEntity> = try {
        titleDao.getAllForCharacter(characterId)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        ZLog.e(TAG, "getAllForCharacter($characterId) 查询失败", e)
        emptyList()
    }

    fun observeAllForCharacter(characterId: Int): Flow<List<CharacterTitleRelationEntity>> =
        titleDao.observeAllForCharacter(characterId)
            .catch { e ->
                ZLog.e(TAG, "observeAllForCharacter($characterId) 查询失败", e)
                emit(emptyList())
            }

    fun observeAll(): Flow<List<CharacterTitleRelationEntity>> =
        titleDao.observeAll()
            .catch { e ->
                ZLog.e(TAG, "observeAll() 查询失败", e)
                emit(emptyList())
            }

    // ── 头衔关系：写 ──────────────────────────────────────────────

    suspend fun setTitle(fromId: Int, toId: Int, title: String) {
        try {
            titleDao.upsert(
                CharacterTitleRelationEntity(
                    fromCharacterId = fromId,
                    toCharacterId = toId,
                    toPresetName = null,
                    title = title,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.e(TAG, "setTitle($fromId→$toId, \"$title\") 写入失败", e)
        }
    }

    suspend fun setTitleForPresetName(fromId: Int, toName: String, title: String) {
        try {
            titleDao.upsert(
                CharacterTitleRelationEntity(
                    fromCharacterId = fromId,
                    toCharacterId = null,
                    toPresetName = toName,
                    title = title,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.e(TAG, "setTitleForPresetName($fromId→$toName, \"$title\") 写入失败", e)
        }
    }

    suspend fun upsertAll(relations: List<CharacterTitleRelationEntity>) {
        try {
            titleDao.upsertAll(relations)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.e(TAG, "upsertAll(${relations.size} 行) 写入失败", e)
        }
    }

    suspend fun deleteById(id: Long) {
        try {
            titleDao.deleteById(id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.e(TAG, "deleteById($id) 删除失败", e)
        }
    }

    // ── 假扮预设名单 ──────────────────────────────────────────────

    suspend fun addPreset(name: String) {
        try {
            presetDao.upsert(ImpersonationPresetEntity(name = name))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.e(TAG, "addPreset(\"$name\") 写入失败", e)
        }
    }

    suspend fun removePreset(name: String) {
        try {
            presetDao.deleteByName(name)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.e(TAG, "removePreset(\"$name\") 删除失败", e)
        }
    }

    /** 假扮识别命中判定：消息里的 XX 是否在预设名单中。查询失败时保守返回 false（不误判命中）。 */
    suspend fun isPresetName(name: String): Boolean = try {
        presetDao.exists(name)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        ZLog.e(TAG, "isPresetName(\"$name\") 查询失败", e)
        false
    }

    suspend fun getAllPresets(): List<ImpersonationPresetEntity> = try {
        presetDao.getAll()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        ZLog.e(TAG, "getAllPresets() 查询失败", e)
        emptyList()
    }

    fun observeAllPresets(): Flow<List<ImpersonationPresetEntity>> =
        presetDao.observeAll()
            .catch { e ->
                ZLog.e(TAG, "observeAllPresets() 查询失败", e)
                emit(emptyList())
            }
}
