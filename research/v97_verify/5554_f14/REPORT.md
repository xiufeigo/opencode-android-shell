# F14 真机终验报告 — emulator-5554 (SSH 域 F14)

**执行人**：真机终验测试员（SSH 域 F14）  **日期**：2026-10-02
**设备**：`emulator-5554` / **AVD**：`Pixel_9a` / **屏幕**：1080x2424 @ 420dpi
**仓库**：`C:\sensorsdata\main\program\opencode-android-shell`

---

## 0. 总体结论（先给答案）

> ## 判定汇总
>
> | 用例 | 判定 | 一句话 |
> |---|---|---|
> | **F14b** | ✅ **PASS** | 私钥首连走 **publickey**，**未回落** keyboard-interactive |
> | **F12a** | ✅ **PASS** | passphrase 留空确实弹卡，输 `keypass123` 后 **Connected** |
> | **F14a** | ✅ **PASS** | 读不到的 keypath → 逐字命中 `Can't read the private key file...` |
> | **F13b** | ⚠️ **协议 PASS / UI FAIL** | sshd `Accepted keyboard-interactive/pam` 通道完好，但 UI 误报失败（缺陷 D2） |
> | **F12b** | ❌ **FAIL** | Cancel 后 **7/7 全部重弹**，且退化为口令卡、`Reconnect` 消失（缺陷 D1） |
>
> **本轮唯一重点（表单填对）在 20 分钟时间盒内完成**：4 个字段 **全部 try 1 命中，0 重试**，
> 且每个字段都有 bounds + 哨兵读回证据。

**前三次全挂在"填不对表单"，本轮 4 个字段全部一次填对（哨兵自证），并首次打通端到端 publickey 认证。**

关键证据（原文，见 §6）：
```
Oct 02 19:32:48 HomeMINISFORUM sshd-session[17242]: Accepted publickey for sshtest from 127.0.0.1 port 44642 ssh2: ED25519 SHA256:sYFitiUL7ZsVSAtodSjj4ob+l4DqiA05VZA9F7w4CR8
```
该行时间戳与脚本 `TAPPED AT 19:32:48`（点 Continue）**同秒**，面板同时显示 `Connected`。

---

## 1. 环境读数（写盘前复核）

| 项 | 读数 | 判定 |
|---|---|---|
| `adb devices` | `emulator-5554 device` | OK |
| `emu avd name` | **Pixel_9a** | OK（符合要求） |
| `wm size` / `wm density` | `Physical size: 1080x2424` / `Physical density: 420` | OK |
| `versionName` / `versionCode` | `1.4.0` / `26` | OK |
| `lastUpdateTime` | **`2026-10-02 11:02:36`** | OK（19:01 闸门构建） |
| `reverse --list` | `host-15 tcp:4095 tcp:4095` / `host-15 tcp:2222 tcp:22` | OK（双隧道在位） |
| `GET /api/info` | `HTTP 200` → `{"version":"2.0.18","pid":260,...}` | OK |
| WSL `sshd` | `active` | OK |
| 测试私钥 md5 | repo `0120a8270f7f0b0c71d31f4933384c63` = device `0120a8270f7f0b0c71d31f4933384c63`，444 字节 | OK |
| 密钥铺入 app 私有目录 | `files/id_ed25519` `-rw-rw-rw- 1 u0_a230 u0_a230 444` | OK |
| 负控（app 读 /sdcard） | `run-as ... cat /sdcard/v95/id_ed25519` → **Permission denied** | OK（F14a 前提成立） |

---

## 2. ★ 根因：前三次为什么全挂（表单字段识别算法错了）

**任务书假设**：「空 EditText 会把 hint 当 `text` 暴露，据此用 `text=` 含 `Host or SSH command` 判断表单已打开」。

**实测 Pixel_9a / 420dpi 上这个假设不成立**。首次 dump 的真实结构是：

```
'Add SSH server'            [106,1406][401,1453]   TextView
'Host or SSH command'       [106,1518][471,1559]   TextView   ← 独立的 hint 文本节点
''  [138,1576][943,1702]  pw=false  EditText      ← 真正的输入框，text 为空
'Server name (optional)'    [106,1718][473,1725]   TextView
''  [138,1776][723,1788]  pw=false  EditText
'Cancel'                    [554,1779][665,1820]
'Add server'                [760,1779][938,1820]
```

