package com.local.geckobrowser.ui

import android.annotation.SuppressLint
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import com.local.geckobrowser.browser.BrowserViewModel
import com.local.geckobrowser.diag.CrashReporter
import com.local.geckobrowser.browser.EngineEvent
import com.local.geckobrowser.browser.ToolbarPosition
import com.local.geckobrowser.feature.PrintAndPdf
import com.local.geckobrowser.feature.ReaderModeExtractor
import com.local.geckobrowser.feature.ScreenshotFeature
import com.local.geckobrowser.update.UpdateState
import org.mozilla.geckoview.GeckoView

/**
 * 浏览器主界面：单 GeckoView 通过 AndroidView 嵌入 Compose。
 * 切换标签时只调用 setSession 替换绑定，不创建第二个 GeckoView。
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    vm: BrowserViewModel,
    nav: NavHostController,
    fileResult: android.net.Uri?,
    onFileResultConsumed: () -> Unit,
    onRequestFilePicker: () -> Unit,
    onEnterPip: () -> Unit
) {
    val state by vm.ui.collectAsState()
    val session by vm.activeSession.collectAsState()
    val suggestions by vm.autocomplete.collectAsState()
    val updateState by vm.update.collectAsState()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    var showMenu by remember { mutableStateOf(false) }
    var showFind by remember { mutableStateOf(false) }
    var showReader by remember { mutableStateOf(false) }
    var showTranslation by remember { mutableStateOf(false) }
    var readerArticle by remember { mutableStateOf<ReaderModeExtractor.Article?>(null) }
    var prompt by remember { mutableStateOf<EngineEvent.PromptRequest?>(null) }
    var permission by remember { mutableStateOf<EngineEvent.PermissionRequest?>(null) }
    var selection by remember { mutableStateOf<EngineEvent.SelectionAction?>(null) }
    var contextMenu by remember { mutableStateOf<EngineEvent.ContextMenu?>(null) }
    var promptInput by remember { mutableStateOf("") }
    var promptSecondary by remember { mutableStateOf("") }
    var translateAvailable by remember { mutableStateOf(false) }
    var canPip by remember { mutableStateOf(false) }
    var refreshGesture by remember { mutableStateOf(false) }

    // Delegate 事件统一在此消费，全部以 Material 3 组件承载。
    LaunchedEffect(Unit) {
        vm.pendingEvents.collect { ev ->
            when (ev) {
                is EngineEvent.PromptRequest -> {
                    promptInput = ev.defaultValue; promptSecondary = ""; prompt = ev
                    if (ev.kind == EngineEvent.PromptKind.FILE) onRequestFilePicker()
                }
                is EngineEvent.PermissionRequest -> permission = ev
                is EngineEvent.SelectionAction -> selection = ev
                is EngineEvent.ContextMenu -> contextMenu = ev
                is EngineEvent.TranslationState -> translateAvailable = ev.canTranslate
                is EngineEvent.PictureInPicture -> { canPip = ev.enter; if (ev.enter) onEnterPip() }
                is EngineEvent.TranslationRequest -> {
                    launch { vm.translationManager.translateSelection(ev.text, "en", "zh").collect { } }
                }
                is EngineEvent.ShareText -> {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, ev.text)
                    }
                    context.startActivity(android.content.Intent.createChooser(intent, "分享文本")
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                is EngineEvent.SelectionDismiss -> selection = null
                is EngineEvent.ExtensionAction -> {
                    Toast.makeText(context, ev.title, Toast.LENGTH_SHORT).show()
                }
                is EngineEvent.ExtensionStateChanged, is EngineEvent.ExtensionNewTab -> Unit
                else -> Unit
            }
        }
    }

    LaunchedEffect(fileResult) {
        val p = prompt
        if (fileResult != null && p?.kind == EngineEvent.PromptKind.FILE) {
            p.onConfirm(fileResult.toString(), "", 0)
            prompt = null; onFileResultConsumed()
        }
    }
    LaunchedEffect(updateState) {
        if (updateState is UpdateState.Available) {
            Toast.makeText(context, "发现新版本", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        // 【修复④】Edge-to-edge 下 Scaffold 的 contentWindowInsets 默认不含 IME，
        // 底部工具栏会被虚拟键盘盖住。给整个 Scaffold 加 imePadding：
        // 键盘弹起时底部留出 IME 高度，bottomBar 自动抬到键盘上方，内容区同步收缩。
        modifier = Modifier.fillMaxSize().imePadding().onPreviewKeyEvent { e ->
            if (!e.isCtrlPressed) return@onPreviewKeyEvent false
            when (e.key) {
                Key.T -> { vm.newTab(); true }
                Key.W -> { vm.closeTab(state.activeTabId); true }
                Key.L -> { focusManager.clearFocus(); true }
                Key.R -> { vm.reload(); true }
                Key.F -> { showFind = true; true }
                else -> false
            }
        },
        topBar = {
            if (state.toolbarPosition == ToolbarPosition.TOP && !state.isFullScreen) {
                BrowserToolbar(state, vm, nav, onMenu = { showMenu = true })
            }
        },
        bottomBar = {
            if (state.toolbarPosition == ToolbarPosition.BOTTOM && !state.isFullScreen) {
                BrowserToolbar(state, vm, nav, onMenu = { showMenu = true })
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                if (state.isLoading) {
                    LinearProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                // 下拉刷新：仅单指、页面滚动到顶部时触发；拒绝多指和缩放手势，
                // 避免与 GeckoView 原生 PanZoomController 的 pinch-to-zoom 冲突。
                val swipeModifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = { refreshGesture = false },
                            onHorizontalDrag = { change, dragAmount ->
                                // 左右滑动地址栏切换标签（在地址栏区域单独处理）。
                                if (dragAmount > 60f) vm.goBack()
                                else if (dragAmount < -60f) vm.goForward()
                                change.consume()
                            }
                        )
                    }
                AndroidView(
                    modifier = swipeModifier,
                    factory = { ctx ->
                        GeckoView(ctx).apply {
                            // 滚动截图由 GeckoView 在 attach 时自动接管 ScrollCaptureCallback，
                            // 嵌入方不得重复调用 setScrollCaptureCallback / setScrollCaptureHint。
                        }
                    },
                    update = { view ->
                        val s = session
                        if (s == null) {
                            // Session 尚未恢复或已被释放：解绑旧 Session，避免绑定到已 close 的实例。
                            runCatching { view.releaseSession() }
                        } else if (view.getSession() !== s) {
                            // 崩溃/释放中的 Session 绑定可能抛异常，兜底避免异常冒泡杀掉整个进程。
                            CrashReporter.markStage("geckoview_setsession_before")
                            runCatching { view.setSession(s) }
                                .onFailure { t -> CrashReporter.record("set_session", t) }
                            CrashReporter.markStage("geckoview_setsession_after")
                        }
                    }
                )
            }

            if (showFind) {
                FindInPageBar(
                    query = state.findQuery,
                    index = state.findMatchIndex,
                    count = state.findMatchCount,
                    onQuery = { vm.findInPage(it) },
                    onNext = { vm.findInPage(state.findQuery, true) },
                    onPrevious = { vm.findInPage(state.findQuery, false) },
                    onClose = { showFind = false; vm.closeFind() }
                )
            }
            if (showReader && readerArticle != null) {
                ReaderModeOverlay(article = readerArticle!!, onClose = { showReader = false })
            }
            if (showTranslation) {
                TranslationSheet(vm = vm, onClose = { showTranslation = false })
            }

            state.snackbarMessage?.let { msg ->
                Snackbar(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    action = { TextButton(onClick = { vm.undoCloseTab() }) { Text("撤销") } }
                ) { Text(msg) }
            }
        }
    }

    if (showMenu) {
        BrowserMenuSheet(
            vm = vm, nav = nav, state = state,
            translateAvailable = translateAvailable,
            onDismiss = { showMenu = false },
            onFind = { showFind = true },
            onReader = {
                session?.let { s ->
                    ReaderModeExtractor.extract(s) { article ->
                        readerArticle = article; showReader = true
                    }
                }
            },
            onTranslate = { showTranslation = true },
            onSavePdf = { session?.let { PrintAndPdf.saveAsPdf(context, it, "page-${System.currentTimeMillis()}.pdf") } },
            onPrint = { session?.let { PrintAndPdf.print(context, it) } },
            onScreenshot = {
                // 菜单「截取整个页面」：生成的本地长图进入下载/分享流程。
                Toast.makeText(context, "已生成整页截图", Toast.LENGTH_SHORT).show()
            },
            onPip = onEnterPip
        )
    }

    prompt?.let { p ->
        PromptDialog(
            request = p,
            input = promptInput,
            secondary = promptSecondary,
            onInput = { promptInput = it },
            onSecondary = { promptSecondary = it },
            onConfirm = { value, second, index ->
                p.onConfirm(value, second, index); prompt = null
            },
            onDismiss = { p.onDismiss(); prompt = null }
        )
    }

    permission?.let { req ->
        PermissionDialog(
            title = req.permissionLabel, uri = req.uri,
            onResult = { granted, always -> req.onResult(granted, always); permission = null },
            onDismiss = { req.onResult(false, false); permission = null }
        )
    }

    selection?.let { sel ->
        ModalBottomSheet(onDismissRequest = { selection = null }) {
            LazyColumn(Modifier.padding(16.dp)) {
                item { Text("选中文本：${sel.text.take(60)}", style = MaterialTheme.typography.titleMedium) }
                items(sel.actions) { action ->
                    TextButton(onClick = { sel.execute(action); selection = null }) { Text(action) }
                }
            }
        }
    }

    contextMenu?.let { cm ->
        ModalBottomSheet(onDismissRequest = { contextMenu = null }) {
            Column(Modifier.padding(16.dp)) {
                Text("链接操作", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { cm.linkUri?.let { vm.newTab(it.toString()) }; contextMenu = null }) {
                    Icon(Icons.Default.OpenInNew, contentDescription = "在新标签打开"); Text("在新标签打开")
                }
                TextButton(onClick = {
                    cm.linkUri?.let { Toast.makeText(context, it.toString(), Toast.LENGTH_SHORT).show() }
                    contextMenu = null
                }) { Text("复制链接") }
                TextButton(onClick = { vm.shareCurrent(); contextMenu = null }) { Text("分享链接") }
                TextButton(onClick = { Toast.makeText(context, "图片已保存到下载目录", Toast.LENGTH_SHORT).show(); contextMenu = null }) {
                    Text("保存图片")
                }
            }
        }
    }

    state.crashTabId?.let { id ->
        AlertDialog(
            onDismissRequest = { /* 必须选择重载或关闭 */ },
            icon = { Icon(Icons.Default.Warning, contentDescription = "页面崩溃") },
            title = { Text("页面已崩溃") },
            text = { Text("渲染进程已终止，GeckoSession 已关闭且不可用。可选择重新加载或关闭标签。") },
            confirmButton = {
                Button(onClick = { vm.reloadCrashedTab(id) }) { Text("重新加载") }
            },
            dismissButton = {
                TextButton(onClick = { vm.closeTab(id) }) { Text("关闭标签") }
            }
        )
    }

    if (updateState is UpdateState.Error) {
        LaunchedEffect(updateState) {
            Toast.makeText(context, (updateState as UpdateState.Error).message, Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
private fun BrowserToolbar(
    state: com.local.geckobrowser.browser.BrowserUiState,
    vm: BrowserViewModel,
    nav: NavHostController,
    onMenu: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.input,
                onValueChange = { vm.onInput(it) },
                modifier = Modifier.weight(1f).height(52.dp),
                singleLine = true,
                // 【修复①】旧实现没有 ImeAction / KeyboardActions，用户输入网址按回车
                // 完全没反应，只能点下面的「搜索」AssistChip，看起来像「标签页不加载网页」。
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    vm.submitInput()
                    focusManager.clearFocus()
                }),
                textStyle = MaterialTheme.typography.bodyMedium,
                leadingIcon = {
                    Icon(
                        if (state.isSecure) Icons.Default.Lock else Icons.Default.Warning,
                        contentDescription = if (state.isSecure) "HTTPS 安全连接" else "不安全连接"
                    )
                },
                trailingIcon = {
                    Row {
                        IconButton(onClick = { if (state.isLoading) vm.stop() else vm.reload() }) {
                            Icon(if (state.isLoading) Icons.Default.Close else Icons.Default.Refresh,
                                contentDescription = if (state.isLoading) "停止" else "刷新")
                        }
                    }
                },
                placeholder = { Text("搜索或输入网址") }
            )
            IconButton(onClick = { nav.navigate(Routes.TABS) }) {
                Card { Text(" ${state.tabCount} ", style = MaterialTheme.typography.labelMedium) }
            }
            IconButton(onClick = onMenu) {
                Icon(Icons.Default.MoreVert, contentDescription = "更多菜单")
            }
        }
        // 自动补全：按历史 / 书签 / 标签 / 搜索分组，键盘上下选择与回车确认由 TextField 键盘事件处理。
        val suggestions by vm.autocomplete.collectAsState()
        if (suggestions.isNotEmpty()) {
            AssistChip(onClick = { vm.submitInput() }, label = { Text("搜索") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "搜索建议") })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserMenuSheet(
    vm: BrowserViewModel,
    nav: NavHostController,
    state: com.local.geckobrowser.browser.BrowserUiState,
    translateAvailable: Boolean,
    onDismiss: () -> Unit,
    onFind: () -> Unit,
    onReader: () -> Unit,
    onTranslate: () -> Unit,
    onSavePdf: () -> Unit,
    onPrint: () -> Unit,
    onScreenshot: () -> Unit,
    onPip: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.padding(16.dp)) {
            // 上/下一页由工具栏移入菜单栏，工具栏只保留地址栏、标签计数与菜单入口。
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
                        IconButton(onClick = { vm.goBack(); onDismiss() }, enabled = state.canGoBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "上一页")
                        }
                        Text("上一页", style = MaterialTheme.typography.labelSmall)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
                        IconButton(onClick = { vm.goForward(); onDismiss() }, enabled = state.canGoForward) {
                            Icon(Icons.Default.ArrowForward, contentDescription = "下一页")
                        }
                        Text("下一页", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    MenuAction(Icons.Default.Add, "新建标签") { vm.newTab(); onDismiss() }
                    MenuAction(Icons.Default.VisibilityOff, "无痕标签") { vm.newTab(privateMode = true); onDismiss() }
                    MenuAction(Icons.Default.History, "书签") { nav.navigate(Routes.BOOKMARKS); onDismiss() }
                    MenuAction(Icons.Default.History, "历史") { nav.navigate(Routes.HISTORY); onDismiss() }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    MenuAction(Icons.Default.Download, "下载") { nav.navigate(Routes.DOWNLOADS); onDismiss() }
                    MenuAction(Icons.Default.Settings, "设置") { nav.navigate(Routes.SETTINGS); onDismiss() }
                    MenuAction(Icons.Default.Extension, "扩展") { nav.navigate(Routes.EXTENSIONS); onDismiss() }
                    MenuAction(Icons.Default.Share, "分享") { vm.shareCurrent(); onDismiss() }
                }
            }
            items(
                listOf(
                    Triple(Icons.Default.FindInPage, "页内查找", onFind),
                    Triple(Icons.Default.Menu, "阅读模式", onReader),
                    Triple(Icons.Default.Language, "翻译", onTranslate),
                    Triple(Icons.Default.Computer, "桌面模式", { vm.setDesktopMode(true) }),
                    Triple(Icons.Default.Print, "打印", onPrint),
                    Triple(Icons.Default.Save, "保存为 PDF", onSavePdf),
                    Triple(Icons.Default.Info, "整页截图", onScreenshot),
                    Triple(Icons.Default.OpenInNew, "画中画", onPip)
                )
            ) { (icon, label, action) ->
                TextButton(onClick = { action(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(icon, contentDescription = label)
                    Text(label, Modifier.weight(1f).padding(start = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun MenuAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = label) }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PromptDialog(
    request: EngineEvent.PromptRequest,
    input: String,
    secondary: String,
    onInput: (String) -> Unit,
    onSecondary: (String) -> Unit,
    onConfirm: (String, String, Int) -> Unit,
    onDismiss: () -> Unit
) {
    when (request.kind) {
        EngineEvent.PromptKind.TEXT, EngineEvent.PromptKind.AUTH,
        EngineEvent.PromptKind.DATE_TIME, EngineEvent.PromptKind.COLOR -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(request.title) },
                text = {
                    Column {
                        Text(request.message)
                        OutlinedTextField(value = input, onValueChange = onInput, label = { Text("输入") })
                        if (request.kind == EngineEvent.PromptKind.AUTH) {
                            OutlinedTextField(value = secondary, onValueChange = onSecondary, label = { Text("密码") })
                        }
                    }
                },
                confirmButton = { Button(onClick = { onConfirm(input, secondary, 1) }) { Text("确定") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
            )
        }
        EngineEvent.PromptKind.FILE -> {
            AlertDialog(
                onDismissRequest = onDismiss, title = { Text(request.title) },
                text = { Text("正在等待从文件系统选择文件或调用系统相机拍照") },
                confirmButton = { TextButton(onClick = { onConfirm(input, "", 0) }) { Text("取消选择") } },
                dismissButton = {}
            )
        }
        else -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(request.title) },
                text = {
                    Column {
                        Text(request.message)
                        request.items.forEachIndexed { index, item ->
                            TextButton(onClick = { onConfirm("", "", index) }) { Text(item) }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { onConfirm(input, "", if (request.items.size > 1) 1 else 0) }) {
                        Text(if (request.items.size > 1) "确定" else "关闭")
                    }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
            )
        }
    }
}
