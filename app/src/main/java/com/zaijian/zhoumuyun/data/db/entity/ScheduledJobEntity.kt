package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Phase 29 · 本地任务调度实体
 *
 * 与 Supabase scheduled_jobs 表结构保持一致，
 * 供本地补跑（App 打开时补执行云端漏跑的任务）使用。
 */
@Entity(
    tableName = "scheduled_jobs",
    indices = [
        Index(value = ["enabled", "nextRunAt"]),
        Index(value = ["characterId"]),
        Index(value = ["cloudSynced"]),
        Index(value = ["characterId", "enabled", "nextRunAt"]),
    ]
)
data class ScheduledJobEntity(
    @PrimaryKey val id: String,
    val characterId: Int,
    val title: String,
    val toolName: String,
    val toolParamsJson: String = "{}",         // JSON 序列化的 Map<String, String>
    val enabled: Boolean = true,
    val repeatIntervalMs: Long?,        // null = 一次性任务
    val nextRunAt: Long,
    val lastRunAt: Long? = null,
    val executedBy: String = "local",   // 本地补跑时记录为 "local"
    val createdAt: Long,
    /** P1-32：云端同步标记（false = 待重试） */
    val cloudSynced: Boolean = true,
    /** P1-33：执行认领锁到期时间（null = 未锁定） */
    val lockedUntil: Long? = null,
    /**
     * 工单描述（日程系统批次1新增，模式 B 专用）。
     *
     * toolName == "agent_task" 时必填，到点后作为系统触发消息注入角色对话管线，
     * 走完整 LLM 推理（角色自己判断要不要调工具、要说什么），而不是像工具型
     * 任务那样直接 AgentToolRegistry.get(toolName).execute(params)。
     *
     * 也是 schedule_list / schedule_get 展示、schedule_update 编辑的目标字段。
     * 工具型任务（mode A，现状）此字段保持 NULL，不受影响。
     *
     * 详见《日程系统_AI创建查询编辑_实现方案_v2.md》第三节 3.1。
     */
    val description: String? = null,
    /**
     * 关联项目 ID（日程系统第七节新增，可选增强）。
     *
     * 指向 [com.zaijian.zhoumuyun.data.db.entity.ProjectEntity] 的主键，
     * 让一条日程挂载到某个具体项目上（如"每天定时 web_search 推送项目进度"，
     * 或"每周提醒复盘项目"）。null = 独立日程，不关联任何项目。
     *
     * 与 `description` / `toolName` 完全正交：工具型与工单型任务都可关联项目，
     * 关联后不影响执行链路（ScheduledJobWorker / AgentTaskJobExecutor 不读此字段），
     * 仅用于展示侧（schedule_list / schedule_get / UI 卡片附加"关联项目: xxx"）
     * 与创建/编辑侧的校验（ScheduleCreateTool / ScheduleUpdateTool 用
     * ProjectRepository.getById 校验 id 存在性，不存则报错，避免悬空引用）。
     *
     * 详见《日程系统_AI创建查询编辑_实现方案_v2.md》第七节。
     */
    val projectId: String? = null,
)
