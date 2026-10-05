# R8_KEEPAWAKE_F12 — R8 版常亮"回合进行中置起"段闭环验证（F12 轮）

> 角色：收尾验证工程师（F12：补上 R8 版常亮的"回合进行中置起"段 —— F11 把它如实留成 🟡，本轮负责闭环它）
> 日期：2026-10-05 10:25–10:53 (+08:00) ｜ 硬期限 2026-10-07 前
> 设备：`emulator-6310`（AVD `OC_Deliver_R2`，端口 **6310**，**本轮自建 → 本轮结束前已自行退出**）
> 写权限：`research/web/**` + `research/**` ｜ **未碰**：`app/**`、`webapp/**`（含 `build.gradle.kts`，**本轮不改构建、只验证**）、`_upstream/**`、`.github/**`、`research/deliver/**`（只读）
> 后端 `127.0.0.1:4095`：**全程未起停**（开轮与收尾均 `LISTEN pid=3748`，同一 pid），只做 `adb reverse`

---

## 0. 一句话结论

**R8 版常亮三段（含 +90 s）本轮全部闭环** —— 置起这一跳**证到了**，且拿到**两条互相独立的证据**（应用自身日志 `flagSet=true` + `dumpsys window` 窗口属性 `fl=KEEP_SCREEN_ON`）。

| 段 | 结果 | 依据 |
|---|---|---|
| ① 回合进行中**置起** | ✅ **PASS（本轮首次闭环）** | §4.2：应用日志 `flagSet=true` @ `02:31:53.237`；窗口属性 `fl=KEEP_SCREEN_ON …` 连续 **16/16** 个采样点为真（t+6 s…t+36 s） |
| ② 回合结束 ⇒ **释放** | ✅ **PASS** | §4.3：`roundActive=false flagSet=false` @ `02:32:35.710`（置起后 **+42.473 s**）；窗口属性回 `false` |
| ③ **+90 s 兜底**仍不挂回 | ✅ **PASS** | §4.4：兜底复查 @ `02:33:23.240`（置起后 **+90.003 s**）读到 `roundActive=false` ⇒ 保持释放；窗口属性仍 `false` |

> ⚠️ **但"默认推荐是否改用 R8"本轮不动**，理由见 §6：派单要求用**本轮同机同轮的数**重新确认首屏，而本轮**非 R8 对照侧没跑出来**（§5）⇒ **理由不足，保持非 R8 为默认**。

---

## 1. 装前 md5 复核（`Get-FileHash` 原文）

```
R8_PREINSTALL_MD5   = 0598817026665544a08803055107a9fd
R8_PREINSTALL_SIZE  = 1435193
R8_EXPECT_MD5       = 0598817026665544a08803055107a9fd
R8_EXPECT_SIZE      = 1435193
R8_MD5_MATCH        = True
NOR8_MD5            = 76ca064b7de9246ee446f765a9198b37
NOR8_SIZE           = 3975389
```

✅ **R8 装前 md5 与体积逐位复核一致**（`0598817026665544a08803055107a9fd` / 1,435,193 B），装的就是被测物。

⚠️ **诚实记账**：**设备端 `base.apk` 的 md5 本轮没取到**。探针里 `md5sum` 的输出经 `Sh` 压平后正则没匹配上（`DEVICE_MD5=` 为空、`DEVICE_MD5_MATCH=False`），我**没有**修好它就往下走了。
安装侧的成功证据是 `INSTALL=Performing Streamed Install | Success` 与随后应用能正常启动/鉴权/跑真实回合；
**这一项按"未取到"记账，不冒充通过**（V6 轮做过同样的设备端 md5 比对，本轮不复用那个数）。

---

## 2. 环境事实

