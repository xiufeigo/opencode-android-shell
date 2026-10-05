# UX2_TERMINAL_REATTACH — 终端做成「杀 app 回来自动接回同一会话」

> 轮次：**UX2 终端会话续接**（派单：产品负责人「终端那条我期望你做成 自动重连同一会话」）
> 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
> 写权限：`webapp/**`、测试文件、`research/**`（**未碰** `app/**` / `_upstream/**`（只读）/ `.github/**`）
> 设备：`emulator-6510`（`OC_Deliver_R2`，端口 6510，**本轮自建**，已按规矩关闭）

## 0. 结论一句话

**上游早就有"持久化 pty id + 进终端页自动 attach"三件套；真正让"杀 app 回来接不上"的，是宿主把页面挂在
`ServerSocket(0)` 的临时端口上 ⇒ WebView 的 origin 每次启动都变 ⇒ `localStorage` 按 origin 隔离 ⇒ 上游那份持久化永远读不到。
本轮的改动只有一句话：把回环端口钉住（`40495`），让 origin 跨启动不变，上游那套机制就原样生效。**

设备实测：**计数 6 →（force-stop）→ 40 → 58，同一个 `pty_10b1ba6ce001SC8UbQhEKcIQqY`，接回成功。**

| 派单项 | 状态 | 证据强度 |
|---|---|---|
| 1. 上游 PTY 创建/列举/连接接口（行号+原文） | ✅ | 源码行号 |
| 1b. 上游有无重连机制 | ✅ **有 ⇒ 复用** | 源码行号 + 设备实证 |
| 1c. 断开后是否保留 PTY | ✅ **保留，无 idle 回收** | 源码行号 + 设备实证（命令在 app 被杀期间继续跑） |
| 2. 实现"自动接回同一会话" | ✅ 闭环 | 源码 diff + 设备前后截图 |
| 3. 绊线 + 反向验证真红 | ✅ 11 条，撤回后 **2 条真红** | 单测输出 |
| 4. 设备实测（杀 app 重开） | ✅ 闭环 | 截图 + logcat 原文 |
| 4b. 会话被回收的边界 | ⚠️ **一半** | 显式关闭已实测；服务端超时回收**未做**（原因见 §6） |
| 5. 三份产物 + 交接文档同步 + 回归 | ✅ | md5/签名/用例数 |

---

## 1. 上游 PTY 机制（行号 + 原文）

> ⚠️ **先纠正派单前提（与上一轮 UX1 的发现一致）**：本仓库 `.git/packed-refs:1-7` **没有 `origin/v2`**
> —— 只有 `origin/main`、`origin/cursor/fix-ime-selection-session-batch`、
> `origin/cursor/opencode-webview-shell-c445`、`origin/native-kotlin-snapshot`。
> 所以 `git show origin/v2:…` 在本仓库**取不到任何东西**。
> 上游源码以 `_upstream/` 目录在盘上（gitignored，**只读**）。
> 下面给的是**盘上路径 + 行号 + 原文**，等价且更可核对。

### 1.1 三个接口：创建 / 列举 / 连接

`_upstream/packages/protocol/src/groups/pty.ts`：

| 用途 | 端点名 | 方法 + 路径 | 行号 |
|---|---|---|---|
| **列举** | `pty.list` | `GET /api/pty` | `:23-35` |
| **创建** | `pty.create` | `POST /api/pty` | `:37-50` |
| 单查 | `pty.get` | `GET /api/pty/:ptyID` | `:52-66` |
| 改标题/尺寸 | `pty.update` | `PUT /api/pty/:ptyID` | `:68-83` |
| 删除（真杀） | `pty.remove` | `DELETE /api/pty/:ptyID` | `:85-99` |
| 取 WS 票据 | `pty.connectToken` | `POST /api/pty/:ptyID/connect-token` | `:101-116` |
| **连接** | `pty.connect` | `GET /api/pty/:ptyID/connect`（WebSocket） | `:120-143` |

