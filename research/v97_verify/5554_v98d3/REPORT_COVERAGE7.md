# v98 D3 覆盖度 · 第七轮 · 真机复验报告（核验卡修复 + D3-3/D3-4）

**执行人**：真机复验测试员（第七轮） **日期**：2026-10-03
**仓库**：`C:\sensorsdata\main\program\opencode-android-shell`
**被复验对象**：
1. `research/v97_fix/HOST_KEY_CARD_FIX.md`（v99 核验卡修复 —— **主判据**）
2. `research/v97_verify/5554_v98d3/REPORT_COVERAGE6.md`（D3-3 / D3-4 被该卡阻塞）

**一句话结论**：**核验卡修复在真机上生效 —— `Cancel` 三判据全中（卡立即消失 / 行脱离 `Connecting` / 行内 ⋮ 恢复 `clickable=true`），严格两跳后 `Add SSH server` 恢复；`Trust and connect` 有硬证据（`known_hosts` 真写入该主机键 + 连接推进到 `SSH authentication` 口令挑战），不是"自动信任"。**

---

## 0. 环境读数

| 项 | 读数 |
|---|---|
| 设备 | `emulator-5554` = Pixel_9a（`sdk_gphone16k_x86_64`） |
| 屏 / 密度 | `Physical size: 1080x2424` / `Physical density: 420` |
| 应用 | `com.xiufeigo.opencodeshell`，`versionName=1.4.0` |
| 安装时间 | `lastUpdateTime=2026-10-03 03:09:27` —— 与任务书给的 v99e（`ae1f61ef…`，03:09:27）**逐位一致** |
| codePath | `/data/app/~~jpz7TlP0dWK1x658lYJPTQ==/com.xiufeigo.opencodeshell-aPiMua1MbxtNRRKqZZUqug==` |
| 焦点 | 全程 `OcActivity`，**从未出现 `Application Not Responding`**，**未 reboot** |
| `adb reverse` | `host-15 tcp:4095 tcp:4095`、`host-15 tcp:2222 tcp:22` —— **本轮一条都没新建**（与第六轮同：只连设备本地 `127.0.0.1:2222`） |
| 纪律 | 未跑 gradle、未装机、未杀他 app；只对本 app 做过 `am force-stop`（允许）；**未按 BACK(4)**；**未按 ESC(111)**（见 §5 工具教训）；截图全部 `screencap -p` + `adb pull`；坐标全部运行期从 dump 解析 |

### 0.1 dump 解析口径（按要求同时匹配两种引号）

`c7lib.ps1` 的 `Nodes()` 对每个属性用 `(?:"([^"]*)"|'([^'']*)')` **同时匹配 `text="…"` 与 `text='…'`**，并优先 `--compressed`：

```
adb -s emulator-5554 shell uiautomator dump --compressed /sdcard/_<tag>.xml
adb -s emulator-5554 pull /sdcard/_<tag>.xml  →  立即 rm -f 设备侧副本
```

### 0.2 落库计数口径（按第六轮更正执行）

第六轮查明 SSH 隧道表是**一个加密键里装一整个列表**，数 `<string>` 行数永远不变。本轮沿用并细化：

| 键 | 本轮读数 |
|---|---|
| **`AXhs57KqmMxUXQA/h1gxbGu9ZbW7vQQstN5gTAw=`（隧道表）** | 空表基线 **76**（第六轮 §1.5）；建 1 条后 **268**；建 2 条后 **464** |
| `<string>` 行数 | 建条前后**恒定不变** —— 印证第六轮"数行数永远得 0"的结论 |

**关于"隧道表条目数"的诚实说明**：该键 value 是 androidx `EncryptedSharedPreferences` 的 AES 密文（`head=ATA0KB9ykU2RLr2kcg/0DKu/4SLZm01KC7IJVImpvkFhUCX0YBS3eqc58emy`），**无法在设备外解密数条目**。因此本轮"条目数"= **UI 可见行清单（滚动遍历去重）**，并以**隧道键 value 长度**作落库侧交叉验证（每条 ≈ **+196**）。

---

## 1. A — 核验卡复验（主判据）

### 1.1 A1 建条目 → 核验卡出现（卡内两按钮文案 + bounds）

