# v97 悬挂项真机复验报告 — emulator-5558 (Pixel_10_Pro_XL)

执行者：真机复验测试员（悬挂项批）
仓库：`C:\sensorsdata\main\program\opencode-android-shell`
证据目录：`research/v97_verify/5558/`（本文件同目录）
纪律：禁 gradle / 禁装机 / 禁杀进程 / 禁碰 `127.0.0.1:5555`；禁 BACK(4)；弹层内禁 ESC(111)（收键盘一律 TAB=61）；
截图只用 `screencap -p /sdcard/x.png` + `adb pull`（禁 exec-out）；`input text` 不带字面空格（空格一律 keyevent 62）；坐标全部运行期从 dump 解析。

---

## 0. 环境读数

| 项 | 命令 | 读数 | 判定 |
|---|---|---|---|
| 设备 serial→AVD 复核 | `adb -s emulator-5558 emu avd name` | `Pixel_10_Pro_XL` | ✅ 与派单一致（未凭惯性） |
| 设备在线 | `adb devices -l` | `emulator-5558  device …transport_id:14` | ✅ |
| 屏幕 | `adb shell wm size` / `wm density` | `1344x2992` / `480`（=3.0x，448dp 宽） | 记录 |
| APK 版本 | `dumpsys package com.xiufeigo.opencodeshell` | `versionCode=26 minSdk=26 targetSdk=35` / `versionName=1.4.0` | ✅ |
| APK 时戳（复核 18:01 新包） | 同上 | `lastUpdateTime=2026-10-02 10:01:34`，`firstInstallTime=2026-09-26 13:38:36` | ✅ **与派单读数一致** |
| 反向隧道 | `adb -s emulator-5558 reverse --list` | `host-15 tcp:4095 tcp:4095` / `host-15 tcp:2222 tcp:22` | ✅ 两条都在 |
| 后端健康 | `curl -u opencode:opencode http://127.0.0.1:4095/api/info` | `{"version":"2.0.18","pid":260,"urls":["http://127.0.0.1:4095"],"paths":{"tmp":"/tmp/opencode"}}` | ✅ 200（`/api/health` 按纪律未用） |
| launcher 拉起 | `am start -n com.xiufeigo.opencodeshell/.OcActivity` | `mCurrentFocus=Window{3b24cd6 u0 com.xiufeigo.opencodeshell/com.xiufeigo.opencodeshell.OcActivity}` | ✅ |

原始读数：`env_apk.txt`、`env_reverse.txt`、`env_api_info.txt`（同目录）。

APK 复核要点：`lastUpdateTime=2026-10-02 10:01:34` 与派单给的时间戳**逐字相同**，说明 Lead 18:01 那版新 APK 确实在机上，本轮测的就是修复后版本（v74 TurnMarker / v75 submit 原文 / v71 WORKSPACE_ROW_END）。

启动首屏（Home）证据：`E2ECHK5558_env_00_launch.png` / `ui_E2ECHK5558_env_01_home.xml`。
Home 可见项目 5 个：`x` / `quiet-pixel` / `E2ECHK_xiufeigo` / `research` / `opencode-android-shell`。
Home 可见会话中属于本 tester 的历史残留（1 day ago）：`E2ECHK5558-draft` / `E2ECHK5558-ok` / `E2ECHK5558-probe1` 等 — 本轮不动它们，另建新会话。

---

## 总判定

| # | 悬挂项 | 判定 |
|---|---|---|
| 1 | v74 Stop 落在 provider 未输出时的中断痕迹 | **PASS** |
| 2 | v75 发送文本的尾随空格端到端 | **PASS**（中间空格 + 尾随空格两条都过） |
| 3 | v71 worktree 行尾 × 的命中几何 | **PASS**（7/7 与 v71 口径逐点一致；附一处口径更正） |

---

## 1. v74「Stop 落在 provider 还没输出时」的中断痕迹

### 1.0 判定：**PASS**

### 1.1 前提核对（这个测试到底有没有落在"provider 还没输出"上？）
先说结论：**落在了**——服务端读回坐实了这一点，不是靠 UI 猜的。

会话：`ses_f03d25e57ffeyp681Hi1ZefyXq`（我新建的，已改名为 `E2ECHK5558_STOP v74 prestream marker`），目录 `/home/xiufeigo`，模型 `GPT-6.1 Sol`。
`GET /api/session/{id}/message` 原始返回（全文存 `S1_messages.json`）：

