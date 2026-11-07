# DOCS_READ.md — 强制文档阅读记录

> 记录时间：2026-11-07（Asia/Shanghai）
> 目标内核：GeckoView **Beta 渠道**，`geckoview-beta-arm64-v8a`，版本由 CI 动态解析 `maven-metadata.xml` 的 `<latest>` 注入 `GECKOVIEW_VERSION`。
> 本文件在任何代码生成之前完成，属于第零节硬性前置条件的交付物。

## 一、已阅读文档 URL 清单

| # | 文档 | URL | 阅读状态 |
|---|---|---|---|
| 1 | 主文档：JavaDoc 包摘要页 | https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/package-summary.html | 已完整阅读，并取回官方 HTML 源码逐项核对 Class Description 表（374 个条目） |
| 2 | JavaDoc 全类索引 | https://mozilla.github.io/geckoview/javadoc/mozilla-central/allclasses-index.html | 已阅读 |
| 3 | JavaDoc 方法搜索索引 | https://mozilla.github.io/geckoview/javadoc/mozilla-central/member-search-index.js | 已解析，共 2306 条成员签名，用于逐个校验代码中的 API 调用 |
| 4 | API Changelog（mozilla-central） | https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/doc-files/CHANGELOG.md | 已阅读，重点核对 v161 / v160 / v157 / v156 |
| 5 | 架构概述 | https://mozilla.github.io/geckoview/ 与 https://firefox-source-docs.mozilla.org/mobile/android/geckoview/index.html | 已阅读 |
| 6 | Consumer 文档（quick start / permissions / web-extensions） | https://mozilla.github.io/geckoview/consumer/docs/ | 已阅读 |
| 7 | Beta 版本元数据 | https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview-beta-arm64-v8a/maven-metadata.xml | 由 GitHub Actions 在构建前读取 `<latest>`（本地沙盒对该域名返回 403，故由 CI 侧解析并注入） |
| 8 | Maven 仓库根 | https://maven.mozilla.org/maven2/ | 已按规格写入 repositories |

### 0.1 主文档要点（原文归纳）

- `GeckoRuntime` 是启动和初始化 Gecko 的入口，可用于预加载 Gecko 或配置崩溃上报等功能；**每个进程只初始化一次**，本项目在 `GeckoBrowserApp` 中以进程级单例持有。
- `GeckoSession` 是加载页面等大部分核心工作发生的地方，它依赖 `GeckoRuntime` 与 Gecko 通信。
- `GeckoView` 是可嵌入的 Android View，是将 `GeckoSession` 显示到屏幕上的最常见方式；本项目通过 `AndroidView` 嵌入 Compose。
- 包摘要页明确：本包包含库的公开接口；库不包含 UI、不存储大量数据，行为通过 Delegate 委托给嵌入器。

### 0.2 架构概述要点

- GeckoView 是「有主见的库」：包含最小 UI，不对应用类型做假设，不假设嵌入器一定是浏览器；**GeckoView 中没有「标签」的概念**。
- 职责边界：`GeckoRuntime`（进程级引擎与全局设置）→ `GeckoSession`（一次网页内容的会话与全部 Delegate 回调）→ `GeckoView`（仅负责把某个 Session 渲染到屏幕）。
- Delegate 模式是核心交互方式：GeckoView 自身无 UI 元素、不持久化历史/标签/密码，所有导航、权限、提示、历史、下载、媒体事件都必须由嵌入器实现并路由到 ViewModel，形成 UDF 闭环。
- 多标签、标签分组、固定标签、最近关闭、崩溃恢复等均为**应用层概念**，由 `TabSessionManager` 维护 `GeckoSession` 与元数据映射。

### 0.3 API Changelog 核对结果（原文摘录）

- **v161**：新增 `Autocomplete.CreditCard.securityCode` 与 `Autocomplete.CreditCard.Builder.securityCode(String)`，使信用卡安全码（CVV）可在 `Autocomplete.StorageDelegate` 间传递；另新增 `GeckoRuntimeSettings.Builder.proguardUuid(String)`。
- **v160**：传给 `GeckoSession.ContentDelegate.onExternalResponse(GeckoSession, WebResponse)` 的 `WebResponse` 的 `content-disposition` 头，**现在包含经桌面端同等规则验证的文件名**，而非服务器原始发送值；新增 `GeckoSessionSettings.Builder.pictureInPictureToken(String)`；新增实验性 `PictureInPictureController.SessionPictureInPicture.Delegate`（通过 `GeckoSession.setPictureInPictureDelegate` 注册、`onEnterPictureInPicture` / `onExitPictureInPicture` 回调、`getSessionPictureInPicture().request()`）；`GeckoView` 实现 `ScrollCaptureCallback`，系统原生长截图自动生效。
- **v157**：新增 `GeckoView.captureFullPage()` 与 `GeckoDisplay.captureFullPage()`，返回 `GeckoResult<Bitmap>`。
- **v156**：`GeckoSession.saveAsPdf()` 会识别当前页面是否为 PDF JS 页面，是则使用 PDF JS 机制保存，否则通过 Gecko 打印框架生成 PDF；`PageExtractionController.ContentParams` 增加 `useSimpleText`，`PageMetadata` 增加 `isGated`（构造器变更）。
- **v121 起**：`ContentBlocking.Settings.Builder.queryParameterStrippingEnabled(boolean)` / `queryParameterStrippingPrivateBrowsingEnabled(boolean)` 可用。
- **v75 起**：`WebExtensionController.setAllowedInPrivateBrowsing(WebExtension, boolean)` 可用。
- `cookieBannerGlobalRulesEnabled` 在 Changelog 中标记为「已移除」的一组 API（`setCookieBannerGlobalRulesEnabled` 等），本项目**不调用**。

## 二、关键类清单（包摘要 Class Description 全表已逐项浏览）

完整 374 个条目见本文件末尾「附录 A」。工程实际直接使用的关键类：

- 核心三件套：`GeckoRuntime`、`GeckoRuntimeSettings.Builder`、`GeckoSession`、`GeckoSessionSettings.Builder`、`GeckoView`、`GeckoResult`
- 会话配置：`GeckoSession.SessionState`、`GeckoSession.Permission`、`AllowOrDeny`、`SessionFinder`、`GeckoDisplay`
- 内容拦截：`ContentBlocking`、`ContentBlocking.AntiTracking`、`ContentBlocking.CookieBehavior`、`ContentBlocking.Settings`、`ContentBlocking.Settings.Builder`、`ContentBlocking.SafeBrowsing`
- 自动填充：`Autocomplete`、`Autocomplete.LoginEntry(.Builder)`、`Autocomplete.CreditCard(.Builder)`、`Autocomplete.Address`、`Autocomplete.StorageDelegate`、`Autofill`
- 翻译：`TranslationsController`、`TranslationsController.RuntimeTranslation`、`TranslationsController.SessionTranslation(.Delegate)`、`TranslationsController.RuntimeTranslation.ModelManagementOptions(.Builder)`
- 页面提取 / PDF / 截图：`PageExtractionController`、`PdfViewerController`、`GeckoView.captureFullPage()`
- 媒体：`MediaSession`、`MediaSession.Delegate`、`MediaSession.Metadata`、`MediaSession.PositionState`
- 扩展：`WebExtension`、`WebExtensionController`、`WebExtension.Action`、`WebExtension.ActionDelegate`、`WebExtension.TabDelegate`、`WebExtension.SessionController`、`WebExtension.SessionTabDelegate`
- 其他：`WebRequest`、`WebRequest.Builder`、`WebResponse`、`WebRequestError`、`WebNotificationDelegate`、`PanZoomController`、`PictureInPictureController`、`ExperimentalGeckoViewApi`