**填表方法（重要，见 §5）**：目标框 = 表单内**最高**的 `EditText`（`h=126`）→ tap 聚焦 → `input text`；其余字段改用 **TAB（keyevent 61）焦点路由**，**全程不依赖坐标**：

| 步骤 | 读数 |
|---|---|
| 目标框 tap | `[138,1576][943,1702]` h=126 → 聚焦后 `[138,1186][943,1312]` |
| 目标回读 | `val='ssh -p 2222 sshtest@127.0.0.1'` ✅ |
| **TAB 1** | 焦点 → `[138,1246][943,1372]`（**name 框，h=126 满高**）→ 填 `E2ECHK5554d3d-1` 回读一致 ✅ |
| **TAB 1→2** | 焦点落在非 `EditText`（认证方式 chip） |
| **TAB 2** | 焦点 → `[138,1157][943,1283]` **`password="true"`** → 填 `keypass123` 回读 `??????????`（10 掩码 = `keypass123` 长度）✅ |
| 提交 | `Add server @ 849,1314` |

**核验卡内容（`U11_card` / `C20_card_before`，运行期解析）**：

| 节点文案 | bounds | clickable |
|---|---|---|
| `Verify host identity` | `[106,1412][454,1459]` | false（标题） |
| `[127.0.0.1]:2222` | `[106,1524][354,1565]` | false |
| `ssh-ed25519` | `[106,1597][315,1638]` | false |
| `MD5 fingerprint` | `[106,1670][361,1711]` | false |
| `MD5:9b:19:f4:e8:83:e3:ca:ad:1d:c0:62:76:76:48:9b:24` | `[106,1716][975,1758]` | false |
| **`Cancel`（按钮 1）** | **`[440,1812][551,1853]`** | false（文本节点；中心 496,1832） |
| **`Trust and connect`（按钮 2）** | **`[646,1812][938,1853]`** | false（文本节点；中心 792,1832） |

同时面板行已出现：`E2ECHK5554d3d-1 [201,1215][510,1256]` + 状态 `Connecting over SSH… [201,1261][510,1296]`。

> **A1 判定：PASS** —— 卡按预期弹在未知主机键上，两按钮文案与 bounds 与第六轮 §1.5 完全一致（`[440,1812][551,1853]` / `[646,1812][938,1853]`）。

---

### 1.2 A2 点 `Cancel` —— 三判据逐条

操作：运行期解析卡内 `Cancel` → `tap @ 496,1832`（点前 `IME=false` 已断言）。

| # | 判据 | 点**前**读数 | 点**后**读数 | 判定 |
|---|---|---|---|---|
| **① 卡消失** | dump 里不再有核验卡 | `cardUp=TRUE`；`Verify host identity [106,1412][454,1459]`；`Trust and connect [646,1812][938,1853]` | `cardUp=FALSE`；`'Verify host identity' text matches=NO`；`'Trust and connect' still present=NO` | ✅ **TRUE** |
| **② 面板行脱离 Connecting** | 行不再是 `Connecting over SSH…` | `STATE: Connecting over SSH…`（+ 全屏 `Connecting to SSH server`） | `STATE: Disconnected. The remote server is left running. [201,1261][843,1296]` | ✅ |
| **③ 行内 ⋮ 恢复可点** | `Server actions` 节点 `clickable=true` 且点得开 | 卡在屏时 dump 中**不存在** `content-desc="Server actions"` 的可点节点（行是"连接中"形态） | `cd=Server actions clk=true en=true [780,1725][906,1736]`（卡退瞬间即出现） | ✅ |

**三路证据（非"dump 签名不变"）**：

1. **dump 前后差异（逐节点）**：

```
--- BEFORE ---
  STATE: Connecting over SSH…
  BTN 'Cancel'              [440,1812][551,1853]
  BTN 'Trust and connect'   [646,1812][938,1853]
  (无 'Server actions' 可点节点)

--- AFTER ---
  ASSERT-1 cardGone=TRUE
  'Trust and connect' still present=NO
  'Verify host identity' text matches=NO
  STATE: Disconnected. The remote server is left running.
  POST '' cd=Server actions clk=true [780,1725][906,1736]
  POST 'Reconnect'  [323,1339][494,1349]
  POST 'Disconnect' [631,1339][811,1349]
```

