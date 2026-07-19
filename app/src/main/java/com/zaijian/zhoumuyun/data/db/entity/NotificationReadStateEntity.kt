package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

// ─────────────────────────────────────────────────────────────
//  NotificationReadStateEntity — 通知中心已读状态
//  通知中心设计方案 第三节。
//
//  itemKey 是"类型+角色/关系对"拼出的稳定字符串 key，由
//  NotificationRepository 统一生成（见 buildItemKey()），
//  不在这里定义生成规则，避免两处各写一份拼接逻辑走岔。
//
//  语义：这张表记的是"用户看过"，不是"问题已解决"。同一 itemKey
//  只要根因还在（BriefingRepository.buildAttentionList() 还会产出
//  同名条目），就会一直出现在列表里，只是不再计入未读角标。
//  根因消失后 itemKey 不再产出，这张表里对应的行变成孤儿数据，
//  由 NotificationRepository.pruneStaleReadState() 定期清理。
// ─────────────────────────────────────────────────────────────

@Entity(tableName = "notification_read_state")
data class NotificationReadStateEntity(
    @PrimaryKey val itemKey: String,
    val readAt: Long,
)