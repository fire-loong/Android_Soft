package com.actionloop.android.notifications

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.actionloop.android.data.ApiClient
import com.actionloop.android.data.ApiException
import com.actionloop.android.data.Session
import com.actionloop.android.data.ShareSourceReq
import java.util.concurrent.TimeUnit

/**
 * 通知/分享来源上传（离线可靠）：只在有网络时上传，失败指数退避重试。
 * 上传内容仅为用户已授权 App 的非敏感文本；原始屏幕帧不在本链路内。
 */
class SourceUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val workspaceId = inputData.getString(KEY_WORKSPACE) ?: return Result.failure()
        val text = inputData.getString(KEY_TEXT) ?: return Result.failure()
        if (!Session.isLoggedIn) return Result.success()
        val request = ShareSourceReq(
            workspaceId = workspaceId,
            sourceType = inputData.getString(KEY_TYPE) ?: "NOTIFICATION",
            sourceApp = inputData.getString(KEY_PACKAGE),
            sourceKey = inputData.getString(KEY_SOURCE_KEY),
            title = inputData.getString(KEY_TITLE),
            text = text,
            timezone = "Asia/Shanghai"
        )
        return try {
            ApiClient.shareSource(request)
            Result.success()
        } catch (e: ApiException) {
            // 4xx（未授权/空间不存在/校验失败）无法靠重试解决，避免无限重试
            if (e.code.startsWith("HTTP_4") || e.code == "AUTH_INVALID") Result.failure() else retry()
        } catch (e: Exception) {
            retry()
        }
    }

    private fun retry(): Result =
        if (runAttemptCount < 6) Result.retry() else Result.failure()

    companion object {
        private const val KEY_WORKSPACE = "workspaceId"
        private const val KEY_TEXT = "text"
        private const val KEY_TITLE = "title"
        private const val KEY_PACKAGE = "package"
        private const val KEY_SOURCE_KEY = "sourceKey"
        private const val KEY_TYPE = "sourceType"

        fun enqueue(
            context: Context,
            workspaceId: String,
            packageName: String?,
            sourceKey: String?,
            title: String?,
            text: String,
            sourceType: String = "NOTIFICATION"
        ) {
            val data = workDataOf(
                KEY_WORKSPACE to workspaceId,
                KEY_TEXT to text,
                KEY_TITLE to title,
                KEY_PACKAGE to packageName,
                KEY_SOURCE_KEY to sourceKey,
                KEY_TYPE to sourceType
            )
            val request = OneTimeWorkRequestBuilder<SourceUploadWorker>()
                .setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "source-upload-${sourceKey?.hashCode() ?: text.hashCode()}",
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun enqueueNow(context: Context, workspaceId: String, text: String) {
            enqueue(context, workspaceId, packageName = null, sourceKey = "share-${System.currentTimeMillis()}",
                title = "用户分享", text = text, sourceType = "SHARE_TEXT")
        }
    }
}
