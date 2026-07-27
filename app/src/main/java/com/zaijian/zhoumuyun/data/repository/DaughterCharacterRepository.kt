package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.dao.DaughterCharacterDao
import com.zaijian.zhoumuyun.util.ZLog
import com.zaijian.zhoumuyun.data.db.entity.DaughterCharacterEntity
import com.zaijian.zhoumuyun.data.model.DaughterCustomEnums
import com.zaijian.zhoumuyun.data.model.DaughterDataException
import com.zaijian.zhoumuyun.data.model.DaughterStateLayer
import com.zaijian.zhoumuyun.data.model.toCharacterConfig
import com.zaijian.zhoumuyun.data.model.toDaughterCharacterData
import androidx.room.withTransaction
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
// (Flow import removed — P2-10 dead code cleanup；角标 Flow 化改造第2步重新引入，
//  用途与 P2-10 清理的那次不同：这次是 observeCharacterConfig 等新增方法的正式依赖，
//  不是遗留死代码。)

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
    private val db: AppDatabase,
    private val dao: DaughterCharacterDao,
) {

    // ── 读取 ────────────────────────────────────────────────

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

    // ── 写入 ────────────────────────────────────────────────

    /**
     * D4 生成器写入完整女儿记录。
     * upsert 语义：首次生成插入，重新生成覆盖（generatedAt 更新）。
     */
    suspend fun saveDaughter(entity: DaughterCharacterEntity) {
        dao.upsert(entity)
    }

    /**
     * 方案 8-7：generate() 失败时回滚已保存的暂存数据。
     * 删除指定母亲 ID 的女儿行，允许下次重新生成。
     */
    suspend fun deleteByMother(motherCharacterId: Int) {
        dao.deleteByMother(motherCharacterId)
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

        // 审查报告问题14修复：原来先 dao.getByMother 读取校验、再 dao.updateStateLayer
        // 写入，两步之间无事务保护——两个并发调用可能同时通过校验后先后写入，
        // 后写入的覆盖先写入的（TOCTOU）。用 db.withTransaction 包裹整个
        // "读→校验→写"过程，Room 在同一 SQLite 事务内串行执行，消除竞态窗口。
        db.withTransaction {
            val existing = dao.getByMother(motherCharacterId)
                ?: throw DaughterDataException(
                    "updateStateLayer: motherCharacterId=$motherCharacterId 查不到女儿记录"
                )
            val customEnums = DaughterCustomEnums.fromJson(existing.customEnumsJson)

            // P3-35 修复：改用统一校验方法 customEnums.validateStateLayerKeys()
            customEnums.validateStateLayerKeys(newStateLayer)

            dao.updateStateLayer(motherCharacterId, stateLayerJson)
        }
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
     * [updateDaughterCharacterId] 的回滚方法，供 DaughterRegistrationHelper 注册流程
     * 在回填之后的步骤失败时撤销这次回填（见该文件 onIdentityRegister 的 step 回滚逻辑）。
     */
    suspend fun clearDaughterCharacterIdForRollback(motherCharacterId: Int, daughterCharacterId: Int) {
        dao.clearDaughterCharacterIdForRollback(motherCharacterId, daughterCharacterId)
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

    /**
     * 复核修复 #7/#13/#20：给定女儿自己的 characterId，返回完整的
     * [com.zaijian.zhoumuyun.data.model.DaughterCharacterData]（而非拼装后的
     * CharacterConfig），供 ChatViewModel 组装 Prompt 时使用。
     *
     * 用途：CharacterStateRepository.getState() 对女儿角色（ID>=1000）的持久化
     * fallback 只查 DefaultCharacters，永远查不到，会退化为空白 CharacterStateLayer()；
     * 且即便查到，母亲侧编译期枚举（MaskType 等）也无法承载女儿的运行时字符串枚举
     * （DaughterCustomEnums）。ChatViewModel 需要这份原始数据，一是补齐女儿状态的
     * 数值维度（talkativeness/intensity 等，见 DaughterStateLayer 字段），二是把
     * customEnums 单独传给 PromptOrchestrator.buildSystemPrompt() 的
     * daughterStateLayer/daughterCustomEnums 参数，在 State Layer 渲染时替换掉
     * 通用/母亲专属枚举翻译。
     *
     * 与 getCharacterConfig 共享同一条查询+解析路径，避免两处 dao 调用逻辑漂移；
     * 返回 null / 抛 DaughterDataException 的情况与 getCharacterConfig 一致。
     */
    suspend fun getCharacterData(
        daughterCharacterId: Int,
    ): com.zaijian.zhoumuyun.data.model.DaughterCharacterData? {
        val entity = dao.getByDaughterCharacterId(daughterCharacterId) ?: return null
        return entity.toDaughterCharacterData()
    }

    // ── 家族链查询（Step 4，FamilyListViewModel / PresenceViewModel 使用）──

    // P1-47 修复：getFamilyChain 返回类型从 List<CharacterConfig> 改为 List<DaughterChainEntry>，
    // 增加 gender 字段，供 FamilyScreen 代数标签使用，不再硬编码 "女儿"/"孙女"。
    data class DaughterChainEntry(
        val config: com.zaijian.zhoumuyun.data.model.CharacterConfig,
        val gender: String?,
    )

    /**
     * 查询以 [firstGenCharacterId] 为起点的完整后代链（最多两层，固定不递归）。
     *
     * 返回有序列表：[第二代 DaughterChainEntry, 第三代 DaughterChainEntry（如有）]
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
    ): List<DaughterChainEntry> {
        val result = mutableListOf<DaughterChainEntry>()

        // ── 第二代 ──────────────────────────────────────────────
        val gen2Entity = dao.getAllWithMotherId(firstGenCharacterId).firstOrNull()
            ?: return result   // 没有女儿，直接返回空列表

        val gen2CharacterId = gen2Entity.daughterCharacterId
            ?: return result   // 注册尚未完成，暂不纳入

        val gen2Config = try {
            gen2Entity.toDaughterCharacterData().toCharacterConfig(gen2CharacterId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w("DaughterCharacterRepo", "gen2 parse failed, skip", e)
            return result
        }
        result.add(DaughterChainEntry(gen2Config, gen2Entity.kinshipTerm))

        // ── 第三代 ──────────────────────────────────────────────
        val gen3Entity = dao.getAllWithMotherId(gen2CharacterId).firstOrNull()
            ?: return result   // 没有孙女，到此为止

        val gen3CharacterId = gen3Entity.daughterCharacterId
            ?: return result   // 注册尚未完成

        val gen3Config = try {
            gen3Entity.toDaughterCharacterData().toCharacterConfig(gen3CharacterId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w("DaughterCharacterRepo", "gen3 parse failed, skip", e)
            return result
        }
        result.add(DaughterChainEntry(gen3Config, gen3Entity.kinshipTerm))

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

    // ── 角标 Flow 化改造第2步：Flow 包装 ────────────────────────
    //
    // 背景：BriefingRepository.observeAttentionItems()（供 NotificationBadgeViewModel
    // 实时订阅）需要一份"全体角色（母亲+女儿）"的 Flow，而不是一次性 suspend 调用。
    // 这里补三个方法，分别对应 getAllDaughterCharacterIds() / getCharacterConfig()
    // 的 Flow 版本，以及两者拼起来的完整角色列表 Flow。
    //
    // 容错原则与 getCharacterConfig() 的既有原则刻意不同：getCharacterConfig()
    // 是"这一条消息"的隔离粒度，数据损坏时抛 DaughterDataException，宁可这条
    // 消息报错也不能让女儿带着残缺人格说话；但 observeCharacterConfig() 服务的
    // 是"批量生成全体角色简报/角标"场景，一位女儿数据损坏不该连累其余角色的
    // Flow 也一起终止（Flow 里任何一环抛异常，整条 combine 链会直接结束），
    // 因此这里选择吞掉 DaughterDataException、emit null，由上层过滤掉这一位，
    // 这与 BriefingRepository.generateBriefing() 现有的按角色 try-catch 跳过
    // 是同一条原则在 Flow 语境下的等价实现。

    /**
     * observeAllDaughterCharacterIds() 的 Repository 层直通包装，
     * 语义与 getAllDaughterCharacterIds() 完全一致，只是换成 Flow。
     */
    fun observeAllDaughterCharacterIds(): Flow<List<Int>> =
        dao.observeAllDaughterCharacterIds()

    /**
     * 监听单个女儿的 CharacterConfig。数据损坏（DaughterDataException）
     * 时不上抛，而是记录日志后 emit null，交由调用方过滤——这是本方法
     * 与 getCharacterConfig() 在容错策略上唯一的、也是刻意的差异，
     * 原因见本节顶部注释。
     */
    fun observeCharacterConfig(daughterCharacterId: Int): Flow<CharacterConfig?> =
        dao.observeByDaughterCharacterId(daughterCharacterId)
            .map { entity ->
                entity?.toDaughterCharacterData()?.toCharacterConfig(daughterCharacterId)
            }
            .catch { e ->
                // 批次3 3-4修复：原 .catch 只吞 DaughterDataException 发 null，
                // 其余异常（SQLiteException 等）一律 throw e 上抛。同链路的
                // observeLastMessageAt/observePregnancy 都是全量捕获发兜底值。
                // 一旦 DB 查询抛非 DaughterDataException，整条角标未读数 Flow
                // 会终止（卡死不再更新或崩溃）。改为全量捕获并对齐兜底行为，
                // 保留日志以便排查真正不可恢复的异常。
                ZLog.w(
                    "DaughterCharacterRepo",
                    "daughter characterId=$daughterCharacterId observeCharacterConfig failed, emit null as fallback",
                    e,
                )
                emit(null)
            }

    /**
     * 监听全体角色（9 位母亲 + 全部已注册女儿）的 CharacterConfig 列表。
     * 母亲是静态数据（DefaultCharacters），不参与 Flow 重新计算；女儿
     * 列表任一成员的注册状态或数据变化都会触发这里重新组装。
     *
     * 用 flatMapLatest 展开女儿 ID 列表：女儿 ID 列表变化（新增/完成注册）
     * 时，之前所有女儿的订阅会被取消重建——这是可以接受的，女儿列表
     * 变化本身不频繁，不是需要额外优化的热路径。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeAllCharacterConfigs(): Flow<List<CharacterConfig>> {
        val mothers = DefaultCharacters.filter { it.isUnlocked }
        return observeAllDaughterCharacterIds().flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(mothers)
            } else {
                combine(ids.map { observeCharacterConfig(it) }) { configs ->
                    mothers + configs.filterNotNull()
                }
            }
        }
    }

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

    // ── 女儿角色名批量解析（技术债清理：原 BriefingViewModel /
    //    NotificationViewModel 各自维护一份完全相同的实现，见
    //    CHANGES_S9_window01_notification_center.md 技术债第 1 条）──

    /**
     * 给定一批候选 ID（可能混杂母亲 ID 和女儿 ID），筛出其中 >= 1000 的
     * 女儿 ID 并批量查询名称，返回 "ID字符串 -> 名称" 映射。
     *
     * 单个女儿 ID 查询失败（数据损坏等）不影响其余 ID 的解析，失败项
     * 直接跳过、不进入返回的 Map（调用方展示层原本就是"查不到就显示裸
     * ID"的兜底逻辑，这里维持一致）。
     *
     * 调用方典型用法（BriefingViewModel / NotificationViewModel 一致）：
     * ```
     * val ids = attentionItems.flatMap { item ->
     *     when (item) {
     *         is BriefingAttentionItem.Tension -> listOf(item.fromId, item.toId)
     *         is BriefingAttentionItem.RelationWorsened -> listOf(item.fromId, item.toId)
     *         else -> emptyList()
     *     }
     * }
     * val nameMap = daughterCharacterRepo.resolveDaughterNames(ids)
     * ```
     *
     * @param candidateIds 字符串形式的候选 ID（通常来自 fromId/toId，本身就是字符串）
     * @param logTag 调用方日志 TAG，失败时用于 ZLog.w，便于区分是哪个页面触发的查询失败
     */
    suspend fun resolveDaughterNames(
        candidateIds: List<String>,
        logTag: String = "DaughterCharacterRepo",
    ): Map<String, String> {
        val daughterIds = candidateIds
            .mapNotNull { id -> id.toIntOrNull()?.takeIf { it >= 1000 } }
            .distinct()

        if (daughterIds.isEmpty()) return emptyMap()

        return daughterIds.mapNotNull { daughterId ->
            try {
                val config = getCharacterConfig(daughterId)
                config?.let { daughterId.toString() to it.name }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.w(logTag, "女儿角色($daughterId)名称查询失败", e)
                null
            }
        }.toMap()
    }
}

