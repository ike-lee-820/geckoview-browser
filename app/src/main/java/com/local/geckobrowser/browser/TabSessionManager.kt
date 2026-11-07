package com.local.geckobrowser.browser

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import com.local.geckobrowser.GeckoEngine
import com.local.geckobrowser.browser.delegates.ContentDelegateImpl
import com.local.geckobrowser.browser.delegates.HistoryDelegateImpl
import com.local.geckobrowser.browser.delegates.NavigationDelegateImpl
import com.local.geckobrowser.browser.delegates.PermissionDelegateImpl
import com.local.geckobrowser.browser.delegates.ProgressDelegateImpl
import com.local.geckobrowser.browser.delegates.PromptDelegateImpl
import com.local.geckobrowser.browser.delegates.SelectionActionDelegateImpl
import com.local.geckobrowser.data.AppDatabase
import com.local.geckobrowser.diag.CrashReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import java.util.UUID

/**
 * 应用层标签与会话管理器。
 *
 * GeckoView 没有「标签」概念，也没有标签组；多标签 = 多个 GeckoSession，
 * 屏幕上只保留一个 GeckoView，切换标签时由 BrowserViewModel 调用 setSession 替换绑定。
 * Session 元数据（TabState）始终保留，后台 Session 可按内存策略释放后重建。
 */
