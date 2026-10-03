# REPORT_SSH_PARSE2 — SSH target 解析修复真机复验（`emulator-5564`，重跑）

- 轮次：复验轮 #2（承接 `research/v97_verify/5564_v98/REPORT_SSH_PARSE.md` 的 BLOCKED）
- 设备：`emulator-5564`（Pixel_10_Pro_Fold，2076x2152@390，全屏手机态）
- 宿主：Windows 11 + WSL2 `Arch`（`HomeMINISFORUM`）
- 窗口：宿主 `13:56` – `14:10` CST（设备 `05:56` – `06:10` GMT）
- 纪律：全程 `adb -s emulator-5564`；**未跑 gradle、未装机、未杀进程**（未使用 `am force-stop`）；**未按 BACK(4)**、**未用 ESC(111)**（弹层一律用「取消」关闭）；截图仅 `screencap -p` + `adb pull`；坐标全部运行期从 `uiautomator dump --compressed` 解析，属性同时匹配 `text="…"`/`text='…'`；只动 `E2ECHK5564p*` 条目；**边做边写盘**。
- **全程未触发 `Application Not Responding`**，因此**未执行 `adb reboot`**。

> ## 状态：**PASS —— 修复在真机上生效**
>
> | 用例 | 判定 | 关键证据 |
> |---|---|---|
> | **构建核对** | ✅ **通过** | md5 `6be68718…` **32/32 位全等**；`lastUpdateTime` **全等** |
> | **A `sshtest@127.0.0.1:2222`（`user@host:port`）** | ✅ **PASS** | `已连接` + sshd `Accepted keyboard-interactive/pam` + **耗时 ~7s（非秒失败）** |
> | **B `ssh -p 2222 sshtest@127.0.0.1`** | ✅ **PASS** | `已连接` + sshd `Accepted …` + 耗时 ~5s（**回归闸通过**） |
> | **C 畸形 `@@@`** | ✅ **PASS** | `连接失败` + `请输入主机或 SSH 连接命令。…`（`ssh_error_input`），**非静默** |
> | **C2 畸形 `sshtest@127.0.0.1:`（判别输入）** | ✅ **PASS（P2 闭环的决定性证据）** | `ssh_error_input`，**不再是**误导性的「检查网络」文案 |
> | **D 清理** | ✅ 完成 | `E2ECHK5564p*` 4 条删净；`/sdcard` 本轮零写入；`known_hosts`/`reverse` 一条未动 |
>
> **上一轮 BLOCKED 的两个理由均已消除**：
> ① 装机 md5 与 Lead 指定值**逐位一致**（`6be68718…`），`lastUpdateTime` **逐位一致**（`2026-10-03 05:55:28`）；
> ② A 的行为学证据与修前症状**不再复现**（修前：秒失败 + sshd 零日志；本轮：连上 + `Accepted`）。
>
> **额外收获**：上一轮指出任务书给的 `@@@` **不具判别力**（两版解析器都会拒）。
> 本轮补测了修复文档 §7 的**判别输入** `sshtest@127.0.0.1:`，**P2 在 target 一侧已闭环**。

---

## 0. 构建核对（本轮第一件事 / 上一轮 BLOCKED 的根因）

### 0.1 设备侧读数

```console
$ adb -s emulator-5564 shell "dumpsys package com.xiufeigo.opencodeshell | grep -E 'lastUpdateTime|versionName|versionCode'"
    versionCode=26 minSdk=26 targetSdk=35
    versionName=1.4.0
    lastUpdateTime=2026-10-03 05:55:28
      firstInstallTime=2026-09-22 02:15:37
```

### 0.2 APK 二进制 md5（设备侧 + 宿主 pull，双路互证）

```console
$ adb -s emulator-5564 shell pm path com.xiufeigo.opencodeshell
package:/data/app/~~e3-yTXwgY9DelTLJ78Qibg==/com.xiufeigo.opencodeshell-YpHBF6FwNVZ5vki73J5D3A==/base.apk

$ adb -s emulator-5564 shell "md5sum /data/app/~~e3-yTXwgY9DelTLJ78Qibg==/com.xiufeigo.opencodeshell-YpHBF6FwNVZ5vki73J5D3A==/base.apk"
6be687187ae4c246472aa9aca96a379f  .../base.apk

$ adb -s emulator-5564 pull …/base.apk $env:TEMP\base5564.apk ; Get-FileHash -Algorithm MD5
HOST md5: 6be687187ae4c246472aa9aca96a379f
SIZE: 44198253
```

