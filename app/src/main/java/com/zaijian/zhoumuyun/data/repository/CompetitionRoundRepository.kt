package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.db.dao.CompetitionEntryDao
import com.zaijian.zhoumuyun.data.db.dao.CompetitionRoundDao
import com.zaijian.zhoumuyun.data.db.entity.CompetitionEntryEntity
import com.zaijian.zhoumuyun.data.db.entity.CompetitionRoundEntity
import kotlinx.coroutines.flow.Flow

/**
 * 竞赛轮次 Repository（S8-窗口01 收口：CompetitionViewModel DI 迁移）。
 *
 * 包装 [CompetitionRoundDao] + [CompetitionEntryDao]，逐方法透传，不改变任何行为。
 * `CompetitionViewModel` 原先裸持有 `db = AppDatabase.getInstance(application)`，
 * 通过 `db.competitionRoundDao()` / `db.competitionEntryDao()` 直接访问两个 DAO，
 * 现改走此层从 `AppContainer.instance` 取用。
 *
 * 方法集合只覆盖 CompetitionViewModel 实际用到的部分（getAllPendingRounds/
 * updateStatus/observeAllForDomain/observeById/getById/observeAllForRound），
 * 不做完整 DAO 透传——`CompetitionRoundManager`（Domain/Agent 层）对这两个 DAO
 * 的大量直接调用维持现状不动，那是既定的 Domain 层持有 DAO 构造依赖的模式
 * （与 `JudgeProfileRepository` 类注释里对 `CompetitionRoundManager` 的既定
 * 判断一致），不在本次"ViewModel 绕过 AppContainer"的收敛范围内。
 */
class CompetitionRoundRepository(
    private val roundDao: CompetitionRoundDao,
    private val entryDao: CompetitionEntryDao,
) {
    // ── 轮次 ──────────────────────────────────────────────────

    suspend fun getAllPendingRounds(): List<CompetitionRoundEntity> =
        roundDao.getAllPendingRounds()

    suspend fun updateRoundStatus(id: String, status: String) =
        roundDao.updateStatus(id, status)

    fun observeAllForDomain(domain: String): Flow<List<CompetitionRoundEntity>> =
        roundDao.observeAllForDomain(domain)

    fun observeRoundById(id: String): Flow<CompetitionRoundEntity?> =
        roundDao.observeById(id)

    suspend fun getRoundById(id: String): CompetitionRoundEntity? =
        roundDao.getById(id)

    // ── 参赛条目 ──────────────────────────────────────────────

    fun observeAllEntriesForRound(roundId: String): Flow<List<CompetitionEntryEntity>> =
        entryDao.observeAllForRound(roundId)
}