## 三、Delegate 清单（全部实现类均已实现并路由到 BrowserViewModel）

| 实现类 | 接口 | 已实现回调（签名取自官方 JavaDoc） |
|---|---|---|
| `NavigationDelegateImpl` | `GeckoSession.NavigationDelegate` | `onLocationChange(GeckoSession, String, List<ContentPermission>, Boolean)`（第 4 参即 `hasUserGesture`）、`onCanGoBack`、`onCanGoForward`、`onLoadRequest`、`onNewSession`，另实现 `onLoadError`、`onSubframeLoadRequest` |
| `ContentDelegateImpl` | `GeckoSession.ContentDelegate` | `onTitleChange`、`onFullScreen`、`onExternalResponse`、`onCrash`、`onFirstContentfulPaint`、`onPaintStatusReset`、`onShowDynamicToolbar`，另实现 `onHideDynamicToolbar`、`onCloseRequest`、`onContextMenu`、`onKill`、`onFirstComposite`、`onFocusRequest` |
| `ProgressDelegateImpl` | `GeckoSession.ProgressDelegate` | `onPageStart`、`onPageStop`、`onProgressChange`，另实现 `onSecurityChange`（HTTPS 锁/证书信息来源）、`onSessionStateChange` |
| `PermissionDelegateImpl` | `GeckoSession.PermissionDelegate` | `onAndroidPermissionsRequest`、`onContentPermissionRequest`、`onMediaPermissionRequest`（摄像头/麦克风/地理位置/通知/持久存储/自动播放） |
| `PromptDelegateImpl` | `GeckoSession.PromptDelegate` | `onAlertPrompt`、`onButtonPrompt`（confirm）、`onTextPrompt`、`onAuthPrompt`、`onFilePrompt`、`onLoginSave`、`onLoginSelect`、`onBeforeUnloadPrompt`、`onChoicePrompt`、`onDateTimePrompt`、`onColorPrompt`、`onPopupPrompt`、`onRepostConfirmPrompt`、`onSharePrompt`、`onCreditCardSave/Select`、`onAddressSave/Select` |
| `HistoryDelegateImpl` | `GeckoSession.HistoryDelegate` | `onHistoryStateChange`、`onVisited`、`getVisited`、`hasVisitedHostSince` |
| `SelectionActionDelegateImpl` | `GeckoSession.SelectionActionDelegate` | `onShowActionRequest`、`onHideAction`、`onShowClipboardPermissionRequest`、`onDismissClipboardPermissionRequest` |
| `MediaSessionDelegateImpl` | `MediaSession.Delegate` | `onActivated`、`onDeactivated`、`onMetadata`、`onFeatures`、`onFullscreen`、`onPlay`、`onPause`、`onStop`、`onPositionState`、`onAudioSessionTypeChanged`（**全部带 `@UiThread`**） |
| `AutocompleteStorageDelegateImpl` | `Autocomplete.StorageDelegate` | `onLoginFetch()`、`onLoginFetch(String)`、`onLoginSave`、`onLoginUsed`、`onAddressFetch`、`onAddressSave`、`onCreditCardFetch`、`onCreditCardSave` |
| `WebExtensionDelegatesImpl` | `WebExtension.TabDelegate` / `WebExtension.ActionDelegate` / `WebExtension.SessionController` / `WebExtensionController.AddonManagerDelegate` | `onNewTab`、`onOpenOptionsPage`、`onBrowserAction`、`onPageAction`、`onOpenPopup`、`onTogglePopup`；`SessionController` 的 `setActionDelegate/setTabDelegate/setMessageDelegate/get*` 由管理器调用 |

> 注：`MediaSession.Delegate` 的 JavaDoc 中**不存在** `onPlaybackStateChange`；播放/暂停/停止状态由 `onPlay` / `onPause` / `onStop` / `onDeactivated` 上报，代码按真实签名实现并注释说明。

## 四、API 核对记录（重要偏差，代码已按现行 JavaDoc 调整）

| 规格中的名称 | 现行 mozilla-central JavaDoc 实际情况 | 本项目处理 |
|---|---|---|
| `TrackingProtectionPolicy` | 类中不存在；追踪保护由 `ContentBlocking.AntiTracking` / `ContentBlocking.Settings.Builder.antiTracking(int)` / `enhancedTrackingProtectionLevel(int)` 表达 | 用 `ContentBlocking` 实现 STANDARD / STRICT / CUSTOM 三档，注释标注偏差 |
| `GeckoSession.SecurityInformation` | 实为 `GeckoSession.ProgressDelegate.SecurityInformation`，且由 `ProgressDelegate.onSecurityChange` 回调传入 | 安全指示器走 `onSecurityChange` |
| `Finder` API | `GeckoSession.Finder` 不存在；实际是 `SessionFinder`，经 `GeckoSession.getFinder()` 获取，方法为 `find(String,int)`、`setDisplayFlags(int)`、`clear()` | 页内查找使用 `SessionFinder` |
| `saveAsPdf(PdfSettings)` | 现行签名 `saveAsPdf()` 无参，返回 `GeckoResult<InputStream>`；`PdfSettings` 类不存在 | 调用无参 `saveAsPdf()`，注释标注 v156 行为 |
| `GeckoRuntimeSettings.Builder.trackingProtectionCategories` / `cookieBehavior` | Builder 无同名方法；通过 `contentBlocking(ContentBlocking.Settings)` 注入 | 用 `contentBlocking(...)` 装配 |
| `GeckoSessionSettings.fontSizeFactor` / `enterFullScreenInLandscape` | 前者在 `GeckoRuntimeSettings.Builder.fontSizeFactor(float)`；后者在 SessionSettings.Builder 中不存在 | 全局字体走 Runtime，全屏走 `ContentDelegate.onFullScreen` |
| `ContentDelegate.onExternalResponse` 文件名 | v160 起为桌面同等规则校验后的文件名 | 下载模块不假设是服务器原始值 |
| `cookieBannerGlobalRulesEnabled` | 已被移除，Changelog 明确 | 不调用，仅留注释 |
| `WebExtension.TabDelegate` 与 `SessionTabDelegate` | `SessionController.setTabDelegate` 接收 `WebExtension.SessionTabDelegate` | 两个接口都实现并按签名注册 |

**实验性 API 标注（统一规则）**：`PictureInPictureController.SessionPictureInPicture.Delegate`、`GeckoSession.setPictureInPictureDelegate`、`getSessionPictureInPicture()`、`TranslationsController.RuntimeTranslation` 相关调用、`GeckoView.captureFullPage()` 等，在代码中一律单独标注「实验性 API，需核对当前 Beta 版本 Changelog」或「需核对当前 GeckoView Beta 版本 Changelog」。

## 五、自检结论

