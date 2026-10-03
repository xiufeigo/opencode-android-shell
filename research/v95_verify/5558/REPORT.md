# V95 真机复验报告 — 复派轮次（emulator-5558 派单）

**包名** `com.xiufeigo.opencodeshell` · **复验项** F12a(promptPassphrase 留存答案) + F12b(行级 Reconnect 弹框出口 / Cancel offered-once)
**日期** 2026-10-02 · **本轮 agent** 重派（前任 2026-10-01 夜被取消）
**本轮结论：四项全部 BLOCKED（未验证），不是 FAIL。** 派单指定的 emulator-5558 上**没有装 app**，物理上无法执行任何一项。

---

## 0. 结论速览

| # | 复验项 | 本轮结论 | 原因 |
|---|--------|----------|------|
| 1 | **F12b Cancel offered-once 语义** | **BLOCKED** | 指定设备无 app |
| 2 | **F12b 键盘态闭环** | **BLOCKED** | 指定设备无 app |
| 3 | **F12a 加密私钥 passphrase 闭环** | **BLOCKED** | 指定设备无 app |
| 4 | **清理测试条目** | **BLOCKED** | 指定设备无 app |

**没有任何一项被判定为 FAIL。** 被验修复既未被证实、也未被证伪；阻塞点是设备派单与实际机队不符，不是被验代码。

> **本轮实际产出**：① 定位并量化了阻塞根因（端口↔AVD 错配）；② 把所有**不依赖设备归属**的环境侧条件全部核到绿（可直接用于改派后的秒跑）；③ 发现并修掉前任脚本里一处会导致**跨设备取证污染**的地雷；④ 产出设备无关的紧凑执行件。

---

## 1. 阻塞根因：派单的「5558 = Pixel_9a」已不成立

派单称 `emulator-5558` 是 Pixel_9a / 1080x2424@420dpi。实测**不是**。

本轮开工时 `adb devices` 有 5 台（**均为 2026-10-02 09:14:04 同批启动**，即今晨「全量恢复」那一批）：

| 端口 | 实际 AVD（按进程 cmdline 解析） | 屏幕 / 密度 | app 装机 | reverse 2222 | /sdcard/v95 |
|---|---|---|---|---|---|
| emulator-5554 | Small_Desktop 或 Medium_Desktop | 1366x768 @160 | ✅ | ❌ | ✅ (u0_a175) |
| **emulator-5556** | **Pixel_9a** | **1080x2424 @420** | **✅** | **✅** | **✅ (u0_a218)** |
| **emulator-5558（派单指定）** | **另一台桌面 AVD** | **3840x2160 @320** | **❌ 无** | ✅ | ✅ (u0_a183) |
| emulator-5560 | Pixel_10_Pro_Fold | 2076x2152 @390 | ✅ | ❌ | ❌ |
| emulator-5562 | Pixel_Tablet | 2560x1600 @320 | ✅ | ❌ | ❌ |

**决定性证据** —— 指定设备上 app 根本不存在：

```
$ adb -s emulator-5558 shell pm list packages -3 | Measure-Object
Count
-----
0                                    <-- 零个第三方包

$ adb -s emulator-5558 shell pm list packages | findstr xiufe|shell|code|opencode
package:com.android.shell           <-- 只有系统自带的 com.android.shell，不是我们的 app
```

- `wm size` = `Physical size: 3840x2160`，`wm density` = `320`，`ro.product.model` = `sdk_gpc_x86_64` —— 与 Pixel_9a 三个特征**全不匹配**。
- 派单里描述的「闸门 609/5506/0/0 绿、已装机」在 5558 上不成立。
- 前任 agent 其实撞到过同一堵墙：`E2ECHK5558_nav.ps1:6-8` 留了注释「Pixel_9a 现在在 emulator-5554；5558 是一台无关的 3840x2160 AVD，没有 app」。**今晨 09:14 的重启又把 Pixel_9a 从 5554 挪到了 5556** —— 端口映射每次重启都在漂移，派单里的端口号是隔夜的。

**满足派单全部设备身份条件的只有 emulator-5556**：Pixel_9a + 1080x2424@420 + app 已装 + reverse 2222 已配 + 私钥已推送（uid `u0_a218` 正是 Pixel_9a 上 app 的 uid，与前任报告 §3 记录的一致）。

**我没有动 5556。** 派单明确「127.0.0.1:5555 不是我的」「兄弟机 5556」，5556 属他人设备；抢用会把两边的取证同时污染。已做只读占用检查留证：5556 当前停在 launcher（`mCurrentFocus=com.google.android.apps.nexuslauncher/.NexusLauncherActivity`，`mInputShown=false`），**看起来空闲**，但"空闲"不等于"可抢"。

