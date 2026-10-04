# FIX_F4_KEEPSCREEN —— 回合结束后 KEEP_SCREEN_ON 不释放

> 修复工程师：F4 ｜ 日期：2026-10-05 ｜ 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
> 被修对象：D1（`e360904`）的"回合常亮"修复 —— **置起那一跳成功了，"释放"那一跳在正常回合结束路径上从不发生**
> 设备：`emulator-6070`（AVD `OC_Deliver_R2`，`android-35;google_apis_playstore_tablet` + `pixel_tablet`，`wm 1080x2400 @420`）

## 0. 结论速览

| 项 | 结论 |
| --- | --- |
| 根因归类 | **条件不满足**（不是"页面不发信号"的另一种含义，见 §3.4） |
| 改前复现 | ✅ 本轮独立复现：回合 **6 s** 结束，flag 在 **T+25s / +65s / +120s 全 HIT**，+90 s 兜底**重新挂上**（§1） |
| 回合结束 ⇒ 释放 | ✅ **成立**（§5）：`roundActive=false flagSet=false` @+59.2 s；+90 s 兜底读到 `false` 不再挂回；T+122 s `dumpsys window` = **MISS** |
| 离开前台 ⇒ 释放 | ✅ **成立**（§6）：回合中按 HOME，4 s 内 `HIT → MISS` |
| 回合中途 force-stop ⇒ 释放 | ⚠️ **本轮无设备原文**（§6），不冒充已验证 |
| 绊线反向验证 | ✅ **真红**（§7.2）：撤回释放后 2 个用例 `FAILED`；恢复后 **128 tests / 0 failures** |
| 释放机制归因 | ⚠️ 设备上实际生效的是**"心跳不再续命 ⇒ 既有 35 s 判据首次可达"**；我新增的 `ROUND_END` 标记快路径**未被观测到触发**（§5.4） |

---

## 1. 复现（改前）—— 本轮自己测的，不是引用

装的是**本轮改动之前**的包：`research/deliver/opencode-shell-web-release-nor8.apk`。
⚠️ 诚实标注：这份 APK 的 `size=3968173` / `md5=5AA0B63657CB599CD288EBC5597E9D21`，
**与 V5 §1.1 记的那份（`3965033` / `b063148df53a1364bf5c42ce6bdca0d3`）不是同一个文件**
—— 期间被别的轮次重建过。但它**同样不含本轮 F4 的任何改动**（本轮 F4 是在 04:3x 之后才动源码的），
所以它仍然是有效的"改前"物。

走官方 UI 发送按钮发真消息 `F4KEEPRELEASE`（模型 `MiMo-V2.6-Pro`），回合 **6 s 正常结束**，
输入框回到占位符 `Ask anything, / for commands, @ for context...` ⇒ **回合确实结束了**
（截图 `research/env/f4/before_stillset.png`）。

### 1.1 `dumpsys window` 前后原文（`fl=...KEEP_SCREEN_ON` 命中与否）

| 时点 | 原文判定 | 设备时间 |
| --- | --- | --- |
| 回合前（基线） | `windowKS=MISS` | 04:32:47 |
| 回合中 T+5s | `windowKS=HIT` | 04:33:56 |
| **回合结束后 T+25s** | **`windowKS=HIT`** | 04:34:16 |
| 回合结束后 T+65s | `windowKS=HIT` | 04:34:41 |
| **回合结束后 T+120s** | **`windowKS=HIT`** | 04:35:52 |

### 1.2 `dumpsys power` 原文（T+120s）

```
  mWakefulness=Awake
  mHoldingDisplaySuspendBlocker=true
```

### 1.3 `D1 applyKeepScreenOn` 完整时间线（一条不漏）

```
10-04 20:33:50.846  3172  3172 I OcWebHost: D1 applyKeepScreenOn: roundActive=true foreground=true shouldKeep=true flagSet=true
10-04 20:35:20.848  3172  3172 I OcWebHost: D1 applyKeepScreenOn: roundActive=true foreground=true shouldKeep=true flagSet=true
```

