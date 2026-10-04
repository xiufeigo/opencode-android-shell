# P6 · 省电落实：把 T1 盘出的措施真正做掉，并用代理量证明

> **本轮没有任何耗电数字。** 我们**没有真机**，`research/perf/BATTERY_HOWTO.md` §4 已说明：
> 模拟器无电池模型、无 rail 电流、`Discharge` 恒为 0 mAh。
> 下文 §3 全部是**代理量**（CPU 时间 / 连接数 / 日志），**代理量不是耗电**。
> **mAh 仍需真机**：脚本 `research/perf/battery_real_device.ps1`，用法见
> `research/perf/BATTERY_HOWTO.md` §1（A 拷到 Windows 机 / B USB 连本机 / C 手机 Termux 三种）。

**本轮基线**：`research/perf/BATTERY_HOWTO.md` §7.2–§7.5（只读盘点，带行号）。
**硬期限**：2026-10-07。**本轮改动范围**：`webapp/**` + `research/perf/**`（未碰 `app/**`、`_upstream/**`、`.github/**`、`webapp/build.gradle.kts`）。

---

## 0. 一句话结论

> T1 的"第一刀"两条都落实了：**后台 SSE 被掐断（后台 60 s 后到代理端口的 ESTABLISHED 连接
> 从 1 条降到 0 条）**、**常亮有了无条件兜底（离开前台/回合结束必清）**。
> 但 **CPU 时间只降了 5.6%，低于 T1 自定的 X = 20% 阈值 ⇒ 判为"在测量噪声内，不构成证据"**。
> **一句话：连接确实断了，耗电下降本轮无法证明，mAh 仍需真机。**

---

## 1. 逐条改动

### 刀 1 · 屏幕常亮：修掉"忘关路径"

**T1 的判断（`BATTERY_HOWTO.md` §7.3）**：桥方法 `setKeepScreenOn` 是**死代码**（无调用者），
但 `onPause()` 里**没有任何防御性 `clearFlags`** ⇒ 一旦页面接上调用，回合中途出错 / 被杀 /
切走就成忘关路径。

**⚠️ 先纠正 T1 一处事实错误**：T1 §7.3 写「全仓 grep `setKeepScreenOn` 只命中定义处 `:159`；
`webapp/**` …… **均无调用**」。**这是错的**：`webapp/src/main/assets/www/oc-host.js:195-198`
就有一层 JS 包装（`setKeepScreenOn: function (on) { … N.setKeepScreenOn(!!on); }`）。
本轮 grep 复核确认调用链**存在**，所以这条风险比 T1 估的更实。

| | 改前 | 改后 |
|---|---|---|
| 置位条件 | 无条件 `addFlags`（`WebHostBridge.kt` 旧 159-167） | 增设**前台校验**：不在前台直接拒 + `Log.w`（`WebHostBridge.kt:166-171`） |
| 离开前台 | 什么都不做 | `onPause()` **无条件** `clearFlags`（`WebHostActivity.kt:783` → `:815-817`） |
| Activity 销毁 | 什么都不做 | `onDestroy()` 也清一次（`WebHostActivity.kt:822`） |
| 状态来源 | 无 | `@Volatile foreground`（`:147`）+ `isForeground()`（`:150`），`onPause`/`onResume` 维护（`:775`/`:799`） |

**为什么安全**：
1. **不砍过头**：`PowerPolicy.shouldKeepScreenOn(foreground=true, roundActive=true) = true`
   ⇒ 前台 + 回合进行中**照常常亮**（`PowerPolicyTest.foreground plus active round...` 守着这条）。
   砍常亮=砍功能，不是省电。
2. **清 flag 用 `clearFlags` 而非位运算**：不依赖"当前值是什么"⇒ 幂等，且能清掉别人设的同一 flag。
3. **顺序**：`onPause` 里**先** `foreground = false`（`:775`）**再** 清 flag（`:783`），
   这样此刻之后任何 `setKeepScreenOn(true)` 的桥调用立刻被 `:167` 的前台校验拒掉。
4. **`@Volatile` 而非 `lifecycle.currentState`**：桥方法跑在 **WebView JS 桥线程**上，
   不是主线程；Activity 已 destroy 时读 lifecycle registry 会抛，窄语义布尔更安全。

