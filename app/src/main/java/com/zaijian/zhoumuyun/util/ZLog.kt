package com.zaijian.zhoumuyun.util

import android.util.Log
import com.zaijian.zhoumuyun.BuildConfig

/**
 * 统一日志封装（安全 L-1）
 *
 * · d / i / w：仅 debug 包输出，release 包静默。
 * · e        ：始终输出，错误日志在生产包也需要保留诊断能力。
 *
 * 用法与 android.util.Log 完全一致，直接替换调用前缀即可：
 *   android.util.Log.d("TAG", "msg")  →  ZLog.d("TAG", "msg")
 *   Log.w("TAG", "msg", e)            →  ZLog.w("TAG", "msg", e)
 */
object ZLog {

    fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.d(tag, msg)
    }

    fun d(tag: String, msg: String, tr: Throwable) {
        if (BuildConfig.DEBUG) Log.d(tag, msg, tr)
    }

    fun i(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.i(tag, msg)
    }

    fun i(tag: String, msg: String, tr: Throwable) {
        if (BuildConfig.DEBUG) Log.i(tag, msg, tr)
    }

    // S3问题5修复：WARNING 日志在生产环境也需要保留诊断能力
    // （SupabaseClient 网络错误等均使用 w 级别），与 e() 保持一致
    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable) {
        Log.w(tag, msg, tr)
    }

    /** 错误日志始终输出，不受 DEBUG 守卫限制 */
    fun e(tag: String, msg: String) {
        Log.e(tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable) {
        Log.e(tag, msg, tr)
    }
}
