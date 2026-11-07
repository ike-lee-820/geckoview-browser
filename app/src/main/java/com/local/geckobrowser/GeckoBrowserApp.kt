package com.local.geckobrowser

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import com.local.geckobrowser.browser.delegates.AutocompleteStorageDelegateImpl
import com.local.geckobrowser.data.AppDatabase
import com.local.geckobrowser.data.PreferencesDataStore
import com.local.geckobrowser.diag.CrashReporter
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

    /**
     * 由 BrowserViewModel 注册：系统内存紧张时主动释放后台标签的 GeckoSession，
     * 降低被 lmkd / 厂商内存策略直接 SIGKILL 的概率。纯应用层逻辑，无 GeckoView API 调用。
     */
    @Volatile
    var memoryPressureHandler: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        // 先建立本地日志通道：检查上次会话 + 写 boot 自检记录。
        // CrashReporter 内部自己判主进程，非主进程不会写会话标记。
        CrashReporter.init(this)

        // 【本次修复】GeckoView 会 fork 内容/GPU/socket 子进程，每个子进程都会实例化
        // Application 并跑 onCreate。旧实现无条件执行下面的初始化与 GeckoRuntime.create()，
        // 等于在子进程里也拉起一套 Gecko 引擎与 Room/DataStore 连接：
        //   - 违反 JavaDoc「GeckoRuntime 每进程只初始化一次」的正确落地（子进程根本不该持有）
        //   - 叠加 native 内存与线程峰值，容易被 vivo 内存策略直接 SIGKILL(9)
        // 用户 build29 日志里同一秒有多个 EXIT_SELF 的 pid，正是这些子进程。
        if (!isMainProcess()) return

        CrashReporter.markStage("app_oncreate_start")
        // 主进程内注入全局 Context：SearchEngineManager.searchUrl 需要读本地 Room，
        // 放在这里比依赖 Activity/ViewModel 创建顺序更可靠（非主进程不会走到这一步）。
        com.local.geckobrowser.feature.SearchEngineManager.appContextHolder = applicationContext
        database = AppDatabase.build(this)
        CrashReporter.markStage("database_ready")
        preferences = PreferencesDataStore(this)
        updateManager = UpdateManager(this)
        // 预热 Gecko：可以在真正加载页面前完成初始化，符合包摘要中「预加载 Gecko」的用法。
        // 预热失败必须落盘，否则这类启动期异常只会静默消失。
        CrashReporter.markStage("gecko_runtime_create_before")
        runCatching { GeckoEngine.runtime(this) }
            .onFailure { t -> CrashReporter.record("gecko_runtime_init", t) }
        CrashReporter.markStage("gecko_runtime_create_after")
        // 关键顺序：GeckoRuntime 会注册自己的 CrashHandler 覆盖全局 UEH，
        // 因此自定义 handler 必须在它之后安装，否则上一次崩溃不会留下任何日志。
        CrashReporter.installHandler()
    }

    private fun isMainProcess(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            runCatching { java.io.File("/proc/self/cmdline").readText().substringBefore('\u0000') }
                .getOrDefault(packageName)
        }
        return name == packageName
    }

    /**
     * Android 系统内存回调（非 GeckoView API）。在进程即将被 lmkd 回收前主动降级：
     * 释放后台非激活标签的 Session，元数据始终保留，切回时由 TabSessionManager 按需重建。
     * 这比等着被 SIGKILL(9) 更可控，也避免用户回来时看到整页重载。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
        runCatching { memoryPressureHandler?.invoke() }
            .onFailure { t -> CrashReporter.record("trim_memory", t, extra = "level=$level") }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        runCatching { memoryPressureHandler?.invoke() }
            .onFailure { t -> CrashReporter.record("low_memory", t) }
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
        // 【按需求删除追踪保护功能】不再配置 antiTracking 位掩码，也不再提供
        // STANDARD / STRICT / CUSTOM 三档开关。GeckoSession 侧同步关闭 useTrackingProtection。
        // 保留下面与追踪保护无关的 Cookie 行为、Safe Browsing 关闭等本地隐私配置。
        val contentBlocking = ContentBlocking.Settings.Builder()
            .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS)
            // 无云端：不启用远程 Safe Browsing 查询，也不启用全局缓存查询。
            .safeBrowsing(ContentBlocking.SafeBrowsing.NONE)
            .safeBrowsingGlobalCacheEnabled(false)
            .contentBlockingDatabase(false)
            // 查询参数剥离属于反追踪能力，随追踪保护功能一并移除。
            .queryParameterStrippingEnabled(false)
            .queryParameterStrippingPrivateBrowsingEnabled(false)
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
            // JavaDoc：crashPullNeverShowAgain(boolean) 禁用 CrashPullController.Delegate
            // 请求「拉取崩溃报告」的通知。只关闭通知，不改崩溃处理链路，符合无云端约束。
            // 需核对当前 GeckoView Beta 版本 Changelog。
            .crashPullNeverShowAgain(true)
            // cookieBehavior 等通过 contentBlocking(ContentBlocking.Settings) 注入；
            // 追踪保护相关配置已按需求移除。
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
