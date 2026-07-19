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
//  SplashBackgroundDataStore — 门扉页（启动页）自定义背景图存储
//
//  与 ChatBackgroundDataStore 同一套模式（URI + 取景偏移/缩放三件套
//  一起存取），去掉 characterId 维度——门扉页是全局唯一的一份配置，
//  不像聊天背景要按角色分别记忆。
//
//  offsetX/offsetY/scale 含义与 AvatarCropDialog 产出的 CropParams
//  一致：
//    offsetX/offsetY：图片中心相对裁剪框中心的归一化偏移，范围约 -1..1
//    scale：缩放倍数，1f = 图片刚好覆盖裁剪框
//
//  未设置过自定义图（uri 为 null）时，SplashScreen 使用原有的品牌
//  兜底视觉（呼吸光晕圆形 Logo），不会因为用户没设置而空白。
// ─────────────────────────────────────────────────────────────

data class SplashBackgroundConfig(
    val uri: String,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scale: Float = 1f,
)

private val Context.splashBgDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "splash_background")

class SplashBackgroundDataStore(private val context: Context) {

    private val uriKey     = stringPreferencesKey("splash_bg_uri")
    private val offsetXKey = floatPreferencesKey("splash_bg_offset_x")
    private val offsetYKey = floatPreferencesKey("splash_bg_offset_y")
    private val scaleKey   = floatPreferencesKey("splash_bg_scale")

    /** 观察门扉页完整背景配置（URI + 取景偏移/缩放），null = 使用默认品牌视觉 */
    val configFlow: Flow<SplashBackgroundConfig?> =
        context.splashBgDataStore.safeData().map { prefs ->
            val uri = prefs[uriKey]?.takeIf { it.isNotBlank() } ?: return@map null
            SplashBackgroundConfig(
                uri     = uri,
                offsetX = prefs[offsetXKey] ?: 0f,
                offsetY = prefs[offsetYKey] ?: 0f,
                scale   = prefs[scaleKey] ?: 1f,
            )
        }

    /** 设置门扉页背景图完整配置（URI + 用户在 AvatarCropDialog 中拖拽/缩放产出的取景参数） */
    suspend fun setBackgroundConfig(config: SplashBackgroundConfig) {
        context.splashBgDataStore.safeEdit { prefs ->
            prefs[uriKey]     = config.uri
            prefs[offsetXKey] = config.offsetX
            prefs[offsetYKey] = config.offsetY
            prefs[scaleKey]   = config.scale
        }
    }

    /** 清除门扉页背景图，恢复默认品牌视觉 */
    suspend fun clearBackground() {
        context.splashBgDataStore.safeEdit { prefs ->
            prefs.remove(uriKey)
            prefs.remove(offsetXKey)
            prefs.remove(offsetYKey)
            prefs.remove(scaleKey)
        }
    }
}