> `adb` 不在 PATH，须用全路径 `C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe`（与上一轮记录一致）。

### 0.3 逐位对照表

| 项 | Lead 指定值 | 设备实测 | 逐位 |
|---|---|---|---|
| **APK md5** | `6be687187ae4c246472aa9aca96a379f` | `6be687187ae4c246472aa9aca96a379f` | ✅ **32/32 位全等** |
| **lastUpdateTime** | `2026-10-03 05:55:28` | `2026-10-03 05:55:28` | ✅ **全等** |

### 0.4 与上一轮 BLOCKED 的逐项对比

| | 上一轮 `REPORT_SSH_PARSE.md` | **本轮** |
|---|---|---|
| 设备 APK md5 | `6FDAFC9820BC9142FBFDB8FE6F79A411`（**旧包**） | **`6be687187AE4C246472AA9ACA96A379F`** ✅ |
| `lastUpdateTime` | `2026-10-03 05:11:22` | **`2026-10-03 05:55:28`**（晚 44 分 06 秒）✅ |
| A `user@host:port` | ❌ `连接失败`，**秒级**，sshd `-- No entries --` | ✅ **`已连接`**，`Accepted …`，**~7s** |
| C2 尾冒号 | 通用网络文案 ⇒ **修前行为** | ✅ **`ssh_error_input`** ⇒ **修后行为** |

> ✅ **构建核对通过 ⇒ 未被 BLOCKED，继续复验。**
> 注：上一轮记录的仓库 `app-debug.apk` md5 `7A9D1E4B4E78397301A5799E1E38D19F`（13:32:40 产出）
> **不等于**本轮 `6be68718…`；Lead 在此后又重新构建并安装，以 Lead 指定值为**唯一标尺**。

---

## 1. 环境读数

### 1.1 宿主 sshd / 账号

```console
$ wsl -d Arch -e bash -lc "sudo ss -ltnp 2>/dev/null | grep -E ':22\b'"
LISTEN 0      128           0.0.0.0:22        0.0.0.0:*    users:(("sshd",pid=336,fd=6))
LISTEN 0      128              [::]:22           [::]:*    users:(("sshd",pid=336,fd=7))

$ wsl -d Arch -e bash -lc "sudo faillock --user sshtest 2>&1 | tail -6"
sshtest:
When                Type  Source                                           Valid
（仅表头、无 `V` 行 ⇒ **未锁定** ✅ ⇒ 本轮**未**执行 `--reset`，宿主侧零状态变更）
```

### 1.2 设备链路

```console
$ adb -s emulator-5564 reverse --list
host-15 tcp:4095 tcp:4095
host-15 tcp:2222 tcp:22          ✅ 2222→宿主 22

$ adb -s emulator-5564 shell "toybox netstat -ltn | grep -E '2222|4095'"
tcp6       0      0 [::]:4095               [::]:*                  LISTEN
tcp6       0      0 [::]:2222               [::]:*                  LISTEN
```

> 与上一轮一致：`reverse` 表**没有** `tcp:22` ⇒ 设备上 `127.0.0.1:22` 无人监听。
> **全部成功连接都必须走 2222**，这正是 A 的判读前提。

### 1.3 时钟对齐（设备 = 宿主 − 8h）

```console
$ adb -s emulator-5564 shell date
Sat Oct  3 05:57:05 GMT 2026
$ Get-Date
2026-10-03 13:57:05
```

> ⇒ 设备 `lastUpdateTime 05:55:28` = 宿主 **13:55:28** CST，**早于**本轮开测（13:56），时序自洽。

### 1.4 设备私有目录

```console
$ adb -s emulator-5564 shell "run-as com.xiufeigo.opencodeshell cat files/ssh/known_hosts"
[127.0.0.1]:2222 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIP4xhCsSfuwShy6+t+va8TMG1R1brte/e+EImnSO8sQM
```

> ⚠️ **上一轮遗留的 `known_hosts` 条目仍在**（按纪律**未删**）。
> **这对 A 的判读有影响，必须声明**：因为 `[127.0.0.1:2222` 已登记，
> A **不会**弹 `Verify host identity` 核验卡（省掉人工点「信任并连接」），直接进入认证。
> 因此判据 ①（口令卡/核验卡）由「弹卡」收敛为
> **「sshd 出现 `Accepted` + UI 终态」** —— 后者是**更强的证据**（服务端正面确认收到了连接）。
> ⚠️ 副作用：核验卡**未被本轮验证**（该 UI 路径本轮未被触发）—— 见 §6.4。

