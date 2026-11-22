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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.actionloop.android.data.ApiClient
import com.actionloop.android.data.ApiException
import com.actionloop.android.data.Candidate
import com.actionloop.android.data.ConfirmCandidateReq
import com.actionloop.android.data.ReminderInput
import com.actionloop.android.data.SyncRepository
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 待确认收件箱（指导书 4.3 / 13.2）：AI/规则只产生候选，必须由用户确认后才创建事项与提醒。
 * 日期存在多值或低置信度时，用户必须显式勾选“日期已核对”，否则服务端会拒绝写入截止日期。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(workspaceId: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var candidates by remember { mutableStateOf<List<Candidate>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<Candidate?>(null) }
    var stepText by remember { mutableStateOf("") }
    var dueConfirmed by remember { mutableStateOf(false) }
    var withReminder by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }

    fun msg(e: Throwable) = scope.launch {
        snackbar.showSnackbar(if (e is ApiException) "${e.code}: ${e.message}" else "网络错误：${e.message}")
    }

    fun refresh() {
        val wid = workspaceId ?: return
        scope.launch {
            loading = true
            runCatching { candidates = ApiClient.candidates(wid).items }
                .onFailure { msg(it) }
            loading = false
        }
    }

    LaunchedEffect(workspaceId) { refresh() }

    fun open(c: Candidate) {
        selected = c
        stepText = c.nextAction ?: c.title
        dueConfirmed = false
        withReminder = c.dueAt != null
    }

    fun confirm() {
        val c = selected ?: return
        val wid = workspaceId ?: return
        submitting = true
        scope.launch {
            val reminders = buildList {
                if (withReminder && c.dueAt != null) {
                    runCatching {
                        val trigger = Instant.parse(c.dueAt).minus(1, ChronoUnit.HOURS).toString()
                        add(ReminderInput(triggerAt = trigger, channel = "NOTIFICATION", message = c.title))
                    }
                }
            }
            runCatching {
                ApiClient.confirmCandidate(
                    c.id,
                    ConfirmCandidateReq(
                        baseVersion = c.version,
                        dueDateConfirmed = dueConfirmed,
                        steps = listOf(stepText.trim()).filter { it.isNotBlank() },
                        reminders = reminders
                    )
                )
            }.onSuccess {
                selected = null
                snackbar.showSnackbar("已创建事项，可在“今天”查看")
                workspaceId?.let { SyncRepository.refreshReminders(context, it) }
                refresh()
            }.onFailure { msg(it) }
            submitting = false
        }
    }

    fun dismiss(c: Candidate) {
        scope.launch {
            runCatching { ApiClient.dismissCandidate(c.id, c.version, "用户在 Android 忽略") }
                .onSuccess { selected = null; refresh() }
                .onFailure { msg(it) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("待确认（${candidates.size}）") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                workspaceId == null -> Text("请先在“今天”页选择一个空间", Modifier.padding(16.dp))
                loading && candidates.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                candidates.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("暂无待确认候选\n通知或分享导入后会自动出现在这里")
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(candidates, key = { it.id }) { c ->
                        Card(Modifier.fillMaxWidth().clickable { open(c) }) {
                            Column(Modifier.padding(14.dp)) {
                                Text(c.title, style = MaterialTheme.typography.titleMedium)
                                if (!c.summary.isNullOrBlank()) {
                                    Text(c.summary, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                }
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AssistChip(onClick = {}, label = { Text("置信度 ${((c.confidence ?: 0.0) * 100).toInt()}%") })
                                    if (!c.sourceApp.isNullOrBlank()) {
                                        AssistChip(onClick = {}, label = { Text(c.sourceApp) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val current = selected
    if (current != null) {
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(current.title) },
            text = {
                Column {
                    Text(current.sourcePreview ?: current.summary ?: "（无来源预览）",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = stepText, onValueChange = { stepText = it },
                        label = { Text("第一步（可在事项详情继续补充）") }, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = dueConfirmed, onCheckedChange = { dueConfirmed = it })
                        Text("我已核对日期 ${current.dueDate ?: "（无明确日期）"}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (current.dueAt != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = withReminder, onCheckedChange = { withReminder = it })
                            Text("提前 1 小时提醒", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text("AI 仅生成候选，确认后才会创建事项与提醒。", style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                Button(onClick = { confirm() }, enabled = !submitting && stepText.isNotBlank()) {
                    Text(if (submitting) "提交中…" else "确认创建")
                }
            },
            dismissButton = {
                TextButton(onClick = { dismiss(current) }) { Text("忽略") }
            }
        )
    }
}
