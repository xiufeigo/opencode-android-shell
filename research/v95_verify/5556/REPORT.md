# v95 真机复验报告 — emulator-5556（Small_Desktop 桌面 freeform 机）

- 设备：`emulator-5556`，1366x768 @160dpi（= **853dp 宽**），locale `en-US`
- 包名：`com.xiufeigo.opencodeshell` v1.4.0，`lastUpdateTime=2026-10-01 10:16:26`（F12a/F12b 两个修复已装机）
- 后端：WSL Arch sshd `active` + `adb reverse tcp:2222 → tcp:22`，账号 `sshtest/opencode`
- 复验时间：2026-10-01（设备 10:26–11:57；宿主 18:26–19:57，宿主 = 设备 + 8h）
- 证据目录：`research/v95_verify/5556/`（98 张 screencap + 89 份 uiautomator dump + 6 个探针脚本 + 1 份 sshd journal）
- 纪律遵守：全程 screencap 为准；**全程未按 BACK（keyevent 4）**，只用应用内 `Close` / Command palette / `am start` 导航；`keyevent 111` 仅收软键盘、`keyevent 61`（TAB）做表单焦点遍历、`keyevent 67`（DEL）修字段；未改代码、未跑 gradle、未装机、**未杀任何进程**（本轮连 `am force-stop` 都没用过）；未动其它设备（127.0.0.1:5555、5554、5558、5560、5562 全程未接触）

---

## 结论汇总

| # | 复验项 | 结论 | 关键证据 |
| --- | --- | --- | --- |
| 1 | **F12b 面板级认证卡（桌面窗口形态）** | **PASS** | `E2ECHK5556_18/19/43/44_*.png`、`ui_A18/A19/A44_*.xml`、`E2ECHK5556_sshd_journal.txt` |
| 2a | **F11 宽窗（≥450dp）CONNECTED 行单行形态** | **PASS** | `E2ECHK5556_44_result.png`、`E2ECHK5556_45_zoom_wide_row.png`、`ui_A44_result.xml` |
| 2b | **F11 窄窗（350dp）堆叠形态** | **PASS（形态）/ 见下方「口径说明」** | `E2ECHK5556_46/47/60/64/65/95/97_*.png`、`ui_A95/A97_*.xml` |
| 3 | F12a 私钥 + promptPassphrase 交叉验证 | **SKIP** | 本轮时间预算耗尽，未做（任务书已声明不做不扣分） |
| 4 | 清理（删条目 / 不复活 / `wm size reset`） | **PASS** | `E2ECHK5556_109_gone.png`、`ui_A110_reenter.xml`（**SHA256 与 v94 基线逐字节相同**）、`E2ECHK5556_105_reset_wide.png` |

**F12b 与 F12a 两个修复中，F12b 完整打通并通过；F12a 未验。**

---

## 1. F12b 面板级认证卡（桌面窗口形态） — PASS

### 1.1 建条

| 步骤 | 证据 | 事实 |
| --- | --- | --- |
| 冷启动 app | `E2ECHK5556_00_launch.png` | pid `19544` |
| 开测基线 | `E2ECHK5556_03b_baseline.png`、`ui_A03b_baseline.xml` | **124 节点 / 41821 字节**，与 v94 基线逐字节相同 |
| 填 target | `ui_A05_host.xml` | 逐字符读回 `ssh -p 2222 sshtest@127.0.0.1` |
| 填 Name | `E2ECHK5556_10_name.png` | 读回 `V95D` |
| 填错误 Password | `ui_A13_pw.xml` | **8 个 `U+2022` 掩码 = `badpw123`**（故意错） |

### 1.2 行级 Reconnect 弹出面板内认证卡（F12b 核心） — PASS

1. **Add 流程**先弹卡（`SSH authentication` / `Password:` / `Cancel` / `Continue`）→ `Cancel`
   → 条目落到 **`Authentication required`** 态（`E2ECHK5556_17_auth_required.png`、`ui_A17_authreq.xml`）
2. 点**行级 Reconnect** `(929,437)` → **面板内重新弹出 SshAuthenticate 卡**
   → `E2ECHK5556_18_reconnect_card.png`、`ui_A18_card.xml`

