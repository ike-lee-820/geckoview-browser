package com.local.geckobrowser.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.local.geckobrowser.browser.BrowserViewModel
import com.local.geckobrowser.browser.ThemeMode
import com.local.geckobrowser.browser.ToolbarPosition
import com.local.geckobrowser.browser.TrackingPolicy
import com.local.geckobrowser.data.BackupManager
import com.local.geckobrowser.data.BrowserPrefs
import com.local.geckobrowser.update.UpdateState

/** 设置：主题、隐私、更新、翻译、扩展入口等，全部本地配置。 */
@Composable
fun SettingsScreen(vm: BrowserViewModel, nav: NavHostController) {
    val prefs by (vm.manager.context.applicationContext as com.local.geckobrowser.GeckoBrowserApp)
        .preferences.cached.collectAsState()
    val update by vm.update.collectAsState()
    var exceptionDialog by remember { mutableStateOf(false) }
    var exceptionHost by remember { mutableStateOf("") }

    // 本地备份与恢复：全部通过 SAF 读写用户选择的文件，无任何网络行为。
    val context = LocalContext.current
    val backupScope = rememberCoroutineScope()
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var exportDialog by remember { mutableStateOf(false) }
    var importPassDialog by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var exportPass by remember { mutableStateOf("") }
    var importPass by remember { mutableStateOf("") }

    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) backupScope.launch {
            backupMessage = runCatching {
                BackupManager.exportTo(
                    context, uri,
                    exportPass.takeIf { it.isNotEmpty() }?.toCharArray()
                )
            }.getOrElse { t -> "导出失败：${t.message}" }
            exportPass = ""
        }
    }
    val openBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        pendingImportUri = uri
        if (BackupManager.needsPassphrase(context, uri)) {
            importPassDialog = true
        } else backupScope.launch {
            backupMessage = runCatching { BackupManager.importFrom(context, uri, null) }
                .getOrElse { t -> "恢复失败：${t.message}" }
        }
    }

    Scaffold(topBar = {
        Text("设置", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { SectionTitle("外观") }
            item {
                SettingsRow("主题") {
                    Row {
                        ThemeMode.entries.forEach { mode ->
                            TextButton(onClick = { vm.setTheme(mode) }) {
                                Text(if (prefs.themeMode == mode) "● ${mode.label()}" else mode.label())
                            }
                        }
                    }
                }
            }
            item {
                SettingsRow("工具栏位置") {
                    Row {
                        ToolbarPosition.entries.forEach { p ->
                            TextButton(onClick = { vm.setToolbarPosition(p) }) {
                                Text(if (prefs.toolbarPosition == p) "● ${p.label()}" else p.label())
                            }
                        }
                    }
                }
            }

            item { SectionTitle("隐私与安全") }
            item {
                SettingsRow("追踪保护") {
                    Column {
                        TrackingPolicy.entries.forEach { p ->
                            TextButton(onClick = {
                                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                    (vm.manager.context.applicationContext as com.local.geckobrowser.GeckoBrowserApp)
                                        .preferences.setTrackingPolicy(p)
                                }
                            }) { Text(if (prefs.trackingPolicy == p) "● ${p.label()}" else p.label()) }
                        }
                        Text("STANDARD 为默认平衡档；STRICT 拦截更多追踪器但可能破坏更多站点；CUSTOM 可逐项选择。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                SettingsSwitch("HTTPS-only 模式", prefs.httpsOnly, onToggle = {
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        (vm.manager.context.applicationContext as com.local.geckobrowser.GeckoBrowserApp)
                            .preferences.setHttpsOnly(it)
                    }
                })
            }
            item {
                Button(onClick = { exceptionDialog = true }, modifier = Modifier.padding(12.dp)) {
                    Text("为站点添加 HTTPS-only 永久例外")
                }
            }
            item { SectionTitle("退出时自动清除（本地执行）") }
            item { SettingsSwitch("缓存", prefs.clearCacheOnExit, onToggle = { setClear(vm, cache = it) }) }
            item { SettingsSwitch("Cookie", prefs.clearCookiesOnExit, onToggle = { setClear(vm, cookies = it) }) }
            item { SettingsSwitch("历史记录", prefs.clearHistoryOnExit, onToggle = { setClear(vm, history = it) }) }
            item { SettingsSwitch("站点数据", prefs.clearSiteDataOnExit, onToggle = { setClear(vm, site = it) }) }

            item { SectionTitle("应用内更新") }
            item {
                SettingsSwitch("每次打开应用时检查更新", prefs.checkUpdateOnLaunch, onToggle = {
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        (vm.manager.context.applicationContext as com.local.geckobrowser.GeckoBrowserApp)
                            .preferences.setCheckUpdateOnLaunch(it)
                    }
                })
            }
            item {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Update, contentDescription = "更新")
                            Text("当前内核版本：${com.local.geckobrowser.BuildConfig.GECKOVIEW_VERSION}",
                                Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                        Text("更新仅查询公开 GitHub Release 并下载 APK，默认走 v6.gh-proxy.com 加速，失败回退直连；不上传任何本地浏览数据。",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                        when (val s = update) {
                            is UpdateState.Downloading -> {
                                LinearProgressIndicator(progress = { s.progress / 100f }, modifier = Modifier.fillMaxWidth())
                                Text("已下载 ${s.received / 1024} KB / ${s.total / 1024} KB")
                            }
                            is UpdateState.Available -> Text("发现新版本 ${s.info.version}")
                            is UpdateState.Ready -> Text("更新包已就绪：${s.version}")
                            is UpdateState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error)
                            is UpdateState.UpToDate -> Text("已是最新版本 ${s.current}")
                            is UpdateState.NeedsReinstall -> Text(
                                s.message, color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                            else -> Unit
                        }
                        Row {
                            Button(onClick = { vm.checkForUpdate(manual = true) }) { Text("检查更新") }
                            TextButton(onClick = { vm.downloadUpdate() }) { Text("下载并安装") }
                            if (update is UpdateState.Ready) {
                                TextButton(onClick = {
                                    vm.installUpdate((update as UpdateState.Ready).apkPath)
                                }) { Text("立即安装") }
                            }
                        }
                    }
                }
            }

            item { SectionTitle("备份与恢复") }
            item {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("导出到本机文件（书签、历史、标签组、站点设置、偏好全部本地保存，不上传）。"
                            + "已保存的密码需要设置一个口令才能随备份迁移；卸载重装后口令需保持一致。",
                            style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.padding(top = 8.dp)) {
                            Button(onClick = { exportDialog = true }) { Text("导出备份") }
                            TextButton(onClick = { openBackup.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                                Text("恢复备份")
                            }
                        }
                        backupMessage?.let { msg ->
                            Text(msg, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp))
                        }
                        Text("固定签名后每次更新都可直接覆盖安装；首次从旧版迁移需卸载重装一次，请先导出备份。",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }

            item { SectionTitle("本地翻译") }
            item {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("模型下载是唯一允许的用户显式触发的网络操作；模型下载后完全在本地运行，不调用云端翻译 API。",
                            style = MaterialTheme.typography.bodySmall)
                        val req = vm.translationManager.requirement
                        Text("最低设备要求：${req.minRamMb / 1024} GB 内存、${req.minFreeStorageMb} MB 可用存储。${req.note}",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                        val models by vm.translationManager.models().collectAsState(initial = emptyList())
                        models.forEach { m ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${m.languagePair} · ${m.sizeBytes / 1024 / 1024} MB", Modifier.weight(1f))
                                IconButton(onClick = { vm.translationManager.deleteModel(m.languagePair) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除语言包")
                                }
                            }
                        }
                        Button(onClick = {
                            vm.translationManager.checkPairSize("en", "zh") { size ->
                                vm.translationManager.downloadModel("en", "zh", size)
                            }
                        }) { Text("下载 英文 → 中文 语言包") }
                    }
                }
            }

            item { SectionTitle("浏览功能") }
            item { SettingsSwitch("自动进入画中画", prefs.autoPictureInPicture, onToggle = {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    (vm.manager.context.applicationContext as com.local.geckobrowser.GeckoBrowserApp)
                        .preferences.setAutoPictureInPicture(it)
                }
            }) }
            item { SettingsSwitch("允许后台音频播放", prefs.allowBackgroundAudio, onToggle = {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    (vm.manager.context.applicationContext as com.local.geckobrowser.GeckoBrowserApp)
                        .preferences.setAllowBackgroundAudio(it)
                }
            }) }
            item { SettingsSwitch("允许后台视频播放", prefs.allowBackgroundVideo, onToggle = {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    (vm.manager.context.applicationContext as com.local.geckobrowser.GeckoBrowserApp)
                        .preferences.setAllowBackgroundVideo(it)
                }
            }) }
            item { SettingsSwitch("显示书签栏", prefs.showBookmarksBar, onToggle = {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    (vm.manager.context.applicationContext as com.local.geckobrowser.GeckoBrowserApp)
                        .preferences.setShowBookmarksBar(it)
                }
            }) }
            item {
                TextButton(onClick = { nav.navigate(Routes.SITE_SETTINGS) }, modifier = Modifier.padding(12.dp)) {
                    Icon(Icons.Default.Security, contentDescription = "站点设置")
                    Text("站点级设置与权限例外")
                }
            }
            item {
                TextButton(onClick = { nav.navigate(Routes.EXTENSIONS) }, modifier = Modifier.padding(12.dp)) {
                    Icon(Icons.Default.Extension, contentDescription = "扩展")
                    Text("扩展管理（本地侧载）")
                }
            }
            item {
                Text("键盘快捷键：Ctrl+T 新建标签、Ctrl+W 关闭标签、Ctrl+L 聚焦地址栏、Ctrl+R 刷新、Ctrl+F 页内查找。",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
            }
        }
    }

    if (exportDialog) {
        AlertDialog(
            onDismissRequest = { exportDialog = false },
            title = { Text("导出本地备份") },
            text = {
                Column {
                    Text("设置一个口令，用于加密备份中的已保存密码；留空则跳过密码，只导出其余数据。",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = exportPass, onValueChange = { exportPass = it },
                        label = { Text("备份口令（可留空）") },
                        singleLine = true,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    exportDialog = false
                    createBackup.launch(BackupManager.defaultFileName())
                }) { Text("选择保存位置") }
            },
            dismissButton = { TextButton(onClick = { exportDialog = false }) { Text("取消") } }
        )
    }

    if (importPassDialog) {
        AlertDialog(
            onDismissRequest = { importPassDialog = false },
            title = { Text("备份包含已保存的密码") },
            text = {
                Column {
                    Text("输入导出时设置的口令以恢复密码；留空则跳过密码，其余数据照常恢复。",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = importPass, onValueChange = { importPass = it },
                        label = { Text("备份口令") },
                        singleLine = true,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    importPassDialog = false
                    val uri = pendingImportUri
                    if (uri != null) backupScope.launch {
                        backupMessage = runCatching {
                            BackupManager.importFrom(
                                context, uri,
                                importPass.takeIf { it.isNotEmpty() }?.toCharArray()
                            )
                        }.getOrElse { t -> "恢复失败：${t.message}" }
                        importPass = ""
                        pendingImportUri = null
                    }
                }) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { importPassDialog = false; importPass = "" }) { Text("取消") } }
        )
    }

    if (exceptionDialog) {
        AlertDialog(onDismissRequest = { exceptionDialog = false },
            title = { Text("添加 HTTPS-only 例外") },
            text = {
                OutlinedTextField(value = exceptionHost, onValueChange = { exceptionHost = it },
                    label = { Text("站点域名") })
            },
            confirmButton = {
                Button(onClick = {
                    if (exceptionHost.isNotBlank()) {
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            (vm.manager.context.applicationContext as com.local.geckobrowser.GeckoBrowserApp)
                                .preferences.setHttpsException(exceptionHost, true)
                        }
                    }
                    exceptionDialog = false
                }) { Text("保存") }
            }, dismissButton = { TextButton(onClick = { exceptionDialog = false }) { Text("取消") } })
    }
}