**hint 是渲染在 EditText 上方的独立 TextView，EditText 自身 `text=""`。**
所以按 `text` 找 `Host or SSH command` 永远找不到 EditText —— 这正是第 1 次 `'name' not visible` 死循环、第 3 次"定位不到私钥路径明文框"的直接原因。**与坐标、dpi、键盘都无关，是识别算法本身错了。**

### 本轮采用的定位算法（已验证有效）

```powershell
# hint TextView 作锚点，取其下方第一个 EditText
FieldBelow($xml, $Label) =
    $lab = Texts($xml) | Where Text -eq $Label
    EditTexts($xml) | Where { $_.Y1 -ge $lab.Y2 } | Sort-Object Y1 | Select-Object -First 1
```
- 锚点用 **label 文本精确匹配**（`Host or SSH command` / `Server name (optional)` / `Key file path` / `Passphrase (optional)`）；
- 取 **锚点下方** 第一个 EditText 的 **bounds 中心** 作为 tap 坐标（每次都从新 dump 重算，从不复用旧坐标）；
- 认证方式切换后**必须重新 dump**（字段集会变）。

### 每字段强制自证（哨兵往返）
1. dump → 算 bounds → tap 中心；
2. dump → 确认该节点 `focused=true`；
3. `input text` 哨兵（`HX1` / `NM1` / `KP1`）；
4. dump → **读回该节点 text 是否含哨兵**；
5. 通过后 `DEL(67)`×N 清哨兵 → 补齐真实值 → dump 读回终值。

**本轮 4 个字段全部 try 1 命中，0 次重试。**

---

## 3. 表单 bounds 表 + 哨兵读回证据

> 注：每次 tap 唤起键盘后表单会整体上移，故"点击时 bounds"与"落字后 bounds"不同，**两者都记录**。

| 字段 | 锚点 label | 点击时 bounds | 落字后 bounds（读回） | 哨兵 | 读回证据 | try |
|---|---|---|---|---|---|---|
| host | `Host or SSH command` | `[138,1576][943,1702]` c=(540,1639) | `[138,1186][943,1312]` | `HX1` | `focused=true` → `text='HX1'` → 终值 `text='ssh -p 2222 sshtest@127.0.0.1'` | 1/3 |
| name | `Server name (optional)` | `[138,1246][943,1372]` c=(540,1309) | `[138,1246][943,1372]` | `NM1` | `focused=true` → `text='NM1'` → 终值 `text='E2ECHK5554f14-b'` | 1/3 |
| auth | `Authentication` 之选择器 | `Password` `[138,1443][296,1484]` → 下拉 `Private key` `[138,1540][317,1581]` c=(228,1560) | 切换后 label 变为 `Key file path` / `Passphrase (optional)` | — | 重新 dump 后字段集已变 | 1/3 |
| keypath | `Key file path` | `[138,1578][943,1704]` c=(540,1641) | `[138,1188][943,1314]` | `KP1` | `focused=true` → `text='KP1'` → 终值 `text='/data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519'` | 1/3 |
| passphrase | `Passphrase (optional)` | **故意留空** | — | — | `pw=true` 且 `text=''`（符合设计） | — |

**提交前完整表单读回**（`F14_vh_b.xml`）：
```
[0] bounds=[96,277][985,403]  focused=false pw=false text=''
[1] bounds=[138,962][943,1088] focused=false pw=false text='ssh -p 2222 sshtest@127.0.0.1'
[2] bounds=[138,1162][943,1288] focused=false pw=false text='E2ECHK5554f14-b'
'Authentication' @(222,1012) | 'Private key' @(228,1112) | 'Key file path' @(206,1212)
'/data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519' @(540,1276)
```

### 滚动与键盘纪律
- 滚动：快滑 120px/80ms，起点 y 取**表单 ScrollView 内、且不与任何 EditText 重叠**的空带（`F14_pre_scroll` 实测 ScrollView = `[43,864][1038,1240]`，空带 y=872）。滚动后**重新 dump** 再取 bounds。
- 收键盘：`keyevent 61`(TAB)。**未使用** ESC(111)、**未使用** BACK(4)；清字符用 DEL(67)。
- 截图：`screencap -p` + `adb pull`，**未使用 exec-out**。

---

## 4. 卡片坐标（Rehearse 学到，实测复用）

**宿主身份卡**（提交后首张）：
| 控件 | bounds | 中心 |
|---|---|---|
| `Verify host identity` | `[106,1412][454,1459]` | (280,1436) |
| `MD5:9b:19:f4:e8:83:e3:ca:ad:1d:c0:62:76:76:48:9b:24` | `[106,1716][975,1758]` | (540,1737) |
| `Cancel` | `[440,1812][551,1853]` | **(496,1832)** |
| `Trust and connect` | `[646,1812][938,1853]` | **(792,1832)** |

