package com.local.geckobrowser.browser.delegates

import android.net.Uri
import android.util.Log
import com.local.geckobrowser.browser.EngineEvent
import com.local.geckobrowser.browser.TabSessionManager
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import org.mozilla.geckoview.SlowScriptResponse
import org.mozilla.geckoview.SlowScriptResponse
import java.util.UUID

/**
 * GeckoSession.NavigationDelegate 完整实现。
 * 方法签名核对自 JavaDoc：onLocationChange(GeckoSession, String, List<ContentPermission>, Boolean)，
 * 第 4 个参数即 hasUserGesture；onNewSession 返回 GeckoResult<GeckoSession>。
 */
class NavigationDelegateImpl(
    private val tabId: String,
    private val manager: TabSessionManager,
    private val emit: (EngineEvent) -> Unit
) : GeckoSession.NavigationDelegate {

    override fun onLocationChange(
        session: GeckoSession,
        url: String?,
        permissions: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
        hasUserGesture: Boolean
    ) {
        emit(EngineEvent.LocationChange(tabId, url.orEmpty(), hasUserGesture))
    }

    override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
        emit(EngineEvent.NavigationCapability(tabId, canGoBack = canGoBack))
    }

    override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
        emit(EngineEvent.NavigationCapability(tabId, canGoForward = canGoForward))
    }

    override fun onLoadRequest(
        session: GeckoSession,
        request: GeckoSession.NavigationDelegate.LoadRequest
    ): GeckoResult<AllowOrDeny> {
        // HTTPS-only、站点级 JavaScript 等策略完全在应用层本地决策，不做任何远程查询。
        val decision = manager.decideLoad(tabId, request.uri)
        if (decision.rewrittenUri != null) {
            // 拒绝当前 http 请求，随后由 ViewModel 重新发起 https 导航。
            session.loadUri(decision.rewrittenUri)
        }
        return GeckoResult.fromValue(decision.result)
    }

    override fun onSubframeLoadRequest(
        session: GeckoSession,
        request: GeckoSession.NavigationDelegate.LoadRequest
    ): GeckoResult<AllowOrDeny> {
        // 子框架同样走本地策略，避免绕过 HTTPS-only 例外判断。
        return GeckoResult.fromValue(manager.decideLoad(tabId, request.uri).result)
    }

    override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
        // GeckoView 没有「标签」概念：新建标签由应用层创建并返回新的 GeckoSession。
        val created = manager.createForNavigation(uri, tabId)
        return GeckoResult.fromValue(created)
    }

    override fun onLoadError(
        session: GeckoSession,
        uri: String?,
        error: WebRequestError
    ): GeckoResult<String> {
        Log.w("NavigationDelegate", "加载失败 uri=$uri category=${error.category} code=${error.code}")
        val result = GeckoResult<String>()
        result.complete(null)
        return result
    }
}

/**
 * GeckoSession.ContentDelegate 完整实现。
 * onExternalResponse 捕获下载；onCrash 后该 Session 已关闭且不可用，禁止继续使用。
 */
