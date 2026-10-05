# FIX_F21 — 堵掉「空口令 ⇒ 后端无认证」这条路径

> 轮次：**F21 安全收尾**，派单依据 `research/web/UPSTREAM_FOLLOWUP_F20.md`
> 硬期限：2026-10-07 之前
> 状态：**已闭环**（路径核实 / 修法 / 绊线含反向验证 / 设备双路径 / 重建交付物 / 回归 / 收尾复核 均已完成）

## 0. 一句话结论

F20 挖到的**不是"启动链忘了判空"**——启动链一直有判空。真正漏的是**配置对话框的两个出口**，
它们都能在**口令为空**的状态下走进 `launch()` ⇒ **起代理 + 加载官方 UI**。
本轮把闸门下在 `launch()`（唯一的"起代理/加载"咽喉）与 `prewarmProxyAsync()`（会真的开一个监听端口），
**空口令 ⇒ 不起代理、不加载官方 UI**，改显一屏明说"没有身份验证"的被挡页。

---

## 1. 路径核实（**不照抄 F20 的结论**）

### 1.1 F20 指的 `WebHostActivity.kt:634-637` 原文

修**前**原文（`showSetupDialog` 里的「先不配置」按钮）：

```kotlin
.setNegativeButton("先不配置") { _, _ ->
    Log.w(TAG, "setup skipped -> API will 401")
    launch(defaultBackend)          // ← 634-637：口令为空仍然起代理、仍然加载官方 UI
}
```

F20 的行号**是对的**，但它注释里那句 `API will 401` **是错的**，而错得正是这个缺陷（§1.4）。

### 1.2 `SecretStore` 的读取/播种路径（行号）

| 事实 | 位置 |
|---|---|
| 口令键名 `serverPassword`；默认值 `http://127.0.0.1:4095` | `host/SecretStore.kt:32-36` |
| **读**：空串被过滤成 `null`（`takeIf { it.isNotEmpty() }`） | `host/SecretStore.kt:81-86`（判据在 **85**） |
| **播种**：传 null/空串 = **删键**（`remove(key)`） | `host/SecretStore.kt:89-96`（删键在 **93**） |
| **口令是 host-only**，拒绝经 JS 桥读 | `host/SecretStore.kt:47-50` |
| 落盘是 `EncryptedSharedPreferences`（AES256-GCM + Keystore） | `host/SecretStore.kt:62-72` |

⇒ **"空口令"在数据层 = 键不存在**，只有两种成因：**从未播种**（首次安装）或**被删**（提交空串 / `pm clear`）。

### 1.3 启动链**本来就有判空**（这是 F20 表述不够准的地方）

```kotlin
private fun startProxyAndLoad(restoredUrl: String?) {   // :397
    val password = SecretStore.getSecret(this, SecretStore.KEY_SERVER_PASSWORD)  // :401
    if (password.isNullOrEmpty()) {                      // :402  ← 这里挡住了
        Log.i(TAG, "no password in SecretStore, asking")
        showSetupDialog(backend)
        return                                            // 不起代理
    }
    probeThenLaunch(backend, password)
}
```

⇒ **"冷启动 + 首次安装未播种"这条最常见的路，本来就被挡住了。** 派单问"某条分支跳过了播种？"
—— 不是跳过播种，是**跳过了这道判空**：漏的是**别的分支**。

### 1.4 真正的洞：四个出口全都汇进 `launch()`

| # | 出口 | 位置（修前） | 为什么能走到"无认证使用" |
|---|---|---|---|
| ① | 「先不配置」按钮 | `:634-637` | 直接 `launch(defaultBackend)`，口令根本没读过 |
| ② | 「保存并连接」**提交空口令** | `:641-649` | `pass` 允许为空 ⇒ `setSecret(..., "")` 等于**删键** ⇒ 探到 200（后端自己没密码时正是 200）⇒ 判 `OK` ⇒ `launch` |
| ③ | F5 探测的 `Unreachable`/`Unknown` 分支"继续看看" | `:402-409` | F5 刻意允许"探不通仍加载"；此时口令可能为空 |
| ④ | 诊断页重试 `retryFromDiagnosticPage` | `:203-210` | 直接 `launch(...)`，无口令判据 |

