package com.actionloop.android.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.actionloop.android.assist.AssistSignalClient
import com.actionloop.android.assist.GuidanceRuntime
import com.actionloop.android.assist.ScreenCaptureService
import com.actionloop.android.data.AssistSessionView
import com.actionloop.android.data.Session
import com.actionloop.android.notifications.AllowlistStore
import com.actionloop.android.notifications.ReminderScheduler
import com.actionloop.android.work.SyncWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 权限中心：通知读取授权 + 按 App 白名单 + 立即同步。
 * 页面明确告知“只读、可随时关闭、敏感通知不上传、不采集屏幕帧”。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var listenerEnabled by remember { mutableStateOf(isListenerEnabled(context)) }
    var allowed by remember { mutableStateOf<Set<String>>(emptySet()) }
    val seen by AllowlistStore.seen(context).collectAsState(initial = emptySet())
    var exactGranted by remember { mutableStateOf(ReminderScheduler.canScheduleExact(context)) }
    var notifGranted by remember { mutableStateOf(notificationsEnabled(context)) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifGranted = notificationsEnabled(context)
    }

    var overlayGranted by remember { mutableStateOf(GuidanceRuntime.canDrawOverlays(context)) }
    var a11yEnabled by remember { mutableStateOf(GuidanceRuntime.isAccessibilityEnabled(context)) }
    var sessionActive by remember { mutableStateOf(false) }
    var assistSession by remember { mutableStateOf(AssistSignalClient.current()) }
    var assistBusy by remember { mutableStateOf(false) }
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenCaptureService.start(context, result.resultCode, result.data!!)
        } else {
            Toast.makeText(context, "未授权屏幕采集，引导未启动", Toast.LENGTH_SHORT).show()
        }
    }

    /** 发起远程求助：创建开放邀请会话；只读标注，不建立任何控制通道。 */
    fun startAssist() {
        val ws = Session.currentWorkspaceId
        if (ws == null) {
            Toast.makeText(context, "请先选择工作空间后再发起求助", Toast.LENGTH_SHORT).show()
            return
        }
        if (assistBusy) return
        assistBusy = true
        scope.launch {
            runCatching { AssistSignalClient.start(context, ws) }
                .onSuccess { s ->
                    assistSession = s
                    Toast.makeText(context, "求助已发起，邀请码已生成", Toast.LENGTH_SHORT).show()
                }
                .onFailure { e ->
                    Toast.makeText(context, "发起失败：${e.message ?: "网络错误"}", Toast.LENGTH_LONG).show()
                }
            assistBusy = false
        }
    }

    // 悬浮层“求助”按钮直接进入发起流程（本页在前台时），离开页面恢复默认拉起 App 行为。
    DisposableEffect(Unit) {
        GuidanceRuntime.helpHandler = { scope.launch { startAssist() } }
        onDispose { GuidanceRuntime.helpHandler = null }
    }

    LaunchedEffect(Unit) {
        allowed = AllowlistStore.allowed(context)
    }

    // 轮询刷新引导权限与会话状态（权限/服务在系统设置中变化时能即时反映）。
    LaunchedEffect(Unit) {
        while (true) {
            overlayGranted = GuidanceRuntime.canDrawOverlays(context)
            a11yEnabled = GuidanceRuntime.isAccessibilityEnabled(context)
            sessionActive = runCatching { GuidanceRuntime.overlay?.isShown() == true }.getOrDefault(false)
            assistSession = AssistSignalClient.current()
            delay(1000)
        }
    }

    fun refresh() {
        listenerEnabled = isListenerEnabled(context)
        exactGranted = ReminderScheduler.canScheduleExact(context)
        notifGranted = notificationsEnabled(context)
        scope.launch { allowed = AllowlistStore.allowed(context) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("采集与同步权限") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("通知读取", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (listenerEnabled) "已授权。只读取你在下方勾选 App 的通知标题与正文。"
                        else "未授权。开启后仅采集你勾选的 App，敏感通知（验证码/支付等）不会上传。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }) { Text(if (listenerEnabled) "管理授权" else "去授权") }
                        Button(onClick = {
                            WorkManager.getInstance(context)
                                .enqueue(OneTimeWorkRequestBuilder<SyncWorker>().build())
                        }) { Text("立即同步") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { refresh() }) { Text("刷新状态") }
                }
            }
            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("本地提醒", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "事项提醒在本机通过闹钟调度，离线也能触发；不自动修改事项状态。" +
                            if (!exactGranted) "\n未授予“精确闹钟”权限，提醒可能被系统延迟（已明确标注，非静默降级）。" else "",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            Button(onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                                Text(if (notifGranted) "通知权限：已开" else "授权通知")
                            }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Button(onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }) { Text(if (exactGranted) "精确闹钟：已开" else "授权精确闹钟") }
                        }
                        Button(onClick = {
                            val exact = ReminderScheduler.scheduleTest(context)
                            Toast.makeText(
                                context,
                                "测试提醒已安排（${if (exact) "精确" else "非精确，可能延迟"}），约 5 秒后触发",
                                Toast.LENGTH_LONG
                            ).show()
                        }) { Text("发送测试提醒") }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("跨 App 引导 / 远程协助", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "高亮目标并给出文字提示，所有点击 / 输入 / 提交仍由你亲自完成。" +
                            "屏幕帧只在内存中处理，绝不落盘；检测到密码 / 支付 / 锁屏会立即清除高亮并停止。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "悬浮层：${if (overlayGranted) "已授权" else "未授权"} · " +
                            "无障碍：${if (a11yEnabled) "已开启（只读）" else "未开启"} · " +
                            "会话：${if (sessionActive) "进行中" else "未开始"}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }) { Text(if (overlayGranted) "悬浮层：已开" else "授权悬浮层") }
                        Button(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }) { Text("管理无障碍") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = overlayGranted,
                            onClick = {
                                val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                projectionLauncher.launch(mpm.createScreenCaptureIntent())
                            }
                        ) { Text("启动一次引导") }
                        Button(onClick = { ScreenCaptureService.stop(context) }) { Text("停止引导") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !assistBusy && assistSession == null,
                            onClick = { startAssist() }
                        ) { Text(if (assistBusy) "发起中…" else "发起远程求助") }
                        assistSession?.let { s ->
                            Button(onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("ActionLoop 协助邀请", s.id))
                                Toast.makeText(context, "邀请码已复制", Toast.LENGTH_SHORT).show()
                            }) { Text("复制邀请码") }
                            Button(onClick = {
                                scope.launch {
                                    assistBusy = true
                                    AssistSignalClient.stop(context, "user_permissions")
                                    assistSession = null
                                    assistBusy = false
                                    Toast.makeText(context, "已停止远程求助", Toast.LENGTH_SHORT).show()
                                }
                            }) { Text("停止求助") }
                        }
                    }
                    assistSession?.let { s ->
                        Spacer(Modifier.height(6.dp))
                        Text("邀请码：${s.id}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "状态：${s.state} · 协助者在 Web 协助台接受后，只能发送高亮/指针/文字提示；" +
                                "本机只上报当前帧标识（frameId），不上传画面、控件文本或输入内容。" +
                                "请先启动一次引导并授权屏幕采集，协助者才有当前帧可标注。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "说明：未开启无障碍时仍可启动远程协助；本地目标匹配需开启只读无障碍。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("允许采集的 App", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            if (seen.isEmpty()) {
                Text("尚未观察到通知。授予通知读取权限后，这里会出现可勾选的 App（仅记录包名）。",
                    style = MaterialTheme.typography.bodySmall)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(seen.toList().sorted()) { pkg ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                                Text(pkg, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = pkg in allowed,
                                    onCheckedChange = { checked ->
                                        scope.launch {
                                            AllowlistStore.setAllowed(context, pkg, checked)
                                            allowed = AllowlistStore.allowed(context)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

fun isListenerEnabled(context: android.content.Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

fun notificationsEnabled(context: android.content.Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    } else true