⇒ 第一行是回合置起（20:33:50）；第二行在 **+90.0 s**（20:35:20）——**回合早就结束了，
兜底复查读到 `roundActive=true`，又把 flag 挂了一遍**。这与 V5 §6.3 的
`+90/+180/+270 s` 三行是同一个形状。**屏幕被永久钉亮，纯耗电，复现成功。**

---

## 2. D1 的修法为什么只做了一半

D1 把"回合活跃"从页面搬到了宿主（`RoundActivityTracker`），并把**置起**接到了真实流量上
（页面 POST 消息 ⇒ `onRoundStart`）。所以置起成功。但**释放**只有一条生产路径，
而那条路径的条件在任何流退出那一刻都**不可能成立** —— 详见 §3。

---

## 3. 根因（行号原文）—— 归类：**条件不满足**

### 3.1 页面那侧：**没有**"回合结束"信号（这一条要先排除）

```
$ Get-ChildItem _upstream\packages -Recurse -Include *.ts,*.tsx,*.go,*.svelte -File |
    Select-String -Pattern "__ocAndroid|setKeepScreenOn" -List
（零命中）
```

⇒ 上游官方 UI **从不调** `setKeepScreenOn`、**从不碰** `window.__ocAndroid`。
**"页面不发信号"这一条成立**，但它**不是**本缺陷的根因 —— 因为 D1 早就把回合判定搬到宿主了。

### 3.2 回合结束**确实有**权威信号，只是宿主没在字节层认它

上游协议里有明确的回合状态事件（**权威依据，带行号**）：

`_upstream/packages/ai/node_modules/@opencode/schema/src/session-status-event.ts`
```
  8-13 :  Schema.Struct({ type: Schema.Literal("idle") })        ← 回合结束
 27-32 :  Schema.Struct({ type: Schema.Literal("busy") })         ← 回合进行中
 43-49 :  export const Idle = Event.ephemeral({ type: "session.idle", ... })   ← 已废弃但仍在发
```

这两个标记都在**回环代理搬的字节里**。宿主完全看得见，只是没去认。

### 3.3 宿主接了，但条件恒不成立 —— **这就是根因**

`webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/LoopbackProxy.kt:707-716`（改前原文）：
```kotlin
        if (roundActivity.isRoundActive(System.currentTimeMillis()) &&
            roundActivity.idleMs(System.currentTimeMillis()) >= SSE_IDLE_READ_TIMEOUT_MS
        ) {
            if (roundActivity.onRoundEnd()) publishRoundActive()
        }
```
`SSE_IDLE_READ_TIMEOUT_MS = 35_000L`（`LoopbackProxy.kt:104`）。
**这是"回合结束 ⇒ 释放"的唯一生产触发点。**

而 `idleMs` 会被谁刷新？`LoopbackProxy.kt:692-693`（改前原文）：
```kotlin
            // D1：搬到了字节 = 回合还活着，续命（防 90s 兜底超时把活跃回合判死）。
            if (roundActivity.onRoundActivity(System.currentTimeMillis())) publishRoundActive()
```
⇒ **任何**流上的**任何**字节都续命。`RoundActivityTracker.kt:137-142`（改前原文）：
```kotlin
    fun onRoundActivity(nowMs: Long): Boolean {
        if (roundStartAtMs.get() == 0L) return false
        val changed = nowMs - lastActivityAtMs.get() >= roundHoldTimeoutMs
        lastActivityAtMs.set(nowMs)
        return changed
    }
```
官方 UI 常驻一条 `GET /event`，其 ~10 s 心跳同样经 `copyStreaming` 搬字节
⇒ `lastActivityAtMs` 被持续刷新 ⇒ **`idleMs` 永远 < 10 s**。

**所以：`idleMs >= 35_000` 在任何流退出那一刻都不成立 ⇒ `onRoundEnd()` 不跑 ⇒ 不释放。**
同一条心跳也把 90 s 兜底 `roundHoldTimeoutMs`（`RoundActivityTracker.kt:61,110`）顶到永远不到期
⇒ `isRoundActive` 恒为 `true` ⇒ §1.3 里 +90 s 那行"又挂一次"。

