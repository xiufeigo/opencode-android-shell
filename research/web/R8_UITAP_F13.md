# R8_UITAP_F13 —— 用「真点官方 UI 发送按钮」驱动回合，再验常亮三段（F13）

> 时间：2026-10-05 11:05–11:2x (+08:00)，**早于硬期限 2026-10-07**
> 设备：`emulator-6330`，AVD `OC_Deliver_R2`（**自建**），端口 **6330**（偶数、≥6330、开工前实测 FREE）
> 镜像：`android-35;google_apis_playstore_tablet` + `pixel_tablet`，**未用 `-skin`**（`pixel_tablet` 不是合法 skin 名）
> 几何：`wm size 1080x1600` + `wm density 420` 覆盖（`2560x1600` 横屏物理尺寸 → 竖屏）
> 后端：`127.0.0.1:4095` **全程未起停**（`LISTEN pid=3748` 与开工前同一 pid），只做 `adb reverse tcp:4095 tcp:4095`
> 写权限：只写 `research/web/**`；**未碰** `app/**`、`webapp/**`、`_upstream/**`、`.github/**`、`research/deliver/**`（只读）
> ⚠️ 本轮消掉的就是 F12 自曝的两条边界：**①置起改由真点驱动 ②补取设备端 base.apk md5**。

---

## 0. 一句话回答

**两条边界都消掉了。**

| 项 | 结论 | 关键证据 |
|---|---|---|
| 「真点官方 UI 发送按钮」这条路 | ✅ **通** | `uiautomator` 当场解析出 `text=[Send]` 按钮 `bounds=[939,1330][1008,1396]` → `input tap 974 1363` → 2 s 后 `roundActive=true` |
| 设备端 `base.apk` md5 | ✅ **取到了** | `0598817026665544a08803055107a9fd`，`DEVICE_MD5_MATCH=True` |
| 常亮第一段（回合进行中置起） | ✅ **成立** | 10/10 次 `KEEP_SCREEN_ON=true`，原始行 `fl=KEEP_SCREEN_ON ...` |
| 常亮第二段（回合结束释放） | ✅ **成立** | `SEG2_afterEnd_WINDOW_KEEP_SCREEN_ON=false` |
| 常亮第三段（+95 s 仍未复起） | ✅ **成立** | `SEG3_tplus95s_WINDOW_KEEP_SCREEN_ON=false` |

---

## 1. 装前 md5 复核（`Get-FileHash` 原文）

```
R8_MD5=0598817026665544a08803055107a9fd
R8_SIZE=1435193
---
NOR8_MD5=76ca064b7de924f765a9198b37   → 实际原文：76ca064b7de9246ee446f765a9198b37
NOR8_SIZE=3975389
```

| 包 | 期望 md5 | 实测 md5 | 期望 size | 实测 size | 一致 |
|---|---|---|---:|---:|---|
| **R8** `opencode-shell-web-release-r8.apk` | `0598817026665544a08803055107a9fd` | `0598817026665544a08803055107a9fd` | 1,435,193 | 1,435,193 | ✅ |
| **非 R8** `opencode-shell-web-release-nor8.apk` | `76ca064b7de9246ee446f765a9198b37` | `76ca064b7de9246ee446f765a9198b37` | 3,975,389 | 3,975,389 | ✅ |

---

## 2. F13-2：设备端 `base.apk` md5（**F12 那条正则已修好，本轮取到**）

### 2.1 F12 为什么没取到

F12 用的是：
```powershell
$base = (Sh "pm path $PKG") -replace '.*package:',''
```
`-replace` 是**贪婪**的，`.*package:` 吃掉前缀后，若命令尾部还带 `|`/空白等残留，路径就被污染；
接着 `md5sum <脏路径>` 失败 → `DEVICE_MD5=`（空）→ `DEVICE_MD5_MATCH=False`。V6 曾用同样写法未取到。