```json
{"data":[
 {"id":"msg_0fc2f06e300145CoKZ6mX7QH9r","time":{"created":1790937335523},"type":"idle","outcome":"interrupted"},
 {"id":"msg_0fc2efc14001UnK68d2VLAEq0d",
  "metadata":{"displayText":"E2ECHK5558_STOP explain in depth how a transistor works and why it replaced the vacuum tube, be very thorough and consider every angle"},
  "time":{"created":1790937332805},
  "text":"E2ECHK5558_STOP explain in depth how a transistor works and why it replaced the vacuum tube, be very thorough and consider every angle",
  "type":"user"}
],"cursor":{...}}
```

**整个会话只有 2 行：1 条 `user` + 1 条 `idle{outcome:"interrupted"}`，中间没有任何 assistant 行。**
两行相隔 `1790937335523 − 1790937332805 = 2718ms`（≈3s，与 v74 记的「3 秒后」一致）。
会话级读回同样确认：`GET /api/session/{id}` → `"outcome":"interrupted"`、`time.idle=1790937335523`。
→ 这正是 v74 描述的服务端行为，**证明 Stop 确实落在 provider 尚未产出任何内容时**（若已出字，服务端会写 assistant 行）。

### 1.2 真机断言：时间线有中断标记/分隔线，且有那条 user 行
Stop 后第 1 次采样（+4436ms）即同时满足两个条件（`E2ECHK5558_S1_samples.csv`）：

```
sample atMs interruptedDivider userBubble
     1  4436               True       True
```

停稳后的完整 dump（`ui_E2ECHK5558_S1_41_after_stop.xml`）关键节点：
```
Interrupted	[569,1116][775,1163]	pw=false	foc=false	android.widget.TextView	clk=false
E2ECHK5558_STOP explain in depth how a transistor works and why it replaced the vacuum tube, be very thorough and consi
der every angle	[309,593][1260,833]	pw=false	foc=false	android.widget.TextView	clk=false
10:35 AM	[933,883][1086,927]
Revert message	[1086,833][1188,977]	android.widget.Button	clk=true
Copy message	[1188,833][1332,977]	android.widget.Button	clk=true
```

截图 `E2ECHK5558_S1_40_after_stop.png`：**用户气泡在（完整原文 + 10:35 AM + 撤销/复制）→ 一条分隔线 → 中间写着 `Interrupted`**，其下**没有空的 assistant 气泡**。

这正是 v74 修法要的两件事，都成立：
- ✅ **有中断标记/分隔线**（不是「只剩用户气泡、assistant 完全空白」）；
- ✅ **那条 user 行也在**（v62 handoff 的本地行 / 本次是服务端 `user` 行本身）；
- ✅ 分隔线下方**没有空壳气泡** —— `interruptedMarkerRow` 的 `parts = emptyList()`，只承载分隔线，正是 v74 注释里写的「partless 行」语义。

### 1.3 时序
| 事件 | 相对时间 |
|---|---|
| TAP Send | `t=5200ms`（脚本单调钟） |
| TAP Stop（从新 dump 里解析出的 Stop 节点，坐标 1242,1894 —— 与 Send 同槽位，按钮原地变形） | `t=7957ms`，即 **Send 后 2758ms** |
| 第 1 次时间线采样 | Stop 后 **+4436ms** |
| 脚本结束 | `t=15203ms` |

Stop 的定位延迟由 `uiautomator dump` 本身决定（本机单次 dump ≈ 2.2s），脚本没有隐藏它：**Send→Stop 2758ms 内 provider 一个字都没吐**（服务端无 assistant 行即为证）。

### 1.4 证据文件
- 脚本：`E2ECHK5558_s1_stop.ps1`；控制台：`E2ECHK5558_S1_console.txt`
- 截图：`E2ECHK5558_S1_10_composer.png`（建会话后）/ `E2ECHK5558_S1_20_typed.png`（prompt 逐字进 composer）/ `E2ECHK5558_S1_40_after_stop.png`（**判定截图**）
- dump：`ui_E2ECHK5558_S1_19_probe.xml`、`ui_E2ECHK5558_S1_20_typed.xml`、`ui_E2ECHK5558_S1_21_running_0.xml`（Stop 节点）、`ui_E2ECHK5558_S1_31_sample.xml`、`ui_E2ECHK5558_S1_41_after_stop.xml`
- 采样表：`E2ECHK5558_S1_samples.csv`；服务端：`S1_messages.json` / `S1_session.json`
- prompt 进 composer 的逐字核对（`ui_E2ECHK5558_S1_20_typed.xml`）：
  `E2ECHK5558_STOP explain in depth how a transistor works and why it replaced the vacuum tube, be very thorough and consider every angle`

