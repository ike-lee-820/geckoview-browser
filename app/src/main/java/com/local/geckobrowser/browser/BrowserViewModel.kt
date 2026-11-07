package com.local.geckobrowser.browser

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.local.geckobrowser.GeckoBrowserApp
import com.local.geckobrowser.GeckoEngine
import com.local.geckobrowser.data.AppDatabase
import com.local.geckobrowser.data.DownloadEntity
import com.local.geckobrowser.data.HistoryEntity
import com.local.geckobrowser.feature.DownloadRegistry
import com.local.geckobrowser.feature.DownloadWorker
import com.local.geckobrowser.feature.SearchEngineManager
import com.local.geckobrowser.feature.TranslationManager
import com.local.geckobrowser.update.UpdateManager
import com.local.geckobrowser.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.SessionFinder
import org.mozilla.geckoview.StorageController
import java.util.UUID

/**
 * UDF 中枢：Compose 只发 Intent/Action，ViewModel 更新状态或操作 GeckoSession；
 * Delegate 的所有回调统一走 onEngineEvent 回到这里，再更新 StateFlow 驱动 Compose 重绘。
 */
class BrowserViewModel(app: Application) : AndroidViewModel(app) {

    private val context: Context get() = getApplication<GeckoBrowserApp>()
    private val db: AppDatabase get() = (context as GeckoBrowserApp).database
    private val prefs get() = (context as GeckoBrowserApp).preferences
    private val updates: UpdateManager get() = (context as GeckoBrowserApp).updateManager

    val manager = TabSessionManager(context, db, viewModelScope, ::onEngineEvent, ::decideLoad)
    val suggestions = SearchEngineManager

    private val _ui = MutableStateFlow(BrowserUiState())
    val ui: StateFlow<BrowserUiState> = _ui.asStateFlow()

    private val _session = MutableStateFlow<GeckoSession?>(null)
    /** 单 GeckoView：Compose 的 AndroidView 观察这个值并 setSession()。 */
    val activeSession: StateFlow<GeckoSession?> = _session.asStateFlow()

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update.asStateFlow()

    private val _bookmarks = MutableStateFlow<List<com.local.geckobrowser.data.BookmarkEntity>>(emptyList())
    val bookmarks: StateFlow<List<com.local.geckobrowser.data.BookmarkEntity>> = _bookmarks.asStateFlow()

    private val _history = MutableStateFlow<List<HistoryEntity>>(emptyList())
    val history: StateFlow<List<HistoryEntity>> = _history.asStateFlow()

    private val _downloads = MutableStateFlow<List<DownloadEntity>>(emptyList())
    val downloads: StateFlow<List<DownloadEntity>> = _downloads.asStateFlow()

    private val _siteSettings = MutableStateFlow<List<com.local.geckobrowser.data.SiteSettingEntity>>(emptyList())
    val siteSettings = _siteSettings.asStateFlow()

    private val _extensions = MutableStateFlow<List<com.local.geckobrowser.data.ExtensionInfo>>(emptyList())
    val extensions = _extensions.asStateFlow()

    private val _autocomplete = MutableStateFlow<List<com.local.geckobrowser.feature.Suggestion>>(emptyList())
    val autocomplete = _autocomplete.asStateFlow()

    /** 启动期异步任务统一兜底：未捕获异常不得直接杀掉进程（表现为「打开过一会闪退」）。 */
    private fun safeLaunch(block: suspend () -> Unit) = viewModelScope.launch {
        // 用 try/catch 而非 runCatching：runCatching 的 lambda 不是 suspend 的，无法调用协程体。
        try {
            block()
        } catch (t: Throwable) {
            android.util.Log.w("BrowserViewModel", "启动期异步任务已忽略异常", t)
        }
    }