### 2.2 F13 改法：用 `[regex]::Match` 抓**带锚点的路径**，并把三条命令原文全部落盘

```
[11:09:51.956] INSTALL=Performing Streamed Install | Success
[11:10:14.343] PMPATH_RAW=package:/data/app/~~Vo8XjEl5bKBkKbCgx9-_cQ==/com.xiufeigo.opencodeshell.web.debug-RRneBvfV0_4wYnHied5UHw==/base.apk
[11:10:42.868] PMPATH_FULL_RAW=[package:/data/app/~~Vo8XjEl5bKBkKbCgx9-_cQ==/com.xiufeigo.opencodeshell.web.debug-RRneBvfV0_4wYnHied5UHw==/base.apk | ]
[11:10:42.882] DEVICE_BASE_APK_PATH=[/data/app/~~Vo8XjEl5bKBkKbCgx9-_cQ==/com.xiufeigo.opencodeshell.web.debug-RRneBvfV0_4wYnHied5UHw==/base.apk]
[11:10:53.099] LS_CHECK=-rw-r--r-- 1 system system 1435193 2026-10-05 03:09 /data/app/~~Vo8XjEl5bKBkKbCgx9-_cQ==/.../base.apk
[11:11:03.280] MDSUM_RAW=[0598817026665544a08803055107a9fd /data/app/~~Vo8XjEl5bKBkKbCgx9-_cQ==/.../base.apk | ]
[11:11:03.283] DEVICE_MD5=0598817026665544a08803055107a9fd
[11:11:03.285] DEVICE_MD5_MATCH=True
```

用的正则（**抓 `base.apk` 结尾的真实绝对路径，避开 `package:` 前缀与尾部残留**）：
```powershell
[regex]::Match($baseRaw,'(/data/app/[^|\s]+/base\.apk)')
```

### 2.3 比对结果

| 项 | 值 |
|---|---|
| 装前本地 md5 | `0598817026665544a08803055107a9fd` |
| **设备端 `base.apk` md5** | **`0598817026665544a08803055107a9fd`** |
| 设备端 `base.apk` 大小（`ls -l`） | **1,435,193**（与本地 1,435,193 相同） |
| **结论** | ✅ **`DEVICE_MD5_MATCH=True`** —— 设备上跑的就是被测 R8 包 |

> F12「记为未取到」这条**本轮已闭环**，不再挂账。

---

## 3. 「真点发送按钮」的完整过程（逐步原始输出）

### 3.1 🔴 明确标注：哪一步是注入、哪一步是真点

| 步骤 | 性质 | 说明 |
|---|---|---|
| 装包 / 播种口令 / 进会话 / 聚焦输入框 / 输入文字 / **点 Send** | **✅ 真点（真实 `input tap`）** | 全部走 `adb shell input tap`，等价于手指点官方 UI |
| **驱动回合的那一下点击** | **✅ 真点** | `input tap 974 1363` 打在官方 UI 的 `Send` 按钮上 |
| 前置探针（`probe OK`、session id 抽取） | **只读观测** | 只 `logcat -d` 读日志，**不发任何请求** |
| `wm` 覆盖 / `adb reverse` | **环境准备** | 与回合无关 |
| ~~F12 的 `curl …/message`~~ | **❌ 本轮完全没用** | F13 回合**没有**任何 `curl` 注入 |

> **本轮 R8 回合 100% 由真实手指点击驱动，零注入。**

### 3.2 前置断言：已鉴权 + 数据已到（**真实会话列表，不是骨架屏**）

先「等渲染」再断言 —— 这一点是本轮踩到的真坑（见 §6.1）：