- 代码中每一个 `org.mozilla.geckoview` 调用均对照 `member-search-index.js` 的 2306 条签名做过存在性校验，校验脚本见 `scripts/verify_geckoview_api.py`。
- 所有 Delegate 均为完整 Kotlin 实现（无空方法、无伪代码），统一通过 `BrowserViewModel.onEvent(...)` 汇入单向数据流。
- 每个依赖具体 Beta 版本可能变化的 API 都在代码注释中统一标注「需核对当前 GeckoView Beta 版本 Changelog」。

## 附录 A：包摘要 Class Description 完整清单（374 项，逐项浏览）

```
AIFeaturesController
AIFeaturesController.AIFeature
AIFeaturesController.AIFeaturesException
AIFeaturesController.AIFeaturesException.Code
AIFeaturesController.RuntimeAIFeatures
AllowOrDeny
Autocomplete
Autocomplete.Address
Autocomplete.Address.Builder
Autocomplete.AddressSaveOption
Autocomplete.AddressSelectOption
Autocomplete.AddressSelectOption.AddressSelectHint
Autocomplete.AddressSelectOption.Hint
Autocomplete.AddressStructure
Autocomplete.AddressStructure.Field
Autocomplete.AddressStructure.Field.SelectField
Autocomplete.AddressStructure.Field.SelectField.Option
Autocomplete.AddressStructure.Field.TextField
Autocomplete.CreditCard
Autocomplete.CreditCard.Builder
Autocomplete.CreditCardSaveOption
Autocomplete.CreditCardSelectOption
Autocomplete.CreditCardSelectOption.CreditCardSelectHint
Autocomplete.CreditCardSelectOption.Hint
Autocomplete.LSUsedField
Autocomplete.LoginEntry
Autocomplete.LoginEntry.Builder
Autocomplete.LoginSaveOption
Autocomplete.LoginSelectOption
Autocomplete.Option
Autocomplete.SaveOption
Autocomplete.SaveOption.Hint
Autocomplete.SaveOption.SaveOptionHint
Autocomplete.SelectOption
Autocomplete.SelectOption.Hint
Autocomplete.SelectOption.SelectOptionHint
Autocomplete.StorageDelegate
Autocomplete.UsedField
Autofill
Autofill.AutofillHint
Autofill.AutofillInputType
Autofill.AutofillNotify
Autofill.Delegate
Autofill.Hint
Autofill.InputType
Autofill.Node
Autofill.NodeData
Autofill.Session
BasicSelectionActionDelegate
CompositorController
ContentBlocking
ContentBlocking.AntiTracking
ContentBlocking.BlockEvent
ContentBlocking.BounceTrackingProtectionMode
ContentBlocking.CBAntiTracking
ContentBlocking.CBCBounceTrackingProtectionMode
ContentBlocking.CBCookieBehavior
ContentBlocking.CBEtpCategory
ContentBlocking.CBEtpLevel
ContentBlocking.CBSafeBrowsing
ContentBlocking.CookieBehavior
ContentBlocking.Delegate
ContentBlocking.EtpCategory
ContentBlocking.EtpLevel
ContentBlocking.SafeBrowsing
ContentBlocking.SafeBrowsingProvider
ContentBlocking.SafeBrowsingProvider.Builder
ContentBlocking.Settings
ContentBlocking.Settings.Builder
ContentBlockingController
ContentBlockingController.Event
ContentBlockingController.LogEntry
ContentBlockingController.LogEntry.BlockingData
ContentBlockingController.LogEntry.BlockingData.LogEvent
ContentBlockingController.TrackingDbEvent
CrashHandler
CrashPullController
CrashPullController.Delegate
CrashReporter
DeprecationSchedule
ExperimentDelegate
ExperimentDelegate.ExperimentException
ExperimentDelegate.ExperimentException.Codes
ExperimentalGeckoViewApi
GeckoDisplay
GeckoDisplay.NewSurfaceProvider
GeckoDisplay.ScreenshotBuilder
GeckoDisplay.SurfaceInfo
GeckoDisplay.SurfaceInfo.Builder
GeckoPreferenceController
GeckoPreferenceController.GeckoPreference
GeckoPreferenceController.Observer
GeckoPreferenceController.Observer.Delegate
GeckoPreferenceController.PrefBranch
GeckoPreferenceController.PrefType
GeckoPreferenceController.SetGeckoPreference
GeckoResult
GeckoResult.CancellationDelegate
GeckoResult.Consumer
GeckoResult.OnExceptionListener
GeckoResult.OnExceptionMapper
GeckoResult.OnValueListener
GeckoResult.OnValueMapper
GeckoResult.UncaughtException
GeckoRuntime
GeckoRuntime.ActivityDelegate
GeckoRuntime.CrashedProcessVisibility
GeckoRuntime.Delegate
GeckoRuntime.ServiceWorkerDelegate
GeckoRuntimeSettings
GeckoRuntimeSettings.Builder
GeckoRuntimeSettings.ColorScheme
GeckoRuntimeSettings.FirefoxRelayMode
GeckoRuntimeSettings.HttpsOnlyMode
GeckoRuntimeSettings.IpProtectionAuthProvider
GeckoRuntimeSettings.TrustedRecursiveResolverMode
GeckoRuntimeSettings.WebContentIsolationStrategy
GeckoSession
GeckoSession.AppLinkLaunchType
GeckoSession.ClipboardPermissionType
GeckoSession.CompositorScrollDelegate
GeckoSession.ContentDelegate
GeckoSession.ContentDelegate.ContextElement
GeckoSession.ContentDelegate.ContextElement.Type
GeckoSession.FinderDisplayFlags
GeckoSession.FinderFindFlags
GeckoSession.FinderResult
GeckoSession.GeckoPrintException
GeckoSession.GeckoPrintException.Codes
GeckoSession.HeaderFilter
GeckoSession.HistoryDelegate
GeckoSession.HistoryDelegate.HistoryItem
GeckoSession.HistoryDelegate.HistoryList
GeckoSession.LoadFlags
GeckoSession.Loader
GeckoSession.MediaDelegate
GeckoSession.MediaDelegate.RecordingDevice
GeckoSession.MediaDelegate.RecordingDevice.DeviceType
GeckoSession.MediaDelegate.RecordingDevice.RecordingStatus
GeckoSession.MediaDelegate.RecordingDevice.Status
GeckoSession.MediaDelegate.RecordingDevice.Type
GeckoSession.NavigationDelegate
GeckoSession.NavigationDelegate.LoadRequest
GeckoSession.PdfSaveResult
GeckoSession.Permission
GeckoSession.PermissionDelegate
GeckoSession.PermissionDelegate.Callback
GeckoSession.PermissionDelegate.ContentPermission
GeckoSession.PermissionDelegate.ContentPermission.Value
GeckoSession.PermissionDelegate.MediaCallback
GeckoSession.PermissionDelegate.MediaSource
GeckoSession.PermissionDelegate.MediaSource.Source
GeckoSession.PermissionDelegate.MediaSource.Type
GeckoSession.PrintDelegate
GeckoSession.Priority
GeckoSession.ProgressDelegate
GeckoSession.ProgressDelegate.SecurityInformation
GeckoSession.ProgressDelegate.SecurityInformation.ContentType
GeckoSession.ProgressDelegate.SecurityInformation.SecurityMode
GeckoSession.PromptDelegate
GeckoSession.PromptDelegate.AlertPrompt
GeckoSession.PromptDelegate.AuthPrompt
GeckoSession.PromptDelegate.AuthPrompt.AuthOptions
GeckoSession.PromptDelegate.AuthPrompt.AuthOptions.AuthFlag
GeckoSession.PromptDelegate.AuthPrompt.AuthOptions.AuthLevel
GeckoSession.PromptDelegate.AuthPrompt.AuthOptions.Flags
GeckoSession.PromptDelegate.AuthPrompt.AuthOptions.Level
GeckoSession.PromptDelegate.AutocompleteRequest
GeckoSession.PromptDelegate.BasePrompt
GeckoSession.PromptDelegate.BasePrompt.Observer
GeckoSession.PromptDelegate.BeforeUnloadPrompt
GeckoSession.PromptDelegate.ButtonPrompt
GeckoSession.PromptDelegate.ButtonPrompt.ButtonType
GeckoSession.PromptDelegate.ButtonPrompt.Type
GeckoSession.PromptDelegate.CertificateRequest
GeckoSession.PromptDelegate.ChoicePrompt
GeckoSession.PromptDelegate.ChoicePrompt.Choice
GeckoSession.PromptDelegate.ChoicePrompt.ChoiceType
GeckoSession.PromptDelegate.ChoicePrompt.Type
GeckoSession.PromptDelegate.ColorPrompt
GeckoSession.PromptDelegate.DateTimePrompt
GeckoSession.PromptDelegate.DateTimePrompt.DatetimeType
GeckoSession.PromptDelegate.DateTimePrompt.Type
GeckoSession.PromptDelegate.FilePrompt
GeckoSession.PromptDelegate.FilePrompt.Capture
GeckoSession.PromptDelegate.FilePrompt.CaptureType
GeckoSession.PromptDelegate.FilePrompt.FileType
GeckoSession.PromptDelegate.FilePrompt.Type
GeckoSession.PromptDelegate.FolderUploadPrompt
GeckoSession.PromptDelegate.IdentityCredential
GeckoSession.PromptDelegate.IdentityCredential.AccountSelectorPrompt
GeckoSession.PromptDelegate.IdentityCredential.AccountSelectorPrompt.Account
GeckoSession.PromptDelegate.IdentityCredential.AccountSelectorPrompt.Provider
GeckoSession.PromptDelegate.IdentityCredential.AccountSelectorPrompt.ProviderAccounts
GeckoSession.PromptDelegate.IdentityCredential.PrivacyPolicyPrompt
GeckoSession.PromptDelegate.IdentityCredential.ProviderSelectorPrompt
GeckoSession.PromptDelegate.IdentityCredential.ProviderSelectorPrompt.Provider
GeckoSession.PromptDelegate.PopupPrompt
GeckoSession.PromptDelegate.PromptInstanceDelegate
GeckoSession.PromptDelegate.PromptResponse
GeckoSession.PromptDelegate.RedirectPrompt
GeckoSession.PromptDelegate.RepostConfirmPrompt
GeckoSession.PromptDelegate.SharePrompt
GeckoSession.PromptDelegate.SharePrompt.Result
GeckoSession.PromptDelegate.SharePrompt.ShareResult
GeckoSession.PromptDelegate.TextPrompt
GeckoSession.PromptDelegate.WebAuthnRelatedOriginPrompt
GeckoSession.RestartReason
GeckoSession.ScrollDelegate
GeckoSession.ScrollPositionUpdate.SourceType
GeckoSession.SelectionActionDelegate
GeckoSession.SelectionActionDelegate.ClipboardPermission
GeckoSession.SelectionActionDelegate.Selection
GeckoSession.SelectionActionDelegateAction
GeckoSession.SelectionActionDelegateFlag
GeckoSession.SelectionActionDelegateHideReason
GeckoSession.SessionState
GeckoSession.TargetWindow
GeckoSession.TextInputDelegate
GeckoSession.VisitFlags
GeckoSession.WebResponseInfo
GeckoSessionSettings
GeckoSessionSettings.Builder
GeckoSessionSettings.DisplayMode
GeckoSessionSettings.Key
GeckoSessionSettings.UserAgentMode
GeckoSessionSettings.ViewportMode
GeckoVRManager
GeckoView
GeckoView.ActivityContextDelegate
GeckoView.ViewBackend
GeckoViewPrintDocumentAdapter
GeckoWebExecutor
GeckoWebExecutor.FetchFlags
HandlerThread
IPProtectionController
IPProtectionController.AuthProvider
IPProtectionController.Country
IPProtectionController.Delegate
IPProtectionController.EnrollResult
IPProtectionController.GpiProvider
IPProtectionController.IPProxyException
IPProtectionController.IPProxyException.Code
IPProtectionController.ProxyState
IPProtectionController.ProxyState.Code
IPProtectionController.ServiceState
IPProtectionController.UsageInfo
Image
Image.ImageProcessingException
MediaSession
MediaSession.Delegate
MediaSession.ElementMetadata
MediaSession.Feature
MediaSession.MSFeature
MediaSession.Metadata
MediaSession.PositionState
MediaSession.SystemAudioFocusChange
OrientationController
OrientationController.OrientationDelegate
OverscrollEdgeEffect
PageExtractionController
PageExtractionController.ContentParams
PageExtractionController.PageExtractionException
PageExtractionController.PageMetadata
PageExtractionController.SessionPageExtractor
PanZoomController
PanZoomController.InputResult
PanZoomController.InputResultDetail
PanZoomController.OverscrollDirections
PanZoomController.ScrollBehaviorType
PanZoomController.ScrollableDirections
PdfViewerController
PdfViewerController.PdfViewerException
PdfViewerController.PdfViewerException.Code
PdfViewerController.SessionEditor
PictureInPictureController
PictureInPictureController.SessionPictureInPicture
PictureInPictureController.SessionPictureInPicture.Delegate
ProfilerController
RuntimeSettings
RuntimeSettings.Builder
ScreenLength
ScreenLength.ScreenLengthType
SessionAccessibility
SessionFinder
SessionPdfFileSaver
SessionTextInput
SlowScriptResponse
StorageController
StorageController.ClearFlags
StorageController.StorageControllerClearFlags
TranslationsController
TranslationsController.Language
TranslationsController.RuntimeTranslation
TranslationsController.RuntimeTranslation.LanguageModel
TranslationsController.RuntimeTranslation.LanguageSetting
TranslationsController.RuntimeTranslation.ModelManagementOptions
TranslationsController.RuntimeTranslation.ModelManagementOptions.Builder
TranslationsController.RuntimeTranslation.ModelOperation
TranslationsController.RuntimeTranslation.OperationLevel
TranslationsController.RuntimeTranslation.TranslationSupport
TranslationsController.SessionTranslation
TranslationsController.SessionTranslation.Delegate
TranslationsController.SessionTranslation.DetectedLanguages
TranslationsController.SessionTranslation.TranslationOptions
TranslationsController.SessionTranslation.TranslationOptions.Builder
TranslationsController.SessionTranslation.TranslationPair
TranslationsController.SessionTranslation.TranslationState
TranslationsController.TranslationsException
TranslationsController.TranslationsException.Code
WebExtension
WebExtension.Action
WebExtension.Action.ActionType
WebExtension.ActionDelegate
WebExtension.BlocklistState
WebExtension.BlocklistStateFlags
WebExtension.BrowsingDataDelegate
WebExtension.BrowsingDataDelegate.Settings
WebExtension.BrowsingDataDelegate.Type
WebExtension.BrowsingDataTypes
WebExtension.ContextFlags
WebExtension.CreateTabDetails
WebExtension.DisabledFlags
WebExtension.Download
WebExtension.Download.DownloadInterruptReason
WebExtension.Download.DownloadState
WebExtension.Download.Info
WebExtension.DownloadDelegate
WebExtension.DownloadInitData
WebExtension.DownloadRequest
WebExtension.DownloadRequest.ConflictActionFlags
WebExtension.EnabledFlags
WebExtension.Flags
WebExtension.InstallException
WebExtension.InstallException.Codes
WebExtension.InstallException.ErrorCodes
WebExtension.InvalidMetaDataException
WebExtension.MessageDelegate
WebExtension.MessageSender
WebExtension.MessageSender.EnvType
WebExtension.PermissionPromptResponse
WebExtension.Port
WebExtension.PortDelegate
WebExtension.SessionController
WebExtension.SessionTabDelegate
WebExtension.SignedState
WebExtension.SignedStateFlags
WebExtension.TabDelegate
WebExtension.UpdateTabDetails
WebExtension.WebExtensionFlags
WebExtensionController
WebExtensionController.AddonManagerDelegate
WebExtensionController.DebuggerDelegate
WebExtensionController.EnableSource
WebExtensionController.EnableSources
WebExtensionController.ExtensionProcessDelegate
WebExtensionController.InstallationMethod
WebExtensionController.PromptDelegate
WebMessage
WebMessage.Builder
WebNotification
WebNotificationAction
WebNotificationDelegate
WebPushController
WebPushDelegate
WebPushSubscription
WebRequest
WebRequest.Builder
WebRequest.CacheMode
WebRequestError
WebRequestError.Error
WebRequestError.ErrorCategory
WebResponse
WebResponse.Builder
```