**共同咽喉 = `launch()`**（`LoopbackProxy` 的 `passwordProvider` 在转发时才现读，空口令 ⇒ **不注入 `Authorization`**，只打一句 warn：`host/LoopbackProxy.kt:375-387`）。

### 1.5 后果与暴露面（`origin/v2` 依据）

- **后端"没设密码"时的行为**：`opencode` 的服务端认证是**可选的** —— 没配密码就**不校验**，
  任何能到达该端口的请求直接放行。⇒ 代理照常转发 ⇒ 官方 UI **完整加载、能点、能用、零报错**。
- ⚠️ **"看起来正常"就是本缺陷的实质**：失败形态不是"用不了"，而是"用得很好，但**完全没有认证**"。
- **暴露面**（派单问的那个）：**不是公网**，但也不是只有本 app ——
  1. **设备上任意 app**：回环地址不隔离进程，任意 app 可访问 `127.0.0.1:<proxy port>`；
  2. **经 USB 的宿主**：宿主跑了 `adb reverse tcp:4095 tcp:4095` 后，**任何连着这台设备的程序**都能打到宿主侧后端。
- 派单担心的"公网暴露"**不成立**（后端只监听回环 + reverse），但"回环 + reverse ⇒ 设备内任意 app / USB 宿主"**成立且是真实路径**。

### 1.6 触发条件（一句话）

> **后端自己没设密码（对任何能到达该端口的人开放）** ＋ **用户点「先不配置」或提交了空口令**
> ⇒ 修前必然进入"官方 UI 可用、但全程无认证"的状态。

---

## 2. 修法

### 2.1 为什么下在 `launch()` 而不是各调用点

`launch()` 是**唯一**"起回环代理 + 加载官方 UI"的地方。逐个调用点加判空，**漏一个就等于重新打开这个洞**
（且 F20 的教训正是"判空存在但不在真正的路径上"）。所以闸门下在咽喉，**一次覆盖四个出口**。

### 2.2 diff（要点）

**新增 `webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/LaunchGuard.kt`（纯逻辑，可单测）**

```kotlin
object LaunchGuard {
    fun mayLaunch(password: String?): Boolean = !password.isNullOrEmpty()

    const val HEADING_NO_PASSWORD = "还没设置服务器密码"
    const val HINT_NO_PASSWORD =
        "没有服务器密码，服务器会对任何能连上它的程序开放，等于没有身份验证。" +
            "请先设置密码再连接。"
    val KIND: ConnectErrorPage.Kind = ConnectErrorPage.Kind.NO_PASSWORD
    const val BUTTON_LABEL = "去设置密码"
    fun blockedHtml(): String = ConnectErrorPage.html(
        kind = KIND,
        detailForLog = "launch blocked: no password in SecretStore (F21)",
        retryJs = OPEN_SETUP_JS,          // 调桥 openSetup，不是 location.reload()
        buttonLabel = BUTTON_LABEL,
    )
    const val OPEN_SETUP_JS = "(function(){var b=window.__ocAndroid;" +
        "if(b&&b.openSetup){b.openSetup();}else{location.reload();}})()"
}
```

**`WebHostActivity.kt:516-533`（咽喉①：`launch`）**

```kotlin
 private fun launch(backend: String) {
+    // F21 · 安全闸门：**口令为空就不许进入已鉴权路径**。
+    if (!LaunchGuard.mayLaunch(SecretStore.getSecret(this, SecretStore.KEY_SERVER_PASSWORD))) {
+        Log.w(TAG, "F21 launch BLOCKED: no password in SecretStore -> refusing unauthenticated use")
+        PerfMarks.mark("H.f21.blockedNoPassword")
+        showNoPasswordBlockedPage()
+        return
+    }
     PerfMarks.mark("H.launch.enter", "backend=$backend mode=$perfMode")
```

**`WebHostActivity.kt:608-617`（咽喉②：`prewarmProxyAsync` —— 这条是本轮**新发现**的第二个洞）**

```kotlin
 private fun prewarmProxyAsync() {
+    if (!LaunchGuard.mayLaunch(SecretStore.getSecret(this, SecretStore.KEY_SERVER_PASSWORD))) {
+        Log.w(TAG, "F21 prewarm SKIPPED: no password in SecretStore")
+        return
+    }
```

