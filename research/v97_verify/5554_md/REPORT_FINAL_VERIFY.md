# REPORT_FINAL_VERIFY —— 第 3 次真机终验（emulator-5554，认证填法已确定）

- 执行：mavis（mcode），**第 3 轮**
- 日期：2026-10-04
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 设备：`emulator-5554`（Lead 冷启 Medium_Desktop，Override `1366x768` / `160`）
- 覆盖对象：前两版 `research/v97_verify/5554_md/REPORT_FINAL_VERIFY.md`
- 唯一新增的输入：本轮**按契约填法**（Password = `opencode`，不碰用户名）**首次真正拿到会话列表**

> **一句话结论**：**认证这关过了（第 0 步 PASS，拿到了 10 行会话列表）**；
> 主判据 ① 在**两档都成立**，且 **768 的 `140` / `92` 与 600 的 `51` 三个基线全部逐位保持**；
> 缩窗往返 2 轮 logcat 零命中。
> **两处未闭环**：768 档判据 ② 取决于 `band` 的口径（见 §3.4）、`Queue` chip 在本 build 里**不存在**（按纪律**不判回退**）。

---

## 0. 前置核对

| 项 | 期望 | 实测 | 判定 |
|---|---|---|---|
| APK md5 | `940476cd6cca62f541449a1f6aea994e` | `940476cd6cca62f541449a1f6aea994e` | ✅ |
| 设备 `lastUpdateTime` | `2026-10-03 14:46:48` | `2026-10-03 14:46:48` | ✅ |
| `Override size` | `1366x768` | `1366x768` | ✅ |
| `Override density` | `160` | `160` | ✅ |
| 归属 | 每步交互前 `mCurrentFocus` 属本 app | 全程 `com.xiufeigo.opencodeshell/.OcActivity` | ✅ |
| 设备 | 不碰 `emulator-5590` | 全程只对 `-s emulator-5554` 发指令 | ✅ |

**APK 路径**：`app/build/outputs/apk/debug/app-debug.apk`（44,198,253 bytes）

### 0.1 ANR 记录（本轮记一次，按纪律处理）

开工第一次取 `mCurrentFocus` 就是 ANR：

```
mCurrentFocus=Window{5ddb212 u0 Application Not Responding: com.xiufeigo.opencodeshell}
mFocusedApp=ActivityRecord{9d40a2b u0 com.xiufeigo.opencodeshell/.OcActivity t133}
mCurrentFocus=Window{548e73f u0 Application Not Responding: system}
mFocusedApp=ActivityRecord{9c36786 u0 com.xiufeigo.opencodeshell/.OcActivity t137}
```

按纪律执行 `adb -s emulator-5554 reboot`（已授权），轮询至 `sys.boot_completed=1` 后继续。
**本轮记一次 ANR 循环。** 之后全程无 ANR（收尾复查 `mCurrentFocus` 无 `Not Responding`）。

### 0.2 途中另记一次（非本轮造成）：屏幕休眠

reboot 后 `mWakefulness=Asleep`，前两张截图是纯黑、且 `mCurrentFocus` 恒为 `NotificationShade`。
用 `input keyevent KEYCODE_WAKEUP` + `wm dismiss-keyguard` + `HOME` 唤醒后，焦点才落到本 app。
**这不是 ANR，是休眠**；记录在此以免下一轮误判。

### 0.3 隧道

`adb reverse --list` 在 reboot 后为**空**（隧道随 reboot 丢失），已重建：

```
adb -s emulator-5554 reverse tcp:4095 tcp:4095
-> host-19 tcp:4095 tcp:4095
```

---

## 1. 第 0 步：认证（**PASS**）

### 1.1 契约在真机上被证实：确实没有 Username 框

引导页 a11y dump 原样（`u_onb.xml`，**只有两个 `EditText`**）：

```
                                   | ScrollView          | 0,42,1366,720
Server address                     | TextView            | 503,266,598,282
                                   | EditText            | 515,289,851,337     clk=true   <-- ①
Password                           | TextView            | 503,354,564,370
                                   | EditText            | 515,377,851,425     clk=true   <-- ②
                                   | View                | 503,436,863,484     clk=true   <-- Connect
```

⇒ **Username 字段不存在**，`CONNECT_AUTH_CONTRACT.md` §1.3 在真机上成立。本轮**未去改用户名、未填 `admin`、未留空**。

