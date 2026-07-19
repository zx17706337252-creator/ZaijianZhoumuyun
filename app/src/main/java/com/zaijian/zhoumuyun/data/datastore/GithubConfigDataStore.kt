package com.zaijian.zhoumuyun.data.datastore

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 批次B（1.8）修复：owner/repo/token 此前存于明文 preferencesDataStore，
 * token 一旦配置真实值即为明文落盘。现改为 EncryptedSharedPreferences，
 * 与 EmailAccountStore（1.7）同一根因同一方案。
 *
 * owner 常量按用户要求一并纳入加密存储管理（非敏感信息，仅为风格统一），
 * 首次读取时若加密存储为空，会用下方 DEFAULT_OWNER 做一次性播种写入。
 *
 * 账号下有 3 个仓库（NyxChat-Android / ZaijianZhoumuyun / farm）共用同一个 token，
 * repo 字段不再硬编码，由各工具调用方按当前操作的项目动态传入
 * （见 [GithubConfig] 默认值仍为空，调用处用 .copy(repo = "...") 指定）。
 *
 * token 无默认值回退：加密存储为空则 [GithubConfig.isConfigured] 为 false，
 * 需通过 saveToken / saveConfig 手动写入一次真实 token。
 */
private const val DEFAULT_OWNER = "zx17706337252-creator"

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

class GithubConfigDataStore(context: Context) {

    // 懒加载：与 EmailAccountStore / ProviderManager 一致的双重检查锁模式。
    private val appContext = context.applicationContext
    @Volatile private var prefsCache: SharedPreferences? = null

    private val prefs: SharedPreferences
        get() = prefsCache ?: synchronized(this) {
            prefsCache ?: EncryptedPrefsFactory.create(appContext, "zaijian_github_config")
                .also {
                    // 首次创建时若 owner 字段为空，播种默认值一次，行为与旧版
                    // HARDCODED_OWNER 回退等价，但落盘后即为该值本身，非明文常量回退。
                    if (it.getString(KEY_OWNER, null).isNullOrBlank()) {
                        it.edit().putString(KEY_OWNER, DEFAULT_OWNER).apply()
                    }
                    prefsCache = it
                }
        }

    // configFlow 保留原 Flow 接口以兼容调用方（ChatViewModel 等按 Flow 消费的场景），
    // 底层改为一次性读取 + MutableStateFlow，而非真正响应式的 DataStore.data。
    // 说明：GithubConfig 目前仅在工具调用前 getConfig() 拉取一次，无长期 collect 场景，
    // 该妥协不影响现有调用方行为（详见调用点核查：无处订阅 configFlow 做持续渲染）。
    val configFlow: Flow<GithubConfig>
        get() {
            val current = GithubConfig(
                owner = prefs.getString(KEY_OWNER, null)?.ifBlank { DEFAULT_OWNER } ?: DEFAULT_OWNER,
                repo  = prefs.getString(KEY_REPO, null)?.ifBlank { DEFAULT_REPO } ?: DEFAULT_REPO,
                token = prefs.getString(KEY_TOKEN, null) ?: "",
            )
            return MutableStateFlow(current).asStateFlow()
        }

    /**
     * 取配置，并指定本次操作针对哪个仓库（三选一，见 [GithubRepos]）。
     * 未传 repo 时使用 [DEFAULT_REPO]（当前为 ZaijianZhoumuyun），不会是空。
     */
    // 批次B 复查修复：EncryptedSharedPreferences.create() 及 commit() 均为同步阻塞
    // 调用（Keystore IO + 磁盘 IO），不像旧版 preferencesDataStore 会自行调度到 IO
    // 线程。调用方（CiCdPipelineWorker.doWork() 默认跑在 Dispatchers.Default，
    // CiCdStartTool 经由 AgentTool 框架调起也未保证 IO 线程）不能保证已切到 IO，
    // 因此这里由 store 自己用 withContext(Dispatchers.IO) 兜底，不再依赖调用方。
    suspend fun getConfig(repo: String? = null): GithubConfig = withContext(Dispatchers.IO) {
        val base = configFlow.first()
        if (repo != null) base.copy(repo = repo) else base
    }

    suspend fun saveConfig(config: GithubConfig) = withContext(Dispatchers.IO) {
        prefs.edit()
            .putString(KEY_OWNER, config.owner.trim())
            .putString(KEY_REPO, config.repo.trim())
            .putString(KEY_TOKEN, config.token.trim())
            .commit()
        Unit
    }

