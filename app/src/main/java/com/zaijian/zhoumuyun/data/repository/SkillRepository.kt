package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.db.dao.SkillDao
import com.zaijian.zhoumuyun.data.db.entity.SkillEditLogEntity
import com.zaijian.zhoumuyun.data.db.entity.SkillEntity
import com.zaijian.zhoumuyun.data.db.entity.SkillStatus
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Window C · 技能系统 Repository。
 *
 * 设计来源：Window C 技能系统设计方案 v1.2 §4.1。范式对齐 [MemoryRepository]：
 * `class XxxRepository(private val dao: XxxDao)`，观察方法返回 `Flow`，写入为 `suspend`。
 *
 * ## 关键设计：Agent 工具与 UI 走同一写入入口
 *
 * `SkillCreateTool`/`SkillEditTool`/... 五个 AgentTool（§4.2）和未来 Window D 的技能
 * 管理面板**全部调用本 Repository**，不直接碰 DAO。这样"Agent 自己改的"和"用户手动改的"
 * 走同一条写入路径，Room `Flow` 自动把任何一次写入推给所有 `observeSkills()` 订阅者，
 * UI 侧永远只需接一次 Flow，不用区分"这次更新是 Agent 弄的还是我自己弄的"，也不用
 * 手动通知 UI"数据变了"——与 `MemoryViewModel` 接 `MemoryDao` 同一套现成机制。
 *
 * ## 与设计方案 v1.2 §4.1 草案的差异（以源码为准）
 *
 * - 所有 `skillId: Long` 改为 **`String`**：主键范式对齐 [MemoryEntity]
 *   （`MemoryEntity.kt:53` `@PrimaryKey val id: String`），详见 [SkillEntity] 类注释。
 * - 草案里 `TODO("待 Window A 存储接口定稿后实现")` 的持久化部分已用核实到的
 *   [SkillDao] 真实接口实现，不再留 TODO 骨架。
 * - 新增 [replace]（供 UI 全字段编辑 name/descriptor/category）和 [getById]/
 *   [countAgentCreatedSince]（供 skill_expand / §5 节流），均属 §4.1"确保 UI 能接入"
 *   与 §5 护栏的必要落点，非越界新增。
 *
 * @param skillDao 由 [com.zaijian.zhoumuyun.data.AppContainer] 注入的共享 DAO 实例。
 */
