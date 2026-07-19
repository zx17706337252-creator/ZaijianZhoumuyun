package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 聊天消息表。
 * 一条记录 = 一条消息（用户或角色）。
 * 按 characterId 索引，支持快速按角色查询历史。
 *
 * Phase 18 新增：
 * - exportedFileJson：file_export 工具生成的文件元数据（JSON 字符串）。
 *   null = 普通消息，非 null = 该消息携带可下载文件。
 *   格式：{"fileName":"xxx.md","mimeType":"text/markdown","sizeBytes":1234}
 *
 * Fix-ThinkingLeak 新增：
 * - thinkingText：ChatViewModel.stripThinkingTag() 从回复正文剥离出的内心推理/
 *   工具调用意图原文，纯文本存储。null = 该消息没有思考内容（或用户消息）。
 *   与 exportedFileJson 不同，思考内容只有一个字段，不需要 JSON 包装。
 *
 * v1.38 问题2·三层分离 新增：
 * - psychText：ChatTagParser.stripPsychText() 从回复正文中全角圆括号（　）包裹的
 *   内容抽取出的心理感受/神态描写，纯文本存储。与 thinkingText 不同——这是"戏内"
 *   内容，展示给用户看（不折叠），不是给系统内部用的决策推理。null = 该消息没有
 *   心理描写（或用户消息）。
 */
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["characterId"]),
        Index(value = ["characterId", "createdAt"]),
        Index(value = ["createdAt"]),
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    /** 关联角色 ID（1-9） */
    val characterId: Int,
    /** "user" 或角色 ID 字符串 */
    val role: String,
    /** 消息文本内容 */
    val content: String,
    /** 毫秒时间戳 */
    val createdAt: Long,
    /**
     * 关联的 WorldEvent ID（Phase 7 写 MESSAGE 事件后填入，
     * 当前阶段可为 null）
     */
    val eventId: String? = null,
    /**
     * Phase 18：file_export 工具生成的文件元数据（JSON 字符串）。
     * null = 普通消息，非 null = 该消息携带可下载文件卡片。
     */
    val exportedFileJson: String? = null,
    /**
     * Fix-ThinkingLeak：从回复正文剥离出的内心推理/工具调用意图原文。
     * null = 没有思考内容，非 null = 气泡下方展示可折叠的"想法"卡片。
     */
    val thinkingText: String? = null,
    /**
     * v1.38 问题2：从回复正文中圆括号（　）包裹的内容抽取出的心理感受/神态描写。
     * null = 没有心理描写，非 null = 台词气泡上方展示不折叠的心理感受小卡。
     */
    val psychText: String? = null,
)
