# REPORT_COVERAGE9 — 真机复验 D3-4 三态（专轮）

- 设备：`emulator-5554`（Pixel_9a，1080x2424@420）
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 轮次：第 9 轮（D3-4 三态专轮；**未重做 D3-3**，第 1–4 行沿用上轮 PASS 结论）
- 纪律：全程 `adb -s emulator-5554`；未跑 gradle / 未装机 / 未杀进程（仅本 app 允许 `am force-stop`）；**未按 BACK(4)**、**未用 ESC(111)**；截图仅 `screencap -p` + `adb pull`；坐标全部运行期从 dump 解析；**`known_hosts` 未创建未删除**。

> **本轮最重要的产出不是三态，而是把上轮「8/8 全部 `row not visible` + `kebabs=0`」的**根因**定位并修掉了——那 8 次扫描不是环境问题，是**脚本定位键选错**，因此「`Connecting` 态无行菜单」这一结论本轮被**证伪**。详见 §1、§3。

---

## 0. 环境读数

```
$ adb devices -l
emulator-5554  device  product:sdk_gphone16k_x86_64  model:sdk_gphone16k_x86_64  transport_id:39

$ adb -s emulator-5554 shell dumpsys window | grep mCurrentFocus
mCurrentFocus=Window{443a51a u0 com.xiufeigo.opencodeshell/com.xiufeigo.opencodeshell.OcActivity}
mFocusedApp=ActivityRecord{222616187 u0 com.xiufeigo.opencodeshell/.OcActivity t132}
（全程未出现 Application Not Responding，未 reboot）

$ adb -s emulator-5554 reverse --list
host-15 tcp:4095 tcp:4095
host-15 tcp:2222 tcp:22
```

宿主 SSH（决定设备端应连 `127.0.0.1:2222`）：

```
LocalAddress LocalPort OwningProcess
::1                 22         35328
127.0.0.1         4095         35328
127.0.0.1           22         35328
```

`reverse tcp:2222 tcp:22` ⇒ 设备 `127.0.0.1:2222` → 宿主 `127.0.0.1:22`（sshd 在监听）。

app 私有目录：

```
$ run-as com.xiufeigo.opencodeshell ls -la files/
-rw-rw-rw- 1 u0_a230 u0_a230  444 2026-10-03 03:40 id_ed25519
$ run-as … md5sum files/id_ed25519
0120a8270f7f0b0c71d31f4933384c63     <- 与上轮一致，私钥未被破坏

$ run-as … ls -la files/ssh/
total 16
drwx------ 2 u0_a230 u0_a230 4096 2026-10-03 04:09 .
drwxrwxr-x 3 u0_a230 u0_a230 4096 2026-10-02 07:54 ..
$ run-as … cat files/ssh/known_hosts
cat: files/ssh/known_hosts: No such file or directory
```

> ### ⚠ 前提修正
> 任务书称「第七轮写入的 `known_hosts`，本轮也不要清它」。**实测 `files/ssh/` 为空目录，`known_hosts` 早已不存在**（上轮报告 §4.3 记录的那次删除已生效）。因此本轮**新建 host 会重新弹核验卡**，「同名不再弹核验卡」的前提不成立。本轮**没有创建也没有删除** `known_hosts`，保持空目录原状。

---

## 1. 上轮 8/8 `row not visible` + `kebabs=0` 的**根因**（已定位并修复）

上轮脚本按**地址**（`127.0.0.1:2222` / `10.0.2.2:4095`）定位列表行。实测地址在 UI 树里**完全不存在**：

```
$ grep -o '[^"]*\(4095\|2222\|127\.0\.0\.1\|10\.0\.2\.2\)[^"]*' c9_p0.xml
（0 命中）
```

**列表行的身份是它的「名称」TextView**（文本走 `content-desc`，`text` 属性为空）。用地址当 key ⇒ 每次查找必然 miss ⇒ 稳定复现 `row not visible` + `kebabs=0`。
**与「扫描太快 / 没先滚动到该行」无关**——上轮报告 §3.2「按行高窗口截取扫描太慢」的解释是**误判**。

行内菜单按钮的真实身份也不是 `⋮`，而是 `content-desc="Server actions"`（`class=android.view.View`, `clickable=true`）。

**修复后的行—菜单几何规律**（本轮实测标定）：