**Passphrase 卡**（点 Trust 后出现）：
| 控件 | bounds | 中心 |
|---|---|---|
| `SSH authentication` | `[106,1412][458,1459]` | (282,1436) |
| `Passphrase for /data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519` | `[106,1524][975,1598]` | (540,1561) |
| 输入框 `pw=true` | `[138,1615][943,1741]` | **(540,1678)** |
| `Cancel` | `[588,1779][699,1820]` | (644,1800) |
| `Continue` | `[794,1779][938,1820]` | **(866,1800)** |

> ★ **卡片副标题回显了 keypath 全文**，这是表单确实填对的独立旁证（不依赖我自己的读回逻辑）。
> 注：点 Continue 时键盘已收起，实际点击位上移为 `(866,1348)` —— 脚本每次都从**新 dump 按 label 重算**，未硬编码。

---

## 5. 逐条判定

### F14b + F12a（核心）— ✅ **PASS**
| 步骤 | 观察 |
|---|---|
| 建条目（passphrase 留空） | host=`ssh -p 2222 sshtest@127.0.0.1`，auth=Private key，keypath=app 私有目录 |
| 提交 | 弹 `Verify host identity` 卡 → 点 `Trust and connect` |
| 再一步 | 弹 `SSH authentication` / `Passphrase for ...id_ed25519` 卡（**F12a：留空确实触发弹卡**） |
| 输 `keypass123` → `Continue` | `TAPPED AT 19:32:48` |
| 面板 | **`Connected`**，出现 `Reconnect` / `Disconnect`，隧道 `127.0.0.1:46041` |
| sshd | **`Accepted publickey for sshtest`** |

**判定依据**：出现 `Accepted publickey` → **F14b PASS**；
25 分钟窗口内**未出现** `Accepted keyboard-interactive` → **无回落**，公钥路径未被破坏。
F12a：留空 passphrase 触发弹卡、输对后 Connected → **PASS**。

### F14a — ✅ **PASS**
另建条目 `E2ECHK5554f14-a`，auth=Private key，keypath 填 **app 读不到的** `/sdcard/Download/nope_id_ed25519`
（负控已在 §1 用 `run-as ... cat` 证明 Permission denied），提交后面板原样出现：

```
'Passphrase (optional)'  [106,903][455,944]
'Can't read the private key file. Check the path and read permission.'  [106,1103][975,1173]
'Connection failed'      [106,1205][340,1240]
'Cancel'  @(652,1314)   'Connect' @(870,1314)
```
断言串**逐字命中**，无截断、无改写。截图 `F14a_08_result.png`，dump `F14a_08_result.xml`。
（同时 sshd 侧 `19:36:16 authentication failure ... user=sshtest`，属该次失败连接的正常伴随记录。）

### F12b — ❌ **FAIL（发现真缺陷，两处）**
在已 Connected 的 `E2ECHK5554f14-b` 上测 cancel 语义：

| 步骤 | 期望 | 实测 | 判定 |
|---|---|---|---|
| ① 一次 Reconnect | 弹 passphrase 卡 | 弹卡 | ✅ |
| ② 点 `Cancel` 后 7 次采样（每 5s） | **0 次重弹**，条目保留 | **7/7 全部重弹** | ❌ **FAIL** |
| ③ 二次 Reconnect | 必重弹 | 面板已无 `Reconnect` 按钮，行态退化为 `Server not reachable` | ❌ 未闭环 |

7 次采样原始记录：
```
sample 1 (+5s)  card=True  hostcard=False
sample 2 (+10s) card=True  hostcard=False
sample 3 (+15s) card=True  hostcard=False
sample 4 (+20s) card=True  hostcard=False
sample 5 (+25s) card=True  hostcard=False
sample 6 (+30s) card=True  hostcard=False
sample 7 (+35s) card=True  hostcard=False
F12b RESULT: re-pop after cancel = 7 / 7  (expect 0)
```

**★ 附带的重要发现（与 F14b 相关但不推翻 F14b）**：
重弹回来的卡**不是**原来的 passphrase 卡，而是变成了 **`SSH authentication` / `Password: `** 的
keyboard-interactive 口令卡（`Cancel @(644,1348)` / `Continue @(866,1348)`），同时条目行态变为
`Server not reachable`、`Reconnect` 按钮消失。