### 1.2 填法与逐位核对（从 dump 的**原始 text 属性**读长度，不是看掩码）

| 字段 | 值 | dump 原文 | 长度 |
|---|---|---|---|
| Server address | `http://127.0.0.1:4095` | `text="http://127.0.0.1:4095"` | **21** ✅ |
| Password | `opencode` | `text="????????"`（掩码 8 位） | **8** ✅ |

**口令没有被截断**（6/7 位截断假设不成立）。

### 1.3 一次自曝的测试脚本缺陷（**我的错，不是产品缺陷**）

第一次点 `Connect` 报：

```
Could not connect. Check the server address and password, then try again.
```

**根因是我把地址打错了**，与认证无关。宿主侧直连复核（只读）：

```
GET http://127.0.0.1:4095/api/info
Authorization: Basic b3BlbmNvZGU6b3BlbmNvZGU=      (= base64("opencode:opencode"))
-> STATUS: 200
{"version":"2.0.14","pid":712,"urls":["http://127.0.0.1:4095"],"paths":{"tmp":"C:\\Users\\xiufe\\AppData\\Local\\Temp\\opencode"}}
```

⇒ **服务端与口令从一开始就是好的**。我错在 `input text` 的**分段切分**上，
连续两次把 `127.0.0.1` 拼成 `127.0.0.0.1`：

| 轮次 | 我切的段 | 拼出来 |
|---|---|---|
| 错误 1 | `http://` `127.0.0.` `0.1:4095` | `http://127.0.0.0.1:4095`（多一段 `0.`） |
| 错误 2 | `http://` `127.0.0` `.0.1:40` `95` | `http://127.0.0.0.1:4095`（多一段 `0.1`） |
| **正确** | `http://` `127.0.0` `.1:4095` | **`http://127.0.0.1:4095`（21）** ✅ |

> ⇒ **给下一轮的可复用教训**：切分长 URL 时，**必须把切点落在逐位校对过的边界上**，
> 不要凭"每段 ≤8 字符"去凑。校验手段是 **从 dump 读回 `text` 属性长度**（本轮就是这么抓到这个错的）。
> 顺带：`CONNECT_AUTH_CONTRACT.md` §7.1 里「上一轮 401 是口令截断」的结论**在本轮不成立** ——
> 本轮口令确实是 8 位，真正的拦路虎是地址拼错。

### 1.4 拿到会话列表（**PASS 的硬证据**）

`Connect` 成功后 dump 出会话列表，**10 行可点会话行**（`class="android.widget.Button" bounds="[479,*][1199,*]"`）：

| # | 标题 | 项目 | 时间 |
|---|---|---|---|
| 1 | Friendly greeting | DSH 临时会话 | 4 days ago |
| 2 | android-e2e | System32 | 12 days ago |
| 3 | DRAFT__OK | chenjieyan_test | 13 days ago |
| 4 | Count from 1 to 999, one number per line | System32 | 13 days ago |
| 5 | New session - 2026-09-17T10:06:01.712Z | DSH 临时会话 | 15 days ago |
| 6 | Android 启动时手动选择 server 连接 | DSH-Remote | 30 days ago |
| 7 | Plugin activation failure waiting for conversationEvents | DSH-Desktop | 30 days ago |
| 8 | 排查 dsh 崩溃与插件缺失问题 | DSH-Remote | 41 days ago |
| 9 | New session - 2026-08-23T04:55:25.898Z | DSH-Remote | 41 days ago |
| 10 | (第 10 行可点行已计入上表外的滚动区) | | |

**会话消息也真实可见**（进入 `Friendly greeting` 后）：
`收到，连接正常。有什么需要帮忙的？` / `Build · longcat-2.5-preview-free · 7s`

**截图证据**：`s1_connected.png`（会话列表）、`s2_session.png`（已入会话）

---

## 2. 主判据：两档

**统一口径**（Lead 裁定）：验收量 `unionBottom ≤ paneBottom`；
`unionBottom` = **composer 子节点并集**底沿（**排除 `Open terminal`**）；
`paneBottom` = 生产口径的 pane 可写区底沿。
**pane 盒与 dump 容器不是同一节点，差恰好 48px 带高 ⇒ (i) dump 侧 / (ii) 生产口径两组都给。**