    init {
        SearchEngineManager.appContextHolder = context
        safeLaunch {
            prefs.observe().collect { p ->
                _ui.value = _ui.value.copy(toolbarPosition = p.toolbarPosition, themeMode = p.themeMode)
            }
        }
        safeLaunch { db.bookmarkDao().all().collect { _bookmarks.value = it } }
        safeLaunch { db.historyDao().all().collect { _history.value = it } }
        safeLaunch { db.downloadDao().all().collect { _downloads.value = it } }
        safeLaunch { db.siteSettingDao().all().collect { _siteSettings.value = it } }

        // 进程被杀后恢复标签列表、分组结构、激活位置与滚动位置；恢复完成后重新绑定 Session。
        manager.restoreFromDisk { bindActiveSession() }
        if (manager.tabs.isEmpty()) manager.createSession()
        bindActiveSession()

        // 启动时检查更新（可在设置中关闭）；只访问 GitHub Release API，不上传任何本地数据。
        safeLaunch { if (prefs.checkUpdateOnLaunch()) checkForUpdate(manual = false) }
        // 本地侧载扩展：从 assets 安装内置扩展，不依赖 AMO 远程安装。
        safeLaunch { loadBuiltInExtensions() }
    }

    // ---------------------------------------------------------------- 用户动作
    fun onInput(text: String) {
        _ui.value = _ui.value.copy(input = text)
        viewModelScope.launch {
            _autocomplete.value = suggestions.suggest(text, db, manager.tabs.toList())
        }
    }

    fun submitInput(text: String = _ui.value.input) {
        viewModelScope.launch {
            val target = if (suggestions.isUrl(text)) suggestions.normalizeUrl(text)
            else suggestions.searchUrl(text, prefs)
            load(target)
            _ui.value = _ui.value.copy(input = target)
        }
    }

    fun load(uri: String) {
        val session = manager.activeSession() ?: return
        // HTTPS-only 默认开启：在应用层拦截 http:// 并升级；例外列表本地存储，无云端查询。
        val rewritten = applyHttpsOnly(uri)
        if (rewritten != null && rewritten != uri) {
            session.loadUri(rewritten)
        } else {
            session.loadUri(uri)
        }
    }

    private fun applyHttpsOnly(uri: String): String {
        if (!uri.startsWith("http://", ignoreCase = true)) return uri
        if (!prefs.httpsOnlyCached()) return uri
        val host = runCatching { Uri.parse(uri).host }.getOrNull() ?: return uri
        if (prefs.httpsExceptionsCached().contains(host)) return uri
        return "https://" + uri.removePrefix("http://")
    }

    /** 供 NavigationDelegateImpl.onLoadRequest 调用的本地决策（HTTPS-only / 站点设置）。 */
    fun decideLoad(tabId: String, uri: String): LoadDecision {
        if (uri.startsWith("http://", ignoreCase = true) && prefs.httpsOnlyCached()) {
            val host = runCatching { Uri.parse(uri).host }.getOrNull()
            if (host != null && !prefs.httpsExceptionsCached().contains(host)) {
                return LoadDecision(AllowOrDeny.DENY, "https://" + uri.removePrefix("http://"))
            }
        }
        val site = _siteSettings.value.firstOrNull { uri.startsWith(it.origin) }
        if (site != null && !site.javascriptEnabled) {
            // 站点级 JavaScript 开关：通过会话设置更新（GeckoSessionSettings.setAllowJavascript）。
            manager.tab(tabId)?.session?.settings?.setAllowJavascript(false)
        }
        return LoadDecision(AllowOrDeny.ALLOW)
    }

    fun reload() = manager.activeSession()?.reload()
    fun stop() = manager.activeSession()?.stop()
    fun goBack() = manager.activeSession()?.goBack()
    fun goForward() = manager.activeSession()?.goForward()

    fun newTab(url: String = "about:blank", privateMode: Boolean = false, background: Boolean = false): GeckoSession? {
        val tab = manager.createSession(url, privateMode, activate = !background)
        if (!background) bindActiveSession()
        return tab.session
    }

    /** NavigationDelegate.onNewSession：返回新 Session，标签完全由应用层创建。 */
    fun newSessionForNavigation(uri: String, fromTabId: String): GeckoSession? {
        val group = manager.tab(fromTabId)?.groupId
        return manager.createSession(uri, isPrivate = manager.tab(fromTabId)?.isPrivate ?: false,
            groupId = group, activate = false).session
    }