class ContentDelegateImpl(
    private val tabId: String,
    private val manager: TabSessionManager,
    private val emit: (EngineEvent) -> Unit
) : GeckoSession.ContentDelegate {

    override fun onTitleChange(session: GeckoSession, title: String?) {
        emit(EngineEvent.TitleChange(tabId, title.orEmpty().ifBlank { "无标题" }))
    }

    override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
        emit(EngineEvent.FullScreen(tabId, fullScreen))
    }

    override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
        // v160 起 WebResponse 的 content-disposition 头包含经桌面端同等规则验证的文件名，
        // 不得假设它是服务器原始发送值；因此这里直接采用该值作为建议文件名。
        // 需核对当前 GeckoView Beta 版本 Changelog。
        val headers = response.headers
        val disposition = headers.entries.firstOrNull {
            it.key.equals("content-disposition", ignoreCase = true)
        }?.value
        val mime = headers.entries.firstOrNull {
            it.key.equals("content-type", ignoreCase = true)
        }?.value
        val length = headers.entries.firstOrNull {
            it.key.equals("content-length", ignoreCase = true)
        }?.value?.toLongOrNull() ?: -1L

        val name = parseValidatedFilename(disposition)
            ?: Uri.parse(response.uri).lastPathSegment?.takeIf { it.isNotBlank() }
            ?: "download-${System.currentTimeMillis()}.bin"

        emit(
            EngineEvent.ExternalResponse(
                tabId = tabId,
                downloadId = UUID.randomUUID().toString(),
                uri = response.uri,
                suggestedName = name,
                mimeType = mime,
                contentLength = length,
                response = response
            )
        )
    }

    /** 解析已校验的 content-disposition 文件名；兼容 filename*= 与 filename= 两种写法。 */
    private fun parseValidatedFilename(disposition: String?): String? {
        if (disposition.isNullOrBlank()) return null
        val parts = disposition.split(';').map { it.trim() }
        val star = parts.firstOrNull { it.startsWith("filename*=", ignoreCase = true) }
        if (star != null) {
            val raw = star.substringAfter('=').trim().trim('"')
            val decoded = raw.substringAfter("''", raw)
            return runCatching { java.net.URLDecoder.decode(decoded, "UTF-8") }
                .getOrNull()?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        }
        val plain = parts.firstOrNull { it.startsWith("filename=", ignoreCase = true) }
        return plain?.substringAfter('=')?.trim()?.trim('"')?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
    }

    override fun onCrash(session: GeckoSession) {
        // ContentDelegate.onCrash 发生后 GeckoSession 即关闭且不可用，
        // 不得继续使用已崩溃的 Session；由 ViewModel 展示 M3 崩溃恢复界面后
        // 调用 manager.switchTo -> recreateSession -> open(runtime) + restoreState 恢复。
        manager.tab(tabId)?.crashed = true
        emit(EngineEvent.Crash(tabId))
    }

    override fun onKill(session: GeckoSession) {
        // 内容进程被系统回收：同样按崩溃路径重建，元数据始终保留。
        manager.tab(tabId)?.crashed = true
        emit(EngineEvent.Crash(tabId))
    }

    override fun onFirstContentfulPaint(session: GeckoSession) {
        emit(EngineEvent.FirstContentfulPaint(tabId))
    }

    override fun onFirstComposite(session: GeckoSession) {
        emit(EngineEvent.FirstContentfulPaint(tabId))
    }

    override fun onPaintStatusReset(session: GeckoSession) {
        emit(EngineEvent.PaintStatusReset(tabId))
    }

    override fun onShowDynamicToolbar(session: GeckoSession) {
        emit(EngineEvent.DynamicToolbar(tabId, true))
    }

    override fun onHideDynamicToolbar(session: GeckoSession) {
        emit(EngineEvent.DynamicToolbar(tabId, false))
    }

    override fun onCloseRequest(session: GeckoSession) {
        // 页面请求关闭窗口：等价于关闭对应标签。
        manager.closeTab(tabId)
    }

    override fun onFocusRequest(session: GeckoSession) {
        manager.switchTo(tabId)
    }

    override fun onContextMenu(
        session: GeckoSession,
        screenX: Int,
        screenY: Int,
        element: GeckoSession.ContentDelegate.ContextElement
    ) {
        // 长按链接/图片：新标签打开、复制链接、分享链接、保存图片等由 M3 上下文菜单承载。
        val link = element.linkUri?.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val image = element.srcUri?.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
        emit(EngineEvent.ContextMenu(tabId, link, image))
    }

    override fun onPointerIconChange(session: GeckoSession, icon: android.view.PointerIcon) {
        // 指针图标由系统接管，此处仅记录，避免空实现。
        Log.d("ContentDelegate", "pointer icon changed: $icon")
    }

    override fun onMetaViewportFitChange(session: GeckoSession, viewportFit: String) {
        Log.d("ContentDelegate", "viewport-fit=$viewportFit")
    }

    override fun onPreviewImage(session: GeckoSession, previewImageUrl: String) {
        manager.tab(tabId)?.favicon = previewImageUrl
    }

    override fun onWebAppManifest(session: GeckoSession, manifest: org.json.JSONObject) {
        Log.d("ContentDelegate", "web app manifest: ${manifest.optString("name")}")
    }

    override fun onSlowScript(
        session: GeckoSession,
        scriptFileName: String
    ): GeckoResult<SlowScriptResponse> {
        // 慢脚本：保持页面继续运行，由用户决定是否停止。
        return GeckoResult.fromValue(SlowScriptResponse.CONTINUE)
    }
}

/**
 * GeckoSession.ProgressDelegate 完整实现。
 * 注意 SecurityInformation 实为 GeckoSession.ProgressDelegate.SecurityInformation，
 * 由 onSecurityChange 传入（规格中的 GeckoSession.SecurityInformation 不存在，见 DOCS_READ.md）。
 */
class ProgressDelegateImpl(
    private val tabId: String,
    private val manager: TabSessionManager,
    private val emit: (EngineEvent) -> Unit
) : GeckoSession.ProgressDelegate {

    override fun onPageStart(session: GeckoSession, url: String) {
        emit(EngineEvent.PageStart(tabId, url))
    }

    override fun onPageStop(session: GeckoSession, success: Boolean) {
        emit(EngineEvent.PageStop(tabId, success))
    }

    override fun onProgressChange(session: GeckoSession, progress: Int) {
        emit(EngineEvent.ProgressChange(tabId, progress))
    }

    override fun onSecurityChange(
        session: GeckoSession,
        securityInfo: GeckoSession.ProgressDelegate.SecurityInformation
    ) {
        val issuer = runCatching {
            securityInfo.certificate?.issuerDN?.toString()
        }.getOrNull()
        emit(
            EngineEvent.SecurityChange(
                tabId = tabId,
                isSecure = securityInfo.isSecure,
                host = securityInfo.host.orEmpty(),
                origin = securityInfo.origin.orEmpty(),
                securityMode = securityInfo.securityMode,
                certificateIssuer = issuer
            )
        )
    }

    override fun onSessionStateChange(session: GeckoSession, sessionState: GeckoSession.SessionState) {
        // SessionState.toString() 可持久化；恢复用 SessionState.fromString(String) + restoreState。
        // GeckoView 不保存标签与滚动位置，全部由应用层落库。
        emit(EngineEvent.SessionStateChange(tabId, sessionState.toString()))
    }
}
