package com.zaijian.zhoumuyun.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.githubConfigDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "github_config")

object GithubConfigKeys {
    val OWNER = stringPreferencesKey("owner")
    val REPO  = stringPreferencesKey("repo")
    val TOKEN = stringPreferencesKey("token")
}

/**
 * 个人项目，无需设置 UI：owner 硬编码在此，token 已改为不再硬编码
 * （原硬编码 token 存在泄露风险，2026-07 已清空，需在 DataStore 里
 * 手动配置真实 token 后 GithubConfig.isConfigured 才会为 true）。
 * 账号下有 3 个仓库（NyxChat-Android / ZaijianZhoumuyun / farm）共用同一个 token，
 * repo 字段不再硬编码，由各工具调用方按当前操作的项目动态传入
 * （见 [GithubConfig] 默认值仍为空，调用处用 .copy(repo = "...") 指定）。
 */
private const val HARDCODED_OWNER = "zx17706337252-creator"
private const val HARDCODED_TOKEN = ""

/** 三个仓库名常量，调用方直接引用，避免手敲拼错。 */
object GithubRepos {
    const val NYX_CHAT_ANDROID   = "NyxChat-Android"
    const val ZAIJIAN_ZHOUMUYUN  = "ZaijianZhoumuyun"
    const val FARM                = "farm"
}

/**
 * 默认操作仓库 —— 现有 CI/CD 工具（BuildApkTool / GitCommitPushTool 等）调用
 * getConfig() 时不传 repo 参数，会回退到这里。当前默认指向 ZaijianZhoumuyun
 * （本项目自身），如需切换到 NyxChat-Android 或 farm，改这一行常量即可，
 * 无需改动任何工具类代码。
 */
private const val DEFAULT_REPO = GithubRepos.ZAIJIAN_ZHOUMUYUN

data class GithubConfig(
    val owner: String = "",
    val repo: String = "",
    val token: String = "",
) {
    val isConfigured: Boolean
        get() = owner.isNotBlank() && repo.isNotBlank() && token.isNotBlank()
}

class GithubConfigDataStore(private val context: Context) {

    val configFlow: Flow<GithubConfig> = context.githubConfigDataStore.data
        .map { prefs ->
            GithubConfig(
                owner = (prefs[GithubConfigKeys.OWNER] ?: "").ifBlank { HARDCODED_OWNER },
                repo  = (prefs[GithubConfigKeys.REPO] ?: "").ifBlank { DEFAULT_REPO },
                token = (prefs[GithubConfigKeys.TOKEN] ?: "").ifBlank { HARDCODED_TOKEN },
            )
        }

    /**
     * 取配置，并指定本次操作针对哪个仓库（三选一，见 [GithubRepos]）。
     * 未传 repo 时使用 [DEFAULT_REPO]（当前为 ZaijianZhoumuyun），不会是空。
     */
    suspend fun getConfig(repo: String? = null): GithubConfig {
        val base = configFlow.first()
        return if (repo != null) base.copy(repo = repo) else base
    }

    suspend fun saveConfig(config: GithubConfig) {
        context.githubConfigDataStore.edit { prefs ->
            prefs[GithubConfigKeys.OWNER] = config.owner.trim()
            prefs[GithubConfigKeys.REPO]  = config.repo.trim()
            prefs[GithubConfigKeys.TOKEN] = config.token.trim()
        }
    }

    suspend fun saveOwner(owner: String) {
        context.githubConfigDataStore.edit { it[GithubConfigKeys.OWNER] = owner.trim() }
    }

    suspend fun saveRepo(repo: String) {
        context.githubConfigDataStore.edit { it[GithubConfigKeys.REPO] = repo.trim() }
    }

    suspend fun saveToken(token: String) {
        context.githubConfigDataStore.edit { it[GithubConfigKeys.TOKEN] = token.trim() }
    }

    suspend fun clearConfig() {
        context.githubConfigDataStore.edit { it.clear() }
    }
}