### 刀 2 · 后台行为：断 SSE（本轮最该砍的一条）

**T1 的判断（§7.2）**：`LoopbackProxy` 自 `start()` 常驻，而 `close()` **只有 `onDestroy` 调**
⇒ 退后台后上游 `/event` 每 10 s 心跳继续唤醒射频，**且与屏幕亮度无关**。

**改前 → 改后**：

| | 改前 | 改后 |
|---|---|---|
| 退后台 | 什么都不做（`onPause` 旧 756-759 只有 `webView.onPause()`） | `proxy?.suspendStreaming()`（`WebHostActivity.kt:792`） |
| 已有 SSE 连接 | 一直挂着 | **掐断**（`LoopbackProxy.kt:198-208`） |
| 退后台后新 SSE | 照常连上游 | **503 拒绝，不建上游 socket**（`LoopbackProxy.kt:290-312`） |
| 回前台 | 无处理 | `resumeStreaming()`（`WebHostActivity.kt:802` → `LoopbackProxy.kt:218-222`） |

**三个关键设计点（都是踩过坑才这么定的）**：

1. **必须"关 + 拒绝"两步，只关是假省电。**
   只关 ⇒ 页面 `EventSource` 自动重连又打回上游 ⇒ "关一条、再连一条"拉锯 ⇒ 射频照旧唤醒。
   所以关掉之后必须**继续拒绝**（`LoopbackProxy.kt:290-292`）。
   判据被 `PowerPolicyTest.background streaming request must be refused not forwarded` 守着。
2. **拒绝必须发生在建上游请求之前**（`LoopbackProxy.kt:290` 早于 `:355` 的
   `client.newCall(request).execute()`）—— 拒绝得晚 = 上游 socket 已经建了，省电为 0。
   断言：`PowerWiringTest.proxyRefusalHappensBeforeUpstreamCall`。
3. **不能用 `proxy?.close()`**（T1 §7.2 明确警告）：那会 `running.set(false)` + 关 `ServerSocket`，
   把 `port` 留在死代理上，`onResume` 永远恢复不了。
   本轮只碰**流式 socket**，`ServerSocket`/`port`/线程池原封不动
   （实测日志 `portStillBound=true`）。断言：`PowerWiringTest.onPauseDoesNotCloseTheProxy` +
   `suspendStreamingKeepsServerSocketAndPortAlive`。

**为什么安全**：
1. **503 而不是 200**：200 空 body 会让 `EventSource` 以为连上了 ⇒ 永远不重连 ⇒
   回前台收不到事件，那才是真 bug。503 语义"稍后重试"，正好对上重连语义
   （`PowerPolicyTest.background refuse status is 503 not 200`）。
2. **复用既有恢复路径**：代理本来就靠"35 s 静默判半开主动断开让页面重连"恢复
   （`LoopbackProxy.kt:129-133`），本轮**不引入新行为类别**。
3. **只关流式、不关普通连接**：普通资源请求的 socket 留在 `activeClients`
   ⇒ 回前台第一次点击不会 502。
4. **回前台自动恢复**：`onResume` 解除拒绝，页面下一次重试即恢复，不主动重连
   （服务端推不出"页面该重建哪条 EventSource"）。

### 刀 3 · 定时器/心跳：**先验证，再决定不砍**

**T1 的判断（§7.4）**：web 壳自己几乎没有定时器漏电，唯一常驻的 `oc-perf.js` 500 ms 采样
在 20 s 后自停 ⇒ **风险低、明确不要砍**。

**本轮复核结论：与 T1 一致，不砍。** 逐条核对 T1 列的 4 个位置（`oc-perf.js:63-72`、
`index.html:281-288`、`WebHostActivity` 看门狗与两个 `postDelayed(400)`），
全是**一次性或自停**；`webapp/**` 内无 `ScheduledExecutorService`/`Timer`/周期 `postDelayed`。
**真正的定时唤醒源是上游 `/event` 的 10 s 心跳，已由刀 2 从连接层掐掉。**

