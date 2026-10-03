# v98 真机复验报告 — SSH 域 D1/D2/D3/F12b 修复 + F13b 复验

**执行人**：真机复验测试员（SSH 域）  **日期**：2026-10-02  20:27–20:45
**设备**：`emulator-5554` / **AVD**：`Pixel_9a` / **屏幕**：1080x2424 @ 420dpi
**仓库**：`C:\sensorsdata\main\program\opencode-android-shell`
**被测构建**：Lead 20:24 闸门构建（`versionName` 1.4.0，`lastUpdateTime=2026-10-02 12:26:08`）
**输入**：`research/v97_fix/SSH_RECONNECT_FIX.md`（修复报告）、`research/v97_verify/5554_f14/REPORT.md`（原始缺陷证据）

---

## 0. 判定汇总（先给答案）

| 用例 | 判定 | 一句话 |
|---|---|---|
| **F14b 回归** | ✅ **PASS** | 私钥首连走 `Accepted publickey`（20:31:17），全窗无 keyboard-interactive 回落 |
| **D1** | ✅ **PASS** | 已存私钥条目 Reconnect → 弹**passphrase 卡**（不再是 `Password:` 卡），且 sshd 侧确有 `Accepted publickey`（20:33:58） |
| **D2** | ✅ **PASS** | 口令认证成功不再被报成失败：提交后 10/10 采样全 `Connected`，`Authentication required` / `Could not establish` 出现 **0** 次 |
| **D3** | ❌ **FAIL（比原报告更严重）** | ⋮ → `Delete` **根本不弹确认弹窗、也不删除**；5 次复现、3 种点击策略、2 种行态，a11y 树与截屏一致 |
| **F12b** | ✅ **PASS（附一处残留）** | Cancel 后 **0/7 重弹**（原 7/7），条目保留，全程无 `Password:` 卡；二次 Reconnect 必重弹。残留：Cancel 后行态卡在 `Connecting over SSH…`，只剩 `Disconnect` |
| **F13b** | ✅ **PASS（前提已变）** | 正确口令 → `Accepted keyboard-interactive/pam`（20:37:05）+ 面板 `Connected`；但**本构建已有"记住密码"字段**，口令卡路径未被触发 |

> **一句话总览**：D1 / D2 / F12b / F13b 四项修复在真机上**全部复验通过**，且 `Auth cancel for methods 'publickey,password,keyboard-interactive'`
> 证明 v97 的"静音本次拨号"语义真的生效。**唯一的红项是 D3，且比 v97 报告描述的更糟**：不是"弹窗按钮点了没反应"，
> 而是**确认弹窗压根不出现、条目压根删不掉**。

---

## 1. 环境读数（写盘前复核）

| 项 | 命令 | 读数 | 判定 |
|---|---|---|---|
| 设备身份 | `adb -s emulator-5554 emu avd name` | **`Pixel_9a`** | ✅ 符合要求 |
| 设备在线 | `adb devices` | `emulator-5554 device`（另有 5556/5558/5560/5562，**全程未碰**） | ✅ |
| 屏幕 | `wm size` / `wm density` | `1080x2424` / `420` | ✅ |
| 应用版本 | `dumpsys package` | `versionName=1.4.0` | ✅ |
| 构建时间 | `dumpsys package` | **`lastUpdateTime=2026-10-02 12:26:08`** | ✅ 与 Lead 20:24 闸门构建一致 |
| reverse 隧道 | `adb reverse --list` | `host-15 tcp:4095 tcp:4095`、`host-15 tcp:2222 tcp:22` | ✅ 双隧道在位 |
| 后端健康 | `GET /api/info` | `HTTP 200` → `{"version":"2.0.18","pid":260,...}` | ✅ |
| 测试私钥 md5 | `E2ECHK5554_provision_key.ps1` | repo `0120a8270f7f0b0c71d31f4933384c63` = device `0120a8270f7f0b0c71d31f4933384c63`，444 B | ✅ |
| 密钥落位 | `run-as … ls -l files/` | `files/id_ed25519` `-rw-rw-rw- u0_a230 444` | ✅ |
| 负控 | `run-as … cat /sdcard/v95/id_ed25519` | **`Permission denied`** | ✅ F14a 前提仍成立 |
| 起始面板 | `v98_05_servers_panel.png` | 仅 `10.0.2.2:4095` / `Server healthy` / `Default` | ✅ **无 v98 残留** |

