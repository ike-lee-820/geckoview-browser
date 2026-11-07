package com.local.geckobrowser

import android.app.Application
import android.content.Context
import com.local.geckobrowser.browser.delegates.AutocompleteStorageDelegateImpl
import com.local.geckobrowser.data.AppDatabase
import com.local.geckobrowser.data.PreferencesDataStore
import com.local.geckobrowser.update.UpdateManager
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/**
 * 应用入口：持有进程级 GeckoRuntime 单例与本地存储。
 *
 * JavaDoc 包摘要明确 GeckoRuntime 是启动和初始化 Gecko 的入口，且每个进程只能初始化一次；
 * 因此这里用双重检查锁做进程级单例，禁止在 Activity / 每个标签里重复 create()。
 */
class GeckoBrowserApp : Application() {
    lateinit var database: AppDatabase
        private set
    lateinit var preferences: PreferencesDataStore
        private set
    lateinit var updateManager: UpdateManager
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.build(this)
        preferences = PreferencesDataStore(this)
        updateManager = UpdateManager(this)
        installCrashLogger()
        // 预热 Gecko：可以在真正加载页面前完成初始化，符合包摘要中「预加载 Gecko」的用法。
        runCatching { GeckoEngine.runtime(this) }
    }

    /**
     * 本地崩溃日志：只写入应用私有目录，不上传、不联网。
     * 用于定位启动后异步阶段（扩展加载 / 会话恢复 / 更新检查）的闪退。
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val dir = java.io.File(filesDir, "crashes").apply { mkdirs() }
                java.io.File(dir, "crash-${System.currentTimeMillis()}.txt").writeText(
                    buildString {
                        append("time=").append(java.util.Date()).append('\n')
                        append("thread=").append(thread.name).append('\n')
                        append(android.util.Log.getStackTraceString(throwable))
                    }
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}

/** GeckoRuntime 全局单例与全局设置装配。 */
object GeckoEngine {

    @Volatile
    private var runtimeInstance: GeckoRuntime? = null

    fun runtime(context: Context): GeckoRuntime {
        runtimeInstance?.let { return it }
        return synchronized(this) {
            runtimeInstance ?: create(context.applicationContext).also { runtimeInstance = it }
        }
    }

    private fun create(context: Context): GeckoRuntime {
        // 规格中提到的 TrackingProtectionPolicy 在当前 mozilla-central JavaDoc 中不存在；
        // 追踪保护由 ContentBlocking.AntiTracking / ContentBlocking.Settings 表达，见 DOCS_READ.md。
        // 三档策略由应用层 TrackingPolicy 映射到下面的位掩码。
        val contentBlocking = ContentBlocking.Settings.Builder()
            .antiTracking(
                ContentBlocking.AntiTracking.DEFAULT or
                    ContentBlocking.AntiTracking.AD or
                    ContentBlocking.AntiTracking.ANALYTIC or
                    ContentBlocking.AntiTracking.SOCIAL or
                    ContentBlocking.AntiTracking.FINGERPRINTING or
                    ContentBlocking.AntiTracking.CRYPTOMINING or
                    ContentBlocking.AntiTracking.EMAIL
            )
            .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS)
            // 无云端：不启用远程 Safe Browsing 查询，也不启用全局缓存查询。
            .safeBrowsing(ContentBlocking.SafeBrowsing.NONE)
            .safeBrowsingGlobalCacheEnabled(false)
            .contentBlockingDatabase(false)
            // 查询参数剥离：无痕模式下默认开启，普通模式由设置项决定是否开启。
            // 需核对当前 GeckoView Beta 版本 Changelog。
            .queryParameterStrippingEnabled(true)
            .queryParameterStrippingPrivateBrowsingEnabled(true)
            .build()

        // cookieBannerGlobalRulesEnabled 在部分版本中已被移除，本项目不调用。
        // 需核对当前 GeckoView Beta 版本 Changelog。
        val settings = GeckoRuntimeSettings.Builder()
            .contentBlocking(contentBlocking)
            .extensionsWebAPIEnabled(true)   // 启用本地侧载 WebExtension 的 WebAPI
            .remoteDebuggingEnabled(false)   // 不开放远程调试端口
            .aboutConfigEnabled(false)       // 不暴露 about:config
            .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM)
            .fontSizeFactor(1.0f)
            .loginAutofillEnabled(true)
            // 规格中的 trackingProtectionCategories / cookieBehavior 在 RuntimeSettings.Builder 中
            // 并无同名方法，统一通过 contentBlocking(ContentBlocking.Settings) 注入。
            .build()

        val runtime = GeckoRuntime.create(context, settings)
        // Autocomplete.StorageDelegate 挂在 Runtime 上（GeckoRuntime.setAutocompleteStorageDelegate）。
        runtime.setAutocompleteStorageDelegate(AutocompleteStorageDelegateImpl(context))
        return runtime
    }

    /** 退出时清除 Cookie / 缓存 / 站点数据；long 标志来自 StorageController.ClearFlags。 */
    fun clearBrowsingData(context: Context, flags: Long) {
        runtime(context).storageController.clearData(flags)
    }
}