证据：`E2ECHK5558_PEEK_5556.png`（只读截图，5556 停在桌面）

---

## 2. 环境侧核验（不依赖设备归属，已全部收口为绿）

改派后可直接开跑，以下均为本轮实测：

| 项 | 结果 | 证据 |
|---|---|---|
| 后端 opencode @127.0.0.1:4095 | **HTTP 200**（Basic opencode:opencode） | 实测 |
| 设备侧 reverse `tcp:2222→22` | 已在 5556 与 5558 上配置 | `adb reverse --list` |
| Windows `:22` 可达 | `TcpTestSucceeded=True` | reverse 链路终点可达 |
| WSL sshd | **active** | `systemctl is-active sshd` |
| sshd 有效配置 | `UsePAM yes` / `PubkeyAuthentication yes` / `PasswordAuthentication yes` / `KbdInteractiveAuthentication yes` | `sshd -T` |
| `/etc/ssh/sshd_config.d/10-v94-kbdint.conf` | 仍在（v94 留下的） | `ls` |
| sshtest 账号 | 存在 uid=1001 | `id sshtest` |
| **pam_faillock 状态** | **干净，无任何锁记录**（不必先 reset） | `faillock --user sshtest` |
| 加密私钥 passphrase | **`keypass123` 正确** → 导出 `ssh-ed25519 ... v95-test` | `ssh-keygen -y -P keypass123` |
| 错误 passphrase 负对照 | **`wrongpp` 正确失败**（`incorrect passphrase supplied`） | `ssh-keygen -y -P wrongpp` |
| authorized_keys 公钥匹配 | 与上面导出的公钥**逐字一致** | `cat /home/sshtest/.ssh/authorized_keys` |
| 5556 上 app 版本 | `versionName=1.4.0` `versionCode=26` `lastUpdateTime=2026-10-01 10:16:31` | `dumpsys package` |

最后一行值得强调：**app 是 10-01 上午 10:16 装的**，与被验修复的装机时间吻合，5556 上的确实是**含 F12a/F12b 的构建**，改派后无需重装。

---

## 3. 发现的地雷：前任脚本会跨设备取证污染

`E2ECHK5558_nav.ps1:12` 的 `Shot()` 把设备号**写死**了，而同文件其余 helper 都走 `$global:DEV`：

```powershell
# E2ECHK5558_nav.ps1:12  —— 写死
cmd /c "...adb.exe -s emulator-5558 exec-out screencap -p > ..."
```

该文件第 5-8 行本意是让人用 `$env:E2E_DEV='emulator-5554'` 改派到真正的 Pixel_9a。**一旦这样改派，所有截图仍然来自 5558**（那台没 app 的桌面 AVD），而 `uiautomator dump` 来自真机 —— 截图与 bounds 来自两台不同设备，**像素证据会被静默伪造**。这是比"没跑完"严重得多的隐患：报告看起来有图有 bounds，实际两者不配对。

本轮已产出替代件 **`E2ECHK5558_run.ps1`**：全部 adb 调用统一走 `$Dev` 参数，无任何硬编码序列号；已用 PS 5.1 Parser 验证 **PARSE OK**、**非 ASCII 字节 = 0**（避开前任 §4.6 记录的 ANSI 解析坑）。

> 前任本轮没有产生被污染的证据：它的截图都摄于 19:35 之前，早于 20:05 的机队更换。污染只是**潜在**风险，未实际发生。

---

## 4. 对四项复验的处置

| 项 | 状态 | 说明 |
|---|---|---|
| 1 F12b Cancel offered-once | **BLOCKED** | 需在装了 app 的 Pixel_9a 上新建 `V95C` 错密码条目；5558 无 app，条目都建不出来 |
| 2 F12b 键盘态闭环 | **BLOCKED** | 同上 |
| 3 F12a 加密私钥闭环 | **BLOCKED** | 同上 |
| 4 清理测试条目 | **BLOCKED** | 同上 |

**没有把任何一项记成 FAIL。** 阻塞是派单层面的设备错配，与被验代码无关。

### 4.1 前任遗留的清理债务（仍然有效）

`REPORT_pred_20261001.md` §5.2 记录：Pixel_9a 的 AVD userdata 持久化，条目 **`V95R`**（错密码 SSH 条目）**很可能仍留在 Pixel_9a 数据里**。改派到 5556 后**第一件事应先删 `V95R`**，否则第 1 项的截图里会混进旧条目。两条 host 为空的废条目前任已删净。