**关于凭据**：按 Lead 已证实的结论，本轮**没有**再怀疑凭据。所有 sshd 侧记录均为 `user=sshtest`，
口令/公钥两条通道都拿到了 `Accepted`，凭据与端口链路无问题。

---

## 2. 表单 bounds 表与哨兵证据

> 定位算法沿用 F14 的 `FieldBelow`：hint 是渲染在 EditText **上方**的独立 TextView，EditText 自身 `text=""`，
> 故以 label 文本为锚点，取其下方第一个 EditText 的 bounds 中心。**每次都从新 dump 重算，从不复用旧坐标。**

| 字段 | 锚点 label | 点击时 bounds | 落字后 bounds | 哨兵 | 读回证据 | try |
|---|---|---|---|---|---|---|
| host (key 条目) | `Host or SSH command` | `[138,1576][943,1702]` c=(540,1639) | `[138,1186][943,1312]` | `HX1` | `focused=true` → `text='HX1'` → 终值 `text='ssh -p 2222 sshtest@127.0.0.1'` | 1 |
| name | `Server name (optional)` | `[138,1246][943,1372]` c=(540,1309) | 同上 | `NM1` | `focused=true` → `text='NM1'` → 终值 `text='E2ECHK5554v98-k'` | 1 |
| auth | `Authentication` 下的 `Password` 选择器 | `Password` c=(217,1678) → 下拉 `Private key` c=(228,1560) | 切换后 label 变为 `Key file path` / `Passphrase (optional)` | — | 重新 dump 确认字段集已变 | 1 |
| keypath | `Key file path` | `[138,1578][943,1704]` c=(540,1641) | `[138,1188][943,1314]` | `KP1` | `focused=true` → `text='KP1'` → 终值 `text='/data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519'` | 1 |
| passphrase | `Passphrase (optional)` | **故意留空** | — | — | `pw=true` 且 `text=''`（F12a 设计） | — |
| host (pwd 条目) | `Host or SSH command` | `[138,1582][943,1708]` c=(540,1645) | `[138,1186][943,1312]` | `HX1` | `focused=true` → `text='HX1'` → 终值逐字命中 | 1 |
| name (pwd 条目) | `Server name (optional)` | `[138,1246][943,1372]` c=(540,1309) | 同上 | `NM1` | `focused=true` → `text='NM1'` → 终值 `text='E2ECHK5554v98-p'` | 1 |
| 口令（**表单内**） | `Password` | `[138,1548][943,1674]` c=(540,1611) | `[138,1063][943,1189]` | —（掩码） | `pw=true` → `len=8` `'????????'` = `opencode` 长度 | 1 |

**提交前完整表单读回**（`v98_p_04_submitpre.xml`）：

```
[0] bounds=[96,560][985,686]   focused=false pw=false text=''
[1] bounds=[180,1006][901,1132] focused=false pw=false text=''
[2] bounds=[138,1286][943,1304] focused=false pw=false text='E2ECHK5554v98-p'
[3] bounds=[138,1615][943,1741] focused=false pw=true  text=''
'Authentication' | 'Password' | 'Password'(label) | 'Enter the password for this connection.'
'Cancel' @(610,1800)   'Add server' @(849,1800)
```

> ★ **与 F14 的重要差异（见 §6.1）**：本构建的 Password 模式在 **Add 表单里就有 `pw=true` 的口令字段**，
> F14 报告的 D4「1.4.0 的 Password 模式根本没有存储密码字段」**在本构建上已不成立**。

**卡片坐标**（每步从新 dump 按 label 重算，未硬编码）：