```
[11:16:20.890] RENDER_WAIT try=0 rows=0
[11:16:27.430] RENDER_WAIT try=1 rows=10
[11:16:27.490] PRE_PROXY_PORT=46001 AUTHFAILED=0 PROBE_OK=True FIRSTCONTENT=True
[11:16:27.493] PRE_SESSION_IDS=2 -> ses_efc722bf9ffeWaRdoFTwbmYj1Y, ses_f408ec4a0ffeKjpcGKG4kfGEBR
[11:16:27.498] PRE_LIST_ROWS=10
[11:16:27.506] PRE_ROW cls=android.widget.Button text=[O Counting from 1 to 4000, one number per line opencode-android-shell] bounds=[52,493][1029,624]
[11:16:27.510] PRECONDITION_PASS=True
```

- `AUTHFAILED=0`、`probe OK=True`、`passwordPresent=true`
- **2 个真实 session id**（`ses_efc722bf9…`、`ses_f408ec4a0…`）
- `PRE_LIST_ROWS=10`：**10 个带真实标题的可点会话行**（`O Counting from 1 to 4000…`、`D Friendly greeting DSH 临时会话`、`S android-e2e System32`、`C DRAFT__OK chenjieyan_test` …）
  ⇒ **是真实数据，不是骨架屏**（骨架屏只有 0 行或无文本行）

### 3.3 STEP A：真点会话行（进会话）

```
[11:16:27.515] STEP_A_SESSION_ROW text=[O Counting from 1 to 4000, one number per line opencode-android-shell] bounds=[52,493][1029,624] center=(540,558)
[11:16:27.516] STEP_A_TAP_CMD=adb -s emulator-6330 shell input tap 540 558
[11:16:27.569] STEP_A_TAPPED=REAL_UI_TAP (session row)
```

### 3.4 STEP B：进会话后重新 dump，当场列出全部可点节点

```
[11:16:35.818] STEP_B_EDITTEXTS=1
[11:16:35.819] STEP_B_ET bounds=[52,1270][1029,1433] center=(540,1352) text=[… resource-id=]
[11:16:35.821] STEP_B_CLICKABLES=21
[11:16:35.850] STEP_B_CLICK cls=android.widget.Button desc=[] text=[Send] bounds=[939,1456][1008,1522] center=(974,1489)
[11:16:35.852] STEP_C_COMPOSER bounds=[52,1270][1029,1433] center=(540,1352)
[11:16:35.853] STEP_C_FOCUS_TAP_CMD=adb -s emulator-6330 shell input tap 540 1352
[11:16:37.103] STEP_C_COMPOSER_FOCUSED=REAL_UI_TAP
```

> 会话内布局：顶部 `Session/Changes/Files/Terminal` 四个 Tab，中部消息流，**底部 composer + 右侧 `Send`**。
> 发送按钮**确实在 a11y 树里**（`android.widget.Button text=[Send]`），所以「真点」这条路**具备可行性**。

### 3.5 STEP C：真点聚焦 composer + 真键盘输入

```
[11:16:37.835] STEP_C_TYPED=f13 uitap (real input text into the composer)
```

输入后再次 dump，**在树里直接读回自己敲的字**（这是「文字真的进了 composer」的闭环证据）：

```
[11:16:40.304] STEP_D_CLICK cls=android.widget.EditText text=[f13uitap] bounds=[52,1144][1029,1307] center=(540,1226)
```

> ⚠️ 诚实标注：**`f13uitap` 中间没有空格**。脚本用 `input text` 逐词敲，词间按 `keyevent 62`（空格）失败/未生效，故粘成一个词。
> 这**不影响**本轮结论（回合照样起来了），但如实记下，不美化。

### 3.6 STEP D：🔴 **重新解析**发送按钮 bounds（每次重新 dump，bounds 会变）→ 真点

**这一步两轮 bounds 不同，正好证明「bounds 会变、必须每次重新解析」：**

| 时点 | 解析到的 `Send` | bounds | 中心 |
|---|---|---|---|
| STEP B（敲字**前**） | `text=[Send]` | `[939,1456][1008,1522]` | `(974,1489)` |
| STEP D（敲字**后**、IME 收起后） | `text=[Send]` | **`[939,1330][1008,1396]`** | **`(974,1363)`** |

