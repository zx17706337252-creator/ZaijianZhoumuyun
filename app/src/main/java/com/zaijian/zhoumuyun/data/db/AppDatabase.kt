package com.zaijian.zhoumuyun.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.zaijian.zhoumuyun.data.db.dao.AgentPlanDao
import com.zaijian.zhoumuyun.data.db.dao.AgentRelationDao
import com.zaijian.zhoumuyun.data.db.dao.CharacterGoalDao
import com.zaijian.zhoumuyun.data.db.dao.MenstrualCycleDao
import com.zaijian.zhoumuyun.data.db.dao.PregnancyAnswerDao
import com.zaijian.zhoumuyun.data.db.dao.PregnancyPendingQuestionDao
import com.zaijian.zhoumuyun.data.db.dao.CharacterStateDao
import com.zaijian.zhoumuyun.data.db.dao.EvaluationSessionDao
import com.zaijian.zhoumuyun.data.db.dao.CharacterIdentityDao
import com.zaijian.zhoumuyun.data.db.dao.JobResultDao
import com.zaijian.zhoumuyun.data.db.dao.LearningGoalDao
import com.zaijian.zhoumuyun.data.db.dao.MemoryCandidateDao
import com.zaijian.zhoumuyun.data.db.dao.MemoryDao
import com.zaijian.zhoumuyun.data.db.dao.MessageDao
import com.zaijian.zhoumuyun.data.db.dao.PregnancyDao
import com.zaijian.zhoumuyun.data.db.dao.ProjectDao
import com.zaijian.zhoumuyun.data.db.dao.ProjectKnowledgeDao
import com.zaijian.zhoumuyun.data.db.dao.RelationshipDao
import com.zaijian.zhoumuyun.data.db.dao.RelationshipMilestoneDao
import com.zaijian.zhoumuyun.data.db.dao.ScheduledJobDao
import com.zaijian.zhoumuyun.data.db.dao.TaskDao
import com.zaijian.zhoumuyun.data.db.dao.WorldEventDao
import com.zaijian.zhoumuyun.data.db.dao.WorkflowJobDao
import com.zaijian.zhoumuyun.data.db.dao.WorkflowStepResultDao
import com.zaijian.zhoumuyun.data.db.dao.AgentActivityDao                  // 心迹：Agent 过程可见层
import com.zaijian.zhoumuyun.data.db.dao.RoundtableMessageDao             // 待办7
import com.zaijian.zhoumuyun.data.db.dao.DaughterCharacterDao             // D4
import com.zaijian.zhoumuyun.data.db.dao.DaughterIdAllocatorDao          // D4
import com.zaijian.zhoumuyun.data.db.dao.EvolutionPlanDao                // P6
import com.zaijian.zhoumuyun.data.db.dao.PracticeRecordDao               // P6
import com.zaijian.zhoumuyun.data.db.dao.PracticeRecordArchiveDao        // P6
import com.zaijian.zhoumuyun.data.db.dao.StageDigestDao                  // P6
import com.zaijian.zhoumuyun.data.db.dao.SpecialtyProfileDao             // P6
import com.zaijian.zhoumuyun.data.db.dao.SystemSuggestionDao             // P6
import com.zaijian.zhoumuyun.data.db.dao.PromotedSkillTagDao             // 擅长领域标签墙
import com.zaijian.zhoumuyun.data.db.dao.NotificationReadStateDao        // 通知中心已读状态
import com.zaijian.zhoumuyun.data.db.dao.JudgeProfileDao                 // 裁判竞争
import com.zaijian.zhoumuyun.data.db.dao.CompetitionRoundDao             // 裁判竞争
import com.zaijian.zhoumuyun.data.db.dao.CompetitionEntryDao             // 裁判竞争
import com.zaijian.zhoumuyun.data.db.dao.CompetitionWeightConfigDao      // 裁判竞争
import com.zaijian.zhoumuyun.data.db.dao.JudgeAccuracyLogDao             // 裁判竞争
import com.zaijian.zhoumuyun.data.db.entity.AgentPlanEntity
import com.zaijian.zhoumuyun.data.db.entity.AgentRelationEntity
import com.zaijian.zhoumuyun.data.db.entity.BirthRecordEntity
import com.zaijian.zhoumuyun.data.db.entity.MenstrualCycleEntity
import com.zaijian.zhoumuyun.data.db.entity.PregnancyAnswerEntity
import com.zaijian.zhoumuyun.data.db.entity.PregnancyPendingQuestionEntity
import com.zaijian.zhoumuyun.data.db.entity.DaughterCharacterEntity         // D4
import com.zaijian.zhoumuyun.data.db.entity.DaughterIdAllocatorEntity      // D4
import com.zaijian.zhoumuyun.data.db.entity.CharacterGoalEntity
import com.zaijian.zhoumuyun.data.db.entity.CharacterStateEntity
import com.zaijian.zhoumuyun.data.db.entity.EvaluationSessionEntity
import com.zaijian.zhoumuyun.data.db.entity.CharacterIdentityEntity
import com.zaijian.zhoumuyun.data.db.entity.JobResultEntity
import com.zaijian.zhoumuyun.data.db.entity.LearningGoalEntity
import com.zaijian.zhoumuyun.data.db.entity.MemoryCandidateEntity
import com.zaijian.zhoumuyun.data.db.entity.MemoryEntity
import com.zaijian.zhoumuyun.data.db.entity.MemoryFtsEntity
import com.zaijian.zhoumuyun.data.db.entity.MessageEntity
import com.zaijian.zhoumuyun.data.db.entity.PregnancyEntity
import com.zaijian.zhoumuyun.data.db.entity.ProjectEntity
import com.zaijian.zhoumuyun.data.db.entity.ProjectKnowledgeEntity
import com.zaijian.zhoumuyun.data.db.entity.ProjectKnowledgeFtsEntity
import com.zaijian.zhoumuyun.data.db.entity.ProjectMemberEntity
import com.zaijian.zhoumuyun.data.db.entity.ProjectMilestoneEntity
import com.zaijian.zhoumuyun.data.db.entity.RelationshipEntity
import com.zaijian.zhoumuyun.data.db.entity.RelationshipMilestoneEntity
import com.zaijian.zhoumuyun.data.db.entity.ScheduledJobEntity
import com.zaijian.zhoumuyun.data.db.entity.TaskEntity
import com.zaijian.zhoumuyun.data.db.entity.WorldEventEntity
import com.zaijian.zhoumuyun.data.db.entity.WorkflowJobEntity
import com.zaijian.zhoumuyun.data.db.entity.WorkflowStepResultEntity
import com.zaijian.zhoumuyun.data.db.entity.AgentActivityEventEntity        // 心迹：Agent 过程可见层
import com.zaijian.zhoumuyun.data.db.entity.RoundtableMessageEntity          // 待办7
import com.zaijian.zhoumuyun.data.db.entity.EvolutionPlanEntity              // P6
import com.zaijian.zhoumuyun.data.db.entity.PracticeRecordEntity             // P6
import com.zaijian.zhoumuyun.data.db.entity.PracticeRecordArchiveEntity      // P6
import com.zaijian.zhoumuyun.data.db.entity.StageDigestEntity                // P6
import com.zaijian.zhoumuyun.data.db.entity.SpecialtyProfileEntity           // P6
import com.zaijian.zhoumuyun.data.db.entity.SystemSuggestionEntity           // P6
import com.zaijian.zhoumuyun.data.db.entity.PromotedSkillTagEntity           // 擅长领域标签墙
import com.zaijian.zhoumuyun.data.db.entity.NotificationReadStateEntity      // 通知中心已读状态
import com.zaijian.zhoumuyun.data.db.entity.JudgeProfileEntity               // 裁判竞争
import com.zaijian.zhoumuyun.data.db.entity.CompetitionRoundEntity           // 裁判竞争
import com.zaijian.zhoumuyun.data.db.entity.CompetitionEntryEntity           // 裁判竞争
import com.zaijian.zhoumuyun.data.db.entity.CompetitionWeightConfigEntity    // 裁判竞争
import com.zaijian.zhoumuyun.data.db.entity.JudgeAccuracyLogEntity           // 裁判竞争