卡片节点（无键盘态）：

| 元素 | bounds |
| --- | --- |
| `SSH authentication` | `[458,436]-[596,455]` |
| `Password:` | `[458,480]-[528,494]` |
| 密码输入框 | `[470,501]-[878,549]` |
| `Cancel` | `[740,562]-[783,578]` |
| `Continue` | `[819,562]-[876,578]` |

→ 卡完整落在设置面板窗口内（`[193,6]-[1173,762]`），**四要素齐全、无裁切**。

### 1.3 footer 与键盘上沿的像素差值 — PASS

点密码框唤起 Gboard 后（`E2ECHK5556_19_kbd_up.png`、`ui_A19_kbd.xml`），卡片上移让位：

| 元素 | bounds |
| --- | --- |
| `SSH authentication` | `[458,136]-[596,155]` |
| 密码输入框 | `[470,201]-[878,249]` |
| `Cancel` | `[740,288]-[783,304]` |
| `Continue` | `[819,288]-[876,304]` |

**键盘上沿实测 = y 342**（`E2ECHK5556_kbdedge.ps1`，x=300 逐行取色）：

```
y=334  #292F3A   卡片背景
y=335  #3F444E   卡片下边框
y=336  #0B0D11   ┐
 ...              │ 窗口底/分隔带
y=341  #0A0C11   ┘
y=342  #F1F0F7   ← 键盘上沿（Gboard 背景色首行）
```

- **footer 底边 304 → 键盘上沿 342，净空 `342 − 304 = 38 px`**
- 卡片自身下边框 335 → 距键盘上沿 `342 − 335 = 7 px`，**整卡完整可见**

### 1.4 正确密码 → Continue → Connected — PASS

- 输 `opencode` → 8 个掩码（`ui_A42_typed.xml`）
- 点 `Continue` → **`E2ECHK5556_44_result.png`** / `ui_A44_result.xml`：
  - `V95D` `[494,454]-[528,470]`，状态 **`Connected`** `[494,472]-[552,486]`
  - 隧道 loopback WS 行 `127.0.0.1:46483`（副标题 `sshtest`）出现在 WS 服务器段
- **服务端决定性旁证**（`E2ECHK5556_sshd_journal.txt`）：

```
18:56:15  pam_unix(sshd:auth): authentication failure ... user=sshtest   ← 存的错密码 badpw123
18:57:41  Accepted keyboard-interactive/pam for sshtest from 127.0.0.1 port 42532 ssh2
18:57:41  pam_unix(sshd:session): session opened for user sshtest(uid=1001)
```

→ 先有**存储密码失败**、后有**键盘交互认证成功并开出会话**，正是 v94 记录的「存储密码错误 → 回落 keyboard-interactive → 面板内弹卡」链路；认证是**真的**，不是假握手。

> 附带记录：Add 流程自身也会弹同一张卡（`E2ECHK5556_15_t6.png`）。F12b 的增量在**行级 Reconnect 这条出口**——v94 之前该出口不弹卡。

---

## 2. F11 回归（宽 / 窄双形态）

### 2.1 宽窗（1366px/160dpi = **853dp**，≥450dp）CONNECTED 行单行形态 — PASS

`E2ECHK5556_44_result.png` 同帧内 V95D（Connected 态）：

| 部分 | bounds | y 带 |
| --- | --- | --- |
| 身份列 `V95D` | `[494,454]-[528,470]` | 454–486 |
| 状态 `Connected` | `[494,472]-[552,486]` | 454–486 |
| `Reconnect` | `[896,462]-[962,478]` | 454–486 |
| `Disconnect` | `[998,462]-[1067,478]` | 454–486 |
| `Server actions ⋮` | `[1075,446]-[1123,494]` | 454–486 |

→ 身份列（x 494–552）与按钮列（x 896–1067）**同处一个 y 带 = 同一个 Row，未分行、未裁切**。
3x 放大取证：`E2ECHK5556_45_zoom_wide_row.png`（`V95D / Connected` 在左，`Reconnect` + accent 蓝 `Disconnect` + `⋮` 在右，一行）。

