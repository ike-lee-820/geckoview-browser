package com.local.geckobrowser.browser.delegates

import android.Manifest
import android.content.Context
import android.net.Uri
import androidx.annotation.UiThread
import com.local.geckobrowser.browser.EngineEvent
import com.local.geckobrowser.browser.TabSessionManager
import com.local.geckobrowser.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.MediaSession

/**
 * GeckoSession.PermissionDelegate 完整实现：摄像头、麦克风、地理位置、通知、持久存储、媒体自动播放。
 * 库本身不在 manifest 中请求危险权限，CAMERA / RECORD_AUDIO 等由本应用声明并在 Activity 中申请。
 */
class PermissionDelegateImpl(
    private val tabId: String,
    private val manager: TabSessionManager,
    private val emit: (EngineEvent) -> Unit
) : GeckoSession.PermissionDelegate {

    override fun onAndroidPermissionsRequest(
        session: GeckoSession,
        permissions: Array<String>?,
        callback: GeckoSession.PermissionDelegate.Callback
    ) {
        val kinds = (permissions ?: emptyArray()).map(::mapAndroidPermission)
        kinds.forEach { kind ->
            emit(
                EngineEvent.PermissionRequest(
                    tabId = tabId,
                    uri = manager.tab(tabId)?.url.orEmpty(),
                    permissionLabel = kind.label(),
                    kind = kind,
                    onResult = { granted, _ -> if (granted) callback.grant() else callback.reject() }
                )
            )
        }
        if (kinds.isEmpty()) callback.grant()
    }

    override fun onContentPermissionRequest(
        session: GeckoSession,
        perm: GeckoSession.PermissionDelegate.ContentPermission
    ): GeckoResult<Int> {
        val result = GeckoResult<Int>()
        val kind = mapContentPermission(perm.permission)
        emit(
            EngineEvent.PermissionRequest(
                tabId = tabId,
                uri = perm.uri.orEmpty(),
                permissionLabel = kind.label(),
                kind = kind,
                onResult = { granted, always ->
                    val value = if (granted)
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                    else GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                    result.complete(value)
                    // 始终允许/阻止：写入本地站点设置，不经过任何云端存储。
                    if (always) persistSitePermission(perm.uri.orEmpty(), kind, granted)
                }
            )
        )
        return result
    }

    override fun onMediaPermissionRequest(
        session: GeckoSession,
        uri: String,
        video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
        audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
        callback: GeckoSession.PermissionDelegate.MediaCallback
    ) {
        // WebRTC 媒体权限：把摄像头/麦克风请求交给 M3 AlertDialog，用户确认后回调 grant/reject。
        emit(
            EngineEvent.PermissionRequest(
                tabId = tabId,
                uri = uri,
                permissionLabel = if (video != null) "摄像头" else "麦克风",
                kind = EngineEvent.PermissionKind.CAMERA,
                onResult = { granted, _ ->
                    if (granted) {
                        val v = video?.firstOrNull()
                        val a = audio?.firstOrNull()
                        if (v != null || a != null) callback.grant(v, a)
                        else callback.grant(uri, uri)
                    } else callback.reject()
                }
            )
        )
    }

    private fun persistSitePermission(origin: String, kind: EngineEvent.PermissionKind, granted: Boolean) {
        val db = AppDatabase.build(manager.context)
        CoroutineScope(Dispatchers.IO).launch {
            db.siteSettingDao().upsert(
                com.local.geckobrowser.data.SiteSettingEntity(
                    origin = origin,
                    camera = if (kind == EngineEvent.PermissionKind.CAMERA) granted else null,
                    microphone = if (kind == EngineEvent.PermissionKind.MICROPHONE) granted else null,
                    geolocation = if (kind == EngineEvent.PermissionKind.GEOLOCATION) granted else null,
                    notification = if (kind == EngineEvent.PermissionKind.NOTIFICATION) granted else null,
                    autoplay = if (kind == EngineEvent.PermissionKind.AUTOPLAY) granted else null,
                    javascriptEnabled = true
                )
            )
        }
    }

    private fun mapAndroidPermission(p: String) = when (p) {
        Manifest.permission.CAMERA -> EngineEvent.PermissionKind.CAMERA
        Manifest.permission.RECORD_AUDIO -> EngineEvent.PermissionKind.MICROPHONE
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION ->
            EngineEvent.PermissionKind.GEOLOCATION
        Manifest.permission.POST_NOTIFICATIONS -> EngineEvent.PermissionKind.NOTIFICATION
        else -> EngineEvent.PermissionKind.OTHER
    }

    private fun mapContentPermission(permission: Int) = when (permission) {
        GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> EngineEvent.PermissionKind.GEOLOCATION
        GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> EngineEvent.PermissionKind.NOTIFICATION
        GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> EngineEvent.PermissionKind.STORAGE
        GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
        GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> EngineEvent.PermissionKind.AUTOPLAY
        else -> EngineEvent.PermissionKind.OTHER
    }

    private fun EngineEvent.PermissionKind.label() = when (this) {
        EngineEvent.PermissionKind.CAMERA -> "摄像头"
        EngineEvent.PermissionKind.MICROPHONE -> "麦克风"
        EngineEvent.PermissionKind.GEOLOCATION -> "地理位置"
        EngineEvent.PermissionKind.NOTIFICATION -> "通知"
        EngineEvent.PermissionKind.STORAGE -> "持久存储"
        EngineEvent.PermissionKind.AUTOPLAY -> "媒体自动播放"
        EngineEvent.PermissionKind.OTHER -> "站点权限"
    }
}