```
[11:16:40.306] STEP_D_CLICK cls=android.widget.Button desc=[] text=[Send] bounds=[939,1330][1008,1396] center=(974,1363)
[11:16:40.309] STEP_D_SEND_PICKED how=text cls=android.widget.Button desc=[] text=[Send] bounds=[939,1330][1008,1396] center=(974,1363)
[11:16:40.309] STEP_D_TAP_CMD=adb -s emulator-6330 shell input tap 974 1363
[11:16:40.358] STEP_D_SEND_TAPPED=REAL_UI_TAP on the official UI send button (NOT a curl/proxy injection)
[11:16:40.361] TAP_AT=11:16:40.308
```

选取规则（**当场解析，不写死坐标**）：`content-desc` 含 `send/发送` → 文本恰为 `send/发送` → 兜底取底部右侧小按钮。实际命中 `how=text`。

### 3.7 断言：回合**真的**被这次点击驱动起来了

```
[11:16:42.454] ROUND_STARTED_BY_UI_TAP=YES (logcat roundActive=true) t+2s
```

`TAP_AT=11:16:40.308` → `t+2s` 内 `roundActive=true`。
**这与 F12 的区别**：F12 是 `curl` 直接打 `…/message`；F13 是**手指点 Send → 页面自己发 POST → 代理收到 → `roundActive=true`**。

---

## 4. 常亮三段（原始行 + 时间戳，全部来自 `dumpsys window` / `dumpsys power`）

### 4.1 段一：回合进行中 → flag 置起 ✅

**原始窗口 flag 行（`KEEP_SCREEN_ON` 这个名字真的在 `fl=` 里）**：

```
[11:16:42.631] SEG0_idle_WINDOWFLAGRAW: fl=KEEP_SCREEN_ON LAYOUT_IN_SCREEN LAYOUT_INSET_DECOR SPLIT_TOUCH HARDWARE_ACCELERATED DRAWS_SYSTEM_BAR_BACKGROUNDS
[11:16:42.632] SEG0_idle_WINDOWFLAGRAW: pfl=NO_MOVE_ANIMATION EDGE_TO_EDGE_ENFORCED FORCE_DRAW_STATUS_BAR_BACKGROUND FIT_INSETS_CONTROLLED
[11:16:42.633] SEG0_idle_WINDOW_KEEP_SCREEN_ON=true
[11:16:42.713] SEG0_idle_POWER_mWakefulness=Awake mHoldingDisplaySuspendBlocker=true
```

> 这一行采样于 `11:16:42.6`，即 `ROUND_STARTED_BY_UI_TAP=YES`（`11:16:42.454`）之后 0.2 s —— 所以标签叫 `SEG0_idle` 但**它其实已经是「回合进行中」状态，不是纯 idle 基线**。如实标注。

**逐次采样（2 s 一次，10/10 命中）**：

```
[11:16:44.825] SEG1_s00_tplus2s_WINDOW_KEEP_SCREEN_ON=true
[11:16:46.951] SEG1_s01_tplus4s_WINDOW_KEEP_SCREEN_ON=true
[11:16:49.062] SEG1_s02_tplus6s_WINDOW_KEEP_SCREEN_ON=true
[11:16:51.164] SEG1_s03_tplus8s_WINDOW_KEEP_SCREEN_ON=true
[11:16:53.275] SEG1_s04_tplus10s_WINDOW_KEEP_SCREEN_ON=true
[11:16:55.392] SEG1_s05_tplus12s_WINDOW_KEEP_SCREEN_ON=true
[11:16:57.500] SEG1_s06_tplus14s_WINDOW_KEEP_SCREEN_ON=true
[11:16:59.621] SEG1_s07_tplus16s_WINDOW_KEEP_SCREEN_ON=true
[11:17:01.737] SEG1_s08_tplus18s_WINDOW_KEEP_SCREEN_ON=true
[11:17:03.839] SEG1_s09_tplus20s_WINDOW_KEEP_SCREEN_ON=true
[11:17:03.949] SEG1_RAISED_SEEN=true
```