/**
 * 再见周慕云 · Room 数据库
 *
 * 版本历史：
 *   v1（Phase 7）：messages / world_events / character_identity
 *   v2（Phase 8）：+ memories / memories_fts / memory_candidates
 *   v3（Phase 9）：+ relationship_states / character_goals /
 *                    projects / project_milestones / project_members
 *   v4（Phase 10）：+ project_knowledge / project_knowledge_fts
 *   v5（Phase 14）：relationship_states 新增 jealousy / tension / isInterCharacter 字段
 *   v6（Phase 18）：messages 新增 exportedFileJson 字段（file_export 工具附件元数据）
 *   v7（Phase 19）：+ tasks 表（Task Engine 持久化）
 *   v8（Phase 22）：+ agent_plans 表（AgentPlan 进化方案）
 *                    + learning_goals 表（学习目标，goal_update 工具支撑）
 *   （Phase 23 为 UI 层补全，DB 结构不变，版本维持 v8）
 *   v9（Phase 24）：+ evaluation_sessions 表（打分会话）
 *   v10（Phase 25）：memories 表新增 isLocked（规则锁定标志）和 goalId（规则目标关联）两字段
 *   v11（Phase 29）：+ scheduled_jobs 表（定时任务）+ job_results 表（执行结果）
 *   v12（Phase 31）：project_knowledge 表新增 charCount 列
 *   v13（Bugfix）：relationship_states 补 sourceEventId 列（修复 schema 不一致）
 *   v14（Phase CSL-4）：+ character_state 表（CharacterStateLayer 持久化，
 *                       见《再见公馆 CharacterStateLayer 完整方案 V3》Phase 4）
 *   v15-v17：（见上）NyxChat 附加字段 + character_state 表
 *   v19（V18 关系结构层借鉴）：character_identity 新增 relationAssumption / conflictStrategy
 *   v20（生理周期状态机）：+ menstrual_cycle 表（周期锚点持久化，九人各自偏移）
 *   v21（D1 女儿系统最小数据结构）：+ agent_relation 表（女儿关系阶段）
 *                                    + pregnancy_answers 表（孕期共设问答记录）
 *   v22（D2.5 排卵期失败概率系统）：pregnancy_state 删 cycle_days 列，
 *                                    新增 consecutive_fail_count / last_failure_injected_at
 *                                    开发阶段 fallbackToDestructiveMigration 清库重建亦可
 *   v23（D2.6 孕期体验与生命事件系统）：
 *                                    - pregnancy_state 新增 miscarried_at（流产时间戳）
 *                                    - memories 新增 is_eternal（永恒状态记忆标记）
 *   v24（D3 孕期共设系统）：
 *                                    - pregnancy_answers 新增 slot_index（槽位序号）
 *                                      + is_locked（槽位是否已锁定）两列
 *                                    - + pregnancy_pending_question 表（问答配对状态追踪，
 *                                      单行覆盖写，PK = motherCharacterId）
 *   v27（D4 触发点接入 Part 3）：character_identity 新增 name 列，
 *                                    统一存储预设角色与女儿角色的显示名，
 *                                    迁移时回填 1-9 号预设角色的硬编码名字
 *   v29（怀孕弹窗触发重构）：pregnancy_state 新增 fertileWindowConsentAsked
 *                                    （本排卵期窗口是否已弹过同意弹窗，离开排卵期后
 *                                    由调用方清回 false，供下次排卵期重新判定）
 *   v30（多步骤工作流系统 Step 1）：+ workflow_jobs 表（工作流任务主表）
 *                                    + workflow_step_results 表（单步执行记录）
 *   v33（P1-32/33 调度系统健壮性修复）：scheduled_jobs 新增 cloudSynced 列
 *                                    （本地写入成功但云端同步失败时置 false，
 *                                    App 启动时自动重试）+ lockedUntil 列
 *                                    （runLocalCompensation 与 ScheduledJobWorker
 *                                    执行前认领锁，防止同一任务被并发重复执行）
 *   v38（P6 专长进化系统）：+ evolution_plans（自我进化方案，带版本历史）
 *                                    + practice_records（每日修炼原始产出，蒸馏第1层）
 *                                    + practice_records_archive（蒸馏后冷存储，归档原文）
 *                                    + stage_digests（阶段摘要，蒸馏第2层）
 *                                    + specialty_profiles（专长档案本体含styleNotes，蒸馏第3层）
 *                                    + system_suggestions（AI自我提案，仅建议不自动生效）
 *                                    roundtable_messages 新增 exportedFileJson 列
 *                                    （圆桌消息首次获得文件卡片能力，对齐 MessageEntity
 *                                    既有同语义字段）
 *   v39（裁判与竞争机制）：+ judge_profiles（裁判档案，含评判标准说明书与候选修正池）
 *                                    + competition_rounds（竞赛轮次，含状态机流转）
 *                                    + competition_entries（参赛条目，含三方评分记录）
 *                                    + competition_weight_configs（项目级评分权重配置）
 *                                    + judge_accuracy_log（裁判排名与用户排名吻合度历史）
 *   v50（Fix-ThinkingLeak：输出格式约束修复）：messages 新增 thinkingText 列
 *                                    （从回复正文剥离出的内心推理/工具调用意图，
 *                                    对齐 exportedFileJson 既有"非空即代表附加内容"设计）
 *   v60（v1.36 问题2：内心独白/心理感受/台词 三层分离）：messages 新增 psychText 列
 *                                    （从回复正文中全角圆括号包裹的内容抽取出的
 *                                    心理感受/神态描写，与 thinkingText 语义不同——
 *                                    这是不折叠的戏内内容，详见 Migration59to60.kt）
 *   v52（擅长领域标签墙接通真实数据）：+ promoted_skill_tags 表——
 *                                    角色详情页"能力"Tab 的擅长领域标签墙此前
 *                                    读取的是硬编码占位符（getSkillTags 五个
 *                                    写死的词，与专长进化系统完全没有接通）。
 *                                    本次改造：IdentityPromotionEvaluator
 *                                    用户确认晋升时，调用
 *                                    SpecialtyEvolutionEngine.distillSkillTag()
 *                                    将完整特征描述浓缩成2-4字短标签写入本表，
 *                                    标签墙据此展示真实的、已晋升的角色能力，
 *                                    未晋升过的角色显示空状态而非假数据。
 */
