package com.zaijian.zhoumuyun.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// ─────────────────────────────────────────────────────────────
//  AppearanceDataStore — 外观三项设置（主题 / 字体 / 背景）
//  Fix-11: 从 SharedPreferences 迁移至 DataStore<Preferences>
// ─────────────────────────────────────────────────────────────

private val Context.appearanceDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "appearance")

object AppearanceKeys {
    val THEME_INDEX     = intPreferencesKey("theme_index")
    val FONT_SIZE_INDEX = intPreferencesKey("font_size_index")
    val BG_STYLE_INDEX  = intPreferencesKey("bg_style_index")
}

class AppearanceDataStore(private val context: Context) {

    // ── Flows ─────────────────────────────────────────────────

    val themeIndexFlow: Flow<Int> = context.appearanceDataStore.safeData()
        .map { it[AppearanceKeys.THEME_INDEX] ?: 0 }

    val fontSizeIndexFlow: Flow<Int> = context.appearanceDataStore.safeData()
        .map { it[AppearanceKeys.FONT_SIZE_INDEX] ?: 1 }

    val bgStyleIndexFlow: Flow<Int> = context.appearanceDataStore.safeData()
        .map { it[AppearanceKeys.BG_STYLE_INDEX] ?: 0 }

    // ── Writers ───────────────────────────────────────────────
    // 审查项 3.14：写入失败（磁盘满/文件损坏）时 safeEdit 内部会捕获
    // IOException 并记录日志，不再让异常穿透导致调用方崩溃。

    suspend fun setThemeIndex(index: Int) {
        context.appearanceDataStore.safeEdit { it[AppearanceKeys.THEME_INDEX] = index }
    }

    suspend fun setFontSizeIndex(index: Int) {
        context.appearanceDataStore.safeEdit { it[AppearanceKeys.FONT_SIZE_INDEX] = index }
    }

    suspend fun setBgStyleIndex(index: Int) {
        context.appearanceDataStore.safeEdit { it[AppearanceKeys.BG_STYLE_INDEX] = index }
    }
}
