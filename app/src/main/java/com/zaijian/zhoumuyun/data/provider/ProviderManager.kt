package com.zaijian.zhoumuyun.data.provider

import android.content.Context
import android.content.SharedPreferences
import com.zaijian.zhoumuyun.data.datastore.EncryptedPrefsFactory
import com.zaijian.zhoumuyun.util.ZLog

/**
 * API 提供商管理器。
 *
 * 职责：
 * - 存储 / 读取 API Key（EncryptedSharedPreferences，不明文落盘）
 * - 管理当前活跃 Provider 实例
 * - 提供快捷的 activeProvider 供 ChatViewModel 调用
 *
 * 使用方式（单例，由 Application 初始化）：
 *   ProviderManager.init(applicationContext)
 *   ProviderManager.instance.activeProvider
 */
class ProviderManager private constructor(context: Context) {

    // EncryptedSharedPreferences.create() 涉及 Keystore 密钥生成，
    // 首次启动可能耗时 100-500ms。改为延迟初始化，在首次访问时才创建。
    private val appContext = context.applicationContext
    @Volatile private var prefsCache: SharedPreferences? = null

    /**
     * P1-13-18 修复，Phase 3（3.3）改为多订阅者模式：
     * Key 或活跃 Provider 变更时的回调列表。
     * 原为单一可空属性（直接赋值），后注册者会静默覆盖前一个且无任何警告。
     * 当前项目只有 ZaijianApp 一处注册，暂无实际冲突，但属于架构预防性修复——
     * 改为列表后，未来任何新模块也需要监听 Provider 配置变更时可以安全追加，
     * 不会互相覆盖。
     * 在 IO 线程（apply() 回调）被调用，各订阅者需自行切换线程。
     */
    private val configChangedListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** 注册一个 Provider 配置变更监听器（可多次调用，追加而非覆盖）。 */
    fun addOnProviderConfigChangedListener(listener: () -> Unit) {
        configChangedListeners.add(listener)
    }

    private fun notifyConfigChanged() {
        configChangedListeners.forEach { it() }
    }

    // L-3 修复：preloadAsync() 每次调用都创建新 Thread，若被多次调用（例如配置变更
    // 导致 Activity 重建后再次触发预加载），会产生多余的裸线程。prefs 本身已有
    // synchronized + @Volatile 双重检查锁保护实际初始化不会重复执行，但避免无意义的
    // 线程创建仍有必要，加标志位短路重复调用。
    @Volatile private var preloadStarted = false

    private val prefs: SharedPreferences
        get() {
            return prefsCache ?: synchronized(this) {
                // 批次B：改为复用 EncryptedPrefsFactory（原地内联创建逻辑与
                // EmailAccountStore/GithubConfigDataStore 完全重复，现收敛为一处）。
                prefsCache ?: EncryptedPrefsFactory
                    .create(appContext, "zaijian_api_keys")
                    .also { prefsCache = it }
            }
        }

    /**
     * 在后台线程预加载 prefs，避免后续 UI 线程访问时阻塞。
     * 由 ZaijianApp.onCreate() 在 IO 协程中调用。
     */
    fun preloadAsync() {
        if (preloadStarted) return
        preloadStarted = true
        // 后台线程预加载 prefs（触发 EncryptedSharedPreferences 首次创建）。
        // 原 GlobalScope.let { scope -> Thread{...}.start() } 中 scope 从未被使用，属死代码，已移除。
        Thread {
            try { prefs } catch (e: Exception) { ZLog.w("ProviderManager", "预加载 prefs 失败: ${e.message}") }
        }.start()
    }

    // ── Key 存取 ─────────────────────────────────────────────

    fun saveKey(providerId: String, apiKey: String) {
        prefs.edit().putString("key_$providerId", apiKey).apply()
        notifyConfigChanged()  // P1-13-18
    }

    fun getKey(providerId: String): String? =
        prefs.getString("key_$providerId", null)

    fun saveActiveProviderId(providerId: String) {
        prefs.edit().putString("active_provider", providerId).apply()
        notifyConfigChanged()  // P1-13-18
    }

    fun getActiveProviderId(): String =
        prefs.getString("active_provider", "deepseek") ?: "deepseek"

    fun saveCustomBaseUrl(url: String) {
        prefs.edit().putString("custom_base_url", url).apply()
    }

    fun getCustomBaseUrl(): String =
        prefs.getString("custom_base_url", "") ?: ""

    fun saveCustomModel(model: String) {
        prefs.edit().putString("custom_model", model).apply()
    }

    fun getCustomModel(): String =
        prefs.getString("custom_model", "") ?: ""

    /** 存/取各平台的模型名 override（用于需要用户填 endpoint id 的平台，如火山方舟） */
    fun saveProviderModel(providerId: String, model: String) {
        prefs.edit().putString("model_$providerId", model).apply()
    }

    fun getProviderModel(providerId: String): String =
        prefs.getString("model_$providerId", "") ?: ""

    // ── 获取活跃 Provider ────────────────────────────────────

    /**
     * 根据当前配置构建活跃 Provider 实例。
     * 如果 API Key 未配置，返回 null（UI 层提示用户配置）。
     */
    val activeProvider: LLMProvider?
        get() {
            val id = getActiveProviderId()
            return when (id) {
                "deepseek" -> {
                    val key = getKey("deepseek")?.takeIf { it.isNotBlank() } ?: return null
                    OpenAICompatProvider.deepSeek(key)
                }
                "volcengine" -> {
                    val key = getKey("volcengine")?.takeIf { it.isNotBlank() } ?: return null
                    val model = getProviderModel("volcengine")
                    OpenAICompatProvider.volcEngine(key, model)
                }
                "aliyun" -> {
                    val key = getKey("aliyun")?.takeIf { it.isNotBlank() } ?: return null
                    OpenAICompatProvider.aliyun(key)
                }
                "opencodego" -> {
                    val key = getKey("opencodego")?.takeIf { it.isNotBlank() } ?: return null
                    val model = getProviderModel("opencodego")
                    OpenAICompatProvider.opencodeGo(key, model)
                }
                "custom" -> {
                    val key = getKey("custom")?.takeIf { it.isNotBlank() } ?: return null
                    val url = getCustomBaseUrl().ifEmpty { return null }
                    val model = getCustomModel().ifEmpty { return null }
                    OpenAICompatProvider.custom(url, key, model)
                }
                else -> null
            }
        }

    companion object {
        @Volatile
        private var INSTANCE: ProviderManager? = null

        fun init(context: Context): ProviderManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ProviderManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        val instance: ProviderManager
            get() = INSTANCE ?: throw IllegalStateException(
                "ProviderManager 未初始化，请在 Application.onCreate() 中调用 ProviderManager.init()"
            )
    }
}
