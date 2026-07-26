package com.zaijian.zhoumuyun.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// ─────────────────────────────────────────────────────────────
//  FileDeliveryDataStore —— 角色输出文档时的呈现方式
//
//  角色通过工具（docx_gen / table_export / file_export 等）产出的文件，
//  与同一轮的文字回复本就落在同一条 MessageEntity 里（exportedFilesJson
//  字段），但气泡渲染层此前恒定把每个 FileExportCard 各画成一张独立的
//  WorldBubble 卡片、堆在文字气泡下方——视觉上就是"文字一条、文件另一条"。
//
//  这里加一个全局开关：
//    true（默认） —— 文件卡片嵌进文字气泡内部，跟文字合并成一个气泡
//    false        —— 保留旧版效果，文件卡片各自独立成一张气泡/卡片
//
//  全局而非分角色：这是用户对"聊天里文件怎么呈现"的界面偏好，不像聊天
//  背景图那样天然带角色属性，没必要按 characterId 拆 key。
// ─────────────────────────────────────────────────────────────

private val Context.fileDeliveryDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "file_delivery")

object FileDeliveryKeys {
    val ATTACH_TOGETHER = booleanPreferencesKey("attach_files_together")
}

class FileDeliveryDataStore(private val context: Context) {

    /** true = 文件卡片与文字回复合并进同一气泡（默认）；false = 各自独立成卡片。 */
    val attachTogetherFlow: Flow<Boolean> = context.fileDeliveryDataStore.safeData()
        .map { it[FileDeliveryKeys.ATTACH_TOGETHER] ?: true }

    suspend fun setAttachTogether(value: Boolean) {
        context.fileDeliveryDataStore.safeEdit { it[FileDeliveryKeys.ATTACH_TOGETHER] = value }
    }
}