| 卡片 | 控件 | bounds | 中心 |
|---|---|---|---|
| 宿主身份 | `Verify host identity` | `[106,1412][454,1459]` | (280,1436) |
| 宿主身份 | `MD5:9b:19:f4:e8:83:e3:ca:ad:1d:c0:62:76:76:48:9b:24` | `[106,1716][975,1758]` | (540,1737) |
| 宿主身份 | `Cancel` / `Trust and connect` | `[440,1812][551,1853]` / `[646,1812][938,1853]` | (496,1832) / **(792,1832)** |
| passphrase | `SSH authentication` | `[106,1412][458,1459]` | (282,1436) |
| passphrase | `Passphrase for /data/…/id_ed25519`（**回显 keypath 全文**） | `[106,1524][975,1598]` | (540,1561) |
| passphrase | 输入框 `pw=true` | `[138,1615][943,1741]` | **(540,1678)** |
| passphrase | `Cancel` / `Continue` | `[588,1779][699,1820]` / `[794,1779][938,1820]` | (644,1800) / **(866,1800)** |

**纪律遵守**：未按 BACK(4)；未用 ESC(111)；收键盘一律 `keyevent 61`(TAB)；清字符 `DEL(67)`；
截图只用 `screencap -p` + `adb pull`（**未用 exec-out**）；空格用 `keyevent 62`；全程只操作 emulator-5554，**未碰 127.0.0.1:5555**。

---

## 3. 逐条判定

### 3.1 F14b 回归 — ✅ PASS

**流程**：新建私钥条目 `E2ECHK5554v98-k`（passphrase 留空）→ `Add server` → `Verify host identity`
（`[127.0.0.1]:2222` / `ssh-ed25519` / MD5 `9b:19:…:9b:24`）→ `Trust and connect` → `SSH authentication` /
`Passphrase for /data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519` → 输 `keypass123`（掩码 `len=10`）→ `Continue`。

| 观察 | 值 |
|---|---|
| 点 `Continue` 时刻 | **`TAPPED AT 20:31:16`** |
| 面板 | **`Connected`**，出现 `Reconnect` / `Disconnect`，隧道 `127.0.0.1:44521` |
| sshd | **`20:31:17 Accepted publickey for sshtest from 127.0.0.1 port 35860 ssh2: ED25519 SHA256:sYFitiUL7ZsVSAtodSjj4ob+l4DqiA05VZA9F7w4CR8`** |

**判定依据**：出现 `Accepted publickey` → **F14b PASS**；`20:31–20:33` 窗口内**无** `Accepted keyboard-interactive`
→ **无回落**，公钥路径未被破坏。F12a（留空 passphrase 确实弹卡）同时成立。

证据：`v98_f14b_01_hostcard.*` / `v98_f14b_02_passcard.*` / `v98_f14b_03_result.*`、`journalctl_F14b.txt`

---

### 3.2 D1 — ✅ PASS（两个断言都成立）

在**已保存**的私钥条目 `E2ECHK5554v98-k` 上点 `Reconnect`（20:31:45）。

**断言 A：不再降级成口令卡**

```
'SSH authentication'                                          [106,1412][458,1459]
'Passphrase for /data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519'
                                                                [106,1524][975,1598]
'Cancel' (644,1800)   'Continue' (866,1800)
```

- `ProbeCards`：`AuthCard=True  PassCard=True  PwCard=False  HostCard=False`
- **全程未出现 `Password:` / keyboard-interactive 口令卡** —— 这正是 D1 的直接指纹，**已消除**。

**断言 B：sshd 侧确实出现认证记录（不是"UI 变了但根本没发起"）**

输 `keypass123` → `Continue` @ **20:33:58** →

```
Oct 02 20:33:58 HomeMINISFORUM sshd-session[20775]: Accepted publickey for sshtest from 127.0.0.1 port 54612 ssh2: ED25519 SHA256:sYFitiUL7ZsVSAtodSjj4ob+l4DqiA05VZA9F7w4CR8
Oct 02 20:33:58 HomeMINISFORUM sshd-session[20775]: pam_unix(sshd:session): session opened for user sshtest(uid=1001) by sshtest(uid=0)
```

**面板 10 次采样（每 2s，20 秒）**：`connected=True` **10/10**，`authRequired=False` 10/10，`couldNotEstablish=False` 10/10。

→ **D1 = PASS**：认证真实发起（`Accepted publickey` + `session opened`），且未降级。

证据：`v98_d1_01_card.*`、`v98_d1_13_s1..s10.xml`、`journalctl_D1.txt`

---

