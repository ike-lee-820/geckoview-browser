# GeckoView 本地浏览器

完全本地化、无云端的 Android 浏览器，对标 Chrome 的核心功能；引擎为 **GeckoView Beta（arm64-v8a）**，UI 为 **Jetpack Compose + Material 3**。

## 构建

- 版本不硬编码：`GECKOVIEW_VERSION` 由 CI 从 Mozilla Maven 的 `maven-metadata.xml` 解析 `<latest>` 注入；变量为空时 `app/build.gradle` 显式失败。
- 本地构建：

```bash
export GECKOVIEW_VERSION=160.0.20261017034010   # 换成 maven-metadata.xml 中的 <latest>
export GITHUB_RUN_NUMBER=1
./gradlew assembleDebug
```

- `versionName` 严格等于 `GECKOVIEW_VERSION`，`versionCode` 取 `GITHUB_RUN_NUMBER`（本地回退 1）。

## 架构

`GeckoRuntime`（进程级单例）→ `GeckoSession`（每标签一个，全部 Delegate 路由到 `BrowserViewModel`）→ 单个 `GeckoView`（通过 `AndroidView` 嵌入 Compose，切换标签用 `setSession`）。Compose UI → ViewModel → Session/Delegate → ViewModel → Compose UI 单向数据流。

GeckoView 没有「标签」概念：标签、分组、固定标签、最近关闭、恢复全部由 `TabSessionManager` 在应用层维护。

## 数据与隐私

- 书签、历史、下载、标签组、站点设置、语言包记录：Room；偏好：DataStore；密码：Android Keystore 加密。
- 无 Firefox Sync、无远程 Safe Browsing、无云端翻译/账户；Cookie/WebStorage 由 GeckoView 管理，无痕会话不落盘。
- 唯一的额外网络行为：**应用内更新检查**（查询 GitHub Release API 与下载 APK，默认走 `v6.gh-proxy.com` 加速）以及用户显式触发的本地翻译模型下载。二者都不上传任何本地浏览数据，均可在设置中关闭。

## 应用内更新

启动时（可关闭）与「设置 → 检查更新」查询 `https://api.github.com/repos/OWNER/REPO/releases/latest`，比较 `tag_name` 与 `BuildConfig.VERSION_NAME`；下载时把 `https://github.com/` 前缀替换为可配置的 `https://v6.gh-proxy.com/https://github.com/`，失败自动回退直连，随后通过 FileProvider 触发系统安装界面。

## 文档阅读记录

见 [DOCS_READ.md](DOCS_READ.md)：JavaDoc 包摘要 374 个类清单、Delegate 清单、API Changelog v160/v161 核对结果，以及规格与现行 API 的偏差说明。
