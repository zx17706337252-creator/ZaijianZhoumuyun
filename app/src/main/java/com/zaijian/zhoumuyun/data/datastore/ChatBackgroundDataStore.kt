package com.zaijian.zhoumuyun.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// ─────────────────────────────────────────────────────────────
//  ChatBackgroundDataStore — 每个角色聊天页背景图（独立存储）
//  key 格式：bg_uri_<characterId> / bg_offset_x_<characterId> / ...
//  value：用户选择的图片 URI 字符串（null / 空 = 使用默认背景），
//  以及配套的取景偏移/缩放参数（v55 修复"背景图无法拖动缩放"问题——
//  此前只存 URI，选完图直接原样 Crop 显示，用户没有任何调整取景
//  范围的入口）。
//
//  offsetX/offsetY/scale 的含义与 AvatarCropDialog 产出的
//  CropParams 一致：
//    offsetX/offsetY：图片中心相对裁剪框中心的归一化偏移，范围约 -1..1
//    scale：缩放倍数，1f = 图片刚好覆盖裁剪框（默认值，对应旧版
//      "选完图直接 Crop 铺满"的行为，兼容未曾调整过取景的老数据）
// ─────────────────────────────────────────────────────────────

data class ChatBackgroundConfig(
    val uri: String,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scale: Float = 1f,
)

private val Context.chatBgDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "chat_background")

class ChatBackgroundDataStore(private val context: Context) {

    private fun uriKey(characterId: Int)     = stringPreferencesKey("bg_uri_$characterId")
    private fun offsetXKey(characterId: Int) = floatPreferencesKey("bg_offset_x_$characterId")
    private fun offsetYKey(characterId: Int) = floatPreferencesKey("bg_offset_y_$characterId")
    private fun scaleKey(characterId: Int)   = floatPreferencesKey("bg_scale_$characterId")

    /** 观察某角色背景 URI（null = 默认背景）。仅供旧调用点兼容，新代码请用 configFlow */
    fun backgroundUriFlow(characterId: Int): Flow<String?> =
        context.chatBgDataStore.safeData().map { prefs ->
            prefs[uriKey(characterId)]?.takeIf { it.isNotBlank() }
        }

    /** 观察某角色完整背景配置（URI + 取景偏移/缩放），null = 使用默认背景 */
    fun configFlow(characterId: Int): Flow<ChatBackgroundConfig?> =
        context.chatBgDataStore.safeData().map { prefs ->
            val uri = prefs[uriKey(characterId)]?.takeIf { it.isNotBlank() } ?: return@map null
            ChatBackgroundConfig(
                uri     = uri,
                offsetX = prefs[offsetXKey(characterId)] ?: 0f,
                offsetY = prefs[offsetYKey(characterId)] ?: 0f,
                scale   = prefs[scaleKey(characterId)] ?: 1f,
            )
        }

    /** 设置背景图 URI（不带取景参数，等价于 scale=1/offset=0 居中裁剪，兼容旧调用点） */
    // 审查项 3.14：写入失败（磁盘满/文件损坏）时 safeEdit 会捕获 IOException 记录日志，
    // 不让异常穿透崩溃；用户只是这一次设置未生效，重新选一次图即可。
    suspend fun setBackgroundUri(characterId: Int, uri: String) {
        context.chatBgDataStore.safeEdit { prefs ->
            prefs[uriKey(characterId)] = uri
            prefs.remove(offsetXKey(characterId))
            prefs.remove(offsetYKey(characterId))
            prefs.remove(scaleKey(characterId))
        }
    }

    /** 设置背景图完整配置（URI + 用户在 AvatarCropDialog 中拖拽/缩放产出的取景参数） */
    suspend fun setBackgroundConfig(characterId: Int, config: ChatBackgroundConfig) {
        context.chatBgDataStore.safeEdit { prefs ->
            prefs[uriKey(characterId)]     = config.uri
            prefs[offsetXKey(characterId)] = config.offsetX
            prefs[offsetYKey(characterId)] = config.offsetY
            prefs[scaleKey(characterId)]   = config.scale
        }
    }

    /** 清除背景图（恢复默认） */
    suspend fun clearBackground(characterId: Int) {
        context.chatBgDataStore.safeEdit { prefs ->
            prefs.remove(uriKey(characterId))
            prefs.remove(offsetXKey(characterId))
            prefs.remove(offsetYKey(characterId))
            prefs.remove(scaleKey(characterId))
        }
    }
}