## 2. v75「发送文本的尾随空格」端到端

### 2.0 判定：**PASS（两条都过）**

会话：`ses_f03cf5dcbffe43ECoCvhkNDAOO`，已改名 `E2ECHK5558_SPACE v75 whitespace e2e`，目录 `/mnt/c/sensorsdata/main/program/opencode-android-shell`，模型 `gpt-6.1-sol`。
两轮发在**同一个会话**里（A 先、B 后），断言对象是**服务端落盘的 user 消息**（比 UI 更硬），UI dump 作为旁证。

### 2.1 造空格的方式（严格遵守 v75 纪律）
**全程没有用过字面尾随空格，也没有用 `%s`**：
- 词用 `input text <段>`，段长 ≤ 8 字符、段间 sleep；
- 空格一律是**真的 KEYCODE_SPACE**：`adb -s emulator-5558 shell input keyevent 62`；
- 发送前每轮都 dump 一次 composer，逐字节核对 `text=`。

### 2.2 断言 ①：中间空格 —— `hello` + SP + `world` → 落盘必须是 `hello world`

发送前 composer dump（`ui_E2ECHK5558_S2_10A_typed.xml` 的 `text=` 属性）：
```
hello world	[84,1678][1260,1822]	pw=false	foc=true	android.widget.EditText	clk=true
```
服务端读回（`S2_messages.json` 原文片段）：
```json
{"id":"msg_0fc30dd56001HmlX5dDBBAqaPQ","text":"hello world","type":"user", …}
```
逐字节打印（`E2ECHK5558_spaces.py`，`·` = 空格）：
```
--- user row #2  id=msg_0fc30dd56001HmlX5dDBBAqaPQ
    repr      : 'hello world'
    visible   : hello·world
    len()     : 11
    tail bytes: 20 77 6f 72 6c 64
    first space at index 5, context 'hello world'
```
→ 中间那个空格**逐字节在**（index 5，U+0020）。✅

### 2.3 断言 ②：尾随空格 —— `hello` + SP → 落盘必须带尾随空格

发送前 composer dump（`ui_E2ECHK5558_S2_20B_typed.xml` 的 `text=` 属性，`<SP>` 是把空格可视化后的样子）：
```
[hello<SP>]  len=6
```
服务端读回（`S2_messages.json` 原文片段）：
```json
{"id":"msg_0fc310deb001Giw7JCeRuILWZQ","metadata":{"displayText":"hello "},"time":{"created":1790937491537},"text":"hello ","type":"user"}
```
逐字节打印：
```
--- user row #1  id=msg_0fc310deb001Giw7JCeRuILWZQ
    repr      : 'hello '
    visible   : hello·
    len()     : 6
    tail bytes: 68 65 6c 6c 6f 20
    >>> TRAILING SPACE PRESENT (ends with U+0020)
```
→ 落盘文本**以 U+0020 结尾**，尾随空格没被 `submit()` 的旧 `.trim()` 吃掉。✅

**这就是 HANDOFF.md:65 结尾「下轮复验『输入 `hello `（尾随空格）发送 → 服务端/时间线 text 带尾随空格』」那条唯一未上机的验收点，现在有真机证据了。**

### 2.4 UI 侧旁证
`ui_E2ECHK5558_S2_40_after.xml`（发送后时间线）里两条 user 气泡的 `text=` 原文：
```
[hello<SP>]        len=6     <- 尾随空格仍在（渲染上看着是 "hello"，但字节在）
[hello<SP>world]   len=11
```
截图 `E2ECHK5558_S2_40_after.png`（两条气泡 + tab 标题 `E2ECHK5558_SPACE v75 w…`）。
**附带观察（非本项断言）**：B 轮是在 A 轮仍在跑时发的，UI 上该条显示为 `Queued`（本地 handoff 行），但服务端已经把它作为 `user` 行持久化了（上面的 `msg_0fc310deb…` 就是它），所以断言不受影响。