### 3.3 D2 — ✅ PASS（10/10，无失败态、无滞后）

口令条目 `E2ECHK5554v98-p`（Password 方式，正确口令 `opencode`），`Add server` @ **20:37:02**。

**10 次采样（每 2s，+2s … +20s）逐条原文**：

```
s1  entry=True card=False connected=True  authReq=False couldNotEst=False connFailed=False
s2  entry=True card=False connected=True  authReq=False couldNotEst=False connFailed=False
s3  entry=True card=False connected=True  authReq=False couldNotEst=False connFailed=False
s4  entry=True card=False connected=True  authReq=False couldNotEst=False connFailed=False
s5  entry=True card=False connected=True  authReq=False couldNotEst=False connFailed=False
s6  entry=True card=False connected=True  authReq=False couldNotEst=False connFailed=False
s7  entry=True card=False connected=True  authReq=False couldNotEst=False connFailed=False
s8  entry=True card=False connected=True  authReq=False couldNotEst=False connFailed=False
s9  entry=True card=False connected=True  authReq=False couldNotEst=False connFailed=False
s10 entry=True card=False connected=True  authReq=False couldNotEst=False connFailed=False
```

- **`Authentication required` 出现 0 次**、`Could not establish the SSH connection.` 出现 **0 次**、
  `Connection failed` 出现 **0 次**。
- **不存在"先失败后自己变好"的滞后**：第一次采样（+2s）就已经是 `Connected`，此后再未回退。
- sshd 侧：`20:37:05 Accepted keyboard-interactive/pam for sshtest from 127.0.0.1 port 40438 ssh2`

→ **D2 = PASS**（v97 报告担心的竞态在本轮未复现；修复前的假阴性 `Authentication required` 已消失）

证据：`v98_p_11_s1..s10.xml`、`journalctl_D2.txt`

---

### 3.4 D3 — ❌ FAIL（**比原报告描述更严重**）

> v97 报告 §6.2 写的是「D3 的"弹窗按钮可点性"只修了一半」。**真机实测比这更糟：弹窗根本不出现。**

**测试路径**：⋮（`Server actions`）→ `Delete`（弹层内该项 bounds `[618,1743][717,1796]`，独立 window，dump 2634 B）。

| # | 行态 | 点击策略 | 确认弹窗 | 行是否删除 |
|---|---|---|---|---|
| 1 | `Connected` | `Delete` 文本中心 (668,1770) | **无** | **否** |
| 2 | `Connected` | `Delete` 文本中心 (668,1770)（重试） | **无** | **否** |
| 3 | `Connected` | `Delete` 文本中心 (666,1735) + 截图 | **无** | **否** |
| 4 | `Disconnected` | `Delete` 文本中心 (668,1770) + 截图 | **无** | **否** |
| 5 | `Disconnected` | 弹层卡片中心 (732,1730) | **无** | **否** |
| 6 | `Disconnected` | 文本中心 (666,1735) | **无** | **否** |
| 7 | `Disconnected` | 长按 (700,1732) `swipe x y x y 120` | **无** | **否** |

**三重独立取证**：
1. **a11y 树**：`v98_d3_52_after.xml` / `v98_d3_82_after.xml` 中**无任何** `Delete*` / `*server*` 确认文案节点，
   行节点 `E2ECHK5554v98-p` 仍在原位。
2. **截屏**：`v98_d3_51_dialog.png`、`v98_d3_70_dlg.png` —— 弹层关闭后画面**干净回到列表**，**无遮罩、无对话框**。
3. **弹层本身可达**：菜单 dump（2634 B，独立 window）稳定含 `Delete` 节点；`v98_d3_60_menu.png` 可见红色
   `Delete` 菜单项。**⇒ 问题不在"点不到 ⋮"或"点不到 Delete"，而在点中 Delete 之后没有任何后续行为。**

**源码侧旁证**：`ServersPanel.kt:480-483` 的 `ServerRow` 菜单项是
`onClick = { menu = false; onDelete() }`（立即删除，无确认）；而全仓 `grep` **不存在** `Delete SSH server` /
`deleteConfirm` 之类确认弹窗字符串。⇒ 与 F14 报告里"弹窗 `Delete SSH server?` 出现后按钮点不动"的观察**不一致**，
本构建上**连弹窗都没有**。

