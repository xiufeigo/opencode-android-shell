package com.xiufeigo.opencodeshell.web.host

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket

/**
 * UX2 · 稳定 origin —— 让「杀掉 app 再打开能接回同一终端会话」**有可能发生**。
 *
 * ## 为什么需要它
 *
 * 上游自己**已经有**「持久化 `pty id` + 进终端页自动 attach」的机制
 * （`_upstream/packages/app/src/session/terminal/context.tsx:104` 的
 * `persisted(terminalPersistTarget(scope, dir), TerminalState, { all: [] })`，
 * 与 `session/terminal/terminal.tsx:573-586` 的 `serverSDK.pty.connect({ ptyID: id, … })`）。
 * 浏览器侧它落在 **`localStorage`**（`runtime/persistence/storage.ts:509-510`）。
 *
 * 而 **`localStorage` 按 origin 隔离**。宿主把页面挂在回环代理上，origin 是
 * `http://127.0.0.1:<port>`（[LoopbackProxy.publicOrigin]）——
 * 改前绑的是 `ServerSocket(0)`（**系统临时端口**），**每次启动端口都不同**
 * ⇒ **origin 每次都变** ⇒ 上游存下来的 `pty id` 读不到 ⇒ 回来必然是空终端。
 *
 * ⇒ **本类只做一件事：把回环端口钉住**，让 origin 跨启动**不变**，
 * 于是上游那套机制**原样生效**。**不自造列表/重连 UI，不碰上游语义。**
 *
 * ## 取值为什么是 40495
 *
 * 与上游后端默认端口 `4095` 同一族，回环地址 + 非特权端口（>1023），
 * 落在 Android 设备上不与系统/别的常用服务冲突。**被占用就自动退回临时端口**
 * （见 [bind]），所以钉不住也只是退回改前的行为，**不会起不来**。
 */
object StableOrigin {

    /** 默认钉住的回环端口。>1023（非特权）、不与后端 4095 相同。 */
    const val DEFAULT_PREFERRED_PORT = 40_495

    /**
     * 端口候选顺序：**先钉的、再临时**。
     *
     * 传 `0` 或负数 ⇒ 退化成"只要临时端口"，即改前行为。
     */
    fun candidates(preferred: Int): List<Int> =
        if (isPinned(preferred)) listOf(preferred, 0) else listOf(0)

    /** 这个端口是不是"钉住的"（会跨启动保持不变 ⇒ origin 稳定）。 */
    fun isPinned(preferred: Int): Boolean = preferred > 0

    /** 与 [LoopbackProxy.publicOrigin] 同构，便于单测断言。 */
    fun origin(port: Int): String = "http://127.0.0.1:$port"

    /**
     * 按 [candidates] 顺序尝试绑定；**全部失败**才抛。
     *
     * [bind] 可注入 ⇒ 单测不碰真实 socket，也能覆盖"钉的端口被占 → 退临时"这条路径。
     */
    fun bind(preferred: Int, bind: (Int) -> ServerSocket): ServerSocket {
        var last: IOException? = null
        for (candidate in candidates(preferred)) {
            try {
                return bind(candidate)
            } catch (ioe: IOException) {
                if (last == null) last = ioe
            }
        }
        throw last ?: IOException("no candidate port to bind")
    }

    /** 真实绑定：绑回环地址。抽出来只为让 [bind] 的默认实现可读。 */
    fun bindLoopback(candidate: Int): ServerSocket =
        ServerSocket(candidate, 50, InetAddress.getByName("127.0.0.1"))
}
