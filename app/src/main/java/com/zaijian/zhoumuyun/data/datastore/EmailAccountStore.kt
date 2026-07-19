package com.zaijian.zhoumuyun.data.datastore

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.Session

/**
 * 邮件账号配置（真实收发用）。
 *
 * 当前仅支持 QQ 邮箱（SMTP+IMAP 均为 ssl.qq.com，端口固定）。
 *
 * 重要：QQ 邮箱不能直接用 QQ 密码登录 SMTP/IMAP，必须使用「授权码」：
 *   QQ邮箱网页版 → 设置 → 账户 → POP3/IMAP/SMTP服务 → 开启 IMAP/SMTP 服务 → 生成授权码
 *
 * 批次B（1.7）修复：此前此处硬编码了真实邮箱地址与授权码作为明文回退值，
 * 授权码一旦随源码/交付物流出即视为泄露。现改为：加密存储为空时视为未配置
 * （[EmailAccount.isConfigured] = false），不再有任何硬编码回退。
 *
 * 首次使用需调用 [saveAccount] 写入一次真实账号（会加密落盘），此后
 * [getAccount] 从 EncryptedSharedPreferences 读取。
 *
 * 【重要】旧版本硬编码的授权码（robinvanpersie0610@qq.com 对应的授权码）
 * 已随历史交付物泄露，务必前往 QQ 邮箱网页版重新生成一个新的授权码，
 * 使旧授权码失效，再通过 saveAccount 写入新授权码。
 */
enum class EmailProvider(
    val displayName: String,
    val smtpHost: String,
    val smtpPort: Int,
    val imapHost: String,
    val imapPort: Int,
) {
    QQ(
        displayName = "QQ邮箱",
        smtpHost = "smtp.qq.com",
        smtpPort = 465,
        imapHost = "imap.qq.com",
        imapPort = 993,
    ),
}

data class EmailAccount(
    val provider: EmailProvider = EmailProvider.QQ,
    val address: String = "",
    val authCode: String = "",
) {
    val isConfigured: Boolean
        get() = address.isNotBlank() && authCode.isNotBlank()
}

/**
 * 邮箱账号加密存储。
 *
 * 与 [com.zaijian.zhoumuyun.data.provider.ProviderManager] 一致的方案：
 * EncryptedSharedPreferences（AES256_SIV 键 + AES256_GCM 值），授权码不明文落盘。
 */
class EmailAccountStore(context: Context) {

    // 懒加载：EncryptedSharedPreferences.create() 首次调用有 Keystore 生成耗时，
    // 与 ProviderManager 保持一致的双重检查锁模式。
    private val appContext = context.applicationContext
    @Volatile private var prefsCache: SharedPreferences? = null

    private val prefs: SharedPreferences
        get() = prefsCache ?: synchronized(this) {
            prefsCache ?: EncryptedPrefsFactory.create(appContext, "zaijian_email_account")
                .also { prefsCache = it }
        }

    /**
     * 修复（第4窗口审查报告问题1）：原为普通函数，内部直接调用
     * EncryptedSharedPreferences.getString()/commit()，属于同步磁盘 IO +
     * Keystore 操作的重型阻塞调用。若调用方在主线程直接调用会有 ANR 风险。
     * 现改为 suspend + withContext(Dispatchers.IO)，与同目录 GithubConfigDataStore
     * 的全部 7 个 suspend 方法保持一致的 API 契约。
     */
    suspend fun getAccount(): EmailAccount = withContext(Dispatchers.IO) {
        val providerName = prefs.getString(KEY_PROVIDER, EmailProvider.QQ.name) ?: EmailProvider.QQ.name
        val provider = runCatching { EmailProvider.valueOf(providerName) }.getOrDefault(EmailProvider.QQ)
        EmailAccount(
            provider = provider,
            address  = prefs.getString(KEY_ADDRESS, "") ?: "",
            authCode = prefs.getString(KEY_AUTH_CODE, "") ?: "",
        )
    }

    suspend fun saveAccount(account: EmailAccount): Unit = withContext(Dispatchers.IO) {
        prefs.edit()
            .putString(KEY_PROVIDER, account.provider.name)
            .putString(KEY_ADDRESS, account.address.trim())
            .putString(KEY_AUTH_CODE, account.authCode.trim())
            .commit()
        Unit
    }

    suspend fun clearAccount(): Unit = withContext(Dispatchers.IO) {
        prefs.edit().clear().commit()
        Unit
    }

    /**
     * 设置页"测试连接"用：只做 IMAP 登录握手验证账号+授权码是否正确，
     * 不打开任何文件夹、不拉取邮件内容，登录成功立即断开。
     *
     * 用 IMAP 而非 SMTP 做验证：SMTP 的 connect() 在部分实现下对错误凭据的
     * 反馈不如 IMAP 明确（有些服务器 SMTP 鉴权失败要等到实际 MAIL FROM 才报错），
     * IMAP store.connect() 鉴权失败会直接抛 AuthenticationFailedException，
     * 判定更可靠；且 IMAP/SMTP 用同一套地址+授权码，验证一个即可代表两者均可用。
     */
    suspend fun testConnection(account: EmailAccount): EmailTestResult = withContext(Dispatchers.IO) {
        if (!account.isConfigured) {
            return@withContext EmailTestResult.Failure("配置不完整：邮箱地址 / 授权码均不能为空")
        }

        var store: javax.mail.Store? = null
        try {
            val props = Properties().apply {
                put("mail.imap.host", account.provider.imapHost)
                put("mail.imap.port", account.provider.imapPort.toString())
                put("mail.imap.ssl.enable", "true")
                put("mail.imap.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
                put("mail.imap.connectiontimeout", "10000")
                put("mail.imap.timeout", "10000")
            }
            val session = Session.getInstance(props)
            store = session.getStore("imap")
            store.connect(account.provider.imapHost, account.address, account.authCode)
            EmailTestResult.Success
        } catch (e: javax.mail.AuthenticationFailedException) {
            EmailTestResult.Failure("登录失败：邮箱地址或授权码不正确（注意 QQ 邮箱需用「授权码」而非 QQ 登录密码）")
        } catch (e: Exception) {
            val msg = e.message ?: "未知错误"
            val friendly = when {
                msg.contains("timed out", ignoreCase = true) || msg.contains("timeout", ignoreCase = true) ->
                    "连接超时：请检查网络连接"
                msg.contains("Unknown IMAP host", ignoreCase = true) ->
                    "无法连接邮件服务器：请检查网络连接"
                else -> "连接失败：${msg.take(100)}"
            }
            EmailTestResult.Failure(friendly)
        } finally {
            runCatching { store?.close() }
        }
    }

    private companion object {
        const val KEY_PROVIDER  = "provider"
        const val KEY_ADDRESS   = "address"
        const val KEY_AUTH_CODE = "auth_code"
    }
}

/** [EmailAccountStore.testConnection] 的结果类型，携带具体失败原因供 UI 展示。 */
sealed class EmailTestResult {
    object Success : EmailTestResult()
    data class Failure(val reason: String) : EmailTestResult()
}