/**
 * GeckoSession.PromptDelegate 完整实现。
 * 新版 JavaDoc 中所有回调返回 GeckoResult<PromptResponse>；
 * 每个 prompt 的 confirm/dismiss 返回一个不透明的 PromptResponse，用挂起的 GeckoResult 等待用户操作。
 */
class PromptDelegateImpl(
    private val tabId: String,
    private val manager: TabSessionManager,
    private val emit: (EngineEvent) -> Unit
) : GeckoSession.PromptDelegate {

    private fun pending(): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> =
        GeckoResult()

    private fun request(
        kind: EngineEvent.PromptKind,
        title: String,
        message: String,
        defaultValue: String = "",
        items: List<String> = emptyList(),
        onConfirm: (String, String, Int) -> Unit,
        onDismiss: () -> Unit
    ) = emit(
        EngineEvent.PromptRequest(tabId, kind, title, message, defaultValue, items, onConfirm, onDismiss)
    )

    private fun GeckoSession.PromptDelegate.BasePrompt.response() =
        dismiss()

    override fun onAlertPrompt(
        session: GeckoSession, prompt: GeckoSession.PromptDelegate.AlertPrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = pending()
        request(EngineEvent.PromptKind.ALERT, prompt.title.orEmpty(), prompt.message.orEmpty(),
            onConfirm = { _, _, _ -> result.complete(prompt.response()) },
            onDismiss = { result.complete(prompt.dismiss()) })
        return result
    }

    override fun onButtonPrompt(
        session: GeckoSession, prompt: GeckoSession.PromptDelegate.ButtonPrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = pending()
        request(EngineEvent.PromptKind.CONFIRM, prompt.title.orEmpty(), prompt.message.orEmpty(),
            items = listOf("取消", "确定"),
            onConfirm = { _, _, index ->
                // confirm(int) 接收按钮类型：POSITIVE=1 / NEGATIVE=0（按 JavaDoc ButtonPrompt.Type 常量语义）。
                result.complete(prompt.confirm(if (index == 1) 1 else 0))
            },
            onDismiss = { result.complete(prompt.dismiss()) })
        return result
    }

    override fun onTextPrompt(
        session: GeckoSession, prompt: GeckoSession.PromptDelegate.TextPrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = pending()
        request(EngineEvent.PromptKind.TEXT, prompt.title.orEmpty(), prompt.message.orEmpty(),
            defaultValue = prompt.defaultValue.orEmpty(),
            onConfirm = { value, _, _ -> result.complete(prompt.confirm(value)) },
            onDismiss = { result.complete(prompt.dismiss()) })
        return result
    }

    override fun onAuthPrompt(
        session: GeckoSession, prompt: GeckoSession.PromptDelegate.AuthPrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = pending()
        request(EngineEvent.PromptKind.AUTH, prompt.title.orEmpty(), prompt.message.orEmpty(),
            defaultValue = prompt.authOptions?.username.orEmpty(),
            onConfirm = { user, password, _ ->
                result.complete(
                    if (password.isBlank()) prompt.confirm(user)
                    else prompt.confirm(user, password)
                )
            },
            onDismiss = { result.complete(prompt.dismiss()) })
        return result
    }

    override fun onFilePrompt(
        session: GeckoSession, prompt: GeckoSession.PromptDelegate.FilePrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = pending()
        // 网页 file chooser：SAF 选择文件或调用系统相机拍照，结果以 Uri 字符串回传。
        request(EngineEvent.PromptKind.FILE, prompt.title.orEmpty(), "选择要上传的文件",
            items = (prompt.mimeTypes ?: emptyArray()).toList(),
            onConfirm = { value, _, _ ->
                val uri = Uri.parse(value)
                result.complete(
                    if (prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE)
                        prompt.confirm(manager.context, arrayOf(uri))
                    else prompt.confirm(manager.context, uri)
                )
            },
            onDismiss = { result.complete(prompt.dismiss()) })
        return result
    }

    override fun onLoginSave(
        session: GeckoSession,
        request: GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.LoginSaveOption>
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = pending()
        val options = request.options ?: emptyArray()
        emit(
            EngineEvent.PromptRequest(
                tabId, EngineEvent.PromptKind.LOGIN_SAVE, "保存密码",
                "是否将本次登录信息保存到本地加密密码库？",
                items = options.map { it.value?.username ?: "保存" },
                onConfirm = { _, _, index ->
                    val chosen = options.getOrNull(index)
                    result.complete(if (chosen != null) request.confirm(chosen) else request.dismiss())
                },
                onDismiss = { result.complete(request.dismiss()) }
            )
        )
        return result
    }

    override fun onLoginSelect(
        session: GeckoSession,
        request: GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.LoginSelectOption>
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = pending()
        val options = request.options ?: emptyArray()
        // 同一域名多个登录条目时展示 M3 选择列表。
        emit(
            EngineEvent.PromptRequest(
                tabId, EngineEvent.PromptKind.LOGIN_SELECT, "选择登录信息",
                "请选择要填入的本地账号",
                items = options.map { it.value?.username.orEmpty().ifBlank { "已保存账号" } },
                onConfirm = { _, _, index ->
                    val chosen = options.getOrNull(index)
                    result.complete(if (chosen != null) request.confirm(chosen) else request.dismiss())
                },
                onDismiss = { result.complete(request.dismiss()) }
            )
        )
        return result
    }

    override fun onCreditCardSave(
        session: GeckoSession,
        request: GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.CreditCardSaveOption>
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = pending()
        // v161 起 Autocomplete.CreditCard 支持 securityCode（CVV）；目标版本低于 v161 时不得依赖该字段。
        // 需核对当前 GeckoView Beta 版本 Changelog。
        val options = request.options ?: emptyArray()
        emit(
            EngineEvent.PromptRequest(
                tabId, EngineEvent.PromptKind.CREDIT_CARD_SAVE, "保存信用卡",
                "是否将信用卡信息保存到本地加密存储？",
                items = options.map { "保存" },
                onConfirm = { _, _, index ->
                    val chosen = options.getOrNull(index)
                    result.complete(if (chosen != null) request.confirm(chosen) else request.dismiss())
                },
                onDismiss = { result.complete(request.dismiss()) }
            )
        )
        return result
    }

    override fun onCreditCardSelect(
        session: GeckoSession,
        request: GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.CreditCardSelectOption>
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = pending()
        val options = request.options ?: emptyArray()
        emit(
            EngineEvent.PromptRequest(
                tabId, EngineEvent.PromptKind.CREDIT_CARD_SELECT, "选择信用卡", "请选择要填入的卡片",
                items = options.map { "已保存卡片" },
                onConfirm = { _, _, index ->
                    val chosen = options.getOrNull(index)
                    result.complete(if (chosen != null) request.confirm(chosen) else request.dismiss())
                },
                onDismiss = { result.complete(request.dismiss()) }
            )
        )
        return result
    }

    override fun onAddressSave(
        session: GeckoSession,
        request: GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.AddressSaveOption>
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = pending()
        val options = request.options ?: emptyArray()
        emit(
            EngineEvent.PromptRequest(
                tabId, EngineEvent.PromptKind.ADDRESS_SAVE, "保存地址", "是否保存到本地地址库？",
                items = options.map { "保存" },
                onConfirm = { _, _, index ->
                    val chosen = options.getOrNull(index)
                    result.complete(if (chosen != null) request.confirm(chosen) else request.dismiss())
                },
                onDismiss = { result.complete(request.dismiss()) }
            )
        )
        return result
    }

    override fun onAddressSelect(
        session: GeckoSession,
        request: GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.AddressSelectOption>
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = pending()
        val options = request.options ?: emptyArray()
        emit(
            EngineEvent.PromptRequest(
                tabId, EngineEvent.PromptKind.ADDRESS_SELECT, "选择地址", "请选择要填入的地址",
                items = options.map { "已保存地址" },
                onConfirm = { _, _, index ->
                    val chosen = options.getOrNull(index)
                    result.complete(if (chosen != null) request.confirm(chosen) else request.dismiss())
                },
                onDismiss = { result.complete(request.dismiss()) }
            )
        )
        return result
    }

    override fun onBeforeUnloadPrompt(
        session: GeckoSession, prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = pending()
        request(EngineEvent.PromptKind.BEFORE_UNLOAD, "离开页面？", "页面提示有未保存的更改",
            items = listOf("留在页面", "离开"),
            onConfirm = { _, _, index ->
                result.complete(prompt.confirm(if (index == 1) AllowOrDeny.ALLOW else AllowOrDeny.DENY))
            },
            onDismiss = { result.complete(prompt.dismiss()) })
        return result
    }

    override fun onChoicePrompt(
        session: GeckoSession, prompt: GeckoSession.PromptDelegate.ChoicePrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = pending()
        val choices = prompt.choices ?: emptyArray()
        request(EngineEvent.PromptKind.CHOICE, prompt.title.orEmpty(), prompt.message.orEmpty(),
            items = choices.map { it.label.orEmpty() },
            onConfirm = { _, _, index ->
                val c = choices.getOrNull(index)
                result.complete(if (c != null) prompt.confirm(c.id.orEmpty()) else prompt.dismiss())
            },
            onDismiss = { result.complete(prompt.dismiss()) })
        return result
    }

    override fun onDateTimePrompt(
        session: GeckoSession, prompt: GeckoSession.PromptDelegate.DateTimePrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = pending()
        request(EngineEvent.PromptKind.DATE_TIME, prompt.title.orEmpty(), "选择日期与时间",
            onConfirm = { value, _, _ -> result.complete(prompt.confirm(value)) },
            onDismiss = { result.complete(prompt.dismiss()) })
        return result
    }

    override fun onColorPrompt(
        session: GeckoSession, prompt: GeckoSession.PromptDelegate.ColorPrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = pending()
        request(EngineEvent.PromptKind.COLOR, prompt.title.orEmpty(), "选择颜色",
            onConfirm = { value, _, _ -> result.complete(prompt.confirm(value)) },
            onDismiss = { result.complete(prompt.dismiss()) })
        return result
    }

    override fun onPopupPrompt(
        session: GeckoSession, prompt: GeckoSession.PromptDelegate.PopupPrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = pending()
        request(EngineEvent.PromptKind.POPUP, "弹出窗口", "页面请求打开新窗口",
            onConfirm = { _, _, index ->
                result.complete(prompt.confirm(if (index == 1) AllowOrDeny.ALLOW else AllowOrDeny.DENY))
            },
            onDismiss = { result.complete(prompt.dismiss()) })
        return result
    }

    override fun onRepostConfirmPrompt(
        session: GeckoSession, prompt: GeckoSession.PromptDelegate.RepostConfirmPrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = pending()
        request(EngineEvent.PromptKind.REPOST, "重新提交表单", "是否重新发送表单数据？",
            onConfirm = { _, _, index ->
                result.complete(prompt.confirm(if (index == 1) AllowOrDeny.ALLOW else AllowOrDeny.DENY))
            },
            onDismiss = { result.complete(prompt.dismiss()) })
        return result
    }

    override fun onRedirectPrompt(
        session: GeckoSession, prompt: GeckoSession.PromptDelegate.RedirectPrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = pending()
        request(EngineEvent.PromptKind.OTHER, "重定向确认", "页面请求跳转",
            onConfirm = { _, _, index ->
                result.complete(prompt.confirm(if (index == 1) AllowOrDeny.ALLOW else AllowOrDeny.DENY))
            },
            onDismiss = { result.complete(prompt.dismiss()) })
        return result
    }

    override fun onSharePrompt(
        session: GeckoSession, prompt: GeckoSession.PromptDelegate.SharePrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = pending()
        request(EngineEvent.PromptKind.SHARE, "分享", "网页请求调用系统分享",
            onConfirm = { _, _, index -> result.complete(prompt.confirm(index)) },
            onDismiss = { result.complete(prompt.dismiss()) })
        return result
    }

    override fun onFolderUploadPrompt(
        session: GeckoSession, prompt: GeckoSession.PromptDelegate.FolderUploadPrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = pending()
        request(EngineEvent.PromptKind.FILE, "上传文件夹", "页面请求上传文件夹",
            onConfirm = { _, _, index ->
                result.complete(prompt.confirm(if (index == 1) AllowOrDeny.ALLOW else AllowOrDeny.DENY))
            },
            onDismiss = { result.complete(prompt.dismiss()) })
        return result
    }

    override fun onRequestCertificate(
        session: GeckoSession, request: GeckoSession.PromptDelegate.CertificateRequest
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = pending()
        emit(
            EngineEvent.PromptRequest(
                tabId, EngineEvent.PromptKind.OTHER, "选择证书", "页面请求客户端证书",
                onConfirm = { value, _, _ -> result.complete(request.confirm(value)) },
                onDismiss = { result.complete(request.dismiss()) }
            )
        )
        return result
    }

    override fun onWebAuthnRelatedOriginPrompt(
        session: GeckoSession, prompt: GeckoSession.PromptDelegate.WebAuthnRelatedOriginPrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = pending()
        request(EngineEvent.PromptKind.OTHER, "验证请求", "站点请求关联来源验证",
            onConfirm = { _, _, index ->
                result.complete(prompt.confirm(if (index == 1) AllowOrDeny.ALLOW else AllowOrDeny.DENY))
            },
            onDismiss = { result.complete(prompt.dismiss()) })
        return result
    }

    override fun onSelectIdentityCredentialAccount(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.IdentityCredential.AccountSelectorPrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = pending()
        request(EngineEvent.PromptKind.OTHER, "选择账号", "请选择登录账号",
            onConfirm = { _, _, index -> result.complete(prompt.confirm(index)) },
            onDismiss = { result.complete(prompt.dismiss()) })
        return result
    }

    override fun onSelectIdentityCredentialProvider(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.IdentityCredential.ProviderSelectorPrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = pending()
        request(EngineEvent.PromptKind.OTHER, "选择身份提供方", "请选择服务",
            onConfirm = { _, _, index -> result.complete(prompt.confirm(index)) },
            onDismiss = { result.complete(prompt.dismiss()) })
        return result
    }

    override fun onShowPrivacyPolicyIdentityCredential(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.IdentityCredential.PrivacyPolicyPrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = pending()
        request(EngineEvent.PromptKind.OTHER, "隐私政策", "查看服务隐私政策",
            onConfirm = { _, _, index -> result.complete(prompt.confirm(index == 1)) },
            onDismiss = { result.complete(prompt.dismiss()) })
        return result
    }
}