### 1.5 认证方式

全程使用**账号口令**（`Authentication = 密码（可选）`，`opencode`），
按任务书要求**规避 P3**（私钥认证退化弹 `Password:` 卡）。

---

## 2. 导航路径（运行期从 dump 解析）

```
首页 → 设置 (154,1921) → 服务器 (178,1443) → 添加 SSH 服务器 (1700,602)
```

- 表单：**Host + Name 填完后才渲染认证区**（`SSH_FORM_REACHABILITY_FIX` 未被弄坏 ✅）。
- IME 弹起时表单不滚动，靠 **`keyevent 61`（TAB）焦点路由**逐字段下移（与上一轮同一路径）。
- **Host 字段逐字读回校验**（防 `input text` 转义问题），本轮 3 条全部一致。

---

## 3. 用例 A —— `sshtest@127.0.0.1:2222`（`user@host:port`）✅ **PASS**

### 3.1 建条目（口令认证）

| 步 | 动作 | 运行期读数 |
|---|---|---|
| 1 | Host = `sshtest@127.0.0.1:2222` | `EditText ctr=(1211,1094) fo=true t='sshtest@127.0.0.1:2222'` ✅ **逐字一致** |
| 2 | `keyevent 61` → Name | 焦点落到空 EditText ✅ |
| 3 | Name = `E2ECHK5564p-colon` | `EditText t='E2ECHK5564p-colon'` ✅ |
| 4 | `keyevent 61` ×2 → 口令 | `EditText t='•••••••'`（7 字符 = `opencode`）✅ |
| 5 | 认证方式 | 渲染 `密码（可选）` 分支 ⇒ **Password** ✅ |
| 6 | 点 `添加服务器` (1625,1141) | 宿主时间 **`T_SUBMIT = 13:59:04`** |

### 3.2 判定：**✅ 连上**

运行期 dump（UTF-8 经 base64 取回，`读数时刻 ≈ 13:59:20`）：

```
ctr=(801,862)  t='SSH 隧道'                  ← 出现「SSH 隧道」区段
ctr=(930,995)  t='E2ECHK5564p-colon'
ctr=(812,1038) t='已连接'                    ← ⭐ Connected
ctr=(1505,1017) t='重新连接'                 ← Reconnect
ctr=(1717,1017) t='断开连接'                 ← Disconnect
ctr=(931,1232) t='127.0.0.1:44187'           ← 隧道本地端口
ctr=(867,1273) t='sshtest'                   ← ⭐ 用户
```

**状态文案原文**（截图同刻）：

- 行状态：**`已连接`**（`Connected`）
- 隧道：`127.0.0.1:44187`
- 用户：`sshtest`
- 按钮：`重新连接`（Reconnect）/ `断开连接`（Disconnect）

> 页面**没有** `连接失败`（Connection failed）、
> **没有** `无法建立 SSH 连接。请检查网络和 SSH 配置。`（Check your network and SSH configuration）、
> **没有** `取消`/`连接` 错误弹层。**错误文案完全消失。**

### 3.3 决定性证据：sshd 正面确认 + **耗时 ~7 秒（非秒失败）**

```console
$ wsl -d Arch -e bash -lc "sudo journalctl -u sshd --since '13:58' --no-pager | grep -Ev '^wsl:'"
Oct 03 13:59:11 HomeMINISFORUM sshd-session[51100]: Accepted keyboard-interactive/pam for sshtest from 127.0.0.1 port 59676 ssh2
Oct 03 13:59:11 HomeMINISFORUM sshd-session[51100]: pam_unix(sshd:session): session opened for user sshtest(uid=1001) by sshtest(uid=0)
```

**耗时对照（任务书要求的「秒失败 vs 超时」两类证据）**

| 事件 | 宿主时间 | 相对提交 |
|---|---|---|
| 点 `添加服务器` | `13:59:04` | T+0 |
| **sshd `Accepted keyboard-interactive/pam`** | **`13:59:11`** | **T+7s** |
| UI 终态 `已连接` | `~13:59:20`（读数时刻） | T+16s（含 4s+ 轮询开销） |

