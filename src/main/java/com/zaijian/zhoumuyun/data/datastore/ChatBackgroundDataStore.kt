package com.zaijian.zhoumuyun.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// ─────────────────────────────────────────────────────────────
//  ChatBackgroundDataStore — 每个角色聊天页背景图（独立存储）
//  key 格式：bg_uri_<characterId>
//  value：用户选择的图片 URI 字符串（null / 空 = 使用默认背景）
// ─────────────────────────────────────────────────────────────

private val Context.chatBgDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "chat_background")

class ChatBackgroundDataStore(private val context: Context) {

    private fun key(characterId: Int) =
        stringPreferencesKey("bg_uri_$characterId")

    /** 观察某角色背景 URI（null = 默认背景）*/
    fun backgroundUriFlow(characterId: Int): Flow<String?> =
        context.chatBgDataStore.data.map { prefs ->
            prefs[key(characterId)]?.takeIf { it.isNotBlank() }
        }

    /** 设置背景图 URI */
    suspend fun setBackgroundUri(characterId: Int, uri: String) {
        context.chatBgDataStore.edit { prefs ->
            prefs[key(characterId)] = uri
        }
    }

    /** 清除背景图（恢复默认） */
    suspend fun clearBackground(characterId: Int) {
        context.chatBgDataStore.edit { prefs ->
            prefs.remove(key(characterId))
        }
    }
}
