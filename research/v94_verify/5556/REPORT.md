# v94 真机复验报告 — emulator-5556（Small_Desktop 桌面 freeform 机）

- 设备：`emulator-5556`，`sdk_gpc_x86_64`，1366x768 @160dpi，locale `en-US`
- 包名：`com.xiufeigo.opencodeshell` v1.4.0，入口 `.OcActivity`
- 后端：WSL Arch sshd `0.0.0.0:22`（`active`）+ `adb reverse tcp:2222 → tcp:22`，账号 `sshtest/opencode`
- 复验时间：2026-10-01（设备时钟 8:25–8:58；宿主 16:25–16:58，宿主 = 设备 + 8h）
- 证据目录：`research/v94_verify/5556/`（42 张 screencap + 37 份 uiautomator dump + 7 个探针脚本 + 3 份日志）
- 纪律遵守：全程 screencap 为准；**未按 BACK（keyevent 4）**，只用应用内 Close / Command palette / `am start` 导航，`keyevent 111` 仅收软键盘、`keyevent 61`（TAB）做表单焦点遍历、`keyevent 67`（DEL）修字段；未动其它设备（127.0.0.1:5555 等全程未接触）；未改代码、未跑 gradle、未装机；除任务书明写的两次 `am force-stop` 外未杀任何进程

---

## 结论汇总

| # | 复验项 | 结论 | 关键证据 |
| --- | --- | --- | --- |
| 1 | READY 隧道建立 + loopback WS 行落盘 | **PASS** | `E2ECHK5556_28_t7.png`、`E2ECHK5556_39/40_zoom_*.png`、`ui_A26_t7.xml`、`E2ECHK5556_sshd_journal.txt` |
| 2 | F8 留白：READY 确认条 release hint | **PASS** | `E2ECHK5556_30_armed_ready.png`、`E2ECHK5556_41_zoom_hint.png`、`ui_A28_armed_ready.xml` |
| 3 | F10 级联删除（loopback 行一并消失） | **PASS** | `E2ECHK5556_33/37/38_*.png`、`ui_A31/A34/A35_*.xml`、像素差 0 |
| 4 | READY 态按钮为 Disconnect 态 | **PASS** | `E2ECHK5556_28_t7.png` + 按钮取色 `#67B1C6` vs `#505661` |

**4/4 全 PASS。** 本轮首次在真机造出 READY 态 SSH 隧道，v91/v93 留白的「确认条 release hint 行」与「SshAuthenticate trust prompt」一并补验成功。

---

## ⚠ 复现要点（务必先读，否则造不出 READY）

**任务书给的 target `sshtest@127.0.0.1:2222` 连不上，必须写成 `ssh -p 2222 sshtest@127.0.0.1`。**

这是**测试输入口径问题，不是本轮 app bug**。`ui/servers/SshConnectionModel.kt` 的 `parseSshTarget()`（第 49–87 行）刻意镜像 upstream 的 `ssh` 命令行语法：只认前导 `ssh` token、`-p <port>`、`-i`、`-l`、`user@host`、`host`、带方括号 IPv6；**host 之后的任何 token 一律判为 remote command 而拒绝**。它没有 `host:port` 形式。所以：

- `sshtest@127.0.0.1:2222` → 被解析成 `user="sshtest"`、**`host="127.0.0.1:2222"`（含端口的整串）**、`port=22`
- `SshConnection.kt:97` 遂以 `jsch.getSession("sshtest", "127.0.0.1:2222", 22)` 去解析主机名 → 解析失败 → 抛异常 → `sshFailure()` 落到通用 `SshErrorCode.CONNECTION`
- 表单只显示 `Could not establish the SSH connection. Check your network and SSH configuration.`

**佐证**：这一轮失败提交（设备 8:30–8:33 / 宿主 16:30–16:33）期间，`E2ECHK5556_sshd_journal.txt` 在 `16:27:34`（sshd reload）与 `16:36:42`（我的设备侧 banner 探针）之间**一条连接都没有** —— 即 app 根本没把 TCP 包送出去，与「主机名不可解析」完全吻合。隧道链路本身没问题：用设备侧 `nc` 探 `127.0.0.1:2222` 能稳定读到 `SSH-2.0-OpenSSH_10.5`（`E2ECHK5556_probe_tunnel.sh`，journal `16:38:16` 有对应到达记录）。

> 附带观察（不计入判定）：`host:port` 这种「看起来像地址但不是合法 ssh 目标」的输入走的是通用 CONNECTION 错误，而不是 `oc_ssh_error_input` 那句「Enter a host or SSH command. Remote commands and unsupported SSH options aren't allowed.」。是否要在解析器里把「host 含 `:`」显式判为 input 错误，属产品口径，未在本轮改动。

