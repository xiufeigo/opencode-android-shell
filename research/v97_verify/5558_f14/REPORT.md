# F14 真机终验报告 — emulator-5558 (SSH 域 F14，1344 宽重标定)

测试员：SSH 域 F14 独立验收设备 (emulator-5558)
状态：**进行中（边做边写）**

---

## 0. 设备身份核实（防 serial↔AVD 重排）

| 项 | 命令 | 读数 | 判定 |
|---|---|---|---|
| AVD 名 | `adb -s emulator-5558 emu avd name` | `Pixel_10_Pro_XL` | ✅ 与任务书期望一致 |
| 屏幕 | `adb -s emulator-5558 shell wm size` | `Physical size: 1344x2992` | ✅ |
| 密度 | `adb -s emulator-5558 shell wm density` | `Physical density: 480` | ✅ |
| APK versionName | `dumpsys package com.xiufeigo.opencodeshell` | `1.4.0` | ✅ |
| APK lastUpdateTime | 同上 | `2026-10-02 10:40:05` | ✅ = host 18:40 构建（含 F14b bcprov+ed25519 路由修复） |
| firstInstallTime | 同上 | `2026-09-26 13:48:36` | 记录 |
| 隧道 | `adb -s emulator-5558 reverse --list` | `host-15 tcp:4095 tcp:4095` / `tcp:2222 tcp:22` | ✅ |
| 后端健康 | `GET /api/info` (Basic opencode:opencode) | `HTTP 200` `{"version":"2.0.18","pid":260,...}` | ✅ |

纪律：未跑 gradle、未装机、未杀进程、未碰 127.0.0.1:5555；全程只操作 emulator-5558。
截图用 `screencap -p /sdcard/...` + `adb pull`（未用 exec-out）；未按 BACK/ESC。

---

## 1. 前序卡死的根因（本机实测确认）

前序两轮卡在 `Fill-SshForm`：`'name' not visible (attempt N, dir -1)` 死循环 →
`collapse IME (keyevent 61 TAB)`。**根因不是"坐标常数照抄"，而是 5554 那套按
1080 宽（Pixel_9a）标定的表单定位启发式在 1344 宽设备上结构性失效**，具体三条：

### 1.1 `Fields()` 的 `x1==138` 筛选在 1344 宽上错位
5554 用 `\t\[138,` 筛字段（排除 Settings 搜索框 x=96）。本机 5558 的
设置搜索框在 `[108,749][1236,893]`，表单字段 x 起点是 156。x 常量不再有判别力。

### 1.2 表单字段顺序与可视性不同 —— name 字段被视口裁剪
本机实测 Add-SSH 表单初始 dump（`ui_E2ECHK5558_nav_tmp.xml`）：

| 节点 | bounds | 高度 |
|---|---|---|
| 卡片 body ScrollView | `[48,1643][1296,2082]` | h=439 |
| `Host or SSH command` 标签 | `[120,1844][538,1891]` | 47 |
| host EditText | `[156,1909][1188,2053]` | 144 |
| `Server name (optional)` 标签 | `[120,2071][541,2082]` | 11 |
| **name EditText** | **`[156,2136][935,2154]`** | **18（被视口下沿裁剪）** |

关键：name EditText 的 `y1=2136` **低于 ScrollView 下沿 y=2082**，且**只有 18px 高**。
5554 的规则（字段必须 h>=50 **且** 完全包含在 ScrollView 内）会**永远排除** name 字段
→ `FieldNear/ScrollToNear 'Authentication' ... 'above'` 永远找不到 → 死循环。
这就是前序 `'name' not visible` 的直接原因。

### 1.3 5554 的字段锚点假设（"name = Authentication 之上"）在本机不成立
本机表单顺序是 **Host → Server name → Authentication**（Authentication 在更下方，
需滚动才可见），而 5554 假设 `Authentication` 标签**始终可见**、name 紧贴其上方。
在本机 `Authentication` 初始不在 dump 里，所以"找 Authentication 上方字段"必然失败。

### 1.4（本机额外发现，非 5554 问题）设置 tab 定位
5554 `GotoSettings/GotoServers` 用硬编码 `y=744` 定位 tab 条。本机 tab 条实际在
`y≈960`（Extensions/Servers/Shell/Experimental/About 都在 936-983）。用 y=744 会
在搜索框区域滑动，永远滚不到 Servers tab。我已改为**从 dump 按 tab 文本聚类 y**
（见 `TabRow`），不写死 y。