2. **截图**：`C20_card_before.png`（卡在屏）→ `C21_after_card_cancel.png`（卡退、行变 `Disconnected` + `Reconnect`/`Disconnect` 操作条露出）。

3. **行为学差异**：卡退后**新增**了 `Reconnect` / `Disconnect` 两个操作按钮 —— 这两个控件只在**非 connecting 形态**下存在，是"行真的脱离了 connecting"的**独立于文本**的第二类证据。

**三条同时成立，且都是"状态变了"的正向证据，不是签名不变。**

> **A2 判定：PASS —— 第六轮 §2.2 记的"Cancel 无效、14 次采样全停在 Connecting"已不复现。**
> **附：`onCancel` 的 F13b 约束按报告预期生效** —— 卡退后 `SshDialog` 回到**表单分支**（`Host or SSH command` / `ssh -p 2222 sshtest@127.0.0.1` / `Server name (optional)` / `E2ECHK5554d3d-1` 全在树里，页脚变成 `Cancel [597,1779][708,1820]` + `Connect [803,1779][938,1820]`），但**对话框本身仍开着**（`Add server` 消失 ⇒ 处于"编辑已存在条目"分支，非"新增"分支）。

---

### 1.3 A3 再点表单页脚 `Cancel` → `Add SSH server` 恢复

操作：运行期解析页脚 `Cancel @ 652,1800`（`[597,1779][708,1820]`）→ tap。

| 判据 | 读数 |
|---|---|
| 对话框关闭 | `Add server` / `Host or SSH command` / `Server name (optional)` 全部从 dump 消失 |
| **`Add SSH server` 恢复** | `ASSERT-3 'Add SSH server' PRESENT [544,927][801,968]`，中心 `672,948` —— 与本轮每次新增时实际 tap 的坐标**逐位一致** |
| 面板行仍在 | `E2ECHK5554d3d-1 [201,1215][510,1256]` + `Disconnected. The remote server is left running.` |
| ⋮ 双行可点 | `cd=Server actions clk=true en=true [780,1375][906,1501]`（`E2ECHK5554d3d-1` 行）/ `[780,1622][906,1748]`（`10.0.2.2:4095` 行） |

> **A3 判定：PASS —— 修复报告 §6.1 声明的"严格两跳"在真机成立：**
> **跳 1 = 点卡内 `Cancel`（关卡、卡退、行脱 connecting）；跳 2 = 点表单页脚 `Cancel`（关对话框）。**
> **两跳之后 `Add SSH server` 恢复可点，形态与修前"点了一堆次也不开门"（第六轮 §1.6 `FAIL: form did not open`）截然不同。**

---

### 1.4 A4 `Trust and connect` —— 有可观测结果（硬证据）

流程：再建第 2 条 `E2ECHK5554d3d-2` → 卡出现 → 运行期解析 `Trust and connect @ 792,1832` → tap。

**观察窗：tap 后 5 次采样（11:25:26 → 11:25:47，间隔 2.6s）**

| 采样 | `cardUp` | 状态 | 面板行 |
|---|---|---|---|
| s1 (11:25:26) | **FALSE** | `Disconnected. The remote server is left running.` + `Connecting to SSH server` | `E2ECHK5554d3d-1` |
| s2 (11:25:31) | FALSE | 同上 | — |
| s3 (11:25:37) | FALSE | 同上 | — |
| s4 (11:25:42) | FALSE | 同上 | — |
| s5 (11:25:47) | FALSE | 同上 | — |
| 终态 (11:25:50) | **FALSE** | 卡片换成了 **`SSH authentication [106,1412][458,1459]`** + `Password: [106,1524][296,1561]` + `Cancel [588,1742][699,1783]` / `Continue [794,1742][938,1783]` | — |

**落库侧硬证据 —— `known_hosts` 真被写入（证明 Trust 真的信任了，而不是"自动信任"）**：

```
$ adb -s emulator-5554 shell run-as com.xiufeigo.opencodeshell ls -la files/ssh/
-rw------- 1 u0_a230 u0_a230  98  2026-10-03 03:25 known_hosts      <-- 本轮 tap Trust 的同一分钟

$ adb -s emulator-5554 shell run-as com.xiufeigo.opencodeshell cat files/ssh/known_hosts
[127.0.0.1]:2222 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIP4xhCsSfuwShy6+t+va8TMG1R1brte/e+EImnSO8sQM
```