---

## 1. READY 隧道建立 + loopback WS 行落盘 — PASS

### 1.0 建条（过程记录）

| 步骤 | 证据 | 事实 |
| --- | --- | --- |
| 冷启动 app | `E2ECHK5556_00_launch.png` | pid `17240` |
| 基线面板 | `E2ECHK5556_03_servers_baseline.png`、`ui_A03b_servers_baseline.xml` | 124 节点；SSH tunnels 段 2 条 v93 遗留（均 DISCONNECTED）+ WS 服务器 1 条 `127.0.0.1:4095`（Default，绿点 healthy） |
| Add SSH server | `E2ECHK5556_04_form.png` | 表单首屏只有 `Host or SSH command` |
| 填 target | `E2ECHK5556_17_target.png`、`ui_A15_target.xml` | 逐字符读回 = `ssh -p 2222 sshtest@127.0.0.1`（空格/`-`/`:`/`.` 全 ASCII，Gboard 未污染） |
| 填 Name | `E2ECHK5556_24_name3.png`、`ui_A22_name3.xml` | 读回 `V94R`，码位 `U+0056 U+0039 U+0034 U+0052` |
| 填 Password | `E2ECHK5556_26_pw.png` + `E2ECHK5556_pwlen.ps1` | 8 个 `U+2022` 掩码 = `opencode`（8 字符） |
| 空密码门 | `E2ECHK5556_07_submitted.png` | 复现 v90 事实：只填 host+name 会被红字 `Enter the password for this connection.` 拦下 |

**表单操作要点（给后续轮次）**：`Server name (optional)` 在 Host 之下，键盘态被裁掉；先 `keyevent 111` 收键盘 + 快滑（`input swipe … 100`，v90 记录慢滑无效）把表单滚到 `Name` 露出。**直接 tap Name 的坐标会误落到 Host 字段**（本轮连续两次把 `V94R` 打进 Host，见 `E2ECHK5556_21/23_name*.png`），最后改用 **`keyevent 61`（TAB）做焦点遍历**才稳定命中（`E2ECHK5556_24_name3.png`）。

### 1.1 trust prompt（v91/v93 留白，本轮打通）

- `E2ECHK5556_27_t3.png`、`ui_A25_t3.xml`：`Verify host identity` / `[127.0.0.1]:2222` / `ecdsa-sha2-nistp256` / `MD5:c0:7b:d5:bd:49:7e:04:f8:15:52:62:5c:e3:09:7a:f6` / `Trust and connect`
- **指纹双向对账**（决定性证据）：宿主 `ssh-keygen -l -E md5 -f /etc/ssh/ssh_host_ecdsa_key.pub` = `MD5:c0:7b:d5:bd:49:7e:04:f8:15:52:62:5c:e3:09:7a:f6` —— 与 app 弹窗显示**逐字符相同**
- 点 `Trust and connect` → 宿主 journal `16:52:48` 记 **`Accepted keyboard-interactive/pam for sshtest from 127.0.0.1 port 33876 ssh2`** + `pam_unix(sshd:session): session opened for user sshtest(uid=1001)`：真认证通过，不是假握手

### 1.2 READY 态 + loopback 行 — PASS

`E2ECHK5556_28_t7.png` / `ui_A26_t7.xml`：

- `V94R` 行状态 = **`Connected`**（`SshStage.READY`），节点 `[494,444]-[552,458]`
- **隧道写下的 loopback WS 行**：`127.0.0.1:38835`，副标题 `sshtest`，节点 `[514,522]-[607,538]`，右侧 `Default` + `⋮`，左侧红点（远端 4096 无服务 → 不健康，符合预期）
  - 该行紧跟在 WS 分组内、`127.0.0.1:4095`（`v2.0.18 • opencode`，绿点 healthy）之上
- 放大取证：`E2ECHK5556_39_zoom_ready_row.png`（V94R 行 3x）、`E2ECHK5556_40_zoom_loopback_row.png`（loopback 行 3x）

> 注意区分：失败态那条 SSH 条目的**名字**也叫 `127.0.0.1:2222`（`sshHostname()` 取最后空白 token 再按 `@` 切尾），它在 **SSH tunnels 段**里、自带 `Reconnect/Disconnect/⋮`；而隧道 loopback 行在 **WS 服务器段**、只有 `Default/⋮`。两者形态不同，本报告全程按「段归属」区分。

---

## 2. F8 留白：READY 确认条 release hint — PASS

### 2.1 READY 条目确认条多一行 — PASS

`E2ECHK5556_30_armed_ready.png` + `ui_A28_armed_ready.xml`，三行俱全：

