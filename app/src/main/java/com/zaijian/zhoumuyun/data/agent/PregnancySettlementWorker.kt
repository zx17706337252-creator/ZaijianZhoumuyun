package com.zaijian.zhoumuyun.data.agent

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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
 */
class PregnancySettlementWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            val db = AppDatabase.getInstance(applicationContext)
            settleAndRecord(
                pregnancyRepo = PregnancyRepository(db.pregnancyDao()),
                memoryRepo    = MemoryRepository(db.memoryDao(), db.memoryCandidateDao()),
                daughterRepo  = DaughterCharacterRepository(db.daughterCharacterDao()),
            )
        } catch (e: Exception) {
            ZLog.w("PregnancySettlementWorker", "doWork failed", e)
            // 不重试：下一个 12h 周期 / 下次进入聊天页会自然再检查一次
        }
        return Result.success()
    }

    companion object {
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
         */
        suspend fun settleAndRecord(
            pregnancyRepo: PregnancyRepository,
            memoryRepo:    MemoryRepository,
            daughterRepo:  DaughterCharacterRepository,
        ) {
            settlementMutex.withLock {
                val settled = pregnancyRepo.settleDueDeliveries()
                for (record in settled) {
                    try {
                        writeEternalMemoryForBirth(record, memoryRepo, daughterRepo)
                    } catch (e: Exception) {
                        // 单条记忆写入失败不影响其余结算记录已经落库的事实
                        // （BirthRecord/pregnancy_state 清零已在 completeBirth 的原子事务内完成，
                        // 这里失败的只是"锦上添花"的记忆感知层）。
                        ZLog.w(
                            "PregnancySettlementWorker",
                            "写入生育长期记忆失败 characterId=${record.characterId}",
                            e,
                        )
                    }
                }
            }
        }

        private suspend fun writeEternalMemoryForBirth(
            record: BirthRecord,
            memoryRepo: MemoryRepository,
            daughterRepo: DaughterCharacterRepository,
        ) {
            val config: CharacterConfig? = DefaultCharacters.firstOrNull { it.id == record.characterId }
                ?: daughterRepo.getCharacterConfig(record.characterId)
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
    }
}