⚠️ 顺带修掉**自己写错的一处注释**：初版日志写 `page EventSource will back off and retry`，
实测**退避不存在**（见 §3 原始输出，60 s 内等间隔重试 30 次、周期恒为 2.0 s）。
注释已改为实测口径。**代码行为无问题，是注释的断言过头了。**

### 刀 4 · `onPause` 加 `stopLoading`：**不砍**

T1 §7.5 列为"小收益 / 中风险（可能打断首屏）"，且要求"真机测出偏高再考虑"。
本轮**无真机、无证据** ⇒ 按"先量再砍"原则不动。列 §7 未闭环。

---

## 2. 决策层为什么单独抽一层（工程说明）

`webapp` 模块**没开** `unitTests.isReturnDefaultValues`（`app` 开了，见 `app/build.gradle.kts:64-65`），
而 `LoopbackProxy`/`WebHostActivity`/`WebHostBridge` 都调 `android.util.Log`
⇒ JVM 单测构造它们会在 `Log.i` 抛 `RuntimeException: not mocked`。
本轮**无权**改 `webapp/build.gradle.kts`，所以把**决策**抽进**零 android 依赖**的
`webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/PowerPolicy.kt`，
三处宿主只做**接线**。这样单测能测**真行为**，接线另有源码断言把关。

---

## 3. 代理量对照表（同机同轮次，改前 vs 改后）

**可比性条件**（对齐 `BATTERY_HOWTO.md` §5.2）：同机 `emulator-5940`、同 AVD `OC_Deliver_R2`、
同镜像 `android-35;google_apis_playstore_tablet` + `pixel_tablet`、同几何 `1080×2400@420`、
同 backend `127.0.0.1:4095`（只 `adb reverse`）、同 APK 签名、**同一段固定脚本化交互**、
**同 25 s 前台 + 60 s 后台窗口**。原始目录：`research/perf/out_p6/{baseline,after}/`。

| # | 代理量 | 改前 (baseline) | 改后 (after) | 变化 | 判定 |
|:--:|---|---|---|---|:--:|
| 1 | **后台 60 s 末，到代理端口的 ESTABLISHED 连接数** | **1** | **0** | **−1（归零）** | ✅ **有效** |
| 2 | **后台 60 s 内 App 进程 CPU 时间** | 10370 ms | 9790 ms | **−580 ms = −5.6%** | ⚠️ **无差异**（< X=20%） |
| 3 | **P6 行为日志条数** | **0** | **31**（1 关 + 30 拒） | +31 | ✅ **有效** |
| 4 | **WakeLock 数（`dumpsys power`）** | `size=0` | `size=0` | **0** | ⚠️ **无差异** |
| 5 | 前台 25 s CPU 时间 | 12320 ms | 11790 ms | −530 ms = −4.3% | ⚠️ **无差异** |

**APK 指纹**：`baseline` = `B716CBEB4C66E55FCB0F497640AE5E0C`，`after` = `46E2ADDDDF4DD24D885A8BBD88D1BDFD`。

### ⚠️ 逐项如实说明（含"无差异"项）

- **量 1（连接归零）——本轮最硬的证据。** 改前退到后台 60 s 后仍有 **1 条** SSE 长连接挂在
  代理端口上（= T1 §7.2 说的"直到进程被杀"）；改后 **0 条**。
- **量 2（CPU −5.6%）——判"无差异"，不拿它当省电证据。**
  按 `BATTERY_HOWTO.md` §6 的判定规则：差异 5.6% **< X = 20%** ⇒
  **"在测量噪声内，不构成证据"**（这不等于"两者一样"，更不等于"省了 5.6% 电"）。
  原因分析：后台 CPU 的大头是 WebView 渲染进程与 EventSource 每 2 s 的回环重试，
  掐掉上游长连接省下的是**射频**，而**射频活动在模拟器上不可观测、也不体现为 app 进程 CPU**。
  ⇒ **CPU 不是本条改动的合适代理量**，这本身是一条有价值的结论。
- **量 4（WakeLock）——无差异，且是"本来就干净"。**
  两边都是 `Wake Locks: size=0`：**这个 app 从来不持有 WakeLock**（T1 §5.1 说
  "出现非零长持锁 = 漏电信号" —— 本项目没有这个信号）。
  ⇒ **WakeLock 对本项目不具区分度**，不是"改动无效"的证据。
