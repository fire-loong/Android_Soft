@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.actionloop.android

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.actionloop.android.assist.GuidanceRuntime
import com.actionloop.android.data.Session
import com.actionloop.android.designsystem.ActionLoopTheme
import com.actionloop.android.notifications.SourceUploadWorker
import com.actionloop.android.ui.InboxScreen
import com.actionloop.android.ui.LoginScreen
import com.actionloop.android.ui.PermissionsScreen
import com.actionloop.android.ui.TaskDetailScreen
import com.actionloop.android.ui.TodayScreen
import com.actionloop.android.ui.WorkspacesScreen

class MainActivity : ComponentActivity() {

    /** 系统分享（文字）进入时的待导入内容；由 Compose 观察。 */
    private val sharedText = mutableStateOf<String?>(null)

    /** 提醒通知点击后待打开的事项 id；由 Compose 观察并导航。 */
    private val pendingTask = mutableStateOf<String?>(null)

    /** 悬浮层“求助”按钮拉起 App 后待打开的协助入口。 */
    private val pendingHelp = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedText.value = extractSharedText(intent)
        pendingTask.value = intent.getStringExtra("openTask")
        pendingHelp.value = intent?.action == GuidanceRuntime.ACTION_HELP_REQUEST
        setContent {
            ActionLoopTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var screen by remember {
                        val start = if (Session.isLoggedIn) {
                            pendingTask.value?.let { Screen.TaskDetail(it) } ?: Screen.Today
                        } else Screen.Login
                        mutableStateOf<Screen>(start)
                    }
                    // 提醒通知点击（含 onNewIntent）后导航到对应事项
                    val openTask = pendingTask.value
                    LaunchedEffect(openTask) {
                        if (openTask != null && Session.isLoggedIn) {
                            screen = Screen.TaskDetail(openTask)
                            pendingTask.value = null
                        }
                    }
                    // 悬浮层“求助”：打开 App 内的协助入口（不在后台静默发起网络请求）。
                    LaunchedEffect(pendingHelp.value) {
                        if (pendingHelp.value) {
                            if (Session.isLoggedIn) {
                                screen = Screen.Permissions
                            } else {
                                Toast.makeText(
                                    this@MainActivity,
                                    "请先登录，再在权限中心发起远程求助",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            pendingHelp.value = false
                        }
                    }
                    when (val s = screen) {
                        Screen.Login -> LoginScreen(onLoggedIn = { screen = Screen.Today })
                        Screen.Today -> TodayScreen(
                            onOpenTask = { screen = Screen.TaskDetail(it) },
                            onOpenWorkspaces = { screen = Screen.Workspaces },
                            onOpenInbox = { screen = Screen.Inbox },
                            onOpenPermissions = { screen = Screen.Permissions },
                            onLogout = { Session.clear(); screen = Screen.Login }
                        )
                        is Screen.TaskDetail -> TaskDetailScreen(taskId = s.taskId, onBack = { screen = Screen.Today })
                        Screen.Workspaces -> WorkspacesScreen(
                            onBack = { screen = Screen.Today },
                            onSwitched = { screen = Screen.Today }
                        )
                        Screen.Inbox -> InboxScreen(
                            workspaceId = Session.currentWorkspaceId,
                            onBack = { screen = Screen.Today }
                        )
                        Screen.Permissions -> PermissionsScreen(onBack = { screen = Screen.Today })
                    }

                    val share = sharedText.value
                    if (share != null && Session.isLoggedIn) {
                        ShareImportDialog(
                            initial = share,
                            onDismiss = { sharedText.value = null },
                            onImport = { text ->
                                val workspaceId = Session.currentWorkspaceId
                                if (workspaceId == null) {
                                    Toast.makeText(this, "请先在“今天”页选择空间", Toast.LENGTH_SHORT).show()
                                } else {
                                    SourceUploadWorker.enqueueNow(this, workspaceId, text)
                                    Toast.makeText(this, "已加入来源导入队列（联网后自动上传）", Toast.LENGTH_SHORT).show()
                                    sharedText.value = null
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractSharedText(intent)?.let { sharedText.value = it }
        intent.getStringExtra("openTask")?.let { pendingTask.value = it }
        if (intent.action == GuidanceRuntime.ACTION_HELP_REQUEST) pendingHelp.value = true
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 分屏只改变 Activity 窗口配置；等当前配置/布局事务完成后恢复悬浮窗层级。
        window.decorView.post { GuidanceRuntime.overlay?.restoreWindowOrder() }
    }

    /** 只接受用户主动分享的文字；不读取任何第三方应用数据。*/
    private fun extractSharedText(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        if (intent.type?.startsWith("text/") != true) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        return text?.takeIf { it.isNotBlank() }
    }
}

private sealed interface Screen {
    data object Login : Screen
    data object Today : Screen
    data class TaskDetail(val taskId: String) : Screen
    data object Workspaces : Screen
    data object Inbox : Screen
    data object Permissions : Screen
}

@androidx.compose.material3.ExperimentalMaterial3Api
@androidx.compose.runtime.Composable
private fun ShareImportDialog(initial: String, onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入分享内容为来源") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("原文（可编辑）") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "将生成带原文证据的待确认候选，不会直接创建事项。",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(text.trim()) }, enabled = text.isNotBlank()) { Text("导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