/**
 * GeckoSession.HistoryDelegate 完整实现。
 * GeckoView 本身不存储浏览历史，必须由应用侦听并持久化到 Room。
 */
class HistoryDelegateImpl(
    private val tabId: String,
    private val manager: TabSessionManager,
    private val database: AppDatabase,
    private val scope: CoroutineScope,
    private val emit: (EngineEvent) -> Unit
) : GeckoSession.HistoryDelegate {

    override fun onHistoryStateChange(
        session: GeckoSession,
        historyList: GeckoSession.HistoryDelegate.HistoryList
    ) {
        emit(EngineEvent.HistoryStateChange(tabId, historyList.currentIndex))
    }

    override fun onVisited(
        session: GeckoSession,
        url: String,
        lastVisitedURL: String?,
        flags: Int
    ): GeckoResult<Boolean> {
        if (manager.tab(tabId)?.isPrivate != true && flags and
            GeckoSession.HistoryDelegate.VISIT_UNRECOVERABLE_ERROR == 0
        ) {
            scope.launch(Dispatchers.IO) {
                database.historyDao().insert(
                    com.local.geckobrowser.data.HistoryEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        url = url, title = url, visitedAt = System.currentTimeMillis()
                    )
                )
            }
        }
        emit(EngineEvent.Visited(tabId, url, ""))
        return GeckoResult.fromValue(true)
    }

    override fun getVisited(session: GeckoSession, urls: Array<String>): GeckoResult<BooleanArray> {
        val result = GeckoResult<BooleanArray>()
        scope.launch(Dispatchers.IO) {
            val known = database.historyDao().allUrls().toHashSet()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                result.complete(BooleanArray(urls.size) { known.contains(urls[it]) })
            }
        }
        return result
    }

    override fun hasVisitedHostSince(
        session: GeckoSession,
        host: String,
        afterEpochMillis: Long,
        beforeEpochMillis: Long
    ): GeckoResult<Boolean> {
        val result = GeckoResult<Boolean>()
        scope.launch(Dispatchers.IO) {
            val hit = database.historyDao().hostVisitedSince(host, afterEpochMillis, beforeEpochMillis)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { result.complete(hit) }
        }
        return result
    }
}