@Database(
    entities = [
        MessageEntity::class,
        WorldEventEntity::class,
        CharacterIdentityEntity::class,
        MemoryEntity::class,
        MemoryFtsEntity::class,
        MemoryCandidateEntity::class,
        RelationshipEntity::class,
        CharacterGoalEntity::class,
        ProjectEntity::class,
        ProjectMilestoneEntity::class,
        ProjectMemberEntity::class,
        ProjectKnowledgeEntity::class,
        ProjectKnowledgeFtsEntity::class,
        TaskEntity::class,
        AgentPlanEntity::class,
        LearningGoalEntity::class,
        EvaluationSessionEntity::class,
        ScheduledJobEntity::class,
        JobResultEntity::class,
        CharacterStateEntity::class,
        RelationshipMilestoneEntity::class,
        PregnancyEntity::class,
        BirthRecordEntity::class,
        MenstrualCycleEntity::class,
        AgentRelationEntity::class,
        PregnancyAnswerEntity::class,
        PregnancyPendingQuestionEntity::class,
        DaughterCharacterEntity::class,       // D4 女儿人格存储
        DaughterIdAllocatorEntity::class,     // D4 女儿编号发号器
        WorkflowJobEntity::class,
        WorkflowStepResultEntity::class,
        AgentActivityEventEntity::class,     // 心迹：Agent 过程可见层（Window B 2.2.2）
        RoundtableMessageEntity::class,       // 待办7：圆桌消息持久化
        EvolutionPlanEntity::class,            // P6 专长进化系统
        PracticeRecordEntity::class,
        PracticeRecordArchiveEntity::class,
        StageDigestEntity::class,
        SpecialtyProfileEntity::class,
        SystemSuggestionEntity::class,
        JudgeProfileEntity::class,             // 裁判竞争机制
        CompetitionRoundEntity::class,
        CompetitionEntryEntity::class,
        CompetitionWeightConfigEntity::class,
        JudgeAccuracyLogEntity::class,
        PromotedSkillTagEntity::class,          // 擅长领域标签墙
        NotificationReadStateEntity::class,     // 通知中心已读状态
    ],
    version = 68,  // 67 → 68：Agent 过程可见层（「心迹」，Window B 2.2.2）——新增
    // agent_activity_events 表，承载 Agent 工具调用/降级链路/工作流镜像的过程痕迹，
    // 供「心迹」面板时间线呈现。纯新增表，不改动任何既有 schema，详见 Migration67to68.kt。
    // 66 → 67：表格直传方案 W1 数据模型——messages /
    // roundtable_messages 两表新增 tableDataJson 列（JSON 序列化 TablePayload），
    // 让 table_export 工具产出的表格数据不经过 LLM token 预算直接落库。
    // 仅两张表（practice_records 不动：修炼播报是独立文件下发流程，不产出表格）。
    // 详见 Migration66to67.kt。
    // 65 → 66：Agent附件下发方案 v2.0 · 1.7 P3——messages /
    // roundtable_messages / practice_records 三表新增 exportedFilesJson 列，
    // 支持单条消息挂载多个文件附件（此前 exportedFileJson 是单文件字段，
    // 一轮回复连续产出多个文件时后一次会覆盖前一次）。详见 Migration65to66.kt。
    // 64 → 65：修复 DailyPracticeWorker 补发路径丢失文件卡片——
    // practice_records 新增 exportedFileJson 列，详见 Migration64to65.kt。
    // 63 → 64：日程系统第七节——scheduled_jobs 新增 projectId 列（关联项目，可选增强），详见Migration63to64.kt。
    // 62 → 63：日程系统批次1——scheduled_jobs 新增 description 列（工单型任务专用），详见Migration62to63.kt。
    // 61 → 62：批次0修复——补建12张表31个历史遗留缺失索引，详见Migration61to62.kt。
    // 60 → 61：圆桌场景补齐三层分离（thinking/psych 标签解析），
    // roundtable_messages 新增 thinkingText/psychText 两列。详见 Migration60to61.kt。
    // 59 → 60：v1.36 问题2 修复——messages 新增 psychText 列，
    // 内心独白（thinkingText）/心理感受（psychText）/台词（content）三层分离。
    // 详见 Migration59to60.kt。
    // 58 → 59：v1.36 问题3 修复——character_identity 新增用户身份设定
    // 4 列（userGender/userRoleLabelPrivate/userRoleLabelPublic/publicPrivacyReason），
    // 修复角色统一用"她"称呼用户的问题。详见 Migration58to59.kt。
    // 57 → 58：新增 notification_read_state 表，见通知中心设计方案第三节。
    // 56 → 57：公馆/书架头像独立化——此前公馆与书架共用同一张
    // 原图（avatarUrl）和同一套裁剪参数（avatarCropTall*）。现在拆成三处
    // 完全独立：新增 avatarUrlTall（公馆专用原图）+ avatarUrlShelf/
    // avatarCropShelfOffsetX/avatarCropShelfOffsetY/avatarCropShelfScale
    // （书架专用原图+裁剪参数）。纯新增列，不涉及现有数据变更。
    // 55 → 56：W1 修复——competition_entries 新增 (roundId, characterId) UNIQUE 约束，
    // 配合 CompetitionRoundManager"先查后写"重构，防止多进程/极端并发下的重复参赛条目。
    // 54 → 55：W1-002 修复——practice_records 新增 roundtablePosted 列，
    // 配合 DailyPracticeWorker 的补偿播报逻辑，解决"落库成功但圆桌播报未完成
    // 就被杀进程"导致播报永久丢失的问题。
    // 53 → 54：W1 数据库与迁移完整性审查修复——memories 表重建去除 decayFactor 僵尸列并补全 13 个索引（含此前从未创建的 6 个）+ character_goals.relatedProjectId 补建索引
    // 52 → 53：批次1 数据层修复——ProjectMemberEntity.characterId 类型纠正（String→Int，与SQL列INTEGER一致）+ 8表新增索引（projects/tasks/memories/messages/roundtable_messages/competition_rounds/workflow_jobs/scheduled_jobs）
    // 50 → 51：P2-5 修复——practice_records 新增 (specialtyId, digestStatus, createdAt) 复合索引
    // 49 → 50：Fix-ThinkingLeak 修复——messages 表新增 thinkingText 列，
    // 承接从回复正文剥离出的内心推理/工具调用意图，气泡下方"想法"卡片据此渲染。
    // 纯新增可空列，不涉及任何现有表结构或数据变更。
    // 48 → 49：P1-47 修复——daughter_character 表新增 gender 列，
    // 用于 FamilyScreen 代数标签展示，替代硬编码 "女儿"/"孙女"。
    // 纯新增索引，不涉及任何表结构或数据变更。
    // 46 → 47：清理 30 个历史遗留索引（改名式冗余 + 无替代覆盖的孤儿索引），
    // 不涉及任何表结构或数据变更。
    // 45 → 46：头像存储重新设计——avatarUrl 改存原图路径，
    // 新增 avatarCropCircle*/avatarCropTall* 两套裁剪参数，同一张原图
    // 分别适配详情页圆形和公馆拱形/书架椭圆两种展示（后者共用一套参数）。
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun worldEventDao(): WorldEventDao
    abstract fun characterIdentityDao(): CharacterIdentityDao
    abstract fun memoryDao(): MemoryDao
    abstract fun memoryCandidateDao(): MemoryCandidateDao
    abstract fun relationshipDao(): RelationshipDao
    abstract fun characterGoalDao(): CharacterGoalDao
    abstract fun projectDao(): ProjectDao
    abstract fun projectKnowledgeDao(): ProjectKnowledgeDao
    abstract fun taskDao(): TaskDao
    abstract fun agentPlanDao(): AgentPlanDao
    abstract fun learningGoalDao(): LearningGoalDao
    abstract fun evaluationSessionDao(): EvaluationSessionDao
    abstract fun scheduledJobDao(): ScheduledJobDao
    abstract fun jobResultDao(): JobResultDao
    abstract fun characterStateDao(): CharacterStateDao
    abstract fun relationshipMilestoneDao(): RelationshipMilestoneDao
    abstract fun pregnancyDao(): PregnancyDao
    abstract fun menstrualCycleDao(): MenstrualCycleDao
    abstract fun agentRelationDao(): AgentRelationDao
    abstract fun pregnancyAnswerDao(): PregnancyAnswerDao
    abstract fun pregnancyPendingQuestionDao(): PregnancyPendingQuestionDao
    abstract fun daughterCharacterDao(): DaughterCharacterDao   // D4
    abstract fun daughterIdAllocatorDao(): DaughterIdAllocatorDao   // D4 触发点接入 Part 2
    abstract fun roundtableMessageDao(): RoundtableMessageDao       // 待办7：圆桌消息持久化
    abstract fun workflowJobDao(): WorkflowJobDao
    abstract fun workflowStepResultDao(): WorkflowStepResultDao
    abstract fun agentActivityDao(): AgentActivityDao        // 心迹：Agent 过程可见层
    abstract fun evolutionPlanDao(): EvolutionPlanDao               // P6 专长进化系统
    abstract fun practiceRecordDao(): PracticeRecordDao
    abstract fun practiceRecordArchiveDao(): PracticeRecordArchiveDao
    abstract fun stageDigestDao(): StageDigestDao
    abstract fun specialtyProfileDao(): SpecialtyProfileDao
    abstract fun systemSuggestionDao(): SystemSuggestionDao
    abstract fun judgeProfileDao(): JudgeProfileDao                  // 裁判竞争机制
    abstract fun competitionRoundDao(): CompetitionRoundDao
    abstract fun competitionEntryDao(): CompetitionEntryDao
    abstract fun competitionWeightConfigDao(): CompetitionWeightConfigDao
    abstract fun judgeAccuracyLogDao(): JudgeAccuracyLogDao
    abstract fun promotedSkillTagDao(): PromotedSkillTagDao        // 擅长领域标签墙
    abstract fun notificationReadStateDao(): NotificationReadStateDao  // 通知中心已读状态

    /**
     * S2 修复：原子记录工作流步骤（插入步骤结果 + 推进 currentStep 在同一事务内）。
     *
     * WorkflowRepository.recordStep() 原本分两步调用两个不同 DAO，
     * 中间如果进程被杀，会出现步骤结果已写入但 currentStep 未更新的不一致状态，
     * 导致 Worker 续跑时重复执行同一步（断点续跑承诺失效）。
     *
     * 修复方案：在 AppDatabase 层（abstract class，可写方法体）
     * 用 @Transaction 包裹两步操作，保证原子性。
     */
    @Transaction
    open suspend fun recordStepAtomic(
        stepResult: WorkflowStepResultEntity,
        jobId: String,
        nextStepIndex: Int,
    ) {
        workflowStepResultDao().insert(stepResult)
        workflowJobDao().updateProgress(jobId, nextStepIndex)
    }

    companion object {
        private const val DB_NAME = "zaijian_world.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                )
                    .addMigrations(
                        *com.zaijian.zhoumuyun.data.db.migration.MIGRATIONS_1_10,
                        *com.zaijian.zhoumuyun.data.db.migration.MIGRATIONS_11_20,
                        *com.zaijian.zhoumuyun.data.db.migration.MIGRATIONS_21_30,
                        *com.zaijian.zhoumuyun.data.db.migration.MIGRATIONS_31_40,
                        *com.zaijian.zhoumuyun.data.db.migration.MIGRATIONS_41_48,
                        com.zaijian.zhoumuyun.data.db.migration.MIGRATION_48_49,
                        com.zaijian.zhoumuyun.data.db.migration.MIGRATION_49_50,
                        com.zaijian.zhoumuyun.data.db.migration.MIGRATION_50_51,
                        com.zaijian.zhoumuyun.data.db.migration.MIGRATION_51_52,
                        com.zaijian.zhoumuyun.data.db.migration.MIGRATION_52_53,
                        com.zaijian.zhoumuyun.data.db.migration.MIGRATION_53_54,
                        com.zaijian.zhoumuyun.data.db.migration.MIGRATION_54_55,
                        com.zaijian.zhoumuyun.data.db.migration.MIGRATION_55_56,
                        com.zaijian.zhoumuyun.data.db.migration.MIGRATION_56_57,
                        com.zaijian.zhoumuyun.data.db.migration.MIGRATION_57_58,
                        com.zaijian.zhoumuyun.data.db.migration.MIGRATION_58_59,
                        com.zaijian.zhoumuyun.data.db.migration.MIGRATION_59_60,
                        com.zaijian.zhoumuyun.data.db.migration.MIGRATION_60_61,
                        com.zaijian.zhoumuyun.data.db.migration.MIGRATION_61_62,
                        com.zaijian.zhoumuyun.data.db.migration.MIGRATION_62_63,
                        com.zaijian.zhoumuyun.data.db.migration.MIGRATION_63_64,
                        com.zaijian.zhoumuyun.data.db.migration.MIGRATION_64_65,
                        com.zaijian.zhoumuyun.data.db.migration.MIGRATION_65_66,
                        com.zaijian.zhoumuyun.data.db.migration.MIGRATION_66_67,
                        com.zaijian.zhoumuyun.data.db.migration.MIGRATION_67_68,
                    )
                    .fallbackToDestructiveMigrationOnDowngrade()
                    // P1-11 修复：原先仅有 fallbackToDestructiveMigrationOnDowngrade()
                    // （只兜底"版本号变小"这一种场景）。若本地库版本号落在上面
                    // 46 条迁移链覆盖范围之外（脏数据、库文件损坏、或未来某次
                    // 漏加 migration），Room 会在 build() 首次访问时抛
                    // IllegalStateException，且这是 onCreate() 主线程同步调用，
                    // 会导致应用直接崩溃且无法启动，用户唯一自救手段是手动清数据。
                    // 追加 fallbackToDestructiveMigration()：仅在"迁移路径缺失/
                    // 执行异常"时触发清库重建兜底，不影响正常情况下 46 条迁移链
                    // 的照常执行，不会掩盖真实的 migration bug。
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }

    }
}
