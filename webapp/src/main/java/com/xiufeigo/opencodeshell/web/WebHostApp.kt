package com.xiufeigo.opencodeshell.web

import android.app.Application
import com.xiufeigo.opencodeshell.web.host.AppUpdateChecker
import com.xiufeigo.opencodeshell.web.host.LocalUiOriginServer
import com.xiufeigo.opencodeshell.web.host.NotifierHost
import com.xiufeigo.opencodeshell.web.host.SecretStore

/**
 * W1 骨架的 [Application]。
 *
 * 统一持有进程资源：通知渠道、加密偏好及自带 UI 的固定来源。
 */
class WebHostApp : Application() {
    // A replacement Activity can start before the previous one's onDestroy.
    // The static origin belongs to the process, so overlapping Activities share it.
    val localUiServer: LocalUiOriginServer by lazy {
        LocalUiOriginServer(assets)
    }

    override fun onCreate() {
        super.onCreate()
        NotifierHost.ensureChannels(this)
        SecretStore.get(this)
        // 版本更新检查：进程级排程（启动后延迟 5s 问一次，之后每 6h 复查）。
        // 放在这里而不是 Activity：Activity 可能早就退出了，但进程还在时
        // 也应该能查到新版并发通知。**网络不在主线程**（见 AppUpdateChecker）。
        AppUpdateChecker.schedule(this)
    }
}
