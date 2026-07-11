package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.db.dao.WorldEventDao
import com.zaijian.zhoumuyun.data.db.entity.EventDomain
import com.zaijian.zhoumuyun.data.db.entity.EventType
import com.zaijian.zhoumuyun.data.db.entity.WorldEventEntity
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.util.UUID

/**
 * Event Engine Repository（Phase 7 基础实现）。
 *
 * 原则：
 * - 所有写操作通过 append() 进入 world_events
 * - 禁止绕过 Event 直接修改 Presence / Timeline
 * - 每条 Message 必须同时产生一条 MESSAGE 事件
 */
class EventRepository(private val dao: WorldEventDao) {

    // ── 写入 ────────────────────────────────────────────────

    suspend fun append(event: WorldEventEntity) = dao.append(event)

    /**
     * 快捷方法：记录一条用户/角色消息事件（Phase 7 主要用途）。
     *
     * @param actorId "user" 或角色 ID 字符串
     * @param targetId 目标角色 ID（用户发消息时填角色 ID，角色发消息时填 "user"）
     * @param payloadJson 消息相关数据（messageId、content 摘要等）
     */
    suspend fun appendMessageEvent(
        actorId: String,
        targetId: String,
        payloadJson: String,
        importance: Int = 2,
    ): String {
        val id = UUID.randomUUID().toString()
        dao.append(
            WorldEventEntity(
                id         = id,
                type       = EventType.MESSAGE.name,
                actorId    = actorId,
                targetId   = targetId,
                domain     = EventDomain.PERSONAL.name,
                projectId  = null,
                payload    = payloadJson,
                importance = importance,
                createdAt  = System.currentTimeMillis(),
            )
        )
        return id
    }

    /**
     * 记录 Presence 变化事件（对话结束后由 ChatViewModel 调用）。
     *
     * @param characterId  角色 ID
     * @param statusType   新状态（存 name 字符串）
     * @param trigger      触发原因，如 "conversation_ended"
     */
    suspend fun appendPresenceChangedEvent(
        characterId: Int,
        statusType: String,
        trigger: String = "conversation_ended",
    ): String {
        val id = UUID.randomUUID().toString()
        dao.append(
            WorldEventEntity(
                id         = id,
                type       = EventType.PRESENCE_CHANGED.name,
                actorId    = characterId.toString(),
                targetId   = null,
                domain     = EventDomain.WORLD.name,
                projectId  = null,
                payload    = JSONObject().apply {
                    put("statusType", statusType)
                    put("trigger", trigger)
                }.toString(),
                importance = 1,
                createdAt  = System.currentTimeMillis(),
            )
        )
        return id
    }

    // ── 查询 ────────────────────────────────────────────────

    suspend fun queryLatest(limit: Int = 50) = dao.queryLatest(limit)

    suspend fun queryByActor(actorId: String, limit: Int = 50) =
        dao.queryByActor(actorId, limit)

    suspend fun queryByDomain(domain: EventDomain, limit: Int = 50) =
        dao.queryByDomain(domain.name, limit)

    /** Phase 10 Project Engine 使用 */
    suspend fun queryByProject(projectId: String, limit: Int = 50) =
        dao.queryByProject(projectId, limit)

    fun observeLatest(limit: Int = 30): Flow<List<WorldEventEntity>> =
        dao.observeLatest(limit)
}