| 行 | 文案 | 节点 bounds |
| --- | --- | --- |
| 标题 | `Delete SSH server?` | `[506,482]-[627,498]` |
| 正文 | `This removes "V94R" from the list. This cannot be undone.` | `[506,502]-[818,516]` |
| **断开提示** | **`Its tunnel is disconnected first.`** | **`[506,520]-[670,534]`** |

- 对应资源 `R.string.oc_ssh_delete_release_hint`（`values/strings.xml:1134` = `Its tunnel is disconnected first.`；zh-rCN = `将先断开对应的隧道。`、zh-rTW = `將先中斷對應的通道。`）。本机 locale `en-US`，故显示英文属**预期**，非缺串。
- 3x 放大：`E2ECHK5556_41_zoom_hint.png`（三行文字清晰完整、无截断）

### 2.2 差分证据：提示行只在 READY 态出现

同一条路径的**失败态对照**（删那条 `127.0.0.1:2222` 失败条目时顺带取证）：

- `E2ECHK5556_14_armed_failed_entry.png` + `ui_A12_armed.xml`：只有 **2 行**（标题 `[506,462]-[627,478]` + 正文 `[506,482]-[860,496]`），**无** `Its tunnel is disconnected first.`

→ 证明该行是按 `releaseFirst`（READY / 连接中）条件渲染，失败态正确地不加，非无条件渲染的假阳性。

### 2.3 Cancel 保留行（像素级） — PASS

- `E2ECHK5556_31_after_cancel.png`（点 `Cancel` 后）
- **V94R 行区**（x=474..1132, y=412..472）对 `E2ECHK5556_28_t7.png`（上膛前 READY 基线）：
  `pixels=40199  diffpx(>12)=0 (0%)  maxsum=0  diff rows: none` → **40199 个像素逐点完全一致**，Cancel 零残留
- **对照组**（上膛态 vs 上膛前，同区）：`diffpx=2670 (6.642%) maxsum=78`，差异行仅 `y=464..472`（每行 659 像素的整条确认条上边框）→ 确认条确实出现过、也确实被 Cancel 完整收回
- **dump 字节级回位**：`ui_A28_armed_ready.xml`（上膛）= 45917 bytes → `ui_A29_after_cancel.xml` / `ui_A29b_after_cancel.xml`（Cancel 后）= **45891 bytes，与上膛前 READY 基线 `ui_A26_t7.xml` 的 45891 完全一致**（节点数亦 136→136）。上膛多出的 26 bytes 正是确认条三个节点的标记，Cancel 后逐字节归零

### 2.4 重新上膛

`E2ECHK5556_32_rearmed.png` + `ui_A30_rearmed.xml`：二次 `⋮→Delete` 仍带 `Its tunnel is disconnected first.`（`[506,520]-[670,534]`），提示行可重复触发。

---

## 3. F10 级联删除 — PASS

### 3.1 确认删除后两行同时消失 — PASS

`E2ECHK5556_33_after_delete.png` + `ui_A31_after_delete.xml`：

- `V94R` SSH 条目：**消失**
- loopback WS 行 `127.0.0.1:38835`：**一并消失**
- 仅剩 v93 两条遗留 + `127.0.0.1:4095`（绿点 healthy）
- **节点数 124 = 本轮开测基线 124**；且 `ui_A31_after_delete.xml` = **41821 bytes，与开测基线 `ui_A03b_servers_baseline.xml` 的 41821 逐字节相同** —— 面板结构完全回到开测前形态
- 对照：READY 基线 vs 删除后，loopback 行所在槽位（x=474..1132, y=500..560）`diffpx=36225 (90.114%) maxsum=643`，整片区域变化 → 该行确实被移除后整体上移，不是被覆盖或置空

### 3.2 离开面板重进 — 两行都不复活 — PASS

- 步骤：`Close`(1129,108) → Command palette(116,54) → `Open settings`(424,173) → `Servers`(255,535)
- 证据：`E2ECHK5556_37_reenter.png` + `ui_A34_reenter.xml` → **124 节点**，仅 `127.0.0.1:4095` + `Add SSH server`，**无 `V94R`、无 `127.0.0.1:38835`**

### 3.3 `am force-stop` + 冷启动 — 两行都不复活 — PASS

- `am force-stop com.xiufeigo.opencodeshell`：pid `17240` → 空
- `am start …/.OcActivity` 冷启动：pid **`19065`**（**新进程，非热重启**）
- 再走 palette → Open settings → Servers
- 证据：`E2ECHK5556_38_cold.png` + `ui_A35_cold.xml` → **124 节点**，无 `V94R`、无 `127.0.0.1:38835`
- **像素级**：`E2ECHK5556_33_after_delete.png`（删除后）vs `E2ECHK5556_38_cold.png`（冷启动后），面板区 x=474..1132, y=152..500：
  `pixels=229991  diffpx(>12)=0 (0%)  maxsum=0  diff rows: none` → **229991 像素逐点完全一致**，删除是持久的

