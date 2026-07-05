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
import com.zaijian.zhoumuyun.data.db.dao.RoundtableMessageDao             // 待办7
import com.zaijian.zhoumuyun.data.db.dao.DaughterCharacterDao             // D4
import com.zaijian.zhoumuyun.data.db.dao.DaughterIdAllocatorDao          // D4
import com.zaijian.zhoumuyun.data.db.dao.EvolutionPlanDao                // P6
import com.zaijian.zhoumuyun.data.db.dao.PracticeRecordDao               // P6
import com.zaijian.zhoumuyun.data.db.dao.PracticeRecordArchiveDao        // P6
import com.zaijian.zhoumuyun.data.db.dao.StageDigestDao                  // P6
import com.zaijian.zhoumuyun.data.db.dao.SpecialtyProfileDao             // P6
import com.zaijian.zhoumuyun.data.db.dao.SystemSuggestionDao             // P6
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
import com.zaijian.zhoumuyun.data.db.entity.RoundtableMessageEntity          // 待办7
import com.zaijian.zhoumuyun.data.db.entity.EvolutionPlanEntity              // P6
import com.zaijian.zhoumuyun.data.db.entity.PracticeRecordEntity             // P6
import com.zaijian.zhoumuyun.data.db.entity.PracticeRecordArchiveEntity      // P6
import com.zaijian.zhoumuyun.data.db.entity.StageDigestEntity                // P6
import com.zaijian.zhoumuyun.data.db.entity.SpecialtyProfileEntity           // P6
import com.zaijian.zhoumuyun.data.db.entity.SystemSuggestionEntity           // P6
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
    ],
    version = 46,  // 45 → 46：头像存储重新设计——avatarUrl 改存原图路径，
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
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,  // 附加 NyxChat A.1/A.2
                        MIGRATION_16_17,  // + character_state 表
                        MIGRATION_17_18,  // P3+P4.0：+ relationship_milestones / pregnancy_state / birth_records
                        MIGRATION_18_19,  // + relationAssumption / conflictStrategy（V18 关系结构层借鉴）
                        MIGRATION_19_20,  // + menstrual_cycle 表（生理周期状态机）
                        MIGRATION_20_21,  // + agent_relation 表 + pregnancy_answers 表（D1）
                        MIGRATION_21_22,  // pregnancy_state D2.5：删 cycle_days，加两列
                        MIGRATION_22_23,  // pregnancy_state + miscarried_at；memories + is_eternal（D2.6）
                        MIGRATION_23_24,  // pregnancy_answers + slotIndex/isLocked；+ pregnancy_pending_question 表（D3）
                        MIGRATION_24_25,  // daughter_character 表（D4 女儿人格系统）
                        MIGRATION_25_26,  // daughter_id_allocator 表（D4 触发点接入 Part 2）
                        MIGRATION_26_27,  // character_identity + name 列（D4 触发点接入 Part 3）
                        MIGRATION_27_28,  // daughter_character + daughterCharacterId 列（D4 触发点接入 Part 4）
                        MIGRATION_28_29,  // pregnancy_state + fertileWindowConsentAsked 列（怀孕弹窗触发重构）
                        MIGRATION_29_30,  // + workflow_jobs / workflow_step_results 表（多步骤工作流系统 Step 1）
                        MIGRATION_30_31,  // daughter_character + generatorVersion 列（D4 生成版本回溯）
                        MIGRATION_31_32,  // character_identity + avatarUrl 列（头像本地路径）
                        MIGRATION_32_33,  // scheduled_jobs + cloudSynced/lockedUntil 列（同步重试 + 执行去重，P1-32/33）
                        MIGRATION_33_34,  // + roundtable_messages 表（圆桌消息持久化，待办7）
                        MIGRATION_34_35,  // memories + memory_candidates 加 scope/roundtableId 列（待办3：群记忆）
                        MIGRATION_35_36,  // roundtable_messages 重建：characterId→speakerId/speakerName 等富字段（P5 整合）
                        MIGRATION_36_37,  // character_identity 加 soulNote/narrativeMemory/userImpression 等 8 列（Soul/Memory/User）
                        MIGRATION_37_38,  // + P6 专长进化系统 6 张新表 + roundtable_messages 加 exportedFileJson 列
                        MIGRATION_38_39,  // + 裁判与竞争机制 5 张新表
                        MIGRATION_39_40,  // judge_profiles (characterId, domain) 升级唯一索引 + 历史脏数据去重（P1-5 修复）
                        MIGRATION_40_41,  // memories + ftsRowId 列（S1 FTS JOIN 修复）
                        MIGRATION_41_42,  // projects + goalId 列（B5 三层结构关联字段）
                        MIGRATION_42_43,  // FTS 迁移修复：重建 memories_fts 为外部内容表，消除 content 反查错配（P1-1-3）
                        MIGRATION_43_44,  // P1-6-1/6-9：evolution_plans 和 pregnancy_answers 补唯一索引
                        MIGRATION_44_45,  // P-1/P-3 修复：memories_fts 恢复普通 FTS4 表，与 Entity 对齐；rowid 回填存量 ftsRowId
                        MIGRATION_45_46,  // 头像存储重新设计：avatarUrl 改存原图，新增圆形/竖长矩形两套裁剪参数
                    )
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { INSTANCE = it }
            }
        }

        // ── Migration v1 → v2 ─────────────────────────────────
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // memories
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `memories` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `characterId` INTEGER NOT NULL,
                        `domain` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `importance` INTEGER NOT NULL DEFAULT 3,
                        `keywords` TEXT NOT NULL DEFAULT '',
                        `isCore` INTEGER NOT NULL DEFAULT 0,
                        `projectId` TEXT,
                        `sourceEventId` TEXT,
                        `accessCount` INTEGER NOT NULL DEFAULT 0,
                        `lastAccessedAt` INTEGER NOT NULL DEFAULT 0,
                        `decayFactor` REAL NOT NULL DEFAULT 1.0,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_characterId` ON `memories` (`characterId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_domain` ON `memories` (`domain`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_importance` ON `memories` (`importance`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_isCore` ON `memories` (`isCore`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_updatedAt` ON `memories` (`updatedAt`)")

                // memories_fts（独立 FTS4 表，非外部内容表，由 MemoryDao.insertWithFts 手动同步）
                db.execSQL("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS `memories_fts`
                    USING fts4(`content`, `keywords`, tokenize=unicode61)
                """.trimIndent())

                // memory_candidates
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `memory_candidates` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `characterId` INTEGER NOT NULL,
                        `sourceEventId` TEXT,
                        `content` TEXT NOT NULL,
                        `score` INTEGER NOT NULL DEFAULT 3,
                        `domain` TEXT NOT NULL,
                        `projectId` TEXT,
                        `isProcessed` INTEGER NOT NULL DEFAULT 0,
                        `resultMemoryId` TEXT,
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_candidates_characterId` ON `memory_candidates` (`characterId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_candidates_score` ON `memory_candidates` (`score`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_candidates_sourceEventId` ON `memory_candidates` (`sourceEventId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_candidates_isProcessed` ON `memory_candidates` (`isProcessed`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_candidates_createdAt` ON `memory_candidates` (`createdAt`)")
            }
        }

        // ── Migration v2 → v3 ─────────────────────────────────
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // relationship_states
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `relationship_states` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `fromId` TEXT NOT NULL,
                        `toId` TEXT NOT NULL,
                        `trust` INTEGER NOT NULL DEFAULT 50,
                        `respect` INTEGER NOT NULL DEFAULT 50,
                        `affection` INTEGER NOT NULL DEFAULT 50,
                        `curiosity` INTEGER NOT NULL DEFAULT 50,
                        `dependence` INTEGER NOT NULL DEFAULT 20,
                        `conflict` INTEGER NOT NULL DEFAULT 10,
                        `stage` TEXT NOT NULL DEFAULT 'STRANGER',
                        `updatedAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_relationship_fromId` ON `relationship_states` (`fromId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_relationship_toId` ON `relationship_states` (`toId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_relationship_from_to` ON `relationship_states` (`fromId`, `toId`)")

                // character_goals
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `character_goals` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `characterId` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `description` TEXT NOT NULL DEFAULT '',
                        `priority` INTEGER NOT NULL DEFAULT 3,
                        `timeHorizon` TEXT NOT NULL DEFAULT 'MID_TERM',
                        `progress` REAL NOT NULL DEFAULT 0.0,
                        `isActive` INTEGER NOT NULL DEFAULT 1,
                        `relatedProjectId` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_goals_characterId` ON `character_goals` (`characterId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_goals_isActive` ON `character_goals` (`isActive`)")

                // projects
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `projects` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `title` TEXT NOT NULL,
                        `description` TEXT NOT NULL DEFAULT '',
                        `status` TEXT NOT NULL DEFAULT 'ACTIVE',
                        `ownerId` TEXT NOT NULL DEFAULT 'user',
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `archivedAt` INTEGER
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_projects_status` ON `projects` (`status`)")

                // project_milestones
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `project_milestones` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `projectId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `description` TEXT NOT NULL DEFAULT '',
                        `isCompleted` INTEGER NOT NULL DEFAULT 0,
                        `completedAt` INTEGER,
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_milestones_projectId` ON `project_milestones` (`projectId`)")

                // project_members
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `project_members` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `projectId` TEXT NOT NULL,
                        `characterId` INTEGER NOT NULL,
                        `role` TEXT NOT NULL DEFAULT 'CONTRIBUTOR',
                        `joinedAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_members_projectId` ON `project_members` (`projectId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_members_characterId` ON `project_members` (`characterId`)")
            }
        }

        // ── Migration v3 → v4 ─────────────────────────────────
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `project_knowledge` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `projectId` TEXT NOT NULL,
                        `characterId` TEXT,
                        `title` TEXT NOT NULL DEFAULT '',
                        `content` TEXT NOT NULL,
                        `source` TEXT NOT NULL DEFAULT 'MANUAL',
                        `importance` INTEGER NOT NULL DEFAULT 3,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_projectId` ON `project_knowledge` (`projectId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_characterId` ON `project_knowledge` (`characterId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_createdAt` ON `project_knowledge` (`createdAt`)")

                db.execSQL("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS `project_knowledge_fts`
                    USING fts4(
                        content=`project_knowledge`,
                        `title`,
                        `content`,
                        tokenize=unicode61
                    )
                """.trimIndent())
                // ⚠️ 手工 trigger 已移除：project_knowledge_fts 使用 @Fts4(contentEntity = ProjectKnowledgeEntity::class)
                // Room 会自动管理 content= 触发器，手工 trigger 与之重复会导致 FTS 双写。
            }
        }

        // ── Migration v4 → v5 ─────────────────────────────────
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `relationship_states` ADD COLUMN `jealousy` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `relationship_states` ADD COLUMN `tension` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `relationship_states` ADD COLUMN `isInterCharacter` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_relationship_isInterCharacter` ON `relationship_states` (`isInterCharacter`)")
            }
        }

        // ── Migration v5 → v6 ─────────────────────────────────
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `exportedFileJson` TEXT")
            }
        }

        // ── Migration v6 → v7 ─────────────────────────────────
        // Phase 19: tasks 表（Task Engine 持久化）
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `tasks` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `title` TEXT NOT NULL,
                        `description` TEXT NOT NULL DEFAULT '',
                        `characterId` INTEGER NOT NULL,
                        `status` TEXT NOT NULL DEFAULT 'RUNNING',
                        `progress` REAL NOT NULL DEFAULT 0.0,
                        `toolName` TEXT,
                        `resultSummary` TEXT,
                        `projectId` TEXT,
                        `source` TEXT NOT NULL DEFAULT 'chat_tool',
                        `sourceMessageId` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `completedAt` INTEGER
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_characterId` ON `tasks` (`characterId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_status` ON `tasks` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_projectId` ON `tasks` (`projectId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_createdAt` ON `tasks` (`createdAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_status_createdAt` ON `tasks` (`status`, `createdAt`)")
            }
        }

        // ── Migration v7 → v8 ─────────────────────────────────
        // Phase 22: agent_plans 表（AgentPlan 进化方案）+ learning_goals 表（学习目标）
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // agent_plans 表
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `agent_plans` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `characterId` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `isActive` INTEGER NOT NULL DEFAULT 1,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_plans_characterId` ON `agent_plans` (`characterId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_plans_characterId_isActive` ON `agent_plans` (`characterId`, `isActive`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_plans_createdAt` ON `agent_plans` (`createdAt`)")

                // learning_goals 表
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `learning_goals` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `characterId` INTEGER NOT NULL,
                        `agentPlanId` TEXT,
                        `title` TEXT NOT NULL,
                        `description` TEXT NOT NULL DEFAULT '',
                        `progress` REAL NOT NULL DEFAULT 0.0,
                        `isActive` INTEGER NOT NULL DEFAULT 1,
                        `status` TEXT NOT NULL DEFAULT 'IN_PROGRESS',
                        `designatedReviewerId` INTEGER,
                        `lastUpdateNote` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_learning_goals_characterId` ON `learning_goals` (`characterId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_learning_goals_characterId_isActive` ON `learning_goals` (`characterId`, `isActive`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_learning_goals_agentPlanId` ON `learning_goals` (`agentPlanId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_learning_goals_createdAt` ON `learning_goals` (`createdAt`)")
            }
        }
        // ── Migration v8 → v9 ─────────────────────────────────
        // Phase 24: evaluation_sessions 表（打分会话）
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `evaluation_sessions` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `characterId` INTEGER NOT NULL,
                        `goalId` TEXT NOT NULL,
                        `triggerMessageId` TEXT NOT NULL,
                        `status` TEXT NOT NULL DEFAULT 'PENDING',
                        `agentScoreJson` TEXT,
                        `agentScore` REAL,
                        `agentComment` TEXT,
                        `userScore` INTEGER,
                        `userNote` TEXT,
                        `compositeScore` REAL,
                        `reportText` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_evaluation_sessions_characterId` ON `evaluation_sessions` (`characterId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_evaluation_sessions_characterId_goalId` ON `evaluation_sessions` (`characterId`, `goalId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_evaluation_sessions_status` ON `evaluation_sessions` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_evaluation_sessions_createdAt` ON `evaluation_sessions` (`createdAt`)")
            }
        }

        // ── Migration v9 → v10 ────────────────────────────────
        // Phase 25: memories 表新增 isLocked（规则锁定标志）和 goalId（规则目标关联）
        //
        // isLocked：仅 domain='RULE' 的记忆使用；isLocked=1 表示该规则已满足锁定条件
        //           （置信度 ≥4.0 且出现在 ≥3 次高分 Session），会被注入 Rule Layer。
        // goalId：  仅 domain='RULE' 的记忆使用；关联该规则所属的 LearningGoal。
        //           非 RULE 域记忆此字段为 NULL。
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 新增 isLocked 字段（默认 0 = false，存量记忆不受影响）
                db.execSQL(
                    "ALTER TABLE `memories` ADD COLUMN `isLocked` INTEGER NOT NULL DEFAULT 0"
                )
                // 新增 goalId 字段（可空，非 RULE 域记忆保持 NULL）
                db.execSQL(
                    "ALTER TABLE `memories` ADD COLUMN `goalId` TEXT"
                )
                // 为 Rule Layer 查询创建联合索引
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memories_isLocked_domain` ON `memories` (`characterId`, `domain`, `isLocked`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memories_goalId` ON `memories` (`characterId`, `goalId`)"
                )
            }
        }

        // ── Migration v10 → v11 ───────────────────────────────
        // Phase 29: + scheduled_jobs 表（定时任务）+ job_results 表（执行结果）
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {

                // scheduled_jobs 表
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `scheduled_jobs` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `characterId` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `toolName` TEXT NOT NULL,
                        `toolParamsJson` TEXT NOT NULL DEFAULT '{}',
                        `enabled` INTEGER NOT NULL DEFAULT 1,
                        `repeatIntervalMs` INTEGER,
                        `nextRunAt` INTEGER NOT NULL,
                        `lastRunAt` INTEGER,
                        `executedBy` TEXT NOT NULL DEFAULT 'local',
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_jobs_enabled_next` ON `scheduled_jobs` (`enabled`, `nextRunAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_jobs_character` ON `scheduled_jobs` (`characterId`)")

                // job_results 表
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `job_results` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `jobId` TEXT NOT NULL,
                        `characterId` INTEGER NOT NULL,
                        `toolName` TEXT NOT NULL,
                        `status` TEXT NOT NULL DEFAULT 'success',
                        `output` TEXT,
                        `errorMessage` TEXT,
                        `executedBy` TEXT NOT NULL DEFAULT 'local',
                        `startedAt` INTEGER NOT NULL,
                        `completedAt` INTEGER,
                        `isRead` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_results_jobId` ON `job_results` (`jobId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_results_char_read` ON `job_results` (`characterId`, `isRead`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_results_created` ON `job_results` (`createdAt`)")
            }
        }
        // ── Migration v11 → v12（Phase 31）────────────────────
        // project_knowledge 表新增 charCount 列
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `project_knowledge` ADD COLUMN `charCount` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // ── Migration v12 → v13（Bugfix）─────────────────────
        // relationship_states 补 sourceEventId 列（Entity 字段早于 migration 加入，导致 schema 不一致）
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `relationship_states` ADD COLUMN `sourceEventId` TEXT"
                )
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `coreWound` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `coreDesire` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `maskTrigger` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `privatePersona` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `privateStyle` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `privateExamples` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `situationRules` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `deviationSignals` TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `relationship_states` ADD COLUMN `suppression` INTEGER NOT NULL DEFAULT 50"
                )
            }
        }

        // ── 附加（NyxChat V18 A.1/A.2）：likes / dislikes / relationships ──
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `likes` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `dislikes` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `relationships` TEXT NOT NULL DEFAULT ''")
            }
        }

        // ── Migration v16 → v17 ───────────────────────────────
        // 新增 character_state 表（CharacterStateLayer 持久化）。
        // 字段与 CharacterStateEntity.kt 一一对应；socialMode 不入库
        // （实时计算，见 CharacterStateRepository.applySocialMode）。
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `character_state` (
                        `characterId` INTEGER NOT NULL PRIMARY KEY,
                        `maskType` TEXT NOT NULL,
                        `talkativeness` INTEGER NOT NULL,
                        `openness` INTEGER NOT NULL,
                        `patience` INTEGER NOT NULL,
                        `vigilance` INTEGER NOT NULL,
                        `primaryEmotion` TEXT NOT NULL,
                        `secondaryEmotion` TEXT,
                        `intensity` INTEGER NOT NULL,
                        `emotionalFatigue` INTEGER NOT NULL,
                        `emotionalStability` INTEGER NOT NULL,
                        `currentNeed` TEXT NOT NULL,
                        `currentGoal` TEXT NOT NULL,
                        `desireStrength` INTEGER NOT NULL,
                        `urgency` INTEGER NOT NULL,
                        `resistance` INTEGER NOT NULL,
                        `currentFear` TEXT NOT NULL,
                        `secretDesire` TEXT NOT NULL,
                        `exposureRisk` INTEGER NOT NULL,
                        `selfControl` INTEGER NOT NULL,
                        `emotionalSuppression` INTEGER NOT NULL,
                        `focusTarget` TEXT NOT NULL,
                        `focusStrength` INTEGER NOT NULL,
                        `observationLevel` INTEGER NOT NULL,
                        `concernLevel` INTEGER NOT NULL,
                        `lastUpdated` INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }
        // ── Migration v17 → v18 ───────────────────────────────
        // P3+P4.0（V5 执行方案，合并迁移）：
        //  ① relationship_milestones：关系转折点追加式历史记录（P3）
        //  ② pregnancy_state：怀孕状态展示（P4.0），不接入 CharacterStateLayer
        //  ③ birth_records：生育记录（P4.0）
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `relationship_milestones` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `fromId` TEXT NOT NULL,
                        `toId` TEXT NOT NULL,
                        `direction` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `sourceEventId` TEXT,
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS `index_relationship_milestones_fromId_toId`
                    ON `relationship_milestones` (`fromId`, `toId`)
                """.trimIndent())
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS `index_relationship_milestones_createdAt`
                    ON `relationship_milestones` (`createdAt`)
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `pregnancy_state` (
                        `characterId` INTEGER NOT NULL PRIMARY KEY,
                        `isPregnant` INTEGER NOT NULL,
                        `pregnancyStartedAt` INTEGER,
                        `cycleDays` INTEGER NOT NULL
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `birth_records` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT,
                        `characterId` INTEGER NOT NULL,
                        `bornAt` INTEGER NOT NULL,
                        `isDaughter` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS `index_birth_records_characterId`
                    ON `birth_records` (`characterId`)
                """.trimIndent())
            }
        }

        // ── Migration v18 → v19 ───────────────────────────────
        // character_identity 表新增两个字段（V18 关系结构层借鉴）：
        // ① relationAssumption：她对关系阶段/性质的默认认知前提（内核字段）
        // ② conflictStrategy：摩擦/误会场景下她的第一反应模式（行为规则字段）
        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `relationAssumption` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `conflictStrategy` TEXT NOT NULL DEFAULT ''")
            }
        }

        // ── Migration v19 → v20 ────────────────────────────────
        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `menstrual_cycle` (
                        `characterId`     INTEGER NOT NULL,
                        `cycleAnchorAt`   INTEGER,
                        `cycleLengthDays` INTEGER NOT NULL DEFAULT 28,
                        `menstrualDays`   INTEGER NOT NULL DEFAULT 5,
                        `fertileDays`     INTEGER NOT NULL DEFAULT 6,
                        PRIMARY KEY(`characterId`)
                    )
                    """.trimIndent()
                )
            }
        }

        // ── Migration v20 → v21 ────────────────────────────────
        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // agent_relation：女儿 Agent 与用户的关系阶段
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `agent_relation` (
                        `daughterId`          INTEGER NOT NULL,
                        `motherCharacterId`   INTEGER NOT NULL,
                        `stage`               TEXT NOT NULL DEFAULT 'STAGE_1_INITIAL',
                        `interactionCount`    INTEGER NOT NULL DEFAULT 0,
                        `createdAt`           INTEGER NOT NULL,
                        `lastStageUpAt`       INTEGER,
                        PRIMARY KEY(`daughterId`)
                    )
                    """.trimIndent()
                )
                // pregnancy_answers：孕期共设问答记录
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pregnancy_answers` (
                        `id`                  INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `motherCharacterId`   INTEGER NOT NULL,
                        `pregnancyStartedAt`  INTEGER NOT NULL,
                        `questionType`        TEXT NOT NULL,
                        `questionText`        TEXT NOT NULL,
                        `answerText`          TEXT NOT NULL,
                        `answeredAt`          INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        // ── Migration v21 → v22 ────────────────────────────────
        // D2.5 排卵期失败概率系统：
        //   - 删除 pregnancy_state.cycle_days 列（改为 PregnancyState.CYCLE_DAYS = 30 常量）
        //   - 新增 consecutive_fail_count（连续排卵期失败次数，成功怀孕后归零）
        //   - 新增 last_failure_injected_at（跨周期背景情绪注入冷却时间戳）
        //
        // SQLite 不支持 DROP COLUMN（API 34 以下），用重建表方式删除 cycle_days。
        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. 建临时表（无 cycle_days，含新两列）
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `pregnancy_state_new` (
                        `characterId`             INTEGER NOT NULL PRIMARY KEY,
                        `isPregnant`              INTEGER NOT NULL DEFAULT 0,
                        `pregnancyStartedAt`      INTEGER,
                        `consecutiveFailCount`    INTEGER NOT NULL DEFAULT 0,
                        `lastFailureInjectedAt`   INTEGER
                    )
                """.trimIndent())

                // 2. 迁移存量数据（cycle_days 直接丢弃）
                db.execSQL("""
                    INSERT INTO `pregnancy_state_new`
                        (`characterId`, `isPregnant`, `pregnancyStartedAt`)
                    SELECT `characterId`, `isPregnant`, `pregnancyStartedAt`
                    FROM `pregnancy_state`
                """.trimIndent())

                // 3. 替换旧表
                db.execSQL("DROP TABLE `pregnancy_state`")
                db.execSQL("ALTER TABLE `pregnancy_state_new` RENAME TO `pregnancy_state`")
            }
        }

        // ── Migration v22 → v23 ────────────────────────────────
        // D2.6 孕期体验与生命事件系统：
        //   - pregnancy_state 新增 miscarried_at（流产时间戳，null=未流产）
        //   - memories 新增 is_eternal（永恒状态记忆标记，生育记录用，永不蒸馏删除）
        //
        // 开发阶段亦可直接 fallbackToDestructiveMigration 清库重建。
        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // pregnancy_state：加 miscarried_at 列
                db.execSQL(
                    "ALTER TABLE `pregnancy_state` ADD COLUMN `miscarriedAt` INTEGER"
                )

                // memories：加 is_eternal 列（Boolean 存为 INTEGER，0=false，1=true）
                db.execSQL(
                    "ALTER TABLE `memories` ADD COLUMN `isEternal` INTEGER NOT NULL DEFAULT 0"
                )

                // 为 is_eternal 建索引，加速每次 Prompt 注入时的永恒记忆查询
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memories_characterId_isEternal` ON `memories` (`characterId`, `isEternal`)"
                )
            }
        }

        // ── Migration v23 → v24 ────────────────────────────────
        // D3 孕期共设系统：
        //   - pregnancy_answers 新增 slotIndex（槽位序号，WORLDVIEW/PERSONA
        //     拆两条用 0/1，NAME_PREF/WORRY 固定 0）
        //   - pregnancy_answers 新增 isLocked（槽位是否已锁定，取代原先
        //     用 pregnancyStartedAt 做边界判定的逻辑）
        //   - 新建 pregnancy_pending_question 表（问答配对状态追踪，
        //     单行覆盖写，PK = motherCharacterId）
        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // pregnancy_answers：加 slotIndex / isLocked 两列
                db.execSQL(
                    "ALTER TABLE `pregnancy_answers` ADD COLUMN `slotIndex` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `pregnancy_answers` ADD COLUMN `isLocked` INTEGER NOT NULL DEFAULT 0"
                )

                // pregnancy_pending_question：新建表
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `pregnancy_pending_question` (
                        `motherCharacterId` INTEGER NOT NULL PRIMARY KEY,
                        `questionType`      TEXT NOT NULL,
                        `slotIndex`         INTEGER NOT NULL,
                        `questionText`      TEXT NOT NULL,
                        `askedAt`           INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        // ── Migration v24 → v25 ───────────────────────────────
        // daughter_character 表：D4 女儿人格系统
        // 三列 JSON 分开存储（identity / stateLayer / customEnums），
        // 方便运行时只更新 stateLayerJson 不重写整张卡。
        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `daughter_character` (
                        `motherCharacterId` INTEGER NOT NULL PRIMARY KEY,
                        `daughterName`      TEXT NOT NULL,
                        `identityJson`      TEXT NOT NULL,
                        `stateLayerJson`    TEXT NOT NULL,
                        `customEnumsJson`   TEXT NOT NULL,
                        `generatedAt`       INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        // ── Migration v25 → v26 ───────────────────────────────
        // daughter_id_allocator 表：女儿角色编号发号器（D4 触发点接入 Part 2）。
        // 单行表（id 恒为 0），nextId 记录下一个可分配的女儿 characterId，
        // 起始值 1000，避免和预设角色（1-9）冲突。见
        // DaughterIdAllocatorEntity 文件头注释。
        private val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `daughter_id_allocator` (
                        `id`     INTEGER NOT NULL PRIMARY KEY,
                        `nextId` INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        // ── Migration v26 → v27 ───────────────────────────────
        // character_identity 表：新增 name 列（D4 触发点接入 Part 3）。
        //
        // 背景：预设角色（蒂法/露娜等 1-9 号）的名字此前硬编码在
        // CharacterConfig.kt 的 DefaultCharacters 列表里，不在数据库中，
        // 用户无法在 app 内修改；女儿角色的名字（daughterName）此前只
        // 存在 daughter_character 表，没有同步到 character_identity，
        // UI 读不到。这次统一加一列 name，作为所有角色（预设 + 女儿）
        // 名字的唯一持久化存储位置，写一次、改一次都直接落库，
        // 不依赖每次启动时重新计算/拼装。
        //
        // 回填策略：
        //   1) ALTER TABLE 加列，默认空字符串；
        //   2) 对 1-9 号预设角色，先 UPDATE 已存在的行；
        //      再 INSERT 那些用户从未打开过"角色管理"页、
        //      表里还没有对应行的角色（避免回填漏掉）。
        //   3) 女儿角色（1000+）此次不需要回填：Part 2 接入时女儿的
        //      identity 行是和这次迁移同批生成的代码一起上线的，
        //      翻译函数 toCharacterIdentityEntity() 已经同步补上了
        //      name 字段的写入（见 DaughterIdentity.kt），新生成的
        //      女儿会自带正确的名字，不存在迁移前的历史脏数据。
        private val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `character_identity` ADD COLUMN `name` TEXT NOT NULL DEFAULT ''"
                )

                val presetNames = listOf(
                    1 to "蒂法",
                    2 to "露娜",
                    3 to "伊芙",
                    4 to "宥熙",
                    5 to "索菲娅",
                    6 to "顾澜",
                    7 to "明媚",
                    8 to "莫婉凝",
                    9 to "江凡",
                )

                presetNames.forEach { (id, name) ->
                    // 已有行：回填名字（参数化查询，避免 name 含特殊字符导致 SQL 解析异常）
                    db.execSQL(
                        "UPDATE `character_identity` SET `name` = ? WHERE `characterId` = ?",
                        // 编译修复：name(String) 与 id(Int) 混合，显式标注 Array<Any?> 避免泛型推断警告
                        arrayOf<Any?>(name, id)
                    )
                    // 还没有行（用户从没存过这个角色的设置）：补一行，
                    // 其余字段使用表定义的默认值（均为 NOT NULL DEFAULT ''）。
                    // M-10 修复：原先用字符串模板把 $name 直接拼入 SQL，presetNames 当前
                    // 虽全为不含单引号的中文，但属于隐患——一旦未来加入含特殊字符的名字会
                    // 触发 SQLiteException 导致迁移失败。改为与上面 UPDATE 一致的参数化查询。
                    db.execSQL(
                        """
                        INSERT INTO `character_identity` (`characterId`, `name`)
                        SELECT ?, ?
                        WHERE NOT EXISTS (
                            SELECT 1 FROM `character_identity` WHERE `characterId` = ?
                        )
                        """.trimIndent(),
                        // 编译修复：id(Int) 与 name(String) 混合，显式标注 Array<Any?> 避免泛型推断警告
                        arrayOf<Any?>(id, name, id)
                    )
                }
            }
        }

        // ── Migration v27 → v28 ───────────────────────────────
        //
        // D4 触发点接入 Part 4：daughter_character 表新增 daughterCharacterId 列。
        //
        // 背景：女儿注册成角色资料表（character_identity）独立一行时，会从
        // DaughterIdAllocator 拿到一个全新编号（1000+）。但这个编号此前只写进
        // character_identity 表，没有回写到 daughter_character 表——也就是说，
        // 给定女儿自己的 characterId，没有任何路径能反查到她在 daughter_character
        // 表里的那一行（拿不到 stateLayerJson / customEnumsJson）。
        //
        // ChatViewModel.sendMessage() 组装 CharacterConfig 时，currentCharacterId
        // 就是女儿自己的 ID，查 DefaultCharacters（预设角色固定列表）必然查不到，
        // 必须有这一列才能继续往 daughter_character 表反查。
        //
        // 历史数据：本次迁移前如果已经生成过女儿（daughter_character 表有行），
        // 这些行的 daughterCharacterId 会是 NULL——因为她们注册时这一列还不存在，
        // 没有回填来源。新增女儿（迁移之后生成）会在 ChatViewModel.onIdentityRegister
        // 回调里自动回填，不受影响。
        private val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `daughter_character` ADD COLUMN `daughterCharacterId` INTEGER DEFAULT NULL"
                )
            }
        }

        // ── Migration v28 → v29 ───────────────────────────────
        //
        // 怀孕弹窗触发重构：pregnancy_state 新增 fertileWindowConsentAsked 列。
        //
        // 背景：原 D2 判定链对 1-6 号角色用关键词触发 + 静默判定，全程不弹窗。
        // 新方案改为三重门（关系阶段 CORE + 排卵期 FERTILE + AI 语义判定 YES）
        // 全部满足才弹出确认弹窗，仅适用于 characterId >= 1000（第二代/第三代
        // 女儿）；1-6 号保留原关键词链路作兜底，不读写这一列。
        //
        // 这一列标记"本次排卵期窗口内是否已经弹过同意弹窗"，防止同一排卵期
        // 内反复弹窗打扰；离开排卵期窗口后由调用方（PregnancyTriggerManager.
        // shouldEvaluateFertileWindowConsent）负责清回 false，供下次排卵期
        // 重新判定。默认 0（false），存量数据不受影响。
        private val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `pregnancy_state` ADD COLUMN `fertileWindowConsentAsked` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // ── Migration v29 → v30 ───────────────────────────────
        //
        // 多步骤工作流系统 Step 1（数据层）：
        //   ① workflow_jobs：工作流任务主表（状态机 RUNNING/COMPLETED/FAILED/TIMEOUT，
        //      currentStep/maxSteps/deadlineAt 双重防护）
        //   ② workflow_step_results：单步执行记录表，供引擎续跑回放 + TaskCenterScreen 展示
        //
        // 详见 WorkflowJobEntity.kt / WorkflowStepResultEntity.kt 文件头注释。
        private val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `workflow_jobs` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `characterId` INTEGER NOT NULL,
                        `goal` TEXT NOT NULL,
                        `status` TEXT NOT NULL DEFAULT 'RUNNING',
                        `currentStep` INTEGER NOT NULL DEFAULT 0,
                        `maxSteps` INTEGER NOT NULL DEFAULT 8,
                        `startedAt` INTEGER NOT NULL,
                        `deadlineAt` INTEGER NOT NULL,
                        `completedAt` INTEGER,
                        `resultSummary` TEXT,
                        `failReason` TEXT,
                        `isReported` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_workflow_jobs_characterId_status` ON `workflow_jobs` (`characterId`, `status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_workflow_jobs_status_createdAt` ON `workflow_jobs` (`status`, `createdAt`)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `workflow_step_results` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `jobId` TEXT NOT NULL,
                        `stepIndex` INTEGER NOT NULL,
                        `toolName` TEXT,
                        `toolParamsJson` TEXT NOT NULL DEFAULT '{}',
                        `success` INTEGER NOT NULL,
                        `output` TEXT,
                        `errorMessage` TEXT,
                        `decidedNextAction` TEXT,
                        `startedAt` INTEGER NOT NULL,
                        `completedAt` INTEGER,
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_workflow_step_results_jobId_stepIndex` ON `workflow_step_results` (`jobId`, `stepIndex`)")
            }
        }

        // ── Migration v30 → v31 ───────────────────────────────
        //
        // daughter_character + generatorVersion 列：
        // 纯回溯/调试字段，记录某条女儿记录是用哪一版 D4 生成器/Prompt
        // 产出的，不影响任何运行时业务逻辑。已有历史数据回填默认值
        // "d4-v1"（当前唯一存在过的生成器版本，回填后语义准确）。
        private val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `daughter_character` ADD COLUMN `generatorVersion` TEXT NOT NULL DEFAULT 'd4-v1'"
                )
            }
        }

        private val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `character_identity` ADD COLUMN `avatarUrl` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        // ── Migration v32 → v33 ───────────────────────────────
        // P1-32：scheduled_jobs + cloudSynced 列，标记本地任务是否已成功同步到
        //   Supabase，createJob/updateJob 同步失败时置 0，App 启动时重试。
        //   已有历史数据默认 1（视为已同步，避免老任务被误判为待同步重发）。
        // P1-33：scheduled_jobs + lockedUntil 列，runLocalCompensation() 与
        //   ScheduledJobWorker 执行前用它做认领式乐观锁，防止同一任务被
        //   两条路径并发执行两次。
        private val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `scheduled_jobs` ADD COLUMN `cloudSynced` INTEGER NOT NULL DEFAULT 1"
                )
                db.execSQL(
                    "ALTER TABLE `scheduled_jobs` ADD COLUMN `lockedUntil` INTEGER"
                )
            }
        }

        // ── Migration v33 → v34 ───────────────────────────────
        // 待办7：圆桌消息持久化，+ roundtable_messages 表
        private val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `roundtable_messages` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `roundtableId` TEXT NOT NULL,
                        `characterId` INTEGER NOT NULL,
                        `content` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_roundtable_messages_roundtableId` ON `roundtable_messages` (`roundtableId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_roundtable_messages_roundtableId_createdAt` ON `roundtable_messages` (`roundtableId`, `createdAt`)")
            }
        }

        // ── Migration v34 → v35 ───────────────────────────────
        // 待办3：群记忆 scope 字段
        // memories 主表 + memory_candidates 候选表加 scope/roundtableId 列
        private val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN scope TEXT NOT NULL DEFAULT 'PERSONAL'")
                db.execSQL("ALTER TABLE memories ADD COLUMN roundtableId TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_roundtableId` ON `memories` (`roundtableId`)")
                db.execSQL("ALTER TABLE memory_candidates ADD COLUMN scope TEXT NOT NULL DEFAULT 'PERSONAL'")
                db.execSQL("ALTER TABLE memory_candidates ADD COLUMN roundtableId TEXT")
            }
        }

        // ── Migration v35 → v36 ───────────────────────────────
        // P5 整合：roundtable_messages 表从简单字段升级为富结构，
        // characterId → speakerId/speakerName，新增 replyTarget/turnIndex
        private val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. 建新表
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `roundtable_messages_new` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `roundtableId` TEXT NOT NULL,
                        `speakerId` TEXT NOT NULL,
                        `speakerName` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `replyTargetId` TEXT,
                        `replyTargetName` TEXT,
                        `turnIndex` INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                // 2. 迁移存量数据：characterId→speakerId，speakerName 用 "角色" 占位
                db.execSQL("""
                    INSERT INTO `roundtable_messages_new`
                        (`id`, `roundtableId`, `speakerId`, `speakerName`, `content`, `createdAt`)
                    SELECT `id`, `roundtableId`, CAST(`characterId` AS TEXT), '', `content`, `createdAt`
                    FROM `roundtable_messages`
                """.trimIndent())
                // 3. 替换旧表
                db.execSQL("DROP TABLE `roundtable_messages`")
                db.execSQL("ALTER TABLE `roundtable_messages_new` RENAME TO `roundtable_messages`")
                // 4. 重建索引
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_roundtable_messages_roundtableId` ON `roundtable_messages` (`roundtableId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_roundtable_messages_roundtableId_createdAt` ON `roundtable_messages` (`roundtableId`, `createdAt`)")
            }
        }

        // ── Migration v36 → v37 ───────────────────────────────
        // Soul/Memory/User 三模块：character_identity 加 8 列
        private val MIGRATION_36_37 = object : Migration(36, 37) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE character_identity ADD COLUMN soulNote TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE character_identity ADD COLUMN soulNoteBackup TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE character_identity ADD COLUMN narrativeMemory TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE character_identity ADD COLUMN narrativeMemoryBackup TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE character_identity ADD COLUMN userImpression TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE character_identity ADD COLUMN userImpressionBackup TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE character_identity ADD COLUMN lastEditedNoteField TEXT")
                db.execSQL("ALTER TABLE character_identity ADD COLUMN lastEditedNoteAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        // ─────────────────────────────────────────────────────
        //  Migration v37 → v38（P6 专长进化系统）
        //
        //  新建 6 张表：evolution_plans / practice_records /
        //  practice_records_archive / stage_digests / specialty_profiles /
        //  system_suggestions。
        //  roundtable_messages 加 1 列：exportedFileJson（圆桌消息首次获得
        //  文件卡片能力）。
        //
        //  不触碰 character_identity / memories / memory_candidates /
        //  agent_plans / learning_goals / evaluation_sessions 等任何既有表，
        //  与 Phase 22-26 的规则提炼链路、P5 Soul/Memory/User 三模块物理隔离，
        //  互不影响——专长进化系统的晋升机制虽然最终会写入
        //  character_identity.soulNote，但那是运行期通过已有的
        //  CharacterIdentityDao.updateSoulNote() 方法写入，不需要任何
        //  schema 改动。
        // ─────────────────────────────────────────────────────
        private val MIGRATION_37_38 = object : Migration(37, 38) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ── evolution_plans ──────────────────────────────
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `evolution_plans` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `characterId` INTEGER NOT NULL,
                        `specialtyId` TEXT NOT NULL,
                        `version` INTEGER NOT NULL,
                        `content` TEXT NOT NULL,
                        `revisionReason` TEXT NOT NULL,
                        `isActive` INTEGER NOT NULL DEFAULT 1,
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_evolution_plans_characterId` ON `evolution_plans` (`characterId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_evolution_plans_characterId_specialtyId` ON `evolution_plans` (`characterId`, `specialtyId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_evolution_plans_characterId_isActive` ON `evolution_plans` (`characterId`, `isActive`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_evolution_plans_specialtyId_version` ON `evolution_plans` (`specialtyId`, `version`)")

                // ── practice_records ─────────────────────────────
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `practice_records` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `characterId` INTEGER NOT NULL,
                        `specialtyId` TEXT NOT NULL,
                        `practiceTopic` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `comparisonResult` TEXT NOT NULL,
                        `comparisonNote` TEXT NOT NULL,
                        `observedTrait` TEXT NOT NULL DEFAULT '',
                        `digestStatus` TEXT NOT NULL DEFAULT 'RAW',
                        `digestedIntoId` TEXT,
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_practice_records_specialtyId` ON `practice_records` (`specialtyId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_practice_records_specialtyId_createdAt` ON `practice_records` (`specialtyId`, `createdAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_practice_records_specialtyId_digestStatus` ON `practice_records` (`specialtyId`, `digestStatus`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_practice_records_characterId` ON `practice_records` (`characterId`)")

                // ── practice_records_archive ─────────────────────
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `practice_records_archive` (
                        `recordId` TEXT NOT NULL PRIMARY KEY,
                        `fullContent` TEXT NOT NULL,
                        `archivedAt` INTEGER NOT NULL
                    )
                """.trimIndent())

                // ── stage_digests ─────────────────────────────────
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `stage_digests` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `characterId` INTEGER NOT NULL,
                        `specialtyId` TEXT NOT NULL,
                        `digestContent` TEXT NOT NULL,
                        `sourceRecordCount` INTEGER NOT NULL,
                        `periodStart` INTEGER NOT NULL,
                        `periodEnd` INTEGER NOT NULL,
                        `hasConflict` INTEGER NOT NULL DEFAULT 0,
                        `conflictSummary` TEXT NOT NULL DEFAULT '',
                        `mergedIntoProfile` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_stage_digests_specialtyId` ON `stage_digests` (`specialtyId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_stage_digests_specialtyId_createdAt` ON `stage_digests` (`specialtyId`, `createdAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_stage_digests_specialtyId_mergedIntoProfile` ON `stage_digests` (`specialtyId`, `mergedIntoProfile`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_stage_digests_characterId` ON `stage_digests` (`characterId`)")

                // ── specialty_profiles ────────────────────────────
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `specialty_profiles` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `characterId` INTEGER NOT NULL,
                        `domain` TEXT NOT NULL,
                        `anchorIntent` TEXT NOT NULL,
                        `styleNotes` TEXT NOT NULL DEFAULT '',
                        `practiceCount` INTEGER NOT NULL DEFAULT 0,
                        `maturityStage` TEXT NOT NULL DEFAULT 'EXPLORING',
                        `candidateObservationsJson` TEXT NOT NULL DEFAULT '[]',
                        `hasUnresolvedConflict` INTEGER NOT NULL DEFAULT 0,
                        `unresolvedConflictDescription` TEXT NOT NULL DEFAULT '',
                        `promotedToIdentity` INTEGER NOT NULL DEFAULT 0,
                        `hasUserConfirmedAtLeastOnce` INTEGER NOT NULL DEFAULT 0,
                        `isActive` INTEGER NOT NULL DEFAULT 1,
                        `lastPracticeAt` INTEGER NOT NULL DEFAULT 0,
                        `lastDigestAt` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_specialty_profiles_characterId` ON `specialty_profiles` (`characterId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_specialty_profiles_characterId_domain` ON `specialty_profiles` (`characterId`, `domain`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_specialty_profiles_characterId_isActive` ON `specialty_profiles` (`characterId`, `isActive`)")

                // ── system_suggestions ────────────────────────────
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `system_suggestions` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `characterId` INTEGER NOT NULL,
                        `specialtyId` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `reasoning` TEXT NOT NULL,
                        `status` TEXT NOT NULL DEFAULT 'PENDING',
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_system_suggestions_specialtyId` ON `system_suggestions` (`specialtyId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_system_suggestions_specialtyId_status` ON `system_suggestions` (`specialtyId`, `status`)")

                // ── roundtable_messages 加列 ──────────────────────
                db.execSQL("ALTER TABLE roundtable_messages ADD COLUMN exportedFileJson TEXT")
            }
        }

        // ─────────────────────────────────────────────────────
        //  v38 → v39  裁判与竞争机制（第1步：数据层）
        //
        //  新增 5 张表：
        //    judge_profiles          裁判档案，含评判标准说明书与候选修正池
        //    competition_rounds      竞赛轮次，含状态机（COLLECTING→COMPLETED）
        //    competition_entries     参赛条目，含三方评分（裁判/自评/用户）
        //    competition_weight_configs  项目级评分权重配置，一个方向一条
        //    judge_accuracy_log      裁判排名与用户排名吻合度历史
        //
        //  不触碰任何既有表。
        // ─────────────────────────────────────────────────────
        private val MIGRATION_38_39 = object : Migration(38, 39) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ── judge_profiles ────────────────────────────────
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `judge_profiles` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `characterId` INTEGER NOT NULL,
                        `domain` TEXT NOT NULL,
                        `anchorIntent` TEXT NOT NULL,
                        `standardNotes` TEXT NOT NULL DEFAULT '',
                        `judgeCount` INTEGER NOT NULL DEFAULT 0,
                        `maturityStage` TEXT NOT NULL DEFAULT 'EXPLORING',
                        `candidateCorrectionsJson` TEXT NOT NULL DEFAULT '[]',
                        `hasUnresolvedConflict` INTEGER NOT NULL DEFAULT 0,
                        `unresolvedConflictDescription` TEXT NOT NULL DEFAULT '',
                        `isActive` INTEGER NOT NULL DEFAULT 1,
                        `lastJudgedAt` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_judge_profiles_characterId` ON `judge_profiles` (`characterId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_judge_profiles_characterId_domain` ON `judge_profiles` (`characterId`, `domain`)")

                // ── competition_rounds ────────────────────────────
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `competition_rounds` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `projectDomain` TEXT NOT NULL,
                        `topic` TEXT NOT NULL,
                        `judgeCharacterId` INTEGER NOT NULL,
                        `participantIdsJson` TEXT NOT NULL,
                        `status` TEXT NOT NULL DEFAULT 'COLLECTING',
                        `createdAt` INTEGER NOT NULL,
                        `completedAt` INTEGER
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_competition_rounds_projectDomain` ON `competition_rounds` (`projectDomain`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_competition_rounds_status` ON `competition_rounds` (`status`)")

                // ── competition_entries ───────────────────────────
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `competition_entries` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `roundId` TEXT NOT NULL,
                        `characterId` INTEGER NOT NULL,
                        `content` TEXT NOT NULL,
                        `judgeScore` INTEGER,
                        `judgeReasoning` TEXT NOT NULL DEFAULT '',
                        `selfScore` INTEGER,
                        `selfReasoning` TEXT NOT NULL DEFAULT '',
                        `userScore` INTEGER,
                        `userComment` TEXT NOT NULL DEFAULT '',
                        `userRank` INTEGER,
                        `compositeScore` REAL NOT NULL DEFAULT 0.0,
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_competition_entries_roundId` ON `competition_entries` (`roundId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_competition_entries_roundId_characterId` ON `competition_entries` (`roundId`, `characterId`)")

                // ── competition_weight_configs ────────────────────
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `competition_weight_configs` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `projectDomain` TEXT NOT NULL,
                        `userBaseWeight` INTEGER NOT NULL DEFAULT 50,
                        `judgeBaseWeight` INTEGER NOT NULL DEFAULT 40,
                        `selfBaseWeight` INTEGER NOT NULL DEFAULT 10,
                        `judgeTrustDynamicEnabled` INTEGER NOT NULL DEFAULT 1,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_competition_weight_configs_projectDomain` ON `competition_weight_configs` (`projectDomain`)")

                // ── judge_accuracy_log ────────────────────────────
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `judge_accuracy_log` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `judgeProfileId` TEXT NOT NULL,
                        `roundId` TEXT NOT NULL,
                        `agreementScore` REAL NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_judge_accuracy_log_judgeProfileId` ON `judge_accuracy_log` (`judgeProfileId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_judge_accuracy_log_judgeProfileId_createdAt` ON `judge_accuracy_log` (`judgeProfileId`, `createdAt`)")
            }
        }

        // ─────────────────────────────────────────────────────
        //  Migration v39 → v40（P1-5 修复：裁判档案懒创建并发重复）
        //
        //  judge_profiles 的 (characterId, domain) 索引从普通索引
        //  升级为唯一索引，配合 DAO 的 OnConflictStrategy.IGNORE +
        //  @Transaction 原子方法，从数据库层面彻底杜绝并发双击下的
        //  TOCTOU 竞态重复插入。
        //
        //  升级前先清理历史脏数据：若某 (characterId, domain) 组合
        //  已存在重复记录（旧版本并发 bug 残留），只保留 createdAt
        //  最早的一条，删除其余的，否则建唯一索引会失败。
        // ─────────────────────────────────────────────────────
        private val MIGRATION_39_40 = object : Migration(39, 40) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 去重：每组 (characterId, domain) 只保留 createdAt 最早的一条。
                // 注：minSdk=26 对应的系统 SQLite 版本不保证支持 ROW_NUMBER() 等
                // 窗口函数（需 3.25+），这里用 GROUP BY + 子查询兼容写法。
                db.execSQL("""
                    DELETE FROM judge_profiles
                    WHERE id NOT IN (
                        SELECT keep_id FROM (
                            SELECT id AS keep_id
                            FROM judge_profiles AS jp
                            WHERE jp.id = (
                                SELECT id FROM judge_profiles AS inner_jp
                                WHERE inner_jp.characterId = jp.characterId
                                  AND inner_jp.domain = jp.domain
                                ORDER BY inner_jp.createdAt ASC, inner_jp.id ASC
                                LIMIT 1
                            )
                        )
                    )
                """.trimIndent())

                // 把旧的普通索引换成唯一索引
                db.execSQL("DROP INDEX IF EXISTS `index_judge_profiles_characterId_domain`")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_judge_profiles_characterId_domain` ON `judge_profiles` (`characterId`, `domain`)")
            }
        }
  
        // ── Migration v40 → v41 ───────────────────────────────
        // S1 修复：memories 表新增 ftsRowId 列。
        // 旧逻辑用 id.hashCode() 作为 FTS 表 rowid 写入，
        // 但 JOIN 时错误地用了 SQLite 自增 rowid（m.rowid），
        // 导致两侧数值永远不等，FTS 全文检索召回完全失效。
        // 修复后：写入时同步将 ftsRowId 存入主表，
        // JOIN 改为 m.ftsRowId = fts.rowid，保证关联正确。
        // 存量记忆的 ftsRowId 默认为 0（FTS 行已与真实 rowid 不对应），
        // 等 MemoryEngine 下次重写时自动修正。
        private val MIGRATION_40_41 = object : Migration(40, 41) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `memories` ADD COLUMN `ftsRowId` INTEGER NOT NULL DEFAULT 0")
                // B3 修复：回填历史 memories 的 ftsRowId。
                // memories_fts 是 FTS4 虚拟表，其内置 rowid 与写入顺序一一对应。
                // 通过联表将 fts.rowid（由 fts.content 列文本推导）更新到主表。
                // 做法：memories_fts content 列存储的是 memories.content，
                // 按 memories.id 与 fts 内容精确匹配来关联。
                // 由于 FTS4 不支持直接 JOIN，先把 fts rowid 写入临时表再 UPDATE。
                db.execSQL("""
                    CREATE TEMPORARY TABLE IF NOT EXISTS _fts_rowid_map AS
                    SELECT rowid AS fts_rowid, content AS fts_content
                    FROM memories_fts
                """.trimIndent())
                db.execSQL("""
                    UPDATE memories
                    SET ftsRowId = (
                        SELECT fts_rowid FROM _fts_rowid_map
                        WHERE fts_content = memories.content
                        LIMIT 1
                    )
                    WHERE ftsRowId = 0
                """.trimIndent())
                db.execSQL("DROP TABLE IF EXISTS _fts_rowid_map")
            }
        }

        // ── Migration v41 → v42 ───────────────────────────────
        // B5 修复：projects 表新增 goalId 列，支持三层结构 Tasks/Goals/Projects 关联。
        // null = 独立项目（不挂载到任何 LearningGoal）。
        private val MIGRATION_41_42 = object : Migration(41, 42) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `projects` ADD COLUMN `goalId` TEXT")
            }
        }

        // ── Migration v42 → v43 ───────────────────────────────
        // P1-1-3 修复：MIGRATION_40_41 用 memories.content 反查 memories_fts.rowid，
        // content 非唯一时会错配 ftsRowId，导致 FTS JOIN 指向错误行。
        //
        // 修复方案：删除旧手动维护的 FTS 表，重建为外部内容 FTS4 表
        // （content=`memories`），由 Room 触发器自动同步，彻底去除 ftsRowId 字段。
        //
        // 注意：此 Migration 不删除 memories.ftsRowId 列（SQLite 不支持 DROP COLUMN）；
        // 该列保留为 0，不再被任何新代码读写——MemoryDao/MemoryRepository 已改为
        // 依赖 Room 触发器，不再手动操作 FTS rowid。
        private val MIGRATION_42_43 = object : Migration(42, 43) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. 删除旧的手动管理 FTS 表（包含旧 content/keywords 索引）
                db.execSQL("DROP TABLE IF EXISTS `memories_fts`")

                // 2. 重建为外部内容 FTS4 表，content=`memories` 告知 FTS 关联主表
                //    Room 会在第一次 build() 时自动生成 after_insert/after_update/after_delete
                //    三条触发器，保持 FTS 与主表同步，不再需要手写 ftsRowId。
                db.execSQL("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS `memories_fts`
                    USING fts4(content=`memories`, `content`, `keywords`, tokenize=unicode61)
                """.trimIndent())

                // 3. 用 rebuild 命令从主表 memories 全量回填 FTS 索引。
                //    FTS4 外部内容表的 rebuild 会读取 content= 指向的主表，
                //    按主表 rowid 对应关系重建全文索引，不依赖 content 列文本匹配，
                //    彻底消除 MIGRATION_40_41 中 content 反查错配的问题。
                db.execSQL("INSERT INTO `memories_fts`(`memories_fts`) VALUES('rebuild')")
            }
        }

        // ── Migration v43 → v44 ────────────────────────────────
        // P1-6-1：evolution_plans 补 (specialtyId, version) 唯一索引，
        //         防止并发写入产生重复版本号（SQLite 不支持直接 ADD UNIQUE INDEX，
        //         需重建表）。
        // P1-6-9：pregnancy_answers 补 (motherCharacterId, questionType, slotIndex, answeredAt)
        //         唯一索引，作为 @Transaction recordIfOpen 的数据库层最终兜底。
        private val MIGRATION_43_44 = object : Migration(43, 44) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ── evolution_plans：重建以加入唯一索引 ─────────
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `evolution_plans_new` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `characterId` INTEGER NOT NULL,
                        `specialtyId` TEXT NOT NULL,
                        `version` INTEGER NOT NULL,
                        `content` TEXT NOT NULL,
                        `revisionReason` TEXT NOT NULL,
                        `isActive` INTEGER NOT NULL DEFAULT 1,
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO `evolution_plans_new`
                    SELECT `id`, `characterId`, `specialtyId`, `version`,
                           `content`, `revisionReason`, `isActive`, `createdAt`
                    FROM `evolution_plans`
                """.trimIndent())
                db.execSQL("DROP TABLE `evolution_plans`")
                db.execSQL("ALTER TABLE `evolution_plans_new` RENAME TO `evolution_plans`")
                // 唯一索引
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_evolution_plans_specialtyId_version` ON `evolution_plans` (`specialtyId`, `version`)")
                // 其余原有普通索引
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_evolution_plans_characterId` ON `evolution_plans` (`characterId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_evolution_plans_characterId_specialtyId` ON `evolution_plans` (`characterId`, `specialtyId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_evolution_plans_characterId_isActive` ON `evolution_plans` (`characterId`, `isActive`)")

                // ── pregnancy_answers：直接 CREATE UNIQUE INDEX（无需重建表）──
                db.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_pregnancy_answers_motherCharacterId_questionType_slotIndex_answeredAt`
                    ON `pregnancy_answers` (`motherCharacterId`, `questionType`, `slotIndex`, `answeredAt`)
                """.trimIndent())
            }
        }
        // ── Migration v44 → v45 ───────────────────────────────
        // P-1 修复：MIGRATION_42_43 把 memories_fts 重建为"外部内容 FTS4 表"
        // （USING fts4(content=`memories`, ...)），但 MemoryFtsEntity 的 @Fts4 注解
        // 定义的是普通 FTS4 表（无 contentEntity 参数）。二者 schema 不一致会导致
        // Room 在 build() 时校验失败、App 启动崩溃（IllegalStateException）。
        //
        // 修复方案：删除外部内容表，重建为与 Entity 一致的普通 FTS4 表。
        // 主表 <-> FTS 同步仍由 MemoryRepository.save()/update() 手动维护
        // （insertWithFts / updateWithFts），ftsRowId 字段继续负责 JOIN。
        //
        // P-3 修复（顺带）：MIGRATION_40_41 用 content 文本反查 ftsRowId，
        // 重复 content 会错配 rowId。MIGRATION_42_43 已重建 FTS 表，但旧的
        // ftsRowId 错配数据仍留在主表。
        // 本次迁移重新全量回填 ftsRowId：借助 SQLite 在新建普通 FTS4 表时
        // rowid 自增的特性，将 FTS 表的真实 rowid（按 INSERT 顺序分配）
        // 与主表按 createdAt 排序写入后按 memories.rowid 回填，
        // 消除因 content 反查造成的错配存量数据。
        //
        // 注意：已删除的外部内容表不再产生 Room 触发器；普通 FTS4 表同步
        // 由代码层 MemoryRepository 负责，行为与 v42 之前完全一致。
        private val MIGRATION_44_45 = object : Migration(44, 45) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. 删除 MIGRATION_42_43 创建的外部内容 FTS4 表（及其隐含触发器）
                db.execSQL("DROP TABLE IF EXISTS `memories_fts`")
                db.execSQL("DROP TRIGGER IF EXISTS `room_fts_content_sync_memories_fts_BEFORE_UPDATE`")
                db.execSQL("DROP TRIGGER IF EXISTS `room_fts_content_sync_memories_fts_BEFORE_DELETE`")
                db.execSQL("DROP TRIGGER IF EXISTS `room_fts_content_sync_memories_fts_AFTER_UPDATE`")
                db.execSQL("DROP TRIGGER IF EXISTS `room_fts_content_sync_memories_fts_AFTER_INSERT`")

                // 2. 重建为普通 FTS4 表（与 MemoryFtsEntity @Fts4 注解保持一致）
                //    tokenize=unicode61 匹配 FtsOptions.TOKENIZER_UNICODE61
                db.execSQL("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS `memories_fts`
                    USING fts4(`content`, `keywords`, tokenize=unicode61)
                """.trimIndent())

                // 3. 从主表回填存量数据进 FTS：按 createdAt 顺序插入，
                //    FTS rowid 由 SQLite 自增分配，随后把该 rowid 更新回主表 ftsRowId。
                //    使用临时表避免 FTS 表不支持直接 JOIN 的限制。
                db.execSQL("""
                    INSERT INTO `memories_fts`(`rowid`, `content`, `keywords`)
                    SELECT `rowid`, `content`, `keywords`
                    FROM `memories`
                    ORDER BY `rowid`
                """.trimIndent())

                // 4. 用 FTS 表真实 rowid 回填主表 ftsRowId（FTS rowid == memories.rowid）
                //    普通 FTS4 表按 INSERT 顺序分配 rowid，与上面 INSERT 的 rowid 列一一对应
                db.execSQL("""
                    UPDATE `memories`
                    SET `ftsRowId` = `rowid`
                    WHERE 1
                """.trimIndent())
            }
        }

        // ── Migration v45 → v46 ───────────────────────────────
        // 头像存储重新设计（2026-07-03）：旧方案 onAvatarCropped 直接把
        // 用户圆形裁剪框里看到的区域裁成 512×512 正方形存盘，avatarUrl
        // 指向的就是这张成品图。公馆页需要把它塞进拱形（矩形+半圆，
        // 宽高比约 0.48:1）容器，正方形图 Crop 撑满宽度后，超出原裁剪
        // 范围的上下区域没有真实画面，只剩容器背景色——这是存储格式
        // 从一开始就没考虑非方形展示场景，不是能靠调渲染参数修好的
        // bug，需要重新设计存储结构。
        //
        // 新方案：avatarUrl 字段语义改为「原图路径」（不再是裁剪成品图，
        // 旧数据存量的 512×512 成品图会被当成「原图」继续使用，效果
        // 退化但不会崩溃——原图=旧成品图时，裁剪参数按 offset=0/scale=1
        // 处理即等于直接显示那张图，圆形场景不受影响，拱形场景仍会
        // 露边，需要用户重新上传一次才能真正修好，属预期内的存量数据
        // 降级，不做自动迁移）。
        // 新增两套裁剪参数：
        //   avatarCropCircle*：详情页圆形头像，语义与旧 AvatarCropDialog
        //     的 offset/scale 一致。
        //   avatarCropTall*：公馆拱形 + 书架椭圆共用的竖长矩形裁剪参数，
        //     两处展示比例一致，不再分别裁剪、分别存储。
        // 默认值 offsetX=0f/offsetY=0f/scale=1f 對存量数据是安全默认值
        // （图片居中、不额外缩放）。
        private val MIGRATION_45_46 = object : Migration(45, 46) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `avatarCropCircleOffsetX` REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `avatarCropCircleOffsetY` REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `avatarCropCircleScale` REAL NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `avatarCropTallOffsetX` REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `avatarCropTallOffsetY` REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `avatarCropTallScale` REAL NOT NULL DEFAULT 1")
            }
        }
    }
}
