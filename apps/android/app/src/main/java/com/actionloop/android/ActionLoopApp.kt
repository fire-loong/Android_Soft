package com.actionloop.android

import android.app.Application
import com.actionloop.android.data.Session
import com.actionloop.android.work.SyncScheduler

/**
 * ActionLoop Android 应用入口：初始化加密会话、注册周期同步。
 * 通知采集服务由系统在用户授权后绑定，应用不会主动开启。
 */
class ActionLoopApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Session.init(this)
        SyncScheduler.ensure(this)
    }
}