### 2.5 证据文件
- 脚本：`E2ECHK5558_s2_space.ps1`；逐字节打印器：`E2ECHK5558_spaces.py`；控制台：`E2ECHK5558_S2_console.txt`
- 截图：`E2ECHK5558_S2_10A_typed.png`（A 轮发送前）/ `E2ECHK5558_S2_20B_typed.png`（B 轮发送前，尾随空格可见）/ `E2ECHK5558_S2_30_timeline.png` / `E2ECHK5558_S2_40_after.png`（**判定截图**）
- dump：`ui_E2ECHK5558_S2_10A_typed.xml`、`ui_E2ECHK5558_S2_20B_typed.xml`、`ui_E2ECHK5558_S2_31_timeline.xml`、`ui_E2ECHK5558_S2_40_after.xml`
- 服务端：`S2_messages.json`（原始响应）、`S2_timeline_nodes.txt`

## 3. v71「worktree 行尾 × 的命中几何」

### 3.0 判定：**PASS**（含一处「派单坐标不可直接套用」的口径更正）

### 3.1 可达性：可达
先用只读 API 判可达性（不建不删任何东西）：`GET /api/worktree?projectID=<id>` 对 Home 上 5 个项目逐个查。命中 4 个有 worktree 的项目，本轮用 `/home/xiufeigo`（UI 名 `E2ECHK_xiufeigo`，projectID `264884bb26d35255a58501108794dbfde2777686`）。

```
PROJECT /home/xiufeigo => [{"directory":"/home/xiufeigo/.local/share/opencode/worktree/264884/cosmic-comet","strategy":"git"},{"directory":"/home/xiufeigo"}]
PROJECT /mnt/c/sensorsdata/main/program/opencode-android-shell => [{"directory":".../worktree/ae5407/curious-cabin","strategy":"git"}, …共 5 条… , {"directory":"/mnt/c/sensorsdata/main/program/opencode-android-shell"}]
```
原始读数：`env_api_projects.json` / `env_api_projects.txt`。
UI 入口：Home → `New session` → 点位置 chip `Local ⌄` → 弹层「Worktrees」段出现 `cosmic-comet` 行 + 行尾 `×`。
截图 `E2ECHK5558_S1_04_localmenu.png`，dump `ui_E2ECHK5558_S1_05_localmenu.xml`。

### 3.2 口径更正（重要）：派单给的 x 是 5556@160dpi 的绝对值，本机不适用
派单口径「x ∈ 793..839 全应打开删除确认，x ≥ 841 应只关菜单」是 v71 在 **emulator-5556 @ 160dpi** 上量的。**5558 是 480dpi（3.0x）、屏宽 1344px**，那两个绝对 x 落在弹层左侧 ~450px 处（行体上），在本机**根本不构成判据**。所以本轮同时扫了两组：

- **A 组（派单字面 x，存档）**：793/807/823/839/841/860/900
- **B 组（把 v71 的偏移量按 3.0x 重新基准化到本机 dump 实测的环右缘）**：这才是判据。

重新基准化依据（全部运行期从 dump / 像素探针得到，未硬编码）：
- × 的 48dp 手指环 = dump 的 `Delete worktree` 节点 `[1001,1896][1145,1980]`，**宽 144px = 48.0dp** ✓（`ocMinTouchTarget(visual=16.dp)` 的 target=48dp）。
- 弹层卡片右缘：`E2ECHK5558_edgeprobe.py` 在 y=1938 逐像素扫描，卡片内部 `(250,250,250)`，**边框落在 x=1143..1146（最深 x=1145 值 220）**，即卡片右缘 ≈ **x=1145**。
- 行标签 `cosmic-comet` = `[653,1938][1001,1980]`；行中心 y = **1938**（每次都从当次 dump 重取）。

**关键结论：环右缘 1144/1145 正好压在卡片右缘 1145 上** —— 这正是 v71 `WORKSPACE_ROW_END = touchTargetRingOverflow(16.dp) = 16dp` 要的效果（行尾留出整个溢出，环不再挂进 scrim）。

### 3.3 逐点扫描结果

B 组（v71 偏移量 × 3.0 重新基准化，**这组是判据**）：