### 3.4 一句话回答任务里的三选一

> **宿主接了，但条件不满足。** 具体条件是 `LoopbackProxy.kt:712-713` 的
> `idleMs >= SSE_IDLE_READ_TIMEOUT_MS`；让它恒假的输入是
> `RoundActivityTracker.kt:137-142`（心跳也在续命）经 `LoopbackProxy.kt:693` 喂进去的字节。
> "页面不发信号"也成立（`_upstream` 零命中），但它只是**为什么宿主必须自己认字节**的理由，
> 不是 D1 失败的原因 —— D1 已经改成宿主自己判了，只是判据选错了输入。

---

## 4. 修法（diff）

### 4.1 思路与取舍

上游 `/event` 上有权威的回合状态事件（§3.2），代理看得见每一个字节 ⇒
**直接在字节层认 `session.status`→`idle` / `session.idle`，回合结束就立刻释放**，
不必等 35 s 静默、不必靠页面配合、不必猜"静置 N 秒"。

同时把**心跳与回合数据分开**：心跳**不续命**。这是"忘关路径"的正解 ——
它让 90 s 兜底重新变成一条**真正可达**的安全网（回合中途出错/被杀/上游不发 idle 时兜底会到期）。

**取舍（明确写出失败方向）**：
- 认标记的失败方向 = **提前释放**（屏幕早灭一次，用户点一下）。可接受。
- 漏标的失败方向 = 退回 35 s 静默判据 + 90 s 兜底 —— 也就是今天的水平，不会更差。
- 不采用"页面静置 N 秒"：那是**没有回合概念**的近似，且 90 s 兜底一旦可达就已经覆盖了它。
- 窗口大小 `SSE_SCAN_WINDOW_CHARS = 2048`：足以覆盖被一次 socket 读切断的标记；
  代价是若某帧负载 > 2 KB 且标记恰在窗口外，本轮会漏判（下一帧即恢复，且兜底仍在）。**如实记录。**

### 4.2 `RoundActivityTracker.kt` 新增（纯逻辑、无 android 依赖 ⇒ 可 JVM 穷举）

新增 `RoundStreamSignal` 枚举 + 三个纯函数 + 一个状态作用点：

```kotlin
    enum class RoundStreamSignal {
        ROUND_END,    // 上游"回合结束"标记
        ACTIVITY,     // 回合自己的数据帧 => 续命
        HEARTBEAT,    // 纯心跳 / 空负载 => 不续命
    }

    fun classifyRoundStream(text: String): RoundStreamSignal = when {
        text.isEmpty() -> RoundStreamSignal.HEARTBEAT
        containsRoundEndMarker(text) -> RoundStreamSignal.ROUND_END
        containsSubstantivePayload(text) -> RoundStreamSignal.ACTIVITY
        else -> RoundStreamSignal.HEARTBEAT
    }

    fun onRoundStreamSignal(nowMs: Long, signal: RoundStreamSignal): Boolean = when (signal) {
        RoundStreamSignal.ROUND_END -> onRoundEnd()
        RoundStreamSignal.ACTIVITY -> onRoundActivity(nowMs)
        RoundStreamSignal.HEARTBEAT -> false
    }

    fun containsRoundEndMarker(text: String): Boolean {
        if (text.contains(ROUND_END_LEGACY_MARKER)) return true
        if (!text.contains(ROUND_STATUS_MARKER)) return false
        return text.contains(STATUS_IDLE_COMPACT) || text.contains(STATUS_IDLE_SPACED)
    }

    fun containsSubstantivePayload(text: String): Boolean = text.contains(SUBSTANTIVE_MARKER)
```