**未能覆盖的部分（如实记录）**：我**没有**造出 `Authentication required` 态，因而未复验 D3 原始场景。
原因有二，如实记录：
- D2 修好后，正确口令直接 `Connected`，自然走不到 `Authentication required`；
- 本构建 SSH 行的 ⋮ 菜单**只有 `Delete`、没有 `Edit`**，无法把已存条目改成错口令。
（造该态需另建一条错口令条目，本轮时间盒未覆盖。）

→ **D3 = FAIL**。判定基于 `Connected` 与 `Disconnected` 两种行态的 7 次复现；
`Authentication required` 态下的删除行为**未验到**。

**连带影响**：我建的 `E2ECHK5554v98-k` / `E2ECHK5554v98-p` **无法经 UI 删除**，构成设备上的残留（见 §7）。

证据：`v98_d3_50..82_*.xml`、`v98_d3_51_dialog.png`、`v98_d3_60_menu.png`、`v98_d3_70_dlg.png`

---

### 3.5 F12b — ✅ PASS（附一处残留）

`Cancel` 后的 **7 次采样（每 5s）** 原文：

```
sample 1 (+5s)  card=False authCard=False passCard=False pwCard=False entry=True connected=False notReachable=True hasReconnect=False
sample 2 (+10s) card=False authCard=False passCard=False pwCard=False entry=True connected=False notReachable=True hasReconnect=False
sample 3 (+15s) card=False authCard=False passCard=False pwCard=False entry=True connected=False notReachable=True hasReconnect=False
sample 4 (+20s) card=False authCard=False passCard=False pwCard=False entry=True connected=False notReachable=True hasReconnect=False
sample 5 (+25s) card=False authCard=False passCard=False pwCard=False entry=True connected=False notReachable=True hasReconnect=False
sample 6 (+30s) card=False authCard=False passCard=False pwCard=False entry=True connected=False notReachable=True hasReconnect=False
sample 7 (+35s) card=False authCard=False passCard=False pwCard=False entry=True connected=False notReachable=True hasReconnect=False
F12b RESULT: re-pop after cancel = 0 / 7 (expect 0)
```

| 断言 | 期望 | 实测 | 判定 |
|---|---|---|---|
| ① Cancel 后 7 点不重弹 | 0/7 | **0/7**（F14 为 7/7） | ✅ |
| ② 条目保留 | 保留 | `entry=True` 7/7 | ✅ |
| ③ 全程无 `Password:` 卡 | 无 | `pwCard=False` 7/7 | ✅ |
| ④ 二次 Reconnect 必重弹 | 重弹 | `Disconnect` → `Reconnect` → **passphrase 卡重弹**（`PassCard=True PwCard=False`） | ✅ |

**★ 残留（如实记录）**：Cancel 之后条目行态**卡在**
`'Connecting over SSH…'` + `'Connecting to SSH server'`，**只剩 `Disconnect`、`Reconnect` 按钮消失**。
因此第 ④ 步无法原地点 `Reconnect`，必须先 `Disconnect` 再 `Reconnect` 才拿到重弹。
这与 v97 报告里"行态被拖成 `Server not reachable`、`Reconnect` 消失"是**同一族残留**（按钮缺失的部分），
但**重弹 7/7 与降级成口令卡这两个原始症状已消除**。

> 注：`notReachable=True` 来自**另一行**（`127.0.0.1:44521` / `sshtest`，即被隧道改写的 HTTP 回环行）的健康徽标，
> **不是** `E2ECHK5554v98-k` 的行态。该行本身是 `Connecting over SSH…`。此处按 dump 原文如实标注归属。

**★ 修复语义的最强证据（sshd 侧）**：Cancel 时刻 20:32:08，sshd 记到

```
Oct 02 20:32:09 … error: Received disconnect from 127.0.0.1 port 60592:3:
              com.jcraft.jsch.JSchException: Auth cancel for methods 'publickey,password,keyboard-interactive' [preauth]
Oct 02 20:32:09 … Disconnected from authenticating user sshtest 127.0.0.1 port 60592 [preauth]
```

