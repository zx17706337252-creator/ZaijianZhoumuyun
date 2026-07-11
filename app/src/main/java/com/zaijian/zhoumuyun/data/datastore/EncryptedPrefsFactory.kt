package com.zaijian.zhoumuyun.data.datastore

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 批次B（1.7/1.8）统一封装：EncryptedSharedPreferences 创建逻辑此前在
 * ProviderManager / EmailAccountStore 中各自复制了一份，此处收敛为单一实现，
 * GithubConfigDataStore 的加密改造同样复用此处。
 *
 * 注意：EncryptedSharedPreferences.create() 涉及 Keystore 密钥生成，
 * 首次调用可能耗时 100-500ms（详见 ProviderManager 内注释）。调用方如
 * 需要预加载优化，应自行做懒加载缓存（ProviderManager 的做法），本工厂
 * 方法本身不做缓存 —— 每次调用都会真实创建一次 SharedPreferences 实例。
 */
internal object EncryptedPrefsFactory {

    fun create(context: Context, fileName: String): SharedPreferences {
        val appContext = context.applicationContext
        return EncryptedSharedPreferences.create(
            appContext,
            fileName,
            MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