**列举原文**（`groups/pty.ts:23-35`）：

```typescript
23:    HttpApiEndpoint.get("pty.list", "/api/pty", {
24:      query: LocationQuery,
25:      success: Location.response(Schema.Array(Pty.Info)),
26:    })
...
32:          description: "List PTY sessions for a location, including exited sessions retained until removal.",
```

**创建原文**（`groups/pty.ts:37-41`）：

```typescript
37:    HttpApiEndpoint.post("pty.create", "/api/pty", {
38:      query: LocationQuery,
39:      payload: Pty.CreateInput,
40:      success: Location.response(Pty.Info),
41:    })
```

**连接要单次短命票据**（`groups/pty.ts:9-19`、`:117-142`）：

```typescript
 9: export const PTY_CONNECT_TICKET_QUERY = "ticket"
10: export const PTY_CONNECT_TOKEN_HEADER = "x-opencode-ticket"
...
17: export function hasPtyConnectTicketURL(url: URL) {
18:   return PTY_CONNECT_PATH.test(url.pathname) && !!url.searchParams.get(PTY_CONNECT_TICKET_QUERY)
19: }
```

```typescript
117:  .add(
118:    // Query fields are decoded in the raw handler after the existence check so a missing
119:    // session responds with an empty 404 before any upgrade work.
120:    HttpApiEndpoint.get("pty.connect", "/api/pty/:ptyID/connect", {
...
131:          "x-websocket": true,
```

### 1.2 后端在客户端断开后**保留** PTY，**没有 idle 回收**

- 断开策略就是"全摘"：`_upstream/packages/server/src/handlers/pty-socket.ts:3-8`
  `Effect.raceFirst(drain, socket).pipe(Effect.ensuring(Effect.sync(detach)))`
- `detach` **只摘订阅者、不碰进程**（`core/src/pty.ts:302-307`）：
  `subscriber.detached = true` … `session.subscribers.delete(token)`
- 杀进程只有 `teardown`（`core/src/pty.ts:115-132`），只由显式 `DELETE`（`pty.remove`）
  或**服务端进程退出时的 finalizer**（`core/src/pty.ts:126-132`）触发
  ⇒ **没有任何 idle 超时回收路径**。
- `groups/pty.ts:32` 的描述自证："including exited sessions **retained until removal**"。

**⇒ 后端不会因为客户端掉线而让长命令停。** 设备侧也证实了：app 被 `force-stop` 的那段时间里，
计数器在服务端继续跑（§4）。

### 1.3 上游前端**自己就有**「持久化 + 重连」机制 ⇒ **复用，不自造**

**持久化**（`_upstream/packages/app/src/session/terminal/context.tsx`）：

```typescript
14: const PTY = Persistence.struct({
15:   id: Schema.NonEmptyString,
16:   title: Persistence.fallback(Schema.String, () => ""),
...
22:   cursor: Persistence.optional(Schema.Finite),
23: })
```

```typescript
104:   const [store, setStore, _, ready] = persisted(terminalPersistTarget(scope, dir), TerminalState, { all: [] })
```

`terminalPersistTarget`（`context.tsx:80-82`）⇒ `Persist.serverWorkspace(scope, dir, "terminal")`；
浏览器/WebView 侧它落在 **`localStorage`**（`runtime/persistence/storage.ts:509-510`
`if (!config.storage) return localStorageDirect()` / `return localStorageWithPrefix(config.storage)`；
`localStorage` 读写实现见 `storage.ts:297-381`）。

**重连**（`session/terminal/terminal.tsx:573-586`）：

```typescript
573:      const open = async () => {
574:        if (disposed) return
575:        drop?.()
576:
577:        const socket = await serverSDK.pty
578:          .connect({
579:            ptyID: id,
580:            location: { directory },
581:            cursor: seek,
582:          })
```

**"会话已不存在" ⇒ 干净降级**（`terminal.tsx:543-552`）：