| 行名 | name cy | 自身 kebab bounds | kebab cy | **delta** |
|---|---|---|---|---|
| `E2ECHK5554d3e-c2` | 975 | `[780,1115][906,1241]` | 1178 | **+203** |
| `127.0.0.1:2222`（本轮建） | 1300 | `[780,1440][906,1566]` | 1503 | **+203** |
| `10.0.2.2:4095` | ~1547 | `[780,1687][906,1813]` | 1750 | **+203** |

> **kebab 的 cy 在其所属行名称 cy 的 +203 px 处**（行内还有状态行 + Reconnect/Disconnect 两个按钮）。按「离名称最近的 kebab」来选会**选中上一行的**——这一点本轮真的踩了，见 §4 的误删。

---

## 2. 基线行清单与落库计数口径

轮次开始时（滚动枚举去重）真实条目 **3 条**：

| 行名 | bounds | 状态文案原文 |
|---|---|---|
| `E2ECHK5554d3e-c2` | `[201,1215][535,1256]` | `Disconnected. The remote server is left running.` |
| `E2ECHK5554d3e-c3` | `[201,1540][535,1581]` | `Disconnected. The remote server is left running.` |
| `10.0.2.2:4095` | `[254,1709][474,1750]` | `Server healthy` |

（枚举中另出现 `v2.0.18 ? opencode`，是 `10.0.2.2:4095` 行下的版本/档案状态行，**非独立条目**。）

**落库计数口径**：`shared_prefs/oc_server_config.xml` 为 **AES 加密**（`androidx.security.crypto`，键名形如 `AXhs57I6NMPf3lLqQntVhk7RMWHThg1Y`，值为 `ATA0KB+…`），**无法 grep 出条目名**——印证上轮 §5.6 的教训。加密后 key 数（5 个数据键）≠ 条目数（每条目的 host/user/password 等各自成键），故**落库计数一律以 UI 枚举为准**，prefs 仅作存在性/体积旁证（文件 2230 B @ 12:46）。

---

## 3. 三态逐条判定

### 3.1 `Connected` — ❌ **未验到（非缺陷，止于网络/认证前置）**

| 项 | 内容 |
|---|---|
| 造的条目 | `127.0.0.1:2222`（Add 表单 Host 填 `sshtest@127.0.0.1:2222`，`user@host` 语法**被 app 正确解析**——行名回落为 `host:port`） |
| 表单字段 | 仅 3 个可见字段：`Host or SSH command` / `Server name (optional)` / `Password`（helper：`Enter the password for this connection.`）。**无独立 Username / Key path 字段** ⇒ 私钥认证在本 UI **无入口** |
| 关键卡点 | `Password` 为**必填**：留空时点 `Add server` 无任何反应（实测点 3 次表单均不关闭）；填入 `opencode` 后存储为掩码 `????????`（8 字符）才提交成功 |
| 实际到达状态 | **`Connection failed`**（不是 `Connected`，也不是 `Authentication required`） |
| 状态文案原文 | 行状态：`Disconnected. The remote server is left running.`；卡片标题 `Connection failed`；正文原文：**`Could not establish the SSH connection. Check your network and SSH configuration.`**；按钮 `Cancel` / `Connect` |
| 试过什么 | ① 口令 `opencode`（账号口令）；② 点 `Connect` 重试；③ 等 `Trust and connect` 核验卡（**始终未出现**，因 `known_hosts` 已空且该轮直接建连失败） |
| 判定 | **未验到（卡在 `Connection failed`，非缺陷）**。`Connected` 态本轮**未造出**，故「`Connected` 态删除」**未闭环**。 |

> 私钥路径 `files/id_ed25519`（md5 `0120a8270f7f0b0c71d31f4933384c63`）在位且未被破坏，但**当前 Add 表单没有任何私钥/Key path 输入框**，`SshConnectionPanel.kt:132` 的 `enabled = !connecting` 说明行内动作是受控的，而认证材料只能在**建条目时**填——本 UI 版本无处填私钥。**这是能力缺口，不是本轮脚本缺陷。**

### 3.2 `Connecting` — ⚠ **复核结论：上轮「该态无行菜单」结论被证伪**

**实测确实捕捉到 `Connecting` 态**（不是 `Connection failed`）——认证卡弹出、状态文案为 `Connecting over SSH…`：