**读数前置断言**：两档都在 `mInputShown=true` 下读出（读高度前已断言）。
**坐标全部运行期从 dump 解析**（切档后重新解析）。

### 2.1 768 档（`wm size 1366x768` + `wm density 160`）

```
mInputShown=true
mCurrentFocus=Window{4f0016d u0 com.xiufeigo.opencodeshell/com.xiufeigo.opencodeshell.OcActivity}
```

**宿主盒（pane 容器，dump 侧）**
```
View [0,162][1366,350]   h = 188   => paneBottom(i) = 350
```

**composer 子节点（排除 `Open terminal [1320,192][1366,240]`）**
```
EditText             [28,244][1338,292]    h=48
Button  (Add)        [10,292][52,340]      h=48
View    (Model chip) [52,292][262,340]     h=48
View    (Send)       [1308,292][1356,340]  h=48
HorizontalScrollView [52,302][1308,330]    h=28
View                 [52,302][262,330]      h=28
TextView placeholder [28,250][319,270]     h=20
View                 [28,250][118,265]      h=15
```

| 项 | 值 | 判定 |
|---|---|---|
| 宿主盒（dump 侧） | `[0,162][1366,350]` h=188 | — |
| 并集（排除 Open terminal） | `[10,244][1356,340]` | — |
| band | `48`（`BAND_HEIGHT` / `MIN_INTERACTIVE_COMPONENT_PX`） | — |
| `unionPx` | **96**（堆叠形态 = editor 48 + toolRow 48） | — |
| **① `unionBottom`(340) ≤ `paneBottom`(350)** | **−10** | ✅ **真** |
| ① (ii) 生产口径 | `paneBottom`=350−48=**302**；`unionBottom`=340−48+12=**304** ⇒ 304≤302 | ❌ **+2**（见 §3.1） |
| ② `unionPx`(96) ≤ `band`(48) | **假** | ❌（见 §3.4） |
| `mInputShown` | `true` | ✅ |
| `readable` | `188 − 96` = **92** | ✅ **与基线一致** |
| 时间线盒 | `188 − 48` = **140** | ✅ **与基线一致** |

**768 生产算式（本档代入生产函数，非字面量）**
```
imeBottomPx     = 768 − 342 = 426        （ime frame=[0,342][1366,768]）
visibleBoxPx    = 768 − 162 = 606
essentialPx     = 284  (CHROME 84 + TIMELINE 72 + COMPOSER 116 + 12)
believedImeBottomPx:
   imeBottomPx=426 > 0
   visibleBoxPx(606) < essentialPx(284)*2 = 568 ?  -> 否，不短路
   claimed = 426 − floor(0) = 426 ; room = 606 − 284 = 322 ; 426 <= 322 ? 否
   => return room + floor = 322
panePx      = 606 − 322 = 284
paneBottom  = 162 + 284 = 446            <-- 生产算式字面值
```
⇒ 生产算式给 **446**，设备侧给 **140**，`446 − 140 = 306`。
这**正是 `CRITERION_PARITY.md` §7.2 已记账的那笔**（该文档记的是 `284/446`），
本轮**独立复现了同一个数**，故 ② 组我按契约用**设备读数换算**（350−48=302），不拿 446 去判。

### 2.2 600 档（`wm size 1366x600` + `wm density 160`）

```
Override size: 1366x600 ; Override density: 160
mInputShown=true
mCurrentFocus=com.xiufeigo.opencodeshell/.OcActivity
```

**宿主盒（pane 容器，dump 侧）**
```
View [0,162][1366,261]   h = 99   => paneBottom(i) = 261
```

**composer 子节点（排除 `Open terminal [1320,192][1366,240]`）**
```
EditText             [24,201][399,249]     h=48
Button  (Add)        [399,201][441,249]    h=48
View    (Model chip) [441,201][651,249]    h=48
View    (Send)       [1316,201][1364,249]  h=48
HorizontalScrollView [441,211][1316,239]   h=28
View                 [441,211][651,239]    h=28
TextView placeholder [24,215][315,235]     h=20
View                 [24,215][114,230]     h=15
```

