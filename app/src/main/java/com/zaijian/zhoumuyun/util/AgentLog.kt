package com.zaijian.zhoumuyun.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter

/**
 * 可导出的 Agent 行为日志（v147+ vault 改造后新增）。
 *
 * ## 为什么需要这个
 * ZLog 只输出到 logcat，重启即丢，且用户拿不到日志文件。
 * Agent 工具调用链路长（LLM → ToolParser → ToolCallInterceptor → AgentTool → 落库 → UI），
 * 出问题时用户只看到"没反应"或"说存了但没文件"，无法定位是哪个环节。
 * AgentLog 把关键节点写到文件，用户可导出日志排查：
 * - 工具调用开始/成功/失败/超时
 * - LLM 调用失败
 * - 工具未注册/被禁用
 * - payload 收集/落库
 * - 文件打开失败（v147 vault FileProvider 修复同步引入）
 *
 * ## 日志文件
 * - 路径：`filesDir/logs/agent_log.txt`（当前）+ `agent_log.old`（上一个）
 * - 环形：当前文件超过 [MAX_FILE_BYTES]（2MB）时，滚动到 `.old`，当前文件清空重写
 * - 最多保留 2 个文件（当前 + 上一个），约 4MB 总量上限
 *
 * ## 格式
 * ```
 * [2026-07-20 15:30:45.123] [INFO] [ToolCall] ▶ file_export 开始
 *   params: {"file_name":"test.txt","content":"测试"}
 * [2026-07-20 15:30:45.234] [INFO] [ToolCall] ✔ file_export 成功（用时 111ms）
 *   result: {"fileName":"test.txt","absolutePath":"/data/.../vault/.../test.txt"}
 * [2026-07-20 15:31:02.456] [ERROR] [ToolCall] ✗ table_export 失败
 *   error: java.io.FileNotFoundException: /vault/...csv (No such file)
 * ```
 *
 * ## 线程安全
 * 用 [Mutex] 保护文件写入——多协程并发写日志时不会交错损坏。
 *
 * ## 性能
 * - 不阻塞调用方：`log()` 是 `suspend fun`，走 `Dispatchers.IO`
 * - 单条日志不做诊断性截断，完整保留 message + 堆栈；只在
 *   [HARD_SAFETY_CAP_CHARS]（约 200KB）这个远超正常场景的失控防线上兜底，
 *   避免误把超大 payload 整段写入日志把本地存储写爆
 *
 * ## 导出
 * [exportLog] 返回日志文件的 [File]，调用方可通过 FileProvider 分享。
 * UI 层（ChatScreen）暴露"导出诊断日志"按钮调用它。
 */
object AgentLog {

    /** 当前日志文件最大字节数，超过则滚动。 */
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024  // 2MB

    /**
     * 单条日志的失控防线上限——不是为了保证可读性而做的诊断性截断
     * （那个已经按用户要求去掉了），只是防止极端情况（比如误把整段数据库
     * dump 或超大 payload 写进一条日志）把本地存储写爆。
     * 设得远高于任何正常日志场景（堆栈、JSON payload 等），正常使用不会触发。
     */
    private const val HARD_SAFETY_CAP_CHARS = 200_000  // 约 200KB 文本

    /** 日志文件名。 */
    private const val LOG_FILE_NAME = "agent_log.txt"
    private const val OLD_LOG_FILE_NAME = "agent_log.old"

    private val mutex = Mutex()

    // ── 公开 API ──────────────────────────────────────────────

    /** 普通信息（工具开始/成功/payload 收集等）。 */
    suspend fun info(tag: String, message: String) {
        writeLog("INFO", tag, message, null)
    }

    /** 警告（工具被禁用/未注册/LLM 调用失败但可重试等）。 */
    suspend fun warn(tag: String, message: String) {
        writeLog("WARN", tag, message, null)
    }

    /** 警告（带堆栈，供 ZLog.w 转发等场景使用）。堆栈完整保留，不做预截断。 */
    suspend fun warn(tag: String, message: String, throwable: Throwable?) {
        val fullMessage = if (throwable != null) {
            "$message\n  ${throwable.stackTraceString}"
        } else {
            message
        }
        writeLog("WARN", tag, fullMessage, null)
    }