| 写法 | 修前（上一轮旧包） | **本轮（修复版）** |
|---|---|---|
| `sshtest@127.0.0.1:2222` | `连接失败`，**秒级**（≤6s 终态），sshd **`-- No entries --`** | ✅ `已连接`，**~7s**，sshd **`Accepted … for sshtest`** |

> ### 🎯 **「是否秒失败」的判定：否。耗时 ~7 秒，且 sshd 记录到 `Accepted`**
>
> - **秒失败**的特征是：UI 瞬时进终态 + **sshd 零日志**（连接从未抵达 22）⇒ 解析出的 port 不是 2222。
> - **本轮**：sshd **记录到 `Accepted … for sshtest`** ⇒ TCP 确实抵达宿主 sshd 的 22（经 `adb reverse 2222→22`），
>   认证也走完了。**⇒ `:2222` 被正确剥离成 `port=2222`。**
>
> 这正是 `SSH_TARGET_PARSE_FIX.md` §7 用例 A 第 3/6 步要求的决定性对比：
> **修前 = `Connection failed` + 零日志；修后 = 连上 + `Accepted …`。**

### 3.4 A 的判定表

| 判据 | 结果 | 证据 |
|---|---|---|
| ① **不再秒失败** | ✅ **耗时 ~7s**，sshd 出现 `Accepted … for sshtest`（TCP 已到 sshd） | §3.3 |
| ①' 出现口令卡 / 核验卡 | ➖ 未出现（`known_hosts` 已登记 ⇒ 无核验卡；口令存于条目 ⇒ 无口令卡）。**由更强的 sshd `Accepted` 取代** | §1.4 / §3.3 |
| ② 最终进入 **`Connected`** | ✅ **`已连接`** | §3.2 |
| ③ sshd 有 `Accepted` | ✅ `Accepted keyboard-interactive/pam for sshtest from 127.0.0.1 port 59676 ssh2` | §3.3 |

**A = PASS。P1 已在真机上闭环。**

---

## 4. 用例 B —— `ssh -p 2222 sshtest@127.0.0.1`（回归闸）✅ **PASS**

### 4.1 建条目

Host = `ssh -p 2222 sshtest@127.0.0.1`（`input text` 空格写 `%s`），
运行期读回 **`t='ssh -p 2222 sshtest@127.0.0.1'`** ✅ **逐字一致**；
Name = `E2ECHK5564p-dashp`，口令 `opencode`（7 字符）✅，认证 = Password ✅。

### 4.2 判定：**✅ 连上**

```
ctr=(801,862)  t='SSH 隧道'
ctr=(933,1135) t='E2ECHK5564p-dashp'
ctr=(812,1178) t='已连接'                    ← ⭐ Connected
ctr=(1505,1157) t='重新连接'
ctr=(1717,1157) t='断开连接'
ctr=(931,1372) t='127.0.0.1:36471'           ← 隧道本地端口
ctr=(867,1413) t='sshtest'
```

**状态文案原文**：行状态 **`已连接`**（`Connected`）；隧道 `127.0.0.1:36471`；用户 `sshtest`。

### 4.3 sshd 证据

```console
$ wsl -d Arch -e bash -lc "sudo journalctl -u sshd --since '14:02' --no-pager | grep -Ev '^wsl:'"
Oct 03 14:03:19 HomeMINISFORUM sshd-session[51545]: Accepted keyboard-interactive/pam for sshtest from 127.0.0.1 port 35640 ssh2
Oct 03 14:03:19 HomeMINISFORUM sshd-session[51545]: pam_unix(sshd:session): session opened for user sshtest(uid=1001) by sshtest(uid=0)
```

提交 `14:03:14` → `Accepted` `14:03:19` = **~5s**。

设备侧 TCP 连接实证：

```console
$ adb -s emulator-5564 shell "toybox netstat -tn | grep 2222"
tcp6  0  0  ::ffff:127.0.0.1:37214  ::ffff:127.0.0.1:2222  ESTABLISHED
tcp6  0  0  ::ffff:127.0.0.1:2222   ::ffff:127.0.0.1:44844  ESTABLISHED
```

### 4.4 B 的判定：**回归闸通过** ✅

> **B 的意义**：证明新增的 `:port` 路径**没有抢走** `-p` 的语义。
> 修后代码的 `optionPort ?: hostPort ?: SSH_DEFAULT_PORT` 优先级在本例下
> `optionPort` 命中（`host` 无 `:port` 后缀）⇒ 与修前行为一致。
> **两条写法（A 的 `:port`、B 的 `-p`）在同一构建上同时连上 ⇒ 解析器两条路径都工作。**