companion 新增常量：
```kotlin
        const val ROUND_END_LEGACY_MARKER: String = "\"session.idle\""
        const val ROUND_STATUS_MARKER: String = "\"session.status\""
        const val STATUS_IDLE_COMPACT: String = "\"type\":\"idle\""
        const val STATUS_IDLE_SPACED: String = "\"type\": \"idle\""
        const val SUBSTANTIVE_MARKER: String = "\"type\""
```

### 4.3 `LoopbackProxy.kt` 接线

新增常量：
```kotlin
internal const val SSE_SCAN_WINDOW_CHARS = 2048
```

新增扫描函数（带**有界尾巴窗口**，让被一次读切断的标记仍能命中；一识别出负载就清空，
防止上一帧的旧标记被重复命中）：
```kotlin
    private fun scanRoundStream(
        window: StringBuilder,
        bytes: ByteArray,
        n: Int,
    ): RoundActivityTracker.RoundStreamSignal {
        window.append(String(bytes, 0, n, Charsets.UTF_8))
        val signal = roundActivity.classifyRoundStream(window.toString())
        if (signal != RoundActivityTracker.RoundStreamSignal.HEARTBEAT) {
            window.setLength(0)
        } else if (window.length > SSE_SCAN_WINDOW_CHARS) {
            window.delete(0, window.length - SSE_SCAN_WINDOW_CHARS)
        }
        return signal
    }
```

`copyStreaming` 里**把原来那句无条件续命换掉**（`LoopbackProxy.kt:692-699` 改后）：
```kotlin
            if (roundActivity.onRoundStreamSignal(
                    System.currentTimeMillis(),
                    scanRoundStream(scanWindow, buf, n),
                )
            ) {
                publishRoundActive()
            }
```

**三条要求的落点**：
1. **回合正常结束 ⇒ 释放**：`ROUND_END` → `onRoundEnd()` → `publishRoundActive()` → `applyKeepScreenOn(false)` → `clearFlags`。
2. **回合中途出错/被杀 ⇒ 释放**：心跳不再续命 ⇒ 90 s 兜底重新可达（`isRoundActive` 过期即返回 false）⇒ 宿主 90 s 复查时 `clearFlags`；另有 `onPause` 立即清（见 §6）。
3. **离开前台 ⇒ 释放**：`PowerPolicy.shouldKeepScreenOn(foreground, roundActive) = foreground && roundActive`（`PowerPolicy.kt:51-52`），`WebHostActivity.kt:845-849` 的 `clearFlags` 路径本就无条件，这条**未改动**、由 §6 设备复核。

---

## 5. 设备正向对照（改后）—— 同一台设备、同一会话、同一模型

被测物：`webapp-debug.apk`（`md5=90690556FB509B3DA6ED47FDF44F1365` / 5,201,814 B，本轮 `assembleDebug`），
`adb install -r` 覆盖安装（**同包名 ⇒ app 数据与口令保留，未重走设置向导**）。
回合消息 `F4AFTERFIX`，模型 `MiMo-V2.6-Pro`，**4 s 完成**，输入框回占位符
（截图 `research/env/f4/after_turnstate.png`）。

### 5.1 `dumpsys window` 采样（`fl=...KEEP_SCREEN_ON`）

| 时点 | 原文判定 | 设备时间 |
| --- | --- | --- |
| 启动后基线（无回合） | `windowKS=MISS` | 04:39:00 前后 |
| 回合中 T+6s | `windowKS=HIT` | 04:41:42 |
| 回合中 T+12s / +18s / +24s / +30s / +36s / +42s / +48s / +54s | `HIT` ×7 | 04:41:48 – 04:42:30 |
| **回合结束后 T+82s** | **`MISS`** | 04:42:57 |
| 回合结束后 T+90s / +98s / +106s / +114s / +122s | `MISS` ×5 | 04:43:05 – 04:43:53 |

### 5.2 `D1 applyKeepScreenOn` 时间线（**这是决定性的一组对照**）