- **量 5（前台 CPU）——无差异。** 刀 2 只在后台生效，前台行为**按设计未变**
  （`PowerPolicyTest.foreground streaming must keep the original forward behaviour` 守着）。
- **⚠️ 网络字节：本轮**没有**可用的 app 级 A/B 数字。**
  `dumpsys netstats` 各版本格式不一、解析不到；`/proc/<pid>/net/dev` 读到的是
  **整个 netns 的累计值**（含 `lo` 全设备流量、且是绝对值不是窗口增量），
  两轮之间不可比。⇒ 记为**未闭环**，并给出可复现修法（§7.3）。

### 原始输出（P6 行为日志，改后独有；改前为 0 条）

```
10-04 18:55:35.816  4921  4921 I OcWebHost/Proxy: P6 suspendStreaming: closed=1 remainingActive=2 portStillBound=true
10-04 18:55:37.611  4921  5005 I OcWebHost/Proxy: P6 refuse SSE while backgrounded: /api/event (no upstream call; page EventSource retries -- measured 2s cadence, loopback only so no radio wakeup)
10-04 18:55:39.609  4921  5004 I OcWebHost/Proxy: P6 refuse SSE while backgrounded: /api/event (...)
...
10-04 18:56:35.604  4921  5004 I OcWebHost/Proxy: P6 refuse SSE while backgrounded: /api/event (...)
```

改前同一窗口 `p6_lines.txt` = **空文件（0 条）**（APK 里根本没有这套日志）。

- `closed=1 remainingActive=2` ⇒ **只掐了 1 条流式、普通连接留着**（符合设计第 3 点）。
- `portStillBound=true` ⇒ **没有误用 `close()`**，`port` 仍在（`onResume` 可恢复）。
- 重试节奏：60 s / 30 次 ≈ **恒定 2.0 s，WebView 的 `EventSource` 没有指数退避**。
  这是**回环**请求，不碰上游 ⇒ 不唤醒射频；但它确实是一笔每 2 s 一次的本地小成本（见 §7.1）。

---

## 4. 绊线与反向验证

### 4.1 绊线本体

> **"离开前台 / 回合结束后不应仍持有 `FLAG_KEEP_SCREEN_ON`"**
> —— `PowerPolicyTest.leaving foreground must not keep screen on even while a round is active`
> —— `PowerPolicyTest.round end must not keep screen on even while in foreground`

第二道（接线）绊线：`PowerWiringTest` 直接读 `WebHostActivity.kt` / `LoopbackProxy.kt` /
`WebHostBridge.kt` 源码，钉死三个接线点 + 三条"不许"的禁令（不许 `proxy?.close()`、
不许 `serverSocket?.close()`、拒绝必须早于建上游请求）。

### 4.2 反向验证：**逐条撤回 → 绊线真红**（原始输出 `out_p6/revert/summary.txt`）

| 撤回的改动 | 结果 | 变红的用例 |
|---|:--:|---|
| **R1** `onPause` 的 `suspendStreaming()` 调用 | **BUILD FAILED** | `PowerWiringTest.onPauseCallsSuspendStreaming` |
| **R2** `shouldKeepScreenOn` 忽略 `roundActive` | **BUILD FAILED** | `PowerPolicyTest > round end must not keep screen on even while in foreground`（`PowerPolicyTest.kt:45`） |
| **R3** `shouldKeepScreenOn` 忽略 `foreground` | **BUILD FAILED** | `PowerPolicyTest > leaving foreground must not keep screen on even while a round is active`、`clearing keep screen on is idempotent` |
| **R4** `allowStreaming` 恒 `true`（只关不拒） | **BUILD FAILED** | `PowerPolicyTest > background streaming request must be refused not forwarded`、`background must not be allowed to hold streaming connections` |
| **还原后** | **BUILD SUCCESSFUL** | 无（67 tests 全绿） |

**四次撤回全部真红、还原即绿。** 每次只改**一处最小可编译**代码，跑完立刻还原；
**没有删任何断言、没有放宽断言、没有改用例数**。

### 4.3 ⚠️ 设备侧断言（题面要求"若只能在设备侧断言 ⇒ 写清并给可复现脚本"）

