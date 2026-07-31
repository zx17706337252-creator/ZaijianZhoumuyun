package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.db.entity.ChainRunEntity
import com.zaijian.zhoumuyun.data.db.entity.ChainRunStatus
import com.zaijian.zhoumuyun.data.repository.ChainRunRepository
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/**
 * 灵活自动化编排 · 触发匹配器（§6 ChainTriggerMatcher）
 *
 * ═══════════════════════════════════════════════════════════════
 * 职责：常驻订阅 [EventBus.events]，对每个到达的事件，查所有
 * `triggerType=EVENT` 且 `triggerEventName` 匹配、`enabled=true` 的
 * [ChainDefinitionEntity][com.zaijian.zhoumuyun.data.db.entity.ChainDefinitionEntity]，
 * 命中则创建 [ChainRunEntity]（`currentNodeIndex=0`，`context` 里预填事件 `payload`）
 * 并调用 [ChainEngine.advance] 启动执行。
 *
 * ── §6.1 挂载位置（必须在 Step 4 阶段确认）──────────────────
 * **必须挂在 [AppContainer] / `appScope` 上，不能挂在 `ChatViewModel.viewModelScope` 上。**
 * ChatViewModel 按角色实例化、随聊天页存续，若挂错会导致用户退出聊天页后事件匹配静默失效。
 * AppContainer 是 App 级单例（`private constructor` + `companion object`），
 * 与 `sharedPresenceEngine` 同一挂载层级——全程只有一份订阅，与角色切换、页面生死完全无关。
 *
 * ── §11.12 项目级链条 ──────────────────────────────────────
 * 匹配时除了查该事件对应角色（`characterId`）的定义，也一并匹配 `characterId=-1`
 * 的项目级定义——一个事件可以同时命中角色专属链条和项目级链条。
 *
 * ── §11.1 事件落盘兜底 ─────────────────────────────────────
 * [processPendingEvents] 在 App 重启时（`ZaijianApp.onCreate()`）查所有
 * `processed=false` 的 [PendingEventEntity][com.zaijian.zhoumuyun.data.db.entity.PendingEventEntity]，
 * 逐条重放给 [handleEvent]，成功后标记 `processed=true`。
 *
 * ── 可测试性 ────────────────────────────────────────────────
 * 只依赖 [ChainRunRepository]（接口）+ [ChainEngine] + [ChainEngineDeps]（接口），
 * 测试时传入 [FakeChainRunRepository][com.zaijian.zhoumuyun.data.agent.FakeChainRunRepository]
 * + FakeChainEngineDeps + ChainEngine 实例即可在纯 JVM 环境验证。
 * ═══════════════════════════════════════════════════════════════
 *
 * @param repository   数据访问层（生产环境用 ChainRunRepositoryImpl，测试用 FakeChainRunRepository）
 * @param chainEngine  节点解释器，创建 ChainRunEntity 后调用 advance() 启动
 * @param deps         ChainEngine 的外部依赖（scheduleResume / runAction / runCheckTool）
 */