> ⚠️ 仍未覆盖：`-p` 与 `:port` **同时出现**时的优先级
> （`ssh -p 2222 sshtest@127.0.0.1:2200` 应取 **2222**）—— 见 §6.2。

---

## 5. 用例 C —— 畸形输入的可诊断性 ✅ **PASS**

### 5.1 C（任务书指定 `@@@`）

条目 `E2ECHK5564p-bad`，提交 `14:04:45`，运行期读数：

```
ctr=(917,1079)  t='E2ECHK5564p-bad'
ctr=(825,1109)  t='连接失败'                                            ← Connection failed
ctr=(1081,1170) t='请输入主机或 SSH 连接命令。不允许使用远程命令和不支持的 SSH 选项。'   ← ssh_error_input
ctr=(1116,988)  t='请输入主机或 SSH 连接命令。不允许使用远程命令和不支持的 SSH 选项。'
ctr=(737,1054)  t='连接失败'
ctr=(1522,1141) t='取消'      ctr=(1672,1141) t='连接'
```

**状态文案原文**：

- 行状态：`连接失败`（`Connection failed`）
- 错误句：**`请输入主机或 SSH 连接命令。不允许使用远程命令和不支持的 SSH 选项。`**
  （`ssh_error_input` = `Enter a host or SSH connection command. Remote commands and unsupported SSH options aren't allowed.`）

**判定：✅ 通过「不得静默无反应」这一条。**

> ⚠️ **`@@@` 仍不具判别力**（上一轮已指出，本轮复核确认）：`@@@` 的 `lastIndexOf('@')=2`
> ⇒ `hostPart = ""` ⇒ `host.isBlank()` ⇒ **修前**解析器也返回 `null` ⇒ 同样落到 `ssh_error_input`。
> 它是**非判别性输入**。故补测 C2。

### 5.2 C2（`sshtest@127.0.0.1:`，修复文档 §7 的**判别输入**）✅ **PASS —— P2 闭环的决定性证据**

条目 `E2ECHK5564p-trailing`，提交 `14:05:41`，运行期读数：

```
ctr=(939,1415)  t='E2ECHK5564p-trailing'
ctr=(825,1249)  t='连接失败'                                                    ← Connection failed
ctr=(1116,988)  t='请输入主机或 SSH 连接命令。不允许使用远程命令和不支持的 SSH 选项。'  ← ⭐ ssh_error_input
ctr=(737,1054)  t='连接失败'
ctr=(1522,1141) t='取消'      ctr=(1672,1141) t='连接'
```

**判别逻辑对照**

| 版本 | `sshtest@127.0.0.1:` 的解析 | 应给文案 | **本轮实测** |
|---|---|---|---|
| **修前**（`else -> hostPart` 原样） | `host="127.0.0.1:"` + `port=22` ⇒ 拨号失败 | `无法建立 SSH 连接。请检查网络和 SSH 配置。` | — |
| **修后**（`splitSshHostPort`） | 尾冒号读不出端口 ⇒ **拒绝** | `请输入主机或 SSH 连接命令。…`（`ssh_error_input`） | ✅ **正是这一句** |

> ### 🎯 **上一轮 C2 给的是「检查网络」通用文案（修前行为）；本轮给的是 `ssh_error_input`（修后行为）。**
> 这与 §0.4 的构建核对证据**完全一致**，两条独立证据互相印证。
> ⇒ **`splitSshHostPort` 的「读不出端口就拒绝、绝不回落 22」在真机上生效。**

**C 与 C2 的判定表**

| 项 | 判定 |
|---|---|
| 畸形输入给出**可见**失败反馈 | ✅ 是（`@@@` 与尾冒号两条**都有**） |
| **不得静默无反应** | ✅ 通过 |
| 反馈**指向 target**（`ssh_error_input`）而非「检查网络」 | ✅ **C2 通过**（上一轮 ❌）⇒ **P2 在 target 一侧闭环** |

### 5.3 C/C2 的 sshd 侧：**零日志**（正确）

```console
$ wsl -d Arch -e bash -lc "sudo journalctl -u sshd --since '14:04' --no-pager | grep -Ev '^wsl:'"
-- No entries --
```

