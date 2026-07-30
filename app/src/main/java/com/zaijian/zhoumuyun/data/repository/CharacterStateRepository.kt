package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.agent.AppEvent
import com.zaijian.zhoumuyun.data.agent.EventPublisher
import com.zaijian.zhoumuyun.data.db.dao.CharacterStateDao
import com.zaijian.zhoumuyun.data.db.entity.toDomain
import com.zaijian.zhoumuyun.data.db.entity.toEntity
import com.zaijian.zhoumuyun.data.model.CharacterStateLayer
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.model.SocialMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// ─────────────────────────────────────────────────────────────
//  CharacterStateRepository
//  Phase 4.4（V3 方案）：CharacterStateLayer 持久化的读写入口。
//
//  设计原则：
//  - 数据库中不存在该角色的状态行时，fallback 到 CharacterConfig.initialState，
//    而不是抛异常或返回 CharacterStateLayer() 默认值——保证每个角色第一次
//    被读取时，状态就是她自己设计好的起点，不是空白人格。
//  - socialMode 不持久化（见 CharacterStateEntity 注释），由 applySocialMode()
//    在组装 Prompt 前实时计算并覆盖，调用方负责传入当前在场角色数。
// ─────────────────────────────────────────────────────────────
class CharacterStateRepository(
    private val dao: CharacterStateDao,
) {

    /**
     * 监听指定角色的当前状态；不存在时 fallback 到该角色的 initialState。
     *
     * 复核修复 #20 说明：本方法对女儿角色（characterId>=1000）仍然会 fallback 到
     * 空白 CharacterStateLayer()，这里的 fallback 逻辑本身没有改动——不引入对
     * DaughterCharacterRepository 的依赖是有意为之：本类是同步 Flow 转换
     * （dao.observeState().map{}），而女儿数据查询/JSON解析是 suspend 函数，
     * 两者不能直接组合，若要在这里接入需要把整个类改造成 suspend Flow 或引入
     * 额外的 combine，改动面和风险明显大于收益。
     * 实际的女儿状态补偿放在调用方 ChatViewModel：组装 Prompt 前单独查询
     * DaughterCharacterRepository.getCharacterData()，当这里返回空白默认值时，
     * 用 DaughterStateLayer.toCharacterStateLayer() 的真实数值覆盖，
     * 且无论是否命中空白 fallback，女儿的种类维度描述都改为经由
     * PromptOrchestrator 的 daughterStateLayer/daughterCustomEnums 参数单独渲染，
     * 不依赖这里的 fallback 结果。详见 ChatViewModel.sendMessage() 中的接线。
     */
    fun observeState(characterId: Int): Flow<CharacterStateLayer> =
        dao.observeState(characterId).map { entity ->
            // Fix-13-1：原来 fallback 到 CharacterStateLayer()（空白默认值），
            // 导致角色首次进入对话时人格状态是空白而非设计起点。
            // 修复：从 DefaultCharacters 查找该角色的 initialState 作为 fallback；
            // 无法找到时（女儿等动态角色）再退化到空白默认值。
            entity?.toDomain() ?: DefaultCharacters
                .firstOrNull { it.id == characterId }
                ?.initialState
                ?: CharacterStateLayer()
        }

    /**
     * 一次性获取当前状态（非 Flow），供 ChatViewModel 在组装 Prompt 时直接读取。
     * 内部复用 observeState 的 fallback 逻辑，避免两套读取路径产生不一致。
     */
    suspend fun getState(characterId: Int): CharacterStateLayer =
        observeState(characterId).first()

    suspend fun updateState(characterId: Int, state: CharacterStateLayer) {
        dao.upsertState(state.toEntity(characterId))
        // §6 EventBus 埋点：角色状态更新事件，供 ChainTriggerMatcher 匹配事件触发型链条
        // §11.1：心情值已写入 Room，走 publishPersistent 先落盘再 EventBus.emit()，
        // 防止 App 被杀期间事件丢失。
        EventPublisher.publishPersistent(AppEvent(
            name = "state_updated",
            characterId = characterId,
            payload = mapOf(
                "primaryEmotion" to state.emotionalState.primaryEmotion.name,
                "intensity" to state.emotionalState.intensity,
            ),
        ))
    }

    /** 重置为角色 initialState（删除持久化行，下次读取自动 fallback）。 */
    suspend fun resetToInitial(characterId: Int) {
        dao.resetState(characterId)
    }

    /**
     * 根据当前会话场景，实时计算 socialMode 并覆盖到状态上。
     * 不经过持久化层，调用方在组装 Prompt 前调用。
     *
     * @param activeCharacterCount 当前场景中除该角色外，还有几个其他角色在场
     *   （0 = 她独自一人；1 = 一对一；≥2 = 多人场景）
     */
    fun applySocialMode(state: CharacterStateLayer, activeCharacterCount: Int): CharacterStateLayer {
        val mode = when {
            activeCharacterCount <= 0 -> SocialMode.ALONE
            activeCharacterCount == 1 -> SocialMode.ONE_ON_ONE
            else -> SocialMode.GROUP
        }
        return state.copy(publicState = state.publicState.copy(socialMode = mode))
    }
}