### 3.4 「先释放隧道」的服务端侧旁证

- 删除后、重进面板的整段流程中，宿主侧已无 `:22` 的已建立连接（`ss -tnp | grep ':22 '` → 空）
- `E2ECHK5556_sshd_journal.txt` 里 `16:52:48` 的 `sshtest` 会话之后再无新建会话
- 诚实标注：该 `ss` 快照采集于**冷启动之后**，故它只能证明「流程结束时无残留会话」，**不能单独隔离出「点击 Delete 那一刻」释放了 socket**。释放动作的 UI 级证据是 2.1 的提示行 + 3.1 的 loopback 行级联消失（读 `SshEntry.lastLocalUrl` → `bridge.delete`）。

---

## 4. 顺带：READY 态按钮为 Disconnect 态 — PASS

对 `E2ECHK5556_28_t7.png` 同帧内两组按钮取色（`E2ECHK5556_pixel.ps1`）：

| 行状态 | 样本点 | `Disconnect` 按钮填充 | `Reconnect` 位置 |
| --- | --- | --- | --- |
| **READY**（`V94R`） | (1005,438) `#5D9EB2`／(1020,438) (1060,438) (1005,446) 均 **`#67B1C6`** | **accent 蓝（主行动）** | (900,438) `#C1C3C6`，与断开态同值 |
| DISCONNECTED（v93 行 1） | (1005,324) `#49505A`／(1020,324) (1060,324) (1005,332) 均 **`#505661`** | 暗灰（非主行动） | (900,324) `#C1C3C6`，同值 |

→ READY 态下 **`Disconnect` 承载 accent 主行动填充 `#67B1C6`**（正是 `SshModel.kt` 注释里记的 solid enabled fill），`Reconnect` 不再是主行动；DISCONNECTED 态则相反。视觉同证于 `E2ECHK5556_28_t7.png`（V94R 行 Disconnect 高亮青色）与同图两条 v93 行（Reconnect 高亮、Disconnect 灰）。

---

## 环境与遗留状态

- **设备已复原**：显示仍为 1366x768（本轮未动 `wm size`）；未按 BACK；除任务书两次 `am force-stop` 外未杀进程；app 正常运行中（pid 19065）
- **adb reverse 保持原样**（未增删）：`tcp:4095`、`tcp:4096`、`tcp:2222→tcp:22`
- **面板现存**：v93 遗留两条 SSH 条目 `V93P-narrow-window-ellipsis-check-0123456789`、`V93P-ellipsis-probe-AAAAAABBBB…`（host 均 `user@192.0.2.1`、口令 `V93Ppw`、均 DISCONNECTED）+ WS 服务器 `127.0.0.1:4095`（Default）。本轮 V94R 与其 loopback 行均已删除清空，任务书未要求清理 v93 遗留故保留；要清走同一确认条即可（已验证可用）
- **已知的 3 条未归因 preauth**：`E2ECHK5556_sshd_journal.txt` 中 `16:49:32` / `16:49:49` / `16:50:05` 三条 `Connection closed … [preauth]`，时间落在第二次填表期间（尚未提交）。未能确证发起方（可能是 app 侧对已存失败条目的后台重试），**不影响上述任何判定**，如实记录在此
- **可复用探针脚本**（serial 固定 emulator-5556、输出指向本目录，共 7 个）：`E2ECHK5556_nav.ps1`（按文本 tap / 坐标 tap / 分段 input / dump+shot）、`E2ECHK5556_parse.ps1`（列 EditText）、`E2ECHK5556_pwlen.ps1`（掩码位数，**必须 `-Encoding UTF8`** 读 dump，否则中文/掩码乱码）、`E2ECHK5556_diff.ps1`（区域逐像素对比）、`E2ECHK5556_pixel.ps1`（取色）、`E2ECHK5556_zoom.ps1`（裁剪放大）、`E2ECHK5556_probe_tunnel.sh`（设备侧 SSH banner 探针）
- **工具坑（已踩）**：`exec-out screencap` 多次被 adb warning 污染 stdout → 一律用 `cmd /c "adb -s emulator-5556 exec-out screencap -p > <file>"`；PowerShell `$PID` 是只读变量（用 `$apppid`）；`$f:` 变量插值在 PowerShell 解析报错（改用 `ssh-keygen` 直接单文件调用）；bash 工具有间歇性 `Parent-death guardian` 超时，重试即可；`E2ECHK5556_zoom.ps1` 里 `$Src` 参数与 `$src` 位图变量大小写同名会撞车（PowerShell 不区分大小写）
