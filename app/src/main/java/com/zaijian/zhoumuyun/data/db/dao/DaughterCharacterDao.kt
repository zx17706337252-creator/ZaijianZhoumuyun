package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.zaijian.zhoumuyun.data.db.entity.DaughterCharacterEntity
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────────────────────────
//  DaughterCharacterDao（D4，v24→v25；反查方法 D4 触发点接入 Part 4，v27→v28）
//
//  六个操作：
//    observeByMother  — 监听单个母亲的女儿记录（Flow，不存在返回 null）
//    getByMother      — 一次性读取（挂起函数，供 Prompt 组装时使用）
//    upsert           — D4 生成器写入 / 覆盖完整记录
//    updateStateLayer — 运行时只更新 StateLayer 列，不碰 identity 和枚举
//    getByDaughterCharacterId  — 用女儿自己的 ID 反查（ChatViewModel 取
//                                CharacterConfig 时用，Part 4 新增）
//    updateDaughterCharacterId — 注册成功后回填女儿自己的 ID（Part 4 新增）
//
//  不提供 delete，女儿记录跟随孕期生命周期，
//  清理逻辑由上层（PregnancyRepository 或重置流程）负责。
// ─────────────────────────────────────────────────────────────

@Dao
interface DaughterCharacterDao {

    /**
     * 监听指定母亲的女儿记录。
     * D4 生成完成前发射 null；生成后发射完整记录。
     * UI 层用此判断"女儿是否已生成"。
     */
    @Query("SELECT * FROM daughter_character WHERE motherCharacterId = :motherCharacterId")
    fun observeByMother(motherCharacterId: Int): Flow<DaughterCharacterEntity?>

    /**
     * 一次性读取（挂起）。
     * PromptOrchestrator 在组装女儿注入层时使用。
     * 返回 null 时调用方必须拒绝进入对话，不允许静默降级。
     */
    @Query("SELECT * FROM daughter_character WHERE motherCharacterId = :motherCharacterId")
    suspend fun getByMother(motherCharacterId: Int): DaughterCharacterEntity?

    /**
     * 写入或覆盖完整女儿记录（INSERT OR REPLACE）。
     * D4 生成器完成后调用，包含所有三列 JSON + name + generatedAt。
     */
    @Upsert
    suspend fun upsert(entity: DaughterCharacterEntity)

    /**
     * 只更新 StateLayer 列（DAO 原始写入，不做任何校验）。
     * 运行时情绪引擎每次状态变更调用此方法，
     * 避免重写 identityJson 和 customEnumsJson（性能 + 安全）。
     *
     * 注意：这里直接接收裸 JSON 字符串写库，不校验 maskKey/
     * primaryEmotionKey/currentNeedKey/currentFearKey 是否非空、
     * 是否在这个女儿的 customEnums 中真实存在——这层校验在
     * [com.zaijian.zhoumuyun.data.repository.DaughterCharacterRepository.updateStateLayer]
     * 里做（需要先查出本行 customEnumsJson 做跨对象比对，DAO 层
     * 拿不到这个上下文）。调用方必须经由 Repository 方法调用，
     * 不要绕过 Repository 直接调用本方法，否则会重新打开写入坏
     * key、绕过校验的口子（与 D4 生成器 parseAndValidate() 堵住的
     * 是同一类问题）。
     */
    @Query(
        "UPDATE daughter_character SET stateLayerJson = :stateLayerJson " +
        "WHERE motherCharacterId = :motherCharacterId"
    )
    suspend fun updateStateLayer(motherCharacterId: Int, stateLayerJson: String)

    // ── D4 触发点接入 Part 4：女儿自己 ID 反查 ──────────────────

    /**
     * 用女儿自己的 characterId（1000+，DaughterIdAllocator 分配）反查整行记录。
     *
     * ChatViewModel.sendMessage() 组装 CharacterConfig 时使用：
     * currentCharacterId 是女儿自己的 ID，不是 motherCharacterId，
     * 主键查不到，必须靠这一列单独建索引查询。
     *
     * 返回 null 的两种情况：
     *   1. 传入的 ID 根本不是女儿（不在 1000+ 范围，调用方应先排除）
     *   2. 女儿确实存在，但 onIdentityRegister 回填失败/还没执行完
     *      （理论上不应长期出现，出现了说明注册流程中断，需要排查日志）
     */
    @Query("SELECT * FROM daughter_character WHERE daughterCharacterId = :daughterCharacterId")
    suspend fun getByDaughterCharacterId(daughterCharacterId: Int): DaughterCharacterEntity?