## 附录 B：关键接口方法签名摘录（由 member-search-index.js 解析）

```
=== GeckoSession.NavigationDelegate (11) ===
LOAD_REQUEST_IS_REDIRECT
TARGET_WINDOW_CURRENT
TARGET_WINDOW_NEW
TARGET_WINDOW_NONE
onCanGoBack(GeckoSession, boolean)
onCanGoForward(GeckoSession, boolean)
onLoadError(GeckoSession, String, WebRequestError)
onLoadRequest(GeckoSession, GeckoSession.NavigationDelegate.LoadRequest)
onLocationChange(GeckoSession, String, List<GeckoSession.PermissionDelegate.ContentPermission>, Boolean)
onNewSession(GeckoSession, String)
onSubframeLoadRequest(GeckoSession, GeckoSession.NavigationDelegate.LoadRequest)
=== GeckoSession.ContentDelegate (18) ===
onCloseRequest(GeckoSession)
onContextMenu(GeckoSession, int, int, GeckoSession.ContentDelegate.ContextElement)
onCrash(GeckoSession)
onExternalResponse(GeckoSession, WebResponse)
onFirstComposite(GeckoSession)
onFirstContentfulPaint(GeckoSession)
onFocusRequest(GeckoSession)
onFullScreen(GeckoSession, boolean)
onHideDynamicToolbar(GeckoSession)
onKill(GeckoSession)
onMetaViewportFitChange(GeckoSession, String)
onPaintStatusReset(GeckoSession)
onPointerIconChange(GeckoSession, PointerIcon)
onPreviewImage(GeckoSession, String)
onShowDynamicToolbar(GeckoSession)
onSlowScript(GeckoSession, String)
onTitleChange(GeckoSession, String)
onWebAppManifest(GeckoSession, JSONObject)
=== GeckoSession.ProgressDelegate (5) ===
onPageStart(GeckoSession, String)
onPageStop(GeckoSession, boolean)
onProgressChange(GeckoSession, int)
onSecurityChange(GeckoSession, GeckoSession.ProgressDelegate.SecurityInformation)
onSessionStateChange(GeckoSession, GeckoSession.SessionState)
=== GeckoSession.PermissionDelegate (14) ===
PERMISSION_AUTOPLAY_AUDIBLE
PERMISSION_AUTOPLAY_INAUDIBLE
PERMISSION_DESKTOP_NOTIFICATION
PERMISSION_GEOLOCATION
PERMISSION_LOCAL_DEVICE_ACCESS
PERMISSION_LOCAL_NETWORK_ACCESS
PERMISSION_MEDIA_KEY_SYSTEM_ACCESS
PERMISSION_PERSISTENT_STORAGE
PERMISSION_STORAGE_ACCESS
PERMISSION_TRACKING
PERMISSION_XR
onAndroidPermissionsRequest(GeckoSession, String[], GeckoSession.PermissionDelegate.Callback)
onContentPermissionRequest(GeckoSession, GeckoSession.PermissionDelegate.ContentPermission)
onMediaPermissionRequest(GeckoSession, String, GeckoSession.PermissionDelegate.MediaSource[], GeckoSession.PermissionDelegate.MediaSource[], GeckoSession.PermissionDelegate.MediaCallback)
=== GeckoSession.PromptDelegate (25) ===
onAddressSave(GeckoSession, GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.AddressSaveOption>)
onAddressSelect(GeckoSession, GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.AddressSelectOption>)
onAlertPrompt(GeckoSession, GeckoSession.PromptDelegate.AlertPrompt)
onAuthPrompt(GeckoSession, GeckoSession.PromptDelegate.AuthPrompt)
onBeforeUnloadPrompt(GeckoSession, GeckoSession.PromptDelegate.BeforeUnloadPrompt)
onButtonPrompt(GeckoSession, GeckoSession.PromptDelegate.ButtonPrompt)
onChoicePrompt(GeckoSession, GeckoSession.PromptDelegate.ChoicePrompt)
onColorPrompt(GeckoSession, GeckoSession.PromptDelegate.ColorPrompt)
onCreditCardSave(GeckoSession, GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.CreditCardSaveOption>)
onCreditCardSelect(GeckoSession, GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.CreditCardSelectOption>)
onDateTimePrompt(GeckoSession, GeckoSession.PromptDelegate.DateTimePrompt)
onFilePrompt(GeckoSession, GeckoSession.PromptDelegate.FilePrompt)
onFolderUploadPrompt(GeckoSession, GeckoSession.PromptDelegate.FolderUploadPrompt)
onLoginSave(GeckoSession, GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.LoginSaveOption>)
onLoginSelect(GeckoSession, GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.LoginSelectOption>)
onPopupPrompt(GeckoSession, GeckoSession.PromptDelegate.PopupPrompt)
onRedirectPrompt(GeckoSession, GeckoSession.PromptDelegate.RedirectPrompt)
onRepostConfirmPrompt(GeckoSession, GeckoSession.PromptDelegate.RepostConfirmPrompt)
onRequestCertificate(GeckoSession, GeckoSession.PromptDelegate.CertificateRequest)
onSelectIdentityCredentialAccount(GeckoSession, GeckoSession.PromptDelegate.IdentityCredential.AccountSelectorPrompt)
onSelectIdentityCredentialProvider(GeckoSession, GeckoSession.PromptDelegate.IdentityCredential.ProviderSelectorPrompt)
onSharePrompt(GeckoSession, GeckoSession.PromptDelegate.SharePrompt)
onShowPrivacyPolicyIdentityCredential(GeckoSession, GeckoSession.PromptDelegate.IdentityCredential.PrivacyPolicyPrompt)
onTextPrompt(GeckoSession, GeckoSession.PromptDelegate.TextPrompt)
onWebAuthnRelatedOriginPrompt(GeckoSession, GeckoSession.PromptDelegate.WebAuthnRelatedOriginPrompt)
=== GeckoSession.HistoryDelegate (10) ===
VISIT_REDIRECT_PERMANENT
VISIT_REDIRECT_SOURCE
VISIT_REDIRECT_SOURCE_PERMANENT
VISIT_REDIRECT_TEMPORARY
VISIT_TOP_LEVEL
VISIT_UNRECOVERABLE_ERROR
getVisited(GeckoSession, String[])
hasVisitedHostSince(GeckoSession, String, long, long)
onHistoryStateChange(GeckoSession, GeckoSession.HistoryDelegate.HistoryList)
onVisited(GeckoSession, String, String, int)
=== GeckoSession.SelectionActionDelegate (22) ===
ACTION_COLLAPSE_TO_END
ACTION_COLLAPSE_TO_START
ACTION_COPY
ACTION_CUT
ACTION_DELETE
ACTION_HIDE
ACTION_PASTE
ACTION_PASTE_AS_PLAIN_TEXT
ACTION_SELECT_ALL
ACTION_UNSELECT
FLAG_IS_COLLAPSED
FLAG_IS_EDITABLE
FLAG_IS_PASSWORD
HIDE_REASON_ACTIVE_SCROLL
HIDE_REASON_ACTIVE_SELECTION
HIDE_REASON_INVISIBLE_SELECTION
HIDE_REASON_NO_SELECTION
PERMISSION_CLIPBOARD_READ
onDismissClipboardPermissionRequest(GeckoSession)
onHideAction(GeckoSession, int)
onShowActionRequest(GeckoSession, GeckoSession.SelectionActionDelegate.Selection)
onShowClipboardPermissionRequest(GeckoSession, GeckoSession.SelectionActionDelegate.ClipboardPermission)
=== MediaSession.Delegate (10) ===
onActivated(GeckoSession, MediaSession)
onAudioSessionTypeChanged(GeckoSession, MediaSession, String)
onDeactivated(GeckoSession, MediaSession)
onFeatures(GeckoSession, MediaSession, long)
onFullscreen(GeckoSession, MediaSession, boolean, MediaSession.ElementMetadata)
onMetadata(GeckoSession, MediaSession, MediaSession.Metadata)
onPause(GeckoSession, MediaSession)
onPlay(GeckoSession, MediaSession)
onPositionState(GeckoSession, MediaSession, MediaSession.PositionState)
onStop(GeckoSession, MediaSession)
=== Autocomplete.StorageDelegate (8) ===
onAddressFetch()
onAddressSave(Autocomplete.Address)
onCreditCardFetch()
onCreditCardSave(Autocomplete.CreditCard)
onLoginFetch()
onLoginFetch(String)
onLoginSave(Autocomplete.LoginEntry)
onLoginUsed(Autocomplete.LoginEntry, int)
=== WebExtension.TabDelegate (2) ===
onNewTab(WebExtension, WebExtension.CreateTabDetails)
onOpenOptionsPage(WebExtension)
=== WebExtension.ActionDelegate (4) ===
onBrowserAction(WebExtension, GeckoSession, WebExtension.Action)
onOpenPopup(WebExtension, WebExtension.Action)
onPageAction(WebExtension, GeckoSession, WebExtension.Action)
onTogglePopup(WebExtension, WebExtension.Action)
=== WebExtension.SessionController (6) ===
getActionDelegate(WebExtension)
getMessageDelegate(WebExtension, String)
getTabDelegate(WebExtension)
setActionDelegate(WebExtension, WebExtension.ActionDelegate)
setMessageDelegate(WebExtension, WebExtension.MessageDelegate, String)
setTabDelegate(WebExtension, WebExtension.SessionTabDelegate)
=== GeckoRuntimeSettings.Builder (53) ===
Builder()
aboutConfigEnabled(boolean)
allowInsecureConnections(int)
appZygoteProcessEnabled(boolean)
arguments(String[])
automaticFontSizeAdjustment(boolean)
configFilePath(String)
consoleOutput(boolean)
contentBlocking(ContentBlocking.Settings)
crashHandler(Class<? extends Service>)
crashPullNeverShowAgain(boolean)
debugLogging(boolean)
defaultRecursiveResolverUri(String)
displayDensityOverride(float)
displayDpiOverride(int)
doubleTapZoomingEnabled(boolean)
enterpriseRootsEnabled(boolean)
experimentDelegate(ExperimentDelegate)
extensionsProcessCrashThreshold(Integer)
extensionsProcessCrashTimeframe(Long)
extensionsProcessEnabled(boolean)
extensionsWebAPIEnabled(boolean)
extras(Bundle)
fissionEnabled(boolean)
fontInflation(boolean)
fontSizeFactor(float)
forceUserScalableEnabled(boolean)
glMsaaLevel(int)
globalPrivacyControlEnabled(boolean)
inputAutoZoomEnabled(boolean)
isolatedProcessEnabled(boolean)
javaScriptEnabled(boolean)
largeKeepaliveFactor(int)
locales(String[])
loginAutofillEnabled(boolean)
lowMemoryDetection(boolean)
newSettings(GeckoRuntimeSettings)
pauseForDebugger(boolean)
preferredColorScheme(int)
proguardUuid(String)
remoteDebuggingEnabled(boolean)
screenSizeOverride(int, int)
setLnaBlockTrackers(Boolean)
setLnaBlocking(Boolean)
setLnaEnabled(Boolean)
setSameDocumentNavigationOverridesLoadType(boolean)
setSameDocumentNavigationOverridesLoadTypeForceDisable(String)
translationsOfferPopup(boolean)
trustedRecursiveResolverMode(int)
trustedRecursiveResolverUri(String)
useMaxScreenDepth(boolean)
webFontsEnabled(boolean)
webManifest(boolean)
=== GeckoSessionSettings.Builder (16) ===
Builder()
Builder(GeckoSessionSettings)
allowJavascript(boolean)
build()
chromeUri(String)
contextId(String)
displayMode(int)
fullAccessibilityTree(boolean)
pictureInPictureToken(String)
screenId(int)
suspendMediaWhenInactive(boolean)
usePrivateMode(boolean)
useTrackingProtection(boolean)
userAgentMode(int)
userAgentOverride(String)
viewportMode(int)
=== GeckoSession (118) ===
APP_LINK_LAUNCH_TYPE_COLD
APP_LINK_LAUNCH_TYPE_HOT
APP_LINK_LAUNCH_TYPE_UNKNOWN
APP_LINK_LAUNCH_TYPE_WARM
FINDER_DISPLAY_DIM_PAGE
FINDER_DISPLAY_DRAW_LINK_OUTLINE
FINDER_DISPLAY_HIGHLIGHT_ALL
FINDER_FIND_BACKWARDS
FINDER_FIND_FORWARD
FINDER_FIND_LINKS_ONLY
FINDER_FIND_MATCH_CASE
FINDER_FIND_WHOLE_WORD
GeckoSession()
GeckoSession(GeckoSessionSettings)
HEADER_FILTER_CORS_SAFELISTED
HEADER_FILTER_UNRESTRICTED_UNSAFE
LOAD_FLAGS_ALLOW_POPUPS
LOAD_FLAGS_BYPASS_CACHE
LOAD_FLAGS_BYPASS_CLASSIFIER
LOAD_FLAGS_BYPASS_LOAD_URI_DELEGATE
LOAD_FLAGS_BYPASS_PROXY
LOAD_FLAGS_EXTERNAL
LOAD_FLAGS_FORCE_ALLOW_DATA_URI
LOAD_FLAGS_NONE
LOAD_FLAGS_REPLACE_HISTORY
PRIORITY_DEFAULT
PRIORITY_HIGH
acquireDisplay()
close()
containsFormData()
didPrintPageContent()
exitFullScreen()
flushSessionState()
getAccessibility()
getAutofillDelegate()
getAutofillSession()
getBrokenSiteReport()
getClientBounds(RectF)
getClientToScreenMatrix(Matrix)
getClientToSurfaceMatrix(Matrix)
getCompositorController()
getCompositorScrollDelegate()
getContentBlockingDelegate()
getContentDelegate()
getDefaultUserAgent()
getExperimentDelegate()
getFinder()
getHistoryDelegate()
getMediaDelegate()
getMediaSessionDelegate()
getNavigationDelegate()
getOverscrollEdgeEffect()
getPageToScreenMatrix(Matrix)
getPageToSurfaceMatrix(Matrix)
getPanZoomController()
getPdfFileSaver()
getPdfViewerEditor()
getPermissionDelegate()
getPictureInPictureDelegate()
getPrintDelegate()
getProgressDelegate()
getPromptDelegate()
getScrollDelegate()
getSelectionActionDelegate()
getSessionPageExtractor()
getSessionPictureInPicture()
getSessionTranslation()
getSettings()
getSurfaceBounds(Rect)
getTextInput()
getTranslationsSessionDelegate()
getUserAgent()
getWebCompatInfo()
getWebExtensionController()
goBack()
goBack(boolean)
goForward()
goForward(boolean)
gotoHistoryIndex(int)
isOpen()
isPdfJs()
load(GeckoSession.Loader)
loadUri(String)
mWindow
open(GeckoRuntime)
printPageContent()
processBackPressed()
purgeHistory()
qwacStatus()
releaseDisplay(GeckoDisplay)
reload()
reload(int)
restoreState(GeckoSession.SessionState)
saveAsPdf()
sendGleanBrokenSiteReport(JSONObject, String, String, String, Boolean, Boolean)
sendMoreWebCompatInfo(JSONObject)
setActive(boolean)
setAutofillDelegate(Autofill.Delegate)
setCompositorScrollDelegate(GeckoSession.CompositorScrollDelegate)
setContentBlockingDelegate(ContentBlocking.Delegate)
setContentDelegate(GeckoSession.ContentDelegate)
setExperimentDelegate(ExperimentDelegate)
setFocused(boolean)
setHistoryDelegate(GeckoSession.HistoryDelegate)
setMediaDelegate(GeckoSession.MediaDelegate)
setMediaSessionDelegate(MediaSession.Delegate)
setNavigationDelegate(GeckoSession.NavigationDelegate)
setPermissionDelegate(GeckoSession.PermissionDelegate)
setPictureInPictureDelegate(PictureInPictureContr
```