### 2.2 窄窗（`wm size 560x768` = **350dp**，<450dp）堆叠形态 — PASS

`E2ECHK5556_97_narrow_v95d.png` / `ui_A97.xml`（V95D 行）：

| 层 | 元素 | bounds |
| --- | --- | --- |
| **第 1 行（身份列独占）** | `V95D` | `[76,480]-[110,496]` |
| | 状态文案 | `[76,498]-[330,512]` |
| **第 2 行（按钮）** | `Reconnect` | `[235,528]-[301,544]` |
| | `Disconnect` | `[353,528]-[422,544]` |
| | `Server actions ⋮` | `[446,510]-[494,558]` |

→ 身份列 y 带 **480–512**、按钮 y 带 **528–558**，**严格分行 = 堆叠形态**。
两条 V93P 行同构（`E2ECHK5556_46_narrow.png`、`_60`、`_64`），无例外。

**无裁切**（左右两侧都查了）：

- 右边界：最右元素 `⋮` 止于 **x=494**，行容器右缘 x≈504，窗口宽 560 → 余 56px
- 左边界：`V93P-ellipsis-probe-AAAAAABBBBCCCCDDDDEEEEFFFFGGGGHH…` 名字止于 **x=484**（`[76,383]-[484,399]`），长名按省略号截断而非被切边

### 2.3 ⚠ 口径说明（诚实标注，不算 PASS）

**窄窗截图里的 V95D 行是 `Disconnected` 态，不是 `Connected` 态。** 原因（下方「附带发现」1、2 有复现记录）：

1. `wm size` 变更会把**已建立的 SSH 隧道连接打掉**，行回落到 `Disconnected. The remote server is left running.`（复现 2 次：`_44` Connected → 改尺寸 → `_95` Disconnected）
2. 改尺寸后，窄窗里行级 `Reconnect` 点不动（详见「附带发现」2），因此无法在 350dp 下重新造出 CONNECTED 行

所以 **「CONNECTED 态 SSH 行在窄窗下的堆叠形态」这一子项没有直接取到 CONNECTED 态的窄窗截图**。
判定给 PASS 的依据是：该行的堆叠/单行分支由**窗口宽度**驱动（853dp→单行、350dp→堆叠），与连接阶段无关；同一份 dump 里两条 DISCONNECTED 行的堆叠结构完全一致，且宽窗 CONNECTED 行的单行结构已独立取证。**但这是推断，不是直证**，按需可复验补齐。

### 2.4 复验后已 `wm size reset`

```
$ adb -s emulator-5556 shell wm size
Physical size: 1366x768
$ adb -s emulator-5556 shell wm density
Physical density: 160
```
（`E2ECHK5556_105_reset_wide.png`；且在 reset 之后宽窗下 Reconnect 恢复正常，见 `E2ECHK5556_106` + journal `19:55:10` 有新建连接）

---

## 3. F12a 私钥 + promptPassphrase — SKIP

本轮时间预算全部消耗在 F12b 的认证卡链路与 F11 窄窗取直证上（认证卡有服务端 120s `LoginGraceTime` 硬约束，见「复现要点」），**未创建私钥条目、未做该项验证**。无证据，不作任何结论。

---

## 4. 清理 — PASS

| 项 | 证据 | 事实 |
| --- | --- | --- |
| 删 V95D | `E2ECHK5556_107_menu.png` → `_108_armed.png` → `_109_gone.png` | 确认条 `Delete SSH server? / This removes "V95D" from the list.`（Disconnected 态下**无** `Its tunnel is disconnected first.` 提示行，与 v94「该行按 `releaseFirst` 条件渲染」的结论一致） |
| 级联删 loopback 行 | `ui_A109.xml` | `127.0.0.1:43143` 随 V95D 一并消失 |
| 重进面板不复活 | `ui_A110_reenter.xml` | Close → Command palette → `Open settings` → `Servers` 后，**124 节点 / 41821 字节** |
| **与基线逐字节相同** | SHA256 比对 | `ui_A110_reenter.xml` = `ui_A03b_servers_baseline.xml`（v94 目录）= **`7C707C00D7430C9A165D25CB00B584B89419BBC13F6503A636AB0D217A80D29B`** → **哈希完全一致** |
| 面板恢复原样 | 同上 | 两条 v93 遗留 SSH 条目 + WS 服务器 `127.0.0.1:4095`（Default，绿点 healthy）全在；**v93 两条是开测前就存在的基线内容，删掉反而算破坏原样，故保留** |
| `wm size reset` | `E2ECHK5556_105_reset_wide.png` | 已执行并回读确认 1366x768 @160dpi |
| 未动其它设备 | — | 127.0.0.1:5555 / 5554 / 5558 / 5560 / 5562 全程未接触 |
| 未杀进程 | — | 本轮零次 `am force-stop` / `am kill` |

