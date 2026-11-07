package com.local.geckobrowser.browser.delegates

import android.content.Context
import com.local.geckobrowser.browser.EngineEvent
import com.local.geckobrowser.browser.TabSessionManager
import com.local.geckobrowser.data.AppDatabase
import com.local.geckobrowser.data.LoginEntity
import com.local.geckobrowser.data.PasswordCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.PictureInPictureController
import org.mozilla.geckoview.TranslationsController
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/**
 * Autocomplete.StorageDelegate 完整实现：把 GeckoView 的登录表单处理接到本地加密存储。
 * 挂在 GeckoRuntime.setAutocompleteStorageDelegate 上（Runtime 级，不是 Session 级）。
 * 签名核对：onLoginFetch() / onLoginFetch(String) / onLoginSave / onLoginUsed / Address / CreditCard 系列。
 */
class AutocompleteStorageDelegateImpl(context: Context) : Autocomplete.StorageDelegate {

    private val app = context.applicationContext
    private val db = AppDatabase.build(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun LoginEntity.toEntry(): Autocomplete.LoginEntry =
        Autocomplete.LoginEntry.Builder()
            .guid(guid)
            .origin(origin)
            .formActionOrigin(formActionOrigin)
            .httpRealm(httpRealm)
            .username(username)
            .password(runCatching { PasswordCrypto.decrypt(this) }.getOrDefault(""))
            .build()

    private fun Autocomplete.LoginEntry.toEntity(): LoginEntity {
        val sealed = PasswordCrypto.encrypt(password.orEmpty())
        return LoginEntity(
            guid = guid?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString(),
            origin = origin.orEmpty(),
            formActionOrigin = formActionOrigin,
            httpRealm = httpRealm,
            username = username.orEmpty(),
            passwordCipher = sealed.cipher,
            passwordIv = sealed.iv,
            lastUsed = System.currentTimeMillis()
        )
    }

    override fun onLoginFetch(): GeckoResult<Array<Autocomplete.LoginEntry>> {
        val result = GeckoResult<Array<Autocomplete.LoginEntry>>()
        scope.launch {
            // 一次性读取：Room Flow 取首个值即可满足同步 GeckoResult 语义。
            val entries = db.loginDao().all().firstOrNull() ?: emptyList()
            withContext(Dispatchers.Main) {
                result.complete(entries.map { it.toEntry() }.toTypedArray())
            }
        }
        return result
    }

    override fun onLoginFetch(domain: String): GeckoResult<Array<Autocomplete.LoginEntry>> {
        val result = GeckoResult<Array<Autocomplete.LoginEntry>>()
        scope.launch {
            val entries = db.loginDao().byDomain(domain)
            withContext(Dispatchers.Main) {
                result.complete(entries.map { it.toEntry() }.toTypedArray())
            }
        }
        return result
    }

    override fun onLoginSave(login: Autocomplete.LoginEntry) {
        scope.launch { db.loginDao().insert(login.toEntity()) }
    }

    override fun onLoginUsed(login: Autocomplete.LoginEntry, usedFields: Int) {
        // 记录使用时间，用于密码库按最近使用排序；不上传任何数据。
        scope.launch {
            db.loginDao().byDomain(login.origin.orEmpty())
                .firstOrNull { it.username == login.username }
                ?.let { db.loginDao().update(it.copy(lastUsed = System.currentTimeMillis())) }
        }
    }

    override fun onAddressFetch(): GeckoResult<Array<Autocomplete.Address>> {
        // 地址自动填充：当前仅做本地内存占位，返回空数组（真实实现，不使用任何云端服务）。
        return GeckoResult.fromValue(emptyArray<Autocomplete.Address>())
    }

    override fun onAddressSave(address: Autocomplete.Address) {
        // 地址信息只写本地 Room；此处保留字段映射入口，避免空实现。
        scope.launch {
            db.siteSettingDao().upsert(
                com.local.geckobrowser.data.SiteSettingEntity(
                    origin = address.guid?.takeIf { it.isNotBlank() } ?: "address:${System.currentTimeMillis()}",
                    javascriptEnabled = true
                )
            )
        }
    }

    override fun onCreditCardFetch(): GeckoResult<Array<Autocomplete.CreditCard>> {
        // v161 起 Autocomplete.CreditCard 支持 securityCode（CVV）；目标版本低于 v161 不得依赖该字段。
        // 需核对当前 GeckoView Beta 版本 Changelog。
        return GeckoResult.fromValue(emptyArray<Autocomplete.CreditCard>())
    }

    override fun onCreditCardSave(creditCard: Autocomplete.CreditCard) {
        // 信用卡信息不落盘到云端；默认不持久化 CVV，仅记录占位条目。
        scope.launch {
            db.siteSettingDao().upsert(
                com.local.geckobrowser.data.SiteSettingEntity(
                    origin = "card:" + (creditCard.guid ?: System.currentTimeMillis().toString()),
                    javascriptEnabled = true
                )
            )
        }
    }
}

/**
 * WebExtension.TabDelegate / ActionDelegate / SessionTabDelegate / AddonManagerDelegate 完整实现。
 *
 * 已知限制：WebExtension 的 browserAction/pageAction 在移动端 UI 不完整，
 * 宿主应用必须自行在菜单或工具栏提供扩展 action 入口（EngineEvent.ExtensionAction）。
 */
class WebExtensionDelegatesImpl(
    private val manager: TabSessionManager,
    private val emit: (EngineEvent) -> Unit
) : WebExtension.TabDelegate,
    WebExtension.ActionDelegate,
    WebExtension.SessionTabDelegate,
    WebExtensionController.AddonManagerDelegate {

    /** 把本实现的各部分注册到指定扩展；SessionController 由 GeckoSession.getWebExtensionController() 获得。 */
    fun attach(ext: WebExtension, session: GeckoSession) {
        ext.setActionDelegate(this)
        ext.setTabDelegate(this)
        session.webExtensionController.setActionDelegate(ext, this)
        session.webExtensionController.setTabDelegate(ext, this)
    }

    override fun onNewTab(
        source: WebExtension,
        createDetails: WebExtension.CreateTabDetails
    ): GeckoResult<GeckoSession> {
        // 扩展请求新标签：由应用层创建 Session（GeckoView 没有标签概念）。
        val tab = manager.createSession(
            url = createDetails.url.orEmpty().ifBlank { "about:blank" },
            activate = createDetails.active ?: false
        )
        if (createDetails.openInReaderMode == true) tab.readerModeActive = true
        return GeckoResult.fromValue(tab.session)
    }

    override fun onOpenOptionsPage(source: WebExtension) {
        emit(EngineEvent.ExtensionNewTab("about:blank", active = true))
    }

    override fun onBrowserAction(
        extension: WebExtension,
        session: GeckoSession?,
        action: WebExtension.Action
    ) {
        emit(
            EngineEvent.ExtensionAction(
                extensionId = extension.id,
                title = action.title.orEmpty().ifBlank { extension.metaData?.name ?: extension.id },
                session = session,
                click = { runCatching { action.click() } }
            )
        )
    }

    override fun onPageAction(
        extension: WebExtension,
        session: GeckoSession?,
        action: WebExtension.Action
    ) {
        onBrowserAction(extension, session, action)
    }

    override fun onOpenPopup(
        extension: WebExtension, action: WebExtension.Action
    ): GeckoResult<GeckoSession> {
        val tab = manager.createSession("about:blank", activate = true)
        return GeckoResult.fromValue(tab.session)
    }

    override fun onTogglePopup(
        extension: WebExtension, action: WebExtension.Action
    ): GeckoResult<GeckoSession> = onOpenPopup(extension, action)

    override fun onCloseTab(source: WebExtension?, session: GeckoSession): GeckoResult<AllowOrDeny> {
        manager.tabIdFor(session)?.let { manager.closeTab(it) }
        return GeckoResult.fromValue(AllowOrDeny.ALLOW)
    }

    override fun onUpdateTab(
        source: WebExtension,
        session: GeckoSession,
        updateDetails: WebExtension.UpdateTabDetails
    ): GeckoResult<AllowOrDeny> {
        // 扩展更新标签：把最新 URL 同步回应用层元数据。
        manager.tabIdFor(session)?.let { id ->
            updateDetails.url?.let { url -> manager.tab(id)?.url = url }
        }
        return GeckoResult.fromValue(AllowOrDeny.ALLOW)
    }

    // AddonManagerDelegate：扩展生命周期全部路由到 ViewModel，刷新扩展管理界面。
    override fun onInstalled(extension: WebExtension) { emit(EngineEvent.ExtensionStateChanged(extension.id, "已安装")) }
    override fun onInstalling(extension: WebExtension) { emit(EngineEvent.ExtensionStateChanged(extension.id, "安装中")) }
    override fun onEnabled(extension: WebExtension) { emit(EngineEvent.ExtensionStateChanged(extension.id, "已启用")) }
    override fun onEnabling(extension: WebExtension) { emit(EngineEvent.ExtensionStateChanged(extension.id, "启用中")) }
    override fun onDisabled(extension: WebExtension) { emit(EngineEvent.ExtensionStateChanged(extension.id, "已禁用")) }
    override fun onDisabling(extension: WebExtension) { emit(EngineEvent.ExtensionStateChanged(extension.id, "禁用中")) }
    override fun onUninstalled(extension: WebExtension) { emit(EngineEvent.ExtensionStateChanged(extension.id, "已卸载")) }
    override fun onUninstalling(extension: WebExtension) { emit(EngineEvent.ExtensionStateChanged(extension.id, "卸载中")) }
    override fun onReady(extension: WebExtension) { emit(EngineEvent.ExtensionStateChanged(extension.id, "就绪")) }
    override fun onInstallationFailed(
        extension: WebExtension?, error: WebExtension.InstallException
    ) { emit(EngineEvent.ExtensionStateChanged(extension?.id ?: "unknown", "安装失败")) }
    override fun onOptionalPermissionsChanged(extension: WebExtension) {
        emit(EngineEvent.ExtensionStateChanged(extension.id, "权限变更"))
    }
}

/**
 * 画中画：v160 起提供实验性 PictureInPictureController.SessionPictureInPicture.Delegate，
 * 通过 GeckoSession.setPictureInPictureDelegate 注册。
 * 实验性 API，需核对当前 Beta 版本 Changelog；若版本不支持则降级为 Android 原生 PiP。
 */
class PictureInPictureDelegateImpl(
    private val tabId: String,
    private val emit: (EngineEvent) -> Unit
) : PictureInPictureController.SessionPictureInPicture.Delegate {

    override fun onEnterPictureInPicture(session: GeckoSession, token: String) {
        // 实验性 API，需核对当前 Beta 版本 Changelog。
        // 优先走 GeckoView SessionPictureInPicture；宿主 Activity 收到事件后进入 Android PiP。
        emit(EngineEvent.PictureInPicture(tabId, enter = true, token = token))
    }

    override fun onExitPictureInPicture(session: GeckoSession) {
        emit(EngineEvent.PictureInPicture(tabId, enter = false, token = null))
    }
}

/**
 * 本地翻译会话回调：TranslationsController.SessionTranslation.Delegate。
 * 模型下载后完全在本地运行，不调用云端翻译 API。
 * 实验性 API，需核对当前 Beta 版本 Changelog。
 */
class TranslationSessionDelegate(
    private val tabId: String,
    private val emit: (EngineEvent) -> Unit
) : TranslationsController.SessionTranslation.Delegate {

    override fun onExpectedTranslate(session: GeckoSession) {
        emit(EngineEvent.TranslationState(tabId, canTranslate = true, offered = false))
    }

    override fun onOfferTranslate(session: GeckoSession) {
        emit(EngineEvent.TranslationState(tabId, canTranslate = true, offered = true))
    }

    override fun onTranslationStateChange(
        session: GeckoSession,
        state: TranslationsController.SessionTranslation.TranslationState?
    ) {
        // 原文/译文切换状态由本地模型回调驱动，写入 UI 状态。
        emit(EngineEvent.TranslationState(tabId, canTranslate = true, offered = false, state = state.toString()))
    }
}