该条 `[127.0.0.1]:2222 ssh-ed25519 …` 的**主机键与卡上 `MD5:9b:19:f4:e8:83:e3:ca:ad:1d:c0:62:76:76:48:9b:24` 对应**，即 jsch `promptYesNo == true` 之后真的调了 `KnownHostsRepository.add`。

**隧道表 value 长度**：`268`（1 条）→ **`464`**（2 条），**+196**，与建第 2 条同步。

> **A4 判定：PASS —— `Trust and connect` 有明确可观测结果，且是"连接推进"而非空操作：**
> **① 核验卡立即撤下（5/5 采样 `cardUp=FALSE`，第六轮的"14 次采样卡纹丝不动"不复现）；② `known_hosts` 新增该主机键（98 B，mtime 与 tap 同分钟）；③ 状态机推进到 `SSH authentication` / `Password:` 口令挑战卡 —— 即修复报告 §5 B 段第 12 步列的"进入认证态"这一条。**
> **明确不把"自动信任"当成功**：这里恰恰相反 —— 是用户点了 Trust，宿主才写 `known_hosts`；**本轮全程没有任何未点 Trust 就写 `known_hosts` 的观察**（`files/ssh/` 目录 mtime 03:25 与 tap 同分钟；此前 11:24:10 的读数是**空目录** `total 16`，只有 `.` 与 `..`）。

---

---

## 2. B — D3-3 / D3-4（卡修复后已可做）

### 2.0 前置：卡阻塞已解 —— 本轮**连续建到 3 条**

第六轮的 1/4 条卡在"核验卡挡住 `Add SSH server`"，本轮三次连续建条全部成功（每条建完都在卡上点 `Cancel`，**两跳**后回到面板）：

| 建条 | 目标 | 核验卡 | 处理 | 隧道键 len |
|---|---|---|---|---|
| `E2ECHK5554d3d-1` | `ssh -p 2222 sshtest@127.0.0.1` | 弹（`cardUp=TRUE`） | 点 `Cancel` → 卡退 / 行 `Disconnected` | 76 → **268**（+192） |
| `E2ECHK5554d3d-2` | 同上 | 弹 | 改点 `Trust and connect` | 268 → **464**（+196） |
| `E2ECHK5554d3d-3` | 同上 | 弹 | 点 `Cancel` → 卡退 | 464 → **660**（+196） |

**第 3 行真实存在** —— 滚动遍历读数（每步先断言 `mInputShown=false`，`dump` 只报可见节点）：

```
[B1 s0] IME=false panelRows=[E2ECHK5554d3d-1|E2ECHK5554d3d-2|E2ECHK5554d3d-3]
[B1 s1] IME=false panelRows=[E2ECHK5554d3d-2|E2ECHK5554d3d-3]
...
=== PANEL ROWS ENUMERATED ===
  ROW E2ECHK5554d3d-1
  ROW E2ECHK5554d3d-2
  ROW E2ECHK5554d3d-3
PANEL_ROWCOUNT=3
```

行锚点用**左边界 `x=201`**（面板行标签位）——这一步很关键：第一版用 `E2ECHK5554d3d-\d+$` 匹配时**把对话框里的 `Connect to E2ECHK5554d3d-3` 标题和表单的 name 字段值也算成了"行"**，`ROWCOUNT=3` 因此不可信（真值当时恰为 3，撞上了，掩盖了污染）。收紧到 `l∈[190,210]` 后读数才成立。

> **B1 判定：PASS —— ≥3 条达成，第 3 行真实存在；落库 76→268→464→660 与 UI 行数 1→2→3 同步。**

### 2.1 D3-3 第 3 行（`E2ECHK5554d3d-3`）—— ✅ PASS

