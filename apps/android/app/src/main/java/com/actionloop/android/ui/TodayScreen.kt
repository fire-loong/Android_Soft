package com.actionloop.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.actionloop.android.data.ApiClient
import com.actionloop.android.data.ApiException
import com.actionloop.android.data.Session
import com.actionloop.android.data.SyncRepository
import com.actionloop.android.data.Task
import com.actionloop.android.data.Workspace
import com.actionloop.android.data.db.TaskEntity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onOpenTask: (String) -> Unit,
    onOpenWorkspaces: () -> Unit,
    onOpenInbox: () -> Unit,
    onOpenPermissions: () -> Unit,
    onLogout: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var workspaces by remember { mutableStateOf<List<Workspace>>(emptyList()) }
    var currentId by remember { mutableStateOf(Session.currentWorkspaceId) }
    var tasks by remember { mutableStateOf<List<Task>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var offline by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var createOpen by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var newDesc by remember { mutableStateOf("") }

    fun msg(e: Throwable) = scope.launch {
        snackbar.showSnackbar(if (e is ApiException) "${e.code}: ${e.message}" else "网络错误：${e.message}")
    }

    LaunchedEffect(Unit) {
        runCatching {
            val list = ApiClient.workspaces()
            workspaces = list
            if (currentId == null || list.none { it.id == currentId }) {
                currentId = list.firstOrNull()?.id?.also { Session.currentWorkspaceId = it }
            }
        }.onFailure { msg(it) }
    }
    LaunchedEffect(currentId) {
        val wid = currentId ?: return@LaunchedEffect
        loading = true
        offline = false
        runCatching { ApiClient.tasks(wid).content }
            .onSuccess {
                tasks = it
                SyncRepository.cacheTasks(context, wid, it)
                SyncRepository.refreshReminders(context, wid)
            }
            .onFailure {
                // 网络失败：回退到本地缓存，并明确标注离线，绝不用空列表冒充“没有事项”
                val cached = SyncRepository.loadCachedTasks(context, wid).map { it.toTask() }
                if (cached.isNotEmpty()) {
                    tasks = cached
                    offline = true
                    // 离线时仍从缓存重建提醒闹钟
                    SyncRepository.rescheduleRemindersFromCache(context, wid)
                } else {
                    msg(it)
                }
            }
        loading = false
    }

    fun createTask() {
        val wid = currentId ?: return
        if (newTitle.isBlank()) return
        scope.launch {
            runCatching { ApiClient.createTask(wid, newTitle.trim(), newDesc.trim().ifBlank { null }) }
                .onSuccess {
                    createOpen = false; newTitle = ""; newDesc = ""
                    val fresh = ApiClient.tasks(wid).content
                    tasks = fresh
                    SyncRepository.cacheTasks(context, wid, fresh)
                    SyncRepository.refreshReminders(context, wid)
                }
                .onFailure { msg(it) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // 200% 字号下长空间名会超出 TopAppBar 固定高度并与列表首卡重叠（真机发现）：单行省略。
                title = {
                    Text(
                        workspaces.find { it.id == currentId }?.name ?: "ActionLoop",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                actions = {
                    TextButton(onClick = onOpenInbox) { Text("待确认") }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Default.MoreVert, contentDescription = "更多") }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            workspaces.forEach { w ->
                                DropdownMenuItem(
                                    text = { Text("${w.name}${if (w.id == currentId) " ✓" else ""}") },
                                    onClick = {
                                        menuExpanded = false
                                        currentId = w.id
                                        Session.currentWorkspaceId = w.id
                                    }
                                )
                            }
                            DropdownMenuItem(text = { Text("空间与成员") }, onClick = { menuExpanded = false; onOpenWorkspaces() })
                            DropdownMenuItem(text = { Text("采集与同步权限") }, onClick = { menuExpanded = false; onOpenPermissions() })
                            DropdownMenuItem(text = { Text("退出登录") }, onClick = { menuExpanded = false; onLogout() })
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { createOpen = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("新建事项") }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (offline) {
                Card(Modifier.fillMaxWidth()) {
                    Text("离线：显示本地缓存，修改将在联网后同步", Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFF8A6D00))
                }
                Spacer(Modifier.height(8.dp))
            }
            if (loading && tasks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (tasks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("暂无事项，点击右下角新建") }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(tasks, key = { it.id }) { task ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { onOpenTask(task.id) }
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(task.title, style = MaterialTheme.typography.titleMedium)
                                if (!task.description.isNullOrBlank()) {
                                    Text(task.description, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                }
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AssistChip(onClick = {}, label = { Text(StatusMeta.taskLabel(task.status)) })
                                    Spacer(Modifier.height(0.dp))
                                    Text("  v${task.version}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (createOpen) {
        AlertDialog(
            onDismissRequest = { createOpen = false },
            title = { Text("新建事项") },
            text = {
                Column {
                    OutlinedTextField(newTitle, { newTitle = it }, label = { Text("标题") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(newDesc, { newDesc = it }, label = { Text("描述（选填）") })
                }
            },
            confirmButton = { TextButton(onClick = { createTask() }) { Text("创建") } },
            dismissButton = { TextButton(onClick = { createOpen = false }) { Text("取消") } }
        )
    }
}

/** 缓存实体 → 展示模型（只用于离线展示，不参与写操作）。*/
private fun TaskEntity.toTask() = Task(
    id = id,
    workspaceId = workspaceId,
    title = title,
    status = status,
    ownerId = ownerId,
    assigneeId = assigneeId,
    dueAt = dueAt,
    dueDate = dueDate,
    timePrecision = timePrecision,
    priority = null,
    description = description,
    version = version,
    createdAt = createdAt,
    updatedAt = updatedAt
)