| v71 x | 偏移@160dpi | 5558 实扫 x | 距环右缘 | 观测结果 | 期望 | 判定 |
|---|---|---|---|---|---|---|
| 793 | −47px | **1004** | −141 | **DELETE_CONFIRM** | 删除确认 | ✅ |
| 807 | −33px | **1046** | −99 | **DELETE_CONFIRM** | 删除确认 | ✅ |
| 823 | −17px | **1094** | −51 | **DELETE_CONFIRM** | 删除确认 | ✅ |
| 839 | −1px | **1142** | −3 | **DELETE_CONFIRM** | 删除确认 | ✅ |
| 841 | +1px | **1148** | +3 | MENU_CLOSED_ONLY | 只关菜单 | ✅ |
| 860 | +20px | **1205** | +60 | MENU_CLOSED_ONLY | 只关菜单 | ✅ |
| 900 | +60px | **1325** | +180 | MENU_CLOSED_ONLY | 只关菜单 | ✅ |

**7/7 与 v71 口径逐点一致。**

C 组（环边界钉死）：

| x | 说明 | 结果 | 判定 |
|---|---|---|---|
| 1001 | 环**左**缘 | DELETE_CONFIRM | ✅ 整个 48dp 环可点（含 glyph 左侧 48px 溢出） |
| 1145 | 环**右**缘 / 卡片边框 | MENU_CLOSED_ONLY | ✅ 环外即 scrim |
| 1146 | 卡片边框再右 1px | MENU_CLOSED_ONLY | ✅ |

A 组（派单字面 x，如前所述落在行体，**不是判据**）：

| x | 距环右缘 | 结果 | 说明 |
|---|---|---|---|
| 793 / 807 / 823 / 839 / 841 / 860 / 900 | −352…−245 | 全 MENU_CLOSED_ONLY | 落在行体 → **选中该 worktree**（chip 由 `Local` 变 `cosmic-comet`、分支 chip 变 `Detached HEAD`），不是删除 |

### 3.4 证据文件
- 扫描脚本：`E2ECHK5558_s3_scan.ps1`（坐标全部 dump 解析；弹层/菜单同名 `Delete worktree` 的消歧、只经 dump 定位的 Cancel 关闭弹层）
- 汇总表：`E2ECHK5558_S3_scan.csv`；完整控制台日志：`E2ECHK5558_S3_scan_console.txt`
- 每个扫描点一截图 + 一 dump：`E2ECHK5558_S3_x{793,807,823,839,841,860,900,1004,1046,1094,1142,1148,1205,1325,1001,1145,1146}.png` / `ui_E2ECHK5558_S3_x*.xml`
- 代表性截图：
  - `E2ECHK5558_S3_x1142.png` = 环内 → 「Delete worktree?」确认弹层
  - `E2ECHK5558_S3_x1148.png` = 环外 3px → 菜单关闭、无弹层
  - `E2ECHK5558_S3_x793.png` = 行体 → 选中 worktree
- 弹层像素探针：`E2ECHK5558_edgeprobe.py` + `E2ECHK5558_S1_04_localmenu.png`

### 3.5 附带观察：竖直方向的环溢出仍在（v71 已知残留，未动）
dump 里 `Delete worktree` 节点是 `[1001,1896][1145,1980]`，**高 84px = 28dp**（= 行高），不是 144px=48dp；宽度却是完整 144px。即**竖直方向的溢出被 uiautomator 截到父 Row 盒内**。按 `ocMinTouchTarget` 的布局算（offset = (48−144)/2 = −48px），环的竖直范围约 y 1848..1992，**盖住 `Refresh worktrees` 行上沿**——即 v71 记录里那条已知竖直残留（160dpi 下 10px），在 3.0x 下按比例约 30px。v71 结论是「要行高到 48dp 才消，属产品可见项，未动」，本轮沿用该裁决，未改行高。竖直方向未逐点扫描（派单口径只给了水平 x）。

### 3.6 ⚠ 本轮自造成并已复原的一起事故（必须记录）
首次跑扫描时，脚本用 `^Delete worktree\t` 判定「菜单里有 worktree 行」，而**删除确认弹层的动作按钮 content-desc 同样是 "Delete worktree"**（见 `E2ECHK5558_S3_01_probe_confirm.png` / `ui_E2ECHK5558_S3_02_probe_confirm.xml`，弹层内同时有 `Delete worktree?` 标题与 `Delete worktree` 按钮）。脚本因此把弹层按钮的包围盒 `[873,1753][1167,1800]` 误当成行的环，按它算出 y=1776 并去点 x=793 —— 落在确认弹层「Delete worktree」**按钮的命中区内**（该按钮实际绘制范围约 x 774..1198；Cancel 文本止于 705、Delete 文本起于 873，按钮命中区在两段文字之间连续）。