| 项 | 值 |
|---|---|
| 设备 | `emulator-6310`，AVD `OC_Deliver_R2`（本轮自建） |
| 镜像 | `android-35;google_apis_playstore_tablet` + `pixel_tablet`（**不传 `-skin`**，`pixel_tablet` 非合法 skin 名） |
| 几何 | `wm size 1080x1600` + `wm density 420` 覆盖（原文：`Physical size: 2560x1600 \| Override size: 1080x1600`、`Physical density: 320 \| Override density: 420`） |
| 端口选择 | **6310**（偶数、≥6310），启动前 6310–6350 全部 `FREE` |
| 后端 | `4095 LISTEN pid=3748`（开轮 = 收尾，**未起停**），`REVERSE=host-16 tcp:4095 tcp:4095` |
| 包名 | `com.xiufeigo.opencodeshell.web.debug`（两版同包名 ⇒ 互相覆盖安装） |

---

## 3. 前置断言（已鉴权 + 数据已到，非骨架屏）

```
[10:31:53.589] PROXY_LISTEN_LINE=listening on http://127.0.0.1:43511
[10:31:53.590] PROXY_PORT=43511
[10:31:53.594] PRE_AUTHFAILED=0
[10:31:53.596] PRE_PROBE_OK=True
[10:31:53.600] PRE_SESSION_IDS=2 -> ses_efc722bf9ffeWaRdoFTwbmYj1Y, ses_f408ec4a0ffeKjpcGKG4kfGEBR
[10:31:53.601] PRE_FIRSTCONTENT=True
```

✅ **PASS**：拿到 **2 个真实 session id**（`ses_…`），`AUTHFAILED=0`、`probe OK`、`firstContent` 出现 ⇒ **数据确实到了，不是骨架屏**。

> `PRE_API_GETS_UNIQUE=0` 是我探针的正则缺陷（`GET /api/…` 行被 `Sh` 压平成单行后没匹配上），**不影响**上面三条独立判据；如实记账，不当 PASS 算。

---

## 4. 三段原始输出（含时间戳）

### 4.0 置起的触发源：一个**真实回合**（POST 消息）

```
10-05 02:31:53.237  3689  3911 I OcWebHost/Proxy: POST http://127.0.0.1:4095/api/session/ses_efc722bf9ffeWaRdoFTwbmYj1Y/message
10-05 02:31:53.237  3689  3911 I OcWebHost/Proxy: D1 roundActive=true ageMs=0
10-05 02:31:53.237  3689  3689 I OcWebHost: D1 applyKeepScreenOn: roundActive=true foreground=true shouldKeep=true flagSet=true
```

⚠️ **口径说明（如实记账）**：这条 POST 是我**从设备 shell 用 `curl` 打到应用内回环代理的 `…/message` 端点**发的，不是用官方 UI 的发送按钮点的。
它是**真实回合**：请求经**应用内 `LoopbackProxy` 转发**到后端（`LoopbackProxy.kt:321-324` 正是把"页面 POST 一条发消息请求"当作回合开始信号的那一段），后端真的把该会话置为 busy 并产生模型输出。
**但它不覆盖"手指点官方 UI 发送按钮"这一条交互路径** ⇒ 这一点按未覆盖记账。

### 4.1 段①：回合进行中 —— **置起（本轮首次证到）**

**证据 A —— 应用自身日志（带设备时间戳）**：

```
10-05 02:31:53.237  3689  3689 I OcWebHost: D1 applyKeepScreenOn: roundActive=true foreground=true shouldKeep=true flagSet=true
```

**证据 B —— `dumpsys window` 窗口属性（独立于应用日志）**：

```
[10:32:06.613] SEG1_s05_tplus12s_WINDOWFLAGRAW: fl=KEEP_SCREEN_ON LAYOUT_IN_SCREEN LAYOUT_INSET_DECOR SPLIT_TOUCH HARDWARE_ACCELERATED DRAWS_SYSTEM_BAR_BACKGROUNDS
[10:32:06.613] SEG1_s05_tplus12s_WINDOWFLAGRAW: pfl=NO_MOVE_ANIMATION EDGE_TO_EDGE_ENFORCED FORCE_DRAW_STATUS_BAR_BACKGROUND FIT_INSETS_CONTROLLED
[10:32:06.614] SEG1_s05_tplus12s_WINDOW_KEEP_SCREEN_ON=true
[10:32:06.670] SEG1_s05_tplus12s_POWER_mWakefulness=Awake mHoldingDisplaySuspendBlocker=true
```