```typescript
543:      const gone = async () => {
544:        return serverSDK.api.pty
545:          .get({ ptyID: id, location: { directory } })
546:          .then((result) => result.data.status === "exited")
547:          .catch((err) => {
548:            if (err && typeof err === "object" && "_tag" in err && err._tag === "PtyNotFoundError") return true
```

⇒ `pty.get` 的 `PtyNotFoundError`（404）**已被上游当成"会话没了"**处理，不需要我们再造。

**⇒ 判定：上游已有「同 workspace 持久化 pty id + 进终端页自动 attach + 404 干净降级」三件套**
**（`context.tsx:104` / `terminal.tsx:573-586` / `terminal.tsx:543-552`）**
**⇒ 本轮不自造列表/重连 UI。**

> 补充一处实测发现：设备上首次开终端时 logcat 出现了 `GET /api/pty`
> （见 §4.2），即上游在这条路径上**确实**打到 `pty.list`；但 grep 源码时
> `packages/app/src` 里 `pty.list` 零命中、只有 `pty.create`/`pty.remove`/`pty.get`。
> 我**不替这一处矛盾下结论**（可能是 SDK 层或另一分支构造的调用），
> 两种情况都不影响本轮结论：**我们的改动没碰任何上游调用路径。**

---

## 2. 根因：origin 每次启动都变

`webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/LoopbackProxy.kt:184-188`（**改前原文**）：

```kotlin
184:    fun start() {
185:        if (running.get()) return
186:        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
187:        serverSocket = socket
188:        port = socket.localPort
```

`ServerSocket(0)` = **系统分配临时端口**，`port` 逐次不同。而 `publicOrigin()`
（`LoopbackProxy.kt:175-176`）：

```kotlin
175:    /** 页面与 API 的**共同** origin。 */
176:    fun publicOrigin(): String = "http://127.0.0.1:$port"
```

⇒ WebView 的 origin `http://127.0.0.1:<随机端口>` **每次启动都不一样**
⇒ `localStorage` 按 origin 隔离 ⇒ **上游存下来的 pty id 根本读不到**
⇒ 杀 app 再打开必然是"新建一个空终端"。

上一轮 UX1 的 logcat 正好是两个不同 origin 的实证：未配置轮没有 origin，
配置轮是 `home=http://127.0.0.1:40393/`（`40393` 是回环端口）；后端 `4095` 才是固定的。

**因果链（完整）**：
`ServerSocket(0)` 临时端口 → origin 每次变 → 上游 `localStorage` 里的 `pty id` 读不到
→ 进终端页只能 `pty.create` 新建 → 长命令断在那里。

---

## 3. 实现（diff + 依据）

### 3.1 择优结论：复用上游，我们只**解开路障**

| 候选做法 | 判断 | 依据 |
|---|---|---|
| **A. 钉住回环端口，让上游机制生效** | ✅ **采用** | 改动面最小（一个 bind 调用）；**不碰上游任何语义**；不新增持久化；不碰凭据 |
| B. 宿主侧自存 "上次的 pty id"，进终端页手动 seed 上游 localStorage | ❌ 不采用 | 要伪造上游的 storage key + `TerminalState` schema（`context.tsx:34-55` 那个 transform 很脆）；上游已有同款持久化，**做两份反而会打架** |
| C. 自造终端列表 / 重连 UI | ❌ 不采用 | 派单明令"有就复用"；且上游 `context.tsx:104` 已经做了 |

### 3.2 diff

**新增** `webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/StableOrigin.kt`（纯逻辑，无 Android 依赖）：

```kotlin
object StableOrigin {
    /** 默认钉住的回环端口。>1023（非特权）、不与后端 4095 相同。 */
    const val DEFAULT_PREFERRED_PORT = 40_495

    fun candidates(preferred: Int): List<Int> =
        if (isPinned(preferred)) listOf(preferred, 0) else listOf(0)

    fun isPinned(preferred: Int): Boolean = preferred > 0

    fun origin(port: Int): String = "http://127.0.0.1:$port"

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
}
```