private fun setClear(vm: BrowserViewModel, cache: Boolean? = null, cookies: Boolean? = null,
                     history: Boolean? = null, site: Boolean? = null) {
    val p = (vm.manager.context.applicationContext as com.local.geckobrowser.GeckoBrowserApp).preferences.cached.value
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
        (vm.manager.context.applicationContext as com.local.geckobrowser.GeckoBrowserApp)
            .preferences.setClearOnExit(
                cache ?: p.clearCacheOnExit, cookies ?: p.clearCookiesOnExit,
                history ?: p.clearHistoryOnExit, site ?: p.clearSiteDataOnExit
            )
    }
}

@Composable
fun SiteSettingsScreen(vm: BrowserViewModel, nav: NavHostController) {
    val list by vm.siteSettings.collectAsState()
    Scaffold(topBar = { Text("站点设置", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp)) }) { padding ->
        if (list.isEmpty()) {
            EmptyState("暂无站点例外", "允许或阻止站点权限后，例外记录会保存在本地", Icons.Default.Security)
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(list) { s ->
                    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(s.origin, style = MaterialTheme.typography.titleSmall)
                            Text("摄像头 ${s.camera ?: "默认"} · 麦克风 ${s.microphone ?: "默认"} · 位置 ${s.geolocation ?: "默认"} · JavaScript ${if (s.javascriptEnabled) "开" else "关"}",
                                style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                    com.local.geckobrowser.data.AppDatabase.build(vm.manager.context)
                                        .siteSettingDao().delete(s.origin)
                                }
                            }) { Text("重置该站点") }
                        }
                    }
                }
                item {
                    Button(onClick = {
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            com.local.geckobrowser.data.AppDatabase.build(vm.manager.context)
                                .siteSettingDao().clearAll()
                        }
                    }, modifier = Modifier.padding(12.dp)) { Text("全部清除") }
                }
            }
        }
    }
}