回合进行期间 **2 s 一采、连采 16 次，`KEEP_SCREEN_ON=true` 命中 16/16**：

```
[10:32:00.252] SEG1_s02_tplus6s_WINDOW_KEEP_SCREEN_ON=true
[10:32:02.367] SEG1_s03_tplus8s_WINDOW_KEEP_SCREEN_ON=true
[10:32:04.489] SEG1_s04_tplus10s_WINDOW_KEEP_SCREEN_ON=true
[10:32:06.614] SEG1_s05_tplus12s_WINDOW_KEEP_SCREEN_ON=true
[10:32:08.734] SEG1_s06_tplus14s_WINDOW_KEEP_SCREEN_ON=true
[10:32:10.853] SEG1_s07_tplus16s_WINDOW_KEEP_SCREEN_ON=true
[10:32:12.981] SEG1_s08_tplus18s_WINDOW_KEEP_SCREEN_ON=true
[10:32:15.091] SEG1_s09_tplus20s_WINDOW_KEEP_SCREEN_ON=true
[10:32:17.235] SEG1_s10_tplus22s_WINDOW_KEEP_SCREEN_ON=true
[10:32:19.344] SEG1_s11_tplus24s_WINDOW_KEEP_SCREEN_ON=true
[10:32:21.452] SEG1_s12_tplus26s_WINDOW_KEEP_SCREEN_ON=true
[10:32:23.577] SEG1_s13_tplus28s_WINDOW_KEEP_SCREEN_ON=true
[10:32:25.704] SEG1_s14_tplus30s_WINDOW_KEEP_SCREEN_ON=true
[10:32:27.845] SEG1_s15_tplus32s_WINDOW_KEEP_SCREEN_ON=true
[10:32:29.970] SEG1_s16_tplus34s_WINDOW_KEEP_SCREEN_ON=true
[10:32:32.092] SEG1_s17_tplus36s_WINDOW_KEEP_SCREEN_ON=true
[10:32:32.228] SEG1_RAISED_SEEN=true
```

> **这一段同时补上了 F11 §5.7 记的口径弱点**：F11 三版探针都没从窗口属性独立判读 0x80 位，只能靠应用日志 `flagSet=`；
> 本轮**修好了探针**（见 §7.2），`fl=KEEP_SCREEN_ON` 是**窗口属性上的独立证据**。

### 4.2 段②：回合正常结束 ⇒ 释放

```
10-05 02:32:35.710  3689  3915 I OcWebHost/Proxy: D1 roundActive=false ageMs=0
10-05 02:32:35.710  3689  3689 I OcWebHost: D1 applyKeepScreenOn: roundActive=false foreground=true shouldKeep=false flagSet=false
```

- 置起 `02:31:53.237` → 释放 `02:32:35.710` = **+42.473 s**，`flagSet=false`。

窗口属性侧（回合结束后采样）：

```
[10:38:18.688] SEG2_afterEnd_WINDOWFLAGRAW: fl=LAYOUT_IN_SCREEN LAYOUT_INSET_DECOR SPLIT_TOUCH HARDWARE_ACCELERATED DRAWS_SYSTEM_BAR_BACKGROUNDS
[10:38:18.690] SEG2_afterEnd_WINDOW_KEEP_SCREEN_ON=false
[10:38:30.941] SEG2_afterEnd_POWER_mWakefulness=Awake
```

`KEEP_SCREEN_ON` 已从 `fl=` 列表里消失 ⇒ **释放成立**。

