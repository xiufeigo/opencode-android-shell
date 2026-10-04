package com.xiufeigo.opencodeshell.web

import android.app.Application
import com.xiufeigo.opencodeshell.web.host.NotifierHost
import com.xiufeigo.opencodeshell.web.host.SecretStore

/**
 * W1 骨架的 [Application]。
 *
 * 只做两件跟进程同生命周期的事：建通知渠道（Android 8.0 必须在 post 之前建好）、
 * 打开加密偏好（keystore-backed，慢一点，别在每次 getSecret 时新建）。
 */
class WebHostApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotifierHost.ensureChannels(this)
        SecretStore.get(this)
    }
}
