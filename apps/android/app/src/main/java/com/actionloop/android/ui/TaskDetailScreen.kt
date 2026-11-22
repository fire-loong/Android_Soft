package com.actionloop.android.ui

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.actionloop.android.data.ApiClient
import com.actionloop.android.data.ApiException
import com.actionloop.android.data.Completion
import com.actionloop.android.data.Session
import com.actionloop.android.data.Step
import com.actionloop.android.data.Task
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(taskId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val workspaceId = Session.currentWorkspaceId

    var task by remember { mutableStateOf<Task?>(null) }
    var steps by remember { mutableStateOf<List<Step>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var transitionExpanded by remember { mutableStateOf(false) }
    var targetStatus by remember { mutableStateOf<String?>(null) }
    var reason by remember { mutableStateOf("") }
    var addStepOpen by remember { mutableStateOf(false) }
    var newStepTitle by remember { mutableStateOf("") }

    fun msg(e: Throwable) = scope.launch {
        snackbar.showSnackbar(if (e is ApiException) "${e.code}: ${e.message}" else "网络错误：${e.message}")
    }
    fun reload() {
        val wid = workspaceId ?: return
        scope.launch {
            runCatching {
                task = ApiClient.getTask(taskId, wid)
                steps = ApiClient.steps(taskId, wid)
            }.onFailure { msg(it) }
            loading = false
        }
    }
    LaunchedEffect(Unit) { loading = true; reload() }

    fun doTransition() {
        val t = task ?: return
        val target = targetStatus ?: return
        val wid = workspaceId ?: return
        scope.launch {
            runCatching { ApiClient.transition(taskId, wid, target, t.version, reason.ifBlank { null }) }
                .onSuccess { reload(); targetStatus = null; reason = "" }
                .onFailure { msg(it) }
        }
    }
    fun doAddStep() {
        val wid = workspaceId ?: return
        if (newStepTitle.isBlank()) return
        scope.launch {
            runCatching { ApiClient.createStep(taskId, wid, newStepTitle.trim(), null) }
                .onSuccess { addStepOpen = false; newStepTitle = ""; reload() }
                .onFailure { msg(it) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(task?.title ?: "事项详情") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        if (loading && task == null) {
            Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        val t = task
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text(t?.title ?: "", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(6.dp))
                        Row {
                            AssistChip(onClick = {}, label = { Text(StatusMeta.taskLabel(t?.status ?: "")) })
                            Spacer(Modifier.height(0.dp))
                            Text("  版本 v${t?.version ?: 0}", style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("截止：${t?.dueAt ?: t?.dueDate ?: "—"}", style = MaterialTheme.typography.bodySmall)
                        if (!t?.description.isNullOrBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(t?.description ?: "", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("状态转换", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box {
                                Button(onClick = { transitionExpanded = true }) { Text(targetStatus?.let { StatusMeta.taskLabel(it) } ?: "选择目标状态") }
                                DropdownMenu(expanded = transitionExpanded, onDismissRequest = { transitionExpanded = false }) {
                                    (t?.allowedTransitions ?: emptyList()).forEach { s ->
                                        DropdownMenuItem(
                                            text = { Text(StatusMeta.taskLabel(s)) },
                                            onClick = { targetStatus = s; transitionExpanded = false }
                                        )
                                    }
                                    if ((t?.allowedTransitions ?: emptyList()).isEmpty()) {
                                        DropdownMenuItem(text = { Text("（无可用转换）") }, onClick = {})
                                    }
                                }
                            }
                            Spacer(Modifier.height(0.dp))
                            Button(onClick = { doTransition() }, enabled = targetStatus != null) { Text("转换") }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(reason, { reason = it }, label = { Text("原因（选填）") }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("步骤", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { addStepOpen = true }) { Text("+ 添加步骤") }
                }
            }
            items(steps, key = { it.id }) { step ->
                StepRow(step, snackbar) { reload() }
            }
        }
    }

    if (addStepOpen) {
        AlertDialog(
            onDismissRequest = { addStepOpen = false },
            title = { Text("添加步骤") },
            text = { OutlinedTextField(newStepTitle, { newStepTitle = it }, label = { Text("步骤标题") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { doAddStep() }) { Text("添加") } },
            dismissButton = { TextButton(onClick = { addStepOpen = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun StepRow(step: Step, snackbar: SnackbarHostState, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val wid = Session.currentWorkspaceId
    var completions by remember { mutableStateOf<List<Completion>>(emptyList()) }
    var statement by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(expanded) {
        if (expanded && wid != null) {
            runCatching { completions = ApiClient.completions(step.id, wid) }
        }
    }
    fun submit() {
        val w = wid ?: return
        if (statement.isBlank()) return
        scope.launch {
            runCatching { ApiClient.submitCompletion(step.id, w, statement.trim()) }
                .onSuccess {
                    statement = ""
                    runCatching { completions = ApiClient.completions(step.id, w) }
                    onChanged()
                }.onFailure { e ->
                    scope.launch { snackbar.showSnackbar(if (e is ApiException) "${e.code}: ${e.message}" else "网络错误") }
                }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = {}, label = { Text(StatusMeta.stepLabel(step.status)) })
                Spacer(Modifier.height(0.dp))
                Text(step.title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(0.dp))
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "凭证") }
            }
            if (!step.instruction.isNullOrBlank()) {
                Text(step.instruction, style = MaterialTheme.typography.bodySmall)
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text("已提交凭证", style = MaterialTheme.typography.labelMedium)
                completions.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.statement ?: "（无说明）", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        AssistChip(onClick = {}, label = { Text(StatusMeta.reviewLabel(c.reviewState)) })
                    }
                }
                if (completions.isEmpty()) Text("暂无", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(statement, { statement = it }, label = { Text("完成说明") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Button(onClick = { submit() }) { Text("提交完成凭证") }
            }
        }
    }
}