```
10-04 20:41:35.058  3551  3551 I OcWebHost: D1 applyKeepScreenOn: roundActive=true  foreground=true shouldKeep=true  flagSet=true
10-04 20:42:34.274  3551  3551 I OcWebHost: D1 applyKeepScreenOn: roundActive=false foreground=true shouldKeep=false flagSet=false
10-04 20:43:05.060  3551  3551 I OcWebHost: D1 applyKeepScreenOn: roundActive=false foreground=true shouldKeep=false flagSet=false
```

- 20:41:35 = 回合置起。
- **20:42:34（+59.2 s）= 释放**，`flagSet=false`。
- 20:43:05 = **+90 s 兜底复查**读到 `roundActive=**false**`，于是保持释放。

### 5.3 改前 / 改后 逐条对照

| 项 | 改前（§1.3） | 改后（§5.2） |
| --- | --- | --- |
| 回合置起 | `flagSet=true` @20:33:50 | `flagSet=true` @20:41:35 |
| 回合结束后是否释放 | ❌ **全程无 `roundActive=false`** | ✅ `flagSet=false` @20:42:34（+59.2 s） |
| +90 s 兜底复查读到 | **`roundActive=true` → 又挂一次** | **`roundActive=false` → 保持释放** |
| 回合结束 2 分钟时 `dumpsys window` | **HIT**（T+120s） | **MISS**（T+122s） |

⇒ **同一台设备、同一会话、同一模型、同一采样脚本，flag 从"永不释放"变成"回合结束后释放且不再被兜底挂回"。**

### 5.4 ⚠️ 如实说明：设备上释放的是哪一跳

设备实测能定位到的释放是 **+59.2 s**，而不是"回合结束即刻"。这个时刻与
**`idleMs >= 35_000` 的既有判据在一次流退出时成立**吻合（回合约 20:41:41 结束，
35 s 后约 20:42:16 起该条件持续为真，随后的流退出即触发 `onRoundEnd()`）。
**换言之：本轮真正让它发生的，是"心跳不再续命"这一改动让 `LoopbackProxy.kt:712-713`
那个本来就写好的判据**第一次变得可达**。

我新增的 `ROUND_END` 标记快路径（`session.status`→`idle` / `session.idle`）**在设备上
没有观测到触发**：唯一一次抓到 `/api/event` 真实帧的采样只拿到
```
data: {"id":"evt_108a8ee4c001MTBI05NSoSonrD","type":"server.connected","data":{}}
: heartbeat
```
（那次采样恰好赶在发送按钮没点中的那一轮，**没有截到回合期间的帧**）。
所以**我不能说"标记快路径已验证生效"** —— 只能说：它有单测覆盖（§7），
在本轮设备证据里**未被观测到**，实际生效的是"心跳不续命 ⇒ 35 s 判据可达"这一条。
**这已满足"回合正常结束 ⇒ 释放"的验收要求，但机制归因必须按上面这句写，不能拔高。**

顺带确认了心跳形态：它是 SSE **注释行** `: heartbeat`，
我的 `HEARTBEAT` 判据（帧内无 `"type"` ⇒ 心跳）**正确命中**这种形态。

---

## 6. 安全网：回合中途被打断 / 离开前台 ⇒ 也要释放

**场景 = 回合进行中按 HOME（离开前台）**。设备原文：

```
SENT 04:46:08 (composer had text, keyboard down)
T+4s  in-turn          windowKS=HIT        ← 回合进行中，flag 在
HOME 04:46:12                              ← 按 HOME
T+8s  backgrounded     windowKS=MISS       ← 4 s 内已释放
T+14s backgrounded     windowKS=MISS       ← 仍是释放
```

对应的 `D1` 原文（只记置起那一行，释放走的是 `onPause` 的无条件 `clearFlags`，
那条路径不打印 `D1` 行 —— 这与 `WebHostActivity.kt:845-849` 的实现一致）：
```
10-04 20:46:07.343  3551  3551 I OcWebHost: D1 applyKeepScreenOn: roundActive=true foreground=true shouldKeep=true flagSet=true
```