## 三、崩溃处理 API 补充核对（2026-11-07，修复日志不可靠问题时）

| # | 文档 | URL | 核对结论 |
|---|---|---|---|
| 9 | `GeckoRuntimeSettings.Builder` JavaDoc | https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoRuntimeSettings.Builder.html | 已核对 `crashHandler(Class<? extends Service> handler)` 签名：崩溃时以 `GeckoRuntime.ACTION_CRASHED` 启动指定 Service，extras 含 `EXTRA_MINIDUMP_PATH`。本项目**不调用**该方法——它会启动上报链路，与「无云端」约束冲突。 |
| 10 | `CrashHandler` JavaDoc | https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/CrashHandler.html | 已核对：`org.mozilla.geckoview.CrashHandler implements Thread.UncaughtExceptionHandler`；构造器 `CrashHandler(Context, Class<? extends Service>)` 会 "Create and register a CrashHandler for all threads"；`createDefaultCrashHandler(Context)`；`reportException(Thread, Throwable)` 会 "Report an exception to Socorro"；`uncaughtException(Thread, Throwable)`。该类未标注 `@ExperimentalGeckoViewApi`。 |

**由此确认的根因**：GeckoRuntime 初始化会注册自己的全局 `Thread.UncaughtExceptionHandler`，
旧实现把自定义 handler 装在 `GeckoEngine.runtime()` 之前，被直接覆盖，导致上一次崩溃没有留下任何日志。
本项目因此改为：Gecko 初始化之后安装自定义 handler + `Activity.onResume` 复检；不链式调用
GeckoView 的 `CrashHandler`（避免 Socorro 上报）；改用本地 `ContentDelegate.onCrash` / `onKill`、
ViewModel 事件、启动期兜底异常与进程存活标记四路采集，日志写入 `Download/bcrash`。
上述两个 API 均**只做签名核对、不在代码中调用**，符合「每个 GeckoView API 调用必须能在 JavaDoc 找到签名」的要求。