    /** 错误（工具失败/超时/异常堆栈）。堆栈完整保留，不做预截断。 */
    suspend fun error(tag: String, message: String, throwable: Throwable? = null) {
        val fullMessage = if (throwable != null) {
            "$message\n  ${throwable.stackTraceString}"
        } else {
            message
        }
        writeLog("ERROR", tag, fullMessage, null)
    }

    // ── 导出 ─────────────────────────────────────────────────

    /**
     * 导出当前日志文件（供 FileProvider 分享）。
     *
     * @return 日志文件 [File]，不存在时返回 null。
     *         调用方用 [Context.packageName].fileprovider + ACTION_SEND 分享。
     */
    suspend fun exportLog(context: Context): File? = withContext(Dispatchers.IO) {
        val logFile = getLogFile(context)
        if (logFile.exists() && logFile.length() > 0) logFile else null
    }

    /**
     * 清空日志文件（用户手动清除）。
     */
    suspend fun clearLog(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            getLogFile(context).writeText("")
            getOldLogFile(context).delete()
        }
    }

    // ── 内部实现 ─────────────────────────────────────────────

    private suspend fun writeLog(level: String, tag: String, message: String, unused: Any?) {
        withContext(Dispatchers.IO) {
            doWriteLog(level, tag, message)
        }
    }

    private suspend fun doWriteLog(level: String, tag: String, message: String) {
        mutex.withLock {
            val ctx = appContext ?: return@withLock
            val logFile = getLogFile(ctx)

            // 滚动检查：当前文件超过上限，滚动到 .old。
            // 单条日志有 HARD_SAFETY_CAP_CHARS（约 200KB 字符，UTF-8 最坏情况
            // 约 600KB 字节）的失控防线，远小于这里的 2MB 文件阈值，所以不存在
            // "单条日志本身就超过文件滚动阈值"的情况，滚动检查逻辑保持简单。
            if (logFile.exists() && logFile.length() > MAX_FILE_BYTES) {
                val oldFile = getOldLogFile(ctx)
                if (oldFile.exists()) oldFile.delete()
                logFile.renameTo(oldFile)
            }

            // 确保目录存在
            logFile.parentFile?.mkdirs()

            // 写入：不做诊断性截断（4096 字符的旧上限已移除），完整保留
            // message + 堆栈。只保留一个远高于正常场景的失控防线——
            // 防止误把整段数据库 dump 或超大 payload 写进日志把本地存储写爆，
            // 这个上限本身不是为了"保护日志可读性"，只是最后兜底。
            val safe = if (message.length > HARD_SAFETY_CAP_CHARS) {
                "${message.take(HARD_SAFETY_CAP_CHARS)}\n  ...(单条日志超过 $HARD_SAFETY_CAP_CHARS 字符安全上限，" +
                    "已截断，原文共 ${message.length} 字符——这是极端保护阈值，正常日志不会触发)"
            } else {
                message
            }

            val timestamp = TimeFormatUtils.formatLogTimestamp(System.currentTimeMillis())
            val line = "[$timestamp] [$level] [$tag] $safe\n"

            FileOutputStream(logFile, /* append = */ true).use { fos ->
                fos.write(line.toByteArray(Charsets.UTF_8))
            }

            // 同时输出到 logcat（方便 adb 调试）。logcat 本身对单条有其自身长度
            // 限制，这里输出不影响已经完整落盘的文件内容。
            when (level) {
                "ERROR" -> Log.e(tag, safe)
                "WARN"  -> Log.w(tag, safe)
                else    -> Log.i(tag, safe)
            }
        }
    }

    private fun getLogFile(context: Context): File =
        File(context.filesDir, "logs/$LOG_FILE_NAME")

    private fun getOldLogFile(context: Context): File =
        File(context.filesDir, "logs/$OLD_LOG_FILE_NAME")

    // ── appContext（由 Application 注入）─────────────────────

    @Volatile
    private var appContext: Context? = null

    /**
     * 初始化（在 [com.zaijian.zhoumuyun.ZaijianApp.onCreate] 里调一次）。
     * 不注入则日志静默丢弃（不会崩溃）。
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }
}

/** Throwable 的堆栈转字符串（避免 android.util.Log.getStackTraceString 依赖）。 */
private val Throwable.stackTraceString: String
    get() = this.stackTrace.joinToString("\n  ") { it.toString() }