> ⚠️ **诚实标注机制归因**：`+42.473 s` 这个时刻与 F4 §5.4 记的"`idleMs >= 35_000` 判据在一次流退出时成立"吻合，
> 即**本轮设备上让它发生的是"心跳不续命 ⇒ 35 s 静默判据首次可达"**这一条。
> F4 新增的 `ROUND_END`（`session.status`→`idle` / `session.idle`）标记快路径**本轮同样没有被观测到触发** ⇒ **不能拔高成"标记快路径已验证生效"**。
> 这满足验收要求（"回合结束 ⇒ 释放"成立），但归因按这句写。

### 4.3 段③：+90 s 兜底 ⇒ 仍 `false`（不挂回）

```
10-05 02:33:23.240  3689  3689 I OcWebHost: D1 applyKeepScreenOn: roundActive=false foreground=true shouldKeep=false flagSet=false
```

- 置起 `02:31:53.237` → `02:33:23.240` = **+90.003 s**，正是宿主 **90 s 兜底复查**那一跳；
- 它读到的是 `roundActive=**false**` ⇒ **保持释放**（这正是 F4 修掉的那个"兜底又把旗挂回去"的缺陷，R8 版上**没有复现**）。

窗口属性侧（+95 s 采样）：

```
[10:40:26.411] SEG3_tplus95s_WINDOWFLAGRAW: fl=LAYOUT_IN_SCREEN LAYOUT_IN_SCREEN … （无 KEEP_SCREEN_ON）
[10:40:26.413] SEG3_tplus95s_WINDOW_KEEP_SCREEN_ON=false
[10:40:26.477] SEG3_tplus95s_POWER_mWakefulness=Awake
```

### 4.4 三段汇总（R8 本轮）

| 段 | 时刻（设备） | 应用日志 | 窗口属性 | 判定 |
|---|---|---|---|---|
| 空闲基线 | `02:31:53.789` | — | `KEEP_SCREEN_ON=false` | ✅ 空闲不置旗（正确） |
| ① 回合进行中 | `02:31:53.237` | `roundActive=true shouldKeep=true **flagSet=true**` | **`KEEP_SCREEN_ON=true` ×16** | ✅ **置起成立** |
| ② 回合结束 | `02:32:35.710`（+42.473 s） | `roundActive=false shouldKeep=false flagSet=false` | `false` | ✅ **释放成立** |
| ③ +90 s 兜底 | `02:33:23.240`（+90.003 s） | `roundActive=false flagSet=false` | `false` | ✅ **未挂回** |
| ③ 窗口复核 | `02:40:26.411`（+9 min） | — | `false` | ✅ |

**本轮 R8 在这一轮里只出现两种 `D1 applyKeepScreenOn` 取值**（全集，无第三种）：

```
roundActive=true  foreground=true shouldKeep=true  flagSet=true     ← 仅回合进行中
roundActive=false foreground=true shouldKeep=false flagSet=false    ← 其余时刻
```

---

## 5. R8 vs 非 R8 **同机同轮**并列

| 维度 | 非 R8 `76ca064b…`（3,975,389 B） | R8 `0598817026…`（1,435,193 B） | 判读 |
|---|---:|---:|---|
| **常亮①置起** | ❌ **本轮未取到** | ✅ **PASS**（`flagSet=true` + 窗口 `KEEP_SCREEN_ON` ×16） | 对照侧缺数 |
| **常亮②释放** | ❌ **本轮未取到** | ✅ **PASS**（+42.473 s `flagSet=false`） | 对照侧缺数 |
| **常亮③+90 s** | ❌ **本轮未取到** | ✅ **PASS**（+90.003 s 读到 `roundActive=false`） | 对照侧缺数 |
| 鉴权 + 数据到达 | ❌ **本轮未取到** | ✅ 2 个 session id、`AUTHFAILED=0` | 对照侧缺数 |
| 体积 | 3,975,389 B | 1,435,193 B | **−63.90%** |

