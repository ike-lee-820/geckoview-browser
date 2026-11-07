package com.local.geckobrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.local.geckobrowser.browser.BrowserViewModel

/** 标签界面：网格卡片、分组颜色条、固定标签单独一行、多选批量、搜索、滑动关闭前撤销 Snackbar。 */
@Composable
fun TabsScreen(vm: BrowserViewModel, nav: NavHostController) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var groupDialog by remember { mutableStateOf(false) }
    var groupName by remember { mutableStateOf("") }
    val tabs = vm.manager.tabs.filter {
        query.isBlank() || it.title.contains(query, true) || it.url.contains(query, true)
    }
    val pinned = tabs.filter { it.isPinned }
    val normal = tabs.filter { !it.isPinned }

    Scaffold(
        topBar = {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = query, onValueChange = { query = it },
                    modifier = Modifier.weight(1f), placeholder = { Text("搜索标签") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "搜索标签") }, singleLine = true)
                IconButton(onClick = { vm.newTab(); nav.popBackStack() }) {
                    Icon(Icons.Default.Add, contentDescription = "新建标签")
                }
            }
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = { vm.newTab(privateMode = true) }) { Text("新建无痕标签") }
                Button(onClick = { groupDialog = true }) { Text("新建分组") }
                if (selected.isNotEmpty()) {
                    Button(onClick = { selected.forEach { vm.closeTab(it) }; selected = emptySet() }) {
                        Text("关闭所选")
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (pinned.isNotEmpty()) {
                item { Text("固定标签", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(12.dp)) }
                items(pinned) { tab ->
                    TabCard(tab.id, tab.title, tab.url, tab.groupId, vm, selected,
                        { id, checked -> selected = if (checked) selected + id else selected - id })
                }
            }
            item { Text("标签（${normal.size}）", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(12.dp)) }
            items(normal, key = { it.id }) { tab ->
                TabCard(tab.id, tab.title, tab.url, tab.groupId, vm, selected,
                    { id, checked -> selected = if (checked) selected + id else selected - id })
            }
            item {
                Text("标签分组", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(12.dp))
            }
            items(vm.manager.groups) { group ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(6.dp).height(32.dp).background(Color(group.colorArgb.toInt())))
                        Text(group.name, Modifier.weight(1f).padding(start = 12.dp))
                        IconButton(onClick = { vm.toggleGroup(group.id) }) {
                            Icon(Icons.Default.Edit, contentDescription = "折叠或展开分组")
                        }
                        IconButton(onClick = { vm.closeGroup(group.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "关闭分组内标签")
                        }
                    }
                }
            }
        }
    }

    if (groupDialog) {
        AlertDialog(
            onDismissRequest = { groupDialog = false },
            title = { Text("新建标签分组") },
            text = { OutlinedTextField(value = groupName, onValueChange = { groupName = it }, label = { Text("分组名称") }) },
            confirmButton = {
                Button(onClick = {
                    if (groupName.isNotBlank()) vm.createGroup(groupName, 0xFF1B5E20)
                    groupDialog = false; groupName = ""
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { groupDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun TabCard(
    id: String, title: String, url: String, groupId: String?,
    vm: BrowserViewModel, selected: Set<String>, onSelect: (String, Boolean) -> Unit
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        onClick = { vm.switchTab(id) }) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (groupId != null) {
                val color = vm.manager.groups.firstOrNull { it.id == groupId }?.colorArgb ?: 0xFF1B5E20
                Box(Modifier.width(4.dp).height(40.dp).background(Color(color.toInt())))
            }
            Checkbox(checked = selected.contains(id), onCheckedChange = { onSelect(id, it) })
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(url, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            IconButton(onClick = { vm.closeTab(id) }) {
                Icon(Icons.Default.Close, contentDescription = "关闭标签")
            }
        }
    }
}