### 1.5（本机实测确认·关键）字段在表单滚动后**整块消失出 dump**
进一步诊断（`diag.ps1` 直测）确认：
- `Fields()` 本身**工作正常**：表单滚到 auth 区时它正确只返回 1 个字段
  （`[156,2028][1188,2154] pw=true`），因为此时 Host/Name 字段已滚出可视区。
- 致命点：表单一旦滚到下方 auth 区，**`Host or SSH command` / `Server name` 标签与
  字段一起从 uiautomator dump 中消失**。此时基于标签锚点的 `FieldNear` 永远找不到
  字段，而 5554 的 `ScrollToNear` 先用 dir=-1（向下）滑 6 次——**越滑越远**，之后才
  尝试反向。这就是前序死循环的完整机理。
- 因此本机改用**直接索引**：表单置顶后 `Fields()[0]`=host、`Fields()[1]`=name
  （见 `Fill-SshForm`），不再依赖标签锚点；并新增 `ScrollFormToTop` 每次填表前把
  表单滚回顶部。

> 证据：`diag.ps1` 输出（Fields count=1，仅 password 字段）、
> `ui_E2ECHK5558_nav_tmp.xml`、日志 `log_f14b.txt`。

---

## 2. 本机重标定的定位策略（已实现于 E2ECHK5558_run.ps1）

1. **设备断言**：`AssertDevice` 期望 `Pixel_10_Pro_XL` + `1344x2992`（原写死 Pixel_9a）。
2. **`TabRow`/`TabNodes`（新）**：按 tab 名的**精确首字段匹配**（规避正则字符类坑），
   对命中的 tab 节点按 y 聚类取众数行 → 得到 tab 条真实 y 与 x 跨度，再据此滑动。
3. **`Fields()`（重写）**：不再用 x 常量、不再用 h>=50+完全包含。改为"EditText 起点
   在表单 ScrollView 顶边以下、且落在其 x 跨度内"即算表单字段 —— 这样**被裁剪的
   18px name 字段也能被枚举到**；Settings 搜索框（y=749 < 顶边 1643）被自然排除。
4. **字段锚点改为"紧贴各自标签下方"**：`FieldNear 'Host or SSH command' ... 'below'`、
   `'Server name (optional)' ... 'below'`。设备无关，不依赖 Authentication 可见性。
5. **`FormSwipe`（重写）**：滑动 x 由表单 ScrollView 实际宽度动态取（原固定 x=1000），
   落在表单内、非 EditText、非键盘区；时长用 **80ms 快滑**（慢滑零位移）。
6. `GotoSettings/GotoServers` 滑动坐标全部来自 `TabRow`，无 1080 宽常量。

> 这些改动直接针对前序死循环的三个根因（1.1/1.2/1.3）。

---

## 3. 冒烟段：能否稳定定位两个关键字段

证据：`E2ECHK5558_smoke.ps1` 运行输出（见下）——**在本机已成功稳定定位**
`Host or SSH command` 与 `Server name (optional)` 两个字段（`FieldNear` 能返回非空），
且能打开表单、枚举到裁剪的 name 字段。冒烟脚本成功走通
Settings→Servers→Add SSH server→dump。**这证明本机不再有前序那种定位死锁。**

> 冒烟具体 dump 证据文件：`ui_E2ECHK5558_nav_tmp.xml`、
> `E2ECHK5558_smoke_04_form.png`、`E2ECHK5558_smoke_03_servers.png`。

---

## 4. 逐条判定表

（用例运行中，边做边补）

| 用例 | 判定 | 命令 | 证据文件（相对 5558_f14/） | 读数 |
|---|---|---|---|---|
| provision_key | **PASS** | `EnsureKey` / `E2ECHK5558_f14b_f12a.ps1` | `log_f14b.txt` | `device=0120a8270f7f0b0c71d31f4933384c63 repo=0120a8270f7f0b0c71d31f4933384c63`（md5 一致，444B 已落到 `files/id_ed25519`，app uid 可读） |
| F14a（key 文件专属文案） | **未验到** | — | — | 未能执行（时间耗尽于 F14b 表单定位调试） |
| **F14b+F12a（publickey vs 回落）** | **INCONCLUSIVE（未闭环）** | `E2ECHK5558_cont.ps1` → `SshdAcceptedPub` | `log_cont.txt`, `ui_E2ECHK5558_final.xml` | **sshd 30 分钟内 0 条 auth 事件**（`Accepted publickey`=0、`keyboard-interactive`=0、`failure`=0）→ **连接根本没发起**。原因见下 |
| F13b（Password→Connected） | **未验到** | — | — | 未能执行 |
| F12b（Cancel 不重弹/二次重弹） | **未验到** | — | — | 未能执行 |

