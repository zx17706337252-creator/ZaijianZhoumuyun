package com.zaijian.zhoumuyun.ui.viewmodel

import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.AgentRelationEntity
import com.zaijian.zhoumuyun.data.manager.DaughterCharacterGenerator
import com.zaijian.zhoumuyun.data.manager.DaughterIdAllocator
import com.zaijian.zhoumuyun.data.model.toCharacterIdentityEntity
import com.zaijian.zhoumuyun.data.provider.LLMConfig
import com.zaijian.zhoumuyun.data.provider.LLMMessage
import com.zaijian.zhoumuyun.data.provider.ProviderManager
import com.zaijian.zhoumuyun.data.repository.DaughterCharacterRepository
import com.zaijian.zhoumuyun.data.repository.IdentityRepository
import com.zaijian.zhoumuyun.data.repository.MenstrualCycleRepository
import com.zaijian.zhoumuyun.util.ZLog

/**
 * 封装 DaughterCharacterGenerator 的构造，特别是 onIdentityRegister 回调中的
 * 4 步写入 + 逐级回滚逻辑（A-6 修复）。从 ChatViewModel 拆出以减少其行数。
 */
class DaughterRegistrationHelper(
    private val daughterRepo: DaughterCharacterRepository,
    private val daughterIdAllocator: DaughterIdAllocator,
    private val identityRepo: IdentityRepository,
    private val cycleRepository: MenstrualCycleRepository,
    private val db: AppDatabase,
) {
    fun createGenerator(): DaughterCharacterGenerator = DaughterCharacterGenerator(
        repository = daughterRepo,
        llmCall = { sys, user ->
            val provider = ProviderManager.instance.activeProvider
                ?: error("D4 生成器：无可用 LLM Provider")
            val cfg = LLMConfig(
                model = "", maxTokens = 4000, temperature = 0.9f, stream = false,
            )
            val resp = StringBuilder()
            provider.chat(
                listOf(LLMMessage(role = "user", content = user)),
                sys,
                cfg,
            ).collect { resp.append(it) }
            resp.toString()
        },
        onIdentityRegister = { daughterData ->
            // A-6 修复：女儿注册时同步插入 agent_relation 初始行。
            // 5 步写入：分配 daughterId → 写 character_identity → 插 agent_relation →
            // 回填 daughter_character → 初始化周期锚点。
            // 用 step 跟踪已成功完成到第几步，失败时按反序只回滚已完成的步骤。
            //
            // E3 复核修复（原实现两处问题）：
            // 1）原嵌套 try/catch 版本里，resetAnchorToToday（第5步）失败时只回滚了
            //    agent_relation + character_identity，漏回滚第4步 updateDaughterCharacterId
            //    ——daughter_character.daughterCharacterId 会残留指向一个已被删除的
            //    character_identity 行。现在改为单层 try + step 计数，每一步失败都能
            //    正确反查到"已经成功到第几步"，回滚不再漏项。
            // 2）原实现每层嵌套都有自己的 catch(Exception)，同一个异常从内向外传播时
            //    会被多层 catch 各自捕获一次、各自重复执行一遍已经做过的回滚（虽然对
            //    已删除的行再删一次不会报错，但是纯浪费）。改为单层 try 后，每个异常
            //    只会被捕获一次，不再有重复回滚。
            val allocatedId = daughterIdAllocator.allocate()
            val identityEntity = daughterData.toCharacterIdentityEntity(allocatedId)

            var step = 0  // 1=identity已写  2=agent_relation已写  3=daughter_character已回填
            try {
                identityRepo.upsert(identityEntity)
                step = 1
                db.agentRelationDao().insert(
                    AgentRelationEntity(
                        daughterId        = allocatedId,
                        motherCharacterId = daughterData.motherCharacterId,
                    )
                )
                step = 2
                daughterRepo.updateDaughterCharacterId(
                    motherCharacterId  = daughterData.motherCharacterId,
                    daughterCharacterId = allocatedId,
                )
                step = 3
                // P0-1 修复：女儿角色注册时必须同步初始化周期锚点，
                // 否则受孕弹窗链路被静默阻断。用 resetAnchorToToday 而非
                // initIfAbsent：后者只遍历写死的母亲映射表，不认识动态分配的女儿 characterId。
                cycleRepository.resetAnchorToToday(allocatedId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 取消场景下回滚调用本身也可能因协程已取消而抛出，用 runCatching
                // 兜底，避免回滚失败掩盖了原始的取消异常。
                if (step >= 3) runCatching {
                    daughterRepo.clearDaughterCharacterIdForRollback(daughterData.motherCharacterId, allocatedId)
                }
                if (step >= 2) runCatching { db.agentRelationDao().deleteByDaughterId(allocatedId) }
                if (step >= 1) runCatching { db.characterIdentityDao().deleteForRollback(allocatedId) }
                throw e
            } catch (e: Exception) {
                if (step >= 3) {
                    daughterRepo.clearDaughterCharacterIdForRollback(daughterData.motherCharacterId, allocatedId)
                }
                if (step >= 2) db.agentRelationDao().deleteByDaughterId(allocatedId)
                if (step >= 1) db.characterIdentityDao().deleteForRollback(allocatedId)
                throw e
            }
            ZLog.i("DaughterRegistrationHelper", "A-6: agent_relation 初始行已插入 daughterId=$allocatedId")
        },
    )
}
