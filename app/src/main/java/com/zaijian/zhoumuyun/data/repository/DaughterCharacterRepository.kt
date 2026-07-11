package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.db.dao.DaughterCharacterDao
import com.zaijian.zhoumuyun.util.ZLog
import com.zaijian.zhoumuyun.data.db.entity.DaughterCharacterEntity
import com.zaijian.zhoumuyun.data.model.DaughterCustomEnums
import com.zaijian.zhoumuyun.data.model.DaughterDataException
import com.zaijian.zhoumuyun.data.model.DaughterStateLayer
import com.zaijian.zhoumuyun.data.model.toCharacterConfig
import com.zaijian.zhoumuyun.data.model.toDaughterCharacterData
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────────────────────────
//  DaughterCharacterRepository（D4，v24→v25；getCharacterConfig
//  及反查回填方法为 D4 触发点接入 Part 4 新增，v27→v28）
//
//  女儿人格数据的唯一读写入口。
//
//  设计原则（对标 CharacterStateRepository）：
//  - getByMother 返回 null 时，调用方必须抛出明确错误，
//    不允许让空人格女儿进入对话——此层不做静默降级。
//  - StateLayer 更新走专用方法 updateStateLayer，不整体替换记录，
//    与母亲侧 CharacterStateRepository.updateState() 语义对齐。
//  - D4 生成器写入走 saveDaughter（upsert），覆盖旧版本，
//    generatedAt 自动更新为当前时间戳。
//  - getCharacterConfig 是 ChatViewModel 取"女儿完整配置"的唯一入口，
//    内部封装反查 + 解析 + 拼装三步，调用方不需要关心 daughter_character
//    表的内部结构。
// ─────────────────────────────────────────────────────────────