class ChainTriggerMatcher(
    private val repository: ChainRunRepository,
    private val chainEngine: ChainEngine,
    private val deps: ChainEngineDeps,
) {

    /**
     * 启动常驻订阅。在 [AppContainer] 初始化时用 `appScope.launch` 调用。
     *
     * 每个事件在独立的 try-catch 中处理——单条事件处理异常不会终止整个订阅。
     */
    fun start(scope: CoroutineScope) {
        scope.launch {
            EventBus.events.collect { event ->
                try {
                    handleEvent(event)
                    // A1-1 修复：App 存活时即时处理成功后，必须同步标记对应的
                    // PendingEventEntity 为 processed=true——否则该记录永远停留在
                    // processed=false，重启后 processPendingEvents() 会把"已经被
                    // 实时处理过"的事件再重放一次，重复创建 ChainRunEntity 并重复
                    // 执行整条链条。event.persistedId 为空说明该事件本身不走
                    // §11.1 落盘兜底（纯瞬时事件），无需标记。
                    event.persistedId?.let { id ->
                        try {
                            repository.markPendingEventProcessed(id)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            // 标记失败不影响本次已完成的实时处理，仅记录日志——
                            // 该事件会在下次重启时被重放并重复执行一次链条，这是
                            // 标记失败这一次性异常的已知代价，优先保证不吞掉取消
                            // 信号、不中断订阅循环。
                            ZLog.e(TAG, "标记事件已处理失败: id=$id, eventName=${event.name}", e)
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e  // 结构化并发约定：取消信号不能被吞掉
                } catch (e: Throwable) {
                    ZLog.e(TAG, "处理事件失败: ${event.name} (characterId=${event.characterId})", e)
                }
            }
        }
    }

    /**
     * 处理单个事件：查匹配的链条定义，为每个命中创建 ChainRunEntity 并启动执行。
     *
     * §11.12：一个事件可以同时命中角色专属链条（characterId 匹配）和项目级链条（characterId=-1）。
     */
    suspend fun handleEvent(event: AppEvent) {
        val definitions = repository.findDefinitionsByTriggerEvent(event.name)
        if (definitions.isEmpty()) return

        for (def in definitions) {
            // §11.12：characterId=-1 的项目级定义匹配所有事件，否则要求角色 ID 匹配
            if (def.characterId != event.characterId && def.characterId != -1) continue

            val runId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()

            // 将事件 payload 预填入 context，供后续 ConditionEvaluator 读取
            val context = JSONObject().apply {
                for ((key, value) in event.payload) {
                    when (value) {
                        is String -> put(key, value)
                        is Int -> put(key, value)
                        is Long -> put(key, value)
                        is Double -> put(key, value)
                        is Boolean -> put(key, value)
                        is JSONObject -> put(key, value)
                        is org.json.JSONArray -> put(key, value)
                        null -> put(key, JSONObject.NULL)
                        else -> put(key, value.toString())
                    }
                }
            }.toString()

            // §11.6 deadlineAt 默认 7 天（远大于 WorkflowEngine 的 10 分钟上限）
            val dayMs = 24L * 60 * 60 * 1000
            val run = ChainRunEntity(
                id = runId,
                chainDefId = def.id,
                characterId = def.characterId,
                status = ChainRunStatus.RUNNING,
                currentNodeIndex = 0,
                context = context,
                deadlineAt = now + 7 * dayMs,
                startedAt = now,
                updatedAt = now,
            )

            repository.insertRun(run)

            // 启动执行——advance() 内部会 claimRun/releaseLock
            try {
                chainEngine.advance(runId, repository, deps)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.e(TAG, "链条启动失败: runId=$runId, chainDefId=${def.id}", e)
            }
        }
    }

    /**
     * §11.1 事件落盘兜底：App 重启时重放未处理的 PendingEventEntity。
     *
     * 在 `ZaijianApp.onCreate()` 里调用（不仅是开机，因为 App 也可能被系统杀后
     * 由用户手动重新打开，不一定经过 BOOT_COMPLETED）。查所有 `processed=false`
     * 的记录，逐条重放给 [handleEvent]，成功后标记 `processed=true`。
     *
     * 单条重放异常不阻塞其余——与 BootReceiver"单条 job 独立 try-catch"写法对齐。
     */
    suspend fun processPendingEvents() {
        val pending = repository.findUnprocessedPendingEvents()
        if (pending.isEmpty()) return

        ZLog.d(TAG, "重放 ${pending.size} 条未处理事件")

        for (event in pending) {
            try {
                // 从 payloadJson 反序列化为 AppEvent
                val payload = try {
                    val json = JSONObject(event.payloadJson)
                    val map = mutableMapOf<String, Any?>()
                    for (key in json.keys()) {
                        map[key] = when (val v = json.get(key)) {
                            is JSONObject -> v
                            is org.json.JSONArray -> v
                            else -> v
                        }
                    }
                    map
                } catch (e: Exception) {
                    emptyMap()
                }

                val appEvent = AppEvent(
                    name = event.eventName,
                    characterId = event.characterId,
                    payload = payload,
                )

                handleEvent(appEvent)
                repository.markPendingEventProcessed(event.id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.e(TAG, "重放事件失败: id=${event.id}, eventName=${event.eventName}", e)
                // 标记为已处理避免反复重试同一条坏数据——对照 BootReceiver 同款策略
                repository.markPendingEventProcessed(event.id)
            }
        }
    }

    companion object {
        private const val TAG = "ChainTriggerMatcher"
    }
}