> 两条畸形输入都被**在拨号前**拒绝 ⇒ 连接从未抵达 sshd。
> 这正是 `SSH_TARGET_PARSE_FIX.md` §7 用例 C 第 2 步的预期（"仍零日志（正确：拨号前就被拒，没到网络）"）。

### 5.4 app 侧日志：仍无 SSH 线索（**P2 残留项**）

```console
$ adb -s emulator-5564 logcat -d | Select-String 'UnknownHost|SocketException|IOException|jsch|JSch|Refused|Exception|Ssh'
10-03 06:04:50.603 18064 18064 E FeatureFlagsImplExport: android.os.flagging.AconfigStorageReadException: ERROR_PACKAGE_NOT_FOUND: package android.xr cannot be found on the device
（全窗口 1214 行；唯一命中是系统 XR 包缺失，**与 SSH 无关**）

$ adb -s emulator-5564 logcat -d | Measure-Object   # 1214 行
```

> ⇒ app 侧**仍不打任何 SSH 相关日志**。UI 文案已正确（P2 的**用户可见部分**闭环），
> 但**日志可诊断性**（运维侧 P2）**未闭环** —— 见 §6.1。

---

## 6. 清理

### 6.1 条目：全部删净 ✅

`E2ECHK5564p*` 共 4 条，逐条走「行 `服务器操作` → `删除` → 确认条」：

| # | 条目 | 结果 |
|---|---|---|
| 0 | `E2ECHK5564p-colon` | 已删除 |
| 1 | `E2ECHK5564p-dashp` | 已删除 |
| 2 | `E2ECHK5564p-bad` | 已删除 |
| 3 | `E2ECHK5564p-trailing` | 已删除 |
| 4 | — | `no E2ECHK5564p* rows left` ✅ |

删除入口坐标（运行期解析，`服务器操作` = content-desc）：`(1857,1017/1157/1297/1437)`；
菜单项 `删除`（popup 单独窗口，dump 仅含 `重新连接` + `删除`）。

**终态读数**（UI XML 中 `E2ECHK` 命中数）：

```console
"E2ECHK hits: 0"
```

服务器列表只剩两条**轮前既有**条目，`SSH 隧道` 区段已随 A/B 两条目删除而消失 ✅：

```
ctr=(926,894)  t='127.0.0.1:4096'
ctr=(902,935)  t='no username'
ctr=(1762,913) t='默认'
ctr=(925,1088) t='127.0.0.1:4095'
ctr=(941,1129) t='v2.0.18 • opencode'
ctr=(783,1107) t='服务器正常'
```

> B 建立的隧道 `127.0.0.1:36471` / `sshtest` 已随条目删除而消失 ✅。

### 6.2 `/sdcard`：**本轮零写入** ✅

```console
$ adb -s emulator-5564 shell "ls -la /sdcard/ | grep '2026-10-03'"
-rw-rw---- 1 u0_a204  media_rw   35307 2026-10-03 03:41 u_t1.xml
```

> ⚠️ **如实记录**：该文件是**更早轮次**的遗留物，**不是本轮产生的**。
> 判据：其设备时间 `03:41` = 宿主 `11:41` CST，**早于本轮开测（13:56 CST）约 2h15m**；
> 本轮全部 dump 写在 `/data/local/tmp/`，截图经 `adb pull` 落宿主 `%TEMP%\e2echk5564\shots\`，
> **`/sdcard` 本轮零写入**。
> 按纪律「只动自建条目 / 本轮残留」，**未删除**该他轮文件。
> 目录其余文件日期均为 `2026-09-22` … `2026-09-27`，无本轮新增。

### 6.3 `/data/local/tmp`：本轮临时文件已删净 ✅

```console
$ adb -s emulator-5564 shell "rm -f /data/local/tmp/d.xml /data/local/tmp/d2.xml /data/local/tmp/s.png /data/local/tmp/window.xml"
$ adb -s emulator-5564 shell "ls -la /data/local/tmp/"
total 24
drwxrwx--x 3 shell shell  4096 2026-10-03 06:09 .
drwxr-x--x 5 root  root   4096 2026-09-22 02:13 ..
drwxrwxrwx 3 shell shell  4096 2026-09-22 02:15 dalvik-cache
（仅剩系统目录）
```

### 6.4 按要求**未**动的两项 ✅

```console
$ adb -s emulator-5564 shell "run-as com.xiufeigo.opencodeshell cat files/ssh/known_hosts"
[127.0.0.1]:2222 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIP4xhCsSfuwShy6+t+va8TMG1R1brte/e+EImnSO8sQM
（**保留未清** ✅ —— 上一轮 `Trust and connect` 写入，属既有行为，其他轮依赖）