    /**
     * 角标 Flow 化改造第2步：与 getByDaughterCharacterId 同一条 SQL 的
     * Flow 版本，供 DaughterCharacterRepository.observeCharacterConfig()
     * 订阅使用。注意这里只负责发射原始 Entity，JSON 解析、
     * DaughterDataException 的吞掉逻辑都在 Repository 层做，
     * DAO 层不做业务语义判断（与全文件其余方法的分层原则一致）。
     */
    @Query("SELECT * FROM daughter_character WHERE daughterCharacterId = :daughterCharacterId")
    fun observeByDaughterCharacterId(daughterCharacterId: Int): Flow<DaughterCharacterEntity?>

    /**
     * 注册阶段回填：把 DaughterIdAllocator 刚分配的新号写回这一行。
     * 只能在 identityDao.upsert() 成功之后调用——
     * 角色资料表（character_identity）是"对话能不能查到女儿"的唯一权威来源，
     * 这一步只是让 daughter_character 表也知道这个号，供反查使用，
     * 失败不影响已经成功的角色注册，但会导致反查失效（需要日志报警）。
     */
    @Query(
        "UPDATE daughter_character SET daughterCharacterId = :daughterCharacterId " +
        "WHERE motherCharacterId = :motherCharacterId"
    )
    suspend fun updateDaughterCharacterId(motherCharacterId: Int, daughterCharacterId: Int)

    /**
     * updateDaughterCharacterId 的回滚方法：注册流程在回填之后的某一步失败时，
     * 把这一行的 daughterCharacterId 撤回 NULL（未注册状态）。
     *
     * WHERE 里同时带 motherCharacterId 和 daughterCharacterId 两个条件，是为了只在
     * 当前值确实等于本次分配的 allocatedId 时才清空——防止和其他并发注册撞车时
     * 清掉了不属于本次回滚的行。
     */
    @Query(
        "UPDATE daughter_character SET daughterCharacterId = NULL " +
        "WHERE motherCharacterId = :motherCharacterId AND daughterCharacterId = :daughterCharacterId"
    )
    suspend fun clearDaughterCharacterIdForRollback(motherCharacterId: Int, daughterCharacterId: Int)

    // ── B 类卡点修复：后台遍历扩展 ──────────────────────────────

    /**
     * 返回所有已完成注册的女儿 characterId（1000+）列表。
     *
     * 用途：WorldSimulation 各 Tier 循环开始前，把女儿 ID 追加到
     * DefaultCharacters.map { it.id } 后面，一起参与后台遍历。
     *
     * 只返回 daughterCharacterId 不为 null 的行——null 意味着注册回调
     * 尚未完成，这类"半生成"女儿不参与后台模拟，等回填成功后下一轮再带入。
     */
    @Query("SELECT daughterCharacterId FROM daughter_character WHERE daughterCharacterId IS NOT NULL")
    suspend fun getAllDaughterCharacterIds(): List<Int>

    /**
     * 角标 Flow 化改造第2步：与 getAllDaughterCharacterIds 同一条 SQL 的
     * Flow 版本，供 DaughterCharacterRepository.observeAllCharacterConfigs()
     * 订阅使用。任意一位女儿完成注册回填（daughterCharacterId 由 null
     * 变为非 null）都会触发这里重新查询。
     */
    @Query("SELECT daughterCharacterId FROM daughter_character WHERE daughterCharacterId IS NOT NULL")
    fun observeAllDaughterCharacterIds(): Flow<List<Int>>

    // ── 家族链查询（Step 4，供 Repository.getFamilyChain 使用）──

    /**
     * 查询以指定 characterId 为母亲的直接后代行（最多 1 条，upsert 覆盖语义）。
     *
     * getFamilyChain 用此方法查"第二代"：传入第一代母亲 ID（1-9），
     * 得到第二代整行（从中取 daughterCharacterId 继续查第三代）。
     * 再用第二代的 daughterCharacterId 作为 motherCharacterId 调用一次，
     * 得到第三代。固定两层，不递归。
     */
    @Query("SELECT * FROM daughter_character WHERE motherCharacterId = :motherCharacterId")
    suspend fun getAllWithMotherId(motherCharacterId: Int): List<DaughterCharacterEntity>

    // ── 方案 8-7：generate() 失败回滚 ────────────────────────────

    /**
     * 删除指定母亲 ID 的女儿暂存记录。
     *
     * 用途：generate() 中 saveDaughter() 成功后 onIdentityRegister() 失败时，
     * 回滚已写入的暂存数据，避免 getByMother() 查到半成品行阻止重试。
     */
    @Query("DELETE FROM daughter_character WHERE motherCharacterId = :motherCharacterId")
    suspend fun deleteByMother(motherCharacterId: Int)
}
