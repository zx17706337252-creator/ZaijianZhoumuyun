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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
 * - 日志超长自动截断（单条上限 [MAX_ENTRY_CHARS] = 4KB），避免大 payload 撑爆日志
 *
 * ## 导出
 * [exportLog] 返回日志文件的 [File]，调用方可通过 FileProvider 分享。
 * UI 层（ChatScreen）暴露"导出诊断日志"按钮调用它。
 */
object AgentLog {

    /** 当前日志文件最大字节数，超过则滚动。 */
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024  // 2MB

    /** 单条日志最大字符数，超过截断（避免大 payload 撑爆日志）。 */
    private const val MAX_ENTRY_CHARS = 4096

    /** 日志文件名。 */
    private const val LOG_FILE_NAME = "agent_log.txt"
    private const val OLD_LOG_FILE_NAME = "agent_log.old"

    private val mutex = Mutex()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    // ── 公开 API ──────────────────────────────────────────────

    /** 普通信息（工具开始/成功/payload 收集等）。 */
    suspend fun info(tag: String, message: String) {
        writeLog("INFO", tag, message, null)
    }

    /** 警告（工具被禁用/未注册/LLM 调用失败但可重试等）。 */
    suspend fun warn(tag: String, message: String) {
        writeLog("WARN", tag, message, null)
    }

    /** 错误（工具失败/超时/异常堆栈）。 */
    suspend fun error(tag: String, message: String, throwable: Throwable? = null) {
        val fullMessage = if (throwable != null) {
            "$message\n  ${throwable.stackTraceString.take(MAX_ENTRY_CHARS)}"
        } else {
            message
        }
        writeLog("ERROR", tag, fullMessage, null)
    }

    /** 同步版本（仅限无法 suspend 的场景，如 catch 块里；走 IO 线程但阻塞调用方）。 */
    fun infoSync(tag: String, message: String) {
        writeLogSync("INFO", tag, message)
    }

    /** 同步错误日志（catch 块里用）。 */
    fun errorSync(tag: String, message: String, throwable: Throwable? = null) {
        val fullMessage = if (throwable != null) {
            "$message\n  ${throwable.stackTraceString.take(MAX_ENTRY_CHARS)}"
        } else {
            message
        }
        writeLogSync("ERROR", tag, fullMessage)
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

    private fun writeLogSync(level: String, tag: String, message: String) {
        // 同步写：直接在当前线程跑（通常已在 IO 或 catch 块）
        kotlinx.coroutines.runBlocking {
            doWriteLog(level, tag, message)
        }
    }

    private suspend fun doWriteLog(level: String, tag: String, message: String) {
        mutex.withLock {
            val ctx = appContext ?: return@withLock
            val logFile = getLogFile(ctx)

            // 滚动检查：当前文件超过上限，滚动到 .old
            if (logFile.exists() && logFile.length() > MAX_FILE_BYTES) {
                val oldFile = getOldLogFile(ctx)
                if (oldFile.exists()) oldFile.delete()
                logFile.renameTo(oldFile)
            }

            // 确保目录存在
            logFile.parentFile?.mkdirs()

            // 写入
            val timestamp = dateFormat.format(Date())
            val truncated = if (message.length > MAX_ENTRY_CHARS) {
                "${message.take(MAX_ENTRY_CHARS)}...(截断，共 ${message.length} 字符)"
            } else {
                message
            }
            val line = "[$timestamp] [$level] [$tag] $truncated\n"

            FileOutputStream(logFile, /* append = */ true).use { fos ->
                fos.write(line.toByteArray(Charsets.UTF_8))
            }

            // 同时输出到 logcat（方便 adb 调试）
            when (level) {
                "ERROR" -> Log.e(tag, truncated)
                "WARN"  -> Log.w(tag, truncated)
                else    -> Log.i(tag, truncated)
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