⇒ **离开前台 ⇒ 释放，成立（设备原文）。** 这一条由 `PowerPolicy.kt:51-52`
（`shouldKeepScreenOn = foreground && roundActive`）+ `WebHostActivity.onPause` 的
`clearFlags` 保证，**本轮未改动该逻辑**，F4 只是把前一条（回合结束）补上；
单测侧另有 `leavingForegroundAlwaysClearsTheFlag` 覆盖四种组合。

**回合中途 force-stop / 渲染进程被杀**：本轮**未在设备上单独取证**。
可推知的部分是：这类情况下窗口本身被销毁，flag 随之消失；
且心跳不再续命后，宿主 90 s 兜底也会把回合结算掉。**但本轮没有设备原文，故不写成"已验证"。**

---

## 7. 绊线与反向验证

### 7.1 新增绊线（只增不减）

新增 `webapp/src/test/java/com/xiufeigo/opencodeshell/web/host/RoundStreamSignalReleaseTest.kt`，
**13 个用例**，其中最关键的是把 V5 的时间线**变成可执行断言**：
`v5TimelineHeartbeatsThenIdleMarkerReleasesTheFlag`（4 分钟里心跳驱动的回合活动必须为 0 次，
且回合结束后 4 分钟仍是释放态）。

另有既有用例文件 `RoundActivityTrackerTest`（17 个）、`PowerPolicyTest`、
`KeepScreenOnWiringTest`（14 个）全部保持通过。

### 7.2 反向验证（**必须真红 —— 确实红了**）

把 `RoundActivityTracker.onRoundStreamSignal` 的 `HEARTBEAT` 分支
从 `false` 改回**改前的语义**（心跳也续命）：

```kotlin
// RoundActivityTracker.kt:207（撤回后）
RoundStreamSignal.HEARTBEAT -> onRoundActivity(nowMs)
```

跑 `RoundStreamSignalReleaseTest`，设备无关的纯逻辑当场变红：
```
MUTATED line 207: RoundStreamSignal.HEARTBEAT -> onRoundActivity(nowMs)
RoundStreamSignalReleaseTest > heartbeatFramesMustNotRefreshTheIdleClock FAILED
RoundStreamSignalReleaseTest > heartbeatOnlyTrafficCannotHoldTheFlagForever FAILED
13 tests completed, 2 failed
BUILD FAILED
```

⇒ **绊线是有效的**：撤回释放 ⇒ 真红。恢复后全量重跑 **0 失败**（§7.3）。
**没有为通过放宽任何断言**；上述 2 个用例在恢复后即通过。

### 7.3 回归

`:webapp:testDebugUnitTest`（全量，含既有用例）**0 失败**（原文见 §9）。
`:app:testDebugUnitTest` 本轮**未跑**（F4 未改 `app/**`，且 `app` 模块的常亮相关类不在本轮改动面内）
—— **如实标注为"本轮未跑"，不冒充通过。**

---

## 8. 新产物（体积 / md5 / 签名指纹）

本轮改了 `webapp/**`，**必须重建，已重建**：

