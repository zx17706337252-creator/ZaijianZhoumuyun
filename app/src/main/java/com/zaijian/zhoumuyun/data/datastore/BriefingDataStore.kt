package com.zaijian.zhoumuyun.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// ─────────────────────────────────────────────────────────────
//  BriefingDataStore — 离线简报"上次打开时间"记录
//  整合方案 v2.1 4.4 节。写法参照 AppearanceDataStore.kt。
//
//  用途：BriefingViewModel 每次生成简报时，先读这里记的
//  「上次简报截止时间」作为本次统计的起点，生成完简报后
//  再把当前时间写回这里，作为下一次简报的起点。
// ─────────────────────────────────────────────────────────────

private val Context.briefingDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "briefing")

object BriefingKeys {
    val LAST_OPEN_AT = longPreferencesKey("last_open_at")
}

class BriefingDataStore(private val context: Context) {

    /** null 表示从未记录过（App 首次安装后第一次启动） */
    val lastOpenAtFlow: Flow<Long?> = context.briefingDataStore.safeData()
        .map { it[BriefingKeys.LAST_OPEN_AT] }

    suspend fun setLastOpenAt(timestamp: Long) {
        context.briefingDataStore.safeEdit { it[BriefingKeys.LAST_OPEN_AT] = timestamp }
    }
}