`Auth cancel for methods 'publickey,password,keyboard-interactive'` —— jsch **一次性取消整条方法链**，
而不是像修复前那样"这个方法失败了 → 换下一个方法 → 铸新 promptId → 重弹"。**这正是 v97 修复的本体语义，已在真机生效。**
且 `20:32:08` 之后**再无任何认证记录**（无 `Accepted`、无新的 `authentication failure`）—— 拨号确实被静默了。

（`20:32:07` 的 `pam_unix(sshd:auth): authentication failure` 与 `20:32:09` 的 `PAM: Authentication failure`
发生在 Cancel **之前**，属该次 Reconnect 的公钥探测：加密私钥无口令时先被拒，随后才升起 passphrase 挑战。
**该窗内没有任何 `Accepted password` / `Accepted keyboard-interactive`** ⇒ 不存在方法链降级。）

证据：`v98_f12b_s1..s7.xml`、`v98_f12b_12_recard.*`、`journalctl_FINAL.txt`

---

### 3.6 F13b — ✅ PASS（**前提已变，卡片路径未被触发**）

**环境差异（重要）**：本构建 Password 模式的 Add 表单**自带 `pw=true` 口令字段**
（`'Password'` label + `'Enter the password for this connection.'`），因此
**口令卡从未出现**（`card=False`），口令在表单内一次填对。

**流程**：新建 `E2ECHK5554v98-p`（Password 方式）→ 口令字段输 `opencode`（掩码 `len=8` 自证）→ `Add server` @ 20:37:02。

| 观察 | 值 |
|---|---|
| 面板 | **`Connected`** 10/10 采样，无任何失败态 |
| sshd | **`Oct 02 20:37:05 … Accepted keyboard-interactive/pam for sshtest from 127.0.0.1 port 40438 ssh2`** |
| 卡在哪一步？ | **没有卡住**。卡片没弹是因为**不需要弹**（凭据已在表单内），不是提交失败 |

→ **F13b = PASS**（凭据通道完好：sshd 明确 `Accepted keyboard-interactive/pam`）。
**但请注意**：任务书描述的"弹卡输 `opencode`"这条**具体交互路径本轮未被执行**，
若后续要专门复验"口令卡路径"，需另行构造（例如不填表单口令直接提交）。

---

## 4. journalctl 原文（全窗口 30 分钟，`journalctl_FINAL.txt`）

```
Oct 02 20:31:17 HomeMINISFORUM sshd-session[20645]: Accepted publickey for sshtest from 127.0.0.1 port 35860 ssh2: ED25519 SHA256:sYFitiUL7ZsVSAtodSjj4ob+l4DqiA05VZA9F7w4CR8
Oct 02 20:31:17 HomeMINISFORUM sshd-session[20645]: pam_unix(sshd:session): session opened for user sshtest(uid=1001) by sshtest(uid=0)
Oct 02 20:32:07 HomeMINISFORUM sshd-session[20738]: pam_unix(sshd:auth): authentication failure; logname= uid=0 euid=0 tty=ssh ruser= rhost=127.0.0.1  user=sshtest
Oct 02 20:32:09 HomeMINISFORUM sshd-session[20729]: error: PAM: Authentication failure for sshtest from 127.0.0.1
Oct 02 20:32:09 HomeMINISFORUM sshd-session[20729]: error: Received disconnect from 127.0.0.1 port 60592:3: com.jcraft.jsch.JSchException: Auth cancel for methods 'publickey,password,keyboard-interactive' [preauth]
Oct 02 20:32:09 HomeMINISFORUM sshd-session[20729]: Disconnected from authenticating user sshtest 127.0.0.1 port 60592 [preauth]
Oct 02 20:33:58 HomeMINISFORUM sshd-session[20775]: Accepted publickey for sshtest from 127.0.0.1 port 54612 ssh2: ED25519 SHA256:sYFitiUL7ZsVSAtodSjj4ob+l4DqiA05VZA9F7w4CR8
Oct 02 20:33:58 HomeMINISFORUM sshd-session[20775]: pam_unix(sshd:session): session opened for user sshtest(uid=1001) by sshtest(uid=0)
Oct 02 20:37:05 HomeMINISFORUM sshd-session[20985]: Accepted keyboard-interactive/pam for sshtest from 127.0.0.1 port 40438 ssh2
Oct 02 20:37:05 HomeMINISFORUM sshd-session[20985]: pam_unix(sshd:session): session opened for user sshtest(uid=1001) by sshtest(uid=0)
```