| 步骤 | 读数 |
|---|---|
| 第 3 行 | `E2ECHK5554d3d-3 [201,1280][517,1321]` |
| 行内 ⋮ | `cd=Server actions clk=true [780,1440][906,1566]` → tap @ **843,1503** |
| 菜单项 `Delete` | `[618,1598][717,1651]` → tap @ **668,1624** |
| **确认条标题** | `Delete SSH server? [233,1604][546,1645]` |
| **确认条正文** | `This removes "E2ECHK5554d3d-3" from the list. This cannot be undone. [233,1656][848,1726]` |
| **确认条按钮 y 区间** | `Delete [707,1780][811,1821]`、`Cancel [501,1780][612,1821]` ⇒ **y = 1780..1821** |
| **视口** | 屏高 **2424** ⇒ **完全落在视口内 = True**（且不与固定页脚重叠） |
| 确认 | tap @ **759,1800** |
| 删除后行清单 | `E2ECHK5554d3d-1 [201,955][510,996]`、`E2ECHK5554d3d-2 [201,1280][516,1321]` ⇒ **COUNT 3 → 2** |
| `TARGET_GONE(d3d-3)` | **YES** |
| 落库 | 隧道键 **660 → 464（Δ −196）**，与建条 +196 严格对称 |

> **D3-3 第 3 行：PASS** —— 确认条可见可点（**y = 1780..1821**，落在 2424 视口内），**删的正是第 3 行**，落库同步回退一条。

### 2.2 D3-3 第 4 行 —— ❌ **未取证（不编造）**

需 ≥4 行；删掉第 3 行后只剩 2 行，补建第 4 条时 **`EnsurePanel` 在"面板已滚下去"的状态下找不到 `Add SSH server`**（该按钮固定在面板顶部，滚走后不在 a11y 树里），连打 2 轮 `no panel` 后本轮时间预算耗尽，遂中止该分支转去做 D3-4 与清理。

**失败原因与核验卡无关**（`cardUp=FALSE`、`focus=OcActivity` 正常），是**导航脚本没先滚回面板顶部**。修法见 §4.6 ⑥。**判定：未取证。**

### 2.3 D3-4 三态

| 态 | 判定 | 证据 |
|---|---|---|
| **`Connecting`** | ⚠️ **态已观测，但"Connecting 中删除"未取证** | 态本身有真读数：建条瞬间 `Connecting over SSH… [201,1261][510,1296]` + 全屏 `Connecting to SSH server [384,1339][801,1349]`（A1 `U11_card`）。但**删除窗口没抓住**：11:33:36 点 `Reconnect @ 408,1100` 后连采 3 次（0.7s/次）全部已是 `Disconnected. The remote server is left running.`，`cardUp=FALSE` —— `Connecting` 窗口比一次 dump 还短。**原因（可解释）**：`Trust` 之后 `127.0.0.1:2222` 已进 `known_hosts`，重连**不再弹核验卡**、直接进认证/失败，`Connecting` 只闪一下。 |
| **`Connected`** | ❌ **未造出** | 本轮**未铺私钥**（`E2ECHK5554_provision_key.ps1` 未执行）。且 `keypass123` 不是 `sshtest` 的有效口令，重连只会落到失败/认证态，**到不了 `Connected`**。 |
| **`Authentication required`** | ❌ **未造出（试过什么，全写在此）** | ① 建条自动进入连接流程 ⇒ 弹的是**主机身份核验卡**，不是口令挑战；② 点 `Trust and connect` 后推进到的是 **`SSH authentication` / `Password:` 挑战卡**（A4）—— **这是认证态的卡片形态，但不是行状态字符串 `Authentication required`**；③ 走完该卡需要有效口令，`keypass123` 无效；④ 11:33:36 点 `Reconnect` 复现 ⇒ 3 次采样直接落 `Disconnected`，**未落 `Authentication required`**。**未证实行状态能否出现该串，不下结论。** |

> **D3-4 判定：BLOCKED** —— 指定三态中，**没有一态拿到"该态下完成删除"的完整链路**。
> **与第六轮的本质差别**：第六轮三态全空是因为**核验卡卡死面板、按钮都点不着**；本轮**核验卡不再阻塞**，造态手段已打通到"**认证挑战卡**"这一层，卡在"**没有有效凭据**造不出 `Connected` / 行状态 `Authentication required`"。

### 2.4 反例（点确认只删目标行、其它行仍在）—— ✅ PASS

点确认前（落库 660）：

```
ROWS_BEFORE_CONFIRM=[E2ECHK5554d3d-2[201,955][516,996] | E2ECHK5554d3d-3[201,1280][517,1321]]
PANEL_ROWCOUNT=3   (E2ECHK5554d3d-1 在上方，滚动遍历已收齐)
```

点确认后（落库 464）：