`dumpsys power` 全程 `mWakefulness=Awake mHoldingDisplaySuspendBlocker=true`。

### 4.2 段二：回合结束 → 释放 ✅

```
[11:18:29.006] SEG2_afterEnd_WINDOWFLAGRAW: fl=LAYOUT_IN_SCREEN LAYOUT_INSET_DECOR SPLIT_TOUCH HARDWARE_ACCELERATED DRAWS_SYSTEM_BAR_BACKGROUNDS
[11:18:29.006] SEG2_afterEnd_WINDOWFLAGRAW: pfl=NO_MOVE_ANIMATION EDGE_TO_EDGE_ENFORCED FORCE_DRAW_STATUS_BAR_BACKGROUND FIT_INSETS_CONTROLLED
[11:18:29.007] SEG2_afterEnd_WINDOW_KEEP_SCREEN_ON=false
[11:18:29.056] SEG2_afterEnd_POWER_mWakefulness=Awake mHoldingDisplaySuspendBlocker=true
```

`KEEP_SCREEN_ON` 已从 `fl=` 里**消失** ⇒ 释放。

### 4.3 段三：+95 s（≥ +90 s）仍未复起 ✅

```
[11:20:04.172] SEG3_tplus95s_WINDOWFLAGRAW: fl=LAYOUT_IN_SCREEN LAYOUT_INSET_DECOR SPLIT_TOUCH HARDWARE_ACCELERATED DRAWS_SYSTEM_BAR_BACKGROUNDS
[11:20:04.173] SEG3_tplus95s_WINDOWFLAGRAW: pfl=NO_MOVE_ANIMATION EDGE_TO_EDGE_ENFORCED FORCE_DRAW_STATUS_BAR_BACKGROUND FIT_INSETS_CONTROLLED
[11:20:04.174] SEG3_tplus95s_WINDOW_KEEP_SCREEN_ON=false
[11:20:04.222] SEG3_tplus95s_POWER_mWakefulness=Awake mHoldingDisplaySuspendBlocker=true
```

自 `TAP_AT=11:16:40.308` 起算，`SEG3` 采样于 `11:20:04.174` = **+203.866 s**，已越过 +90 s 窗口。

### 4.4 ⚠️ 一处**脚本自身的**假阴性（如实记，不掩盖）

脚本最后一行汇总打出：
```
[11:20:04.285] VERDICT_r8v3 raised=true release=false plus90=false
```
- `raised=true` ✅
- **`release=false` 是脚本 bug**：`KeepState` 函数里 `L()` 有 `Write-Output`，导致 `$seg2` 收到的是**整个多行数组**而不是 `'false'` 字符串，于是 `$seg2 -eq 'false'` 判假。
- **权威值是原始行**：`SEG2_afterEnd_WINDOW_KEEP_SCREEN_ON=false`（§4.2）、`SEG3_tplus95s_WINDOW_KEEP_SCREEN_ON=false`（§4.3）。
- ⇒ **第二段、第三段实际都成立**，以原始行为准。同一 bug 也出现在 `SUMMARY_r8v3` 里（`SEG2_afterEnd=[…多行…] false`）。

---

## 5. R8 vs 非 R8：同机同轮对照

> 同一台 `emulator-6330`、同一镜像、同一几何、同一后端、同一流程（进会话 → 真点 Send → 三段采样）各跑一遍。

两包**都跑通了「真点 Send 驱动回合 + 常亮三段」**，行为一致。

