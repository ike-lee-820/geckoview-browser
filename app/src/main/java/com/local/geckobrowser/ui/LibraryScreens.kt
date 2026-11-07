package com.local.geckobrowser.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.local.geckobrowser.data.PasswordCrypto
import com.local.geckobrowser.feature.BookmarkHtmlIo

@Composable
fun BookmarksScreen(vm: BrowserViewModel, nav: NavHostController) {
    val list by vm.bookmarks.collectAsState()
    var query by remember { mutableStateOf("") }
    var editor by remember { mutableStateOf(false) }
    var editTitle by remember { mutableStateOf("") }
    var editUrl by remember { mutableStateOf("") }
    val shown = list.filter { query.isBlank() || it.title.contains(query, true) || it.url.contains(query, true) }
    Scaffold(topBar = {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = query, onValueChange = { query = it },
                modifier = Modifier.weight(1f), placeholder = { Text("搜索书签") }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "搜索书签") })
            IconButton(onClick = { editor = true }) { Icon(Icons.Default.Add, contentDescription = "添加书签") }
        }
    }) { padding ->
        if (shown.isEmpty()) {
            EmptyState("暂无书签", "可在地址栏菜单中添加，或从本地 HTML 文件导入", Icons.Default.Bookmark,
                "添加当前页面") { vm.addBookmark() }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(shown) { b ->
                    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(b.title, style = MaterialTheme.typography.titleSmall)
                                Text(b.url, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                            IconButton(onClick = { vm.load(b.url); nav.popBackStack() }) {
                                Icon(Icons.Default.Bookmark, contentDescription = "打开书签")
                            }
                        }
                    }
                }
            }
        }
    }
    if (editor) {
        AlertDialog(onDismissRequest = { editor = false }, title = { Text("编辑书签") },
            text = {
                Column {
                    OutlinedTextField(value = editTitle, onValueChange = { editTitle = it }, label = { Text("标题") })
                    OutlinedTextField(value = editUrl, onValueChange = { editUrl = it }, label = { Text("网址") })
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (editUrl.isNotBlank()) vm.addBookmark(editTitle.ifBlank { editUrl }, editUrl)
                    editor = false
                }) { Text("保存") }
            }, dismissButton = { TextButton(onClick = { editor = false }) { Text("取消") } })
    }
}

@Composable
fun HistoryScreen(vm: BrowserViewModel, nav: NavHostController) {
    val list by vm.history.collectAsState()
    var query by remember { mutableStateOf("") }
    val shown = list.filter { query.isBlank() || it.title.contains(query, true) || it.url.contains(query, true) }
    Scaffold(topBar = {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = query, onValueChange = { query = it },
                modifier = Modifier.weight(1f), placeholder = { Text("搜索历史") }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "搜索历史") })
            Button(onClick = { vm.clearAllHistory() }) { Text("全部清除") }
        }
    }) { padding ->
        if (shown.isEmpty()) {
            EmptyState("暂无历史记录", "访问过的页面会按今天、昨天、更早分组保存在本地", Icons.Default.History)
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(shown) { h ->
                    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(h.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                Text(h.url, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                            IconButton(onClick = { vm.addBookmark(h.title, h.url) }) {
                                Icon(Icons.Default.Bookmark, contentDescription = "添加为书签")
                            }
                            IconButton(onClick = { vm.deleteHistory(h.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除历史记录")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DownloadsScreen(vm: BrowserViewModel, nav: NavHostController) {
    val list by vm.downloads.collectAsState()
    val groups = listOf("进行中" to list.filter { it.status == "RUNNING" || it.status == "QUEUED" || it.status == "PAUSED" },
        "已完成" to list.filter { it.status == "COMPLETED" },
        "失败" to list.filter { it.status == "FAILED" || it.status == "CANCELLED" })
    Scaffold(topBar = { Text("下载", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp)) }) { padding ->
        if (list.isEmpty()) {
            EmptyState("暂无下载", "网页下载会显示进度、速度与剩余时间", Icons.Default.Download)
        } else {
            LazyColumn(Modifier.padding(padding)) {
                groups.forEach { (label, items) ->
                    item { Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(12.dp)) }
                    this.items(items) { d ->
                        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(d.fileName, style = MaterialTheme.typography.titleSmall)
                                Text("${d.bytesReceived / 1024} KB / ${if (d.bytesTotal > 0) "${d.bytesTotal / 1024} KB" else "未知"} · ${d.speedBytesPerSec / 1024} KB/s",
                                    style = MaterialTheme.typography.bodySmall)
                                if (d.status == "RUNNING") LinearProgressIndicator(
                                    progress = { (d.bytesReceived.toFloat() / d.bytesTotal.coerceAtLeast(1)).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth())
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (d.status == "RUNNING") TextButton(onClick = { vm.pauseDownload(d.id) }) { Text("暂停") }
                                    if (d.status == "PAUSED") TextButton(onClick = { vm.resumeDownload(d.id) }) { Text("恢复") }
                                    TextButton(onClick = { vm.cancelDownload(d.id) }) { Text("取消") }
                                    TextButton(onClick = { vm.pauseDownload(d.id) }) { Text("删除记录") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PasswordsScreen(vm: BrowserViewModel, nav: NavHostController) {
    // 密码库：查看需设备凭证或生物识别验证；列表本身只展示域名与用户名，不展示明文。
    var query by remember { mutableStateOf("") }
    var generated by remember { mutableStateOf("") }
    Scaffold(topBar = { Text("密码库", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp)) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(value = query, onValueChange = { query = it },
                label = { Text("按域名搜索") }, leadingIcon = { Icon(Icons.Default.Search, contentDescription = "搜索密码") },
                modifier = Modifier.fillMaxWidth())
            Button(onClick = { generated = com.local.geckobrowser.feature.PasswordGenerator.generate() },
                modifier = Modifier.padding(vertical = 12.dp)) { Text("生成本地强密码") }
            if (generated.isNotBlank()) {
                Card(Modifier.fillMaxWidth()) { Text(generated, Modifier.padding(16.dp)) }
            }
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, contentDescription = "本地加密存储")
                Text("所有登录条目经 Android Keystore 加密后存于本地，不提供任何云端导出或同步入口。",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
            }
            EmptyState("暂无保存的登录", "在网页提交表单时可选择保存到本地加密密码库", Icons.Default.Lock)
        }
    }
}