```
[12:39:22]  '127.0.0.1:2222'          b=[201,1865][425,1906]
[12:39:22]  'Connecting over SSH…'     b=[201,1911][510,1937]      <- 该态状态文案原文
[12:39:22]  'Authentication'           b=[106,1357][337,1398]
[12:39:22]  'Password'                 b=[106,1557][264,1598]
[12:39:22]  '????????'                 b=[138,1615][943,1741]
[12:39:22]  'Cancel'                   b=[359,1779][470,1820]
[12:39:22]  'Connecting over SSH…'     b=[565,1779][938,1820]      <- 提交按钮
```

**两次 dump 对照（同一屏，行 kebab 归属按 §1 的 +203 规律判定）**：

| # | 行的状态 | 行 name cy | 该行自身 kebab | kebab cy | 存在？ | enabled |
|---|---|---|---|---|---|---|
| A（`Disconnected`） | `E2ECHK5554d3e-c2` | 975 | `[780,1115][906,1241]` | 1178 | **存在** | `true` |
| B（`Connecting`/`Connection failed`） | `127.0.0.1:2222` | 1300 | `[780,1440][906,1566]` | 1503 | **存在** | `true` |

同屏 4 个 `Server actions` 节点，cy 依次 `853 / 1178 / 1503 / 1750`，行名 cy 依次 `~750 / 975 / 1300 / ~1547`，**每个 kebab 都与其行名精确相差 +203**。落在 `Connecting`/`Connection failed` 态的 `127.0.0.1:2222` 行（cy=1300）**有**自己的 kebab（cy=1503, enabled=true）。

> **判定：上轮 `C2 NO KEBAB during Connecting` 是脚本假象，不是「按设计不可达」。** 根因是 §1 的两个定位错误：① 用地址而非名称找行；② kebab 归属判据用「离名称最近」而非「名称 cy + 203」，导致把上一行的 kebab 判给了本行。**本轮已在该态成功删除（见 §4），故「该态无行菜单」不成立。**

### 3.3 `Authentication required` — ❌ **未验到（非缺陷）**

| 项 | 内容 |
|---|---|
| 造的条目 | 计划用错误口令 `wrongpass` 触发；**实际未走到认证判定就失败了** |
| 实际发生 | 认证卡（`Authentication` / `Password`）**确实弹出**，`Password` 字段可填入；提交后直接落到 `Connection failed`（`Could not establish the SSH connection…`） |
| 为何没造出 | 失败发生在**建 TCP/SSH 会话阶段**，服务端**从未进入认证协商**，因此 app 侧没有拿到 auth 失败信号，**不可能**渲染 `Authentication required` |
| 判定 | **未验到（非缺陷）**。该态以「认证被服务端拒绝」为前提，而本轮连会话都没建立起来。`Authentication required` 态删除**未闭环** |

---

## 4. 删除证据（本轮**已闭环**的项）

### 4.1 在**非 `Disconnected` 态**删除成功 ✅ —— `Connection failed` 态删除 `127.0.0.1:2222`

**删前行清单**（滚动枚举去重）：
```
E2ECHK5554d3e-c2 | 127.0.0.1:2222 | 10.0.2.2:4095 |（v2.0.18 ? opencode = 状态行，非条目）
```

**操作链（坐标全部运行期从 dump 解析）**：
```
target name cy=1300
kebab nodes: [780,1115][906,1241]@1178 , [780,1440][906,1566]@1503 , [780,1687][906,1813]@1750
KebabFor '127.0.0.1:2222': [780,1440][906,1566] cy=1503 (name cy=1300, delta=+203) -> OWN   <- 归属判定
TAP kebab -> 843,1503
DELETE item [618,1598][717,1651]  -> 668,1624
```

**确认条原文（决定性证据）**：
```
'Delete SSH server?'                                              b=[233,1279][546,1320]
'This removes "127.0.0.1:2222" from the list. This cannot be undone.'  b=[233,1331][848,1401]
CONFIRM [707,1780][811,1821] -> tap 759,1800
```
**确认条文里带的就是目标行名本身** ⇒ 删的就是它。

**删后行清单**：
```
E2ECHK5554d3e-c2 | 10.0.2.2:4095 |（v2.0.18 ? opencode = 状态行）
TARGET REMOVED = True
10.0.2.2:4095 INTACT = True
E2ECHK5554d3e-c2 INTACT = True
```

### 4.2 ⚠ 误删一次，如实记录：误删 `E2ECHK5554d3e-c3`