```
ROW_AFTER E2ECHK5554d3d-1 [201,955][510,996]
ROW_AFTER E2ECHK5554d3d-2 [201,1280][516,1321]
PANEL_ROWCOUNT_AFTER=2
TARGET_GONE(d3d-3)=YES
--- 同屏非自建行 ---
  '10.0.2.2:4095' [254,1709][474,1750]   'Server healthy' [201,1739][222,1760]   'Default' [674,1732][769,1767]
  'Server actions' [780,1687][906,1813]  <- 该行 ⋮ 完好
```

> **反例判定：PASS** —— 只删了 `E2ECHK5554d3d-3`；`E2ECHK5554d3d-1` / `-2` **各留一条**，非自建行 **`10.0.2.2:4095` 连同它的 `Server actions` ⋮ 完好保留**。清理终态再次确认：`10.0.2.2:4095 [254,1115][474,1156]` + `Server actions [780,1093][906,1219]` 仍在。

---

## 3. C — 清理 —— ✅ 已闭环

| 项 | 状态 | 证据 |
|---|---|---|
| 删净 `E2ECHK5554d3d*` | ✅ **删净** | 清理 3 轮：`pass1 rows=[-1\|-2] → pass2 rows=[-2] → pass3 rows=[] count=0`；终态 `CLEANUP RESULT rows=[] count=0` |
| 落库回到基线 | ✅ | 隧道键 value **660 → 464 → 76**，与本轮开始的 **76（空表基线）** 逐位一致；`<string>` 键数 **全程恒为 7**（再次印证"数 `<string>` 行数必得 0"）；文件 **2042 B**（基线 2040 B，差 2 B，见 §4.5） |
| app 私有目录无残留 | ✅ | `run-as … grep -rl E2ECHK5554d3d .` → **空** |
| `/sdcard` 无本轮残留 | ✅ | 本轮 dump/截图一律"pull 完立即 `rm -f`"；另补删 3 个早期 pull 失败留下的 `_S0.xml`(03:11)、`_EP2.xml`(03:32)、`_B2d6.xml`(03:29)。复核 `/sdcard/_*.xml` 现存仅 `_h.xml`、`_v91/93/94.xml` —— **均属往轮遗留**（`_im.txt` 10-01 属他轮，未动） |
| `reverse` 复核 | ✅ | `host-15 tcp:4095 tcp:4095`、`host-15 tcp:2222 tcp:22` —— **本轮一条都没建**（全程只连设备本地 `127.0.0.1:2222`） |
| 只动自建条目 | ✅ | 只创建/删除了 `E2ECHK5554d3d-1..3`；`10.0.2.2:4095` 全程未动（§2.4 反例佐证） |
| 设备健康 | ✅ | 全程 `focus=OcActivity`，**从未出现 `Application Not Responding`**，**未 reboot**；仅对本 app 做过 `am force-stop`（纪律允许） |

---

## 4. 未闭环 / 给下一轮

1. **【最高】核验卡修复本身：全绿，无遗留。** `Cancel` 三判据、严格两跳、`Trust`（含 `known_hosts` 落盘硬证据）全部真机 PASS。**修复报告 §6.2"Trust 在新鲜卡上有效未被单独取证"这一条，本轮已闭环** —— A4 是第一次在**未被 Cancel 污染过**的新鲜卡上点 Trust。
2. **D3-3 第 4 行：未取证**（§2.2）。卡在我的导航脚本没先滚回面板顶部，**不是产品缺陷**；下一轮补建一条即可，删除链路已在第 3 行证明可用。
3. **D3-4 三态：均未拿到"该态下完成删除"的完整链路**（§2.3）。下一轮造态配方建议：
   - `Connected`：先跑 `research\v97_verify\5554\E2ECHK5554_provision_key.ps1` 铺 `files/id_ed25519`，用 `PrivateKeyPath`、keypath `/data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519`、passphrase 留空、卡里输 `keypass123`；**但必须先独立确认对端真的接受该密钥** —— 本轮 `keypass123` 对 `sshtest` 无效，是没造出 `Connected` 的首要嫌疑。
   - `Authentication required`：需要"对端可达但口令故意填错"。**本轮未能确认 `127.0.0.1:2222` 后端是否真有 sshd 在监听**（`adb reverse` 存在 ≠ 对端在监听），建议下一轮先在 `adb shell` 侧独立验证再进 UI。
   - `Connecting` 中删除：应改用**建条自动进入连接流程**的那段窗口（该段 `Connecting` 稳定存在），而不是事后点 `Reconnect`。
