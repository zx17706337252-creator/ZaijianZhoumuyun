package com.zaijian.zhoumuyun.data.agent

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 灵活自动化编排 · 进程内事件总线（§6）
 *
 * 基于 [MutableSharedFlow]，纯内存、无外部消息队列依赖。
 * [ChainTriggerMatcher] 常驻订阅 [events]，对每个到达的事件做触发匹配。
 *
 * §11.1 补充：EventBus 是纯内存态，App 被杀期间的事件会永久丢失。
 * 对来自持久化业务操作的事件，调用方应先写 [PendingEventEntity][com.zaijian.zhoumuyun.data.db.entity.PendingEventEntity]
 * 再 emit——内存 emit 用于"App 存活时的即时响应"，落盘记录用于兜底。
 * 参见 [EventPublisher]，它将两步合为一个方法调用。
 *
 * extraBufferCapacity=64：缓冲区足够大，避免高频事件场景下 emit 被挂起。
 * replay=0：新订阅者不收到历史事件（ChainTriggerMatcher 只处理订阅后发生的事件，
 * 历史事件由 processPendingEvents() 从磁盘重放）。
 */
object EventBus {

    private val _events = MutableSharedFlow<AppEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )

    val events: SharedFlow<AppEvent> = _events.asSharedFlow()

    /**
     * 发出一个事件。suspend 是因为 MutableSharedFlow.emit 在缓冲区满时会挂起
     * （实际场景下 extraBufferCapacity=64 足够，几乎不会触发）。
     */
    suspend fun emit(event: AppEvent) = _events.emit(event)
}