**改** `LoopbackProxy.kt`（构造参数新增一项 + bind 一行）：

```diff
@@ class LoopbackProxy(
     private val renderMode: String = "full",
+    /**
+     * UX2 · 回环代理**钉住**的端口（0 = 沿用系统临时端口）。
+     * 页面 origin = publicOrigin = http://127.0.0.1:<port>，而 localStorage 按 origin 隔离。
+     * 上游"持久化 pty id + 自动重连"就存在 localStorage 里 ⇒ 端口一变，那套机制就永远读不到。
+     * 被占用时 StableOrigin.bind 自动退回临时端口（不会起不来）。
+     */
+    private val preferredPort: Int = StableOrigin.DEFAULT_PREFERRED_PORT,
 ) : Closeable {

@@ fun start() {
     if (running.get()) return
-    val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
+    // UX2：先钉 [preferredPort]，被占才退回临时端口（见 [StableOrigin]）。
+    val socket = StableOrigin.bind(preferredPort) { candidate ->
+        ServerSocket(candidate, 50, InetAddress.getByName("127.0.0.1"))
+    }
     serverSocket = socket
     port = socket.localPort
```

### 3.3 红线复核

| 红线 | 怎么保证 | 证据 |
|---|---|---|
| **不许**让"没配服务器也能用" | `StableOrigin` **不认识口令**、不碰加载路径；`start()` 只在 `LaunchGuard` 放行后被调用，**闸门与调用点都没动** | 绊线 `stableOriginDoesNotWeakenTheEmptyPasswordGate` 断言 `StableOrigin.kt` 不含 `mayLaunch`/`loadUrl`/`SecretStore`，且 `mayLaunch` 下标 **早于** `.start()` |
| **不许**把凭据/口令写进任何持久化 | 本轮**没有新增任何持久化**；`StableOrigin` 里只有一个端口号 | 源码（`StableOrigin.kt` 全文无 `SecretStore`/`password`） |
| 用已有存储通道、只存标识 | **不需要**——因为复用上游的 `localStorage`，我们不重复存 | §3.1 |
| 会话被回收要干净新建 | 走上游既有 `terminal.tsx:543-552`（`PtyNotFoundError` ⇒ `gone()`） | §6 |

---

## 4. 设备实测（核心证据）

### 4.1 装机与起点

- 设备：`emulator-6510`（`OC_Deliver_R2`，端口 6510，本轮自建；几何 `wm size 1080x2400` + `wm density 420`）
- 装包：`research/deliver/opencode-shell-web.apk`（md5 `69bbe05f772250888d4e1dac4faf9ce0`）`install -r -t` = `Success`
- 口令沿用该 AVD 上已有的 `SecretStore`（未 `pm clear`）⇒ logcat `passwordPresent=true`
- 起一个可辨认的长命令（PowerShell，因为**终端跑在服务端那台 Windows 机器上**，见下）：

```
PS C:\sensorsdata\main\program\opencode-android-shell> $i=0; while ($true) { $i++; Write-Output UX2MARK-$i; Start-Sleep -Seconds 2 }
```

> 顺带一条**决定性的新证据**（UX1 只给了源码判定）：终端提示符是
> **`PS C:\sensorsdata\main\program\opencode-android-shell>`**，
> 且报 `Windows PowerShell / Copyright (C) Microsoft Corporation`
> ⇒ **终端是服务端（Windows 主机）的 shell，不是设备上的**。
> （首次尝试敲 bash 语法 `while [ 1 ]; do …`，服务端直接报 PowerShell 解析错，
> 反而把这个事实坐实了。）

### 4.2 杀掉前

截图 `research/web/ux2/ux2_07_before_kill.png` 原文（可辨认计数在涨）：

```
UX2MARK-1
UX2MARK-2
UX2MARK-3
UX2MARK-4
UX2MARK-5
UX2MARK-6
```

**PTY 建立过程的 logcat 原文**：