$ adb -s emulator-5564 reverse --list
host-15 tcp:4095 tcp:4095
host-15 tcp:2222 tcp:22
（**一条未动** ✅）
```

宿主侧本轮**零状态变更**（`faillock` 原本干净，**未**执行 `--reset`；**未**碰 sshd 配置）。

### 6.5 截图产物（宿主 `%TEMP%\e2echk5564\shots\`）

| 文件 | 内容 |
|---|---|
| `A_connected.png` | 用例 A 终态 `已连接` + `127.0.0.1:44187` / `sshtest` |
| `B_connected.png` | 用例 B 终态 `已连接` + `127.0.0.1:36471` / `sshtest` |
| `C2_error.png` | 用例 C2 错误弹层：红色 `请输入主机或 SSH 连接命令。不允许使用远程命令和不支持的 SSH 选项。` + `连接失败` + `取消`/`连接` |
| `D_after_cleanup.png` | 清理终态：仅两条轮前既有条目，无 `E2ECHK5564p*` |

> ⚠️ **如实记录取证过程**：`C2_error.png` 是**补拍的**。
> 首次截图时 `adb pull` 静默失败（未落盘），**当时未察觉即已写报告**；
> 收尾核对截图清单时发现缺失，遂**重建 C2 条目**补拍并再次删净。
> 文字证据（`rows_c1.txt` / `rows_c2.txt` 中的逐字文案）取自**首次运行**，未受补拍影响。
> 当前状态：**`E2ECHK` 命中 0**，补拍条目已删除（§6.1 iter 4/5）。

---

## 7. 未闭环

1. **P2 运维侧（日志）未闭环。** UI 文案已正确（P2 用户可见部分闭环），
   但 app 侧**仍不打任何 SSH 相关日志**（全窗口 1214 行，0 条 SSH 记录，§5.4）。
   `sshFailure()` 仍无 `java.net.UnknownHostException` 分类
   （`SSH_TARGET_PARSE_FIX.md` §8.3 同项）。若要闭环需新增 `unknown-host` 句子 ⇒ **属新增三语字符串，需单独决策**。
2. **`-p` 与 `:port` 同时出现时的优先级未测。** 修后 `optionPort ?: hostPort ?: 22` 的
   冲突场景 `ssh -p 2222 sshtest@127.0.0.1:2200`（应取 **2222**）本轮**未测**
   （需在 2200 上另起一个监听才能做出可判读结果）。A/B 各自通过**不覆盖**该分支。
3. **「不带 port ⇒ 22」仍缺正向证据。** 与上一轮同因：`adb reverse` 缺 `tcp:22`，
   设备上 `127.0.0.1:22` 无人监听 ⇒ 无法在设备上正面验证「无 port 落 22」。
   要验证需加 `adb reverse tcp:22 tcp:22`（**属环境变更，本轮纪律未授权**）。
4. **P3（私钥退化）本轮未触碰。** 全程口令认证（按任务书规避），
   `Accepted publickey` 在 5564 上本轮**从未出现**（唯一 `Accepted` 是 `keyboard-interactive/pam`）。
   这与 P3 描述一致，但**不构成独立验证**。
5. **核验卡（`Verify host identity`）本轮未被触发。** 因 `known_hosts` 已登记
   `[127.0.0.1]:2222`（§1.4），A/B 都**跳过**了该卡。
   ⇒ `HOST_KEY_CARD_FIX`（`取消` / `信任并连接` 两按钮、指纹展示）在本构建上**未被验证**。
   若要验证需清 `known_hosts`（**本轮纪律明确要求不动**）。
6. **IPv6 / 连写式 `-p2222` 未在真机上测。** 修复文档 §2.2 列的
   `user@[2001:db8::1]:2222`、`ssh -p2222 devbox`、`2001:db8::1`（裸 IPv6 不拆）
   等分支**仅有 JVM 单测覆盖**（`SshTargetParseV99Test`），真机侧未跑。
7. **`/sdcard/u_t1.xml` 为他轮遗留**（§6.2），非本轮产生，本轮未删 —— 需由其归属轮次清理。

---

## 8. 结论

> ## **判定：PASS。被测构建 = Lead 指定构建；P1 已闭环；B 未退化；C/C2 可诊断。**
>
> | 缺陷 | 来源 | 本轮状态 |
> |---|---|---|
> | **P1** `user@host:port` 静默丢弃 | `REPORT_SSH_DIAG.md` §5.b | ✅ **已闭环**（A 连上，sshd `Accepted`，~7s） |
> | **P2** 畸形 target 被报成「检查网络」 | 同上 | ✅ **target 一侧已闭环**（C2 给 `ssh_error_input`）；⚠️ **日志侧未闭环** |
> | **P3** 私钥退化弹 `Password:` 卡 | 同上 | ⏸ **未触碰**（本轮口令认证规避） |
> | **回归** `-p` 语义 | 修复文档 §7 用例 B | ✅ **未退化**（B 连上） |
>
> **修后的相对优势再次成立**：`REPORT_SSH_DIAG.md` §1.2 记录上游 OpenSSH 对
> `user@host:port` 直接报 `Could not resolve hostname 127.0.0.1:2222`；
> 本壳修复后**真连上**（sshd 有 `Accepted … for sshtest`）。

**建议下一步**（按优先级）：
1. 决策是否新增 `unknown-host` 三语句子，把 P2 的日志侧一并闭环（§7.1）；
2. 授权加 `adb reverse tcp:22 tcp:22` 以验证「无 port ⇒ 22」与核验卡路径（§7.3、§7.5）；
3. 安排 P3（私钥退化）修复轮（§7.4）。

---

## 附：复现命令清单

```powershell
$env:PATH = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools;" + $env:PATH

