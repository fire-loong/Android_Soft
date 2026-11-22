package com.actionloop.android.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.Constraints
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.actionloop.android.data.Session
import com.actionloop.android.data.SyncRepository
import java.util.concurrent.TimeUnit

/**
 * 后台同步：重放离线写队列 + 刷新当前空间事项。
 * 只在已登录且有网络时执行；失败由 WorkManager 退避重试，不影响前台缓存展示。
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!Session.isLoggedIn) return Result.success()
        val workspaceId = Session.currentWorkspaceId ?: return Result.success()
        return try {
            SyncRepository.drainOutbox(applicationContext)
            SyncRepository.refreshTasks(applicationContext, workspaceId)
            SyncRepository.refreshReminders(applicationContext, workspaceId)
            if (runAttemptCount > 5) Result.failure() else Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }
}

object SyncScheduler {
    private const val UNIQUE = "actionloop-periodic-sync"

    fun ensure(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE, ExistingPeriodicWorkPolicy.KEEP, request
        )
    }
}