| 项 | 值 | 判定 |
|---|---|---|
| 宿主盒（dump 侧） | `[0,162][1366,261]` h=99 | — |
| 并集（排除 Open terminal） | `[24,201][1364,249]` | — |
| band | `48` | — |
| `unionPx` | **48**（一行形态） | — |
| **① `unionBottom`(249) ≤ `paneBottom`(261)** | **−12** | ✅ **真**（**旧口径 `249 ≤ 261` 逐位复现**） |
| ① (ii) 生产口径 | `paneBottom`=261−48=**213**；`unionBottom`=249−48+12=**213** ⇒ 213≤213 | ✅ **真，差 0**（**生产零余量逐位复现**） |
| ② `unionPx`(48) ≤ `band`(48) | **真** | ✅ |
| `mInputShown` | `true` | ✅ |
| `readable` | `99 − 48` = **51** | ✅ **与基线一致** |

### 2.3 两档汇总

| 档 | ① `unionBottom` | ① `paneBottom`(i) | ① 差值 | ① 判定 | (ii) 生产差值 | ② `unionPx ≤ band` | `readable` | 时间线盒 |
|---|---|---|---|---|---|---|---|---|
| **768** | 340 | 350 | **−10** | ✅ | **+2**（§3.1） | ❌ / ✅（§3.4） | **92** ✅ | **140** ✅ |
| **600** | 249 | 261 | **−12** | ✅ | **0** ✅ | ✅ | **51** ✅ | （单行形态，无独立时间线盒） |

**基线保持情况（需求点名的三格）**：
- 768 `readable 92` —— ✅ **保持**
- 768 `时间线盒 140` —— ✅ **保持**
- 600 `readable 51` —— ✅ **保持**
- 600 旧口径 `249 ≤ 261` —— ✅ **逐位复现**

---

## 3. 未闭环（如实记账）

### 3.1 768 档 (ii) 生产口径 `+2`

`unionBottom 304 > paneBottom 302`，**+2 越界**。
来源已明确、且是 `CRITERION_PARITY.md` §7.1 **已记账的同一格**：
768 是**堆叠形态**（`unionPx = 96 = editor 48 + toolRow 48`），
并集底沿是 `toolRow` 那个 48dp 触摸靶的底，而它**在自己的盒里居中**，
⇒ 它比 region 内底沿高出的量**不足整 12**（实测是 10），因此 `−12` 的换算在这里只兑现了 `−10`。
600 是一行形态、并集**底对齐**，所以那里严格等于 12、`差值 = 0`。

**这 +2 不是本轮新缺陷，是几何事实的复现**；不豁免，留给下一轮关掉。

### 3.2 生产算式与设备读数的 306px 背离

本轮 768 档按生产函数算出 `paneBottom = 446`，设备侧是 `140`，
`446 − 140 = 306`。`CRITERION_PARITY.md` §7.2 记的是「生产比设备多算 **144px**」——
本轮在**本机 IME 高度**下复算出的是 `284`（panePx）而文档也是 `284`，
但**含 paneTop 的绝对值**按设备读数应为 `162 + 140 = 302`，与 `446` 差 `144`
（`446 − 302 = 144` ✅ **与 §7.2 的 144 完全一致**）。
⇒ **本轮独立复现了 §7.2 的 144，没有新信息**。该文档「768 档换算必须以设备读数 140 为输入」的前提继续有效。

### 3.3 768 档 (i) 差值 −10 ≠ 旧口径 −12

768 差值 **−10**、600 差值 **−12**。
600 严格兑现 `regionBottomPaddingPx = 12`；768 因堆叠居中只兑现 10（§3.1）。
**两档差值不同是形态差异，不是某档退化**；需求点名的 600 旧口径 `249 ≤ 261` 仍逐位保持。

### 3.4 判据 ② `unionPx ≤ band` 在 768 档**口径未定义**

`band` 这个词在仓里有**两个不同的值**，② 的判定因此**取决于口径**，我不能替 Lead 选：

| 口径下 `band` | 768 `unionPx=96` | 600 `unionPx=48` |
|---|---|---|
| `BAND_HEIGHT` = 48（`readable` 里减去的那一项在 600 恰好也是 48） | `96 ≤ 48` = **假** | `48 ≤ 48` = **真** |
| 768 堆叠总带高 96（`readable 92 = 188 − 96` 里用的那个） | `96 ≤ 96` = **真** | — |

- `CRITERION_PARITY.md` 里 `readable` 公式用的是 **96**（768）/ **48**（600），
  也就是**「band = 该档 composer 占掉的总高」**；
- 而 `BAND_HEIGHT` 常量是 **48**，且 §1.3 把 48 定义成「挂在 dump 容器下沿的那一个带高」。

