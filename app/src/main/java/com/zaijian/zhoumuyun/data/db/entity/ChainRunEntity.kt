package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 灵活自动化编排 · 链条运行实例（新增表：chain_runs）
 *
 * 对应《灵活自动化编排·改造设计方案》§3.2，对应 Step Functions 的一次 Execution。
 * 一条 [ChainRunEntity] 是一条链条定义的一次运行：currentNodeIndex 记录当前推进到
 * 哪个节点，context（JSON）在节点间传递"事实"（如 Check 节点的检查结果），供后续
 * 节点的 ConditionEvaluator 读取。
 *
 * 字段已合并 §11 补充的四处修正：
 * - §11.2 [lockedUntil]：数据库级认领锁，配合 ChainRunDao.claimRun() 使用
 * - §11.6 [visitCount]/[maxNodeVisits]/[deadlineAt]：双重防护，防 Check 节点死循环
 * - §11.10 [isReported]：未播报机制，对齐 WorkflowJobEntity.isReported
 *
 * §11.12：[characterId] 允许取值 -1（项目级链条），已有索引天然覆盖。
 *
 * 建表 SQL 见 Migration72to73.kt（§13.2），String 主键写法对照 workflow_jobs 表，
 * Boolean 字段用 INTEGER 存储（对照 workflow_jobs.isReported）。
 */
@Entity(
    tableName = "chain_runs",
    indices = [
        Index(value = ["status"]),
        Index(value = ["characterId", "status"]),
        // §11.10 未播报查询的高频路径，对照 workflow_jobs 表 (isReported, status) 组合索引
        Index(value = ["isReported", "status"]),
    ],
)
data class ChainRunEntity(
    @PrimaryKey val id: String,
    val chainDefId: String,
    val characterId: Int,
    val status: String,                // WAITING | RUNNING | COMPLETED | FAILED | CANCELLED，见 [ChainRunStatus]
    val currentNodeIndex: Int = 0,
    val context: String = "{}",        // JSON，节点间传递的"事实"，供 ConditionEvaluator 读取
    val wakeAtMs: Long? = null,        // status=WAITING 且当前节点是 Wait 节点时，唤醒时间戳
    val visitCount: Int = 0,           // §11.6：节点推进总次数（区别于 currentNodeIndex）
    val maxNodeVisits: Int = 200,      // §11.6：推进次数上限，超过判 FAILED，防 Check 节点死循环
    val deadlineAt: Long,              // §11.6：链条总时长上限（建议默认远大于 WorkflowEngine 的10分钟，如7天）
    val lockedUntil: Long? = null,     // §11.2：数据库级认领锁，配合 ChainRunDao.claimRun() 使用
    val isReported: Boolean = false,   // §11.10：未播报机制，对齐 WorkflowJobEntity.isReported
    val startedAt: Long,
    val updatedAt: Long,
)

/** [ChainRunEntity.status] 的合法取值。 */
object ChainRunStatus {
    const val WAITING = "WAITING"
    const val RUNNING = "RUNNING"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
}
