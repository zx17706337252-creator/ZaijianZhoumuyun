package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Window C · Agent 自主技能系统 —— 技能实体。
 *
 * 设计来源：《再见公馆》Window C 技能系统设计方案 v1.2 §1.1 / §4。
 *
 * ## 与设计方案 v1.2 的差异（以源码为准，见交付变更说明）
 *
 * 1. `id` 类型由 `Long` 改为 **`String`**：本工程记忆主表 [MemoryEntity] 的主键即
 *    `@PrimaryKey val id: String`（`MemoryEntity.kt:53`），且 `MemoryWriteTool` 用
 *    `UUID.randomUUID().toString()` 生成（`AgentCoreTools.kt:193`）。技能与记忆同属
 *    "角色私有知识条目"，沿用同一套 String UUID 主键范式，避免在存储底座里混入两套
 *    主键策略。相应地 §1.1 中 `relatedSkillIds: List<Long>?` 落库为 `String?`（JSON
 *    数组文本），Room 原生不支持 List 列，且本工程未注册 List TypeConverter。
 *
 * 2. `status` / `sourceType` 以 **`String`** 列存储（存枚举 `name`），而非直接存枚举：
 *    与 [MemoryEntity] 存储 `domain`/`scope` 的范式一致（`MemoryEntity.kt` 中
 *    `domain`/`scope` 均为 String 列）。这样 §4.1 草案里 DAO 查询 `status = 'ACTIVE'`
 *    这类字符串字面量条件可直接成立，无需额外注册枚举 TypeConverter。枚举
 *    [SkillStatus] / [SkillSourceType] 仅在 Repository / 工具层作类型安全约束，落库前
 *    `.name()`。
 *
 * 3. §1.3 已拍板"纯角色私有"，故**不含** `scope` 字段——技能永远只属于 `characterId`。
 *
 * 表结构严格对照 [com.zaijian.zhoumuyun.data.db.migration.MIGRATION_68_69]，
 * 列名/类型/可空性/索引名逐一对应，保证 `validateMigration()` 通过。
 */
@Entity(
    tableName = "skills",
    indices = [
        Index(value = ["characterId"]),
        Index(value = ["characterId", "status"]),
    ],
)
data class SkillEntity(
    @PrimaryKey
    val id: String,
    val characterId: Int,
    val name: String,
    /** 一句话描述，≤40 字，约束同 `AgentTool.description`（`AgentTool.kt:96-101`）。 */
    val shortDescriptor: String,
    /** 完整内容：自然语言描述 + 适用场景 + 可选执行步骤，按需展开（skill_expand）时才读取。 */
    val fullContent: String,
    /** 轻量标签，仿 `MemoryDomain` 思路，不强制、不做规则引擎打标。 */
    val category: String? = null,
    /** [SkillStatus.name]，ACTIVE / DEPRECATED / DRAFT。不做物理删除，废弃是状态迁移。 */
    val status: String,
    /** [SkillSourceType.name]，AGENT_AUTONOMOUS / USER_MANUAL / DISTILLED。 */
    val sourceType: String,
    val version: Int = 1,
    val usageCount: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val lastUsedAt: Long? = null,
    /** 可选的技能间组合/依赖关系，存 JSON 数组文本（如 `["id-a","id-b"]`）；Room 不支持 List 列。 */
    val relatedSkillIds: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 技能状态。落库为 [SkillEntity.status] 字符串列（`name`）。
 * @see SkillEntity
 */
enum class SkillStatus { ACTIVE, DEPRECATED, DRAFT }

/**
 * 技能来源。落库为 [SkillEntity.sourceType] 字符串列（`name`）。
 * @see SkillEntity
 */
enum class SkillSourceType { AGENT_AUTONOMOUS, USER_MANUAL, DISTILLED }

/**
 * 技能变更日志（独立表，不是字段）。设计方案 v1.2 §1.2。
 *
 * 技能被编辑或废弃时写一条，保证"随时修改升级"的改动历史可查——不管是 Agent 自己改
 * 还是用户在面板手动改。`id` 同样用 String UUID，与 [SkillEntity] 主键范式一致。
 */
@Entity(
    tableName = "skill_edit_log",
    indices = [
        Index(value = ["skillId"]),
        Index(value = ["skillId", "timestamp"]),
    ],
)
data class SkillEditLogEntity(
    @PrimaryKey
    val id: String,
    val skillId: String,
    val changeSummary: String,
    /** "AGENT" | "USER" —— 谁改的。 */
    val actor: String,
    val reason: String? = null,
    val timestamp: Long,
)