## 四、native 崩溃诊断补充核对（2026-11-07，分析 previous_session_died 日志后）

用户设备 vivo V2353A / Android 14 (sdk=34)，日志显示上次进程 `state=alive` 且 pid 在 1 秒内消失、
Java 层无任何异常记录，判定为 native 级或系统信号终止。为此核对以下文档：

| # | 文档 | URL | 核对结论 |
|---|---|---|---|
| 11 | `GeckoRuntime` JavaDoc | https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoRuntime.html | 已核对 `ACTION_CRASHED`、`EXTRA_MINIDUMP_PATH`、`EXTRA_EXTRAS_PATH`、`EXTRA_CRASH_PROCESS_VISIBILITY`、`EXTRA_CRASH_PROCESS_TYPE` 五个常量。崩溃 Intent 的 extras 仅此四项。 |
| 12 | `GeckoRuntimeSettings.Builder` JavaDoc（复核） | https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoRuntimeSettings.Builder.html | `crashHandler(Class<? extends Service>)` 明确要求「Service 必须运行在与 GeckoRuntime 不同的进程」且「会作为前台服务运行」。**本项目不启用**：独立进程会重复执行 Application.onCreate（进而重复初始化 GeckoRuntime），前台服务又受 vivo 后台策略与通知权限约束，反而引入新的启动期风险。 |
| 13 | `CrashHandler` JavaDoc（复核） | https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/CrashHandler.html | `reportException(Thread, Throwable)` 会 "Report an exception to Socorro"，`createDefaultCrashHandler(Context)` 注册默认处理。因此本项目继续用自定义 UEH 覆盖，不链式调用，保持无云端。 |
| 14 | Android `ApplicationExitInfo`（系统 API，非 GeckoView） | Android 11 / API 30+ `ActivityManager.getHistoricalProcessExitReasons` | 无需权限、完全本地，可读取 `REASON_CRASH_NATIVE` / `SIGNALED` / `LOW_MEMORY` / `ANR`、信号号、系统描述与 `getTraceInputStream()` tombstone 摘要。用于补齐「补记没有堆栈」的缺口。 |

