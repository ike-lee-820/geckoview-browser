package com.local.geckobrowser.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.local.geckobrowser.browser.ThemeMode
import com.local.geckobrowser.browser.ToolbarPosition
import com.local.geckobrowser.browser.TrackingPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.firstOrNull

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "browser_prefs")

/** 应用偏好：全部本地，无任何云同步。 */
data class BrowserPrefs(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val toolbarPosition: ToolbarPosition = ToolbarPosition.TOP,
    val trackingPolicy: TrackingPolicy = TrackingPolicy.STANDARD,
    val httpsOnly: Boolean = true,
    val httpsExceptions: Set<String> = emptySet(),
    val checkUpdateOnLaunch: Boolean = true,
    val updateProxyBase: String = "https://v6.gh-proxy.com/https://github.com/",
    val allowBackgroundAudio: Boolean = true,
    val allowBackgroundVideo: Boolean = false,
    val autoPictureInPicture: Boolean = false,
    val showBookmarksBar: Boolean = true,
    val swipeTabSwitch: Boolean = true,
    val edgeSwipeSensitivity: Float = 0.5f,
    val clearCacheOnExit: Boolean = false,
    val clearCookiesOnExit: Boolean = false,
    val clearHistoryOnExit: Boolean = false,
    val clearSiteDataOnExit: Boolean = false,
    val suspendedMediaWhenInactive: Boolean = true
)

