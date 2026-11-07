package com.local.geckobrowser.browser

import android.net.Uri
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebResponse

/**
 * 所有 Delegate 回调统一转换成不可变事件，交给 BrowserViewModel.onEngineEvent 处理，
 * 形成 Compose UI → ViewModel → GeckoSession/Delegate → ViewModel → Compose UI 的单向数据流。
 */
sealed interface EngineEvent {

    data class LocationChange(
        val tabId: String, val url: String, val hasUserGesture: Boolean
    ) : EngineEvent

    data class NavigationCapability(
        val tabId: String, val canGoBack: Boolean? = null, val canGoForward: Boolean? = null
    ) : EngineEvent

    data class TitleChange(val tabId: String, val title: String) : EngineEvent
    data class FullScreen(val tabId: String, val enabled: Boolean) : EngineEvent
    data class DynamicToolbar(val tabId: String, val show: Boolean) : EngineEvent
    data class FirstContentfulPaint(val tabId: String) : EngineEvent
    data class PaintStatusReset(val tabId: String) : EngineEvent

    /** ContentDelegate.onCrash：该 Session 已关闭且不可用，必须 open() 重建或 restoreState 恢复。 */
    data class Crash(val tabId: String) : EngineEvent

    /** ContentDelegate.onExternalResponse：v160 起 content-disposition 是桌面规则校验后的文件名。 */
    data class ExternalResponse(
        val tabId: String,
        val downloadId: String,
        val uri: String,
        val suggestedName: String,
        val mimeType: String?,
        val contentLength: Long,
        val response: WebResponse
    ) : EngineEvent

    data class PageStart(val tabId: String, val url: String) : EngineEvent
    data class PageStop(val tabId: String, val success: Boolean) : EngineEvent
    data class ProgressChange(val tabId: String, val progress: Int) : EngineEvent

    /** ProgressDelegate.onSecurityChange 提供 SecurityInformation。 */
    data class SecurityChange(
        val tabId: String,
        val isSecure: Boolean,
        val host: String,
        val origin: String,
        val securityMode: Int,
        val certificateIssuer: String?
    ) : EngineEvent

    /** ProgressDelegate.onSessionStateChange 提供可序列化的 SessionState。 */
    data class SessionStateChange(val tabId: String, val serialized: String) : EngineEvent

    /** HistoryDelegate.onHistoryStateChange / onVisited：GeckoView 本身不存储历史。 */
    data class HistoryStateChange(val tabId: String, val currentIndex: Int) : EngineEvent
    data class Visited(val tabId: String, val url: String, val title: String) : EngineEvent

    /** 权限请求：Material 3 AlertDialog 展示来源域名与权限类型。 */
    data class PermissionRequest(
        val tabId: String,
        val uri: String,
        val permissionLabel: String,
        val kind: PermissionKind,
        val onResult: (Boolean, Boolean) -> Unit  // (granted, always)
    ) : EngineEvent

    enum class PermissionKind { CAMERA, MICROPHONE, GEOLOCATION, NOTIFICATION, STORAGE, AUTOPLAY, OTHER }

    /** 网页 prompt 请求，统一由 Material 3 对话框承载。 */
    data class PromptRequest(
        val tabId: String,
        val kind: PromptKind,
        val title: String,
        val message: String,
        val defaultValue: String = "",
        val items: List<String> = emptyList(),
        val onConfirm: (String, String, Int) -> Unit,
        val onDismiss: () -> Unit
    ) : EngineEvent

    enum class PromptKind {
        ALERT, CONFIRM, TEXT, AUTH, FILE, LOGIN_SAVE, LOGIN_SELECT,
        CREDIT_CARD_SAVE, CREDIT_CARD_SELECT, ADDRESS_SAVE, ADDRESS_SELECT,
        BEFORE_UNLOAD, CHOICE, DATE_TIME, COLOR, POPUP, REPOST, SHARE, OTHER
    }

    /** 选中文本动作：复制、分享、全选、翻译选中文本。 */
    data class SelectionAction(
        val tabId: String,
        val text: String,
        val actions: List<String>,
        val execute: (String) -> Unit
    ) : EngineEvent

    data class MediaEvent(
        val tabId: String,
        val kind: MediaEventKind,
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val playing: Boolean = false,
        val duration: Double = 0.0,
        val position: Double = 0.0,
        val fullscreen: Boolean = false
    ) : EngineEvent

    enum class MediaEventKind {
        ACTIVATED, DEACTIVATED, METADATA, FEATURES, FULLSCREEN,
        PLAY, PAUSE, STOP, POSITION, AUDIO_TYPE
    }

    /** 扩展动作入口：移动端 WebExtension UI API 不完整，必须自行在菜单/工具栏提供入口。 */
    data class ExtensionAction(
        val extensionId: String,
        val title: String,
        val session: GeckoSession?,
        val click: () -> Unit
    ) : EngineEvent

    data class ExtensionNewTab(val uri: String, val active: Boolean) : EngineEvent
    data class ExtensionStateChanged(val extensionId: String, val status: String) : EngineEvent
    data class TranslationRequest(val tabId: String, val text: String) : EngineEvent
    data class ShareText(val tabId: String, val text: String) : EngineEvent
    data class SelectionDismiss(val tabId: String) : EngineEvent
    data class PictureInPicture(val tabId: String, val enter: Boolean, val token: String?) : EngineEvent
    data class TranslationState(
        val tabId: String, val canTranslate: Boolean, val offered: Boolean, val state: String = ""
    ) : EngineEvent
    data class ContextMenu(val tabId: String, val linkUri: Uri?, val imageUri: Uri?) : EngineEvent
}

/** NavigationDelegate.onLoadRequest 返回的决策，由 ViewModel 依据本地规则给出。 */
data class LoadDecision(val result: AllowOrDeny, val rewrittenUri: String? = null)