**本项目实际调用的 GeckoView API 变化**：仅新增
`GeckoRuntimeSettings.Builder.crashPullNeverShowAgain(true)`（禁用 CrashPullController 请求拉取崩溃报告的通知，
不改变崩溃处理链路，避免任何云端交互）。已核对该签名存在于 mozilla-central JavaDoc；
需核对当前 GeckoView Beta 版本 Changelog。

`ACTION_CRASHED` / `EXTRA_*` / `crashHandler(...)` 均**只做签名核对、不在代码中调用**，符合第 0.3 节自检要求。

## 五、WebExtensionController 线程约束与系统 FORCE STOP 诊断核对（2026-11-07）

| # | 文档 | URL | 核对结论 |
|---|---|---|---|
| 15 | `WebExtensionController` JavaDoc | https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/WebExtensionController.html | 已逐项核对 `ensureBuiltIn(String uri, String id)`、`install(String uri, String installationMethod)`、`list()`、`enable(WebExtension, int)`、`disable(WebExtension, int)`、`uninstall(WebExtension)`、`setAllowedInPrivateBrowsing(WebExtension, boolean)` 签名，均返回 `GeckoResult`，且**全部标注 `@HandlerThread`** —— 必须在带 Handler 的线程调用。未标注 `@ExperimentalGeckoViewApi`。 |
| 16 | Android `ApplicationExitInfo`（复核，系统 API） | Android 11 / API 30+ | `REASON_USER_REQUESTED = 10` 表示用户/系统主动结束；vivo/OriginOS 的 `description=[FORCE STOP] stop <pkg> due to single-cleaner` 属于一键清理强杀，**不是应用崩溃**，且常不产生 native trace。 |

