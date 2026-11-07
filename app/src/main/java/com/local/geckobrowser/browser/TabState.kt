package com.local.geckobrowser.browser

import androidx.compose.runtime.snapshots.SnapshotStateList
import org.mozilla.geckoview.GeckoSession

/** 主题档位。 */
enum class ThemeMode { LIGHT, DARK, SYSTEM }

/** 工具栏位置。 */
enum class ToolbarPosition { TOP, BOTTOM }

/** 标签分组：分组不是 GeckoView 原生概念，全部由应用层维护。 */
data class TabGroupState(
    val id: String,
    val name: String,
    val colorArgb: Long,
    val collapsed: Boolean = false
)

/**
 * 单个标签的应用层状态。GeckoView 中没有「标签」概念，一个标签对应一个 GeckoSession。
 * 会话元数据始终保留；后台 Session 可按内存压力策略释放后再重建。
 */
data class TabState(
    val id: String,
    var session: GeckoSession?,
    var title: String = "新标签页",
    var url: String = "about:blank",
    var favicon: String? = null,
    var isPrivate: Boolean = false,
    var isPinned: Boolean = false,
    var groupId: String? = null,
    var lastUsed: Long = System.currentTimeMillis(),
    var scrollX: Int = 0,
    var scrollY: Int = 0,
    var readerModeActive: Boolean = false,
    var sessionStateSerialized: String? = null,
    var crashed: Boolean = false
)

/** 最近关闭的标签，本地保留 20 条，支持撤销恢复。 */
data class RecentClosedTab(
    val id: String,
    val url: String,
    val title: String,
    val isPrivate: Boolean,
    val groupId: String?,
    val closedAt: Long,
    val sessionStateSerialized: String?
)

/** 媒体会话状态，由 MediaSession.Delegate 上报后写入 UI 状态。 */
data class MediaState(
    val active: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val playing: Boolean = false,
    val duration: Double = 0.0,
    val position: Double = 0.0,
    val fullscreen: Boolean = false
)

/** 浏览器主界面 UDF 状态：Compose 只读取，不直接操作 GeckoSession。 */
data class BrowserUiState(
    val url: String = "about:blank",
    val title: String = "新标签页",
    val input: String = "",
    val isLoading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isSecure: Boolean = false,
    val securityHost: String = "",
    val securityMode: Int = 0,
    val tabCount: Int = 0,
    val activeTabId: String = "",
    val isFullScreen: Boolean = false,
    val isPrivate: Boolean = false,
    val toolbarPosition: ToolbarPosition = ToolbarPosition.TOP,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val showDynamicToolbar: Boolean = true,
    val readerAvailable: Boolean = false,
    val readerModeActive: Boolean = false,
    val findQuery: String = "",
    val findMatchIndex: Int = 0,
    val findMatchCount: Int = 0,
    val media: MediaState = MediaState(),
    val crashTabId: String? = null,
    val snackbarMessage: String? = null
)

/** 供 TabSessionManager 使用的容器别名，明确标签池使用 SnapshotStateList。 */
typealias TabList = SnapshotStateList<TabState>
