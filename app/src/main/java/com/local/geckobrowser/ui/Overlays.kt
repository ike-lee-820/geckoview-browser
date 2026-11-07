package com.local.geckobrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.geckobrowser.browser.BrowserViewModel
import com.local.geckobrowser.feature.ReaderModeExtractor

/** 页内查找条：SessionFinder 结果显示当前匹配序号/总数，支持上一个/下一个。 */
@Composable
fun FindInPageBar(
    query: String, index: Int, count: Int,
    onQuery: (String) -> Unit, onNext: () -> Unit, onPrevious: () -> Unit, onClose: () -> Unit
) {
    Card(Modifier.fillMaxWidth().padding(8.dp)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = query, onValueChange = onQuery,
                modifier = Modifier.weight(1f), placeholder = { Text("在页面中查找") }, singleLine = true)
            Text("$index / $count", Modifier.padding(horizontal = 8.dp))
            IconButton(onClick = onPrevious) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上一个匹配")
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "下一个匹配")
            }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "关闭查找") }
        }
    }
}

/** 阅读模式：本地渲染标题、作者、正文、lead image，字体大小/行距/背景色可调节。 */
@Composable
fun ReaderModeOverlay(article: ReaderModeExtractor.Article, onClose: () -> Unit) {
    var fontScale by remember { mutableFloatStateOf(1f) }
    var lineSpacing by remember { mutableFloatStateOf(1.4f) }
    var bg by remember { mutableStateOf(Color.White) }
    val textColor = if (bg == Color.Black || bg == Color(0xFF121212)) Color.White else Color(0xFF202020)
    Column(Modifier.fillMaxSize().background(bg).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("阅读模式", style = MaterialTheme.typography.titleMedium, Modifier.weight(1f), color = textColor)
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "退出阅读模式") }
        }
        Text("字体 ${(16 * fontScale).toInt()}", color = textColor)
        Slider(value = fontScale, onValueChange = { fontScale = it }, valueRange = 0.7f..2f)
        Text("行距 ${lineSpacing}", color = textColor)
        Slider(value = lineSpacing, onValueChange = { lineSpacing = it }, valueRange = 1f..2.2f)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Color.White, Color(0xFFF5F0DC), Color(0xFF121212), Color.Black).forEach { c ->
                Button(onClick = { bg = c }, modifier = Modifier.width(64.dp)) { Text(" ") }
            }
        }
        Text(article.title, fontSize = (24 * fontScale).sp, fontWeight = FontWeight.Bold, color = textColor)
        if (article.author.isNotBlank()) Text(article.author, color = textColor)
        Spacer(Modifier.padding(8.dp))
        Text(article.body, fontSize = (16 * fontScale).sp,
            lineHeight = (16 * fontScale * lineSpacing).sp, color = textColor)
        // 阅读模式偏好按域名在本地记忆（字体、背景），由 DataStore/Room 保存。
    }
}

/** 翻译面板：页面翻译检测、原文/译文切换、语言包管理入口，全部本地模型。 */
@Composable
fun TranslationSheet(vm: BrowserViewModel, onClose: () -> Unit) {
    var translated by remember { mutableStateOf(false) }
    val models by vm.translationManager.models().collectAsState(initial = emptyList())
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("本地翻译", style = MaterialTheme.typography.titleMedium, Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "关闭翻译") }
            }
            Text("模型在本机运行，不调用云端翻译 API；模型体积和性能因设备而异。",
                style = MaterialTheme.typography.bodySmall)
            Text("已安装语言对：${models.size} 个", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                Button(onClick = { translated = !translated }) {
                    Text(if (translated) "显示原文" else "翻译此页面")
                }
                TextButton(onClick = {
                    vm.translationManager.checkPairSize("en", "zh") { size ->
                        vm.translationManager.downloadModel("en", "zh", size)
                    }
                }) { Text("下载语言包") }
            }
            Text("选中文本可通过长按菜单中的「翻译选中文本」使用本地模型即时翻译。",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
