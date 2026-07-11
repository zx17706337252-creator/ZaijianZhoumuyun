package com.zaijian.zhoumuyun.data.datastore

import android.content.Context
import android.content.SharedPreferences

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

    fun getAccount(): EmailAccount {
        val providerName = prefs.getString(KEY_PROVIDER, EmailProvider.QQ.name) ?: EmailProvider.QQ.name
        val provider = runCatching { EmailProvider.valueOf(providerName) }.getOrDefault(EmailProvider.QQ)
        return EmailAccount(
            provider = provider,
            address  = prefs.getString(KEY_ADDRESS, "") ?: "",
            authCode = prefs.getString(KEY_AUTH_CODE, "") ?: "",
        )
    }

    fun saveAccount(account: EmailAccount) {
        prefs.edit()
            .putString(KEY_PROVIDER, account.provider.name)
            .putString(KEY_ADDRESS, account.address.trim())
            .putString(KEY_AUTH_CODE, account.authCode.trim())
            .commit()
    }

    fun clearAccount() {
        prefs.edit().clear().commit()
    }

    private companion object {
        const val KEY_PROVIDER  = "provider"
        const val KEY_ADDRESS   = "address"
        const val KEY_AUTH_CODE = "auth_code"
    }
}
