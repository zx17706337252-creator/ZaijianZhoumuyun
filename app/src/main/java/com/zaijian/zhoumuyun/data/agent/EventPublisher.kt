package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.db.entity.PendingEventEntity
import com.zaijian.zhoumuyun.data.repository.ChainRunRepository
import com.zaijian.zhoumuyun.util.ZLog
import org.json.JSONObject
import java.util.UUID

/**
 * 灵活自动化编排 · 事件发布器（§6 + §11.1）
 *
 * 将"写 PendingEventEntity + EventBus.emit()"两步合为一个方法调用，
 * 使业务代码只需一行 `eventPublisher.publish(event)` 即可完成事件发布。
 *
 * §11.1：对来自持久化业务操作的事件（如消息已落库、心情值已写入 Room），
 * 先写一条轻量 PendingEventEntity（processed=false），再 EventBus.emit()——
 * 内存 emit 用于"App 存活时的即时响应"，落盘记录用于兜底。
 *
 * 对纯瞬时事件（如"用户正在输入"），调用方应直接使用 [EventBus.emit]，
 * 不需要落盘——落不落盘取决于事件语义，不是每个 emit 都要写库。
 *
 * @param repository 数据访问层，用于写入 PendingEventEntity
 */
class EventPublisher(
    private val repository: ChainRunRepository,
) {

    /**
     * 发布一个持久化事件：先写 PendingEventEntity，再 EventBus.emit()。
     *
     * 适用于来自持久化业务操作的事件（消息发送、任务完成、状态更新等）。
     * App 被系统杀掉后，processPendingEvents() 会从磁盘重放。
     */
    suspend fun publish(event: AppEvent) {
        val payloadJson = try {
            val json = JSONObject()
            for ((key, value) in event.payload) {
                when (value) {
                    is String -> json.put(key, value)
                    is Int -> json.put(key, value)
                    is Long -> json.put(key, value)
                    is Double -> json.put(key, value)
                    is Boolean -> json.put(key, value)
                    is JSONObject -> json.put(key, value)
                    is org.json.JSONArray -> json.put(key, value)
                    null -> json.put(key, JSONObject.NULL)
                    else -> json.put(key, value.toString())
                }
            }
            json.toString()
        } catch (e: Exception) {
            ZLog.e(TAG, "序列化事件 payload 失败: ${event.name}", e)
            "{}"
        }

        repository.insertPendingEvent(
            PendingEventEntity(
                id = UUID.randomUUID().toString(),
                eventName = event.name,
                characterId = event.characterId,
                payloadJson = payloadJson,
                processed = false,
                createdAt = System.currentTimeMillis(),
            )
        )

        EventBus.emit(event)
    }

    companion object {
        private const val TAG = "EventPublisher"

        /**
         * 静态发布入口：供尚未注入 [EventPublisher] 实例的既有 Repository
         * （`MessageRepository`/`TaskRepository`/`CharacterStateRepository` 等）调用，
         * 不需要改动它们的构造函数签名，也不受 `AppContainer` 属性声明顺序影响。
         *
         * 惰性取 `AppContainer.instance.eventPublisher`——这几个 Repository 在
         * `AppContainer` 主构造体中的实例化顺序早于 `eventPublisher` 字段声明处，
         * 无法在构造时直接注入；调用发生在 App 运行期（消息发送/任务完成等业务时机），
         * 此时 `AppContainer.instance` 必然已完成初始化，惰性取值是安全的。
         *
         * 对照 `EventBus`（object 单例）的既有引用风格，不引入新的 DI 范式。
         *
         * §11.1：仍然是"先写 PendingEventEntity 再 EventBus.emit()"，与实例方法
         * [publish] 语义完全一致，只是取 repository 的方式不同。
         */
        suspend fun publishPersistent(event: AppEvent) {
            com.zaijian.zhoumuyun.data.AppContainer.instance.eventPublisher.publish(event)
        }
    }
}
