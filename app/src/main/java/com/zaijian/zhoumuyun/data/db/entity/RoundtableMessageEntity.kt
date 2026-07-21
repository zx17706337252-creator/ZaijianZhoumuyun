package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 圆桌消息表（待办7：圆桌消息持久化）。
 *
 * 与单人聊天的 [MessageEntity] 按 characterId 分表不同，圆桌一条对话线由
 * "进入圆桌时的成员集合"决定（[roundtableId] = 成员 characterId 排序后用
 * "_" 拼接，如 "1_2_3_4"），后续 addMember/removeMember 临时增减人不改变
 * 这条线的归属，避免每次改名单就"断片"成新对话。
 *
 * 一条记录 = 一条消息（用户发言 或 某个 Bot 的回复）。
 */
@Entity(
    tableName = "roundtable_messages",
    indices = [
        Index(value = ["roundtableId"]),
        Index(value = ["roundtableId", "createdAt"]),
        Index(value = ["speakerId", "createdAt"]),
    ]
)
data class RoundtableMessageEntity(
    @PrimaryKey val id: String,
    /** 成员 characterId 排序后拼接，如 "1_2_3_4" */
    val roundtableId: String,
    /** "user" 或 characterId 字符串 */
    val speakerId: String,
    val speakerName: String,
    val content: String,
    val createdAt: Long,
    /** 回应目标：null=回应用户，非null=回应某Bot */
    val replyTargetId: String? = null,
    val replyTargetName: String? = null,
    /** 此消息所属的用户消息轮次 */
    val turnIndex: Int = 0,
    /**
     * P6 专长进化系统新增：与 MessageEntity.exportedFileJson 同语义。
     * 圆桌场景此前没有这个能力，专长进化的每日修炼播报需要"产出全文可下载"，
     * 借此补齐圆桌消息的文件卡片能力。
     * null = 普通消息，非 null = 该消息携带可下载文件卡片。
     */
    val exportedFileJson: String? = null,
    /**
     * v66（Agent附件下发方案 v2.0 · 1.7 P3）：多文件版本，与
     * MessageEntity.exportedFilesJson 同语义/同格式（JSON 数组字符串）。
     * null = 该消息没有文件附件；历史消息永远为 null，即使 exportedFileJson
     * 有值。圆桌一轮回复内连续调用多个文件类工具时，这里保存全部文件。
     */
    val exportedFilesJson: String? = null,
    /**
     * v67（表格直传方案）：与 MessageEntity.tableDataJson 同语义/同格式
     * （JSON 序列化 TablePayload）。null = 该消息没有表格；历史消息永远为 null。
     * 行数超过阈值时只存预览行 + 关联的完整 xlsx 文件引用（复用 exportedFilesJson），
     * 不整份塞进这个字段，避免单行 SQLite 记录膨胀。详见 Migration66to67。
     */
    val tableDataJson: String? = null,
    /**
     * 内心独白：从回复正文中 [thinking:...] 标签抽取，与 MessageEntity.thinkingText
     * 同语义（角色的决策/推理过程，戏外内容，默认折叠不展示）。
     *
     * 补齐圆桌场景与私聊场景的标签解析能力对等——圆桌此前从未接入
     * [thinking:]/[mood:] 标签解析（ChatTagParser 的所有 strip 函数全项目
     * 只有 ChatMessageOrchestrator.kt 一处调用），导致圆桌 Bot 回复里
     * 这类标签原样泄漏在正文中。null = 该消息没有内心独白内容。
     */
    val thinkingText: String? = null,
    /**
     * 心理感受：从回复正文中圆括号（　）包裹的内容抽取，与 MessageEntity.psychText
     * 同语义（角色当下的情绪/心理状态，戏内内容，不折叠直接展示）。
     * 与 thinkingText 同批次补齐，理由同上。null = 该消息没有心理描写。
     */
    val psychText: String? = null,
)