class PreferencesDataStore(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val TOOLBAR = stringPreferencesKey("toolbar")
        val TRACKING = stringPreferencesKey("tracking")
        val HTTPS_ONLY = booleanPreferencesKey("https_only")
        val HTTPS_EXCEPTIONS = stringPreferencesKey("https_exceptions")
        val CHECK_UPDATE = booleanPreferencesKey("check_update_on_launch")
        val PROXY_BASE = stringPreferencesKey("update_proxy_base")
        val BG_AUDIO = booleanPreferencesKey("bg_audio")
        val BG_VIDEO = booleanPreferencesKey("bg_video")
        val AUTO_PIP = booleanPreferencesKey("auto_pip")
        val BOOKMARKS_BAR = booleanPreferencesKey("bookmarks_bar")
        val SWIPE_TABS = booleanPreferencesKey("swipe_tabs")
        val EDGE_SENSITIVITY = floatPreferencesKey("edge_sensitivity")
        val CLEAR_CACHE = booleanPreferencesKey("clear_cache")
        val CLEAR_COOKIES = booleanPreferencesKey("clear_cookies")
        val CLEAR_HISTORY = booleanPreferencesKey("clear_history")
        val CLEAR_SITE_DATA = booleanPreferencesKey("clear_site_data")
        val SUSPEND_MEDIA = booleanPreferencesKey("suspend_media")
    }

    private val _cached = MutableStateFlow(BrowserPrefs())
    val cached: StateFlow<BrowserPrefs> get() = _cached

    init {
        CoroutineScope(Dispatchers.IO).launch { _cached.value = read() }
    }

    fun observe(): Flow<BrowserPrefs> = context.dataStore.data.map { p ->
        val value = decode(p)
        _cached.value = value
        value
    }

    suspend fun snapshot(): BrowserPrefs = observe().first()

    /** 同步读取缓存，供 Delegate 的同步回调（如 onLoadRequest）使用。 */
    fun httpsOnlyCached(): Boolean = _cached.value.httpsOnly
    fun httpsExceptionsCached(): Set<String> = _cached.value.httpsExceptions
    suspend fun checkUpdateOnLaunch(): Boolean = snapshot().checkUpdateOnLaunch
    fun proxyBase(): String = _cached.value.updateProxyBase

    private fun decode(p: Preferences): BrowserPrefs = BrowserPrefs(
        themeMode = runCatching { ThemeMode.valueOf(p[Keys.THEME] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
        toolbarPosition = runCatching { ToolbarPosition.valueOf(p[Keys.TOOLBAR] ?: "TOP") }.getOrDefault(ToolbarPosition.TOP),
        trackingPolicy = runCatching { TrackingPolicy.valueOf(p[Keys.TRACKING] ?: "STANDARD") }.getOrDefault(TrackingPolicy.STANDARD),
        httpsOnly = p[Keys.HTTPS_ONLY] ?: true,
        httpsExceptions = (p[Keys.HTTPS_EXCEPTIONS] ?: "").split('|').filter { it.isNotBlank() }.toSet(),
        checkUpdateOnLaunch = p[Keys.CHECK_UPDATE] ?: true,
        updateProxyBase = p[Keys.PROXY_BASE] ?: "https://v6.gh-proxy.com/https://github.com/",
        allowBackgroundAudio = p[Keys.BG_AUDIO] ?: true,
        allowBackgroundVideo = p[Keys.BG_VIDEO] ?: false,
        autoPictureInPicture = p[Keys.AUTO_PIP] ?: false,
        showBookmarksBar = p[Keys.BOOKMARKS_BAR] ?: true,
        swipeTabSwitch = p[Keys.SWIPE_TABS] ?: true,
        edgeSwipeSensitivity = p[Keys.EDGE_SENSITIVITY] ?: 0.5f,
        clearCacheOnExit = p[Keys.CLEAR_CACHE] ?: false,
        clearCookiesOnExit = p[Keys.CLEAR_COOKIES] ?: false,
        clearHistoryOnExit = p[Keys.CLEAR_HISTORY] ?: false,
        clearSiteDataOnExit = p[Keys.CLEAR_SITE_DATA] ?: false,
        suspendedMediaWhenInactive = p[Keys.SUSPEND_MEDIA] ?: true
    )

    private suspend fun read(): BrowserPrefs = decode(context.dataStore.data.first())

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
        _cached.value = read()
    }

    /** 备份：导出全部偏好键（键名 -> 原始值），供 BackupManager 写入 JSON。 */
    suspend fun exportAll(): Map<String, Any?> =
        context.dataStore.data.first().asMap().entries.associate { it.key.name to it.value }

    /** 恢复：按值的类型重建 Preferences.Key 并写回，随后刷新内存缓存。 */
    suspend fun importAll(values: Map<String, Any?>) = edit { p ->
        values.forEach { (name, value) ->
            when (value) {
                null -> Unit
                is Boolean -> p[booleanPreferencesKey(name)] = value
                is Int -> p[intPreferencesKey(name)] = value
                is Long -> p[longPreferencesKey(name)] = value
                is Float -> p[floatPreferencesKey(name)] = value
                is Double -> p[floatPreferencesKey(name)] = value.toFloat()
                is Set<*> -> p[stringSetPreferencesKey(name)] = value.map { it.toString() }.toSet()
                else -> p[stringPreferencesKey(name)] = value.toString()
            }
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME] = mode.name }
    suspend fun setToolbarPosition(position: ToolbarPosition) = edit { it[Keys.TOOLBAR] = position.name }
    suspend fun setTrackingPolicy(policy: TrackingPolicy) = edit { it[Keys.TRACKING] = policy.name }
    suspend fun setHttpsOnly(enabled: Boolean) = edit { it[Keys.HTTPS_ONLY] = enabled }
    suspend fun setHttpsException(host: String, allowed: Boolean) = edit {
        val current = _cached.value.httpsExceptions.toMutableSet()
        if (allowed) current.add(host) else current.remove(host)
        it[Keys.HTTPS_EXCEPTIONS] = current.joinToString("|")
    }
    suspend fun setCheckUpdateOnLaunch(enabled: Boolean) = edit { it[Keys.CHECK_UPDATE] = enabled }
    suspend fun setUpdateProxyBase(base: String) = edit { it[Keys.PROXY_BASE] = base }
    suspend fun setAllowBackgroundAudio(v: Boolean) = edit { it[Keys.BG_AUDIO] = v }
    suspend fun setAllowBackgroundVideo(v: Boolean) = edit { it[Keys.BG_VIDEO] = v }
    suspend fun setAutoPictureInPicture(v: Boolean) = edit { it[Keys.AUTO_PIP] = v }
    suspend fun setShowBookmarksBar(v: Boolean) = edit { it[Keys.BOOKMARKS_BAR] = v }
    suspend fun setSwipeTabSwitch(v: Boolean) = edit { it[Keys.SWIPE_TABS] = v }
    suspend fun setEdgeSensitivity(v: Float) = edit { it[Keys.EDGE_SENSITIVITY] = v }
    suspend fun setClearOnExit(cache: Boolean, cookies: Boolean, history: Boolean, siteData: Boolean) = edit {
        it[Keys.CLEAR_CACHE] = cache; it[Keys.CLEAR_COOKIES] = cookies
        it[Keys.CLEAR_HISTORY] = history; it[Keys.CLEAR_SITE_DATA] = siteData
    }
    suspend fun setSuspendMedia(v: Boolean) = edit { it[Keys.SUSPEND_MEDIA] = v }
}