| 维度 | R8 `0598817026…` / 1,435,193 B | 非 R8 `76ca064b…` / 3,975,389 B | 是否一致 |
|---|---|---|---|
| 装前 md5 复核 | `0598817026665544a08803055107a9fd` | `76ca064b7de9246ee446f765a9198b37` | — |
| **设备端 base.apk md5** | `0598817026665544a08803055107a9fd` **`MATCH=True`** | `76ca064b7de9246ee446f765a9198b37` **`MATCH=True`** | ✅ 各等于各自装前值 |
| 安装 | `Performing Streamed Install \| Success` | `Performing Streamed Install \| Success` | ✅ |
| 鉴权 + 数据到达 | `AUTHFAILED=0` `probe OK=True` `FIRSTCONTENT=True` `PRE_LIST_ROWS=10` | `AUTHFAILED=0` `probe OK=True` `FIRSTCONTENT=True` `PRE_LIST_ROWS=10` | ✅ |
| 前置断言 | `PRECONDITION_PASS=True` | `PRECONDITION_PASS=True` | ✅ |
| 进会话（真点） | `input tap 540 558` | `input tap 540 558` | ✅ 同一点 |
| composer（真点聚焦） | `[52,1270][1029,1433]` → `tap 540 1352` | `[52,1270][1029,1433]` → `tap 540 1352` | ✅ |
| **Send 按钮 bounds（当场解析）** | `text=[Send]` `[939,1330][1008,1396]` → `tap 974 1363` | `text=[Send]` `[939,1330][1008,1396]` → `tap 974 1363` | ✅ 逐像素相同 |
| **回合是否被真点驱动** | `ROUND_STARTED_BY_UI_TAP=YES` `t+2s` | `ROUND_STARTED_BY_UI_TAP=YES` `t+2s` | ✅ |
| 段一 置起 | **10/10 `true`** @`11:16:44`–`11:17:03` | **8/8 `true`** @`11:21:25`–`11:21:40` | ✅ |
| 段二 释放 | `false` @`11:18:29.007` | `false` @`11:23:05.367` | ✅ |
| 段三 +95 s | `false` @`11:20:04.174`（tap 后 +203.9 s） | `false` @`11:24:40.542`（tap 后 +199.6 s） | ✅ |
| `dumpsys power` | `Awake / mHoldingDisplaySuspendBlocker=true` | 同 | ✅ |
| 包体积 | 1,435,193 B | 3,975,389 B | **R8 小 63.90%** |

**非 R8 侧关键原始行**：

```
[11:20:23.245] INSTALL=Performing Streamed Install | Success
[11:20:23.430] MDSUM_RAW=[76ca064b7de9246ee446f765a9198b37 /data/app/~~VHxADIYcmyCRLcE5wgDR4A==/.../base.apk | ]
[11:20:23.435] DEVICE_MD5_MATCH=True
[11:21:07.976] RENDER_WAIT try=1 rows=10
[11:21:08.035] PRECONDITION_PASS=True
[11:21:08.042] STEP_A_TAP_CMD=adb -s emulator-6330 shell input tap 540 558
[11:21:20.951] STEP_D_SEND_PICKED how=text cls=android.widget.Button text=[Send] bounds=[939,1330][1008,1396] center=(974,1363)
[11:21:20.952] STEP_D_TAP_CMD=adb -s emulator-6330 shell input tap 974 1363
[11:21:23.081] ROUND_STARTED_BY_UI_TAP=YES (logcat roundActive=true) t+2s
[11:21:23.269] SEG0_idle_WINDOWFLAGRAW: fl=KEEP_SCREEN_ON LAYOUT_IN_SCREEN LAYOUT_INSET_DECOR SPLIT_TOUCH HARDWARE_ACCELERATED DRAWS_SYSTEM_BAR_BACKGROUNDS
[11:21:40.311] SEG1_RAISED_SEEN=true
[11:23:05.367] SEG2_afterEnd_WINDOW_KEEP_SCREEN_ON=false
[11:24:40.542] SEG3_tplus95s_WINDOW_KEEP_SCREEN_ON=false
[11:24:40.651] VERDICT_nor8 raised=true release=false plus90=false      ← 同样是 §4.4 的脚本 bug，以原始行为准
```

