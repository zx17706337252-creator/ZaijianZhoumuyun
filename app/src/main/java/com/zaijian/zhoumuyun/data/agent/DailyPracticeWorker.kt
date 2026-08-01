package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.provider.chatSyncWithRetry
import android.content.Context
import com.zaijian.zhoumuyun.util.ZLog
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.PracticeRecordArchiveEntity
import com.zaijian.zhoumuyun.data.db.entity.PracticeRecordEntity
import com.zaijian.zhoumuyun.data.db.entity.RoundtableMessageEntity
import com.zaijian.zhoumuyun.domain.SpecialtyEvolutionConfig
import com.zaijian.zhoumuyun.domain.SpecialtyEvolutionEngine
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

    // A5-4 修复：companion object 原为 private，导致 ZaijianApp.setupNotificationChannels()
    // 无法访问 CHANNEL_ID/CHANNEL_NAME 统一注册通知渠道；参照 CiCdPipelineWorker /
    // BuildApkDownloadTool 的写法改为公开 companion object。
    companion object {
        const val PRACTICE_EXPORT_DIR = "specialty_practices"
        val UNSAFE_CHARS = Regex("[/\\\\:*?\"<>|]")

        // A5-4 修复：Provider 为空时"连续跳过 N 次后提醒"机制的配置。
        // 计数与每日触发时间共用同一份 SharedPreferences（specialty_evolution_prefs，
        // 见 readConfiguredTime()），key 为 daily_practice_skip_count。
        // 阈值 3 为实现选择（见文件末尾 A5-4 说明），后续可在此处统一调整。
        const val PREFS_NAME = "specialty_evolution_prefs"
        const val KEY_SKIP_COUNT = "daily_practice_skip_count"
        const val SKIP_NOTIFY_THRESHOLD = 3
        const val NOTIF_ID_SKIP_PROMPT = 77021

        // A5-4 修复：跳过提醒系统通知渠道，与其余 Worker 同款在
        // ZaijianApp.setupNotificationChannels() 统一注册（见该处补充）。
        const val CHANNEL_ID = "daily_practice"
        const val CHANNEL_NAME = "每日修炼"
    }

    override suspend fun doWork(): Result {
        // 提前读取配置（在 try 之外，异常不会中断调度链路）
        // 若 SharedPreferences XML 损坏等原因导致读取失败，使用默认值兜底，
        // 确保 scheduleNext 始终被调用，每日修炼链路不会永久中断。
        val (hour, minute) = try {
            readConfiguredTime()
        } catch (e: Throwable) {
            ZLog.w("DailyPracticeWorker", "读取配置时间失败，使用默认值", e)
            DailyPracticeScheduler.DEFAULT_HOUR to DailyPracticeScheduler.DEFAULT_MINUTE
        }

        try {
            val db = AppDatabase.getInstance(applicationContext)
            val provider = ProviderManager.instance.activeProvider
            if (provider == null) {
                // A5-4 修复：原此处直接 return Result.success() 静默跳过，连续多日
                // 无任何提示，用户可能长期不知道每日修炼因未配置 API Key 而一直没在跑。
                // 改为累计跳过次数，达到阈值（SKIP_NOTIFY_THRESHOLD）时通过系统通知
                // （NotificationPermissionUtils.safeNotify）+ 通知中心
                // （NotificationRepository.recordAppNotice）提醒用户配置 Provider，
                // 并重置计数（下一轮重新累计，即每跳过阈值次提醒一次，不刷屏）。
                // 计数 / 通知本身用独立 try-catch 包裹，失败不影响 doWork 主流程
                // （即便提醒没发出去，本次仍按原语义 return Result.success() 跳过，
                // finally 块照常 scheduleNext 保证次日继续，链路不断）。
                handleProviderMissing()
                return Result.success()
            }
            // A5-4 修复：Provider 可用，重置此前可能累积的跳过计数。重置失败
            // 不应阻断当天修炼，单独 try-catch 吞掉（最坏情况是计数未清零，
            // 下次提醒阈值提前触发一次，无数据正确性影响）。
            try {
                resetSkipCount()
            } catch (e: Throwable) {
                ZLog.w("DailyPracticeWorker", "重置跳过计数失败", e)
            }
            val engine = SpecialtyEvolutionEngine(provider)
            // 窗口02结论5修复：原先在此处独立 new 一份 SpecialtyProfileRepository，
            // 构造参数（7个字段）与 AppContainer.specialtyProfileRepo 完全一致
            // （AppContainer.init() 在 ZaijianApp.onCreate() 同步执行，早于任何
            // Worker 执行，AppDatabase.getInstance() 全局单例，db 引用与容器
            // 内部持有的是同一实例），改用容器共享实例，消除重复构造。
            val repo = com.zaijian.zhoumuyun.data.AppContainer.instance.specialtyProfileRepo
            val daughterRepo = DaughterCharacterRepository(db, db.daughterCharacterDao())

            // W1-002 修复：先补发上次运行中断留下的 PENDING 播报（roundtablePosted=false）。
            // 修炼记录本身已经落库成功，只是圆桌播报/后续步骤没走完，
            // 这里只需要把播报这一步补上，不重新生成内容、不重复计数。
            try {
                repostPendingRecords(db, daughterRepo)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.w("DailyPracticeWorker", "补发未播报的修炼记录失败", e)
                // 补发失败不影响当天新的修炼流程继续执行
            }

            val activeProfiles = db.specialtyProfileDao().getAllActiveProfiles()
            for (profile in activeProfiles) {
                try {
                    runSinglePractice(db, repo, engine, daughterRepo, profile, provider)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    ZLog.w("DailyPracticeWorker", "专长 ${profile.id} 修炼失败", e)
                    // 单个专长失败不影响其余专长，继续下一个
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w("DailyPracticeWorker", "doWork failed", e)
            // 不重试，下一天自然会再跑
            //
            // C7#24 核实结论（未采纳审查报告"区分瞬时/永久故障走 Result.retry()"
            // 的建议）：这里的 retry 建议与本文件既有设计直接冲突——finally 块
            // 无条件调用 scheduleNext(hour, minute) 把下一次触发锁定在"次日同一
            // 固定时刻"，这是刻意设计（避免同一天内因异常重试导致重复修炼，
            // 见类头部注释第30-31行）。若在这里加 Result.retry()，WorkManager
            // 会按退避策略在几分钟到几小时内重新拉起本 Worker，与 scheduleNext
            // 已经排好的次日闹钟并行触发，可能导致同一天修炼两次。保持
            // Result.success() + 下一天固定时刻重跑，是唯一不产生该副作用的选择。
        } finally {
            // 无条件重新调度，即使上面发生异常也不能让每日链路断掉。
            //
            // C9#49 修复后说明：这里不再是"唯一"调度点——PracticeAlarmReceiver.onReceive
            // 现在会在广播到达的第一时间就调用 scheduleNext（不等 Worker 是否被系统启动），
            // 覆盖设备离线导致 doWork 迟迟不执行的边缘 case。此处的 finally 调用作为
            // 第二重保险保留：一旦 doWork 真正跑起来，用它读到的 hour/minute（可能来自
            // 用户设置，而 Receiver 那次用的是默认值）重新对齐一次触发时刻，不会冲突
            // ——两次调度指向同一 requestCode 的 PendingIntent，后一次覆盖前一次即可。
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
    //  A5-4 修复：Provider 为空时"连续跳过 N 次后提醒"
    // ─────────────────────────────────────────────────────────
    //
    // 实现说明（报告点名本条需要设计决策，这里给出具体选择）：
    //   - 计数存储：SharedPreferences（specialty_evolution_prefs，与每日触发时间
    //     同一份文件），key = daily_practice_skip_count。新增 Room 表来存一个
    //     整数计数器属过度设计，SharedPreferences 足够。
    //   - 提醒阈值：SKIP_NOTIFY_THRESHOLD = 3（实现选择，见类头 companion 注释）。
    //   - 提醒形式：①系统通知（NotificationPermissionUtils.safeNotify，与
    //     CiCdPipelineWorker/PregnancySettlementWorker 同款 NotificationCompat +
    //     已注册渠道）；②应用内通知（NotificationRepository.recordAppNotice，
    //     写入通知中心数据层）。两者并存：系统通知即时可见，应用内通知留档
    //     便于在通知中心回看。
    //   - 提醒后重置计数：每跳过阈值次提醒一次，避免每日刷屏。
    //   - Provider 恢复可用时计数清零（见 doWork() 调用处 resetSkipCount()）。
    private fun skipCountPrefs() =
        applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 跳过计数 +1 并返回累加后的值。 */
    private fun incrementSkipCount(): Int {
        val prefs = skipCountPrefs()
        val next = prefs.getInt(KEY_SKIP_COUNT, 0) + 1
        prefs.edit().putInt(KEY_SKIP_COUNT, next).apply()
        return next
    }

    /** 计数清零（Provider 可用，或达到阈值提醒之后调用）。 */
    private fun resetSkipCount() {
        skipCountPrefs().edit().putInt(KEY_SKIP_COUNT, 0).apply()
    }

    /**
     * Provider 缺失时的处理：累计跳过次数，达到阈值则提醒并重置。
     * 整体用 try-catch 兜底，任何环节异常都不影响 doWork 的早退语义
     * （仍 return Result.success()，finally 照常调度次日）。
     */
    private suspend fun handleProviderMissing() {
        try {
            val skipCount = incrementSkipCount()
            if (skipCount >= SKIP_NOTIFY_THRESHOLD) {
                sendSkipPromptNotification(skipCount)
                writeSkipPromptToNotificationCenter(skipCount)
                resetSkipCount()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w("DailyPracticeWorker", "处理 Provider 缺失提醒失败", e)
        }
    }

    /**
     * 发送"连续跳过"系统通知，复用已注册的 daily_practice 渠道。
     * notificationId 固定（NOTIF_ID_SKIP_PROMPT），同一提醒多次触发时
     * 后一条覆盖前一条，不在通知栏堆积。
     */
    private fun sendSkipPromptNotification(skipCount: Int) {
        val title = "每日修炼已连续跳过 $skipCount 次"
        val text = "角色每日修炼需要 API Key，已连续 $skipCount 次因未配置 Provider 而跳过。" +
            "请前往设置页配置 Provider，配置完成后次日将自动恢复修炼。"
        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        // C类审查 #47 同款：统一走权限检查入口，避免 Android 13+ 权限被拒时静默失败。
        com.zaijian.zhoumuyun.util.NotificationPermissionUtils.safeNotify(
            applicationContext, NOTIF_ID_SKIP_PROMPT, notif, "DailyPracticeWorker",
        )
    }

    /**
     * 把跳过提醒写入通知中心数据层（NotificationRepository），作为应用内
     * 通知留档。写入失败仅打日志，不影响系统通知是否已发出。
     */
    private suspend fun writeSkipPromptToNotificationCenter(skipCount: Int) {
        try {
            val title = "每日修炼已连续跳过 $skipCount 次"
            val content = "角色每日修炼需要 API Key，已连续 $skipCount 次因未配置 Provider 而跳过。" +
                "请前往设置页配置 Provider，配置完成后次日将自动恢复修炼。"
            com.zaijian.zhoumuyun.data.AppContainer.instance.notificationRepo
                .recordAppNotice(applicationContext, title, content)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w("DailyPracticeWorker", "写入跳过提醒到通知中心失败", e)
        }
    }

    // ─────────────────────────────────────────────────────────
    //  W1-002 修复：补发上次运行中断留下的 PENDING 播报
    // ─────────────────────────────────────────────────────────

    /**
     * 扫描所有 roundtablePosted=false 的修炼记录并补发圆桌播报。
     *
     * 只重放"播报"这一步——记录本身、专长计数、候选观察池在上次运行时
     * 已经成功落库（不然这条记录根本不存在/查不到 profile），不需要
     * 也不应该重新生成内容或重复计数。exportedFileJson 用不了上次生成的
     * 产出文件路径（Worker 未落盘该关联，且文件很可能已经写盘成功，重新
     * 生成反而多余），补发的播报消息不携带文件卡片，仅补齐文字播报本身，
     * 保证圆桌其他角色至少能看到这条修炼被记录下来。
     */
    private suspend fun repostPendingRecords(
        db: AppDatabase,
        daughterRepo: DaughterCharacterRepository,
    ) {
        val pending = db.practiceRecordDao().getUnpostedRecords()
        if (pending.isEmpty()) return
        for (record in pending) {
            try {
                val profile = db.specialtyProfileDao().getById(record.specialtyId)
                if (profile == null) {
                    // 专长档案已被删除（用户主动删除等），该条播报不再有意义，
                    // 直接标记已处理，避免每次运行都重新尝试同一条死记录。
                    db.practiceRecordDao().markRoundtablePosted(record.id)
                    continue
                }
                val roundtableId = db.roundtableMessageDao()
                    .findMostRecentRoundtableIdForSpeaker(profile.characterId.toString())
                if (roundtableId == null) {
                    // 角色还没有可播报的圆桌，与 postToRoundtable() 的跳过语义一致，
                    // 不标记完成，等下次角色进入过圆桌后再补发。
                    continue
                }
                val config: CharacterConfig? = DefaultCharacters.firstOrNull { it.id == profile.characterId }
                    ?: daughterRepo.getCharacterConfig(profile.characterId)
                val speakerName = config?.name ?: "角色${profile.characterId}"
                val briefText = buildString {
                    append("补发：早前在「${profile.domain}」上练了「${record.practiceTopic}」。")
                    when (record.comparisonResult) {
                        "EMERGING" -> append(record.observedTrait.ifBlank { "发现了一些新的倾向，还在观察中。" })
                        "CONFLICTING" -> append("不过这次的处理方式和之前确立的风格有些不一样，等攒够样本后会标记出来让你看看怎么定。")
                        else -> append("延续了之前的感觉，又巩固了一次。")
                    }
                }
                db.roundtableMessageDao().insert(
                    RoundtableMessageEntity(
                        id = UUID.randomUUID().toString(),
                        roundtableId = roundtableId,
                        speakerId = profile.characterId.toString(),
                        speakerName = speakerName,
                        content = briefText,
                        createdAt = System.currentTimeMillis(),
                        // v65 修复：此前硬编码 null，导致补发播报必然丢失文件卡片
                        // （即便文件本身已经安全写在磁盘上）。现在从 record 里读取
                        // 首次落库时保存的文件元数据。历史记录（v65 之前生成、
                        // 仍处于 PENDING 状态）没有这份数据，record.exportedFileJson
                        // 为 null，此时行为退化为修复前的样子（无文件卡片的纯文字
                        // 补发），不是新问题，只是没有变得更好——这是数据侧的
                        // 天然限制，无法通过 migration 回填。
                        exportedFileJson = record.exportedFileJson,
                        // v66（1.7 P3）：同理透传多文件字段。v66 之前生成的
                        // PENDING 记录这里也是 null，同样的退化逻辑。
                        exportedFilesJson = record.exportedFilesJson,
                    )
                )
                db.practiceRecordDao().markRoundtablePosted(record.id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.w("DailyPracticeWorker", "补发记录 ${record.id} 失败", e)
                // 单条补发失败不影响其余 PENDING 记录，留到下次继续尝试
            }
        }
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
        val (topic, content) = generateTodayPractice(provider, engine, profile, plan.content)
        if (content.isBlank()) return  // 生成失败，本次跳过，不留空记录

        // 3. 风格比对
        // B-3 修复：parseCandidateObservations JSON 损坏时会抛
        // SpecialtyProfileRepository.CandidatePoolCorruptedException，此前这里
        // 是裸调用，损坏会直接向上抛出、中止本次 runSinglePractice（外层
        // doWork() 的 try-catch 能兜住不至于整个 Worker 崩溃，但会导致这个
        // profile 本次修炼流程整体跳过，包括后面本不受候选池影响的生成/落库/
        // 播报步骤）。DailyPracticeWorker 拿不到 SpecialtyProfileRepository 的
        // private logCorruptedPool，这里按 repo 内部同款思路自己写一份简化版
        // 日志兜底：记录损坏的原始 JSON 以便从 logcat 恢复，以空摘要继续本次
        // 修炼（候选池会在 recordCandidateObservation 内下一次写入时被重置）。
        val candidateSummary = try {
            repo.parseCandidateObservations(profile.candidateObservationsJson)
                .joinToString("; ") { "${it.trait}（已观察${it.occurrenceCount}次）" }
        } catch (e: SpecialtyProfileRepository.CandidatePoolCorruptedException) {
            ZLog.e("DailyPracticeWorker",
                "候选池JSON损坏，将以空摘要继续本次修炼。profileId=${profile.id} " +
                "原始JSON（可从日志恢复）：${e.corruptedJson}", e)
            ""
        }
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

        // W1-002 修复：先以 roundtablePosted=false（PENDING）落库。若进程在
        // 本次落库之后、圆桌播报完成之前被杀，下次 Worker 启动时
        // repostPendingRecords() 会扫描到这条记录并补发播报，圆桌里的其他
        // 角色不会永远错过这条播报消息。
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
            roundtablePosted = false,
            // v65 修复：随记录一并保存文件元数据，repostPendingRecords() 补发时
            // 不再需要重新生成文件（也做不到，LLM 产出不可重放），直接从这里读取。
            exportedFileJson = exportMetaToJson(exportMeta),
            exportedFilesJson = exportMetaToJsonArray(exportMeta),   // v66（1.7 P3）
        )
        db.practiceRecordDao().insert(record)
        repo.recordPracticeCompleted(profile.id)

        // 5. 候选观察池更新（仅 EMERGING 结果需要）
        if (comparison.result == "EMERGING" && comparison.observedTrait.isNotBlank()) {
            updateCandidatePool(db, repo, engine, profile.id, comparison.observedTrait)
        }

        // 6. 圆桌播报 + 文件卡片。真正插入成功后才标记 roundtablePosted=true（COMPLETED）；
        //    角色暂无可播报的圆桌时保持 PENDING，等下次运行（角色进过圆桌后）自然补发。
        val posted = postToRoundtable(db, daughterRepo, profile, topic, comparison, exportMeta)
        if (posted) {
            db.practiceRecordDao().markRoundtablePosted(recordId)
        }

        // 7. 容量检查：是否触发蒸馏（异步，不阻塞本次播报；失败不影响本次修炼已落库的事实）
        try {
            DistillationTrigger.checkAndRun(db, provider, profile.id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w("DailyPracticeWorker", "蒸馏触发检查失败 specialtyId=${profile.id}", e)
        }
    }

    // ─────────────────────────────────────────────────────────
    //  生成今日练习内容
    // ─────────────────────────────────────────────────────────

    private suspend fun generateTodayPractice(
        provider: LLMProvider,
        engine: SpecialtyEvolutionEngine,
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
                    temperature = SpecialtyEvolutionConfig.PRACTICE_TEMPERATURE,
                    stream = false,
                ),
            )
            val jsonStr = engine.extractJson(response)
            val obj = JSONObject(jsonStr)
            val topic = obj.optString("topic", "今日练习").take(40)
            val content = obj.optString("content", "")
            topic to content
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
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
        // B-3 修复：同上一处调用点，裸调用会在候选池 JSON 损坏时向上抛出
        // CandidatePoolCorruptedException，中止本次候选池更新（连带影响后面的
        // CandidatePromotionChecker.checkPromotion 转正检查）。同款兜底：记录
        // 原始 JSON 后以空列表继续——recordCandidateObservation 会把这条新观察
        // 当作全新候选写入，等价于损坏前的候选池被重置，不会丢失本次观察本身。
        val existing = try {
            repo.parseCandidateObservations(profile.candidateObservationsJson)
        } catch (e: SpecialtyProfileRepository.CandidatePoolCorruptedException) {
            ZLog.e("DailyPracticeWorker",
                "候选池JSON损坏，将以空池继续本次候选观察更新。profileId=$profileId " +
                "原始JSON（可从日志恢复）：${e.corruptedJson}", e)
            mutableListOf()
        }
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
        } catch (e: Throwable) {
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
    ): Boolean {
        // 反查该角色最近活跃的圆桌（见 RoundtableMessageDao.findMostRecentRoundtableIdForSpeaker
        // 的注释：角色没有"默认圆桌"概念，没有历史记录时直接跳过播报，
        // 不强行创建一个新圆桌——这超出了本次设计范围）
        //
        // W1-002 修复：返回值表示是否真正插入了播报消息。找不到圆桌时返回
        // false——这不是"已完成播报"，调用方不应把 roundtablePosted 标记为
        // true，否则这条记录以后角色一旦有了圆桌也不会再补发。
        val roundtableId = db.roundtableMessageDao()
            .findMostRecentRoundtableIdForSpeaker(profile.characterId.toString())
            ?: return false

        val config: CharacterConfig? = DefaultCharacters.firstOrNull { it.id == profile.characterId }
            ?: daughterRepo.getCharacterConfig(profile.characterId)
        val speakerName = config?.name ?: "角色${profile.characterId}"

        val briefText = buildBriefSummary(profile.domain, topic, comparison)
        val fileJson = exportMetaToJson(exportMeta)

        db.roundtableMessageDao().insert(
            RoundtableMessageEntity(
                id = UUID.randomUUID().toString(),
                roundtableId = roundtableId,
                speakerId = profile.characterId.toString(),
                speakerName = speakerName,
                content = briefText,
                createdAt = System.currentTimeMillis(),
                exportedFileJson = fileJson,
                exportedFilesJson = exportMetaToJsonArray(exportMeta),   // v66（1.7 P3）
            )
        )
        return true
    }

    /**
     * v65 修复：ExportMeta → JSON 字符串序列化，从 postToRoundtable() 内联逻辑
     * 提取为独立函数，供 runSinglePractice()（落库 PracticeRecordEntity 时）和
     * postToRoundtable()（播报 RoundtableMessageEntity 时）共用同一份序列化，
     * 避免两处格式各写一份、后续字段增减时忘记同步。
     */
    private fun exportMetaToJson(exportMeta: ExportMeta?): String? {
        return exportMeta?.let {
            JSONObject().apply {
                put("fileName", it.fileName)
                put("mimeType", it.mimeType)
                put("sizeBytes", it.sizeBytes)
                put("absolutePath", it.absolutePath)
            }.toString()
        }
    }

    /**
     * v66（Agent附件下发方案 v2.0 · 1.7 P3）：ExportMeta → JSON 数组字符串，
     * 写入 exportedFilesJson（多文件字段）。practice 每次只产出一个文件，
     * 这里包成单元素数组——保持与 exportedFileJson 同一份 exportMeta 来源，
     * 不是重新生成，纯粹是格式包装，不会与单文件字段的值产生分歧。
     */
    private fun exportMetaToJsonArray(exportMeta: ExportMeta?): String? {
        return exportMetaToJson(exportMeta)?.let { single ->
            org.json.JSONArray().apply { put(JSONObject(single)) }.toString()
        }
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
