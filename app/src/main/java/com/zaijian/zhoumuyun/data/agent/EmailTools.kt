package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.datastore.EmailAccount
import com.zaijian.zhoumuyun.data.datastore.EmailAccountStore
import com.zaijian.zhoumuyun.data.datastore.EmailProvider
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage

/**
 * EmailTools.kt — 真实邮件收发（恢复版）
 *
 * 旧包行为：EmailFetchTool（真实 IMAP 收信）+ EmailSendTool（真实 SMTP 发信，javax.mail）。
 * 当前账号体系仅支持 QQ 邮箱（[EmailProvider.QQ]），凭据使用「邮箱地址 + 授权码」
 * （非 QQ 登录密码），存于 [EmailAccountStore]（EncryptedSharedPreferences）。
 *
 * 与 [EmailDraftTool]（CreativeDocTools.kt，纯 LLM 起草草稿）配合使用：
 * 角色可先用 email_draft 起草内容，再用 email_send 真正发出。
 *
 * 注册方式（在 ZaijianApp.onCreate 中）：
 * ```kotlin
 * val emailAccountStore = EmailAccountStore(this)
 * AgentToolRegistry.registerAll(
 *     EmailSendTool(emailAccountStore),
 *     EmailFetchTool(emailAccountStore),
 * )
 * ```
 */

// ─────────────────────────────────────────────────────────────
//  内部辅助：构建 Session
// ─────────────────────────────────────────────────────────────

private fun buildSmtpSession(provider: EmailProvider): Session {
    val props = Properties().apply {
        put("mail.smtp.host", provider.smtpHost)
        put("mail.smtp.port", provider.smtpPort.toString())
        put("mail.smtp.auth", "true")
        put("mail.smtp.ssl.enable", "true")
        put("mail.smtp.socketFactory.port", provider.smtpPort.toString())
        put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
        put("mail.smtp.connectiontimeout", "15000")
        put("mail.smtp.timeout", "15000")
    }
    return Session.getInstance(props)
}

private fun buildImapSession(provider: EmailProvider): Session {
    val props = Properties().apply {
        put("mail.imap.host", provider.imapHost)
        put("mail.imap.port", provider.imapPort.toString())
        put("mail.imap.ssl.enable", "true")
        put("mail.imap.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
        put("mail.imap.connectiontimeout", "15000")
        put("mail.imap.timeout", "15000")
    }
    return Session.getInstance(props)
}

/** 友好化常见鉴权/网络错误，避免把 javax.mail 原始堆栈丢给用户。 */
private fun friendlyMailError(e: Throwable): String = when {
    e.message?.contains("authentication failed", ignoreCase = true) == true ||
        e.message?.contains("Authentication", ignoreCase = true) == true ->
        "登录失败：请检查邮箱地址和授权码是否正确（注意：QQ邮箱需用「授权码」而非QQ密码，在QQ邮箱网页版-设置-账户中生成）"
    e.message?.contains("timed out", ignoreCase = true) == true ||
        e.message?.contains("timeout", ignoreCase = true) == true ->
        "连接超时：请检查网络连接"
    e.message?.contains("Unknown SMTP host", ignoreCase = true) == true ||
        e.message?.contains("Unknown IMAP host", ignoreCase = true) == true ->
        "无法连接邮件服务器：请检查网络连接"
    else -> "邮件操作失败，请稍后重试。"
}

// ─────────────────────────────────────────────────────────────
//  ① EmailSendTool — 真实 SMTP 发信
// ─────────────────────────────────────────────────────────────

/**
 * 真实邮件发送工具（SMTP）。
 *
 * 标签格式：
 *   <tool:email_send to="对方邮箱" subject="主题" body="正文"/>
 *
 * 实现：javax.mail SMTP over SSL（QQ邮箱固定 smtp.qq.com:465）。
 * 鉴权使用邮箱地址 + 授权码（[EmailAccountStore]）。
 */
class EmailSendTool(
    private val accountStore: EmailAccountStore,
) : AgentTool {

    override val name = "email_send"
    override val description = "通过SMTP真实发送邮件（当前仅支持QQ邮箱），用于实际发送场景"
    override val paramKeys = listOf("to", "subject", "body")

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            // P1 修复：getAccount() 移入 try，避免读取邮箱配置异常导致未捕获崩溃
            val account: EmailAccount = try { accountStore.getAccount() }
                catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    return@withContext toolFailure(name, "读取邮箱配置失败，请稍后重试。", "email_account_read_failed", e)
                }
            if (!account.isConfigured) {
                return@withContext ToolResult(
                    toolName = name, success = false, content = "",
                    error = "邮箱账号未配置",
                    userHint = "邮箱未配置，请先在设置中填写邮箱地址和授权码",
                )
            }

            val to = params["to"]?.trim()
            if (to.isNullOrBlank()) {
                return@withContext ToolResult(name, false, "", "缺少 to 参数（收件人邮箱）")
            }
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(to).matches()) {
                return@withContext ToolResult(name, false, "", "收件人邮箱格式无效：$to")
            }
            val subject = params["subject"]?.trim() ?: "(无主题)"
            val body = params["body"]?.trim() ?: ""
            if (body.isBlank()) {
                return@withContext ToolResult(name, false, "", "缺少 body 参数（邮件正文）")
            }

            try {
                val session = buildSmtpSession(account.provider)
                val message = MimeMessage(session).apply {
                    setFrom(InternetAddress(account.address))
                    setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
                    setSubject(subject, "UTF-8")
                    setText(body, "UTF-8")
                }

                val transport = session.getTransport("smtp")
                try {
                    transport.connect(account.provider.smtpHost, account.address, account.authCode)
                    transport.sendMessage(message, message.allRecipients)
                } finally {
                    transport.close()
                }

                ToolResult(
                    toolName = name,
                    success  = true,
                    content  = "[邮件已发送]\n收件人：$to\n主题：$subject",
                    userHint = "正在发送邮件…",
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                toolFailure(name, friendlyMailError(e), "email_send_failed", e, tag = "EmailSend")
            }
        }
}

