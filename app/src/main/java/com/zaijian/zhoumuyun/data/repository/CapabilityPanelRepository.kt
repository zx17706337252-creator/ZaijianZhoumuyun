package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.agent.AgentToolRegistry
import com.zaijian.zhoumuyun.data.db.dao.AgentActivityDao
import com.zaijian.zhoumuyun.data.db.dao.WorkflowJobDao
import com.zaijian.zhoumuyun.data.db.entity.AgentActivityEventEntity
import com.zaijian.zhoumuyun.data.db.entity.WorkflowJobEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * §2.2.4 给 Window D 的能力面板数据契约。
 *
 * Window D 的"Agent 能力面板"（角色配置页新增模块）展示"当前角色已启用的
 * 工具/技能列表 + 状态"。数据源在 Window B，Window D 只消费这一个只读接口，
 * 不直接查表，双方各自的表结构变化不会互相牵连。
 *
 * 此契约字段在 2.2.2/2.2.3 落地（模块①②③）后有真实数据跑起来，现在定稿。
 * 如后续需要增加字段（如工具调用成功率统计、最近失败的工具等），可在
 * [CharacterCapabilitySnapshot] 上扩展，不影响 Window D 现有消费方。
 */

/**
 * 角色能力快照。
 *
 * @param characterId          角色 ID
 * @param enabledToolNames     当前已注册的工具名称列表（AgentToolRegistry.allNames() 排序后），
 *                              能力面板展示"角色能做什么"
 * @param recentActivity       最近 N 条心迹事件（N=20），能力面板展示"最近做了什么"
 * @param runningWorkflowJob   进行中的工作流（null = 无），能力面板展示"是否正在执行任务"
 */
data class CharacterCapabilitySnapshot(
    val characterId: Int,
    val enabledToolNames: List<String>,
    val recentActivity: List<AgentActivityEventEntity>,
    val runningWorkflowJob: WorkflowJobEntity?,
)

/**
 * 能力面板只读查询接口。
 *
 * Window D 消费方只需调用 [getCharacterCapabilities]，不需要知道底层数据来自
 * 哪些表/DAO。实现类 [CapabilityPanelRepositoryImpl] 在 [AppContainer] 中注入。
 */
interface CapabilityPanelRepository {
    suspend fun getCharacterCapabilities(characterId: Int): CharacterCapabilitySnapshot
}

/**
 * [CapabilityPanelRepository] 的默认实现。
 *
 * 数据来源：
 * - 工具列表：[AgentToolRegistry.allNames]（内存，无 IO）
 * - 最近活动：[AgentActivityDao.observeRecentByCharacter]（Flow.first() 取快照）
 * - 进行中的工作流：[WorkflowJobDao.findAllRunning] 按 characterId 过滤
 */
class CapabilityPanelRepositoryImpl(
    private val agentActivityDao: AgentActivityDao,
    private val workflowJobDao: WorkflowJobDao,
) : CapabilityPanelRepository {

    override suspend fun getCharacterCapabilities(characterId: Int): CharacterCapabilitySnapshot =
        withContext(Dispatchers.IO) {
            val tools = AgentToolRegistry.allNames().sorted()
            val recent = agentActivityDao.observeRecentByCharacter(characterId, 20).first()
            val runningJob = workflowJobDao.findAllRunning()
                .firstOrNull { it.characterId == characterId }
            CharacterCapabilitySnapshot(
                characterId       = characterId,
                enabledToolNames  = tools,
                recentActivity    = recent,
                runningWorkflowJob = runningJob,
            )
        }
}
