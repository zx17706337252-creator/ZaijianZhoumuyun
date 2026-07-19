package com.zaijian.zhoumuyun.data.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.repository.ScheduleRepository
import com.zaijian.zhoumuyun.data.repository.WorkflowRepository
import com.zaijian.zhoumuyun.util.ZLog
import org.json.JSONObject
import java.io.File

/**
 * BootReceiver — D-7 fix
 *
 * AlarmManager 的闹钟在设备重启后会全部清空。
 * 监听 BOOT_COMPLETED 广播，重启后恢复每日修炼闹钟，
 * 确保每日链路不会因重启而永久断掉。
 *
 * 只在存在 isActive=true 的专长档案时才恢复，避免用户
 * 从未使用该功能时也注册闹钟。
 *
 * 需在 AndroidManifest.xml 中声明：
 * <receiver android:name=".data.agent.BootReceiver"
 *           android:exported="true">
 *   <intent-filter>
 *     <action android:name="android.intent.action.BOOT_COMPLETED" />
 *   </intent-filter>
 * </receiver>
 *
 * 同时在 <manifest> 层添加：
 * <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        // W1-013 修复：系统时间合理性下限，2024-01-01 00:00:00 UTC 的毫秒时间戳。
        // 用于粗粒度判断设备重启后系统时钟是否已完成 NTP 同步——只要
        // System.currentTimeMillis() 还早于这个值，就可以确定时钟明显不准
        // （不依赖具体"现在"是哪一年，只需要一个远早于本项目任何可能运行时间
        // 的下限，避免每次发版都要更新这个常量）。
        private const val MIN_PLAUSIBLE_TIME_MS = 1704067200000L  // 2024-01-01T00:00:00Z

        /**
         * 批次4-3-1 修复：从 reminders/ 目录恢复所有未完成且未到期的提醒闹钟。
         *
         * AlarmManager 在设备重启后会清空所有已注册的闹钟，需要在开机时遍历持久化的
         * 提醒文件，逐条重新注册。与 ReminderTool.scheduleAlarm() 使用同一套逻辑，
         * 不重复实现 AlarmManager 调度代码。
         *
         * 批次1 1-8修复：从 private 实例方法提升为 companion object 的 internal 方法，
         * 供 ZaijianApp.onCreate() 也能调用。原代码仅在 BootReceiver 调用一次，
         * NTP 未同步命中 MIN_PLAUSIBLE_TIME_MS 早返回、或外层 catch 吞掉异常时，
         * Reminder 闹钟永久丢失。onCreate 补一次调用作为兜底。
         */
        internal fun restoreReminderAlarms(context: Context) {
            try {
                val remindersDir = File(context.filesDir, "reminders")
                if (!remindersDir.isDirectory) return

                val now = System.currentTimeMillis()
                val reminderTool = ReminderTool(context)

                remindersDir.listFiles()
                    ?.filter { it.extension == "json" }
                    ?.forEach { file ->
                        try {
                            val json = JSONObject(file.readText())
                            val isCompleted = json.optBoolean("isCompleted", false)
                            val triggerAtMs = json.optLong("triggerAtMs", 0)
                            if (!isCompleted && triggerAtMs > now) {
                                val text = json.optString("text", "提醒")
                                val id = json.optLong("id", triggerAtMs)
                                val requestCode = id.toString().hashCode()
                                val characterId = json.optInt("characterId", -1)
                                reminderTool.scheduleAlarm(requestCode, text, triggerAtMs, characterId)
                            }
                        } catch (_: Exception) {
                            // 单条提醒文件损坏不影响其他提醒的恢复
                        }
                    }
            } catch (_: Exception) {
                // 开机恢复提醒是兜底功能，失败不影响 App 正常使用
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // 性能 M5 修复：原注释认为普通 CoroutineScope(Dispatchers.IO).launch{}
        // 能"借"到系统给 BroadcastReceiver 留的约 10 秒存活窗口，这个理解不对——
        // 该窗口只在调用 goAsync() 拿到 PendingResult 并持有它时才会生效，
        // 普通协程不受这层保护，系统在 onReceive() 返回后随时可能回收进程，
        // 导致这段 DB 查询 + 闹钟恢复在低概率下被中途杀死、永远不会执行完。
        // 改为 goAsync() + finally 中 pendingResult.finish()，确保协程真正跑完
        // （或异常退出）后才释放，系统也会按预期延长这段时间的存活。
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // W1-013 修复：设备重启后 ACTION_BOOT_COMPLETED 广播触发时，
                // 系统网络时间可能尚未完成 NTP 同步（无 SIM 卡或纯 Wi-Fi 设备上
                // 尤为常见），System.currentTimeMillis() 可能返回 1970 年附近的
                // 错误时间。本 Receiver 内多处直接或间接使用当前时间写入持久化
                // 数据（ScheduleRepository.runLocalCompensation 的 nextRunAt/
                // completedAt、WorkflowRepository.markFailed 的 completedAt、
                // restoreReminderAlarms 的到期判断），一旦写入错误时间戳，
                // 定时任务调度会永久错乱（例如 nextRunAt 被设为 1970 年，
                // 该任务会被判定为"早已到期"，此后每次补跑都会立即重复执行）。
                //
                // 用一个合理下限（2024-01-01 00:00:00 UTC）做粗粒度校验：系统
                // 时间如果连这个下限都不到，说明时钟明显未同步，此时不做任何
                // 写操作，仅记录日志退出——闹钟恢复、补跑等本身都是"晚点做也
                // 不会丢"的兜底机制，用户下次打开 App 时 ZaijianApp.onCreate()
                // 会用（届时大概率已同步好的）系统时间重新走一遍同样的逻辑，
                // 不会因为这次跳过而永久错过。
                // （批次1 1-8修复：上述承诺此前对 Reminder 闹钟不成立——
                // ZaijianApp.onCreate 原本没有恢复 Reminder 的逻辑，现已补上
                // BootReceiver.restoreReminderAlarms 调用，承诺兑现。）
                if (System.currentTimeMillis() < MIN_PLAUSIBLE_TIME_MS) {
                    ZLog.w(
                        "BootReceiver",
                        "系统时间明显未同步（当前值早于 2024-01-01），跳过本次开机恢复逻辑，" +
                            "等待用户打开 App 后由 ZaijianApp.onCreate 用同步后的时间重新处理",
                    )
                    return@launch
                }

                val db = AppDatabase.getInstance(context)
                val hasActive = db.specialtyProfileDao().getAllActiveProfiles().isNotEmpty()
                if (hasActive) {
                    val prefs = context.getSharedPreferences("specialty_evolution_prefs", Context.MODE_PRIVATE)
                    val hour   = prefs.getInt("daily_practice_hour",   DailyPracticeScheduler.DEFAULT_HOUR)
                    val minute = prefs.getInt("daily_practice_minute", DailyPracticeScheduler.DEFAULT_MINUTE)
                    DailyPracticeScheduler.scheduleNext(context, hour, minute)
                }

                // 批次4-3-1 修复：恢复 Reminder 闹钟。
                // AlarmManager 在设备重启后全部清空，需要遍历 reminders/ 目录，
                // 将未完成且未到期的提醒重新注册到 AlarmManager。
                restoreReminderAlarms(context)

                // 修复（第4窗口审查报告问题2）：ScheduledJobWorker 以 OneTimeWorkRequest
                // 入队的到期任务，WorkManager 会持久化 WorkSpec 并在重启后自动恢复。
                // 此前注释称"WorkManager 队列被清空"不准确（批次1 1-5修正）——实际是
                // runLocalCompensation 与 WorkManager 自动恢复的 Worker 存在时序竞争，
                // 若 runLocalCompensation 先抢锁执行完任务但不重新入队（因 context 为
                // null 导致 finally 块的重新入队被跳过），WorkManager 恢复的 Worker 后到
                // 抢锁成功会二次执行同一任务。此处补 context 参数让重新入队逻辑生效，
                // 并由 ScheduledJobWorker.doWork 的 nextRunAt 校验做防御纵深。
                try {
                    val scheduleRepository = ScheduleRepository(
                        scheduledJobDao = db.scheduledJobDao(),
                        jobResultDao    = db.jobResultDao(),
                        db              = db,
                        context         = context.applicationContext,  // 批次1 1-5修复：补 context，让 runLocalCompensation 的 finally 块重新入队逻辑生效
                    )
                    scheduleRepository.runLocalCompensation()
                } catch (e: Exception) {
                    // 与其余开机恢复逻辑一致：单次失败不影响 App 后续正常使用，
                    // 用户进入 App 后 ZaijianApp.onCreate 仍会再次兜底执行。
                }

                // 修复（第4窗口审查报告问题2）：workflow_jobs 表中残留 status=RUNNING 的任务，
                // 是设备重启导致 WorkManager 队列被清空、原本正在执行的 Worker 再也不会被触发
                // 造成的（区别于上面 ScheduledJobWorker 走的到期轮询表，这里是"执行到一半被打断"）。
                //
                // 核实结论：workflow_jobs 表被两类 Worker 共用，处理方式必须区分——
                //   · WorkflowJobWorker（workflow_start 创建）：CoroutineWorker 的 inputData
                //     只有 jobId 一项，WorkflowEngine.run() 每次都从数据库回放状态续跑，
                //     不依赖任何已丢失的输入，可以直接安全地重新入队。
                //   · CiCdPipelineWorker（cicd_start 创建，goal 固定以 "CI/CD: " 开头）：
                //     inputData 携带 files_json/message 等原始提交内容。WorkManager
                //     会持久化 WorkSpec（含 inputData）并在重启后自动恢复，故此前
                //     注释称"inputData 已永久丢失"不准确（批次2 2-2附带修正）。
                //     但 CiCdPipelineWorker 非幂等——它内部按步骤执行 git commit →
                //     编译 → 下载 APK，重启后重新入队会从头跑，对已 commit 过的
                //     仓库产生重复提交、浪费 CI 配额。故 markFailed 让用户/角色
                //     感知到这次编译流程因重启中断、需重新发起，比无声重复执行更安全。
                // W1-012 修复：原先整个 for 循环共享外层唯一一个 try-catch——
                // 如果某条 job 的 markFailed/enqueueWorkflow 抛异常（如 DB 写入
                // 失败、WorkManager 调度异常），会直接中断循环，导致排在它后面
                // 的 job 完全不会被处理，且没有任何日志能区分"这批 RUNNING job
                // 里究竟哪些已经处理、哪些还卡着"——只能靠人工逐条去查 workflow_jobs
                // 表状态排查。
                //
                // 改为单条 job 独立 try-catch：一条异常只影响它自己，不阻塞同批
                // 其余 job 的处理；同时收集处理成功的 job ID 列表，处理完毕后统一
                // 打一条汇总日志（成功数/总数 + 具体 ID），后续如果用户反馈某个
                // 编译任务在重启后状态异常，可以直接从日志里查到当时是否已被
                // BootReceiver 处理过，不需要额外埋点。
                try {
                    val workflowRepo = WorkflowRepository(
                        db = db,
                        workflowJobDao = db.workflowJobDao(),
                        workflowStepResultDao = db.workflowStepResultDao(),
                    )
                    val runningJobs = db.workflowJobDao().findAllRunning()
                    val processedJobIds = mutableListOf<String>()
                    for (job in runningJobs) {
                        try {
                            if (job.goal.startsWith("CI/CD: ")) {
                                workflowRepo.markFailed(job.id, "设备重启导致编译流水线中断，请重新发起")
                            } else {
                                WorkManagerScheduler.enqueueWorkflow(context, job.id)
                            }
                            processedJobIds.add(job.id)
                        } catch (e: Exception) {
                            // 单条 job 处理失败不影响同批其他 job；未处理的 RUNNING job
                            // 会在用户下次查看任务状态时仍显示为进行中，不会造成数据损坏，
                            // 可后续人工介入，日志里能看出它不在本次已处理列表中。
                            ZLog.w("BootReceiver", "开机恢复 workflow job 处理失败 jobId=${job.id}", e)
                        }
                    }
                    if (runningJobs.isNotEmpty()) {
                        ZLog.d(
                            "BootReceiver",
                            "开机恢复 workflow job：共 ${runningJobs.size} 条 RUNNING，" +
                                "成功处理 ${processedJobIds.size} 条，已处理 jobId=$processedJobIds",
                        )
                    }
                } catch (e: Exception) {
                    // 外层兜底：findAllRunning() 本身查询失败等场景，此时连"哪些 job
                    // 需要处理"都不知道，只能整体跳过，用户下次查看任务状态时仍显示
                    // 为进行中，不会造成数据损坏，可后续人工介入。
                    ZLog.w("BootReceiver", "开机恢复 workflow job 整体失败", e)
                }
            } catch (_: Exception) {
                // 静默失败：开机恢复闹钟是兜底功能，单次失败不影响 App 后续正常使用，
                // 用户进入 App 后 ZaijianApp.onCreate 仍会按需重新调度。
            } finally {
                pendingResult.finish()
            }
        }
    }

    // restoreReminderAlarms 定义在上方 companion object 中（与 MIN_PLAUSIBLE_TIME_MS 同级），
    // 批次1 1-8修复：从 private 实例方法提升为 companion object 的 internal 方法，
    // 供 ZaijianApp.onCreate() 也能调用作为兜底。
}
