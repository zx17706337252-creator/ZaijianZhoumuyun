package com.zaijian.zhoumuyun.ui.viewmodel

import com.zaijian.zhoumuyun.data.db.entity.MessageEntity
import com.zaijian.zhoumuyun.domain.ChatTagParser

// 原文件名 ChatTagParser.kt：本次把标签剥离逻辑（stripThinkingTag/stripPsychText/
// stripMoodTag/stripTagsForDisplay 等）下沉到 domain.ChatTagParser（详见该文件顶部
// 架构位置说明），本文件只保留依赖 ui.viewmodel.ChatMessage 的映射函数，
// 随之更名为 ChatMessageMapper.kt，避免文件名与其实际内容（不再含 ChatTagParser 本体）不符。
//
// 顶层扩展函数（而非成员扩展函数）：
// 写成 domain.ChatTagParser 内部的成员扩展函数的话，只能以
// `with(ChatTagParser) { entity.toChatMessage() }` 这种形式调用；
// 项目里两处调用点用的是 `ChatTagParser.toChatMessage(entity)` 这种静态调用写法，
// 对成员扩展函数不成立，改为顶层扩展函数后两种调用点都能编译通过。
fun ChatTagParser.toChatMessage(entity: MessageEntity) = ChatMessage(
    id = entity.id,
    role = entity.role,
    content = entity.content,
    createdAt = entity.createdAt,
    exportedFileJson = entity.exportedFileJson,
    exportedFilesJson = entity.exportedFilesJson,   // v66（1.7 P3）：不透传的话，
    // DB 里存的多文件数据永远读不到 UI 层，等于白写。
    thinkingText = entity.thinkingText,
    psychText = entity.psychText,
)