**其余改动**：`ConnectErrorPage` 加 `Kind.NO_PASSWORD` + `buttonLabel`/`DEFAULT_BUTTON_LABEL`（默认"重试"**逐字不变**）；
`WebHostBridge.openSetup()`（新桥方法）；`WebHostActivity.openSetupFromBlockedPage()`（`:221`）；
`showNoPasswordBlockedPage()`（`:254`）；`:702` 那句错注释改正。

### 2.3 为什么安全

1. **在起代理之前拦**（不是"起代理后靠 401 兜"）——后者仍然是**先放行再失败**。
2. **连预热也拦** —— 预热会 `start()` 一个**已在监听**的代理；只拦 `launch()` 会留下"闸门挡住 UI、
   但设备上已经有个无认证监听端口"的窗口。
3. **被挡页说真话**：明写"等于没有身份验证"，**不写 host:port**（沿用 F5 的 redact 规矩），**永不回显口令**。
4. **不留死角**：按钮走 `openSetup`（回配置对话框）**而不是 `location.reload()`** ——
   `retry()` 会回到 `launch()` 再被挡 ⇒ "点一次刷一次"的死循环，正是 F5 修掉的"不可自救"复发。
5. **不破坏正常路径**：口令非空时判据为真，**一行原逻辑不变**（§4 实测）。

### 2.4 顺带修的一个"不诚实"（设备实测发现）

首版被挡页按钮写着**「重试」**、动作却是"回配置对话框"。在这一页上每个字都必须是真话 ——
已改为 **`BUTTON_LABEL = "去设置密码"`**，并加用例钉住；同时加 `defaultButtonLabelIsUnchangedForOtherErrorPages`
确保**前三类错误页文案逐字不变**。

---

## 3. 绊线与反向验证

文件：`webapp/src/test/java/com/xiufeigo/opencodeshell/web/EmptyPasswordLaunchGateTest.kt`
**16 个用例，0 失败**（纯逻辑 4 + 接线 5 + 被挡态 7）。

**可断言的量（派单指定的那一条）**：

> **口令为空时不得进入已鉴权路径** ——
> `LaunchGuard.mayLaunch("")==false` 且 `mayLaunch(null)==false`，
> **且** `launch()` 在 `LoopbackProxy(` 与 `webView.loadUrl(url)` **之前**调用该闸门。

### 3.1 反向验证（**两次独立突变，均真红**）

| 突变 | 结果（原文） |
|---|---|
| ① `mayLaunch` 改成恒 `true` | `14 tests completed, 2 failed` → 红：`nullPasswordIsRejected`、`emptyPasswordIsRejected` |
| ② **把 `launch()` 里的闸门整段删掉**（预热那道保留） | `14 tests completed, 3 failed` → 红：`everyProxyStartSiteIsGated`、`launchGuardsBeforeProxyStart`、`launchChecksTheGateBeforeLoadingUi` |

⇒ 突变②是关键：它证明**接线用例真的在盯着接线**，不是"纯逻辑恒绿"的空断言。
两次突变**均已还原**（还原后 16/16 绿）。

> 过程中我自己出过一次**无效突变**（想删闸门却只删了 `PerfMarks.mark`，闸门还在）——
> 记在这里是因为它提醒：**"跑红了"必须核对红的是不是自己以为的那几条**，否则容易把无效验证当成有效。

---

## 4. 设备验证（`emulator-6470`，`OC_Deliver_R2`）

- 镜像 `android-35;google_apis_playstore_tablet` + `pixel_tablet`，**未传 `-skin`**（V7 教训），
  几何用**物理值未覆盖**：`Physical size: 2560x1600` / `Physical density: 320`
- 后端 `4095` **全程未起停**（开轮与收尾均 `LISTEN pid=3748`，同一 pid），只做 `adb reverse tcp:4095 tcp:4095`
- 截图：`research/env/f21/f21_1_setup_dialog.png` / `f21_2_blocked.png` / `f21_3_typed.png` / `f21_4_official_ui.png`

### 4.1 异常路径（空口令 ⇒ 被挡）—— 截图 + 原文