4. **`known_hosts` 已被本轮写入且未清理**（`[127.0.0.1]:2222 ssh-ed25519 AAAAC3…`，98 B，mtime 03:25）。它既是 A4 的证据、也属自建条目的信任记录，**但会让下一轮重建同名条目时不再弹核验卡** —— 这正是本轮 D3-4 `Connecting` 抓不住窗口的**直接原因**。**建议下一轮开跑前先记录并清掉它**，否则 A 段"弹卡"前提失效。
5. **未解释的 2 字节**：清理后 `oc_server_config.xml` = 2042 B，基线 2040 B（第六轮记的是"差 10 B"）。隧道键 value 已精确回到 76、`grep` 全树无 `E2ECHK5554d3d`，**推测是 XML 序列化/属性顺序差异，非数据丢失**，**未逐字节证实**。
6. **工具侧教训（本轮踩到）**：
   - ① **表单 fill 必须用 TAB 焦点路由，不能靠坐标 tap**。name 框未滚动到位时是 **6px 残片**（`h=6`/`h=12`），tap 其中心不聚焦（`foc=false`），连续 8 次滚动重试 bounds 纹丝不动；改用 **TAB1→name、TAB2→认证 chip、TAB3→password** 后一次通过，**全程零坐标**。这正面印证了 v98 的 IME-next 路由（`SshImeNextRoutingF13aTest`）在真机可用。
   - ② **focused 之后字段会位移**：聚焦 target 后它从 `[138,1576][943,1702]` 移到 `[138,1186][943,1312]`，**用旧 bounds 反查会 tap 错框**（本轮把 name 追加进了 target：`ssh -p 2222 sshtest@127.E2ECHK5554d3d-10.0.1`）。**每步重新 dump 取 bounds。**
   - ③ PowerShell 保留变量 `$host` 赋值会抛错并中断整段脚本。
   - ④ 双引号串里的 `$_` 插值会触发 ParserError；先把 `$_` 结果存进变量再拼串。
   - ⑤ `am force-stop` 与 `am start` 相隔 1.5s 会抢跑，`dumpsys window` 报 `FOCUS=null`；要隔 **≥3s**。
   - ⑥ **面板滚下去后 `Add SSH server` 离树**（顶部固定项）—— 导航必须先**向上滚到顶**再找它（D3-3 第 4 行死在这）。
   - ⑦ **确认条 `Delete` 文本节点自身 `clickable=false`**（可点的是父容器），按 `clickable=true` 过滤会找不到按钮；应按"同名取最后一个 + 与菜单项 bounds 不同"定位。
   - ⑧ **`input text` 无焦点时不报错、只是丢字**：早期一次 tap 失败后紧跟的 `input text` 把 `ssh -p 2222 …` 灌进了**会话创建输入框**，在 Home 侧建出一个 `Untitled` 会话。**每步 `input text` 前必须断言 `focused=true`。**

---

## 附：本轮证据文件

`research/v97_verify/5554_v98d3/`
- `D00_run.log` —— 全程带时间戳日志（主证据）
- `c7lib.ps1` —— dump/解析/Tap/截图/行计数/落库读数helper（双引号+单引号同时匹配）
- `a1b.ps1` —— A1 建条（TAB 路由版，可复现）
- `a2.ps1` / `a4.ps1` —— A2/A3 三判据 / A4 Trust
- `b_funcs.ps1` / `b2.ps1` / `b4.ps1` —— 建条、行枚举（x=201 锚点）、删除、D3-4、清理
- `U11_card` / `C20_card_before` / `C21_after_card_cancel` / `C31_panel_after_footer_cancel` / `A40_before_trust` / `A42_after_trust` / `A50_auth_card` / `E00_confirm_bar` / `E01_after_delete3` / `B1_three_rows` / `D4_connecting` / `CL_final` —— 关键截图（均已补 `.png` 后缀）
- `_prefs_*.txt` / `_prefs_final.txt` —— 落库快照（隧道键 len 全程轨迹：76→268→464→660→464→76）