### 5.1 非 R8 侧为什么没数（**如实写，不编、不跨轮沿用**）

非 R8 对照**装了**（`APK_MD5=76ca064b7de9246ee446f765a9198b37`、`INSTALL=… Success`），但**应用始终没起来**：

```
[10:45:32.093] PASSWORD_FIELDS=1
[10:45:36.704] PASSWORD_TYPED
[10:45:38.837] SEED_SAVE_TAPPED=(854,967)
[10:49:44.622] PROXY_LISTEN_LINE=NOT_FOUND
[10:49:44.624] PROXY_PORT=0
[10:49:44.626] PRE_AUTHFAILED=0
[10:49:44.627] PRE_PROBE_OK=False
[10:49:44.631] PRE_SESSION_IDS=0 ->
[10:49:44.633] PRECONDITION_PASS=False
```

我随后起了一次"不重装、只重启 Activity + 重播种口"的补跑（`research/web/_f12_nor8.ps1`），
但**在我读到它的播种结果之前，`emulator-6310` 已经自行退出**（见 §7.1）⇒ 补跑没有取到任何段的数据。

⚠️ **因此**：本轮**没有**"R8 vs 非 R8 的同机同轮常亮三段并列数"。
**我不用 F11 的 644/691 ms、也不用 V12 的数字来填这张表**（派单明令禁止跨轮沿用），
非 R8 侧的常亮行为**本轮保持"未测"**，**不因 R8 通过就代它下结论**。

---

## 6. 结论

### 6.1 R8 常亮三段：**全部闭环（可升级为默认推荐候选）**

| 判定项 | 结果 |
|---|---|
| 常亮三段（置起 / 释放 / +90 s） | ✅ **全部闭环** —— 置起这一跳**本轮首次证到**，且有应用日志 + 窗口属性**两条独立证据** |
| 加上 F11 已验 | 口令存储（Tink）、JS 桥、错误态三态、`shrinkResources`、无崩溃 ⇒ **R8 版功能面已全部设备验证** |

### 6.2 默认推荐：**本轮不动，仍是非 R8**

派单要求"改默认推荐要用**本轮同机同轮**的数重新确认首屏，理由不足就保持非 R8"。本轮：

- **首屏本轮完全没测**（我只跑常亮三段）；
- **非 R8 对照侧本轮没跑出来**（§5.1）；
⇒ **没有任何本轮同轮数据支持"改默认推荐"** ⇒ **保持 `opencode-shell-web-release-nor8.apk` 为默认推荐，不变**。
**体积 −63.90% 仍然只是体积/分发侧的收益**，不能替代首屏与行为对照证据。

### 6.3 交接文档：**本轮未改**（超出本轮写权限）

拟改的位置是仓库根的 `HANDOFF_WEB_SHELL.md:25`（R8 那行）：

> 现文含：`🟡 **已设备验证核心项，但默认推荐仍是上一行的非 R8 版** … ❌ **未闭环**：常亮"回合进行中 0x80 置起"段未验`

拟改为（**建议文案，本轮未落盘**）：

> ✅ **已完整设备验证**（F12 补齐常亮三段：置起/释放/+90 s 全 PASS，见 `research/web/R8_KEEPAWAKE_F12.md`）；**默认推荐仍为非 R8 版**（本轮无同轮首屏对照数据，理由不足）。

⚠️ **为什么没落盘**：本轮派单的写权限是 `research/web/**` + `research/perf/**` + `research/env/**` + `research/**`；
`HANDOFF_WEB_SHELL.md` 在**仓库根**，**不在我的授权写入面内** ⇒ 我**没有**改它，**也没有**绕过权限去改。
**需要下一轮（或有根目录写权限的轮次）把上面这段文案落进去。**

> 另注：同文件 `:504-507` 有一节标题仍写着「**② R8 版未产出 —— 设备验证前不要分发**」，F11 轮只改了交付物表那行、**这节没同步**，现已与实际不符，建议一并更正。