@Composable
fun ExtensionsScreen(vm: BrowserViewModel, nav: NavHostController) {
    val list by vm.extensions.collectAsState()
    val saf = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.installExtensionFromSaf(uri)
    }
    Scaffold(topBar = { Text("扩展", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp)) }) { padding ->
        Column(Modifier.padding(padding)) {
            Text("仅支持本地侧载 .xpi，不依赖 AMO 远程安装；移动端 WebExtension UI API 不完整，action 入口由宿主菜单提供。",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
            Button(onClick = { saf.launch("application/octet-stream") }, modifier = Modifier.padding(12.dp)) {
                Icon(Icons.Default.Extension, contentDescription = "侧载扩展")
                Text("从本地文件侧载扩展")
            }
            if (list.isEmpty()) {
                EmptyState("暂无扩展", "可从本地 .xpi 文件侧载或启用内置扩展", Icons.Default.Extension)
            } else {
                LazyColumn {
                    items(list) { e ->
                        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(e.name, style = MaterialTheme.typography.titleSmall)
                                Text("版本 ${e.version} · ${e.id}", style = MaterialTheme.typography.bodySmall)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(checked = e.enabled, onCheckedChange = { vm.toggleExtension(e.id, it) })
                                    Text("启用", Modifier.padding(start = 8.dp))
                                    Switch(checked = e.allowedInPrivate,
                                        onCheckedChange = { vm.setExtensionPrivateAllowed(e.id, it) })
                                    Text("无痕中运行", Modifier.padding(start = 8.dp))
                                }
                                TextButton(onClick = { vm.uninstallExtension(e.id) }) { Text("卸载") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
}
@Composable private fun SettingsRow(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        content()
    }
}
@Composable private fun SettingsSwitch(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}
private fun ThemeMode.label() = when (this) { ThemeMode.LIGHT -> "浅色"; ThemeMode.DARK -> "深色"; ThemeMode.SYSTEM -> "跟随系统" }
private fun ToolbarPosition.label() = when (this) { ToolbarPosition.TOP -> "顶部"; ToolbarPosition.BOTTOM -> "底部" }
private fun TrackingPolicy.label() = when (this) {
    TrackingPolicy.STANDARD -> "标准"; TrackingPolicy.STRICT -> "严格"; TrackingPolicy.CUSTOM -> "自定义"
}
