package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.provider.chatSyncWithRetry
import android.content.Context
import com.zaijian.zhoumuyun.util.ZLog
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.PracticeRecordArchiveEntity
import com.zaijian.zhoumuyun.data.db.entity.PracticeRecordEntity
import com.zaijian.zhoumuyun.data.db.entity.RoundtableMessageEntity
import com.zaijian.zhoumuyun.data.engine.SpecialtyEvolutionConfig
import com.zaijian.zhoumuyun.data.engine.SpecialtyEvolutionEngine
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.provider.LLMConfig
import com.zaijian.zhoumuyun.data.provider.LLMMessage
import com.zaijian.zhoumuyun.data.provider.LLMProvider
import com.zaijian.zhoumuyun.data.provider.ProviderManager
import com.zaijian.zhoumuyun.data.repository.DaughterCharacterRepository
import com.zaijian.zhoumuyun.data.repository.SpecialtyProfileRepository
import org.json.JSONObject
import java.util.UUID

/**
 * DailyPracticeWorker — P6 专长进化系统"每日修炼"的后台执行器
 *
 * 与 ProactiveMessageWorker 同样的模式：
 *   - CoroutineWorker，临时拼装最小依赖（不走 Hilt，与项目里其余 Worker 一致）
 *   - 失败不重试，下一天自然有新机会（异常被 catch，不向上抛，避免 WorkManager
 *     按默认 BackoffPolicy 重试导致同一天内重复执行多次修炼）
 *   - finally 块无条件重新调度下一次（见 DailyPracticeScheduler 文档说明，
 *     这是这个"自我重新调度"模式唯一需要小心的地方）
 *
 * 单次 doWork() 会遍历所有 isActive=true 的 SpecialtyProfile（跨角色），
 * 逐一执行完整的"今日修炼"流程：
 *   生成产出 → 风格比对 → 候选观察池更新 → 落库（含写文件）→ 圆桌播报
 *   → 触发蒸馏容量检查（DistillationTrigger，另一文件，本类只负责调用入口）
 *
 * 某个专长方向若处理失败（LLM 解析异常等），不影响其余专长方向继续执行——
 * 每个专长的处理逻辑包在独立的 try-catch 里，一个失败不拖累整批。
 */
class DailyPracticeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private companion object {
        const val PRACTICE_EXPORT_DIR = "specialty_practices"
        val UNSAFE_CHARS = Regex("[/\\\\:*?\"<>|]")
    }

    override suspend fun doWork(): Result {
        try {
            val db = AppDatabase.getInstance(applicationContext)
            val provider = ProviderManager.instance.activeProvider
            if (provider == null) {
                // 用户未配置 API Key，本次静默跳过（与 EvaluationEngine/DistillationEngine
                // 在 ChatViewModel 里的 by lazy 可空写法同样的"优雅跳过"原则）
                return Result.success()
            }
            val engine = SpecialtyEvolutionEngine(provider)
            val repo = SpecialtyProfileRepository(
                db = db,
                specialtyProfileDao = db.specialtyProfileDao(),
                evolutionPlanDao = db.evolutionPlanDao(),
                practiceRecordDao = db.practiceRecordDao(),
                practiceRecordArchiveDao = db.practiceRecordArchiveDao(),
                stageDigestDao = db.stageDigestDao(),
                systemSuggestionDao = db.systemSuggestionDao(),
            )
            val daughterRepo = DaughterCharacterRepository(db.daughterCharacterDao())

            val activeProfiles = db.specialtyProfileDao().getAllActiveProfiles()
            for (profile in activeProfiles) {
                try {
                    runSinglePractice(db, repo, engine, daughterRepo, profile, provider)
                } catch (e: Exception) {
                    ZLog.w("DailyPracticeWorker", "专长 ${profile.id} 修炼失败", e)
                    // 单个专长失败不影响其余专长，继续下一个
                }
            }
        } catch (e: Exception) {
            ZLog.w("DailyPracticeWorker", "doWork failed", e)
            // 不重试，下一天自然会再跑
        } finally {
            // 无条件重新调度，即使上面发生异常也不能让每日链路断掉
            val (hour, minute) = readConfiguredTime()
            DailyPracticeScheduler.scheduleNext(applicationContext, hour, minute)
        }
        return Result.success()
    }

    /** 读取用户配置的每日触发时间。v1 暂未做专门的设置 UI，先用默认值，
     *  接口已经留好（专长档案页后续可以加"修改修炼时间"的入口，写入
     *  SharedPreferences，这里改成读取即可，不需要再改 Worker 内部逻辑）。 */
    private fun readConfiguredTime(): Pair<Int, Int> {
        val prefs = applicationContext.getSharedPreferences("specialty_evolution_prefs", Context.MODE_PRIVATE)
        val hour = prefs.getInt("daily_practice_hour", DailyPracticeScheduler.DEFAULT_HOUR)
        val minute = prefs.getInt("daily_practice_minute", DailyPracticeScheduler.DEFAULT_MINUTE)
        return hour to minute
    }

    // ─────────────────────────────────────────────────────────
    //  单个专长方向的完整修炼流程
    // ─────────────────────────────────────────────────────────

    private suspend fun runSinglePractice(
        db: AppDatabase,
        repo: SpecialtyProfileRepository,
        engine: SpecialtyEvolutionEngine,
        daughterRepo: DaughterCharacterRepository,
        profile: com.zaijian.zhoumuyun.data.db.entity.SpecialtyProfileEntity,
        provider: LLMProvider,
    ) {
        // 1. 取当前生效的 EvolutionPlan，没有则跳过本次（用户还没布置方向，
        //    或者方案因为某种原因被清空——理论上 createProfile 之后应该
        //    立刻有人创建第一版方案，这里做防御性检查）
        val plan = repo.getActivePlan(profile.id) ?: return

        // 2. 让 LLM 基于方案 + styleNotes，自主决定"今天具体练什么"，
        //    产出 practiceTopic + 创作正文
        val (topic, content) = generateTodayPractice(provider, profile, plan.content)
        if (content.isBlank()) return  // 生成失败，本次跳过，不留空记录

        // 3. 风格比对
        val candidateSummary = repo.parseCandidateObservations(profile.candidateObservationsJson)
            .joinToString("; ") { "${it.trait}（已观察${it.occurrenceCount}次）" }
        val comparison = engine.compareAgainstStyleNotes(
            domain = profile.domain,
            styleNotes = profile.styleNotes,
            candidateObservationsSummary = candidateSummary,
            newContent = content,
        )

        // 4. 落库：先写文件（复用 file_export 工具同款的 filesDir/子目录 写入方式），
        //    再写 PracticeRecordEntity
        val recordId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val exportMeta = writePracticeFile(profile.domain, topic, content, now)

        val record = PracticeRecordEntity(
            id = recordId,
            characterId = profile.characterId,
            specialtyId = profile.id,
            practiceTopic = topic,
            content = content,
            comparisonResult = comparison.result,
            comparisonNote = comparison.note,
            observedTrait = comparison.observedTrait,
            createdAt = now,
        )
        db.practiceRecordDao().insert(record)
        repo.recordPracticeCompleted(profile.id)

        // 5. 候选观察池更新（仅 EMERGING 结果需要）
        if (comparison.result == "EMERGING" && comparison.observedTrait.isNotBlank()) {
            updateCandidatePool(db, repo, engine, profile.id, comparison.observedTrait)
        }

        // 6. 圆桌播报 + 文件卡片
        postToRoundtable(db, daughterRepo, profile, topic, comparison, exportMeta)

        // 7. 容量检查：是否触发蒸馏（异步，不阻塞本次播报；失败不影响本次修炼已落库的事实）
        try {
            DistillationTrigger.checkAndRun(db, provider, profile.id)
        } catch (e: Exception) {
            ZLog.w("DailyPracticeWorker", "蒸馏触发检查失败 specialtyId=${profile.id}", e)
        }
    }

    // ─────────────────────────────────────────────────────────
    //  生成今日练习内容
    // ─────────────────────────────────────────────────────────

    private suspend fun generateTodayPractice(
        provider: LLMProvider,
        profile: com.zaijian.zhoumuyun.data.db.entity.SpecialtyProfileEntity,
        planContent: String,
    ): Pair<String, String> {
        val systemPrompt = """
            你正在为角色规划并执行一次「${profile.domain}」方向的自主练习。
            角色当前的自我进化方案：
            $planContent

            角色目前已经沉淀的风格说明书（可能为空，为空说明仍在摸索期）：
            ${profile.styleNotes.ifBlank { "（暂无，仍在摸索阶段）" }}

            请你：
            1. 自主决定今天具体练什么场景/角度（不要每天都选同一个，也不要完全脱离
               已有方案随意发挥），用一个简短的主题描述（不超过20字）。
            2. 围绕这个主题，产出一段真实的创作内容（不是讨论怎么写，是真的写出来）。

            仅输出 JSON，不加任何其他文字：
            {"topic":"今天的练习主题","content":"创作正文"}
        """.trimIndent()

        return try {
            val response = provider.chatSyncWithRetry(
                messages = listOf(LLMMessage("user", "请开始今天的练习。")),
                systemPrompt = systemPrompt,
                config = LLMConfig(
                    model = "",
                    maxTokens = 1500,
                    temperature = SpecialtyEvolutionConfig.PRACTICE_TEMPERATURE.toFloat(),
                    stream = false,
                ),
            )
            val trimmed = response.trim()
            val start = trimmed.indexOf('{')
            val end = trimmed.lastIndexOf('}')
            val jsonStr = if (start >= 0 && end > start) trimmed.substring(start, end + 1) else trimmed
            val obj = JSONObject(jsonStr)
            val topic = obj.optString("topic", "今日练习").take(40)
            val content = obj.optString("content", "")
            topic to content
        } catch (e: Exception) {
            ZLog.w("DailyPracticeWorker", "生成今日练习失败", e)
            "" to ""
        }
    }

    // ─────────────────────────────────────────────────────────
    //  候选观察池更新（含语义匹配判断）
    // ─────────────────────────────────────────────────────────

    private suspend fun updateCandidatePool(
        db: AppDatabase,
        repo: SpecialtyProfileRepository,
        engine: SpecialtyEvolutionEngine,
        profileId: String,
        observedTrait: String,
    ) {
        val profile = repo.getProfile(profileId) ?: return
        val existing = repo.parseCandidateObservations(profile.candidateObservationsJson)
        val matchedTrait = if (existing.isEmpty()) {
            null
        } else {
            engine.matchCandidateObservation(observedTrait, existing.map { it.trait })
        }
        val updatedCount = repo.recordCandidateObservation(
            profileId = profileId,
            newTrait = observedTrait,
            matchedExistingTrait = matchedTrait,
        )
        val effectiveTrait = matchedTrait ?: observedTrait

        // 达到转正阈值后的"询问用户确认"/"稳定期强化补充冲突判断"流程
        CandidatePromotionChecker.checkPromotion(
            db = db,
            repo = repo,
            engine = engine,
            profileId = profileId,
            trait = effectiveTrait,
            occurrenceCount = updatedCount,
        )
    }

    // ─────────────────────────────────────────────────────────
    //  写文件（复用 FileExportTool 同款 filesDir 写入方式）
    // ─────────────────────────────────────────────────────────

    data class ExportMeta(val fileName: String, val mimeType: String, val sizeBytes: Long, val absolutePath: String)

    private fun writePracticeFile(domain: String, topic: String, content: String, timestamp: Long): ExportMeta? {
        return try {
            val safeTopic = UNSAFE_CHARS.replace(topic, "_").take(40)
            val safeDomain = UNSAFE_CHARS.replace(domain, "_").take(20)
            val fileName = "${safeDomain}_${safeTopic}.md"
            val exportDir = java.io.File(applicationContext.filesDir, PRACTICE_EXPORT_DIR).also { it.mkdirs() }
            val file = java.io.File(exportDir, "${timestamp}_$fileName")
            file.writeText(content, Charsets.UTF_8)
            ExportMeta(
                fileName = fileName,
                mimeType = "text/markdown",
                sizeBytes = file.length(),
                absolutePath = file.absolutePath,
            )
        } catch (e: Exception) {
            ZLog.w("DailyPracticeWorker", "写入修炼产出文件失败", e)
            null
        }
    }

    // ─────────────────────────────────────────────────────────
    //  圆桌播报
    // ─────────────────────────────────────────────────────────

    private suspend fun postToRoundtable(
        db: AppDatabase,
        daughterRepo: DaughterCharacterRepository,
        profile: com.zaijian.zhoumuyun.data.db.entity.SpecialtyProfileEntity,
        topic: String,
        comparison: SpecialtyEvolutionEngine.ComparisonResult,
        exportMeta: ExportMeta?,
    ) {
        // 反查该角色最近活跃的圆桌（见 RoundtableMessageDao.findMostRecentRoundtableIdForSpeaker
        // 的注释：角色没有"默认圆桌"概念，没有历史记录时直接跳过播报，
        // 不强行创建一个新圆桌——这超出了本次设计范围）
        val roundtableId = db.roundtableMessageDao()
            .findMostRecentRoundtableIdForSpeaker(profile.characterId.toString())
            ?: return

        val config: CharacterConfig? = DefaultCharacters.firstOrNull { it.id == profile.characterId }
            ?: daughterRepo.getCharacterConfig(profile.characterId)
        val speakerName = config?.name ?: "角色${profile.characterId}"

        val briefText = buildBriefSummary(profile.domain, topic, comparison)

        val fileJson = exportMeta?.let {
            JSONObject().apply {
                put("fileName", it.fileName)
                put("mimeType", it.mimeType)
                put("sizeBytes", it.sizeBytes)
                put("absolutePath", it.absolutePath)
            }.toString()
        }

        db.roundtableMessageDao().insert(
            RoundtableMessageEntity(
                id = UUID.randomUUID().toString(),
                roundtableId = roundtableId,
                speakerId = profile.characterId.toString(),
                speakerName = speakerName,
                content = briefText,
                createdAt = System.currentTimeMillis(),
                exportedFileJson = fileJson,
            )
        )
    }

    /** 拼装播报文案（不调用 LLM，本身已经是结构化信息，直接拼句子即可，节省一次调用） */
    private fun buildBriefSummary(
        domain: String,
        topic: String,
        comparison: SpecialtyEvolutionEngine.ComparisonResult,
    ): String = buildString {
        append("今天在「$domain」上练了「$topic」。")
        when (comparison.result) {
            "EMERGING" -> append(comparison.observedTrait.ifBlank { "发现了一些新的倾向，还在观察中。" })
            "CONFLICTING" -> append("不过这次的处理方式和之前确立的风格有些不一样，等攒够样本后会标记出来让你看看怎么定。")
            else -> append("延续了之前的感觉，又巩固了一次。")
        }
    }
}