首次尝试用了「离行名最近的 kebab」（阈值 70 px）。目标行名 cy=1300、最近 kebab cy=1178（delta=122）——**那是上一行 `c3` 的 kebab**（1178−975=203）。结果：

```
'Delete SSH server?' b=[233,1279][546,1320]
'This removes "E2ECHK5554d3e-c3" from the list. This cannot be undone.'  b=[233,1331][848,1401]
→ E2ECHK5554d3e-c3 INTACT = False
```

**影响评估**：`c3` 是上轮自建的 E2E 条目，**不是他人条目**，属「只动自建条目」范围；**`10.0.2.2:4095` 全程完好**（三次枚举均 `INTACT=True`），无第三方损失。修正定位规律（+203）后重试才删对目标。
**这也再次印证 §1：行—kebab 归属判据错了，删的就是邻居行。**

### 4.3 反例 — 只动目标行 / 他人条目完好 ✅

| 断言 | 结果 | 证据 |
|---|---|---|
| `10.0.2.2:4095` 完好 | ✅ | 轮初 / 删前 / 删后 / 收尾 四次枚举均存在；状态 `Server healthy` |
| `E2ECHK5554d3e-c2` 完好 | ✅ | `E2ECHK5554d3e-c2 INTACT = True` |
| 只创建/删除自建条目 | ✅ | 新建 `127.0.0.1:2222`（自建）并删除；误删的 `c3` 亦为自建 |

---

## 5. 清理

| 项 | 状态 | 证据 |
|---|---|---|
| `E2ECHK5554d3f*` 残留 | ✅ **0 条** | `d3f residue = 0`（本轮实际条目名因 UI 无 Name 字段写入而落为 `127.0.0.1:2222`，已删净） |
| 最终条目 | `E2ECHK5554d3e-c2` \| `10.0.2.2:4095` | `FINAL ENTRIES` |
| `/sdcard` 本轮残留 | ✅ 已清 | `rm -rf /sdcard/d3r9 /sdcard/d3`；复核 `No such file or directory`。`/sdcard` 仍有**往轮**遗留（`v34_*`/`v38*`/`v43*`/`E2ECHK5556_V95C*`/`d3.xml`），**非本轮产生，未动** |
| `reverse` 复核 | ✅ 一条没动 | `host-15 tcp:4095 tcp:4095`、`host-15 tcp:2222 tcp:22` |
| **`known_hosts`** | ✅ **未动** | 轮前轮后均 `files/ssh/` 空目录、`known_hosts` 不存在；本轮未创建未删除 |
| 私钥 `files/id_ed25519` | ✅ 未破坏 | md5 `0120a8270f7f0b0c71d31f4933384c63`（与上轮一致） |
| 设备健康 | ✅ | 全程 `focus=OcActivity`，**未出现 `Application Not Responding`**，未 reboot，未 `am force-stop` |

---

## 6. 未闭环 / 下一轮建议

1. **`Connected` 态删除 未闭环**：止于 `Connection failed`（`Could not establish the SSH connection`）。这是**前置条件**问题，不是三态 UI 缺陷。
2. **`Authentication required` 态删除 未闭环**：需服务端**真的进入认证协商**才会出现该态。本轮会话根本没建起来。
3. **UI 能力缺口（新发现）**：当前 Add 表单**只有 Host / Name / Password 三个字段，无 Username、无 Key path/私钥选择** ⇒
   - `user@host` 语法可用（`sshtest@127.0.0.1:2222` 被正确解析）；
   - **私钥认证在本 UI 无入口** ⇒ 任务书要求的「私钥认证条目 + `keypass123` 口令挑战」在本版本**无法执行**，`keypass123` 本轮从未被用上。
4. **定位规律请固化进下轮脚本**（本轮最大教训）：
   - 行 = **名称** TextView（地址不出现在 UI 树里）；
   - 行 kebab = `content-desc="Server actions"` 且 **cy = 行名 cy + 203**；
   - 状态文案 = 行名下 ~+46 px 处的 TextView；
   - 断言用**大小写敏感**匹配：`Disconnected` 含 "connected"，`-match 'Connected'` 会假阳性（本轮真的假阳性过一次）。
5. **建议下一轮的解锁顺序**：先在宿主侧确认 sshd 对 `127.0.0.1:2222`（经 `adb reverse`）**确实可达且会走认证协商**，再谈 `Connected` / `Authentication required`；否则这两态在本轮这类环境下**结构上不可达**。