class TabSessionManager(
    val context: Context,
    private val database: AppDatabase,
    private val scope: CoroutineScope,
    private val onEvent: (EngineEvent) -> Unit,
    val decideLoad: (String, String) -> LoadDecision
) {
    val tabs = mutableStateListOf<TabState>()
    val groups = mutableStateListOf<TabGroupState>()
    val recentClosed = mutableStateListOf<RecentClosedTab>()

    var activeTabId: String = ""
        private set

    private val runtime by lazy { GeckoEngine.runtime(context) }
    private val sessionToTab = HashMap<GeckoSession, String>()

    /** 创建并装配一个完整 Session；所有 Delegate 均路由到同一个 ViewModel 事件入口。 */
    fun createSession(
        url: String = "about:blank",
        isPrivate: Boolean = false,
        groupId: String? = null,
        activate: Boolean = true,
        restoredState: String? = null
    ): TabState {
        // fontSizeFactor 属于 GeckoRuntimeSettings.Builder，enterFullScreenInLandscape 不在 SessionSettings 中；
        // 会话级配置按现行 JavaDoc 中的真实方法装配，见 DOCS_READ.md。
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(isPrivate)
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .suspendMediaWhenInactive(true)
            .allowJavascript(true)
            // 按需求删除追踪保护功能：不再启用会话级跟踪保护。
            .useTrackingProtection(false)
            .build()

        val session = GeckoSession(settings)
        val id = UUID.randomUUID().toString()
        val tab = TabState(
            id = id,
            session = session,
            url = url,
            isPrivate = isPrivate,
            groupId = groupId,
            sessionStateSerialized = restoredState
        )
        tabs.add(tab)
        sessionToTab[session] = id
        attachDelegates(id, session)
        CrashReporter.markStage("session_open_before")
        session.open(runtime)
        CrashReporter.markStage("session_open_after")
        if (!restoredState.isNullOrBlank()) {
            // SessionState.fromString + restoreState：进程被杀后恢复历史栈与滚动位置。
            runCatching { GeckoSession.SessionState.fromString(restoredState)?.let { session.restoreState(it) } }
        } else if (url != "about:blank") {
            session.loadUri(url)
        }
        persistTab(tab)
        if (activate) activeTabId = id
        return tab
    }

    /** 为一个 Session 挂接全部 Delegate；每个 Delegate 都带 tabId，事件能精确路由。 */
    private fun attachDelegates(tabId: String, session: GeckoSession) {
        session.setNavigationDelegate(NavigationDelegateImpl(tabId, this, onEvent))
        session.setContentDelegate(ContentDelegateImpl(tabId, this, onEvent))
        session.setProgressDelegate(ProgressDelegateImpl(tabId, this, onEvent))
        session.setPermissionDelegate(PermissionDelegateImpl(tabId, this, onEvent))
        session.setPromptDelegate(PromptDelegateImpl(tabId, this, onEvent))
        session.setHistoryDelegate(HistoryDelegateImpl(tabId, this, database, scope, onEvent))
        session.setSelectionActionDelegate(SelectionActionDelegateImpl(tabId, this, onEvent))
        // MediaSession.Delegate 必须添加 @UiThread，见 MediaSessionDelegateImpl。
        session.setMediaSessionDelegate(
            com.local.geckobrowser.browser.delegates.MediaSessionDelegateImpl(tabId, this, onEvent)
        )
        // 实验性 API：PictureInPictureController.SessionPictureInPicture.Delegate，
        // 需核对当前 Beta 版本 Changelog。仅在支持时注册，失败由 runCatching 降级到 Android 原生 PiP。
        // 实验性 API，需核对当前 Beta 版本 Changelog。
        runCatching {
            session.setPictureInPictureDelegate(
                com.local.geckobrowser.browser.delegates.PictureInPictureDelegateImpl(tabId, onEvent)
            )
        }
        // 本地翻译：TranslationsController.SessionTranslation.Delegate 注册到会话。
        // 实验性 API，需核对当前 Beta 版本 Changelog。
        runCatching {
            session.setTranslationsSessionDelegate(
                com.local.geckobrowser.browser.delegates.TranslationSessionDelegate(tabId, onEvent)
            )
        }
    }

    fun tabIdFor(session: GeckoSession): String? = sessionToTab[session]

    fun tab(id: String): TabState? = tabs.firstOrNull { it.id == id }

    fun activeTab(): TabState? = tab(activeTabId)

    fun activeSession(): GeckoSession? = activeTab()?.session

    /** 单 GeckoView 模式：只替换绑定的 Session，不创建第二个 GeckoView。 */
    fun switchTo(id: String): GeckoSession? {
        val target = tab(id) ?: return null
        activeTabId = id
        target.lastUsed = System.currentTimeMillis()
        var session = target.session
        if (session == null || target.crashed) {
            session = recreateSession(target)
        }
        persistTab(target)
        return session
    }

    /** 后台 Session 被释放或崩溃后，按元数据重建；TabState 元数据始终保留。 */
    fun recreateSession(tab: TabState): GeckoSession {
        tab.session?.let { old ->
            sessionToTab.remove(old)
            runCatching { old.close() }
        }
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(tab.isPrivate)
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .suspendMediaWhenInactive(true)
            .allowJavascript(true)
            // 按需求删除追踪保护功能：重建会话时同样不启用。
            .useTrackingProtection(false)
            .build()
        val fresh = GeckoSession(settings)
        tab.session = fresh
        tab.crashed = false
        sessionToTab[fresh] = tab.id
        attachDelegates(tab.id, fresh)
        // onCrash 后原 Session 已关闭且不可用，必须 open() 重建；无状态会被保留，
        // 因此用本地持久化的 SessionState 恢复，否则重新加载 URL。
        CrashReporter.markStage("session_recreate_open_before")
        fresh.open(runtime)
        CrashReporter.markStage("session_recreate_open_after")
        val saved = tab.sessionStateSerialized
        if (!saved.isNullOrBlank()) {
            runCatching { GeckoSession.SessionState.fromString(saved)?.let { fresh.restoreState(it) } }
        } else if (tab.url != "about:blank") {
            fresh.loadUri(tab.url)
        }
        return fresh
    }

    /**
     * 【修复①】确保当前标签有可用 Session：后台回收/内存策略释放后，
     * 输入网址时旧代码直接 `activeSession() ?: return` 静默失败，页面永远空白。
     * 这里按需重建并重新绑定，元数据（含 URL / 滚动位置）始终保留。
     */
    fun ensureActiveSession(): GeckoSession? {
        val target = tab(activeTabId) ?: tabs.firstOrNull()?.also { activeTabId = it.id }
            ?: return null
        return target.session ?: recreateSession(target)
    }

    /** 内存压力策略：只释放后台非固定标签的 Session，元数据保留。 */
    fun releaseInactiveSessions(keepCount: Int = 3) {
        // 【修复①】activeTabId 尚未就绪（启动恢复是异步的）时绝不释放任何 Session，
        // 否则会把唯一的激活标签也关掉，导致页面彻底空白。
        val activeId = activeTabId
        if (activeId.isBlank() || tab(activeId) == null) return
        val candidates = tabs.filter {
            it.id != activeId && !it.isPinned && it.session != null
        }.sortedBy { it.lastUsed }
            .take((tabs.size - keepCount).coerceAtLeast(0))
        candidates.forEach { releaseSession(it) }
    }

    private fun releaseSession(tab: TabState) {
        tab.session?.let { sessionToTab.remove(it); runCatching { it.close() } }
        tab.session = null
    }

    /** 关闭并写入最近关闭列表（最多 20 条），支持撤销 Snackbar。 */
    fun closeTab(id: String): RecentClosedTab? {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return null
        val tab = tabs[index]
        if (tab.isPinned) return null // 固定标签不参与批量关闭
        val record = RecentClosedTab(
            id = tab.id, url = tab.url, title = tab.title,
            isPrivate = tab.isPrivate, groupId = tab.groupId,
            closedAt = System.currentTimeMillis(),
            sessionStateSerialized = tab.sessionStateSerialized
        )
        tab.session?.let { sessionToTab.remove(it); runCatching { it.close() } }
        tabs.removeAt(index)
        recentClosed.add(0, record)
        if (recentClosed.size > 20) recentClosed.removeLast()
        scope.launch(Dispatchers.IO) {
            database.recentClosedDao().insert(
                com.local.geckobrowser.data.RecentClosedTabEntity(
                    record.id, record.url, record.title, record.isPrivate, record.groupId,
                    record.closedAt, record.sessionStateSerialized
                )
            )
            database.recentClosedDao().trim(20)
        }
        if (activeTabId == id) {
            activeTabId = tabs.getOrNull(index.coerceAtMost(tabs.lastIndex))?.id
                ?: tabs.lastOrNull()?.id ?: ""
        }
        scope.launch(Dispatchers.IO) { database.tabGroupDao().deleteTabSnapshot(id) }
        return record
    }

    fun restoreRecentlyClosed(record: RecentClosedTab): TabState = createSession(
        url = record.url,
        isPrivate = record.isPrivate,
        groupId = record.groupId,
        restoredState = record.sessionStateSerialized
    ).also {
        it.title = record.title
        recentClosed.remove(record)
    }

    /** NavigationDelegate.onNewSession：由应用层创建并返回新的 GeckoSession。 */
    fun createForNavigation(uri: String, fromTabId: String): GeckoSession? {
        val source = tab(fromTabId)
        return createSession(
            url = uri,
            isPrivate = source?.isPrivate ?: false,
            groupId = source?.groupId,
            activate = false
        ).session
    }

    fun duplicateTab(id: String): TabState? {
        val origin = tab(id) ?: return null
        return createSession(
            url = origin.url,
            isPrivate = origin.isPrivate,
            groupId = origin.groupId,
            restoredState = origin.sessionStateSerialized
        ).also { it.title = origin.title }
    }

    fun setPinned(id: String, pinned: Boolean) {
        tab(id)?.let { it.isPinned = pinned; persistTab(it) }
    }

    // ---------------------------------------------------------------- 标签分组
    fun createGroup(name: String, colorArgb: Long): TabGroupState {
        val group = TabGroupState(UUID.randomUUID().toString(), name, colorArgb)
        groups.add(group)
        scope.launch(Dispatchers.IO) { database.tabGroupDao().insertGroup(group.toEntity()) }
        return group
    }

    fun renameGroup(id: String, name: String, colorArgb: Long? = null) {
        val i = groups.indexOfFirst { it.id == id }
        if (i >= 0) {
            groups[i] = groups[i].copy(name = name, colorArgb = colorArgb ?: groups[i].colorArgb)
            scope.launch(Dispatchers.IO) { database.tabGroupDao().insertGroup(groups[i].toEntity()) }
        }
    }

    fun toggleGroupCollapsed(id: String) {
        val i = groups.indexOfFirst { it.id == id }
        if (i >= 0) groups[i] = groups[i].copy(collapsed = !groups[i].collapsed)
    }

    fun assignGroup(tabId: String, groupId: String?) {
        tab(tabId)?.let { it.groupId = groupId; persistTab(it) }
    }

    fun closeGroup(id: String) {
        tabs.filter { it.groupId == id && !it.isPinned }.forEach { closeTab(it.id) }
        groups.removeAll { it.id == id }
        scope.launch(Dispatchers.IO) { database.tabGroupDao().deleteGroup(id) }
    }

    // ---------------------------------------------------------------- 恢复
    fun restoreFromDisk(defaultUrl: String = "about:blank", onRestored: (() -> Unit)? = null) {
        scope.launch(Dispatchers.IO) {
            // 数据库读取失败不得让启动流程抛未捕获异常。
            // DAO 方法是 suspend，不能放进 runCatching 的非 suspend lambda 里，改用 try/catch。
            val snaps: List<com.local.geckobrowser.data.TabSnapshotEntity> =
                try { database.tabGroupDao().tabSnapshots() } catch (t: Throwable) { emptyList() }
            val groupEntities: List<com.local.geckobrowser.data.TabGroupEntity> =
                try { database.tabGroupDao().allGroups() } catch (t: Throwable) { emptyList() }
            withContextMain {
                CrashReporter.markStage("restore_main_start")
                runCatching {
                    groups.clear()
                    groups.addAll(groupEntities.map { TabGroupState(it.id, it.name, it.colorArgb, it.collapsed) })
                    // 启动期占位标签：有历史快照时移除，避免每次启动累加空白标签。
                    if (snaps.isNotEmpty() && tabs.size == 1 &&
                        tabs[0].url == "about:blank" && tabs[0].sessionStateSerialized == null) {
                        tabs[0].session?.let { sessionToTab.remove(it); runCatching { it.close() } }
                        tabs.removeAt(0)
                        activeTabId = ""
                    }
                    snaps.forEach { s ->
                        // 后台标签只恢复元数据，不立即 open GeckoSession。
                        // 启动即一次性拉起多个内容进程，是「打开过一会闪退」的高风险点（内存/内容进程被杀）；
                        // 切到该标签时由 switchTo -> recreateSession 按需 open，TabState 元数据始终保留。
                        tabs.add(
                            TabState(
                                id = s.id, session = null, title = s.title, url = s.url,
                                isPrivate = s.isPrivate, isPinned = s.isPinned,
                                groupId = s.groupId, lastUsed = s.lastUsed,
                                scrollX = s.scrollX, scrollY = s.scrollY,
                                readerModeActive = s.readerModeActive,
                                sessionStateSerialized = s.sessionStateSerialized
                            )
                        )
                        if (s.isActive) activeTabId = s.id
                    }
                    // 首次启动/无历史快照时按用户设置的主页打开，而不是固定 about:blank。
                    if (tabs.isEmpty()) createSession(defaultUrl)
                    else {
                        if (activeTabId.isBlank() || tab(activeTabId) == null) activeTabId = tabs.first().id
                        val active = tab(activeTabId)
                        if (active != null && active.session == null) recreateSession(active)
                    }
                    CrashReporter.markStage("restore_main_done")
                }.onFailure { t ->
                    CrashReporter.record("restore_from_disk", t, extra = "阶段=${currentStageForLog()}")
                }
                runCatching { onRestored?.invoke() }
                    .onFailure { t -> CrashReporter.record("restore_callback", t) }
                CrashReporter.markStage("restore_completed")
            }
        }
    }

    private suspend fun withContextMain(block: () -> Unit) =
        kotlinx.coroutines.withContext(Dispatchers.Main) { block() }

    private fun currentStageForLog(): String = "restoreFromDisk/Dispatchers.Main"

    private fun persistTab(tab: TabState) {
        scope.launch(Dispatchers.IO) { database.tabGroupDao().insertTabSnapshot(tab.toEntity()) }
    }

    fun persistAll() {
        tabs.forEach { persistTab(it) }
    }

    fun shutdown() {
        tabs.forEach { it.session?.let { s -> runCatching { s.close() } } }
    }

    fun findByUrlOrTitle(query: String): List<TabState> {
        if (query.isBlank()) return tabs.toList()
        return tabs.filter {
            it.title.contains(query, ignoreCase = true) || it.url.contains(query, ignoreCase = true)
        }
    }
}

/** Room 实体转换，集中放在这里避免污染 UI 层。 */
private fun TabGroupState.toEntity() = com.local.geckobrowser.data.TabGroupEntity(
    id = id, name = name, colorArgb = colorArgb, collapsed = collapsed
)

private fun TabState.toEntity() = com.local.geckobrowser.data.TabSnapshotEntity(
    id = id, url = url, title = title, isPrivate = isPrivate, isPinned = isPinned,
    groupId = groupId, lastUsed = lastUsed, scrollX = scrollX, scrollY = scrollY,
    readerModeActive = readerModeActive, sessionStateSerialized = sessionStateSerialized
)