但 **sshd 侧在同一时间窗内没有 `Accepted keyboard-interactive`，也没有新的 `authentication failure`**——
即 app 把 UI 降级成了口令卡，**却根本没有向 sshd 发起一次新的认证**。因此：

- **F14b 判定不变 = PASS**：任务书指定的 F14b 场景是"新建条目 → 提交 → 弹卡 → 输 keypass123"，
  该路径实测为 `Accepted publickey`，无回落。
- **但要单独记一个缺陷**：**已保存条目的重连（Reconnect）路径**未复用私钥，
  直接退化为 keyboard-interactive 口令卡；在 `Server not reachable` 状态下该卡还会持续重弹、
  且不给出可恢复的 `Reconnect` 入口。建议单独立 issue 跟进。

### 5.1 F13b — ⚠️ **协议层 PASS / UI 层 FAIL（且任务书前提在 1.4.0 不成立）**

**前提修正（重要）**：任务书要求"Password 方式 + **错密码建条目**"。实测 **1.4.0 的 Password 模式
根本没有存储密码字段**——Add 表单只有 `host / name / Authentication=Password / Cancel / Add server`，
`E2ECHK5554f14-c` 的表单 dump 里不存在任何 `pw=true` 的 EditText（脚本连试 3 轮均报
`no masked field yet`）。**凭据一律在连接时弹卡询问**，"建条目时存错密码"这一步骤无法表达。
故改为：建 Password 模式条目 → 提交 → 在弹出的口令卡上先输错、再输对。

| 步骤 | 实测 |
|---|---|
| 弹卡 | `Password` / `Enter the password for this connection.`，输入框 `[138,1548][943,1674]` `pw=true` |
| 输 `wrongpw999` 提交 | 卡仍在，**sshd 侧无任何新记录**（口令未真正送达） |
| 改为先 `MOVE_END`+`DEL×40` 清空（首轮脚本漏清，字段残留 18 位 `wrongpw999opencode`） | 清空后 `text=''`，再输 `opencode` → 掩码长度 8 ✅ |
| `19:40:05` 点 `Add server` | — |
| **sshd** | **`19:40:08 Accepted keyboard-interactive/pam for sshtest from 127.0.0.1 port 45180 ssh2`** |
| **面板 UI** | ❌ `Authentication required` + `Could not establish the SSH connection. Check your network and SSH configuration.`（**不是** `Connected`） |

**判定**：
- **"键盘交互路径未被破坏" = 成立**。sshd 明确记录 `Accepted keyboard-interactive/pam`，
  说明 keyboard-interactive 通道本身工作正常，**F14b 的公钥改动没有破坏它**。
- **但 UI 层 FAIL**：服务端**已经接受**了凭据，App 却把行态标成 `Authentication required` +
  `Could not establish the SSH connection`，**这是一个假阴性**——成功被报成失败。
  这是独立于 F14b 的第二个缺陷，建议单独立 issue。

---

## 6. journalctl 原文（全窗口，30 分钟）

```
Oct 02 19:32:48 HomeMINISFORUM sshd-session[17242]: Accepted publickey for sshtest from 127.0.0.1 port 44642 ssh2: ED25519 SHA256:sYFitiUL7ZsVSAtodSjj4ob+l4DqiA05VZA9F7w4CR8
Oct 02 19:36:16 HomeMINISFORUM sshd-session[17406]: pam_unix(sshd:auth): authentication failure; logname= uid=0 euid=0 tty=ssh ruser= rhost=127.0.0.1  user=sshtest
Oct 02 19:40:08 HomeMINISFORUM sshd-session[17689]: Accepted keyboard-interactive/pam for sshtest from 127.0.0.1 port 45180 ssh2
```

逐行对应（存于 `journalctl_FINAL.txt`）：

| 时间 | 事件 | 归属用例 | 含义 |
|---|---|---|---|
| `19:32:48` | **`Accepted publickey`** | **F14b+F12a** | ★ **F14b 核心证据**。与脚本 `TAPPED AT 19:32:48` 同秒。**全窗口唯一一条**认证成功记录属于公钥路径 |
| `19:36:16` | `authentication failure` | F14a | 该次用的是**读不到的** keypath，属预期内的失败 |
| `19:40:08` | `Accepted keyboard-interactive/pam` | F13b | 口令通道**确实可用**（F14b 未破坏它）；但 App UI 当时报的是失败（见 §5.1） |