    fun switchTab(id: String) {
        manager.switchTo(id)
        bindActiveSession()
        syncActiveTabToUi()
    }

    fun closeTab(id: String) {
        val record = manager.closeTab(id) ?: return
        bindActiveSession()
        syncActiveTabToUi()
        _ui.value = _ui.value.copy(snackbarMessage = "已关闭标签，可撤销")
        pendingUndo = record
    }

    private var pendingUndo: RecentClosedTab? = null
    fun undoCloseTab() {
        pendingUndo?.let {
            manager.restoreRecentlyClosed(it)
            bindActiveSession()
            syncActiveTabToUi()
        }
        pendingUndo = null
        _ui.value = _ui.value.copy(snackbarMessage = null)
    }

    fun duplicateTab(id: String) { manager.duplicateTab(id); syncActiveTabToUi() }
    fun togglePin(id: String) { manager.tab(id)?.let { manager.setPinned(id, !it.isPinned) } }
    fun createGroup(name: String, color: Long) = manager.createGroup(name, color)
    fun renameGroup(id: String, name: String, color: Long? = null) = manager.renameGroup(id, name, color)
    fun toggleGroup(id: String) = manager.toggleGroupCollapsed(id)
    fun assignGroup(tabId: String, groupId: String?) = manager.assignGroup(tabId, groupId)
    fun closeGroup(id: String) = manager.closeGroup(id)

    fun setDesktopMode(enabled: Boolean) {
        val s = manager.activeSession() ?: return
        s.settings.setUserAgentMode(
            if (enabled) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
            else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        )
        s.settings.setViewportMode(
            if (enabled) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
            else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        )
        s.reload()
    }

    fun setFontSizeFactor(factor: Float) {
        // 全局字体由 GeckoRuntimeSettings.Builder.fontSizeFactor 控制；运行中通过 settings 更新。
        GeckoEngine.runtime(context).settings.setFontSizeFactor(factor)
    }

    fun findInPage(query: String, forward: Boolean = true) {
        val finder = manager.activeSession()?.finder ?: return
        finder.setDisplayFlags(
            GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL or GeckoSession.FINDER_DISPLAY_DIM_PAGE
        )
        val flags = if (forward) GeckoSession.FINDER_FIND_FORWARD else GeckoSession.FINDER_FIND_BACKWARDS
        finder.find(query, flags)
        _ui.value = _ui.value.copy(findQuery = query, findMatchCount = 1)
    }

    fun closeFind() {
        manager.activeSession()?.finder?.clear()
        _ui.value = _ui.value.copy(findQuery = "", findMatchIndex = 0, findMatchCount = 0)
    }