逐行归属：

| 时间 | 事件 | 归属 | 含义 |
|---|---|---|---|
| `20:31:17` | **`Accepted publickey`** | **F14b** | ★ 私钥首连成功，与点 `Continue`(20:31:16) 同秒 |
| `20:32:07/09` | `authentication failure` / `PAM: Authentication failure` | D1 探测 | 加密私钥无口令的正常前置探测；**该窗内无任何 `Accepted`，无降级** |
| `20:32:09` | **`Auth cancel for methods 'publickey,password,keyboard-interactive'`** | **F12b/D1** | ★★ **v97 修复本体在真机生效的直接证据**：整条方法链被一次性取消，而非改道 |
| `20:33:58` | **`Accepted publickey`** | **D1** | ★ 已存条目的 Reconnect 真实走公钥认证，非"UI 变了没发起" |
| `20:37:05` | **`Accepted keyboard-interactive/pam`** | **D2 / F13b** | 口令通道完好，且面板同时为 `Connected`（无假阴性） |

**F14b 判定复核**：`20:31:17` 那次只有 `publickey`；全窗口出现的唯一 keyboard-interactive 是
`20:37:05` 的 **D2/F13b 口令条目**，与 F14 条目无关。→ **F14b 回归 = PASS**。

---

## 5. 面板行态最终态

`v98_d3_44_panel.png`（两条测试条目）：

```
'E2ECHK5554v98-k'  y=1342  'Disconnected. The remote server is left running.'  Reconnect(408,1486) Disconnect(721,1486) ⋮(843,1565)
'E2ECHK5554v98-p'  y=1667  'Disconnected. The remote server is left running.'  Reconnect(408,1812) Disconnect(721,1812) ⋮(843,1890)
```

> 中途 app 曾自行回到 Home 并显示 `Couldn't load sessions`——因为 active server 被隧道改写到了回环地址，
> 隧道断掉后取不到会话。随后隧道落回 `Disconnected`，面板恢复正常。这是本轮 SSH 条目带来的副作用，非被测缺陷。

---

## 6. 与前序轮次的差异（需要 Lead 知道）

### 6.1 ★ 本构建已支持"记住密码"（F14 的 D4 已不成立）

F14 报告 §5.1 写「1.4.0 的 Password 模式根本没有存储密码字段，Add 表单只有 host/name/Authentication/Cancel/Add server」。
**本构建不成立**：Password 模式的 Add 表单里有
`'Password'` label + `'Enter the password for this connection.'` + `pw=true` 输入框 `[138,1548][943,1741]`。

**后果**：F13b/D2 的"弹卡输口令"路径**无法通过常规表单触发**，本轮改用表单内口令字段完成。
若 Lead 认为"弹卡输口令"仍是必须覆盖的场景，需要单独设计用例。

### 6.2 D3 的表现与 F14 报告不一致

F14 §8 D3 记录的是「弹窗 `Delete SSH server?` 出现后，按钮点击无响应、弹窗不消失」。
**本构建上确认弹窗压根不出现**（§3.4），且源码中也不存在该确认弹窗文案。
⇒ 这不是 v97 修复引入的回归（v97 未改 `ServerRow`/`SshTunnelCard` 的删除入口），
而是**与 F14 观察不同的另一个（或同一个？）问题**，需要定位。

---

## 7. 清理

| 项 | 结果 |
|---|---|
| `ResetFaillock` | ✅ `files/ssh` 现为空目录（`total 16`，仅 `.` 与 `..`），`known_hosts` 已清 |
| `/sdcard` 本轮残留 | ✅ **149 个** `v98_*` 文件已删，复查 `v98_*` = **0** |
| `/sdcard` 其它轮次文件 | ✅ 未触碰（`1034` 个历史文件原样保留） |
| 删除测试条目 `E2ECHK5554v98-k` | ❌ **未能删除**（D3 缺陷所致，见 §3.4） |
| 删除测试条目 `E2ECHK5554v98-p` | ❌ **未能删除**（同上） |
| 停止/杀进程 | 未杀模拟器、未杀 gradle；仅用过 `am force-stop com.xiufeigo.opencodeshell`（本轮开头重置状态一次） |