---

## 5. 改派后最短执行路径（已备好件，待放行）

前提：把派单里的 `emulator-5558` 换成 `emulator-5556`（本报告 §1 已论证这是唯一满足条件的设备）。

```powershell
. C:\sensorsdata\main\program\opencode-android-shell\research\v95_verify\5558\E2ECHK5558_run.ps1 -Dev emulator-5556
ResetFaillock            # 当前已干净，跑前再确认一次
```

执行件已内建的关键约束（全部来自派单纪律 + 前任实证）：

- `TypeText` **不用 `%s`**，改用 `keyevent 62` 真空格，词间打、末词后不打（否则 target 被当远程命令，报 `Remote commands ... aren't allowed`）。
- `HideKb` 先查 `dumpsys input_method | grep mInputShown` 再决定是否发 ESC；**Settings 里禁用 ESC 收键盘**（会连整个对话框一起 dismiss）。
- `AnswerCard` 把「弹卡 → 点密码框起键盘 → 输密码 → 点 Continue」压成**一次紧凑调用**（字段 tap 500ms + 900ms 等待 + 分段 1500ms + Continue 300ms），专治派单点名的「SSH 服务端 auth 会话超时，慢手动会拖死会话」。
- `CancelCard` 在 Cancel 后额外等 **6 秒**再截图，专门证伪「同一 prompt 被立即重弹」，然后 `Nodes()` 落 bounds。
- `Rowscan` 逐行像素扫描测 Gboard 上沿与 footer 净空（Pixel_9a 期望值：footer 底 1368 / 键盘上沿 1450 / 净空 82px）。
- Pixel_9a 关键坐标已内置为默认（卡隐藏态 field c=(540,1641)、Cancel c=(643,1762)、Continue c=(866,1762)；键盘态 c=(540,1156)/(643,1347)/(866,1347)），但**每轮必须用 `Nodes()` 复核**，不盲信。

---

## 6. 给派单方的一句话

**请把本任务的设备从 `emulator-5558` 改派为 `emulator-5556`** —— 后者才是装了被验构建、且与派单描述完全吻合的 Pixel_9a（1080x2424@420）；`emulator-5558` 是一台 3840x2160@320 的桌面 AVD，**零个第三方包**，四项复验均无法执行。环境侧（后端 200、sshd active、三种认证全开、faillock 干净、密钥 passphrase 已双向验证、公钥已装）本轮已全部核绿，改派后可立即开跑。

---

## 7. 本轮证据清单

目录 `research/v95_verify/5558/`，全部 `E2ECHK5558` 前缀。

| 文件 | 内容 |
|---|---|
| **`REPORT.md`** | 本报告（当前结论） |
| `REPORT_pred_20261001.md` | **前任 2026-10-01 报告原文（完整保留，未删改）** |
| `E2ECHK5558_PEEK_5556.png` | 只读截图：5556 停在 launcher（占用检查） |
| `E2ECHK5558_run.ps1` | **本轮新增**：设备无关执行件（修掉跨设备截图 bug + 紧凑认证编排） |
| `E2ECHK5558_nav.ps1` | 前任脚本，**含 §3 所述硬编码地雷，勿直接改派使用** |
| `E2ECHK5558_rowscan.py` / `_nodes.py` / `_clickable.py` | 前任像素扫描 / 节点抽取 / 可点节点定位工具（可复用，无设备绑定） |
| `E2ECHK5558_R_26_submit_prompt.png` / `_R_29_reconnect_prompt.png` / `_R_30_kbup.png` | **前任已取到的核心像素证据**（弹卡出现 / 面板直渲染 / 键盘态净空 82px），本轮采信复用 |
| `E2ECHK5558_R_31_typed.png` | **0 字节**（前任设备掉线，③ 未发生） |
| `ui_E2ECHK5558_R_*.xml` | 前任各步 uiautomator dump（Pixel_9a 坐标来源） |

**前任成果采信说明**：本轮**复用**前任报告中与设备归属无关、且证据文件齐备的结论 —— ①行级 Reconnect 由面板直接渲染 SshAuthenticate 卡（`ui_E2ECHK5558_R_29_reconnect_prompt.xml`，卡 bounds `[106,1412][458,1459]`、Cancel `[588,1742][699,1783]`、Continue `[794,1742][938,1783]`）；②键盘态 footer 钉底，净空 **82px**（footer 底 1368 / 键盘上沿 1450）。**不采信**其 F12b ③、F12b-Cancel、F12a 三项 —— 那三项前任自己标注为「未验证/未跑到」，本轮同样未验证。