class SkillRepository(
    private val skillDao: SkillDao,
) {
    /** §4.1 / §6：技能管理页列表订阅。 */
    fun observeSkills(characterId: Int): Flow<List<SkillEntity>> =
        skillDao.observeSkills(characterId)

    /** §6：详情页变更历史时间线订阅。 */
    fun observeEditLog(skillId: String): Flow<List<SkillEditLogEntity>> =
        skillDao.observeEditLog(skillId)

    /** 供 [com.zaijian.zhoumuyun.data.agent.SkillRegistry] 生成目录 + skill_create 去重检查。 */
    suspend fun getActiveSkills(characterId: Int): List<SkillEntity> =
        skillDao.getActiveSkills(characterId)

    /** 供 skill_expand 读取 fullContent / skill_edit·deprecate 定位。 */
    suspend fun getById(skillId: String): SkillEntity? = skillDao.getById(skillId)

    /** §5 节流：某角色自 [sinceTimestamp] 起 Agent 自主新建的技能条数。 */
    suspend fun countAgentCreatedSince(characterId: Int, sinceTimestamp: Long): Int =
        skillDao.countAgentCreatedSince(characterId, sinceTimestamp)

    /**
     * 创建技能（§2 创建 / §4.2 skill_create）。原子写主表 + 首条变更日志。
     *
     * 回归修复：[actor] 与 [edit]/[deprecate] 等其余写入同一语义口径——
     * [SkillEditLogEntity.actor] 字段约定为 `"AGENT" | "USER"`（见
     * `SkillEntity.kt:96`），而非 `sourceType`（`"AGENT_AUTONOMOUS"` 等）。
     * 此前误用 `finalized.sourceType` 充当 actor，会让 actor 列混入
     * `AGENT_AUTONOMOUS` 这类来源标记值，与 edit/deprecate 写入的 `AGENT`
     * 不一致，按 actor 过滤变更历史的查询会漏数据。
     *
     * @param actor 谁建的：Agent 侧传 [com.zaijian.zhoumuyun.data.agent.ACTOR_AGENT]
     *   （"AGENT"），UI 侧传 "USER"。
     * @return 新建技能的 id（若入参 [skill.id] 为空则在此生成 UUID）
     */
    suspend fun create(skill: SkillEntity, actor: String): String {
        val now = System.currentTimeMillis()
        val withId = if (skill.id.isEmpty()) skill.copy(id = UUID.randomUUID().toString()) else skill
        val finalized = withId.copy(
            createdAt = if (withId.createdAt == 0L) now else withId.createdAt,
            updatedAt = if (withId.updatedAt == 0L) now else withId.updatedAt,
        )
        skillDao.createWithLog(
            finalized,
            SkillEditLogEntity(
                id = UUID.randomUUID().toString(),
                skillId = finalized.id,
                changeSummary = "创建技能：${finalized.name}",
                actor = actor, // "AGENT" | "USER"，与 edit/deprecate 等其余写入同一口径
                reason = null,
                timestamp = now,
            ),
        )
        return finalized.id
    }

    /**
     * 编辑技能内容（§2 编辑 / §4.2 skill_edit）：仅更新 [newContent]，version+1，
     * 写变更日志。@return false 表示技能不存在。
     */
    suspend fun edit(skillId: String, newContent: String, reason: String, actor: String): Boolean {
        val existing = skillDao.getById(skillId) ?: return false
        val now = System.currentTimeMillis()
        val updated = existing.copy(
            fullContent = newContent,
            version = existing.version + 1,
            updatedAt = now,
        )
        skillDao.updateWithLog(
            updated,
            SkillEditLogEntity(
                id = UUID.randomUUID().toString(),
                skillId = skillId,
                changeSummary = "编辑内容（v${updated.version}）",
                actor = actor,
                reason = reason,
                timestamp = now,
            ),
        )
        return true
    }

    /**
     * 全字段替换（§6 UI 编辑 name/shortDescriptor/category 等）。@return false 表示不存在。
     * Agent 侧 5 个工具不直接用此方法（它们走语义更窄的 [edit]/[deprecate]），此方法
     * 供 Window D 面板的"编辑"操作调用，保证 UI 改动同样落变更日志、同样经 Flow 推送。
     */
    suspend fun replace(
        skill: SkillEntity,
        changeSummary: String,
        reason: String?,
        actor: String,
    ): Boolean {
        val existing = skillDao.getById(skill.id) ?: return false
        val now = System.currentTimeMillis()
        // P1-19 修复：以 existing（DB 最新值）为基准，仅覆盖用户编辑的字段，
        // 保留 Agent 并发修改的 usageCount/successCount/failureCount/lastUsedAt/status/sourceType。
        // 原 skill.copy(...) 从调用方快照出发，会把这些 Agent 管理字段回退到编辑前的旧值。
        val updated = existing.copy(
            name             = skill.name,
            shortDescriptor  = skill.shortDescriptor,
            fullContent      = skill.fullContent,
            category         = skill.category,
            relatedSkillIds  = skill.relatedSkillIds,
            version          = existing.version + 1,
            updatedAt        = now,
        )
        skillDao.updateWithLog(
            updated,
            SkillEditLogEntity(
                id = UUID.randomUUID().toString(),
                skillId = updated.id,
                changeSummary = changeSummary,
                actor = actor,
                reason = reason,
                timestamp = now,
            ),
        )
        return true
    }

    /** §2 废弃 / §4.2 skill_deprecate：状态转 DEPRECATED，保留记录不删除。 */
    suspend fun deprecate(skillId: String, reason: String, actor: String): Boolean {
        val existing = skillDao.getById(skillId) ?: return false
        if (existing.status == SkillStatus.DEPRECATED.name) return true
        val now = System.currentTimeMillis()
        val updated = existing.copy(
            status = SkillStatus.DEPRECATED.name,
            updatedAt = now,
        )
        skillDao.updateWithLog(
            updated,
            SkillEditLogEntity(
                id = UUID.randomUUID().toString(),
                skillId = skillId,
                changeSummary = "废弃技能",
                actor = actor,
                reason = reason,
                timestamp = now,
            ),
        )
        return true
    }

    /** §2 用户干预：从 DEPRECATED 恢复为 ACTIVE。 */
    suspend fun restore(skillId: String, actor: String): Boolean {
        val existing = skillDao.getById(skillId) ?: return false
        if (existing.status == SkillStatus.ACTIVE.name) return true
        val now = System.currentTimeMillis()
        val updated = existing.copy(
            status = SkillStatus.ACTIVE.name,
            updatedAt = now,
        )
        skillDao.updateWithLog(
            updated,
            SkillEditLogEntity(
                id = UUID.randomUUID().toString(),
                skillId = skillId,
                changeSummary = "恢复为 ACTIVE",
                actor = actor,
                reason = null,
                timestamp = now,
            ),
        )
        return true
    }

    /** §2 用户干预：彻底删除（主表 + 变更日志）。Agent 侧不暴露此操作。 */
    suspend fun delete(skillId: String): Boolean {
        if (skillDao.getById(skillId) == null) return false
        skillDao.deleteWithLogs(skillId)
        return true
    }

    /** §3.5 步骤 3：skill_expand 调用，usageCount+1、lastUsedAt 刷新。 */
    suspend fun recordUsage(skillId: String) {
        skillDao.incrementUsage(skillId, System.currentTimeMillis())
    }

    /** §3.5 步骤 6：skill_feedback 调用，成功/失败计数 +1。 */
    suspend fun recordFeedback(skillId: String, success: Boolean) {
        skillDao.adjustFeedback(
            skillId = skillId,
            successDelta = if (success) 1 else 0,
            failureDelta = if (success) 0 else 1,
            now = System.currentTimeMillis(),
        )
    }
}