---

## 7. 本轮踩的坑（**如实记账，3 个都是我的探针缺陷，不是应用缺陷**）

### 7.1 设备静默退出（形态与 F4 / R2 / V6 轮一致）
`emulator-6310` 在**非 R8 补跑期间自行退出**：收尾复核时 `adb devices` 已无 6310、端口 6310 `FREE`、`OC_Deliver_R2` 进程数 0。
**本轮对它从未发出过任何 kill** ⇒ 退出非本轮主动所致；是否与"几何 2400 > 物理 1600"同源，**本轮不下因果结论**。
**代价**：非 R8 对照侧因此丢数（§5.1）。

### 7.2 窗口 flags 口径：Android 15 是**符号名列表**，不是 `flags=0x…`
F11 §5.7 记的"三版探针都取不到窗口 flags 位"，**根因找到了**：
本镜像（SDK 35 / Android 15）的 `dumpsys window windows` 把窗口 flags 打在 `mAttrs` 的 **`fl=` 符号名列表**里
（`fl=KEEP_SCREEN_ON LAYOUT_IN_SCREEN …`），**根本没有 `flags=0x…` 这种十六进制形式** ⇒ F11 那三版按 `flags=` 取值的正则注定取不到。
**本轮改成在 `fl=`/`pfl=` 行里匹配 `KEEP_SCREEN_ON` 名字** ⇒ 一次就中。**这条口径修正建议回写进环境交接**，避免后续轮次重踩。

### 7.3 回环代理端口是**临时端口**，不是 4095
第一版探针用 `http://127.0.0.1:(\d+)/api/` 从日志里抓端口，抓到的是**后端** `4095` ⇒
`POST /api/session/…/message` **绕过了应用内代理**，服务器返回 `401`，**回合根本没起来**（`SEG1_RAISED_SEEN=false`）。
`LoopbackProxy.kt:186` 用的是 `ServerSocket(0)`（临时端口），权威值在 **`listening on http://127.0.0.1:<port>`** 那一行（本轮 **43511**）。
**这条同样建议回写进环境交接。**

### 7.4 两个小缺陷（如实记账，未修）
- 设备端 `base.apk` md5 提取失败（§1）⇒ 记为"未取到"。
- `PRE_API_GETS_UNIQUE=0` 是压平日志导致的正则失配（§3）⇒ 不影响前置判据。

---

## 8. 收尾复核输出（原文）

```
=== F12 closeout 2026-10-05T10:52:35 ===
OWNER_CONFIRM avd=OC_Deliver_R2 port=6310 MINE=True (self-exited before I could emu kill)
WM_SIZE_RESET: SKIPPED - device 6310 already gone (adb devices no longer lists it)
WM_DENSITY_RESET: SKIPPED - same reason
EMU_KILL: NOT EXECUTED - process already gone; I never issued a kill this round to anything
--- RE-VERIFY 10:52:35:35 ---
emulator/qemu process count = 6
OC_*/Medium_Desktop_B residual = 0
  REMAINING pid=53864 avd=DSH_T80_Tablet port=5662  -> NOT MINE, untouched
  REMAINING pid=31688 avd=DSH_T80_Tablet port=5662  -> NOT MINE, untouched
  REMAINING pid=37456 avd=T78_Phone     port=5660  -> NOT MINE, untouched
  REMAINING pid=44424 avd=T78_Phone     port=5660  -> NOT MINE, untouched
  REMAINING pid=51084 avd=Pixel_Tablet  port=5670  -> NOT MINE, untouched
  REMAINING pid=44512 avd=Pixel_Tablet  port=5670  -> NOT MINE, untouched
port 6310 LISTEN = 0
port 6312 LISTEN = 0
port 6314 LISTEN = 0
adb devices: List of devices attached | emulator-5660 device | emulator-5662 device | emulator-5670 device
backend 4095 (must be untouched, opened round as pid=3748): LISTEN pid=3748
.agent-gradle-lock exists = False
```

