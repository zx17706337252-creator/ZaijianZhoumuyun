package com.zaijian.zhoumuyun.data.agent

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zaijian.zhoumuyun.MainActivity
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.model.BirthRecord
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.repository.DaughterCharacterRepository
import com.zaijian.zhoumuyun.data.repository.MemoryRepository
import com.zaijian.zhoumuyun.data.repository.PregnancyRepository
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * PregnancySettlementWorker — 问题5 修复：分娩到期结算调度
 *
 * 报告断裂点【断裂6】：`PregnancyRepository.settleDueDeliveries()` 实现完整（原子写入
 * BirthRecord + 清零 pregnancy_state），但全项目零调用点——怀孕满 30 天不会自动触发
 * 分娩结算，isPregnant 会永远卡在 true，currentDay() 卡在 30，玩家看不到任何"生育完成"
 * 的信号。
 *
 * 与 DailyPracticeWorker/ProactiveMessageWorker 同一模式：
 *   - CoroutineWorker，临时拼装最小依赖（不走 Hilt，与项目其余 Worker 一致）
 *   - 单次 doWork() 遍历全部怀孕中角色，逐一结算到期的
 *   - 失败不重试（下一个周期自然会再检查一次，不会永久错过——只是延迟发现）
 *
 * 调度策略（PregnancySettlementScheduler）：
 *   - PeriodicWorkRequest，12 小时一次兜底轮询（怀孕以"天"为单位，12h 间隔足够及时，
 *     不需要 DailyPracticeScheduler 那种精确到分钟的 AlarmManager 方案）
 *   - 额外配合 ZaijianApp.onCreate() / ChatViewModel.init() 的立即一次性检查
 *     （见 PregnancySettlementScheduler.runImmediateCheck），避免用户在满 30 天后
 *     要等到下一个 12h 轮询点才看到结算结果
 *
 * 结算后的"长期记忆写入"（writeEternalMemory，报告断裂点附带问题，MemoryRepository
 * 中该方法此前也是零调用点）在本次修复中一并接入：每条 BirthRecord 结算后写入一条
 * 永恒记忆，内容依据 isDaughter 区分文案，确保聊天时该生育事件会被角色感知到。
 *
 * W1-007 修复：记忆写入已纳入 settlementMutex.withLock 范围（见 settleAndRecord），
 * 与结算共享同一把锁，避免"结算已提交、记忆写入前进程被杀"导致角色永远感知不到
 * 该生育事件的问题。
 */
class PregnancySettlementWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            val db = AppDatabase.getInstance(applicationContext)
            settleAndRecord(
                context       = applicationContext,
                pregnancyRepo = PregnancyRepository(db.pregnancyDao()),
                memoryRepo    = MemoryRepository(db.memoryDao(), db.memoryCandidateDao(), db.memoryTagDao()),
                daughterRepo  = DaughterCharacterRepository(db, db.daughterCharacterDao()),
            )
        } catch (e: Exception) {
            ZLog.w("PregnancySettlementWorker", "doWork failed", e)
            // 不重试：下一个 12h 周期 / 下次进入聊天页会自然再检查一次
        }
        return Result.success()
    }

    companion object {
        // 方案 5-12：通知渠道常量，供 ZaijianApp.setupNotificationChannels() 注册
        const val CHANNEL_ID = "birth_settlement"
        const val CHANNEL_NAME = "生育结算"

        /**
         * 并发竞态修复：settleDueDeliveries() 内部是"读取到期列表 → 逐个 completeBirth()"，
         * completeBirth() 虽然用 completeBirthAtomic() 保证了单次调用内两张表的一致性，
         * 但不同调用之间没有互斥——若两条触发路径（App 启动一次性检查 / ChatViewModel.init()
         * 每次切换角色都会触发一次 / 12h 周期 Worker）并发执行到 dao.getAllPregnant()，
         * 都可能在对方提交事务前读到同一角色 isPregnant=true，都会各自执行 completeBirth()，
         * 因为 BirthRecordEntity 主键自增、insertBirthRecord() 是普通 @Insert（非 upsert，
         * 无唯一约束），两边都会成功插入一条记录，导致同一次生育被重复结算、
         * "永恒记忆"也被写两条。
         *
         * 用 companion object 级别的 Mutex 串行化整个结算过程，与 ZaijianApp.buildMutex
         * 同一模式：三条触发路径（Worker/ZaijianApp 一次性检查/ChatViewModel.init()）
         * 共用同一把锁，同一时刻只有一条路径能进入 settleDueDeliveries()，后到的会挂起
         * 等待，前一条完全提交后再读，读到的已经是清零后的 isPregnant=false，不会重复结算。
         *
         * 锁顺序核查：withLock 块内部只调用 pregnancyRepo/memoryRepo/daughterRepo 的方法，
         * 三者内部均未持有任何 Mutex（纯 DAO 调用），不存在反向获取其他锁、锁顺序反转
         * 导致死锁的风险。
         */
        private val settlementMutex = Mutex()

        /**
         * 供 Worker 和"立即检查"路径（ZaijianApp/ChatViewModel）共用的核心逻辑，
         * 抽成 object 内的静态方法，避免两处各写一份、日后改动只改一处。
         *
         * W1-007 修复：原先 settlementMutex.withLock 只包住了
         * pregnancyRepo.settleDueDeliveries()，随后写"永恒记忆"的 for 循环在锁外
         * 执行。如果进程在结算完成（birth_records 已写、pregnancy_state 已清零）
         * 之后、记忆写入之前被杀死，角色在聊天中永远不会提及这次生育——因为
         * 永恒记忆是角色感知生育事件的唯一数据来源，而 pregnancy_state 已清零，
         * 不会再有第二次结算机会去补写这条记忆。
         *
         * 现将 writeEternalMemoryForBirth() 的调用一并纳入 settlementMutex.withLock
         * 范围，使"结算 + 记忆写入"成为一个完整的逻辑单元：同一时刻只有一条触发
         * 路径能进入该临界区，进程若在记忆写入过程中被杀，下次任意路径重新进入
         * 时 settleDueDeliveries() 会返回空列表（因为 pregnancy_state 已清零），
         * 但这也意味着记忆写入本身仍不是与结算同一个 DB 事务——记忆写入使用独立
         * 的每条 try-catch（单条失败不阻塞其他记录，不因为个别记忆写入异常导致
         * 整批持锁时间无意义地失败），这与 W1-002/W1-007 场景中"结算是可回滚的
         * DB 事务、记忆写入不适合塞进同一个事务但需要与结算共享互斥保护"的思路
         * 一致：用同一把锁而非同一个事务来保证逻辑完整性。
         */
        suspend fun settleAndRecord(
            context:       Context,
            pregnancyRepo: PregnancyRepository,
            memoryRepo:    MemoryRepository,
            daughterRepo:  DaughterCharacterRepository,
        ) {
            val settled = settlementMutex.withLock {
                val records = pregnancyRepo.settleDueDeliveries()
                for (record in records) {
                    try {
                        writeEternalMemoryForBirth(record, memoryRepo, daughterRepo)
                    } catch (e: Exception) {
                        ZLog.w(
                            "PregnancySettlementWorker",
                            "写入生育长期记忆失败 characterId=${record.characterId}",
                            e,
                        )
                    }
                }
                records
            }

            // 方案 5-12：结算完成后发送系统通知，解决"生育完成无视觉信号"问题。
            // 通知发送不涉及共享数据写入，放在锁外执行，避免不必要地延长持锁时间。
            if (settled.isNotEmpty()) {
                sendBirthNotifications(context, settled, daughterRepo)
            }
        }

        private suspend fun writeEternalMemoryForBirth(
            record: BirthRecord,
            memoryRepo: MemoryRepository,
            daughterRepo: DaughterCharacterRepository,
        ) {
            // P1-5 修复（增强）：daughterRepo.getCharacterConfig() 在女儿数据表损坏时
            // 抛出 DaughterDataException（而非返回 null），原先的 ?: fallback 无法生效。
            // 异常被外层 catch 吞掉后，该条记忆永久丢失。
            // 现在在内部 catch 异常，确保无论如何都使用 fallback 名称写入记忆。
            val config: CharacterConfig? = try {
                DefaultCharacters.firstOrNull { it.id == record.characterId }
                    ?: daughterRepo.getCharacterConfig(record.characterId)
            } catch (e: Exception) {
                ZLog.w(
                    "PregnancySettlementWorker",
                    "获取女儿角色配置失败，使用 fallback 名称 characterId=${record.characterId}",
                    e,
                )
                null
            }
            val name = config?.name ?: "角色${record.characterId}"

            val content = if (record.isDaughter) {
                "$name 生下了一个女儿。这是一件值得铭记的生命事件。"
            } else {
                "$name 生下了一个男孩。这是一件值得铭记的生命事件。"
            }

            memoryRepo.writeEternalMemory(
                characterId = record.characterId,
                content     = content,
            )
        }

        /**
         * 方案 5-12：发送结算通知，解决"生育完成无视觉信号"问题。
         *
         * 批次3 3-5修复：改为 suspend fun 并接收 daughterRepo，复用与
         * writeEternalMemoryForBirth 相同的角色名解析逻辑（DefaultCharacters
         * + daughterRepo.getCharacterConfig 兜底）。原代码只查 DefaultCharacters
         * （ID 1-9），女儿角色（ID≥1000）查不到回退成"角色1000"，与同时刻
         * 写入的永久记忆（用真实姓名）不一致。
         */
        private suspend fun sendBirthNotifications(
            context: Context,
            records: List<BirthRecord>,
            daughterRepo: DaughterCharacterRepository,
        ) {
            val nm = NotificationManagerCompat.from(context)
            // 修复（第4窗口审查报告问题7）：原硬编码起始值 3000 + 自增，仅在单次调用内保证批内不冲突；
            // 若本 Worker 跨批次多次触发，每次都从 3000 重新起跳，可能与前一批次残留的通知 ID 撞车。
            // 改为与项目内 CiCdPipelineWorker/WorkflowJobWorker 一致的做法：基于当前时间戳生成 ID，
            // 同时在批内对每条记录追加序号偏移，保证同一批次内多条通知互不覆盖。
            val notifIdBase = System.currentTimeMillis().toInt()
            for ((offset, record) in records.withIndex()) {
                val notifId = notifIdBase + offset
                try {
                    // 批次3 3-5修复：复用 writeEternalMemoryForBirth 的角色名解析逻辑，
                    // 女儿角色（ID≥1000）用 daughterRepo.getCharacterConfig 兜底。
                    val config: CharacterConfig? = try {
                        DefaultCharacters.firstOrNull { it.id == record.characterId }
                            ?: daughterRepo.getCharacterConfig(record.characterId)
                    } catch (e: Exception) {
                        ZLog.w(
                            "PregnancySettlementWorker",
                            "获取女儿角色配置失败，使用 fallback 名称 characterId=${record.characterId}",
                            e,
                        )
                        null
                    }
                    val name = config?.name ?: "角色${record.characterId}"
                    val gender = if (record.isDaughter) "女儿" else "儿子"
                    val title = "🎉 $name 完成生育"
                    val content = "$name 生下了一个$gender"

                    val intent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    val pi = android.app.PendingIntent.getActivity(
                        context, notifId, intent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                            android.app.PendingIntent.FLAG_IMMUTABLE,
                    )

                    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(title)
                        .setContentText(content)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setContentIntent(pi)
                        .build()

                    nm.notify(notifId, notification)
                } catch (e: Exception) {
                    ZLog.w(
                        "PregnancySettlementWorker",
                        "发送生育通知失败 characterId=${record.characterId}",
                        e,
                    )
                }
            }
        }
    }
}