// ─────────────────────────────────────────────────────────────
//  ② EmailFetchTool — 真实 IMAP 收信
// ─────────────────────────────────────────────────────────────

/**
 * 真实邮件收取工具（IMAP）。
 *
 * 标签格式：
 *   <tool:email_fetch limit="5" unread_only="true"/>
 *
 * 实现：javax.mail IMAP over SSL（QQ邮箱固定 imap.qq.com:993），
 * 只读打开收件箱（Folder.READ_ONLY），不会误标记邮件状态。
 * 默认按收件时间倒序取最新 [limit] 封，limit 默认 5，最大 20。
 */
class EmailFetchTool(
    private val accountStore: EmailAccountStore,
) : AgentTool {

    override val name = "email_fetch"
    override val description = "通过IMAP只读收取最近邮件（当前仅支持QQ邮箱+授权码），用于查看收件箱"
    override val paramKeys = listOf("limit", "unread_only")

    private companion object {
        const val DEFAULT_LIMIT = 5
        const val MAX_LIMIT = 20
        const val MAX_PREVIEW_CHARS = 200
        // #25 修复：正文解析失败与"邮件本身无正文"此前都返回同一个空字符串，
        // 报告里两种情况都表现为不出现"摘要："这一行，用户无法区分。用哨兵值
        // 区分两种情况，解析失败时改为显式提示而不是静默消失。
        const val PREVIEW_PARSE_FAILED = "\u0000__PREVIEW_PARSE_FAILED__"
    }

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            // P1 修复：getAccount() 移入 try，避免读取邮箱配置异常导致未捕获崩溃
            val account: EmailAccount = try { accountStore.getAccount() }
                catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    return@withContext toolFailure(name, "读取邮箱配置失败，请稍后重试。", "email_account_read_failed", e)
                }
            if (!account.isConfigured) {
                return@withContext ToolResult(
                    toolName = name, success = false, content = "",
                    error = "邮箱账号未配置",
                    userHint = "邮箱未配置，请先在设置中填写邮箱地址和授权码",
                )
            }

            val limit = params["limit"]?.toIntOrNull()?.coerceIn(1, MAX_LIMIT) ?: DEFAULT_LIMIT
            val unreadOnly = params["unread_only"]?.lowercase() == "true"

            var store: jakarta.mail.Store? = null
            var folder: Folder? = null
            try {
                val session = buildImapSession(account.provider)
                store = session.getStore("imap")
                store.connect(account.provider.imapHost, account.address, account.authCode)

                folder = store.getFolder("INBOX")
                folder.open(Folder.READ_ONLY)

                val allMessages = folder.messages
                val candidates = if (unreadOnly) {
                    allMessages.filter { !it.isSet(Flags.Flag.SEEN) }
                } else {
                    allMessages.toList()
                }

                val picked = candidates
                    .sortedByDescending { it.sentDate ?: it.receivedDate }
                    .take(limit)

                if (picked.isEmpty()) {
                    return@withContext ToolResult(
                        toolName = name,
                        success  = true,
                        content  = if (unreadOnly) "收件箱没有未读邮件。" else "收件箱是空的。",
                        userHint = "正在查收邮件…",
                    )
                }

                val report = buildString {
                    appendLine("[收件箱最新 ${picked.size} 封邮件]")
                    picked.forEachIndexed { idx, msg ->
                        val from = (msg.from?.firstOrNull() as? InternetAddress)?.let {
                            if (it.personal != null) "${it.personal} <${it.address}>" else it.address
                        } ?: "未知发件人"
                        val subject = msg.subject ?: "(无主题)"
                        val date = msg.sentDate ?: msg.receivedDate
                        val previewRaw = extractTextPreview(msg)
                        val unread = !msg.isSet(Flags.Flag.SEEN)

                        appendLine("${idx + 1}. ${if (unread) "【未读】" else ""}$subject")
                        appendLine("   发件人：$from")
                        if (date != null) appendLine("   时间：$date")
                        // #25 修复：解析失败时明确提示，不再和"邮件本身无正文"一样静默不显示
                        if (previewRaw == PREVIEW_PARSE_FAILED) {
                            appendLine("   摘要：（正文解析失败，无法预览，可能是不支持的邮件格式）")
                        } else if (previewRaw.isNotBlank()) {
                            appendLine("   摘要：${previewRaw.take(MAX_PREVIEW_CHARS)}")
                        }
                    }
                }.trimEnd()

                ToolResult(
                    toolName = name,
                    success  = true,
                    content  = report,
                    userHint = "正在查收邮件…",
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                toolFailure(name, friendlyMailError(e), "email_fetch_failed", e, tag = "EmailFetch")
            } finally {
                runCatching { folder?.close(false) }
                runCatching { store?.close() }
            }
        }

    /** 提取邮件正文的纯文本预览（递归遍历嵌套 Multipart，支持 multipart/alternative 等常见结构）。 */
    private fun extractTextPreview(message: Message): String {
        return try {
            extractTextFromContent(message.content)
                .replace(Regex("\\s+"), " ").trim()
        } catch (e: Throwable) {
            ZLog.d("EmailTools", "extractTextPreview 解析邮件正文失败: ${e.message}")
            PREVIEW_PARSE_FAILED
        }
    }

    /** 递归提取 text/plain 内容，支持嵌套 multipart/mixed → multipart/alternative → text/plain */
    // P2 修复：添加 depth 参数防止恶意嵌套邮件导致 StackOverflow
    private fun extractTextFromContent(content: Any, depth: Int = 0): String {
        if (depth > 10) return ""  // 防止恶意嵌套邮件导致 StackOverflow
        return when (content) {
            is String -> content
            is jakarta.mail.Multipart -> {
                for (i in 0 until content.count) {
                    val part = content.getBodyPart(i)
                    if (part.isMimeType("text/plain")) {
                        val text = part.content as? String
                        if (!text.isNullOrBlank()) return text
                    } else if (part.isMimeType("multipart/*")) {
                        val nested = extractTextFromContent(part.content, depth + 1)
                        if (nested.isNotBlank()) return nested
                    }
                }
                ""
            }
            else -> ""
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  模块注册入口
// ─────────────────────────────────────────────────────────────

/**
 * 注册真实邮件收发工具（2个：email_send + email_fetch）。
 * 在 ZaijianApp.onCreate() 中调用。
 *
 * 注意：email_draft（纯起草，不发送）仍由 CreativeDocTools.registerCreativeDocTools 注册，
 * 三者互不冲突，可配合使用。
 */
fun AgentToolRegistry.registerEmailTools(accountStore: EmailAccountStore) {
    registerAll(
        EmailSendTool(accountStore),
        EmailFetchTool(accountStore),
    )
}