# §0 构建核对
adb -s emulator-5564 shell "dumpsys package com.xiufeigo.opencodeshell | grep -E 'lastUpdateTime|versionName'"
adb -s emulator-5564 shell pm path com.xiufeigo.opencodeshell
adb -s emulator-5564 shell "md5sum /data/app/~~e3-yTXwgY9DelTLJ78Qibg==/com.xiufeigo.opencodeshell-YpHBF6FwNVZ5vki73J5D3A==/base.apk"

# §1 环境
wsl -d Arch -e bash -lc "sudo ss -ltnp | grep -E ':22\b'"
wsl -d Arch -e bash -lc "sudo faillock --user sshtest"
adb -s emulator-5564 reverse --list
adb -s emulator-5564 shell date

# §5 精确文案读数（避免控制台编码损坏：dump → base64 → UTF-8）
adb -s emulator-5564 shell "uiautomator dump --compressed /data/local/tmp/d.xml"
adb -s emulator-5564 shell "base64 -w0 /data/local/tmp/d.xml"   # 宿主 [Text.Encoding]::UTF8.GetString(...)

# §3/§4 sshd 正面确认
wsl -d Arch -e bash -lc "sudo journalctl -u sshd --since '-5 min' --no-pager | grep -Ev '^wsl:'"

# §6 清理核验
adb -s emulator-5564 shell "run-as com.xiufeigo.opencodeshell cat files/ssh/known_hosts"
adb -s emulator-5564 reverse --list
adb -s emulator-5564 shell "ls -la /sdcard/ | grep '2026-10-03'"
```

> **方法学备注（供后续轮次）**：设备 `uiautomator dump` 的中文经 `adb shell cat` 直取会**按控制台代码页损坏**；
> 必须用 `adb shell base64 -w0 <file>` 取回再 `[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(...))` 解码，
> 否则「状态文案原文」这一交付要求无法满足。上一轮报告中出现的 `已连�?` 等截断即由此导致。

### 本轮落盘的复验脚本（与本报告同目录）

| 脚本 | 用途 | 状态 |
|---|---|---|
| `add.ps1` | 建 SSH 条目：**运行期解析** Host/Name/口令字段、**逐字回读校验**、TAB 焦点路由、定位 `添加服务器` 并提交 | ✅ 可用 |
| `clean2.ps1` | 按 `E2ECHK5564p*` 前缀逐条走「`服务器操作` → `删除` → 确认条」删净（幂等，循环至无匹配） | ✅ 可用 |
| `rd.ps1` | 屏幕 dump → 带坐标的文本行（文本节点去重） | ✅ 可用 |

> 已可恢复地删除本轮两个中间产物：`clean.ps1`（kebab 定位逻辑有 bug，实测不生效）与
> `dump.ps1`（被 `rd.ps1` 取代），**不留给后续轮次以免误用**。
