package com.zaijian.zhoumuyun.util

import android.util.Log
import com.zaijian.zhoumuyun.BuildConfig
import com.zaijian.zhoumuyun.ZaijianApp
import kotlinx.coroutines.launch

/**
 * 统一日志封装（安全 L-1）
 *
 * · d / i / w：仅 debug 包输出，release 包静默。
 * · e        ：始终输出，错误日志在生产包也需要保留诊断能力。
 *
 * 用法与 android.util.Log 完全一致，直接替换调用前缀即可：
 *   android.util.Log.d("TAG", "msg")  →  ZLog.d("TAG", "msg")
 *   Log.w("TAG", "msg", e)            →  ZLog.w("TAG", "msg", e)
 *
 * ## AgentLog 转发（诊断日志扩容）
 * `w`/`e` 除了照常写 logcat，还会异步转发一份到 [AgentLog]（可导出、落盘）。
 * 这样无需改动全项目里已有的 94 处 `ZLog.w/e` 调用点，
 * 就能让诊断日志覆盖范围从"仅工具调用"扩大到"全项目 warn/error"。
 * 转发用 [ZaijianApp.appScope] 异步执行、不阻塞调用方；
 * appScope 为 null（App 未初始化完成/已销毁）时静默跳过，不抛异常。
 * 转发的 tag 前缀会加 `Z/`，方便在导出日志里区分"来自 ZLog 转发"还是
 * "Agent 工具自己写的"。
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
        forwardToAgentLog(isError = false, tag, msg, null)
    }

    fun w(tag: String, msg: String, tr: Throwable) {
        Log.w(tag, msg, tr)
        forwardToAgentLog(isError = false, tag, msg, tr)
    }

    /** 错误日志始终输出，不受 DEBUG 守卫限制 */
    fun e(tag: String, msg: String) {
        Log.e(tag, msg)
        forwardToAgentLog(isError = true, tag, msg, null)
    }

    fun e(tag: String, msg: String, tr: Throwable) {
        Log.e(tag, msg, tr)
        forwardToAgentLog(isError = true, tag, msg, tr)
    }

    /**
     * 把 warn/error 异步转发到 [AgentLog]，不阻塞调用方（调用方可能是非 suspend
     * 上下文，比如 catch 块里）。用 [ZaijianApp.appScope] 起一个后台协程；
     * appScope 尚未初始化或已被清空（onTerminate 之后）时静默跳过。
     *
     * tag 前缀加 "Z/" 用来在导出日志里区分来源：
     * "Z/xxx" = 从 ZLog 转发；其余 = Agent 工具自己写的。
     *
     * 协程体内 try-catch 是必须的：[AgentLog] 内部文件写入（FileOutputStream /
     * renameTo）没有做异常保护，一旦触发（磁盘满、权限问题等），异常会在协程里
     * 未捕获抛出。appScope 用的是 SupervisorJob，兄弟协程不受影响，但这个
     * appScope 本身没有挂 CoroutineExceptionHandler 兜底，未捕获异常会走到
     * Thread 的默认异常处理器——也就是本文件里注册的全局 crash handler，
     * 直接杀掉进程。绝不能让"记录一条日志失败"升级成"应用崩溃"，所以在
     * 转发点自己吞掉异常（只发 logcat，不再尝试写 AgentLog，避免死循环）。
     */
    private fun forwardToAgentLog(isError: Boolean, tag: String, msg: String, tr: Throwable?) {
        val scope = ZaijianApp.appScope ?: return
        val forwardedTag = "Z/$tag"
        scope.launch {
            try {
                if (isError) {
                    AgentLog.error(forwardedTag, msg, tr)
                } else {
                    AgentLog.warn(forwardedTag, msg, tr)
                }
            } catch (forwardFailure: Exception) {
                // 只打 logcat，不再调用 AgentLog/ZLog，避免转发失败本身又触发一次转发。
                Log.e("ZLog", "转发到 AgentLog 失败（原始 tag=$tag）", forwardFailure)
            }
        }
    }
}