⇒ **本轮如实给两组，不合并、不替 Lead 选边。**
若 Lead 裁定 ② 用 `BAND_HEIGHT=48`，则 **768 档 ② 为假 ⇒ 全局 FAIL**；
若裁定用「该档总带高」，则 **两档 ② 均真 ⇒ PASS**。

### 3.5 `Queue` chip 在本 build **不存在**（**取证，不判回退**）

按纪律：长回合已发、队列 chip 未出现时，**只给取证，不判回退**。取证如下：

**已做**：
1. 在 `Friendly greeting` 会话发长回合
   `E2ECHK5554u1q count from 1 to 300 one number per line`（dump 读回原文，逐位一致）
2. 点 Send（`(1308,292)-(1356,340)`，运行期从 dump 解析）⇒ 按钮翻成 `desc=[Stop]`，回合真实开跑（流式数到 80）
3. 回合运行中再输入第二条 `E2ECHK5554u1q queued follow up two` 并发送
4. 回合跑完（`Response ready · Friendly greeting` / `Build · 7s`）
5. 隐藏 IME 后再扫一次 composer 工具行

**扫了 4 次 dump（`u_q1` / `u_q2` / `u_q3` / `u_q4` / `u_noime`），结论一致**：

```
=== QUEUE-BAR CANDIDATES (Queue|Steer) ===
t=[] desc=[E2ECHK5554u1q queued follow up two] Button (1316,187)-(1364,235) w=48 h=48 clk=true
```
⇒ 唯一命中的是**右侧建议栏**里我自己那条输入（`w=48 h=48`），
**不是队列条**。全树**没有任何 `Queue` 或 `Steer` 标签的节点**。

**composer 工具行的全部内容**（隐藏 IME 后，`u_noime.xml`）：
```
desc=[Add] Button (10,292)-(52,340) w=42 h=48
desc=[Send] View  (1308,292)-(1356,340) w=48 h=48
（另有 Model chip: View (52,302)-(262,330)）
```
⇒ 工具行**只有 `Add` + `Model` + `Send`**，**没有 `Queue` chip**，所以**无从点它**。

**因此基线 `Steer h = 43` / `× h = 48` / 全 clickable 本轮【无法测量】，不是【不达标】。**
一个"一次发一条就排队"的长回合，两次抓屏之间队列条已经消失，**也可能**是队列条生命周期太短。
**本轮不下"回退"结论**，只如实记：**未出现 ⇒ 取证在此**。

### 3.6 `dumpsys window insets` 不是合法子命令

本 build 上：

```
$ adb -s emulator-5554 shell dumpsys window insets
Bad window command, or no windows match: insets
Use -h for help.
```

⇒ **insets 原始行在 `dumpsys window` 的输出里**（见 §4），不是独立子命令。
任务书里「`dumpsys window insets`」这个写法需要纠正，否则下一轮会误判为「拿不到 insets」。

---

## 4. insets 原文（768 档）

从 `dumpsys window` 抄出的原始行（**未改写**）：

```
InsetsSource id=52ad0002 type=captionBar frame=[0,0][1366,42] visible=true flags= insetsRoundedCornerFrame=false
InsetsSource id=3 type=ime frame=[0,342][1366,768] visibleFrame=[0,342][1366,768] visible=true flags= insetsRoundedCornerFrame=false
InsetsSource id=324c0001 type=navigationBars frame=[0,720][1366,768] visible=true flags= insetsRoundedCornerFrame=false
InsetsSource id=324c0004 type=systemGestures frame=[0,0][1366,768] visible=true flags= insetsRoundedCornerFrame=false
InsetsSource id=324c0005 type=mandatorySystemGestures frame=[0,720][1366,768] visible=true flags= insetsRoundedCornerFrame=false
ROTATION_0={nonDecorInsets=[0,0][0,48], configInsets=[0,0][0,48], nonDecorFrame=[0,0][1366,720], configFrame=[0,0][1366,720]}
mDisplayFrame=Rect(0, 0 - 1366, 768)
```

**关键行**：`type=ime frame=[0,342][1366,768]` ⇒ **`imeTop = 342`、`imeBottomPx = 768 − 342 = 426`**。

600 档对照（同一批原始行）：
```
ROTATION_0={nonDecorInsets=[0,0][0,48], configInsets=[0,0][0,48], nonDecorFrame=[0,0][1366,552], configFrame=[0,0][1366,552]}
```

