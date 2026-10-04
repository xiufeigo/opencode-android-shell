package com.xiufeigo.opencodeshell.web.host

import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * P2 性能归因：进程相对计时打点。
 *
 * `Process.getStartUptimeMillis()` 返回**本进程**启动时刻的 uptime，
 * 于是 `elapsedRealtime() - 进程启动` 就是一张以「进程创建」为 0 的时间轴，
 * **不受设备 8 小时时差影响**（AB_NATIVE_VS_WEB §3.1.1 踩过的坑）。
 *
 * 全部走 `Log.i`，`logcat` 单条抓 `[OcPerf]` / `[OcPerfJS]` 即可。
 */
object PerfMarks {
    const val TAG = "OcPerf"

    private val procStartUptime: Long = runCatching { Process.getStartUptimeMillis() }
        .getOrElse { SystemClock.uptimeMillis() }

    /** 进程创建后经过的毫秒数。 */
    val ms: Long get() = SystemClock.elapsedRealtime() - procStartUptime

    fun mark(name: String) {
        Log.i(TAG, "$name t=${ms}ms")
    }

    fun mark(name: String, detail: String) {
        Log.i(TAG, "$name t=${ms}ms $detail")
    }

    /** 计时开关：默认开（debug 壳），`oc_perf=0` 可关。 */
    @Volatile
    var enabled: Boolean = true

    fun markIfEnabled(name: String, detail: String = "") {
        if (enabled) Log.i(TAG, "$name t=${ms}ms $detail")
    }
}