> **本轮首次真正闭环了 F12 未闭环的这一格**：F12 明确写着「非 R8 端因数据播种与页面未起，没有跑成，所以**没有**『R8 vs 非 R8 同机同轮对照』」。本轮非 R8 端**播种成功、前置通过、真点驱动成功、三段齐全**。

**结论**：**R8 未因 minify/shrink 引入常亮三段的行为差异** —— 两个包在同一台设备、同一轮次内，走同一套「真点官方 UI Send」流程，置起/释放/+90 s 不复起三段**行为一致**。

---

## 6. 过程中的真实卡点（都已解决，如实记录）

### 6.1 卡点一：等渲染（`uiautomator` 抓到的是还没渲染的骨架）
第一次 dump 在 `am start` 后 ~17 s，`rows=0`；**等到 ~26 s 才 `rows=10`**。
⇒ 修法：轮询 dump 直到 `clickable Button 且有文本且 bounds 非 0` 的行 ≥3 再继续。
> 这正是「数据已到、不是骨架屏」这条前置断言必须**轮询**而不能只 sleep 一次的原因。

### 6.2 卡点二：顺序错了 ⇒ 点到的是**会话行**不是发送按钮
初版脚本先找 Send 按钮、后找会话，而当时页面**根本不在会话里**（还在列表页）。回退规则于是挑中了列表页最右一行的可点节点
`text=[S Count from 1 to 999, one number per line System32]`，tap 后只打开会话，**没有发送任何消息**，`ROUND_STARTED_BY_UI_TAP=NO`。
> 教训：**必须先真点进会话，再在会话内找 composer 与 Send**。修正顺序后即通。

### 6.3 卡点三：顶部搜索框被误认成 composer
列表页唯一的 `EditText` 在 `bounds=[147,299][1008,367]`（**顶部搜索框**），初版把它当 composer 敲了字。
> 修法：composer 取 **`y1 >= 900` 的底部 EditText**；会话内实测在 `[52,1270][1029,1433]`。

### 6.4 已确认**可行**（推翻 F11 的悲观结论）
F11 记录「`uiautomator` 够不到 WebView DOM / 发送按钮找不到」。本轮实测**反例**：
WebView 内的按钮、会话行、composer **都带真实 `text` 与 `bounds`**（`Send` 的 `text=[Send]` 明确可见）。
差别在于**必须等 WebView 渲染完再 dump**（§6.1），且**要先进入会话**（§6.2）。

---

## 7. 收尾

### 7.1 `wm` 覆盖已 reset

```
$ adb -s emulator-6330 shell wm size reset
$ adb -s emulator-6330 shell wm density reset
$ adb -s emulator-6330 shell wm size
Physical size: 2560x1600
$ adb -s emulator-6330 shell wm density
Physical density: 320
```
⇒ **Override 已清除**，回到物理值（`2560x1600` / `320`）。

### 7.2 `adb reverse` 已摘除

```
$ adb -s emulator-6330 reverse --remove tcp:4095
REVERSE_AFTER=            ← 空，已摘干净
```

### 7.3 关掉自建实例（kill 前先核对归属）

```
PID=51084 AVD=Pixel_Tablet  PORT=5670     ← 别人的，不碰
PID=44512 AVD=Pixel_Tablet  PORT=5670     ← 别人的，不碰
PID=32388 AVD=OC_Deliver_R2 PORT=6330     ← MINE
PID=2276  AVD=OC_Deliver_R2 PORT=6330     ← MINE

$ adb -s emulator-6330 emu kill
OK: killing emulator, bye bye
OK
```

### 7.4 收尾复核输出（kill 后 25 s）