制造方式：`pm clear com.xiufeigo.opencodeshell.web.debug`（**键不存在 = 从未播种**，§1.2）
⇒ 启动日志 `no password in SecretStore, asking`（`f21_1`）⇒ 点**「先不配置」**（**这就是 634-637 那条路**）：

```
W OcWebHost: setup skipped -> launch() will be blocked by the F21 gate (no password)
W OcWebHost: F21 launch BLOCKED: no password in SecretStore -> refusing unauthenticated use
```

**决定性证据 = 没有 `I.proxyListening`、没有 `home=` 那一行** ⇒ **代理根本没起**。
页面 origin 是 `https://appassets.androidplatform.net/`（被挡页用 `loadDataWithBaseURL` 挂宿主 origin），
资源数 `res-total n=1`、DOM `nodes=4` —— **不是官方 UI**。

`f21_2_blocked.png` 原文：

> **还没设置服务器密码**
> 没有服务器密码，服务器会对任何能连上它的程序开放，**等于没有身份验证**。请先设置密码再连接。
> `[ 去设置密码 ]`

⇒ **行为从"官方 UI 照常可用、零报错"变成"一屏明说没有身份验证 + 唯一出口是去设置密码"**。

### 4.2 正常路径回归（**最重要的一条：没被这个修复挡住正常使用**）

在同一次安装里播种口令 `opencode`（截图 `f21_3` 确认 8 个字符全部落位，**F5 记的丢字问题本轮未复现**）
⇒ 点「保存并连接」：

```
I OcWebHost: credentials stored backend=http://127.0.0.1:4095 passwordPresent=true
I OcWebHost: F5 probe OK code=200, loading official UI
I OcWebHost: home=http://127.0.0.1:45645/ backend=http://127.0.0.1:4095 passwordPresent=true
```

`f21_4_official_ui.png`：**官方 UI 完整加载**（Search sessions / Projects / Today / Older 全部真实会话可见），
`first-contentful-paint at=899 @19`。⇒ **闸门放行、正常路径未被影响。**

---

## 5. 重建交付物（三份新产物）

| # | 产物 | 体积 | **md5** | 签名 |
|---|---|---|---|---|
| 1 | `opencode-shell-web.apk`（debug） | **5,245,386 B** | **`922117547212296b85dfa80cc116c06d`** | `CN=Android Debug` |
| 2 | `opencode-shell-web-release-nor8.apk` | **3,980,197 B** | **`7bcdef48ae8d3ce84bee0b244cb44a94`** | `CN=Android Debug` |
| 3 | `opencode-shell-web-release-r8.apk` | **1,438,845 B** | **`4a37e9d600cc0f00618ba89a3654a154`** | `CN=Android Debug` |

三份证书 SHA-256 同为 `e86b70066546bd0bdc2e6cb4d970704bc48b1a244af59c664564f030cca0510a`
（沿用 F11 起的做法：release 变体用 `sharedDebug` 证书）。

### 5.1 包内含源自查

| 产物 | dex 数 | `LaunchGuard` | `F21 launch BLOCKED` | `H.f21.blockedNoPassword` | `F21 prewarm SKIPPED` | 被挡页文案 | `assets/www` |
|---|---|---|---|---|---|---|---|
| debug | 5 | ✅ | ✅ | ✅ | ✅ | ✅ | 4 |
| nor8 | 2 | ✅ | ✅ | ✅ | ✅ | ✅ | 4 |
| **r8** | **1** | ✅ | ✅ | ✅ | ✅ | ✅ | 4 |

> 只搜类名在 R8 下是**弱证据**（类名会被混淆）。真正有效的是**三条运行期字符串**都在 ——
> 它们是代码里的字面量，**R8 不会删**，所以能证明**闸门逻辑在 R8 包里确实存在**。

### 5.2 交接文档改动

`research/web/HANDOFF_F19.md`：
- **§3 表三行整体替换**为上表新值，并**显式作废** F18 那三个 md5
  （`03d7888fbc22779dfe7b10737f1ca151` / `5227c2ac67f5a613c32adacdbb7783e0` / `9f511c5a2d865cca173d62a57ba956a0`），
  注明"**在任何文档里都不得再当作当前交付包引用**"；
