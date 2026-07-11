package com.zaijian.zhoumuyun.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import java.io.IOException

// ─────────────────────────────────────────────────────────────
//  审查项 3.14 修复：DataStore 读写统一异常兜底
//
//  背景：preferencesDataStore 的 .data 在磁盘读取失败（文件损坏、
//  权限异常等）时会抛 IOException（这是 Jetpack DataStore 文档里
//  明确写明的行为），.edit {} 写入失败时同理。原代码全项目 11 处
//  调用点均无 try-catch，任一处触发即整个协程崩溃、间接导致
//  上层页面/ViewModel 状态卡死甚至应用崩溃。
//
//  没有在每个调用点分别写 try-catch，而是在这里统一提供两个
//  扩展函数，原因：
//  1) 11 处调用点分散在 4 个文件，逐个手写 try-catch 重复度高，
//     且容易漏改（未来新增 DataStore 也会重蹈覆辙）。
//  2) 读取失败时的正确兜底是"当作空数据处理"（DataStore 官方
//     推荐写法就是 catch { if (it is IOException) emit(emptyPreferences()) else throw it }），
//     这是语义明确的统一策略，不需要每个 Flow 各写一遍。
//  3) 写入失败目前项目里没有重试机制的基础设施（无 WorkManager 排队
//     写入之类的设计），最优解不是"假装成功"也不是"整个应用崩溃"，
//     而是记录日志、把这次写入丢弃——用户下次操作还能正常继续用，
//     不会因为一次磁盘抖动就卡死整个 App。
// ─────────────────────────────────────────────────────────────

private const val TAG = "DataStoreSafe"

/**
 * 包一层 IOException 兜底的读取 Flow。
 * 磁盘损坏/IO 异常时不让异常穿透到 collector，而是当作"空偏好"处理
 * （即所有 key 都读不到，业务层的 ?: 默认值分支会自然接管）。
 * 非 IOException（例如 CancellationException）原样向上抛，不吞掉。
 */
fun DataStore<Preferences>.safeData(): Flow<Preferences> =
    data.catch { e ->
        if (e is IOException) {
            ZLog.w(TAG, "读取 DataStore 失败，回退空 Preferences: ${e.message}")
            emit(emptyPreferences())
        } else {
            throw e
        }
    }

/**
 * 包一层异常兜底的写入。失败时记录日志并静默返回，不让单次磁盘异常
 * 导致调用方协程崩溃。返回 Boolean 供调用方按需判断是否成功
 * （当前调用点大多不关心返回值，失败只影响这一次设置不会持久化，
 * 用户下次重新操作即可，不属于需要强提示的场景）。
 */
suspend fun DataStore<Preferences>.safeEdit(
    transform: suspend (MutablePreferences) -> Unit,
): Boolean {
    return try {
        this.edit { transform(it) }
        true
    } catch (e: IOException) {
        ZLog.w(TAG, "写入 DataStore 失败: ${e.message}")
        false
    }
}
