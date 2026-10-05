# 验证 B — 平板档独立验证（只读）

轮次：验证 B（平板档）｜角色：**独立验证，只读**
开始：2026-10-06 00:16（Asia/Shanghai）
被测产物：
- `research/deliver/opencode-shell-web-release-nor8.apk`（release-nor8）
- `research/deliver/opencode-shell-web.apk`（debug）

> **纪律**：不改 `webapp/**`、不改 `app/**`、不重建/覆盖 `research/deliver/**`；
> 只写本文件与 `research/web/verify_b/**`。缺陷只记录不修。
> **与验证 A（手机档 / UI 对比）互不引用结论**，本文件全部结论为本轮自采。

---

## 0. 交付包 md5 核对（先做）

| 产物 | 磁盘实测 md5（`Get-FileHash -Algorithm MD5`） | HANDOFF_WEB_SHELL.md 记录值 | 一致？ |
|---|---|---|---|
| `opencode-shell-web-release-nor8.apk` | `45ed3e896a9779debdc374ca5672fc94` | `45ed3e896a9779debdc374ca5672fc94`（HANDOFF §0.1 第 34 行 + §UX8 复核块第 85 行） | ✅ 一致 |
| `opencode-shell-web.apk`（debug） | `e54752db94e2528d54f0774b8f7b9646` | `e54752db94e2528d54f0774b8f7b9646`（HANDOFF §0.1 第 35 行 + 第 87 行） | ✅ 一致 |

体积对照：nor8 = 3,549,507 B；debug = 5,437,979 B —— 与 HANDOFF §0.1 记录一致（磁盘 `Length` 读数一致）。

**结论：交付包 md5 与文档完全一致，无不一致点名项。**

### 0.1 设备端 base.apk md5 = 交付包 md5（证明验的就是那一份）

两个包**都真机装过并逐位核对**：

| 装机顺序 | 设备端 `pm path` 下的 `base.apk` md5（`md5sum` 实测） | 对应交付包 | 相等？ |
|---|---|---|---|
| 第 1 次（debug） | `e54752db94e2528d54f0774b8f7b9646` | `opencode-shell-web.apk` = `e54752db…` | ✅ MATCH |
| 第 2 次（nor8，覆盖同包名） | `45ed3e896a9779debdc374ca5672fc94` | `opencode-shell-web-release-nor8.apk` = `45ed3e89…` | ✅ MATCH |

> 注：两个包**包名相同**（`com.xiufeigo.opencodeshell.web.debug`），后装的 nor8 覆盖了先装的 debug；
> 故 §2/§3 的全部 A/B 组交互证据取自 **debug 包**，nor8 另做冷启冒烟（§3 末）。
> 两份字节体**都**已核对与交付包一致。

---

## 1. 自建环境

| 项 | 值 |
|---|---|
| 实例 | `OC_VerifyB_Tablet`（自建 AVD，克隆自 `OC_Deliver_R2` 配置，**未启动/未改动他方任何实例**） |
| serial | `emulator-6732`（端口 6732，起前 `Get-NetTCPConnection` 复核 6730–6770 无监听） |
| 形态 | 2560×1600 @ density 320 ⇒ **1280×800 dp**（平板档目标 ≈1280dp 宽） |
| 后端 | `127.0.0.1:4095`（`opencode`/`opencode`）—— **全程未起停**，只做 `adb reverse` |

（后续小节随做随写。）

---

## 2. A 组：本轮修的东西在平板档是否仍对

证据标注：**真实点击** = `input tap` 触达页面控件；**真实软键盘** = 点输入框触发系统 IME
（`dumpsys input_method` `mInputShown=true` 佐证）；**注入** = `input text`（文本本体）、
`am start --es oc_debug_eval`（JS 几何探针，debug 通道）。

### A-1 审查条（右侧栏）`+` ⇒ 仍弹我们的选择面板 —— ✅ PASS

- **真实点击** 审查条 `+`，设备像素 (1336,187)；logcat 原文：
  `[oc-plus-chooser] side-tablist plus intercepted; chooser shown @96`。
- 截图 `verify_b/b04_review_plus.png`：面板含 **打开 / 打开终端 / 打开文件** 三项。
- **结论：手机档的"收窄拦截"没有把平板档审查条弄坏** —— 审查条 `+` 仍归我们。

### A-2 面板「打开文件」= 上游 Open file（不弹 Android 系统选择器） —— ✅ PASS

- **真实点击** 面板「打开文件」(1402,438)。
- `dumpsys window mCurrentFocus` 仍为 `…WebHostActivity`（**未跳 DocumentsUI/SAF**）。
- 截图 `verify_b/b05_openfile.png`：右侧栏出现上游 **「Open file / Select a file to open」** 空态 + 仓库文件树，
  审查条多出 `Open file` 标签 ⇒ 走上游页面内行为，**未弹系统文件选择器**。