> 诚实标注：`E2ECHK5556_110_reenter.png` 是 **0 字节**——该次 screencap 与设备掉线同时发生。更强的证据 `ui_A110_reenter.xml`（哈希比对对象）完整落盘并已通过 SHA256 校验。

### 交设备时的状态

清理动作**全部完成并逐项验证完毕后**，`emulator-5556` 才从 adb 设备列表中消失：

```
$ adb devices          # 交设备时
127.0.0.1:5555   offline
emulator-5560    device
emulator-5562    device
$ adb connect 127.0.0.1:5556
cannot connect ... 目标计算机积极拒绝 (10061)
```

同批次 `emulator-5554` / `emulator-5558` 也一并消失、`127.0.0.1:5555` 转 offline，**是宿主侧多台模拟器一起掉的，不是本轮操作所致**——本轮全程零次 `am force-stop` / `am kill`，也从未对模拟器进程下过任何命令。

因此最后一步的 `wm size` / 面板内容复核是在掉线**之前**完成的：
- `wm size reset` → 1366x768 @160dpi（`E2ECHK5556_105_reset_wide.png`，掉线前约 20 分钟）
- 面板重进 → SHA256 与 v94 基线一致（掉线前最后一条落盘证据）
- 若要复验，请先确认 `emulator-5556` 已重新起来；掉线期间**无人改动过该机上的任何状态**。

---

## 附带发现（不在本轮复验项内，供参考）

### A. `wm size` 变更会打掉已建立的 SSH 隧道连接
`E2ECHK5556_44`（Connected，`127.0.0.1:46483` 在列）→ `wm size 560x768` → `E2ECHK5556_95`（V95D 变 `Disconnected`）。复现 2 次（含重建条目后重连 Connected 再改尺寸）。行回落文案为 `Disconnected. The remote server is left running.`。

### B. 改尺寸后，窄窗内 SSH 行的 `Reconnect` 按钮可点但不发连接
- dump 佐证按钮**未禁用**：`clickable="true" enabled="true"`，节点 `[221,554]-[315,602]`（同行 `Disconnect` 则是 `enabled="false"`，说明 enabled 位是有区分的）
- 点它之后：行状态无变化（连 `Connecting` 都没闪一下），`journalctl -u sshd --since '-60s'` **无任何新连接记录**
- 对**任何**行都一样：V93P 行（host `192.0.2.1`）的 `Reconnect` 同样无反应（`E2ECHK5556_104`）
- 但**宽窗下 Reconnect 是好的**：reset 尺寸后同一点击，journal `19:55:10` 出现新的连接超时记录（证明确实建了连）
- 未能定位根因（未读码、未加日志）。可能是 Activity 重建后条目连接状态机未复位。不影响 F12b 结论——F12b 是在**宽窗**下验的。

### C. WSL sshd 的 `pam_faillock` 会锁测试账号
```
18:41:21  pam_faillock(sshd:auth): Consecutive login failures for user sshtest account temporarily locked
```
F12b 需要先制造错密码，连续几次后账号被临时锁死，**此后连正确密码也会被拒**，表现为 `Could not establish the SSH connection.`。
`faillock --user sshtest --reset` 可清（本轮用过 3 次）。**这是测试环境坑，不是 app 缺陷**，但下一轮做同类验证前建议先 reset。

---

## 复现要点（务必先读，否则会误判成 app bug）