后果：**误删了共享后端上的 `/home/xiufeigo/.local/share/opencode/worktree/264884/cosmic-comet`**。

- **无数据丢失**：opencode 的 `DELETE /api/worktree` 对脏 worktree 先返 400 `forceRequired`；能一次删成功即证明该 worktree **工作区干净**（`git worktree remove` 未 force 直接成功）。丢的只是 checkout 本身。
- **已复原**（走服务端自己的创建端点）：
  ```
  POST /api/worktree {"projectID":"264884bb26d35255a58501108794dbfde2777686","from":"/home/xiufeigo","name":"cosmic-comet"}
  -> {"directory":"/home/xiufeigo/.local/share/opencode/worktree/264884/cosmic-comet"}
  ```
  `GET /api/worktree?projectID=264884bb…` 读回与事故前**逐字节相同**（`S3_worktree_after.json` = `[{"directory":".../cosmic-comet","strategy":"git"},{"directory":"/home/xiufeigo"}]`）；`git worktree list` 为 `…/cosmic-comet 264884b (detached HEAD)`，与同项目幸存的 `misty-forest` 同一 commit。app 侧重新进入草稿页后 `cosmic-comet` 也回到列表、**包围盒与事故前完全一致**（`ui_E2ECHK5558_S3_recov_10.xml`）。
- **整轮扫描 18 个点全部只 Cancel 不确认**，扫完复查 worktree 仍在（`S3_worktree_after.json`）。
- 教训（已改进脚本并写进文件注释）：**弹层与宿主可同名 content-desc**，状态判定必须用无歧义锚点（本轮用带问号的 `Delete worktree?`）；**弹层未确认关闭前不得落任何扫描坐标**；关弹层只走 dump 定位的 Cancel，绝不按 BACK/ESC。

## 4. 未闭环清单

| # | 事项 | 状态 | 说明 |
|---|---|---|---|
| 1 | **本轮误删共享 worktree `cosmic-comet`（已复原）** | 已复原，需 Lead 知悉 | 见 §3.6。工作区干净（删除未 force 成功即证），走服务端 `POST /api/worktree` 复原，API 读回与事故前逐字节相同，`git worktree list` 同 commit。本轮结束复查仍在。**需要 Lead 确认是否还有别的 tester 在等这个 worktree。** |
| 2 | v71 竖直方向的环溢出（环盖住 `Refresh worktrees` 行上沿） | **未验到 / 按 v71 既有裁决保留** | dump 显示 `Delete worktree` 节点竖直只报 84px(=行高 28dp)，溢出被 a11y 截到父盒；按 `ocMinTouchTarget` 布局推算环约覆盖 y 1848..1992，3.0x 下约 30px 压住下一行上沿。v71 结论是「要行高到 48dp 才消，属产品可见项，未动」。**本轮只扫了派单要求的水平 x，没做竖直逐点扫描**，也未改行高。 |
| 3 | 派单给的 v71 绝对 x（793/807/823/839/841/860/900）在本机 | 已扫但**不构成判据** | 那组坐标是 5556@160dpi 的；本机 480dpi/1344px 宽，二者差 3.0x 缩放 + 弹层锚点不同。字面点全部落在行体上（结果=选中 worktree）。判据用的是「同样偏移量 ×3.0 重新基准化」的等价点，7/7 与 v71 口径一致。**建议下一轮把 v71 的复验口径改成「相对环右缘的偏移量」而不是绝对 x。** |
| 4 | v75「`%s` 路径下那 1/5 在英文 Gboard 上复现不了」 | 未动 | HANDOFF:65 记的遗留项，需要**中文布局**再取证，本轮是英文 Gboard，不在派单范围，未做。 |
| 5 | v75 规格里的「shell 模式键盘行（autocorrect/spellcheck off）」未实现 | 未动 | 产品裁决项（改键盘选项会多一次 `restartInput()`），本轮未碰。 |
| 6 | v74 Stop 定位延迟 2758ms 是否够快 | 已记录未改 | 本机单次 `uiautomator dump` ≈2.2s，Send→Stop 最短只能做到 ~2.7s。这轮**恰好**落在 provider 出字前（服务端无 assistant 行为证），但**不是稳定保证**——换更快的模型/更短的 prompt 就可能 Stop 太晚。若要常态化验收，得让 Stop 按钮坐标可预解析（或用更慢的模型/prompt）。 |
| 7 | 中文 Gboard 把 `.` 打成 `。` | 本轮未触发 | 本轮所有输入都是纯 ASCII（`hello`/`world` + v74 那条全 ASCII prompt），故未踩到；仅作记录。 |
| 8 | 竖直向的 `×` 与 `Refresh worktrees` 行的实际命中 | 未测 | 派单只给了水平 x 口径；见第 2 条。 |