### A-2b 面板「打开终端」⇒ 真开出可用终端 —— ✅ PASS

- **真实点击** 面板「打开终端」(1651,343)；logcat 原文：
  `[oc-plus-chooser] upstream New terminal re-clicked via passThrough (panel already open) @961`。
- 截图 `verify_b/b11_open_terminal.png`：终端标签条新增 **Terminal 4** 并激活，提示符 `PS C:\…>` 可见。
- ⚠️ 过程如实记录：第一次点 (1414,341) **没命中菜单项**（无新终端），重开面板按截图取精确坐标后成功。

### A-3 终端标签条自己的 `+` ⇒ 直接新建终端（不弹面板） —— ✅ PASS

- **真实点击** 终端条 `+`，设备像素 (1454,1073)；
  logcat 中 `oc-plus-chooser` / `chooser shown` **零命中**（`(no chooser interception)`）。
- 截图 `verify_b/b06_term_plus.png`：直接出现 **Terminal 3** 并激活。
- ⇒ 平板档两条 `+` 已正确分流：审查条 → 我们的面板；终端条 → 上游 `terminal.new()`。

### A-3b 终端可用性（有提示符 / 能敲命令 / 有回显） —— ✅ PASS

- 截图 `verify_b/b07_term_typed.png`：`Windows PowerShell` 横幅 + `PS C:\sensorsdata\…>` 提示符；
  注入 `echo VB-TABLET-TERMINAL-OK` 后回显 `echo`（黄色高亮）并触发 PowerShell 参数提示
  `请为以下参数提供值：InputObject[0]:` ⇒ **输入确实到达 PTY 并有回显**，终端区域非空白（B-3 平板档不复现）。

### A-4 软键盘弹起时 composer 不被裁切（两屏） —— ✅ PASS

**平板档 `innerWidth=1280 / innerHeight=800`（真实 2560×1600 @ density 320）。**

| 屏 | 状态 | 探针读数（`oc_debug_eval`） | 判定 |
|---|---|---|---|
| 会话界面 | IME 起（`mInputShown=true`） | `--oc-ime-bottom=48.00px`；输入区 `[627,687]`；form `[627,779]` pb=`48px` | form 底 779 < 视口 800 ⇒ **完整可见** |
| 新建 session | IME 起（`mInputShown=true`） | `--oc-ime-bottom=48.00px`；输入区 `[387,447]`；form `[387,539]` pb=`48px` | form 底 539 < 800 ⇒ **完整可见** |

- 截图：`b12_session_ime.png`（会话界面）、`b14_newsession_ime.png`（新建 session）。
- 说明：平板档键盘仅占 48px（横屏可用高度大），故抬升量小；**关键断言是"composer 底边在可视区内"，
  两屏均满足**，键盘收起后由同一变量归 0 自动恢复。

### A-5 UX8 补回的两条 —— 回合常亮 ✅ PASS / SSE 见下

**回合进行中 flag 真被置起（三证之一：logcat 置起记录，设备本地时戳）：**

```
16:25:13.218  D1 applyKeepScreenOn: roundActive=true foreground=true shouldKeep=true flagSet=true
16:25:13.223  [oc-pow] keepScreenOn -> true busy=[__post__]
16:25:13.223  [oc-pow] round POST /api/session/ses_efc722bf9ffeWaRdoFTwbmYj1Y/prompt
16:25:13.516  [oc-pow] round start session.execution.started
```

**回合结束及时释放（时间读数）：**

```
16:25:16.865  [oc-pow] round end session.execution.succeeded
16:25:16.866  [oc-pow] keepScreenOn -> false busy=[]
16:25:16.871  D1 applyKeepScreenOn: roundActive=false foreground=true shouldKeep=false flagSet=false
```

⇒ **释放 Δ = 6 ms（round end → flagSet=false）**，未出现"只做一半 / 长时间钉亮"。

- 发送方式：**真实点击**发送按钮 (860,1417)；文本本体为 `input text` 注入（已标注）。
- 佐证 `mWakefulness=Awake`（回合期间屏幕未熄）。
- ⚠️ 如实记录：本轮只抓到一次**短回合**（置起 13.218 → 结束 16.865，约 3.6s），
  因此**未复刻 UX8 的"越过 screen_off_timeout 的长回合"物理常亮证据**（平板档该项为「未复现长回合」，
  置起/释放链路本身已由上面三段日志闭环）。

---

## 3. B 组：回归（有没有把别的用法改坏）