```
10-05 08:08:43.455  3037  3037 I OcWebHost: … http://127.0.0.1:40495/api/pty @19
10-05 08:08:43.848  3037  3037 I OcWebHost: … /api/pty/pty_10b1ba6ce001SC8UbQhEKcIQqY @19
10-05 08:08:43.940  3037  3037 I OcWebHost: … /api/pty/pty_10b1ba6ce001SC8UbQhEKcIQqY/connect-token @19
10-05 08:08:44.134  3037  3037 I OcWebHost: … /api/pty/pty_10b1ba6ce001SC8UbQhEKcIQqY @19
```

⇒ **pty id = `pty_10b1ba6ce001SC8UbQhEKcIQqY`**，origin = `http://127.0.0.1:40495`（**钉住了**）。

### 4.3 真杀

```
> adb -s emulator-6510 shell am force-stop com.xiufeigo.opencodeshell.web.debug
> adb -s emulator-6510 shell pidof com.xiufeigo.opencodeshell.web.debug
pid-after-kill(empty=ok)
```

### 4.4 重开 ⇒ **origin 没变**

```
10-05 08:11:50.758  3445  3445 I OcWebHost: home=http://127.0.0.1:40495/ backend=http://127.0.0.1:4095 passwordPresent=true
```

⇒ **与杀之前逐字相同**（改前这里会是另一个随机端口）。

### 4.5 进终端 ⇒ **接回同一个 pty id**

```
10-05 08:12:16.427  3445  3445 I OcWebHost: … /api/pty/pty_10b1ba6ce001SC8UbQhEKcIQqY @19
10-05 08:12:16.427  3445  3445 I OcWebHost: … /api/pty/pty_10b1ba6ce001SC8UbQhEKcIQqY/connect-token @19
10-05 08:12:16.458  3445  3445 I OcWebHost: … /api/pty/pty_10b1ba6ce001SC8UbQhEKcIQqY @19
```

⇒ **同一个 `pty_10b1ba6ce001SC8UbQhEKcIQqY` 被重新 attach（`pty.get` + `connect-token` + connect）。**

### 4.6 计数对比（前 / 后 / 再后）

| 时点 | 截图 | 计数原文 | pty id |
|---|---|---|---|
| 杀之前 | `ux2_07_before_kill.png` | `UX2MARK-1 … UX2MARK-6` | `pty_10b1ba6ce001SC8UbQhEKcIQqY` |
| **重开后** | `ux2_09_after_terminal.png` | `UX2MARK-3 … UX2MARK-40` | **同一个** `pty_10b1ba6ce001SC8UbQhEKcIQqY` |
| 再过 20 s | `ux2_10_still_running.png` | `UX2MARK-21 … UX2MARK-58` | 同一个 |

**⇒ 接回同一会话，长命令的计数接着涨：6 → 40 → 58。**
最后一张专门用来排除"只是静态重放历史缓冲"：20 秒内又涨了 18 格（≈2 s/格），说明是**活着在续流**。

> 如实记录一个输入保真问题：该镜像软键盘**会丢字符**（上一轮 F5 已记录）。
> 这次实测里 `$i=0` 的 `0` 被吃掉，敲成 `$i=`；PowerShell 把未定义的 `$i` 当 0，
> **计数行为不受影响**，所以不影响结论。（这也是本轮唯一一次输入失真。）

---

## 5. 绊线与反向验证

新增 `webapp/src/test/java/com/xiufeigo/opencodeshell/web/TerminalReattachTest.kt` = **11 条**：