class DaughterCharacterRepository(
    private val dao: DaughterCharacterDao,
) {

    // ── 读取 ────────────────────────────────────────────────

    /**
     * 监听指定母亲的女儿记录（Flow）。
     * D4 生成前发射 null，生成后发射完整 Entity。
     * UI 层用此驱动"女儿档案"页面的状态。
     */
    fun observeByMother(motherCharacterId: Int): Flow<DaughterCharacterEntity?> =
        dao.observeByMother(motherCharacterId)

    /**
     * 一次性读取女儿记录（挂起）。
     *
     * 返回 null 代表 D4 尚未生成，调用方必须处理此情况：
     * - PromptOrchestrator：拒绝注入女儿层，记录错误日志
     * - 对话入口：弹出明确提示，不允许进入女儿对话
     *
     * 此层不 throw，由调用方决定如何响应 null。
     */
    suspend fun getByMother(motherCharacterId: Int): DaughterCharacterEntity? =
        dao.getByMother(motherCharacterId)

    /**
     * 检查指定母亲的女儿记录是否存在且完整。
     * 进入女儿对话前的防御性校验入口。
     *
     * "完整"定义：三列 JSON 均非空字符串。
     * 不做 JSON 解析（性能考量），JSON 格式校验在 D4 生成器写入时保证。
     */
    suspend fun isDaughterReady(motherCharacterId: Int): Boolean {
        val entity = dao.getByMother(motherCharacterId) ?: return false
        return entity.identityJson.isNotBlank()
            && entity.stateLayerJson.isNotBlank()
            && entity.customEnumsJson.isNotBlank()
    }

    // ── 写入 ────────────────────────────────────────────────

    /**
     * D4 生成器写入完整女儿记录。
     * upsert 语义：首次生成插入，重新生成覆盖（generatedAt 更新）。
     */
    suspend fun saveDaughter(entity: DaughterCharacterEntity) {
        dao.upsert(entity)
    }

    /**
     * 运行时更新 StateLayer（情绪引擎调用，目前尚无实际调用方——
     * 情绪引擎本身还未接入，这是预留接口）。
     * 只写 stateLayerJson 列，不碰 identity 和枚举词库。
     *
     * 写入前校验（补齐，原先直接透传给 DAO，完全绕开 D4 生成器那套
     * key 存在性校验）：解析新 stateLayerJson，并用这一行已有的
     * customEnumsJson 做跨对象校验，规则与
     * [DaughterCharacterEntity.toDaughterCharacterData]（读库端）、
     * [com.zaijian.zhoumuyun.data.manager.DaughterCharacterGenerator.parseAndValidate]
     * （D4 生成器写库端）完全一致——三处校验口径必须对齐，否则会重新
     * 出现"能写进去、读出来才报错"的同类问题。
     *
     * @param motherCharacterId 母亲 characterId
     * @param stateLayerJson    序列化后的新 StateLayer JSON 字符串
     * @throws DaughterDataException stateLayerJson 解析失败、四个索引 key
     *         （maskKey/primaryEmotionKey/currentNeedKey/currentFearKey）
     *         非空校验不通过，或任一 key 在这个女儿现有的 customEnums
     *         对应数组中找不到匹配项；motherCharacterId 查不到女儿记录
     *         （理论上不应发生——调用方应该只对已生成的女儿调用此方法）。
     *         校验失败时不会写库，调用方需要自行决定重试或放弃这次更新，
     *         不能吞掉异常静默跳过——静默跳过会让情绪引擎以为更新成功了。
     */
    suspend fun updateStateLayer(motherCharacterId: Int, stateLayerJson: String) {
        val newStateLayer = DaughterStateLayer.fromJson(stateLayerJson)

        val existing = dao.getByMother(motherCharacterId)
            ?: throw DaughterDataException(
                "updateStateLayer: motherCharacterId=$motherCharacterId 查不到女儿记录"
            )
        val customEnums = DaughterCustomEnums.fromJson(existing.customEnumsJson)

        if (customEnums.findMask(newStateLayer.maskKey) == null)
            throw DaughterDataException(
                "updateStateLayer: maskKey='${newStateLayer.maskKey}' 不在 customEnums.maskStates 中"
            )
        if (customEnums.findEmotion(newStateLayer.primaryEmotionKey) == null)
            throw DaughterDataException(
                "updateStateLayer: primaryEmotionKey='${newStateLayer.primaryEmotionKey}' 不在 customEnums.emotionStates 中"
            )
        if (customEnums.findNeed(newStateLayer.currentNeedKey) == null)
            throw DaughterDataException(
                "updateStateLayer: currentNeedKey='${newStateLayer.currentNeedKey}' 不在 customEnums.needStates 中"
            )
        if (customEnums.findFear(newStateLayer.currentFearKey) == null)
            throw DaughterDataException(
                "updateStateLayer: currentFearKey='${newStateLayer.currentFearKey}' 不在 customEnums.fearStates 中"
            )

        dao.updateStateLayer(motherCharacterId, stateLayerJson)
    }

    // ── D4 触发点接入 Part 4：女儿自己 ID → CharacterConfig ─────

    /**
     * 注册阶段回填：把刚分配的女儿 characterId 写回 daughter_character 表，
     * 让 [getCharacterConfig] 之后能用这个 ID 反查回这一行。
     *
     * 必须在 identityDao.upsert() 成功之后调用（见 ChatViewModel.onIdentityRegister），
     * 失败不影响角色资料表的注册，但会导致反查失效。
     */
    suspend fun updateDaughterCharacterId(motherCharacterId: Int, daughterCharacterId: Int) {
        dao.updateDaughterCharacterId(motherCharacterId, daughterCharacterId)
    }

    /**
     * 唯一入口：给定女儿自己的 characterId（1000+），返回拼装好的完整
     * [com.zaijian.zhoumuyun.data.model.CharacterConfig]。
     *
     * 内部串联三步：
     *   1. dao.getByDaughterCharacterId() —— 用女儿自己的 ID 反查 daughter_character 行
     *   2. entity.toDaughterCharacterData() —— 三列裸 JSON 解析成强类型对象
     *   3. data.toCharacterConfig() —— 拼成 CharacterConfig
     *
     * 调用方（ChatViewModel.sendMessage()）应该这样用：
     * ```
     * val character = DefaultCharacters.firstOrNull { it.id == currentCharacterId }
     *     ?: daughterCharacterRepo.getCharacterConfig(currentCharacterId)
     *     ?: return@launch
     * ```
     *
     * 返回 null 的情况：
     * - 这个 ID 根本不是女儿（既不在 DefaultCharacters，也查不到 daughter_character 行）
     * - 反查列（daughterCharacterId）还没回填（注册流程中断，应检查日志）
     *
     * 抛出 [DaughterDataException] 的情况：
     * - 反查到了行，但三列 JSON 解析失败或关键字段缺失（数据本身损坏）。
     *   这里选择让异常往上抛，不在 Repository 层吞掉——与 [toDaughterCharacterData]
     *   的既定防御性原则一致：宁可这一条消息报错，不能让女儿带着残缺人格说话。
     */
    suspend fun getCharacterConfig(
        daughterCharacterId: Int,
    ): com.zaijian.zhoumuyun.data.model.CharacterConfig? {
        val entity = dao.getByDaughterCharacterId(daughterCharacterId) ?: return null
        return entity.toDaughterCharacterData().toCharacterConfig(daughterCharacterId)
    }

    // ── 家族链查询（Step 4，FamilyListViewModel / PresenceViewModel 使用）──

    /**
     * 查询以 [firstGenCharacterId] 为起点的完整后代链（最多两层，固定不递归）。
     *
     * 返回有序列表：[第二代 CharacterConfig, 第三代 CharacterConfig（如有）]
     * 不含第一代母亲本身（调用方自己持有 DefaultCharacters 里的那条）。
     *
     * 查询逻辑：
     *   1. 用 firstGenCharacterId 查直接后代行（第二代）
     *   2. 取第二代行的 daughterCharacterId，再查一次（第三代）
     *   3. 最多到此为止，固定封顶三代
     *
     * 任一层 daughterCharacterId 为 null（注册未完成）或查不到行，
     * 该层及之后的层直接停止，不报错——调用方拿到的列表可能只有 1 项或 0 项。
     */
    suspend fun getFamilyChain(
        firstGenCharacterId: Int,
    ): List<com.zaijian.zhoumuyun.data.model.CharacterConfig> {
        val result = mutableListOf<com.zaijian.zhoumuyun.data.model.CharacterConfig>()

        // ── 第二代 ──────────────────────────────────────────────
        val gen2Entity = dao.getAllWithMotherId(firstGenCharacterId).firstOrNull()
            ?: return result   // 没有女儿，直接返回空列表

        val gen2CharacterId = gen2Entity.daughterCharacterId
            ?: return result   // 注册尚未完成，暂不纳入

        val gen2Config = try {
            gen2Entity.toDaughterCharacterData().toCharacterConfig(gen2CharacterId)
        } catch (e: Exception) {
            ZLog.w("DaughterCharacterRepo", "gen2 parse failed, skip", e)
            return result
        }
        result.add(gen2Config)

        // ── 第三代 ──────────────────────────────────────────────
        val gen3Entity = dao.getAllWithMotherId(gen2CharacterId).firstOrNull()
            ?: return result   // 没有孙女，到此为止

        val gen3CharacterId = gen3Entity.daughterCharacterId
            ?: return result   // 注册尚未完成

        val gen3Config = try {
            gen3Entity.toDaughterCharacterData().toCharacterConfig(gen3CharacterId)
        } catch (e: Exception) {
            ZLog.w("DaughterCharacterRepo", "gen3 parse failed, skip", e)
            return result
        }
        result.add(gen3Config)

        return result
    }

    // ── B 类卡点修复：供 WorldSimulation 后台遍历使用 ─────────────

    /**
     * 返回所有已完成注册的女儿 characterId（1000+）列表。
     *
     * WorldSimulation 各 Tier 在循环开始前调用，把返回值追加到
     * DefaultCharacters.map { it.id } 后面，一起参与后台遍历。
     * 只包含 daughterCharacterId 已回填（注册完成）的行。
     */
    suspend fun getAllDaughterCharacterIds(): List<Int> =
        dao.getAllDaughterCharacterIds()

    // ── 第三代封顶判断（D3/D4 截断用，11.1 决策 5）──────────────

    /**
     * 判断 [characterId]（女儿自己的 ID，1000+）是否是「第三代」。
     *
     * 判断依据：反查 daughter_character 表里 daughterCharacterId == characterId
     * 的那一行，取出它的 motherCharacterId：
     * - motherCharacterId 在 1-9 → 母亲是原生角色 → [characterId] 是第二代 → false
     * - motherCharacterId ≥ 1000 → 母亲本身也是一位女儿 → [characterId] 是第三代 → true
     *
     * 用途：
     * - ChatViewModel.maybeTriggerDaughterGeneration()：第三代不调用 D4 生成器
     *   （家族传承固定三代封顶，不再延伸到第四代，见设计文档 11.1 决策 5）
     * - ChatViewModel 的 D3-② 发问决策：第三代不触发槎位问答
     *   （没有"第四代"概念，问了也不会被消费）
     *
     * 返回 false 的两种情况（都视为"不是第三代"，调用方据此放行 D3/D4）：
     * - 反查不到这一行（理论上不应发生在已注册的女儿身上，但防御性处理）
     * - 反查到了，motherCharacterId 在 1-9 范围内（正常的第二代）
     *
     * 注意：[characterId] 如果根本不是女儿（< 1000），本方法不做前置校验，
     * 调用方应自行保证只对 isDaughterMother() 判定为真的 1000+ ID 调用此方法。
     */
    suspend fun isThirdGeneration(characterId: Int): Boolean {
        val entity = dao.getByDaughterCharacterId(characterId) ?: return false
        return entity.motherCharacterId >= 1000
    }
}
