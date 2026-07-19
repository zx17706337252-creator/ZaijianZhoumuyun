package com.zaijian.zhoumuyun.data.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Fix-17 拆分 · PersonalTools.kt
 *
 * ═══════════════════════════════════════════════════════════════
 * 个人助手工具（4个）
 * ═══════════════════════════════════════════════════════════════
 *
 * 工具列表：
 *   ① NoteSaveTool       — 笔记保存（note_save）
 *   ② ReminderTool       — 提醒设置（reminder）
 *   ③ ClipboardWriteTool — 剪贴板写入（clipboard_write）
 *   ④ QrDecodeTool       — 二维码内容解析（qr_decode）
 *
 * 注册方式（在 ZaijianApp.onCreate 中）：
 * ```kotlin
 * AgentToolRegistry.registerAll(
 *     NoteSaveTool(context),
 *     ReminderTool(context),
 *     ClipboardWriteTool(context),
 *     QrDecodeTool(),
 * )
 * ```
 *
 * 原位置：BuiltinTools.kt ⑦⑧⑫⑬（Phase 17 / Phase 18）
 * ═══════════════════════════════════════════════════════════════
 */

// ─────────────────────────────────────────────────────────────
//  ① NoteSaveTool
// ─────────────────────────────────────────────────────────────

/**
 * 笔记保存工具（Phase 17 新增）。
 *
 * 标签格式：<tool:note_save title="标题" content="内容"/>
 * 可选参数：tag="标签名"（默认无标签）
 *
 * 实现：写入 app 内部 notes/ 目录，每条笔记一个 .txt 文件，
 *   文件名 = Unix 时间戳 + 标题片段（确保唯一，避免冲突）。
 * 无需网络，纯本地，永不失败（除非磁盘满）。
 */
class NoteSaveTool(private val context: Context) : AgentTool {

    override val name      = "note_save"
    override val description = "把内容保存为本地笔记文件，用于「帮我记一下/存个笔记」这类请求"
    override val paramKeys = listOf("title", "content", "tag")

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val title   = params["title"]?.trim()?.take(60)  ?: "未命名笔记"
        val content = params["content"]?.trim()          ?: ""
        val tag     = params["tag"]?.trim()?.take(20)    ?: ""

        if (content.isBlank()) {
            return@withContext ToolResult(name, false, "笔记内容不能为空。")
        }

        try {
            val notesDir = java.io.File(context.filesDir, "notes").also { it.mkdirs() }
            val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40)
            val timestamp = System.currentTimeMillis()
            val fileName  = "${timestamp}_${safeTitle}.txt"
            val file      = java.io.File(notesDir, fileName)

