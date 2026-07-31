package com.zaijian.zhoumuyun.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// ─────────────────────────────────────────────────────────────
//  PregnancyPressureDataStore — 孕期压力系数 / P5（怀孕功能）总开关
//  审查项 2.9 修复：从 AppearanceDataStore.kt 中拆出，两者职责不相关。
//
//  C3#8 修复（产品决策：不做用户可调节项）：pregnancy_pressure_scale
//  的 setter 全仓零调用，确认为遗留死代码。删除 setter，读取侧固定
//  返回 1.0f（与此前"用户从未调整过时"的实际值完全一致，零行为变化）。
//
//  C3#9 修复（产品决策：做成怀孕功能总开关）：p5_trigger_enabled 原
//  读写两端全仓为零。P5 = 怀孕自动触发判定链路代号（叙事解锁 + 伴侣
//  同意 + 周期判定，见 PregnancyTriggerManager），该链路此前不受此
//  开关控制。现接入为总开关：默认 true（保持现状不变，怀孕功能默认
//  开启），用户可在设置中关闭后 PregnancyTriggerManager 判定链路整体
//  短路，不再触发新的怀孕。
// ─────────────────────────────────────────────────────────────

private val P5_TRIGGER_ENABLED_KEY =
    booleanPreferencesKey("p5_trigger_enabled")

private val Context.pressureDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "pregnancy_settings")

class PregnancyPressureDataStore(private val context: Context) {

    // C3#8：不再提供用户调节入口，固定返回默认强度。保留 Flow 类型不
    // 变（PregnancyPromptDelegate.kt:198,559 仍以 .first() 读取），
    // 避免改动两处调用点，仅将其语义从"读用户设置"收敛为"读固定值"。
    val pregnancyPressureScaleFlow: Flow<Float> =
        kotlinx.coroutines.flow.flowOf(1.0f)

    // C3#9：默认 true，与"此前 PregnancyTriggerManager 无条件触发"的
    // 既有行为保持一致，用户未主动关闭前零行为变化。
    val p5TriggerEnabledFlow: Flow<Boolean> = context.pressureDataStore.safeData()
        .map { it[P5_TRIGGER_ENABLED_KEY] ?: true }

    suspend fun setP5TriggerEnabled(enabled: Boolean) {
        context.pressureDataStore.safeEdit { it[P5_TRIGGER_ENABLED_KEY] = enabled }
    }
}