| 用例 | 断言什么 |
|---|---|
| `pinnedPortIsPreferredOverEphemeral` | 候选顺序 `[40495, 0]` |
| `zeroPreferredDegradesToEphemeralOnly` | 传 0 ⇒ 只剩临时端口 |
| `ephemeralOnlyMeansOriginIsNotGuaranteedStable` | 只走临时端口时**不得**声称稳定 |
| `bindActuallyUsesThePinnedPortWhenFree` | 端口空闲 ⇒ **只调一次**且用的就是钉的端口 |
| `bindFallsBackToEphemeralWhenPinnedPortIsTaken` | 端口被占 ⇒ `[taken, 0]`，**不抛** |
| `bindThrowsOnlyWhenEveryCandidateFails` | 全失败才抛，且**透传**原异常信息 |
| `originMatchesLoopbackProxyPublicOrigin` | origin 字符串同构 |
| `defaultPreferredPortIsUnprivilegedAndNotTheBackendPort` | `>1023` 且 `!= 4095` |
| `loopbackProxyNoLongerBindsEphemeralPortZero` | **源码接线**：不得再出现 `ServerSocket(0,` |
| `loopbackProxyStartGoesThroughStableOriginBind` | **源码接线**：`start()` 必须走 `StableOrigin.bind(preferredPort)` |
| `stableOriginDoesNotWeakenTheEmptyPasswordGate` | **红线**：`StableOrigin` 不含 `mayLaunch`/`loadUrl`/`SecretStore`；闸门下标 < 代理启动下标 |

### 5.1 反向验证：**真红**（撤回改动的实测输出）

把 `LoopbackProxy.start()` 改回 `ServerSocket(0, 50, …)` 后重跑：

```
> Task :webapp:testDebugUnitTest FAILED
TerminalReattachTest > loopbackProxyNoLongerBindsEphemeralPortZero FAILED
    java.lang.AssertionError at TerminalReattachTest.kt:137
TerminalReattachTest > loopbackProxyStartGoesThroughStableOriginBind FAILED
    java.lang.AssertionError at TerminalReattachTest.kt:146
202 tests completed, 2 failed
BUILD FAILED
```

⇒ **两条接线绊线同时变红**，随后已把改动恢复（并复跑至绿）。

### 5.2 本轮跑红过两次，都是新测试自己的 bug（如实记录）

1. `bindActuallyUsesThePinnedPortWhenFree` 断言 `assertTrue(got.isBound)` ——
   那个 socket 是 `object : ServerSocket()` 的**未绑定**替身，`isBound` 恒 false。改用 `assertSame(fake, got)`。
2. `bindThrowsOnlyWhenEveryCandidateFails` 同样断言了 `assertTrue(fake.isClosed)`（未关闭的替身）——删掉该无意义断言。

**两处都只改了测试的精确性，没有删/放宽任何既有断言**（新增文件 11 条全在）。

---

## 6. 会话被回收时的边界行为

| 情形 | 行为 | 证据 |
|---|---|---|
| **用户显式关掉终端标签**（= 显式 `DELETE`） | 干净新建：立刻生成**新** pty id，不卡死、不报错、不崩 | ✅ 已实测 |
| **服务端超时回收**（本地记的 id 失效） | 走上游既有 `terminal.tsx:543-552`：`pty.get` → `PtyNotFoundError` ⇒ `gone()` 为真 ⇒ 放弃重连；新开终端时 `pty.create` 干净新建 | ⚠️ **未在设备上实测**，见下 |

**显式关闭的实测原文**：

```
# 点 × 关掉唯一的终端标签，再点开 Terminal
$ logcat … | grep -oE 'pty_[a-zA-Z0-9]+' | sort | uniq -c
      5 pty_10b1ba6ce001SC8UbQhEKcIQqY
      2 pty_10b20712e001ob3s0lmgaa5TJX     ← 新建的
```

截图 `ux2_11_new_pty.png` / `ux2_12_reopened.png`：**回到 Session 标签页、无错误页**；
重新开出来后是一个**全新**的 PowerShell 提示符（`ux2_12_reopened.png` 原文
`Windows PowerShell / Copyright (C) Microsoft Corporation. All rights reserved.`
+ `PS C:\sensorsdata\main\program\opencode-android-shell>`）⇒ **干净新建、不崩**。

**⚠️ 服务端超时回收这一格：未做 + 原因**

- 本部署的后端**根本没有 idle 回收策略**（§1.2：只有显式 `DELETE` 和进程退出两条 kill 路径），
  所以**等不到超时**；