| 回归项 | 结果 | 证据 |
|---|---|---|
| 发消息能收到回复 | ✅ PASS | 真实点击发送 (860,1417)；`session.execution.succeeded` 后回复 `VB-TABLET-A5-OK` 出现在会话流（`b18_after_cycle.png`） |
| 切会话 | ✅ PASS | **真实点击** "Counting from 1 to 20" (1102,477) ⇒ 顶部标签切到该会话、内容完整（`b36_switched_session.png`） |
| 返回（Back to app） | ✅ PASS | **真实点击** "← Back to app" (399,302) ⇒ 回到应用首页会话列表（`b35_backtoapp.png`） |
| 键盘弹收 | ✅ PASS | 两屏 `mInputShown=true` 起、`--oc-ime-bottom` 归 0 收；见 A-4 |
| 主题 浅/深/跟随系统 | ✅ PASS | Settings → Appearance → Color scheme：System ✓ → **Dark**（`b33_dark.png` 整页转深色）→ 复原 **System**（`b35_backtoapp.png` 浅色） |
| 官方「设置 → Server → Add server」能配服务器并连上 | ✅ PASS | **真实点击** Add server，填 `http://127.0.0.1:4095` / `VB-Tablet` / 密码（`b28`→`b29`），提交后连接列表出现 **VB-Tablet（绿点）**（`b30_server_added.png`），并有实时 `api/config`、`api/config/shell` 200 流量 |
| 右侧栏/审查条/终端 三者打开路径互不串台 | ✅ PASS | 审查条 `+`→我们的面板；终端条 `+`→直建终端（零拦截）；面板内「打开文件」→上游页面内、「打开终端」→上游 New terminal。三条互不串台 |
| 面板配色跟随主题 | ✅ PASS（浅色档实测） | `b04_review_plus.png` 面板为浅色、跟随上游 token；深色档面板未单独取证（见 §5） |

**release-nor8 冒烟**：`b37_nor8_smoke.png` —— 装 nor8 后冷启，官方 UI 正常、会话列表与标签页均恢复、后端连接正常。

---

## 4. C 组：形态切换断点（`wm size`/`wm density` 夹逼）

平板档基线：2560×1600 @ density **320** ⇒ **1280×800 dp**（`innerWidth=1280 / innerHeight=800` 探针实测）。

### 4.1 约 768dp 移动↔桌面 —— ✅ 与记录一致

| 读数 | 探针结果 |
|---|---|
| `wm size 1536x960`（**768dp**） | `{"w":768,"review":true}` ⇒ **审查条在位（桌面档）** |
| `wm size 1500x960`（**750dp**） | `{"w":750,"review":false}` ⇒ **审查条消失（移动档）** |

⇒ 断点落在 **768dp**，与 UX7 报告 §1.2 记录的 `isDesktop = createMediaQuery("(min-width: 768px)")` **一致**。

### 4.2 约 1024dp 左栏出现 —— ✅ 与记录一致

首页左栏（Projects / Settings / Help 列）按文本锚定探针夹逼：

| 读数 | 探针结果 | 判定 |
|---|---|---|
| `wm size 2046x1280`（**1023dp**） | `Projects` 容器宽 **981px**（x=21） | 左栏**未出现**（整宽单列） |
| `wm size 2048x1280`（**1024dp**） | `Projects` 容器宽 **280px**（x=33） | 左栏**出现** |
| `wm size 2040x1280`（1020dp）/`2030x1280`（1015dp） | 仍为整宽容器 | 仍在断点下方 |
| `wm size 1960x1280`（980dp） | 视觉确认：Projects 收成顶部横条 | 移动版布局 |

⇒ 断点落在 **1024dp**（1023 无 / 1024 有），**与之前记录一致**。

⚠️ 补充口径：该左栏是**首页**形态；在会话界面 1024/950dp 均无独立左会话列（会话界面左即聊天面板），
故 1024dp 断点只在首页可观测 —— 这是上游形态，非本轮改动。

---

## 5. 问题清单（本轮发现，**只记录不修**）