            val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(timestamp))

            val noteText = buildString {
                appendLine("# $title")
                if (tag.isNotBlank()) appendLine("标签：$tag")
                appendLine("时间：$now")
                appendLine()
                append(content)
            }

            file.writeText(noteText, Charsets.UTF_8)

            val noteCount = notesDir.listFiles()?.size ?: 1
            ToolResult(
                toolName = name,
                success  = true,
                content  = "[笔记已保存]\n标题：$title${if (tag.isNotBlank()) "\n标签：$tag" else ""}\n时间：$now\n\n你现在共有 $noteCount 条笔记。",
                userHint = "正在保存笔记…",
            )
        } catch (e: Exception) {
            ToolResult(name, false, "保存笔记时遇到问题：${e.message?.take(80)}", e.message)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ② ReminderTool
// ─────────────────────────────────────────────────────────────

/**
 * 提醒设置工具（Phase 17 新增）。
 *
 * 标签格式：<tool:reminder text="提醒内容" time="HH:mm" date="yyyy-MM-dd"/>
 * time 和 date 均为可选；若都缺省则保存为「待定提醒」。
 *
 * 实现（Phase 20 升级）：
 *   1. 持久化到 app 内部 reminders/ 目录（JSON，与之前相同）
 *   2. 新增 AlarmManager 调度（setExactAndAllowWhileIdle，Android 12+ 兼容）
 *   3. 触发时通过 ReminderReceiver（BroadcastReceiver）发送系统通知
 *
 * U2 延伸修复：新增 [characterIdProvider]，与 ScheduleCreateTool / HeartbeatSetTool
 * 等工具同款模式——不改 AgentTool 接口（影响面太大），构造时以 -1 静态占位注册
 * （ZaijianApp.registerPersonalTools()，依赖默认值不用改调用处），由
 * ChatViewModel.registerCharacterTools() 在会话开始时动态覆盖为
 * { currentCharacterId }。characterId 随 AlarmManager Intent 一路传到
 * ReminderReceiver，用于提醒触发时通知上的「查看日程」按钮深链接。
 *
 * 注意：需要在 AndroidManifest 中声明：
 *   <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM"/>
 *   <receiver android:name=".data.agent.ReminderReceiver"/>
 */
class ReminderTool(
    private val context: Context,
    private val characterIdProvider: () -> Int = { -1 },
) : AgentTool {

    override val name      = "reminder"
    override val description = "设置一次性提醒（到点系统通知），用于「提醒我/记得叫我/晚上十点联系我」这类口语化的单次约定，不用于周期性任务"
    override val paramKeys = listOf("text", "time", "date")

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val text = params["text"]?.trim() ?: return@withContext ToolResult(name, false, "请告诉我要提醒什么内容。")
        val time = params["time"]?.trim() ?: ""
        val date = params["date"]?.trim() ?: ""

        try {
            // ── 1. 持久化到本地文件 ───────────────────────────
            val remindersDir = java.io.File(context.filesDir, "reminders").also { it.mkdirs() }
            val id        = System.currentTimeMillis()
            val file      = java.io.File(remindersDir, "${id}.json")

            val triggerAtMs = parseTriggerTime(date, time, id)

            val jsonObj = org.json.JSONObject().apply {
                put("id", id)
                put("text", text)
                put("time", time)
                put("date", date)
                put("createdAt", id)
                put("triggerAtMs", triggerAtMs)
                put("isCompleted", false)
            }
            file.writeText(jsonObj.toString(), Charsets.UTF_8)

            // ── 2. AlarmManager 调度（Phase 20 §D）────────────
            // 批次C·问题22 修复：原先直接 id.toInt() 截断 System.currentTimeMillis()
            // （13 位毫秒时间戳）为 32 位 Int，任何两条提醒只要创建时间相差 2^32 毫秒
            // （约 49.7 天）的整数倍，requestCode 就会完全相同，PendingIntent.getBroadcast()
            // 配合 FLAG_UPDATE_CURRENT 会静默覆盖旧提醒的闹钟——不报错，用户毫无感知，
            // 旧提醒永远不会触发。改为对 id 的字符串形式取 hashCode()，与
            // ReminderReceiver.kt 里"查看日程"按钮 PendingIntent 已经采用的
            // ("reminder_schedule_$reqId").hashCode() 同一思路（保留该文件用法不变，
            // 两处各自独立派生，不需要额外传参）——hashCode() 参与了 Long 的全部数位，
            // 不再只取低 32 位，把碰撞概率从"确定性碰撞"降为"哈希碰撞"（32 位空间下
            // 约 2^16 条同时存活的提醒才会有 50% 碰撞概率，对单机提醒场景足够安全）。
            val requestCode = id.toString().hashCode()
            if (triggerAtMs > System.currentTimeMillis()) {
                scheduleAlarm(requestCode, text, triggerAtMs, params["__character_id"]?.toIntOrNull() ?: characterIdProvider())
            }

            val timeDesc = buildString {
                if (date.isNotBlank()) append(date)
                if (date.isNotBlank() && time.isNotBlank()) append(" ")
                if (time.isNotBlank()) append(time)
            }

            val confirmText = if (timeDesc.isNotBlank()) {
                "[提醒已设置]\n内容：$text\n时间：$timeDesc"
            } else {
                "[提醒已设置]\n内容：$text\n时间：待定（你可以告诉我具体时间）"
            }

            ToolResult(
                toolName = name,
                success  = true,
                content  = confirmText,
                userHint = "正在设置提醒…",
            )
        } catch (e: Exception) {
            ToolResult(name, false, "设置提醒时遇到问题：${e.message?.take(80)}", e.message)
        }
    }

    /**
     * 解析 date+time 字符串为毫秒时间戳。
     * 支持 "HH:mm" / "HH时mm分" 时间格式，日期支持 "MM-dd" / "MM月dd日"。
     * 无法解析时返回 fallback（创建时间 + 1 小时）。
     */
    private fun parseTriggerTime(date: String, time: String, fallback: Long): Long {
        return try {
            val cal = Calendar.getInstance()
            // 解析时间（HH:mm 或 HH时mm分）
            val timeMatch = Regex("(\\d{1,2})[：:时](\\d{1,2})").find(time)
            if (timeMatch != null) {
                cal.set(Calendar.HOUR_OF_DAY, timeMatch.groupValues[1].toInt())
                cal.set(Calendar.MINUTE,      timeMatch.groupValues[2].toInt())
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
            }
            // 解析日期（MM-dd 或 MM月dd日）
            val dateMatch = Regex("(\\d{1,2})[-月](\\d{1,2})").find(date)
            if (dateMatch != null) {
                cal.set(Calendar.MONTH,       dateMatch.groupValues[1].toInt() - 1)
                cal.set(Calendar.DAY_OF_MONTH, dateMatch.groupValues[2].toInt())
            }
            val ts = cal.timeInMillis
            // 批次4-2-4 修复：原先条件 dateMatch == null 导致指定日期已过去时
            // 不执行 +1 天（ts 保持过去时间戳），外层 triggerAtMs > now 检查
            // 走到 else 分支，闹钟完全不注册，提醒静默失效。
            // 去掉 dateMatch == null 条件，始终检查并 +1 天。
            if (ts <= System.currentTimeMillis()) ts + 86400_000L else ts
        } catch (e: Exception) {
            fallback + 3600_000L  // 默认 1 小时后
        }
    }

    /**
     * 通过 AlarmManager 注册精确闹钟。
     * Android 12+ 需要 SCHEDULE_EXACT_ALARM 权限。
     */
    // 批次4-3-1 修复：改为 internal，供 BootReceiver 开机恢复提醒闹钟时复用
    internal fun scheduleAlarm(requestCode: Int, text: String, triggerAtMs: Long, characterId: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("reminder_text", text)
            putExtra("reminder_id", requestCode)
            // U2 延伸修复：携带 characterId，供 ReminderReceiver 在通知上加「查看日程」按钮
            putExtra("character_id", characterId)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT

        val pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, flags)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent
                )
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
            }
        } catch (e: SecurityException) {
            // Android 12+ 未获得精确闹钟权限时降级到非精确
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ③ ClipboardWriteTool
// ─────────────────────────────────────────────────────────────

/**
 * 写入系统剪贴板工具（Phase 18）。
 *
 * 标签格式：<tool:clipboard_write text="要复制的内容"/>
 *
 * 实现：调用 Android ClipboardManager，主线程执行。
 * 使用场景：「帮我把这段代码复制到剪贴板」「把结果复制出来」。
 */
class ClipboardWriteTool(private val context: Context) : AgentTool {

    override val name      = "clipboard_write"
    override val description = "将文本写入系统剪贴板，用于「把这段复制出来」这类请求"
    override val paramKeys = listOf("text")

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val text = params["text"]
        if (text.isNullOrEmpty()) {
            return ToolResult(name, false, "", "缺少 text 参数")
        }

        return try {
            withContext(Dispatchers.Main) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("zaijian_copy", text)
                clipboard.setPrimaryClip(clip)
            }
            ToolResult(
                toolName = name,
                success  = true,
                content  = "已将内容复制到剪贴板（${text.length} 个字符）。",
                userHint = "正在复制到剪贴板…",
            )
        } catch (e: Exception) {
            ToolResult(name, false, "复制到剪贴板时出了问题：${e.message?.take(60)}", e.message)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ④ QrDecodeTool
// ─────────────────────────────────────────────────────────────

/**
 * 二维码内容解析工具（Phase 18）。
 *
 * 标签格式：<tool:qr_decode content="二维码内容或URL"/>
 *
 * 注意：本工具不解码图像（不依赖 ZXing），而是解析用户已粘贴的二维码文本内容，
 * 并做语义分类（URL / 微信 / 支付 / vCard / 纯文本）。
 *
 * 更常见用法：用户直接粘贴扫到的内容，角色帮助理解这段内容是什么。
 */
class QrDecodeTool : AgentTool {

    override val name      = "qr_decode"
    override val description = "解析用户粘贴的二维码文本内容并做语义分类（不解码图像）"
    override val paramKeys = listOf("content")

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
        val content = params["content"]?.trim()
        if (content.isNullOrEmpty()) {
            return@withContext ToolResult(name, false, "", "缺少 content 参数")
        }

        return@withContext try {
            val type = classifyQrContent(content)
            val analysis = buildString {
                appendLine("[二维码内容解析]")
                appendLine("类型：$type")
                appendLine("内容：$content")
                when {
                    content.startsWith("http://") || content.startsWith("https://") -> {
                        val domain = try {
                            java.net.URL(content).host
                        } catch (_: Exception) { "未知域名" }
                        appendLine("域名：$domain")
                    }
                    content.startsWith("WIFI:") -> {
                        appendLine("提示：这是 Wi-Fi 连接信息，可直接扫码连接网络。")
                    }
                    content.startsWith("BEGIN:VCARD") -> {
                        appendLine("提示：这是名片信息，包含联系人数据。")
                    }
                    content.startsWith("weixin://") || content.contains("weixin.qq.com") -> {
                        appendLine("提示：这是微信相关链接。")
                    }
                    content.startsWith("alipay://") || content.contains("alipay.com") -> {
                        appendLine("提示：这是支付宝相关链接，请谨慎确认后再操作。")
                    }
                }
            }.trimEnd()

            ToolResult(
                toolName = name,
                success  = true,
                content  = analysis,
                userHint = "正在解析二维码…",
            )
        } catch (e: Exception) {
            ToolResult(name, false, "二维码解析出错：${e.message?.take(80)}", e.message)
        }
    }

    private fun classifyQrContent(content: String): String = when {
        content.startsWith("http://") || content.startsWith("https://") -> "网页链接（URL）"
        content.startsWith("WIFI:")         -> "Wi-Fi 连接信息"
        content.startsWith("BEGIN:VCARD")   -> "名片（vCard）"
        content.startsWith("BEGIN:VEVENT")  -> "日历事件（iCal）"
        content.startsWith("weixin://")     -> "微信链接"
        content.contains("weixin.qq.com")   -> "微信链接"
        content.startsWith("alipay://")     -> "支付宝链接"
        content.contains("alipay.com")      -> "支付宝链接"
        content.startsWith("mailto:")       -> "电子邮件地址"
        content.startsWith("tel:")          -> "电话号码"
        content.startsWith("sms:")          -> "短信"
        content.matches(Regex("\\d{6,20}")) -> "纯数字（可能是验证码或ID）"
        else                                -> "纯文本"
    }
}

// ─────────────────────────────────────────────────────────────
//  模块注册入口
// ─────────────────────────────────────────────────────────────

/**
 * 注册所有个人助手工具（4个）。
 * 在 ZaijianApp.onCreate() 中调用。
 */
fun AgentToolRegistry.registerPersonalTools(context: Context) {
    registerAll(
        NoteSaveTool(context),
        ReminderTool(context),
        ClipboardWriteTool(context),
        QrDecodeTool(),
    )
}