    suspend fun saveOwner(owner: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_OWNER, owner.trim()).commit()
        Unit
    }

    suspend fun saveRepo(repo: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_REPO, repo.trim()).commit()
        Unit
    }

    suspend fun saveToken(token: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_TOKEN, token.trim()).commit()
        Unit
    }

    suspend fun clearConfig() = withContext(Dispatchers.IO) {
        prefs.edit().clear().commit()
        Unit
    }

    /**
     * 设置页"测试连接"用：验证 token 本身是否有效，并区分「token 无效」
     * 与「token 有效但 owner/repo 不匹配或仓库不存在」两种失败原因，
     * 避免用户拿着一个可用的 token 却因为 repo 名打错字而误判成"token 坏了"。
     *
     * 分两步请求，而非只调一次 /repos/{owner}/{repo}：
     *   第一步：GET /user —— 仅验证 token 本身合法性 + 是否有 API 访问权限，
     *      不依赖 owner/repo 是否正确，无副作用（不创建、不修改任何资源）。
     *   第二步：GET /repos/{owner}/{repo} —— 在 token 有效的前提下，再验证
     *      当前配置的仓库确实存在且 token 有权限访问。
     * 两步都成功才返回 [GithubTestResult.Success]。
     */
    suspend fun testConnection(config: GithubConfig): GithubTestResult = withContext(Dispatchers.IO) {
        if (!config.isConfigured) {
            return@withContext GithubTestResult.Failure("配置不完整：owner / repo / token 均不能为空")
        }

        // 第一步：验证 token 本身
        val userConn = try {
            (URL("https://api.github.com/user").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Authorization", "Bearer ${config.token}")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
        } catch (e: Exception) {
            return@withContext GithubTestResult.Failure("网络连接失败：${e.message?.take(100) ?: "未知错误"}")
        }

        try {
            val userCode = userConn.responseCode
            if (userCode == 401) {
                return@withContext GithubTestResult.Failure("Token 无效或已过期，请重新生成一个 Personal Access Token")
            }
            if (userCode !in 200..299) {
                val err = runCatching { userConn.errorStream?.bufferedReader()?.use { it.readText() }?.take(150) }.getOrNull()
                return@withContext GithubTestResult.Failure("GitHub 返回 HTTP $userCode：${err ?: userConn.responseMessage}")
            }
        } catch (e: IOException) {
            // 方案 5-5：responseCode 在网络超时/TLS 失败时抛出 IOException，
            // 需包裹 catch 避免异常向上传播。
            return@withContext GithubTestResult.Failure("网络连接失败：${e.message?.take(100) ?: "未知错误"}")
        } finally {
            userConn.disconnect()
        }

        // 第二步：token 有效，再验证 owner/repo 是否可访问
        val repoConn = try {
            (URL("https://api.github.com/repos/${config.owner}/${config.repo}").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Authorization", "Bearer ${config.token}")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
        } catch (e: Exception) {
            return@withContext GithubTestResult.Failure("网络连接失败：${e.message?.take(100) ?: "未知错误"}")
        }

        return@withContext try {
            val repoCode = repoConn.responseCode
            when {
                repoCode in 200..299 -> GithubTestResult.Success
                repoCode == 404 -> GithubTestResult.Failure(
                    "Token 有效，但仓库 ${config.owner}/${config.repo} 不存在或 token 无权限访问，请检查 owner / repo 拼写"
                )
                else -> {
                    val err = runCatching { repoConn.errorStream?.bufferedReader()?.use { it.readText() }?.take(150) }.getOrNull()
                    GithubTestResult.Failure("GitHub 返回 HTTP $repoCode：${err ?: repoConn.responseMessage}")
                }
            }
        } catch (e: IOException) {
            GithubTestResult.Failure("网络连接失败：${e.message?.take(100) ?: "未知错误"}")
        } finally {
            repoConn.disconnect()
        }
    }

    private companion object {
        const val KEY_OWNER = "owner"
        const val KEY_REPO  = "repo"
        const val KEY_TOKEN = "token"
    }
}

/** [GithubConfigDataStore.testConnection] 的结果类型，携带具体失败原因供 UI 展示。 */
sealed class GithubTestResult {
    object Success : GithubTestResult()
    data class Failure(val reason: String) : GithubTestResult()
}