1. **认证卡有 120s 服务端硬时限。** sshd 的 `LoginGraceTime` 是 120s（journal 里成对出现 `Timeout before authentication ... pid=NNNNN`，恒为 PAM 失败后 +120s）。**从「点 Reconnect」到「点 Continue」必须在 120s 内完成**，否则即使密码正确也只会得到 `Could not establish the SSH connection.`。本轮前几次失败**全部**是这个原因（我一开始每步都 dump + 截图 + 读图，耗时 2~3 分钟），改成一条命令链死打（tap→1.2s→text→1.5s→tap Continue，总耗时 ~6s）后一次过。**这不是 app bug，是测试节奏问题。**

2. **认证卡不会自动聚焦密码框**，必须手动点。两种布局的坐标：
   - 无键盘：密码框 `(674,525)`、`Continue (848,570)`
   - 键盘弹起：密码框 `(674,225)`、`Continue (848,296)`

3. **窄窗里点任务栏「显示应用」钮 `(22,744)` 会拉起启动器抽屉**，之后所有点击都被吞掉，而 `uiautomator dump` **仍然只 dump app 自己的节点、看不出异常**——极易误判成「点击没生效」。再点一次同一个钮可收起。窄窗下设置导航变成**横向可滚动 tab bar**，竖排列表消失。

4. **`wm size` 覆盖后坐标会变**，每次操作前都要重新 dump 取坐标；列表滚动位置在切 tab 后会复位。

5. **读掩码密码长度要用 UTF-8 方式读 dump**：`U+2022` 被按 GBK 读时每个掩码点变成 3 个字符，所以「29」其实是 **9 个点**，不是 29 位。本轮因此一度以为密码多打了字符。

6. 窄窗的「Chromium 悬浮层抢焦点」老坑（v94 记的「点任务栏」）在 560 宽下坐标已变为 `(22,744)`，且点它会开抽屉——**改用「再点一次同一颗钮」来 toggle，不要用来清焦点**。

---

## 证据清单（关键项）

| 文件 | 说明 |
| --- | --- |
| `E2ECHK5556_03b_baseline.png` / `ui_A03b_baseline.xml` | 开测基线（124 节点 / 41821B） |
| `E2ECHK5556_13_pw_typed.png` / `ui_A13_pw.xml` | 错误密码 `badpw123`（8 掩码） |
| `E2ECHK5556_17_auth_required.png` | `Authentication required` 态（行级 Reconnect 的起点） |
| **`E2ECHK5556_18_reconnect_card.png` / `ui_A18_card.xml`** | **行级 Reconnect 弹出的面板内认证卡** |
| **`E2ECHK5556_19_kbd_up.png` / `ui_A19_kbd.xml`** | **键盘弹起态：整卡可见，footer 底边 y=304** |
| `E2ECHK5556_kbdedge.ps1` | 键盘上沿像素测量脚本（实测 342） |
| **`E2ECHK5556_44_result.png` / `ui_A44_result.xml`** | **Continue 后 Connected + loopback 行** |
| `E2ECHK5556_45_zoom_wide_row.png` | 宽窗 CONNECTED 单行 Row（3x 放大） |
| `E2ECHK5556_46/47/60/64/65/95/97_*.png` | 窄窗堆叠形态多帧 |
| `E2ECHK5556_105_reset_wide.png` | `wm size reset` 后 |
| `E2ECHK5556_108_armed.png` / `_109_gone.png` | 删除确认条 / 删除后 |
| **`ui_A110_reenter.xml`** | **重进面板，SHA256 = v94 基线** |
| `E2ECHK5556_sshd_journal.txt` | sshd journal（`Accepted keyboard-interactive/pam` 决定性证据） |
| `E2ECHK5556_nav.ps1` | 按文本 tap / 坐标 tap / 分段 input / dump+shot（serial 固定 emulator-5556） |
| `E2ECHK5556_kbdedge.ps1` | 逐行取色测键盘上沿 |
| `E2ECHK5556_zoom.ps1` | 裁剪放大取证 |
| `E2ECHK5556_diff.ps1` / `_pixel.ps1` / `_pwlen.ps1` | 区域像素差 / 取色 / 掩码位数（从 v94 沿用） |