**没有「未验到」的三条悬挂项本体**：三条都拿到了真机判定，且每条都有服务端读回坐实「测试条件确实成立」（不是靠 UI 观感）。

---

## 5. 纪律自检

- 全程**没有**跑 gradle、**没有**装机、**没有** force-stop / kill 任何进程（`LaunchApp` 只发 `am start -n`）、**没有**碰 `127.0.0.1:5555`；只操作 `emulator-5558`。
- 全程**没有** `keyevent 4`(BACK)；**没有**在设置/弹层里发 `keyevent 111`(ESC)；收键盘/关弹层只走 `keyevent 61`(TAB) 与 dump 定位的 Cancel / scrim 点。
- 截图全部 `screencap -p /sdcard/...` + `adb pull`，**没有用 exec-out**（`screencap` 每次都校验了字节数，<20000 会告警）。
- `input text` **全程没有字面空格、没有字面尾随空格、没有 `%s`**；空格一律 `keyevent 62`；段长 ≤8、段间 sleep。
- 所有点击坐标**全部运行期从 uiautomator dump 解析**，无硬编码（唯一的例外是 v71 扫描里那组**派单指定的**绝对 x，以及用 dump 边界算出的弹层卡片右缘——后者是像素探针实测值）。
- 只动自己建的会话，且都改成了 `E2ECHK5558` 前缀（`E2ECHK5558_STOP v74 prestream marker` / `E2ECHK5558_SPACE v75 whitespace e2e`）；别人的会话一律只读。
- 边做边写盘：本文件分三次落盘，环境 → 第 3 项 → 第 1 项 → 第 2 项 → 未闭环。
- **唯一越界**：§3.6 那次误删（弹层/菜单同名 `Delete worktree` 导致误判），已复原并在此记录。

## 6. 证据文件清单（`research/v97_verify/5558/`）

脚本/工具：`E2ECHK5558_run.ps1`（库）、`E2ECHK5558_nodes.py`、`E2ECHK5558_edgeprobe.py`、`E2ECHK5558_spaces.py`、`E2ECHK5558_s1_stop.ps1`、`E2ECHK5558_s2_space.ps1`、`E2ECHK5558_s3_scan.ps1`、`oc_db_probe.py` / `oc_wt_probe.py`（事故取证）
环境读数：`env_apk.txt`、`env_avdname.txt`、`env_reverse.txt`、`env_api_info.txt`、`env_api_projects.json`、`env_api_projects.txt`
第 1 项：`E2ECHK5558_S1_console.txt`、`E2ECHK5558_S1_samples.csv`、`E2ECHK5558_S1_10_composer.png`、`E2ECHK5558_S1_20_typed.png`、`E2ECHK5558_S1_40_after_stop.png`、`ui_E2ECHK5558_S1_*.xml`、`S1_messages.json`、`S1_session.json`、`S1_final_nodes.txt`
第 2 项：`E2ECHK5558_S2_console.txt`、`E2ECHK5558_S2_10A_typed.png`、`E2ECHK5558_S2_20B_typed.png`、`E2ECHK5558_S2_30_timeline.png`、`E2ECHK5558_S2_40_after.png`、`ui_E2ECHK5558_S2_*.xml`、`S2_messages.json`、`S2_timeline_nodes.txt`
第 3 项：`E2ECHK5558_S3_scan_console.txt`、`E2ECHK5558_S3_scan.csv`、`E2ECHK5558_S1_04_localmenu.png`、`E2ECHK5558_S3_01_probe_confirm.png`、`E2ECHK5558_S3_x*.png`（18 点）、`ui_E2ECHK5558_S3_*.xml`、`S3_worktree_after.json`
