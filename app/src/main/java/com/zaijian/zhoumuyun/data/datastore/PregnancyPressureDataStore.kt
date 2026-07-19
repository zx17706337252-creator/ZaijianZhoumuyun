package com.zaijian.zhoumuyun.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// ─────────────────────────────────────────────────────────────
//  PregnancyPressureDataStore — 孕期压力系数 / P5 触发开关
//  审查项 2.9 修复：从 AppearanceDataStore.kt 中拆出，两者职责不相关。
// ─────────────────────────────────────────────────────────────

private val PREGNANCY_PRESSURE_SCALE_KEY =
    floatPreferencesKey("pregnancy_pressure_scale")

private val P5_TRIGGER_ENABLED_KEY =
    booleanPreferencesKey("p5_trigger_enabled")

private val Context.pressureDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "pregnancy_settings")

class PregnancyPressureDataStore(private val context: Context) {

    val pregnancyPressureScaleFlow: Flow<Float> = context.pressureDataStore.safeData()
        .map { it[PREGNANCY_PRESSURE_SCALE_KEY] ?: 1.0f }

    // 审查项 3.14：写入失败时 safeEdit 记录日志并返回，不让异常穿透崩溃。
    suspend fun setPregnancyPressureScale(scale: Float) {
        val clamped = scale.coerceIn(0f, 1f)
        context.pressureDataStore.safeEdit { it[PREGNANCY_PRESSURE_SCALE_KEY] = clamped }
    }

    val p5TriggerEnabledFlow: Flow<Boolean> = context.pressureDataStore.safeData()
        .map { it[P5_TRIGGER_ENABLED_KEY] ?: false }

    suspend fun setP5TriggerEnabled(enabled: Boolean) {
        context.pressureDataStore.safeEdit { it[P5_TRIGGER_ENABLED_KEY] = enabled }
    }
}