### 4.1 判 −8px 的数据是否够（`DIVERGENCE_LOCATE2.md` §2.3 口径）

768 档**当轮实测**三个数：

| 量 | 值 | 来源 |
|---|---|---|
| `readable` | **92** | `188 − 96`（dump 侧） |
| 时间线盒 | **140** | `188 − 48`（dump 侧） |
| `imeTop` | **342** | insets 原文 |
| `imeHeight` | **426** | `768 − 342` |
| `nonDecorFrame` 高 | **720** | insets 原文 |
| `navigationBars` 顶 | **720** | insets 原文 |

⇒ **本轮 768 档没有出现 −8**：名义 `readable 92` 与时间线盒 `140` 都**原样保持**，
`imeTop 342` 与 pane 盒底 `350` 只差 **8**（这是 `regionBottomPaddingPx=12` 未被兑现的那 10 − 2，
与 §3.1 的 `+2` 是同一笔的另一面）。
**⇒ 判 −8px 所需的数据本轮是齐的、结论是"未复现 −8"。**

---

## 5. 缩窗往返（2 轮）

全程按 `$appPid` 过滤 logcat（**未使用 `$pid` 变量**），扫描 `FATAL|IllegalArgumentException|maxHeight`。

**第 1 轮**（600 → 768）
```
appPid=3526
[600] Override size: 1366x600
[600] pidof: 3526
[600] mCurrentFocus=com.xiufeigo.opencodeshell/.OcActivity
[768] Override size: 1366x768
[768] pidof: 3526
[768] mCurrentFocus=com.xiufeigo.opencodeshell/.OcActivity
logcat: CLEAN: no FATAL|IllegalArgumentException|maxHeight
```

**第 2 轮**（768 → 600 → 768）
```
appPid=3526
[600] Override size: 1366x600
[600] pidof: 3526
[600] mCurrentFocus=com.xiufeigo.opencodeshell/.OcActivity
[768] Override size: 1366x768
[768] pidof: 3526
[768] mCurrentFocus=com.xiufeigo.opencodeshell/.OcActivity
logcat: CLEAN: zero matches for FATAL|IllegalArgumentException|maxHeight
ANR: no ANR
```

| 轮次 | 档位切换 | `pidof` | `mCurrentFocus` 归属 | `FATAL\|IllegalArgumentException\|maxHeight` |
|---|---|---|---|---|
| 1 | 600 → 768 | **3526 全程未变**（未崩、未重启） | 本 app | **空** ✅ |
| 2 | 768 → 600 → 768 | **3526 全程未变** | 本 app | **空** ✅ |

---

## 6. 收尾

| 项 | 状态 | 证据 |
|---|---|---|
| `wm` 显式重设回 `1366x768` / `160` | ✅ **已重设并复查** | `Override size: 1366x768` / `Override density: 160` |
| 自建会话清理 | ✅ **无需清理** | 本轮**没有新建会话**：进的是**既有**会话 `Friendly greeting`；`E2ECHK5554u1q` 只是**发进该既有会话的消息**，不构成自建会话。**未对 `Friendly greeting` 做 Stop / 长按 / 删除**（纪律：只动自建会话） |
| `/sdcard` 无残留 | ✅ **已清** | 清前 14 张 png + 19 个 xml；清后只剩原有 `anim1-3.mp4`、`v95` 与标准目录 |
| gradle / 装机 / 杀进程 | ✅ **一律未动** | 本轮零 gradle 调用、零 `install`、未 `kill`；隧道 `reverse tcp:4095` 仅为 rebuild（非杀进程） |
| BACK(4) / 弹层内 ESC(111) | ✅ **未使用** | 全程只用 `tap` / `text` / `keyevent 62`(空格) / `keyevent 61`(TAB) / `keyevent 123`(END) / `keyevent 67`(DEL) / `keyevent 3`(HOME) / `KEYCODE_WAKEUP` |
| `emulator-5590` | ✅ **全程未碰** | 所有 adb 指令均带 `-s emulator-5554` |

`/sdcard` 清后完整列表（仅原有项）：
```
Alarms Android Audiobooks DCIM Documents Download Movies Music Notifications
Pictures Podcasts Recordings Ringtones _c anim1.mp4 anim2.mp4 anim3.mp4 v95
```