**修复依据**：用户 build 28 日志里唯一真实异常为
`java.lang.IllegalThreadStateException: Must be ran on a thread with a Handler!`
at `WebExtensionController.ensureBuiltIn`（堆栈经 `ThreadUtils.assertOnHandlerThread`），
根因是 `loadBuiltInExtensions()` / `installExtensionFromSaf()` 把调用放在 `Dispatchers.IO`。
已统一切到 `Dispatchers.Main.immediate`；文件复制等纯 IO 仍留在 IO。
其余 `list/enable/disable/uninstall/setAllowedInPrivateBrowsing` 原就在 `viewModelScope.launch`
（Main dispatcher），符合 `@HandlerThread`。

日志侧同步修正：按 `ApplicationExitInfo` 的 reason/description 把系统 FORCE STOP 归类为
`force_stopped_by_system`（写 `info-*.txt` 而非 `crash-*.txt`）、补记去重与 30 秒窗口抑制、
合并 `session.marker` 单文件、仅主进程写标记。以上均为 Android 系统 API 或本地逻辑，
未新增任何 GeckoView API 调用。

## 六、SIGKILL 分类与 GeckoRuntime 主进程隔离核对（2026-11-07，分析 build29 日志后）

| # | 文档 | URL | 核对结论 |
|---|---|---|---|
| 17 | Android `ApplicationExitInfo` 常量语义（系统 API，非 GeckoView） | Android 11 / API 30+ | `REASON_SIGNALED = 2` + `status = 9` 表示内核/SIGKILL 直接终止；与 native 崩溃信号 `SIGSEGV(11) / SIGABRT(6) / SIGBUS(7) / SIGFPE(8) / SIGILL(4)` 语义不同，系统不生成 tombstone、无堆栈。`REASON_EXIT_SELF = 1` 是主动退出。`REASON_LOW_MEMORY = 3` / `REASON_CRASH = 4` / `REASON_CRASH_NATIVE = 5` / `REASON_ANR = 6` 才属于真实异常退出。 |
| 18 | GeckoView JavaDoc 包摘要（复核） | https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/package-summary.html | `GeckoRuntime` 是启动和初始化 Gecko 的入口，**每个进程只能初始化一次**。GeckoView 会 fork 内容/GPU/socket 子进程，每个子进程都会实例化 `Application`；嵌入方必须按进程名隔离 `Application.onCreate` 的重初始化逻辑，子进程不应持有 `GeckoRuntime`。 |
| 19 | Android `Application.onTrimMemory` / `ComponentCallbacks2`（系统 API） | Android SDK | 内存紧张时系统的回调；`TRIM_MEMORY_RUNNING_LOW` 及以上应主动释放可重建资源，避免被 lmkd 直接 SIGKILL。本项目在该回调释放后台标签的 `GeckoSession`（已有 API `GeckoSession.close()`），元数据保留。 |

**本次代码变更未新增任何 GeckoView API 调用**，仅调整已有调用的进程归属与时机：
`GeckoRuntime.create()` 只在主进程执行；`GeckoSession.close()` 在 `onTrimMemory` 时由 `TabSessionManager.releaseInactiveSessions()` 调用（原已存在）。
其余为 Android 系统 API 与本地诊断逻辑，符合第 0.3 节「每个 GeckoView API 调用必须能在 JavaDoc 找到签名」的自检要求。

## 七、本次功能变更核对（2026-11-07）

用户要求：①修复标签页不加载网页 ②可设置主页与搜索引擎 ③删除追踪保护功能 ④底部工具栏随虚拟键盘抬升。

| 项 | 变更 | 涉及的 GeckoView API 核对 |
|---|---|---|
| ① | 地址栏补 `ImeAction.Go` / `KeyboardActions`；`load()` 在 `activeSession()` 为 null 时用 `ensureActiveSession()` 重建 Session；`releaseInactiveSessions` 在 activeTabId 未就绪时不释放；`submitInput` 异常兜底 | 仍只调用 `GeckoSession.loadUri(String)`、`GeckoSession.open(GeckoRuntime)`、`GeckoSession.close()`、`GeckoView.setSession(GeckoSession)` / `releaseSession()`，签名与 JavaDoc 一致，未新增 API |
| ② | DataStore 新增 `home_page`；Room 的 `search_engines` 表在 `RoomDatabase.Callback.onOpen` 幂等 seed 内置引擎；新增默认引擎设置 / 自定义引擎增删 | 全部为 AndroidX Room / DataStore / Compose，未调用 GeckoView API |
| ③ | 删除 `TrackingPolicy` 三档枚举与设置 UI、DataStore 偏好、`ContentBlocking.Settings.Builder.antiTracking(int)` 调用、`queryParameterStrippingEnabled` 置 false；`GeckoSessionSettings.Builder.useTrackingProtection(false)` | `useTrackingProtection(boolean)` 签名在既有 JavaDoc 核对记录中（见第 693 行）；`antiTracking(...)` 只是「不再调用」，无新增 API。保留 `cookieBehavior`、`safeBrowsing(NONE)` 等与追踪保护无关的本地隐私配置 |
| ④ | `Scaffold` 增加 `Modifier.imePadding()`，配合既有 `WindowCompat.setDecorFitsSystemWindows(window, false)` 与 `windowSoftInputMode=adjustResize`，底部 bottomBar 随 IME 抬升 | 纯 Compose WindowInsets，未调用 GeckoView API |

未新增任何 `@ExperimentalGeckoViewApi` 调用；所有 GeckoView 调用均可在既有 JavaDoc 核对清单中找到对应签名。