    fun shareCurrent() {
        val url = _ui.value.url
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url)
        }
        context.startActivity(Intent.createChooser(intent, "分享链接").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun addBookmark(title: String = _ui.value.title, url: String = _ui.value.url, folderId: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            db.bookmarkDao().insert(
                com.local.geckobrowser.data.BookmarkEntity(
                    id = UUID.randomUUID().toString(), title = title, url = url,
                    folderId = folderId, createdAt = System.currentTimeMillis(), position = 0
                )
            )
        }
    }

    fun clearHistory(rangeStart: Long, rangeEnd: Long = Long.MAX_VALUE) {
        viewModelScope.launch(Dispatchers.IO) { db.historyDao().clearRange(rangeStart, rangeEnd) }
    }

    fun clearAllHistory() {
        viewModelScope.launch(Dispatchers.IO) { db.historyDao().clearAll() }
    }

    fun deleteHistory(id: String) {
        viewModelScope.launch(Dispatchers.IO) { db.historyDao().delete(id) }
    }

    /** 退出时清除：本地 Room + Gecko StorageController.ClearFlags，全部本地执行。 */
    fun runExitCleanup() {
        viewModelScope.launch(Dispatchers.IO) {
            val p = prefs.snapshot()
            if (p.clearHistoryOnExit) db.historyDao().clearAll()
            var flags = 0L
            if (p.clearCookiesOnExit) flags = flags or StorageController.ClearFlags.COOKIES
            if (p.clearCacheOnExit) flags = flags or StorageController.ClearFlags.ALL_CACHES
            if (p.clearSiteDataOnExit) flags = flags or StorageController.ClearFlags.SITE_DATA
            if (flags != 0L) GeckoEngine.clearBrowsingData(context, flags)
        }
    }

    // ---------------------------------------------------------------- 下载
    fun enqueueDownload(id: String, uri: String, name: String, mime: String?) {
        DownloadWorker.enqueue(context, id)
    }

    fun pauseDownload(id: String) = DownloadWorker.setPaused(id, true)
    fun resumeDownload(id: String) { DownloadWorker.setPaused(id, false); DownloadWorker.enqueue(context, id) }
    fun cancelDownload(id: String) = DownloadWorker.setCancelled(id, true)

    // ---------------------------------------------------------------- 更新
    fun checkForUpdate(manual: Boolean = true) {
        viewModelScope.launch {
            updates.checkForUpdate().collect { _update.value = it }
        }
    }

    fun downloadUpdate() {
        viewModelScope.launch {
            updates.downloadAndInstall().collect { _update.value = it }
        }
    }

    // ---------------------------------------------------------------- 扩展
    /** 本地侧载：assets 内置扩展通过 ensureBuiltIn 加载，不依赖 AMO。 */
    private suspend fun loadBuiltInExtensions() = withContext(Dispatchers.IO) {
        val controller = GeckoEngine.runtime(context).webExtensionController
        runCatching {
            controller.ensureBuiltIn("resource://android/assets/extensions/readermode/", "reader@local.browser")
            controller.ensureBuiltIn("resource://android/assets/extensions/translate/", "translate@local.browser")
        }
        refreshExtensions()
    }

    fun installExtensionFromSaf(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            // WebExtensionController.install(String) 接收扩展 URI；侧载文件由 SAF 复制到内部存储后传入。
            val local = com.local.geckobrowser.feature.ExtensionInstaller.copyToInternal(context, uri)
            runCatching { GeckoEngine.runtime(context).webExtensionController.install(local) }
            refreshExtensions()
        }
    }

    fun refreshExtensions() {
        viewModelScope.launch {
            runCatching {
                val result = GeckoEngine.runtime(context).webExtensionController.list()
                // webExtensionController.list() 返回 GeckoResult<List<WebExtension>>，accept 的回调参数是 List 而非 Array。
                result.accept { list: List<WebExtension>? ->
                    _extensions.value = (list ?: emptyList<WebExtension>()).map { ext ->
                        com.local.geckobrowser.data.ExtensionInfo(
                            id = ext.id, name = ext.metaData?.name ?: ext.id,
                            version = ext.metaData?.version ?: "", enabled = ext.metaData?.enabled ?: false,
                            allowedInPrivate = false
                        )
                    }
                }
            }
        }
    }

    fun toggleExtension(id: String, enabled: Boolean) {
        viewModelScope.launch {
            GeckoEngine.runtime(context).webExtensionController.list().accept { list ->
                list?.firstOrNull { it.id == id }?.let { ext ->
                    if (enabled) GeckoEngine.runtime(context).webExtensionController.enable(ext, 0)
                    else GeckoEngine.runtime(context).webExtensionController.disable(ext, 0)
                    refreshExtensions()
                }
            }
        }
    }

    fun setExtensionPrivateAllowed(id: String, allowed: Boolean) {
        viewModelScope.launch {
            GeckoEngine.runtime(context).webExtensionController.list().accept { list ->
                list?.firstOrNull { it.id == id }?.let {
                    GeckoEngine.runtime(context).webExtensionController.setAllowedInPrivateBrowsing(it, allowed)
                }
            }
        }
    }

    fun uninstallExtension(id: String) {
        viewModelScope.launch {
            GeckoEngine.runtime(context).webExtensionController.list().accept { list ->
                list?.firstOrNull { it.id == id }?.let {
                    GeckoEngine.runtime(context).webExtensionController.uninstall(it)
                }
            }
            refreshExtensions()
        }
    }

    // ---------------------------------------------------------------- 翻译
    val translationManager = TranslationManager(app)

    // ---------------------------------------------------------------- Delegate 事件路由
    fun onEngineEvent(event: EngineEvent) {
        val active = manager.activeTabId
        val isActive = when (event) {
            is EngineEvent.LocationChange -> event.tabId == active
            is EngineEvent.TitleChange -> event.tabId == active
            is EngineEvent.PageStart -> event.tabId == active
            is EngineEvent.PageStop -> event.tabId == active
            is EngineEvent.ProgressChange -> event.tabId == active
            is EngineEvent.SecurityChange -> event.tabId == active
            is EngineEvent.FullScreen -> event.tabId == active
            is EngineEvent.DynamicToolbar -> event.tabId == active
            else -> true
        }
        when (event) {
            is EngineEvent.LocationChange -> {
                manager.tab(event.tabId)?.let { it.url = event.url; it.lastUsed = System.currentTimeMillis() }
                if (isActive) _ui.value = _ui.value.copy(url = event.url, input = event.url)
            }
            is EngineEvent.NavigationCapability -> if (isActive) {
                _ui.value = _ui.value.copy(
                    canGoBack = event.canGoBack ?: _ui.value.canGoBack,
                    canGoForward = event.canGoForward ?: _ui.value.canGoForward
                )
            }
            is EngineEvent.TitleChange -> {
                manager.tab(event.tabId)?.title = event.title
                if (isActive) _ui.value = _ui.value.copy(title = event.title)
            }
            is EngineEvent.FullScreen -> if (isActive) _ui.value = _ui.value.copy(isFullScreen = event.enabled)
            is EngineEvent.DynamicToolbar -> if (isActive) _ui.value = _ui.value.copy(showDynamicToolbar = event.show)
            is EngineEvent.FirstContentfulPaint, is EngineEvent.PaintStatusReset -> { /* 首帧状态重置由进度条处理 */ }
            is EngineEvent.Crash -> {
                manager.tab(event.tabId)?.crashed = true
                if (isActive) _ui.value = _ui.value.copy(crashTabId = event.tabId)
                viewModelScope.launch(Dispatchers.IO) { db.crashDao().insert(
                    com.local.geckobrowser.data.CrashEntity(
                        id = UUID.randomUUID().toString(), tabId = event.tabId,
                        timestamp = System.currentTimeMillis(), detail = "渲染进程崩溃"
                    )
                ) }
            }
            is EngineEvent.ExternalResponse -> {
                // 把 WebResponse 交给同进程注册表，WorkManager 读取流并支持断点续传。
                DownloadRegistry.register(event.downloadId, event.response)
                viewModelScope.launch(Dispatchers.IO) {
                    db.downloadDao().insert(
                        DownloadEntity(
                            id = event.downloadId, uri = event.uri, fileName = event.suggestedName,
                            mimeType = event.mimeType, bytesReceived = 0, bytesTotal = event.contentLength,
                            status = "QUEUED", createdAt = System.currentTimeMillis()
                        )
                    )
                }
                enqueueDownload(event.downloadId, event.uri, event.suggestedName, event.mimeType)
            }
            is EngineEvent.PageStart -> if (isActive) _ui.value = _ui.value.copy(isLoading = true, progress = 0)
            is EngineEvent.PageStop -> if (isActive) _ui.value = _ui.value.copy(isLoading = false, progress = 100)
            is EngineEvent.ProgressChange -> if (isActive) _ui.value = _ui.value.copy(progress = event.progress)
            is EngineEvent.SecurityChange -> if (isActive) _ui.value = _ui.value.copy(
                isSecure = event.isSecure, securityHost = event.host, securityMode = event.securityMode
            )
            is EngineEvent.SessionStateChange -> manager.tab(event.tabId)?.sessionStateSerialized = event.serialized
            is EngineEvent.HistoryStateChange -> { /* 历史栈索引变化，本地持久化由 onVisited 完成 */ }
            is EngineEvent.Visited -> {
                if (manager.tab(event.tabId)?.isPrivate != true) {
                    viewModelScope.launch(Dispatchers.IO) {
                        db.historyDao().insert(
                            HistoryEntity(
                                id = UUID.randomUUID().toString(), url = event.url,
                                title = event.title.ifBlank { event.url },
                                visitedAt = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }
            is EngineEvent.MediaEvent -> if (isActive) _ui.value = _ui.value.copy(
                media = _ui.value.media.copy(
                    active = event.kind != EngineEvent.MediaEventKind.DEACTIVATED,
                    title = event.title.ifEmpty { _ui.value.media.title },
                    artist = event.artist.ifEmpty { _ui.value.media.artist },
                    album = event.album.ifEmpty { _ui.value.media.album },
                    playing = when (event.kind) {
                        EngineEvent.MediaEventKind.PLAY -> true
                        EngineEvent.MediaEventKind.PAUSE, EngineEvent.MediaEventKind.STOP -> false
                        else -> _ui.value.media.playing
                    },
                    duration = if (event.duration > 0) event.duration else _ui.value.media.duration,
                    position = event.position,
                    fullscreen = event.fullscreen
                )
            )
            is EngineEvent.SelectionAction,
            is EngineEvent.PermissionRequest,
            is EngineEvent.PromptRequest,
            is EngineEvent.ExtensionAction,
            is EngineEvent.ContextMenu,
            is EngineEvent.TranslationRequest,
            is EngineEvent.ShareText,
            is EngineEvent.SelectionDismiss,
            is EngineEvent.ExtensionStateChanged,
            is EngineEvent.PictureInPicture,
            is EngineEvent.TranslationState -> _pendingEvents.tryEmit(event)
            is EngineEvent.ExtensionNewTab -> newTab(event.uri, background = !event.active)
        }
    }

    /** 需要 UI 承载的事件（对话框 / 底部菜单 / 上下文菜单）单独开一条流。 */
    private val _pendingEvents = kotlinx.coroutines.flow.MutableSharedFlow<EngineEvent>(
        extraBufferCapacity = 16
    )
    val pendingEvents = _pendingEvents.asSharedFlow()

    fun consumeSnackbar() { _ui.value = _ui.value.copy(snackbarMessage = null) }

    fun reloadCrashedTab(tabId: String) {
        val tab = manager.tab(tabId) ?: return
        // ContentDelegate.onCrash 后 Session 已关闭且不可用，必须 open() 重建或 restoreState 恢复。
        manager.switchTo(tabId)
        bindActiveSession()
        _ui.value = _ui.value.copy(crashTabId = null)
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { prefs.setThemeMode(mode) }
        GeckoEngine.runtime(context).settings.setPreferredColorScheme(
            when (mode) {
                ThemeMode.LIGHT -> org.mozilla.geckoview.GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
                ThemeMode.DARK -> org.mozilla.geckoview.GeckoRuntimeSettings.COLOR_SCHEME_DARK
                ThemeMode.SYSTEM -> org.mozilla.geckoview.GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
            }
        )
    }

    fun setToolbarPosition(position: ToolbarPosition) {
        viewModelScope.launch { prefs.setToolbarPosition(position) }
    }

    private fun bindActiveSession() {
        val session = manager.activeSession()
        _session.value = session
        syncActiveTabToUi()
    }

    private fun syncActiveTabToUi() {
        val tab = manager.activeTab()
        _ui.value = _ui.value.copy(
            activeTabId = manager.activeTabId,
            tabCount = manager.tabs.size,
            url = tab?.url ?: "about:blank",
            input = tab?.url ?: "",
            title = tab?.title ?: "新标签页",
            isPrivate = tab?.isPrivate ?: false,
            readerModeActive = tab?.readerModeActive ?: false,
            crashTabId = null
        )
    }

    override fun onCleared() {
        manager.persistAll()
        super.onCleared()
    }
}