> **残留说明**：因 D3 的删除入口在本构建上完全无响应（连确认弹窗都不出），这两条测试条目
> **仍留在 emulator-5554 的 Settings → Servers → SSH tunnels 列表里**（当前均为 `Disconnected`）。
> 它们**没有**在后台拨号、不占端口、不会自动重连。若需清除，只能等删除入口修复后手动删，
> 或由持有设备的人重装/清数据（`pm clear com.xiufeigo.opencodeshell`，**本轮按纪律未执行**）。

---

## 8. 未闭环 / 未验到

1. **D3 的 `Authentication required` 态未验到**。原因见 §3.4（无 Edit 入口 + D2 修好后走不到该态）。
   需要另建一条**错口令**条目才能覆盖，建议下一轮补。
2. **D3 的根因未定位**。本轮只做到现象取证（三重独立取证 + 源码 grep 旁证），未做代码级定位。
   线索：菜单 `Delete` 的 `onClick` 路径上，没有任何东西把点击转成"确认弹窗"或"删除"。
3. **F12b 残留未闭环**：Cancel 后行态卡在 `Connecting over SSH…`、`Reconnect` 按钮消失，
   需 `Disconnect` → `Reconnect` 才能恢复入口。**建议单独立 issue。**
4. **F13b 的"弹卡输口令"路径未执行**（本构建表单已带口令字段），见 §6.1。
5. **D2 只跑了 1 轮**。v97 报告要求跑 3 轮以量化竞态复现率；本轮时间盒只完成 1 轮
   （10/10 采样全绿）。**竞态复现率仍未量化。**
6. **未做跨机型/跨 dpi 回归**（沿用 F14 遗留项，本轮未扩范围）。
7. **无 logcat 埋点**。v97 报告 §5 建议本轮在 `SshConnection.emit/publishPrompt` 加 `Log.d` 打印
   `attempt/guard.speaks`——**该埋点未出现在本构建中**，故"区分没发起认证 vs 发起了被拒"只能靠
   sshd 侧记录反推（本轮 sshd 记录已足够支撑全部判定，故此项不阻塞结论）。

---

## 9. 交付物清单

| 文件 | 内容 |
|---|---|
| `REPORT.md` | 本报告 |
| `v98lib.ps1` | 可复用 UI 库（dump/解析/哨兵填表/`ProbeCards`/`ProbeRows`/sshd 取证） |
| `00_setup.txt` / `01_panel.txt` / `02_keyentry_form.txt` | 环境、表单 bounds、哨兵证据 |
| `03_F14b.txt` / `04_D1.txt` / `05_F12b.txt` / `06_D2F13b.txt` / `07_D3.txt` | 各步逐条判定原文 |
| `08_cleanup.txt` / `09_store.txt` | 清理与 store 快照 |
| `journalctl_F14b.txt` / `journalctl_D1.txt` / `journalctl_D2.txt` / `journalctl_FINAL.txt` | sshd 原文 |
| `*.xml` / `*.png` | 各阶段 dump 与截图（约 160 个文件） |

### 关键证据文件速查

| 结论 | 文件 |
|---|---|
| F14b `Accepted publickey` | `journalctl_F14b.txt`、`v98_f14b_03_result.png` |
| D1 弹 passphrase 卡（非口令卡） | `v98_d1_01_card.png` |
| D1 重连 `Accepted publickey` | `journalctl_D1.txt`、`v98_d1_13_s1.xml`…`s10.xml` |
| D2 10/10 Connected | `v98_p_11_s1.xml`…`v98_p_11_s10.xml` |
| **D3 弹窗不出现** | `v98_d3_70_dlg.png`、`v98_d3_60_menu.png`、`v98_d3_82_after.xml` |
| F12b 0/7 | `v98_f12b_s1.xml`…`v98_f12b_s7.xml` |
| **v97 静音语义生效** | `journalctl_FINAL.txt`（`Auth cancel for methods 'publickey,password,keyboard-interactive'`） |