| 产物 | 体积 | md5 | sha256 |
| --- | --- | --- | --- |
| `research/deliver/opencode-shell-web-release-nor8.apk` | **3,968,477 B** | `BC7F30180775485CE887DC74E4489C72` | `9BAF43F1DF6CA0C5CE73995011242D5C9D94B9824034BD05FE44A9F9A503F205` |
| `webapp-debug.apk`（本轮设备实测物，**未进 deliver/**） | 5,201,814 B | `90690556FB509B3DA6ED47FDF44F1365` | — |

**签名指纹**（`apksigner verify --print-certs`，与上一版**同一张证书**）：
```
Signer #1 certificate DN: C=US, O=Android, CN=Android Debug
Signer #1 certificate SHA-256 digest: e86b70066546bd0bdc2e6cb4d970704bc48b1a244af59c664564f030cca0510a
Signer #1 certificate SHA-1 digest: 426b682d492e08b8b5cff4a57bac2bef4070f18d
Signer #1 certificate MD5 digest: a8a01dcb84f4932ae6a181cf2524afbd
```
（仍是 Android Debug 证书 —— **沿用上一版的既有事实，本轮未改签名配置**。）

**上一版对照**：`3,965,033 B` / `b063148df53a1364bf5c42ce6bdca0d3`。
⚠️ **注意**：我 §1 用来复现"改前"的那份 `research/deliver/` 里的 APK
（`3,968,173 B` / `5AA0B63657CB599CD288EBC5597E9D21`）**与上一版文档记的
`b063148…` 不是同一个文件**，期间被别的轮次重建过。两者都**不含**本轮 F4 改动。

### 8.1 包内含源自查（机械比对）

```
APK  assets/www/oc-host.js  md5=2b6ee8db66451f659737f29dcfc96f45  size=16939
WORK webapp/src/main/assets/www/oc-host.js  md5=2B6EE8DB66451F659737F29DCFC96F45  size=16939
APK  assets/www/index.html  md5=af724f814d5889bec018a1ad17763b23  size=17621
WORK webapp/src/main/assets/www/index.html  md5=AF724F814D5889BEC018A1AD17763B23  size=17621
```
⇒ **包内 assets 与工作区源码逐字节一致。**
⚠️ 这份 `oc-host.js`（16,939 B）是**别人未提交的改动**（V5 §3 已点名），
本轮**原样保留、未改它**，故新包也带着它。如需纯净包请其主人决定。

### 8.2 交接文档改动（只改交付物小节）

`HANDOFF_WEB_SHELL.md` §0「当前可交付」**只改了这一小节**：
产物表的 release 行换成 `3,968,477 B` / `bc7f30180775485ce887dc74e4489c72`，
并补 F4 重建记录与新的包内含源自查 md5（`2b6ee8db…`），
同时把 `b063148d…` 列入作废清单。**其余章节未动。**

---

## 9. 收尾复核输出（原文）

### 9.1 谁起谁关

本轮只起了一台：`emulator-6070`（AVD `OC_Deliver_R2`，端口 **6070** — 偶数、≥6070、
避开 6010/6030/6050 段；启动前 6070/6072/6074 全 FREE）。**本轮起、本轮关。**

杀前按 AVD 名确认是自己的：
```
pid=26584 avd=OC_Deliver_R2 port=6070
pid=49992 avd=OC_Deliver_R2 port=6070
```

`wm` 复原（我改过 `wm size` / `wm density`，已 reset）：
```
$ adb -s emulator-6070 shell wm size reset
$ adb -s emulator-6070 shell wm density reset
Physical size: 2560x1600
Physical density: 320
```
⇒ 已回到 AVD 原生 `2560x1600 @ 320`，**无残留 override**。

摘掉自己那条 reverse（只摘自己的，不动共享的 adb server）：
```
$ adb -s emulator-6070 reverse --remove tcp:4095
$ adb -s emulator-6070 reverse --list      # 空
```

关实例（**只按 serial**）：
```
$ adb -s emulator-6070 emu kill
OK: killing emulator, bye bye
OK
```

### 9.2 kill 后复核

```
=== emulator/qemu procs (紧跟 kill) ===
  pid=36760 avd=(no -avd: transient helper?) port=?
  pid=11576 avd=(no -avd: transient helper?) port=?
total emulator/qemu process count = 2
=== OC_*/Medium_Desktop_B residual count ===
  0
=== port 6070 LISTEN ===
  LISTEN count = 0
=== adb devices ===
List of devices attached
          （空）