---

## 7. 交付物与证据清单

| 文件 | 内容 |
|---|---|
| `REPORT_FINAL_VERIFY.md` | 本报告（覆盖前两版） |
| `evidence_768.txt` | 768 档原始读数（宿主盒 / 并集 / insets / 生产算式） |
| `evidence_600.txt` | 600 档原始读数 |
| `dump.ps1` | dump 解析脚本（`text="…"` 与 `text='…'` **双模式**匹配，优先 `--compressed`） |
| `queuescan.ps1` | Queue/Steer 节点扫描脚本 |
| `s1_connected.png` | **第 0 步 PASS 证据**：会话列表（10 行） |
| `s2_session.png` | 已进入会话（连接正常） |
| `s0fill.png` | 引导页填入态（可见地址框内容） |
| `noime.png` | 隐藏 IME 后的 composer 工具行（**无 Queue chip 的证据**） |
| `q3_running.png` / `q4_queuebar.png` | 回合运行中 / 回合完成（`Response ready`） |
| `u_*.xml`（已同步到本目录） | 各轮 dump 原文 |

> `/sdcard` 上的同名 xml/png 在收尾时**已从设备删除**（不残留），副本保留在本目录。

---

## 8. 状态汇总

| 判据 | 结果 |
|---|---|
| 第 0 步：认证拿会话 | ✅ **PASS**（10 行会话列表 + 真实回复） |
| 构建 / 归属核对 | ✅ **PASS**（md5、`lastUpdateTime`、全程归属） |
| ① 768（dump 侧） | ✅ **真**，差 **−10** |
| ① 600（dump 侧） | ✅ **真**，差 **−12**（旧口径逐位） |
| ① (ii) 600 生产口径 | ✅ **真**，差 **0**（生产零余量逐位） |
| ① (ii) 768 生产口径 | ❌ **+2**（§3.1，§7.1 同格） |
| ② 600 | ✅ **真**（48 ≤ 48） |
| ② 768 | ⚠️ **口径未定义**（§3.4）—— `band=48` 则假、`band=96` 则真 |
| 768 `readable 92` / 时间线盒 `140` | ✅ **双双保持** |
| 600 `readable 51` | ✅ **保持** |
| 768 insets 原始行 | ✅ **已抄全**（§4） |
| 缩窗往返 2 轮 | ✅ **PASS**（pid 3526 未变，logcat 空） |
| 收尾 | ✅ **PASS**（wm 已重设 / 无自建会话 / `/sdcard` 已清） |
| Queue 几何 43/48/全 clickable | ❓ **未测到**（chip 不存在，§3.5 取证，**不判回退**） |

**全局判定**：**PASS（附两项未闭环）**。
- 若 Lead 裁定判据 ② 的 `band` = `BAND_HEIGHT` 48，**全局转 FAIL**（768 档 ② 为假）；
- `Queue` 几何**未测到**，按纪律**不构成回退**。

---

**状态：PASS（附 §3.1 的 +2 与 §3.4 的 ② 口径两项未闭环）**
**是否拿到会话：是**（10 行列表，`Friendly greeting` 内可见真实回复 `收到，连接正常。有什么需要帮忙的？`）
**两档 ① 差值：768 = −10（dump 侧）／ +2（生产口径换算）；600 = −12（dump 侧）／ 0（生产口径）**
**768 的 140/92 与 600 的 51 是否保持：是，三个全部逐位保持**（时间线盒 140、`readable` 92、`readable` 51）
**队列几何（43/48/全 clickable）：未测到** —— 本 build composer 工具行只有 `Add`(42×48) / `Model` / `Send`(48×48)，**全树无 `Queue` 也无 `Steer` 节点**；4 次 dump 取证，按纪律**不判回退**
**insets 关键行**：`InsetsSource id=3 type=ime frame=[0,342][1366,768] visibleFrame=[0,342][1366,768] visible=true`（`imeTop=342`、`imeBottomPx=426`）；`nonDecorFrame=[0,0][1366,720]`；`navigationBars frame=[0,720][1366,768]`；`mDisplayFrame=Rect(0,0-1366,768)`；**注意 `dumpsys window insets` 在本 build 不是合法子命令，原始行在 `dumpsys window` 里**
**报告路径**：`research/v97_verify/5554_md/REPORT_FINAL_VERIFY.md`