**遵守情况**：

- ✅ **谁起谁关**：`emulator-6310`（AVD `OC_Deliver_R2`）本轮自建；**它在收尾前已自行退出**，故本轮**未执行 `emu kill`**（无需杀、也无从杀）。
- ✅ **只关自己的**：按 AVD 名判定，`OC_*/Medium_Desktop_B` 残留 = **0**。
- ✅ **未执行** `adb kill-server`；**未用** `-a`/全量/通配符；只按 serial 操作。
- ✅ 后端 `4095` **全程未起停**（开轮 = 收尾 = `pid=3748`），只做 `adb reverse`。
- ✅ **故意保留：无。**
- ⚠️ **几何 reset 未能执行**：实例已自行消失，`wm size/density reset` 随实例一并消失（覆盖设置不落盘到 AVD 配置）。
- ⚠️ **观察到两台非本轮实例在开轮/收尾之间换了位置**：`DSH_T80_Phone`(5570) 开轮在列、收尾已不在；收尾时出现 `Pixel_Tablet`(5670)。
  **本轮对这两台从未执行过任何命令**，消失/出现原因**未从本轮日志得出因果**，如实记录、不代其它轮次下结论。

---

## 9. 我碰过哪些 serial

**只有一个：`emulator-6310`**（AVD `OC_Deliver_R2`，端口 6310，本轮自建）。

**我有没有碰过任何非自建实例？没有。**
全程只对 `emulator-6310` 发过 adb 命令；对 `emulator-5570` / `5660` / `5662` / `5670`
（用户的 `DSH_T80_Phone` / `DSH_T80_Tablet` / `T78_Phone`，以及非本轮的 `Pixel_Tablet`）
**没有执行过任何命令**（`adb devices` 是全局只读列举，不构成"操作"）。
**没有为抢端口关闭任何别人的实例。**

---

## 10. 未闭环清单

1. 🟡 **非 R8 的同机同轮常亮三段对照数** —— 本轮因应用未起来 + 设备静默退出而**未取到**（§5.1）。**不代它下结论。**
2. 🟡 **"点官方 UI 发送按钮"这条交互路径未覆盖** —— 置起是用 `curl` 打回环代理的 `…/message` 端点驱动的（§4.0）。
3. 🟡 **首屏本轮未测** ⇒ 无本轮同轮数据可用来论证"改默认推荐"（§6.2）。
4. 🟡 **设备端 `base.apk` md5 本轮未取到**（§1）。
5. 🟡 **`ROUND_END` 标记快路径仍未被观测到触发** —— 与 F4 §5.4 同源结论，设备上实际生效的是"心跳不续命 ⇒ 35 s 静默判据可达"（§4.2）。
6. 🟡 **交接文档未落盘** —— `HANDOFF_WEB_SHELL.md:25`（及已过时的 `:504-507`）待有写权限的轮次更正（§6.3）。
7. ⚠️ **建议回写环境交接的两条探针口径**（§7.2 / §7.3）：`fl=` 符号名列表；回环代理是临时端口、要看 `listening on` 那行。

---

## 11. 证据文件索引

| 文件 | 内容 |
|---|---|
| `research/web/out_f12/R8.log` | **R8 三段主证据**（前置断言、置起 ×16、释放、+90 s、窗口 flags 原文） |
| `research/web/out_f12/NOR8.log` | 非 R8 侧记录（安装成功但应用未起来 ⇒ 前置未过） |
| `research/web/out_f12/housekeeping.log` | 收尾复核原文 |
| `research/web/out_f12/boot.log` | 开轮启动日志 |
| `research/web/_f12_keep.ps1` | 本轮主探针（R8 + 非 R8 通用，第一版） |
| `research/web/_f12_nor8.ps1` | 非 R8 补跑探针（不重装，只重启 Activity + 重播种口；因设备退出未取到数） |