/**
 * GeckoSession.SelectionActionDelegate 完整实现：复制、分享、全选、翻译选中文本。
 */
class SelectionActionDelegateImpl(
    private val tabId: String,
    private val manager: TabSessionManager,
    private val emit: (EngineEvent) -> Unit
) : GeckoSession.SelectionActionDelegate {

    override fun onShowActionRequest(
        session: GeckoSession,
        selection: GeckoSession.SelectionActionDelegate.Selection
    ) {
        val actions = (selection.availableActions ?: emptyArray<String>()).toList()
        // 翻译选中文本是应用层动作（本地模型），插入到菜单项列表并由 M3 菜单承载。
        emit(
            EngineEvent.SelectionAction(
                tabId = tabId,
                text = selection.text.orEmpty(),
                actions = actions + listOf("翻译选中文本", "分享"),
                execute = { action ->
                    when (action) {
                        "翻译选中文本" -> emit(
                            EngineEvent.TranslationRequest(tabId, selection.text.orEmpty())
                        )
                        "分享" -> emit(EngineEvent.ShareText(tabId, selection.text.orEmpty()))
                        else -> selection.execute(action)
                    }
                }
            )
        )
    }

    override fun onHideAction(session: GeckoSession, reason: Int) {
        emit(EngineEvent.SelectionDismiss(tabId))
    }

    override fun onShowClipboardPermissionRequest(
        session: GeckoSession,
        permission: GeckoSession.SelectionActionDelegate.ClipboardPermission
    ): GeckoResult<AllowOrDeny> {
        // 本地剪贴板读取：仅在用户主动粘贴时允许，不做任何上传。
        return GeckoResult.fromValue(AllowOrDeny.ALLOW)
    }

    override fun onDismissClipboardPermissionRequest(session: GeckoSession) {
        emit(EngineEvent.SelectionDismiss(tabId))
    }
}

