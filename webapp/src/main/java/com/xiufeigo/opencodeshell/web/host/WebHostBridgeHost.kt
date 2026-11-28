package com.xiufeigo.opencodeshell.web.host

interface WebHostBridgeHost {
    val imeModeName: String

    fun dispatchToUiThread(action: () -> Unit)
    fun isForeground(): Boolean
    fun roundActivityTracker(): RoundActivityTracker
    fun applyKeepScreenOnState()
    fun requestNotificationPermission()
    fun requestNotificationPermissionOnRoundStart()
    fun setImeMode(mode: ImeMode)
    fun applyPageTheme(dark: Boolean)
    fun applySurfaceColor(cssColor: String?)
    fun onFirstContent(kind: String?, atMs: Long?)
    fun retryFromDiagnosticPage()
    fun evaluateJs(js: String)
}