本轮的绊线**全部可在 JVM 单测断言**（因为抽了纯逻辑层），**不需要**设备侧断言。
但连接归零那条**只有设备侧能量**，可复现脚本已交付：

```powershell
# 同机同轮次跑改前/改后两轮，对比 out_p6/<tag>/summary.json 的 sock_bg_end
pwsh -NoProfile -File research\perf\p6_boot.ps1          # 起 OC_Deliver_R2 @ 5940
pwsh -NoProfile -File research\perf\p6_ab.ps1 -Apk research\perf\out_p6\apk\p6_BASELINE_webapp.apk -Tag baseline -BgSec 60 -FgSec 25
pwsh -NoProfile -File research\perf\p6_ab.ps1 -Apk research\perf\out_p6\apk\p6_AFTER_webapp.apk    -Tag after    -BgSec 60 -FgSec 25
# 收尾：wm size/density reset → adb -s emulator-5940 emu kill（见 §6）
```

### 4.4 本轮自己踩到并修掉的两个测量 bug（如实记录）

1. **`/proc/net/tcp` 解析错位**：`st` 是第 **4** 个下标（前面有个前导空白空串），
   第一版误取下标 3（那是 `rem_address`）⇒ `st` 恒 ≠ `01` ⇒ **socket 数恒为 0（假零）**。
   已改用位置正则，并把"改前 socket 数 = 2/1"重测了一遍。
2. **logcat tag 过滤漏代理**：`LoopbackProxy` 的 `TAG = "OcWebHost/Proxy"`（`LoopbackProxy.kt:99`），
   不是我以为的 `OcWebHost`。第一版用 `-s OcWebHost:V` ⇒ **P6 日志整段丢失 + 代理端口解析成 −1**。
   已改为 `OcWebHost:V OcWebHost/Proxy:V`。
3. 另有一处**自己写的断言有缺陷**：`PowerWiringTest.onPauseDoesNotCloseTheProxy` 最初用裸
   `contains()`，而 `onPause` 的**解释性注释里**恰好写了 `proxy?.close()` ⇒ 假红。
   已改为**先剥行注释再断言**（断言意图是"代码里没这个调用"，不是"这段文字没这几个字母"）。

---

## 5. 回归

```
:webapp:testDebugUnitTest   tests=67    failures=0  errors=0
:app:testDebugUnitTest      tests=6136  failures=0  errors=0
```

- **webapp 67 = 原有 46 + 本轮新增 21**（`PowerPolicyTest` 11 + `PowerWiringTest` 10）。
- **用例数只增不减**：`git status --porcelain -- webapp/src/test app/src/test` 只显示
  **2 个 `??` 新增文件**，**零既有测试文件被修改/删除** ⇒ 计数只增。
- **没有删/放宽任何断言**：R1–R4 反向验证正是靠这些断言变红来证明它们有效的。

---

## 6. 收尾复核输出（原始）

```
=== [1] 杀前确认：只按 AVD 名认自己的 ===
  MINE pid=27972 avd-match
  MINE pid=48996 avd-match
  total emulator/qemu procs = 4
=== [2] wm size/density reset ===
  Physical size: 2560x1600
  Physical density: 320            ← 已无 Override 行
=== [3] emu kill（只按 serial） ===
OK: killing emulator, bye bye
=== [4] 复核 ===
  OC_Deliver_R2 procs = 0
  port 5940 LISTEN     = 0
  5960/5980 ports LISTEN= 4        ← 别人的，不碰
```

- **我碰过的 serial：只有 `emulator-5940`**（AVD `OC_Deliver_R2`）。
- **故意保留：无。** 我起的实例已全部关闭。
- **⚠️ 如实记录**：收尾时全机还有 `OC_Preflight_V3`(5960)、`OC_Preflight_WebView83`(5980)，
  **是别的 agent 起的、不是本轮起的** ⇒ 按 `HOUSEKEEPING.md` 规则 2「谁起谁关」**未碰、未 kill**。