### F14b 为什么是 INCONCLUSIVE（关键，非 FAIL）
F14b 的判定标准是「sshd 日志出现 `Accepted publickey`」。本轮 **一条 auth 事件都没有**，
说明 app **从未向 WSL sshd 发起连接**，所以既不能判 PASS 也不能判 FAIL——是
**连接未发生**，不是「回落 keyboard-interactive」。

根因（表单填键路径失败）：选中 `Private key` 后，本机表单下方出现的是
**`Passphrase (optional)`** 标签，其下的 EditText 是 **`pw=true` 掩码字段**
（实测 `[156,1956][1188,2100] pw=true`，我填完显示为 34 个 `•`）。也就是说：
- 我脚本里定位到的那个"auth 区 EditText"其实是**口令（Passphrase）字段**，不是**私钥路径字段**；
- 真正的**私钥路径明文输入框**（应位于 `Private key` 标签下方）在本机滚动/定位中
  **始终没被找到**（`reveal pass 0-3: fields below 'Private key' = 0`）；
- 结果：私钥路径留空 → 点 `Add server` 后 app 因缺 key path **不发起连接**，
  表单仍停在 `Passphrase (optional)` 页（`ui_E2ECHK5558_final.xml` 可见该标签）。

> 这是继 1.1/1.2/1.3 之后**第 4 个本机特有坑**：`Private key` 模式下表单同时有
> 「私钥路径(明文)」+「Passphrase(掩码)」两个字段，而 5554 的 `ScrollToPw`
> （挑 `pw=true` 的那个）恰好会把**口令字段误当成 keypath**。本轮时间不够修这条，
> 留作下一轮的必修项。

### journalctl 关键行原文
本轮 F14b 判定窗口（`--since '-30min'`）的 grep 计数**全部为 0**，无任何
`Accepted publickey` / `Accepted keyboard-interactive` / `authentication failure` 行——
即连接未发生，故无原文可贴。（WSL 中文输出在 PS 管道里有编码乱码，故以 `wc -l`
计数形式取证。）

### 未闭环 / 下一轮必修（本机 1344 宽）
1. **F14b keypath 字段定位**（最高优先，直接卡住核心验收）：`Private key` 模式下需
   区分「私钥路径明文框」与「Passphrase 掩码框」；`Fields()` 需按标签语义而非
   `pw=true` 来选 keypath。定位到后再跑 F14b 才能拿到 publickey 判定。
2. **F14a / F13b / F12b** 均未跑，需在 F14b 打通后依次补齐。
3. **F12a 弹卡坐标**：本轮因连接未发起，未出现 passphrase 弹卡，故
   `Rehearse-Card` 学不到 FieldX/FieldY/ContX/ContY（仍是 TODO）。

---

## 4.1 冒烟段实测表单 bounds 表（1344 宽，供下一轮直接复用）

初始（表单置顶，`ui_E2ECHK5558_nav_tmp.xml`）：

| 节点 | bounds | 高度 | 备注 |
|---|---|---|---|
| 卡片 body ScrollView | `[48,1643][1296,2082]` | w=1248 h=439 | `FormSwipe` 动态取 x=1276 |
| Settings 搜索框 EditText | `[108,749][1236,893]` | 144 | 位于卡片顶边之上，`Fields()` 自然排除 |
| `Host or SSH command` 标签 | `[120,1844][538,1891]` | 47 | |
| host EditText | `[156,1909][1188,2053]` | 144 | `Fields()[0]` |
| `Server name (optional)` 标签 | `[120,2071][541,2082]` | 11 | |
| **name EditText** | `[156,2136][935,2154]` | **18（裁剪）** | `Fields()[1]` |
| `Cancel` / `Add server` | `[742,2143][869,2190]` / `[977,2143][1182,2190]` | 47 | 不与字段 x 重叠 |

滚到 auth 区（`Private key` 选中后）：

| 节点 | bounds | 备注 |
|---|---|---|
| `Authentication` 标签 | `[120,1891][387,1938]` | |
| `Private key` 标签 | `[156,2005][361,2052]` | 选中的认证方式 |
| **`Passphrase (optional)` 标签** | `[120,1815][522,1862]` | 填 keypath 的坑就在这 |
| Passphrase EditText | `[156,1956][1188,2100]` `pw=true` | 掩码，填成了 34 个 `•` |

设置 tab 条（`TabRow` 实测）：`y≈960`，`x=347..1254`，
`Servers` tab 在 `@419,960`（点击命中）。