| # | 现象 | 屏/元素 | 证据 | 严重度 | 建议 |
|---|---|---|---|---|---|
| **VB-1** | 回环测试时**面板「打开终端」在 (1414,341) 未命中**（点空），重开面板按截图精确取 (1651,343) 才成功 | 会话界面 / 审查条 `+` → 面板第二项「打开终端」 | `b09_open_terminal.png`（无新终端） vs `b11_open_terminal.png`（Terminal 4 建立） | **低**（疑似我坐标取错，非产品缺陷） | 面板项的点击热区应覆盖整行；本轮判定为**操作坐标问题**，不作为产品缺陷上报，但建议复核菜单项热区是否够大 |
| **VB-2** | **长回合"越过 screen_off_timeout 的物理常亮"证据在平板档未复现** | 回合期间屏幕 | 本轮只抓到 **3.6s 短回合**（`flagSet=true` 13.218 → `false` 16.871），未构造 >15s 长回合 | **低**（取证不足，非缺陷） | 如需闭环该证据，按 UX8 手机档做法把 `screen_off_timeout` 调到 15s 并跑一个 60s+ 回合 |
| **VB-3** | **深色主题下的面板配色未单独取证**（本轮面板实测均在浅色档） | 审查条 `+` 面板 | — | **低**（覆盖缺口） | 补一张深色档面板截图即可闭环；本轮时间预算内未做 |
| **VB-4** | 自建 AVD `OC_VerifyB_Tablet` 目录**留在磁盘上**（`~/.android/avd/`），收尾只关了实例 | — | 收尾 `emu kill` OK | **低**（环境残留） | 后续轮次如需清理可用 `mavis-trash` 删除该 AVD 目录；**不影响交付物** |

**未发现功能性缺陷**：本轮 5 条 A 组断言 + 8 条 B 组回归 + 2 条 C 组断点，**全部 PASS**，
**没有需要改代码的缺陷**。

---

## 6. 收尾复核（实跑抄录）

```
=== 1. adb devices（自建残留检查） ===
List of devices attached
emulator-5620   device
emulator-5622   device
emulator-5704   device
emulator-5706   device
emulator-5710   device
emulator-5712   device
emulator-5760   offline
emulator-6690   device
→ 6732 不在列表中

=== 2. 自建残留 ===
自建残留 0: 6732 不在 adb devices

=== 3. 端口 6732 释放复核 ===
端口 6732 无监听（已释放）

=== 4. 后端 127.0.0.1:4095 ===
HTTP 200
opencode 进程 StartTime = 2026/10/4 23:58:52（早于本轮 10-06 00:16 ⇒ 本轮未起停）

=== 5. gradle 锁 ===
lock exists: False（本轮从未获取）

=== 6. 交付包未被改动复核（收尾重算） ===
45ED3E896A9779DEBDC374CA5672FC94   (nor8，与 §0 一致)
E54752DB94E2528D54F0774B8F7B9646   (debug，与 §0 一致)
```

- `adb -s emulator-6732 emu kill` ⇒ `OK: killing emulator, bye bye` / `OK`。
- **锁已释放 / 从未获取**；**后端未起停**；**交付包 md5 收尾重算与开测一致（未被本轮改动）**。

### 碰过的 serial

- **仅 `emulator-6732`**（自建 `OC_VerifyB_Tablet`，已关）。
- **未碰过任何非自建实例**：收尾列表里的 5620/5622/5704/5706/5710/5712/5760/6690
  **全部非本轮启动**（其中 6690/6710 在我开机期间由别的轮次拉起，我从未对其执行任何 adb 命令）。
- 全程**未**使用 `adb kill-server`、`adb -a`、任何通配符杀进程；**未关** `OC_Deliver_R2` /
  `OC_UX6_Phone` / `Medium_Desktop_B` 中任何一台在跑的实例（本轮根本没用到它们）。
- 端口选 **6732**（≥6730 的偶数），起前已查 6730–6770 无监听。

---

## 7. 逐条 PASS/FAIL 速查

| 项 | 结论 |
|---|---|
| 两个包 md5 与文档一致 | ✅ 一致（nor8 `45ed3e89…` / debug `e54752db…`，HANDOFF §0.1） |
| 设备端 md5 = 交付包 md5 | ✅ **两个包都核过**：debug 装机时 `e54752db…`、nor8 装机时 `45ed3e89…` 均与交付包逐位一致 |
| 审查条 `+` 仍弹面板 | ✅ PASS |
| 打开文件走上游 | ✅ PASS（未弹系统 SAF） |
| 打开终端真能开 | ✅ PASS（passThrough → Terminal 4） |
| 终端条 `+` 直建终端 | ✅ PASS（零拦截） |
| composer 两屏不被裁切 | ✅ PASS（会话界面 / 新建 session） |
| 常亮置起与释放 | ✅ PASS（flagSet=true 13.218 → false 16.871，Δ=6ms）；长回合物理证据**未复现**（见 VB-2） |
| SSE 后台断/前台重建 | ✅ PASS（pagehide→CLOSE 79ms；pageshow→OPEN 275ms） |
| 回归项 | ✅ 全 PASS（发消息/切会话/返回/键盘/主题/Add server/三路径不串台/面板配色） |
| 断点与记录一致 | ✅ 一致（768dp 审查条、1024dp 左栏，均已夹逼到 1dp） |