- **新增 §3.2**：把这条安全修复写进去（一句话 + 指向本报告）。

---

## 6. 回归用例数

| 套件 | 用例数 | 失败 | 错误 |
|---|---|---|---|
| `:webapp:testDebugUnitTest` | **173**（18 suites，含 F21 新增 16） | **0** | 0 |
| `:app:testDebugUnitTest` | **6,136**（677 suites） | **0** | 0 |

**用例数只增不减**：F21 新增 16 条，**未删除、未放宽任何既有断言**
（另加 `defaultButtonLabelIsUnchangedForOtherErrorPages` 专门守"前三类错误页文案逐字不变"）。

---

## 7. 收尾复核输出（原文）

```
$ adb -s emulator-6470 emu kill
OK: killing emulator, bye bye
OK

# 25 s 后复核：
List of devices attached            ← 空
emulator/qemu process count = 0
  port 6470 LISTEN=0
  port 6472 LISTEN=0
  port 6474 LISTEN=0
OC_*/Medium_Desktop_B residual = 0
backend 4095 = LISTEN pid=3748      ← 与开轮前同一 pid，全程未起停
.agent-gradle-lock exists = False   ← 释放后复核
```

- 杀前按 **AVD 名 + 端口**确认归属：`pid=48156 avd=OC_Deliver_R2 port=6470 MINE=True`
- `wm size reset` + `wm density reset` ⇒ 复核 `Physical size: 2560x1600` / `Physical density: 320`（**无 Override**）
- **未执行 `adb kill-server`**、**未用 `-a`/通配符**，`emu kill` **只对 `emulator-6470` 一个 serial** 发过
- 抢锁：开轮前确认锁空 → 写锁 → 前台跑完 → 释放

### 7.1 碰过哪些 serial

| serial | 归属 | 本轮是否起 | 是否 kill |
|---|---|---|---|
| **`emulator-6470`** | **本轮自建**（`OC_Deliver_R2`，端口 6470） | ✅ | ✅ 已关 |
| `emulator-5710` | **用户实例 `T90_Phone`（pid=12128）** | ❌ | ❌ **全程未碰、未发任何命令** |

**是否碰过非自建实例：没有。** 开轮时 `adb devices` 上有 `emulator-5710`（`T90_Phone`，
按 `HOUSEKEEPING.md` 规矩 2 属用户 AVD，全程未碰、未 kill）。

⚠️ **如实记录**：收尾复核时 `adb devices` 已空，**用户的 5710 也不在列**。
本轮**只对 6470 发过一条 `emu kill`**，原理上无法波及其它 serial；其消失原因
**未从本轮日志得出因果**，**不代其它轮次下结论**（与 F4/R2/V6/V7 轮记的形态一致）。

---

## 8. 未闭环 / 本轮明确没做的事（如实）

1. **`T90_Phone`(5710) 的消失原因未知** —— 见 §7.1，非本轮所致，无因果证据。
2. **设备上没有复现"提交空口令"这条支路（②）** —— 走的是「先不配置」（①）。
   ② 的判定由 `mayLaunch` 单测 + 接线用例覆盖，**未做设备端专门取证**。
3. **未做"非本 app 的攻击者"实测** —— 暴露面结论（设备内任意 app / USB 宿主）来自
   回环不隔离进程 + `adb reverse` 的机制推断，**本轮未写一个攻击程序去打**。
4. **`origin/v2` 的"后端不设密码即不校验"**取自 F20 的核对结论与本轮 proxy 侧行为
   （`LoopbackProxy.kt:375-387` 空口令不注入 `Authorization`），**本轮未重新拉取/复算上游 v2**。
5. **`ConnectErrorPage.Kind` 变成 4 类** —— `Native`/其他按 `when (kind)` 穷举的地方
   若存在，编译期会暴露；**本轮 `:webapp`/`:app` 编译与单测全绿**，未发现遗漏分支。
6. **`HANDOFF_FINAL_F16.md:24` 仍写着 F18 那一批旧 md5** —— 本轮只改了**活跃**交接
   `HANDOFF_F19.md`；F16 是历史轮次存档，**未追改**（避免大改无关历史记录，与 F19 §3.1 的克制口径一致）。