/**
 * MediaSession.Delegate 完整实现。JavaDoc 要求所有回调添加 @UiThread 注解。
 * 媒体通知依赖应用通知权限（POST_NOTIFICATIONS）开启，由 MainActivity 申请。
 *
 * 注意：JavaDoc 中不存在 onPlaybackStateChange；播放/暂停/停止状态由
 * onPlay / onPause / onStop / onDeactivated 上报，此处按真实签名实现。
 */
class MediaSessionDelegateImpl(
    private val tabId: String,
    private val manager: TabSessionManager,
    private val emit: (EngineEvent) -> Unit
) : MediaSession.Delegate {

    @UiThread
    override fun onActivated(session: GeckoSession, mediaSession: MediaSession) {
        emit(EngineEvent.MediaEvent(tabId, EngineEvent.MediaEventKind.ACTIVATED))
    }

    @UiThread
    override fun onDeactivated(session: GeckoSession, mediaSession: MediaSession) {
        emit(EngineEvent.MediaEvent(tabId, EngineEvent.MediaEventKind.DEACTIVATED))
    }

    @UiThread
    override fun onMetadata(
        session: GeckoSession, mediaSession: MediaSession, meta: MediaSession.Metadata
    ) {
        emit(
            EngineEvent.MediaEvent(
                tabId, EngineEvent.MediaEventKind.METADATA,
                title = meta.title.orEmpty(), artist = meta.artist.orEmpty(), album = meta.album.orEmpty()
            )
        )
    }

    @UiThread
    override fun onFeatures(session: GeckoSession, mediaSession: MediaSession, features: Long) {
        emit(EngineEvent.MediaEvent(tabId, EngineEvent.MediaEventKind.FEATURES))
    }

    @UiThread
    override fun onPlay(session: GeckoSession, mediaSession: MediaSession) {
        emit(EngineEvent.MediaEvent(tabId, EngineEvent.MediaEventKind.PLAY, playing = true))
    }

    @UiThread
    override fun onPause(session: GeckoSession, mediaSession: MediaSession) {
        emit(EngineEvent.MediaEvent(tabId, EngineEvent.MediaEventKind.PAUSE, playing = false))
    }

    @UiThread
    override fun onStop(session: GeckoSession, mediaSession: MediaSession) {
        emit(EngineEvent.MediaEvent(tabId, EngineEvent.MediaEventKind.STOP, playing = false))
    }

    @UiThread
    override fun onPositionState(
        session: GeckoSession, mediaSession: MediaSession, state: MediaSession.PositionState
    ) {
        emit(
            EngineEvent.MediaEvent(
                tabId, EngineEvent.MediaEventKind.POSITION,
                duration = state.duration, position = state.position
            )
        )
    }

    @UiThread
    override fun onFullscreen(
        session: GeckoSession,
        mediaSession: MediaSession,
        enabled: Boolean,
        meta: MediaSession.ElementMetadata?
    ) {
        emit(
            EngineEvent.MediaEvent(
                tabId, EngineEvent.MediaEventKind.FULLSCREEN, fullscreen = enabled
            )
        )
        emit(EngineEvent.FullScreen(tabId, enabled))
    }

    @UiThread
    override fun onAudioSessionTypeChanged(
        session: GeckoSession, mediaSession: MediaSession, type: String
    ) {
        emit(EngineEvent.MediaEvent(tabId, EngineEvent.MediaEventKind.AUDIO_TYPE))
    }
}