=== gradle lock ===
.agent-gradle-lock exists = False
```

⚠️ 紧跟 `emu kill` 的那 **2 个进程命令行不带 `-avd`**，是 `emu kill` 自拉的**瞬时拆卸辅助进程**，
按 `HOUSEKEEPING.md` 规则 2 的判据（`-avd (OC_*|Medium_Desktop_B)`）**不属于我**，故**未 kill**。
25 s 后复查：
```
total emulator/qemu process count = 0
OC_*/Medium_Desktop_B residual = 0
port 6070 LISTEN=0    port 6072 LISTEN=0
```

### 9.3 红线自查

- 只关 **`emulator-6070`** 一个 serial；只对 `OC_Deliver_R2`（`OC_*` 前缀，允许）动手；
- **未** `adb kill-server`；**未**用 `-a` / 通配符 / 按 pid 裸 kill；
- **未**起停后端 —— `4095` 全程 `LISTEN pid=3748`，**与开轮前同一 pid**（只做了 `adb reverse`）；
- **未**写 `app/**`、`_upstream/**`、`.github/**`、`webapp/build.gradle.kts`。
  本轮写入仅：`webapp/src/main/java/.../host/RoundActivityTracker.kt`、
  `webapp/src/main/java/.../host/LoopbackProxy.kt`、
  `webapp/src/test/.../host/RoundStreamSignalReleaseTest.kt`（新增）、
  `HANDOFF_WEB_SHELL.md`（**只改交付物小节**）、`research/web/FIX_F4_KEEPSCREEN.md`、
  `research/HOUSEKEEPING.md`、`research/deliver/opencode-shell-web-release-nor8.apk`。
- 抢锁：等 `.agent-gradle-lock` 消失（等了约 16 min，F3 那轮在用）→ 写锁 → 前台跑完 →
  释放。**释放时 `Remove-Item` 被安全策略拦下（不可恢复删除）⇒ 按派单改用单条顶层
  `rm --`，未绕过**，回执：`mavis-trash: moved to trash: '.agent-gradle-lock'`。

> ⚠️ **收尾时注意到但不是本轮起的**：`emulator-6050`（`OC_Deliver_R2`，`-read-only`，
> V5 记录的并发路实例）在我收尾时已不在 `adb devices` 里。
> **本轮从未对它执行过任何命令**（本轮只用过 6070），消失原因**未从本轮日志得出因果**，如实记录。

### 9.4 我碰过哪些 serial

| serial | AVD | 端口 | 镜像 / 设备 | 几何 | 处置 |
| --- | --- | --- | --- | --- | --- |
| `emulator-6070` | `OC_Deliver_R2` | **6070** | `android-35;google_apis_playstore_tablet` + `pixel_tablet` | `wm 1080x2400 @420`（已复原） | **本轮起、本轮关** ✅ |

**本轮只碰过 `emulator-6070` 这一个 serial。** 未连接任何 `MCRemote_*` / `DSH_*` / `T7x_*` 实例。
**故意保留：0 项（我自己的）。**

---

## 10. 未闭环

1. **`ROUND_END` 标记快路径在设备上未被观测到触发**（§5.4）。实际生效的是
   "心跳不续命 ⇒ 35 s 判据可达"，释放延迟约 **+59 s**（不是回合结束的即刻）。
   要拿到"即刻释放"，需要抓一次**回合期间**的 `/api/event` 真实帧，
   确认本服务器版本到底发不发 `session.status`→`idle`（本轮那次采样没赶上，§5.4）。
2. **释放延迟 59 s 仍偏长**：反耗电角度理想是回合结束即释放。可考虑把
   `SSE_IDLE_READ_TIMEOUT_MS`（35 s）在**已确认回合结束**后调小，但**本轮未改**，
   因为它同时是 SSE 半开检测的复用值，单独调小会牵连那条语义。
3. **回合中途 force-stop / 渲染进程被杀 ⇒ 释放**，本轮**无设备原文**（§6）。
4. **`:app:testDebugUnitTest` 本轮未跑**（§7.3）—— F4 未改 `app/**`。
5. **`maxRoundKeepMs = 30 min` 硬天花板仍未在设备上验证**（沿用 V5 §9 第 2 条）。
6. **窗口 2048 字符的边界**：若某帧负载 > 2 KB 且回合结束标记恰落在窗口外，
   本轮会漏判该帧（下一帧即恢复，兜底仍在）。**已知取舍，非未测。**
7. `research/env/f4/event_capture.txt` 里只截到 `server.connected` 与 `: heartbeat`，
   **没截到回合帧** —— 这正是第 1 条没闭环的直接原因。