```
=== emulator/qemu process count (ALL) ===
PROC_COUNT=2
PID=51084 AVD=Pixel_Tablet PORT=5670
PID=44512 AVD=Pixel_Tablet PORT=5670
=== my AVD (OC_*/Medium_Desktop_B) remaining ===
MINE_COUNT=0
=== ports 6330/6332/6334 LISTEN ===
PORT 6330 LISTEN=FREE
PORT 6332 LISTEN=FREE
PORT 6334 LISTEN=FREE
=== backend 4095 (must be untouched) ===
4095 LISTEN pid=3748
=== adb devices ===
List of devices attached
emulator-5670	device
=== gradle lock ===
.agent-gradle-lock exists = False
```

- ✅ 自建 AVD 进程 **0**（`MINE_COUNT=0`）
- ✅ 端口 6330/6332/6334 全 FREE
- ✅ **后端 `4095` 全程未停**，`pid=3748` 与开工前**同一 pid**（只做过 `adb reverse`，现已摘除）
- ✅ `.agent-gradle-lock` 不存在
- ✅ **未执行** `adb kill-server`；**未执行** `-a` / 通配符 / 全局 kill —— 一律只按 **serial** 精确操作

### 7.5 🔴 我碰过哪些 serial

| serial | 归属 | 我做了什么 | 是否 kill |
|---|---|---|---|
| **`emulator-6330`** | **自建**（`OC_Deliver_R2`，我 11:05:46 启动） | 装包、播种口令、真点 UI、采样、`wm reset`、`reverse --remove`、`emu kill` | ✅ 已关 |
| `emulator-5670` | **别人的**（`Pixel_Tablet` / 产品负责人侧） | **只出现在 `adb devices` / 进程列表的只读枚举里** | ❌ **没碰** |
| `emulator-5660` | **别人的**（`T78_Phone`） | **只在开工时的只读 `adb devices` 里出现** | ❌ **没碰** |
| `emulator-5662` | **别人的**（`DSH_T80_Tablet`） | **只在开工时的只读 `adb devices` 里出现** | ❌ **没碰** |

### 7.6 🔴 我有没有碰过任何非自建实例？

**没有。** 明确声明：

- 本轮**只对 `emulator-6330`（自建）**下发过任何会改变状态的命令（`install` / `pm` / `input` / `dumpsys` / `wm` / `reverse` / `emu kill`）。
- `emulator-5670` / `5660` / `5662`（`Pixel_Tablet` / `T78_Phone` / `DSH_T80_Tablet`，以及开工时枚举到的 `MCRemote_*`）**全程只被"读"过**（`adb devices` 与 `Get-CimInstance` 进程枚举），**没有下发过任何命令，更没有 kill**。
- **未为抢端口去关任何别人的实例** —— 6330 开工前实测 FREE，直接用。
- **未执行** `adb kill-server`、**未执行** `adb -a` / 通配符 / 全局 kill。
- 本机 `emulator/qemu` 进程开工时 6 个（含我未启动的）+ 我起的 2 个 = 8 个，收尾时剩 2 个（`Pixel_Tablet` ×2，别人），**净减 2 个 = 恰好是我起的那两个**。

---

## 8. 未闭环 / 需下一轮

1. **SEG0 标签名不副实**：`SEG0_idle` 那次采样实际落在「回合已开始后 0.2 s」，不是真 idle 基线。真 idle 基线见 F12 `@02:31:53.789` 的 `false`。⇒ 需要把 `SEG0` 挪到 tap 之前重采（本轮时间不够）。
2. **`VERDICT` 行 `release` 字段是脚本 bug**（§4.4），判定应以 `*_WINDOW_KEEP_SCREEN_ON=` 原始行为准。脚本需修 `KeepState` 的返回值。
3. **`input text` 词间空格丢失**（§3.5，`f13uitap`），要拿到带空格的正文需改用 `input text "a%sb"` 或 `adb shell input keyboard text`。
4. **词间空格 + 真实长文本**未覆盖：本轮只敲了 2 个短词，未验证长文本/特殊字符在真点路径下的转义。
5. **未测「连续两回合」**：单回合三段成立，两回合之间 flag 是否正确反复，未测。