- **后端 `4095` 全程未起停**（收尾仍在 `LISTEN pid=3748`，与开轮前一致）。
- **未执行** `adb kill-server`、**未用** `-a`/通配符，只按 serial 关。
- **Gradle 锁**已释放（`.agent-gradle-lock exists = False`）。
- 轮次开始时 `adb devices` 为空、emulator 进程数 0、5930–5944 端口全 FREE
  ⇒ **本轮不存在可被波及的用户设备**。

---

## 7. 未闭环（含"mAh 仍需真机"）

### 7.1 🔴 **mAh 仍需真机，本轮未编任何耗电数字**
模拟器无电池模型、无 rail 电流（`BATTERY_HOWTO.md` §4）。**§3 全部是代理量，不是耗电。**
拿一台真机跑两条命令即可闭环：`research/perf/battery_real_device.ps1`，
用法见 `BATTERY_HOWTO.md` §1；可比性铁律见 §5.2；阈值 **X = 20%** 见 §6。
⚠️ 且据 §3 量 2，**本条改动的收益很可能主要落在射频侧**，而真机 A/B 必须用
`webapp`/`native` 两轮 + 同亮度同交互才有意义。

### 7.2 `EventSource` 每 2 s 重试、**无指数退避**
60 s 内 30 次恒定 2.0 s。是**回环**请求（不唤醒射频），但仍是每 2 s 一次本地小成本。
**未闭环**，因为修它要动页面侧（`EventSource` 的重连策略在官方 UI 产物里，
不在本仓可改范围）。候选方案（需真机验证收益后再做）：后台期让代理**挂起**（park）该请求
而不返回 503，回前台再主动断开触发真实重连。**本轮未做，因为没有真机数据支撑它更省。**

### 7.3 网络字节代理量缺失
`dumpsys netstats` 解析不到；`/proc/<pid>/net/dev` 是 netns 累计值、不可比（§3）。
**可复现修法**：改用 `dumpsys netstats detail` 按 UID 过滤，或对 app 进程做
`/proc/<pid>/net/dev` 的**窗口增量**（本轮只取了绝对值，可一行改掉）。

### 7.4 `onPause` 加 `stopLoading`（T1 §7.5 第 4 条）——**按"先量再砍"不动**
T1 标注"小收益 / 中风险（可能打断首屏）"，要求真机测出偏高再考虑。本轮无真机 ⇒ 不动。

### 7.5 WebSocket 隧道**未纳入**后台断开
PTY 终端走 `tunnelWebSocket`（`LoopbackProxy.kt:415-463`），同样是长连接。
本轮**故意只切 SSE**：终端断连语义不明、回前台重建路径未验证，误伤风险高于收益。
⇒ 列为**未闭环**，需真机 + 终端场景单独验证。

### 7.6 `oc-perf.js` 500 ms 采样（DEBUG 探针）
按 T1 §7.5「明确不要砍」**未动**（第 40 次自停、最长 20 s、release 无害）。

### 7.7 `app/**/ServerConfigSyncJs.kt:36` 的原生壳 5 s 轮询
T1 §7.4 跨壳对照项，影响**真机 A/B 公平性**（原生壳射频侧可能更费电）。
**本轮无权改 `app/**`**，且它不是 web 壳的问题 ⇒ 仅记录，供后续轮次。

---

## 8. 文件清单

| 文件 | 性质 |
|---|---|
| `webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/PowerPolicy.kt` | **新增** · 纯 Kotlin 决策层（零 android 依赖） |
| `webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/LoopbackProxy.kt` | 改 · `suspendStreaming`/`resumeStreaming`/后台 503 拒绝 |
| `webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt` | 改 · `onPause`/`onResume`/`onDestroy` 接线 + `foreground` 状态 |
| `webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/WebHostBridge.kt` | 改 · `setKeepScreenOn` 前台校验 |
| `webapp/src/test/java/com/xiufeigo/opencodeshell/web/host/PowerPolicyTest.kt` | **新增** · 11 例绊线 |
| `webapp/src/test/java/com/xiufeigo/opencodeshell/web/PowerWiringTest.kt` | **新增** · 10 例接线绊线 |
| `research/perf/p6_boot.ps1` / `p6_ab.ps1` / `p6_revert.ps1` | 新增 · 引导 / A-B / 反向验证 |
| `research/perf/out_p6/**` | 原始输出（`baseline/`、`after/`、`revert/`、`apk/`） |