- 派单**禁止起停后端**（`127.0.0.1:4095` 必须保持就绪），
  而"重启后端让 finalizer 回收全部 PTY"是唯一能自然制造该状态的合法手段；
- 替代方案（`adb reverse` 指向错端 / 断链）只能制造"连不上"，
  **造不出"服务端记得这个 id 但已删除"**这个状态。

⇒ 因此该格**如实记为未实测**，只保留**源码级**依据（`terminal.tsx:543-552`）
与**本轮改动不引入新失败路径**的事实（我们只改了 bind 端口，没碰任何错误处理）。

---

## 7. 三份新产物（体积 / md5 / 签名 / 含源自查）

重建命令：`.\gradlew.bat :webapp:assembleDebug :webapp:assembleRelease :webapp:assembleReleaseR8`

| # | 交付物（规范名） | 体积 | **md5** | 签名 |
|---|---|---|---|---|
| 1 | `opencode-shell-web.apk`（debug） | **5,437,748 B** | **`69bbe05f772250888d4e1dac4faf9ce0`** | `CN=Android Debug` |
| 2 | `opencode-shell-web-release-nor8.apk` | **3,990,760 B** | **`d6015bf2873fa19776a0e53bb3ab96a0`** | `CN=Android Debug` |
| 3 | `opencode-shell-web-release-r8.apk` | **1,445,812 B** | **`28ee9ffc886da26c40c903d50eb03e12`** | `CN=Android Debug` |

三份证书 SHA-256 同为 `e86b70066546bd0bdc2e6cb4d970704bc48b1a244af59c664564f030cca0510a`
（`apksigner verify --print-certs`，三份逐字相同，沿用 `sharedDebug` 证书口径）。

**与上一轮（UX1）对比**：

| 产物 | UX1 | UX2 | 差 |
|---|---|---|---|
| debug | 5,437,748 B | 5,437,748 B | **+0 B** |
| nor8 | 3,989,904 B | 3,990,760 B | **+856 B** |
| r8 | 1,445,416 B | 1,445,812 B | **+396 B** |

> debug 体积**逐字节相同**（同一个 Kotlin 类结构，debug 不压缩差异）；
> 两个 release 变体只多了 `StableOrigin` 那几百字节 ⇒ 改动的体量符合预期。

### 7.1 包内含源自查（三份逐份）

判据：dex 里**运行期字面量**必须都在（R8 不会删）。

| 产物 | dex 数 | `StableOrigin` | `http://127.0.0.1:` | `F21 launch BLOCKED` | `UX1 not-configured shell` |
|---|---|---|---|---|---|
| debug | 5 | ✅ | ✅ | ✅ | ✅ |
| nor8 | 2 | ✅ | ✅ | ✅ | ✅ |
| r8 | 1 | ✅ | ✅ | ✅ | ✅ |

> **一处必须说清的判据限制**：`40495` 这个字面量在 dex 里**查不到（present=False）** ——
> 因为它是 **int 常量**（`const val`），被编译成 dex 的整型立即数，**不是字符串**，
> 用 ASCII 搜当然搜不到。这**不是**"实现被 R8 优化掉了"，
> 真正能证明新逻辑在包里的判据是 **`StableOrigin` 这个类名三份全在** + 设备上 origin 真的钉在 40495（§4.4）。

---

## 8. 回归用例数

| 套件 | 用例数 | suites | 失败 | 错误 | 对比 |
|---|---|---|---|---|---|
| `:webapp:testDebugUnitTest` | **202** | 20 | **0** | 0 | UX1 = 191 ⇒ **+11**（本轮新文件 11 条） |
| `:app:testDebugUnitTest` | **6,136** | 677 | **0** | 0 | 与 F21/UX1 **逐字相同**（我没碰 `app/**`） |

**未删除、未放宽任何既有断言；用例数只增不减。**

---

## 9. 交接文档

