package com.zaijian.zhoumuyun.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
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

    val themeIndexFlow: Flow<Int> = context.appearanceDataStore.data
        .map { it[AppearanceKeys.THEME_INDEX] ?: 0 }

    val fontSizeIndexFlow: Flow<Int> = context.appearanceDataStore.data
        .map { it[AppearanceKeys.FONT_SIZE_INDEX] ?: 1 }

    val bgStyleIndexFlow: Flow<Int> = context.appearanceDataStore.data
        .map { it[AppearanceKeys.BG_STYLE_INDEX] ?: 0 }

    // ── Writers ───────────────────────────────────────────────

    suspend fun setThemeIndex(index: Int) {
        context.appearanceDataStore.edit { it[AppearanceKeys.THEME_INDEX] = index }
    }

    suspend fun setFontSizeIndex(index: Int) {
        context.appearanceDataStore.edit { it[AppearanceKeys.FONT_SIZE_INDEX] = index }
    }

    suspend fun setBgStyleIndex(index: Int) {
        context.appearanceDataStore.edit { it[AppearanceKeys.BG_STYLE_INDEX] = index }
    }
}

private val PREGNANCY_PRESSURE_SCALE_KEY =
    androidx.datastore.preferences.core.floatPreferencesKey("pregnancy_pressure_scale")

private val P5_TRIGGER_ENABLED_KEY =
    androidx.datastore.preferences.core.booleanPreferencesKey("p5_trigger_enabled")

private val Context.pressureDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "pregnancy_settings")

class PregnancyPressureDataStore(private val context: Context) {

    val pregnancyPressureScaleFlow: Flow<Float> = context.pressureDataStore.data
        .map { it[PREGNANCY_PRESSURE_SCALE_KEY] ?: 1.0f }

    suspend fun setPregnancyPressureScale(scale: Float) {
        val clamped = scale.coerceIn(0f, 1f)
        context.pressureDataStore.edit { it[PREGNANCY_PRESSURE_SCALE_KEY] = clamped }
    }

    val p5TriggerEnabledFlow: Flow<Boolean> = context.pressureDataStore.data
        .map { it[P5_TRIGGER_ENABLED_KEY] ?: false }

    suspend fun setP5TriggerEnabled(enabled: Boolean) {
        context.pressureDataStore.edit { it[P5_TRIGGER_ENABLED_KEY] = enabled }
    }
}
