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

## 固定下载链接与固定签名

每次推送都会构建并更新 Release，因此**下面这两个链接永远不变**，不用再换运行号：

```
https://github.com/ike-lee-820/geckoview-browser/releases/latest/download/geckoview-browser-latest.apk
https://v6.gh-proxy.com/https://github.com/ike-lee-820/geckoview-browser/releases/latest/download/geckoview-browser-latest.apk
```

- CI 每次同时发布两份内容完全相同的产物：`geckoview-browser-<内核版本>-<运行号>.apk`（归档、供更新器比较构建号）与 `geckoview-browser-latest.apk`（固定名、每次覆盖）。
- `/releases/latest/download/` 由 GitHub 自动指向最新 Release，tag 变化时链接也不变；代理前缀失败时应用内更新自动回退直连。
- **固定签名**：所有 release APK 使用同一证书（SHA-256 `1D:39:FF:CB:0F:CF:84:9E:5B:FF:C1:15:65:18:CD:34:13:E0:4B:59:11:D3:0F:26:57:EF:26:86:3C:5E:6C:0B`），保证应用内更新可以直接覆盖安装。此前 release 复用 AGP 的 `debug` 签名，而 CI 每次全新 runner 会重新随机生成 `debug.keystore`，导致每次证书不同、更新必失败。
- 密钥文件 `app/release.keystore` 随仓库提供；生产环境建议改用 GitHub Secrets：`RELEASE_KEYSTORE`（文件路径）、`RELEASE_KEYSTORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`，`app/build.gradle` 会优先读取环境变量。注意仓库内的私钥是公开的，不要用于需要防篡改的分发场景。
- **一次性迁移**：从旧版（debug 签名）升级到本版时签名已改变，Android 不允许覆盖安装，需先卸载旧版再安装一次；此后每次更新都能直接覆盖，本地数据也会因卸载而清空。

## 一次性迁移与备份（重要）

CI 诊断已确认：早期版本复用 AGP 自动生成的 debug 签名，而 GitHub runner **没有预置** `~/.android/debug.keystore`，每次构建都会随机生成新证书；旧版 APK 的证书 DN 是 `C=US, O=Android, CN=Android Debug`。私钥随 runner 销毁、无法复现，因此**从旧版升级到固定签名版必须卸载重装一次**，这是 Android 的签名校验限制，应用内无法绕过。

- 卸载前：打开旧版没有备份入口（备份功能是新版才加入的）。若旧版数据非常重要，请先记录书签/密码；否则直接从下面的固定链接安装新版即可。
- 安装后：以后每次更新都能直接覆盖安装，不会再丢数据。
- **设置 → 备份与恢复**：导出为本地 JSON（SAF 选择保存位置），覆盖 Room 全部表与全部偏好；已保存的密码用导出时设置的口令（PBKDF2 + AES/GCM）重新加密，导入时用同一口令解密并写入新设备的 Android Keystore。不提供任何云端导出/同步。
- 应用内更新在点击安装前会比对 APK 与当前应用的签名证书；不一致时不再弹出含糊的「应用未安装」，而是明确提示先备份、卸载、重装、恢复。

### 进阶：换签名时保留旧版全部数据（需电脑 + USB 调试）

普通「设置 → 卸载」会清空 `/data/data`。如果旧版数据非常重要，可用 adb 卸载 APK 但保留数据目录，再安装新签名包——此时系统里没有「已安装的旧包」可供比对签名，数据目录会被新包直接复用，连 Android Keystore 里的密码密钥也保留：

```bash
adb shell pm uninstall -k com.local.geckobrowser   # 只卸载 APK，保留数据
adb install -r geckoview-browser-latest.apk        # 安装固定签名版，沿用原数据
```

若 `pm uninstall -k` 在当前系统被拒绝，只能用 root / 自定义 recovery 直接备份并回写 `/data/data/com.local.geckobrowser`，或依赖 ROM 自带的整机应用数据备份。没有电脑或 root 时，这一次的数据清空无法避免。

装好固定签名版之后，请立刻到「设置 → 备份与恢复」导出一份备份，后续任何更新都不会再丢数据。

## vivo / OriginOS 一键清理导致应用被强杀

若 `Download/bcrash` 里出现 `info-*-force_stopped_by_system.txt`，
`ApplicationExitInfo` 会写 `reason=USER_REQUESTED(10)` 与
`description=[FORCE STOP] stop com.local.geckobrowser due to single-cleaner`。
这是系统内存清理器主动终止应用，**不是应用崩溃**，代码侧无法修复。
请在系统「设置 → 电池 / 后台管理 / 一键清理白名单」里把本应用设为允许后台运行。
日志模块对这类终止做了去重与 30 秒抑制，不会刷屏，也不会误写成 crash。