**我已同步。** `research/web/HANDOFF_F19.md` §3「三个 md5 复核结果」小节已写入
**UX2 三个新 md5** 并**显式作废** UX1 与更早的旧值（见该文件 §3 的 UX2 段落）。

---

## 10. 收尾复核（原文）

自建实例 `emulator-6510`（`OC_Deliver_R2`）本轮起、本轮关：

```
--- before kill: confirm ownership ---
pid=24960 avd=OC_Deliver_R2 MINE=True
--- wm reset ---
Physical size: 2560x1600
Physical density: 320          ← 已无 Override
--- reverse --remove tcp:4095 ---
--- emu kill ---
OK: killing emulator, bye bye
OK
```

复核（25 s 后）：

```
=== OUR AVD residual (OC_*/Medium_Desktop_B) ===
OC_*/Medium_Desktop_B residual = 0
=== ports ===
port 6510 LISTEN=0
port 6512 LISTEN=0
port 6514 LISTEN=0
=== adb devices ===
emulator-5682  emulator-5684  emulator-5700  emulator-5702  emulator-5706   ← 全部是别人的
```

> ⚠️ **最后一次收尾复核**（本报告定稿时）`adb devices` 上还多出
> `emulator-5730` / `emulator-5732` 两台 —— **本轮没对它们执行过任何命令**，
> 属**别的 agent / 用户**在这期间自己起的，如实记录、不代其下结论。=== backend 4095 (must be untouched) ===
OwningProcess : 3748            ← 与开轮前同一 pid
```

- **未执行 `adb kill-server`**，**未用 `-a`/通配符**，`emu kill` **只对 `emulator-6510` 一个 serial** 发过。
- 抢锁：`.agent-gradle-lock` 开轮时不存在 → 写锁 → 前台跑完 → 单条顶层 `rm --` 释放
  （回执 `mavis-trash: moved to trash: '.agent-gradle-lock'`，**未绕��**）。
- 后端 `4095` 全程**未起停**（开轮前与收尾均 `LISTEN pid=3748`），只做 `adb reverse tcp:4095 tcp:4095`。
- 收尾时 `adb reverse --remove tcp:4095` 本轮**成功执行**（前几轮曾被安全策略拦下）。

### 碰过哪些 serial

- **本轮只碰过 `emulator-6510`** 一个 serial。
- **是否碰过非自建实例：否。** 开轮时在列的 `emulator-5682 / 5684 / 5700 / 5702 / 5706`
  **全程未对它们执行过任何命令**，收尾复核时它们**仍在列、未受影响**。
  （本报告全程未对任何 `MCRemote_*` / `DSH_*` / `T7x_*` / `T88_*` / `T90_*` / `T91_*` / `Pixel_*` 发过 kill。）

---

## 11. 未闭环 / 诚实边界

1. **服务端超时回收那一格未在设备实测**（§6）：本部署没有 idle 回收，且禁止起停后端 ⇒ 等不到、也造不出。
   只保留源码级依据 `terminal.tsx:543-552`。
2. **`origin/v2` 在本仓库不存在**（§1）：给的是 `_upstream/` 盘上路径 + 行号，不是 `git show origin/v2:`。
3. **上游 `pty.list` 在前端源码里 grep 零命中，但设备 logcat 出现了 `GET /api/pty`**（§1.3 末）：
   这处矛盾我**没追查清楚**，不替它下结论；不影响本轮结论。
4. **没有新增持久化**，所以"只存标识、不存敏感内容"这条是靠"**不存**"满足的，
   不是靠"存得干净"——若将来上游持久化不可用，才需要回到方案 B（届时必须重新做敏感信息审查）。
5. **`input text` 丢字符**（§4.6 注）导致 `$i=0` 敲成 `$i=`，靠 PowerShell 默认值兜住；不影响结论，但记录在案。
6. 端口钉在 **40495**：若设备上恰好被别的进程占用，会**静默退回临时端口**（退化回改前行为，不会起不来）。
   本轮实测未触发退化（origin 确实是 40495）。
