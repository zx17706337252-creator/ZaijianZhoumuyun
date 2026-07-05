package com.zaijian.zhoumuyun.data.datastore

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 邮件账号配置（真实收发用）。
 *
 * 当前仅支持 QQ 邮箱（SMTP+IMAP 均为 ssl.qq.com，端口固定）。
 *
 * 重要：QQ 邮箱不能直接用 QQ 密码登录 SMTP/IMAP，必须使用「授权码」：
 *   QQ邮箱网页版 → 设置 → 账户 → POP3/IMAP/SMTP服务 → 开启 IMAP/SMTP 服务 → 生成授权码
 *
 * 个人项目，无需设置 UI：直接在此处硬编码账号即可，[EmailAccountStore] 会在
 * 加密存储为空时回退使用下面两个常量。
 */
private const val HARDCODED_EMAIL_ADDRESS = "robinvanpersie0610@qq.com"
private const val HARDCODED_AUTH_CODE     = "qzzlqyyawecxdhbb"

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

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "zaijian_email_account",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun getAccount(): EmailAccount {
        val providerName = prefs.getString(KEY_PROVIDER, EmailProvider.QQ.name) ?: EmailProvider.QQ.name
        val provider = runCatching { EmailProvider.valueOf(providerName) }.getOrDefault(EmailProvider.QQ)
        val storedAddress  = prefs.getString(KEY_ADDRESS, "") ?: ""
        val storedAuthCode = prefs.getString(KEY_AUTH_CODE, "") ?: ""
        return EmailAccount(
            provider = provider,
            // 加密存储为空时回退到硬编码常量（个人项目场景，省去设置 UI）
            address  = storedAddress.ifBlank { HARDCODED_EMAIL_ADDRESS },
            authCode = storedAuthCode.ifBlank { HARDCODED_AUTH_CODE },
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