**F14b 判定复核**：任务书规定"若出现 keyboard-interactive → F14b = FAIL"。
本窗口内**确实出现过**一条 keyboard-interactive，但它来自 **F13b 的 Password 模式条目**，
而非 F14b 的私钥条目。F14b 所测的那次连接（`19:32:48`）**只有 publickey**，
且 F14b 用例执行期间（19:31–19:33）**无任何 keyboard-interactive 记录**。
→ **F14b = PASS，走 publickey，无回落。**

---

## 7. 清理

| 项 | 结果 |
|---|---|
| 删除条目 `E2ECHK5554f14-a` | ✅ 已删 |
| 删除条目 `E2ECHK5554f14-b` | ✅ 已删（需先 Disconnect 才能删，见缺陷 D3） |
| 删除条目 `E2ECHK5554f14-c` | ✅ 已删 |
| 最终面板 | 只剩原始条目 `10.0.2.2:4095` / `Server healthy` / `Default` —— **无 `E2ECHK5554f14*` 残留** |
| `ResetFaillock` | ✅ `files/ssh/` 现为空目录（`total 16`，仅 `.` 与 `..`），`known_hosts` 已清 |
| `/sdcard` 残留 | ✅ 本轮全部 `F14_*/F14a_*/F12b_*/F13*/CL*/f1.*/h.xml/m.xml/s.xml/s2.xml/sv.xml/_e2echk5554.png` 已清空 |

> 注：首轮清理用的小写 glob `/sdcard/f14*` 匹配不到大写 `F14_*`，且 `CL2?` 误匹配了
> `v34_chk_04_picker_cleared.png` 等**其它轮次**的既有文件（`clean`/`closed`/`cl1`）——
> 那些**不是本轮产物**，未删除。/sdcard 上仍有 1010 个历史文件，与本轮无关。

---

## 8. 本轮发现的缺陷（建议各自立 issue）

| # | 缺陷 | 严重度 | 证据 |
|---|---|---|---|
| **D1** | **已保存条目的 Reconnect 不复用私钥**，直接退化为 `Password:` keyboard-interactive 卡；且在 `Server not reachable` 下该卡**无限重弹 7/7 次**，`Reconnect` 按钮消失，无法自行恢复 | **高** | §5 F12b；`journalctl` 同窗无 keyboard-interactive / 无 auth failure → UI 降级但**根本没发起认证** |
| **D2** | **口令认证成功被报成失败**：sshd 已 `Accepted keyboard-interactive/pam`，面板却显示 `Authentication required` + `Could not establish the SSH connection`；稍后条目又自行变成 `Connected` → **UI 状态严重滞后/假阴性** | **高** | §5.1；`19:40:08` sshd 成功 vs 面板报错；`CL6_01` 中 `-c` 已显示 `Connected` |
| **D3** | **删除确认弹窗会卡死**：对处于 `Authentication required` 的条目点 `Delete` → 弹窗 `Delete SSH server?` 出现后，按钮点击**无响应、弹窗不消失**；必须先 `Disconnect` 把行态改成 `Disconnected`，弹窗按钮才变为可点击并生效 | 中 | §7；`CL4_02`–`CL6_01` 完整过程 |
| **D4** | Password 模式**无"记住密码"字段**，口令每次连接都要重输 | 低（可能是有意设计） | §5.1，Add 表单无 `pw=true` 节点 |

**D1/D2 与 F14b 的关系**：两者都发生在**重连/口令**路径，**不影响**本轮 F14b 判定
（新建私钥条目的首连路径确实是干净的 publickey）。但它们是真实可复现的用户可见故障。

---

## 9. 交付物清单

| 文件 | 内容 |
|---|---|
| `REPORT.md` | 本报告 |
| `f14lib.ps1` | 可复用 UI 库（dump/解析/哨兵填表/滚动/收键盘） |
| `step1_host_name.ps1` … `step14_cleanup5.ps1` | 各步可复跑脚本 |
| `journalctl_F14b.txt` / `journalctl_F12b.txt` / `journalctl_FINAL.txt` | sshd 原文 |
| `*.xml` / `*.png` | 各阶段 dump 与截图证据（约 200 个文件） |

## 10. 未闭环

1. **D1 的恢复路径未验证**：条目卡在 `Server not reachable` + 口令卡重弹后，
   是否只能靠重启 App / `ResetFaillock` 脱困，本轮未继续深挖（时间盒已到）。
2. **D2 的稳定复现率未量化**：只跑了一轮口令用例，不足以判断是竞态还是必现。
3. F14b 只在 `emulator-5554` 单机型验证，未做跨机型/跨 dpi 回归。
