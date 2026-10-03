# REPORT_SSH_PARSE — SSH target 解析修复真机复验（`emulator-5564`）

- 轮次：复验轮（承接 `research/v97_fix/SSH_TARGET_PARSE_FIX.md`）
- 设备：`emulator-5564`（Pixel_10_Pro_Fold，2076x2152@390，全屏手机态）
- 宿主：Windows 11 + WSL2 `Arch`（`HomeMINISFORUM`）
- 纪律：全程 `adb -s emulator-5564`；**未跑 gradle、未装机、未杀进程**（未使用 `am force-stop`）；**未按 BACK(4)**、**未用 ESC(111)**（弹层一律用 `取消` 关闭）；截图仅 `screencap -p` + `adb pull`；坐标全部运行期从 `uiautomator dump --compressed` 解析，属性同时匹配 `text="…"`/`text='…'`；只动 `E2ECHK5564p*` 条目；**边做边写盘**。

> ## 状态：**BLOCKED —— 被测二进制不含本轮修复**
>
> | 用例 | 判定 |
> |---|---|
> | **A `sshtest@127.0.0.1:2222`** | **❌ 未连上，秒失败**（sshd 零日志）——与**修前**症状逐字一致 |
> | **A-反证 `sshtest@127.0.0.1`** | ❌ 未连上（设备 22 无人监听） |
> | **B `ssh -p 2222 sshtest@127.0.0.1`** | **✅ 连上**（`已连接`，sshd `Accepted keyboard-interactive/pam`） |
> | **C 畸形 `@@@`** | ✅ 给了可见反馈（`ssh_error_input`），**但该输入不具区分力**（修前同样拒） |
> | **C2 畸形 `sshtest@127.0.0.1:`** | ⚠️ 给了可见反馈，但是**通用网络文案** ⇒ **修前行为**（决定性反证） |
>
> **「两种写法都要能连」这个命题本轮无法判定** —— 见 §0.3：5564 的 APK 装机时间比修复源码的最后写入**早 12 分 47 秒**，
> 行为学上（C2）也独立证明跑的是**修前**解析器。装机需 Lead 授权（本轮纪律禁装机、禁 gradle）。

---

## 0. 环境读数

### 0.1 宿主 sshd / 账号

```console
$ wsl -d Arch -e bash -lc "sudo ss -ltnp 2>/dev/null | grep -E ':22\b'"
LISTEN 0      128           0.0.0.0:22        0.0.0.0:*    users:(("sshd",pid=336,fd=6))
LISTEN 0      128              [::]:22           [::]:*      users:(("sshd",pid=336,fd=7))

$ wsl -d Arch -e bash -lc "sudo faillock --user sshtest 2>&1 | tail -6"
sshtest:
When                Type  Source                                           Valid
（仅表头、无 `V` 行 ⇒ **未锁定** ✅，不需 reset）

$ wsl -d Arch -e bash -lc "sudo ssh-keygen -l -f /home/sshtest/.ssh/authorized_keys"
256 SHA256:sYFitiUL7ZsVSAtodSjj4ob+l4DqiA05VZA9F7w4CR8 v95-test (ED25519)   ✅ 与诊断报告一致
```

### 0.2 设备链路

```console
$ adb -s emulator-5564 reverse --list
host-15 tcp:4095 tcp:4095
host-15 tcp:2222 tcp:22          ✅ 2222→宿主 22

$ adb -s emulator-5564 shell toybox netstat -ltn | grep 2222
tcp6       0      0 [::]:2222               [::]:*                  LISTEN
```

> ⚠️ `reverse` 表里**没有** `tcp:22`。设备上 `127.0.0.1:22` 无人监听 ⇒ **「不带 port」那条走的是设备本地 22，不会抵达宿主 sshd**。
> 这一点决定了 A-反证的读数（见 §2），与「宿主 22 是 WSL sshd 所以可能真能连」的预期不同。

### 0.3 ⛔ 决定性阻塞：5564 上的二进制**早于**本轮修复

```console
$ adb -s emulator-5564 shell dumpsys package com.xiufeigo.opencodeshell | Select-String 'versionName|versionCode|lastUpdateTime'
    versionCode=26 minSdk=26 targetSdk=35
    versionName=1.4.0
    lastUpdateTime=2026-10-03 05:11:22
```

设备时钟 = 宿主 − 8h（对齐校验）：

```console
$ adb -s emulator-5564 shell date
Sat Oct  3 05:34:41 GMT 2026
$ Get-Date
2026年10月3日 13:34:41
```

| 项 | 时间（CST） |
|---|---|
| **设备 APK 装机** `lastUpdateTime` | **13:11:22** |
| `SshConnectionModel.kt` 最后写入 | **13:24:09** |
| `SshTargetParseV99Test.kt` 最后写入 | 13:25:23 |
| `SSH_TARGET_PARSE_FIX.md` 最后写入 | 13:27:10 |
| 仓库 `app/build/outputs/apk/debug/app-debug.apk` 产出 | **13:32:40** |

**修复比装机晚 12 分 47 秒。**

二进制同一性用 MD5 正面确认（两者字节数相同 44198253 但内容不同，size 相同属巧合）：

```console
$ adb -s emulator-5564 pull /data/app/.../base.apk ; Get-FileHash … -Algorithm MD5
installed_on_5564 : 6FDAFC9820BC9142FBFDB8FE6F79A411
repo_app-debug   : 7A9D1E4B4E78397301A5799E1E38D19F
SAME_BINARY      : False
```

修复代码确实在源码里（装机之后写入）：

```console
$ Select-String SshConnectionModel.kt -Pattern 'splitSshHostPort|SshHostParts|SSH_DEFAULT_PORT'
134:  port = optionPort ?: hostPort ?: SSH_DEFAULT_PORT,
165:  const val SSH_DEFAULT_PORT: Int = 22
171:  private data class SshHostParts(val user: String?, val host: String, val port: Int?)
174:  private fun parseSshHostPart(token: String, fallbackUser: String?): SshHostParts? {
183:      val (hostLiteral, portLiteral) = splitSshHostPort(hostPart) ?: return null
189:      if (portLiteral == null) return SshHostParts(user, host, null)
195:      return SshHostParts(user, host, port)
208: private fun splitSshHostPort(hostPart: String): Pair<String, String?>? {
```

> **行为学独立佐证**（§4.2）：修复文档 §7 用例 C 指定的判别输入 `sshtest@127.0.0.1:`
> 在本二进制上给的是**通用网络文案**而非 `ssh_error_input`
> ⇒ 解析器**没有**「读不出端口就拒绝」的逻辑 ⇒ 修前版本。
> 结论不依赖时间戳推断。

### 0.4 设备私有目录

```console
$ adb -s emulator-5564 shell run-as com.xiufeigo.opencodeshell md5sum files/id_ed25519
md5sum: files/id_ed25519: No such file or directory
$ adb -s emulator-5564 shell run-as com.xiufeigo.opencodeshell ls -la files/ssh/
ls: files/ssh/: No such file or directory
```

5564 是**干净设备**：无测试私钥、无 `known_hosts`。本轮按任务书用**账号口令**认证，故不受影响。

---

## 1. 用例 A —— `sshtest@127.0.0.1:2222`（`user@host:port`）

### 1.1 建条目（口令认证）

表单导航：首页 → `设置` (154,1921) → `服务器` (178,1443) → `添加 SSH 服务` (1700,582)。

> **表单可达性实测（`SSH_FORM_REACHABILITY_FIX` 未被弄坏 ✅）**：
> IME 弹起时 `ScrollView` 被裁剪到 `y=806..1156`，**滑动无效**（两次 swipe 均无位移），
> 但 **`keyevent 61`（TAB）焦点路由可逐字段下移**，把折叠线以下的 `服务器名称`、认证方式、口令字段全部带到可交互位置。
> 这是本轮在 5564 上唯一可达深字段的路径（**与 5554 报告不同**：`tab` 而非 `swipe`）。

| 步 | 动作 | 运行期读数 |
|---|---|---|
| 1 | Host = `sshtest@127.0.0.1:2222` | `ctr=(1211,1094) EditText pw=false fo=true t='sshtest@127.0.0.1:2222'` ✅ 逐字一致 |
| 2 | `keyevent 61` → Name | 焦点落到 `服务器名称（可选）` 下空 EditText ✅ |
| 3 | Name = `E2ECHK5564p-colon` | `ctr=(1211,1358) EditText t='E2ECHK5564p-colon'` ✅ |
| 4 | `keyevent 61` ×2 → 口令字段 | `ctr=(1211,1054) EditText pw=true fo=true t='•••••••'`（7 字符 = `opencode`）✅ |
| 5 | 认证方式 | 渲染 `密码（可选）` 分支 ⇒ `auth == PASSWORD` ✅ |
| 6 | 点 `添加服务` `13:39:47` | — |

### 1.2 判定：**❌ 未连上，秒失败**（与修前症状逐字一致）

提交后 6 秒读数（运行期 dump 解析）：

```
ctr=(930,976)  t='E2ECHK5564p-colon'
ctr=(825,1018) t='连接失败'
ctr=(1048,1060) t='无法建立 SSH 连接。请检查网络和 SSH 配置。'
ctr=(1505,1018) t='重新连接'
ctr=(1717,1018) t='断开连接'
ctr=(960,1450)  t='无法建立 SSH 连接。请检查网络和 SSH 配置。'
ctr=(737,1516)  t='连接失败'
ctr=(1522,1602) t='取消'
ctr=(1672,1602) t='连接'
```

**状态文案原文**：

- 行状态：`连接失败`（`Connection failed`）
- 错误句：`无法建立 SSH 连接。请检查网络和 SSH 配置。`
  （`Could not establish the SSH connection. Check your network and SSH configuration.`）

**没有**口令挑战卡，**没有**主机核验卡 `Verify host identity` ⇒ 失败发生在**拨号阶段**。

### 1.3 决定性证据：sshd 零日志

```console
$ wsl -d Arch -e bash -lc "sudo journalctl -u sshd --since '13:39' --no-pager | tail -20"
-- No entries --
```

> 提交 `13:39:47`，窗口内 sshd **一条记录都没有**（连 `[preauth]` 关闭都没有）
> ⇒ 连接**从未抵达 sshd** ⇒ 解析出的 port **不是 2222**，拨号目标无法解析。

### 1.4 app 侧日志：零线索（P2 复现）

```console
$ adb -s emulator-5564 logcat -c   # 提交前清空
… 提交 …
$ adb -s emulator-5564 logcat -d | Measure-Object          # 1211 行
$ adb -s emulator-5564 logcat -d | Select-String 'UnknownHostException|SocketException|IOException|jsch|JSch|Connection refused|Name or service'
（0 命中；APP_PID=48912）
```

> 全窗口**无** `UnknownHostException`、无 `SocketException`、无 jsch 记录
> ⇒ app 既不分类也不打日志，只丢通用文案，**P2「失败不可诊断」原样复现**。

### 1.5 A 的判定表

| 判据 | 结果 |
|---|---|
| ① 出现口令挑战 / 核验卡（TCP 已通、到了 sshd） | ❌ 未出现 |
| ② 行状态进入 `Connected` | ❌ `连接失败` |
| sshd 有 `Accepted …` | ❌ `-- No entries --` |

**A = FAIL。** 归因：**被测二进制不含修复**，而非修复无效。
判据 ①② 同时失败 + sshd 零日志，正是 `SSH_TARGET_PARSE_FIX.md` §2.1 描述的修前因果链
（`host="127.0.0.1:2222"` + `port=22` → 拨号前即失败）在**第二台设备**上的复现。

### 1.6 「是否秒失败」的对照

| 写法 | 结果 | 延迟 | 到达 sshd？ | 性质 |
|---|---|---|---|---|
| `sshtest@127.0.0.1:2222`（A） | `连接失败` | **秒级**（提交后 6s 内已终态） | ❌ 零日志 | 拨号前失败 |
| `ssh -p 2222 sshtest@127.0.0.1`（B） | `已连接` | ~9s（含核验卡） | ✅ `Accepted` | 真连上了 2222 |

> 同设备、同认证方式（口令）、同账号、同口令，**唯一差别是写法** ⇒ A 的失败是**解析层**的，不是环境/账号/认证的。
> 这是本轮最有价值的一组对照。

---

## 2. 用例 A-反证 —— `sshtest@127.0.0.1`（不带 port ⇒ 走 22）

条目 `E2ECHK5564p-noport`，口令认证，提交后读数：

```
ctr=(938,1136) t='E2ECHK5564p-noport'
ctr=(825,1178) t='连接失败'
ctr=(1048,1220) t='无法建立 SSH 连接。请检查网络和 SSH 配置。'
```

```console
$ wsl -d Arch -e bash -lc "sudo journalctl -u sshd --since '-6 min' --no-pager | tail -15"
-- No entries --
```

**判定：❌ 未连上。** 与 A 同样秒失败、sshd 同样零日志。

**与任务书预期的偏差（重要）**：任务书假设「宿主机 22 是 WSL sshd，不带 port 可能真能连」。
实测**连不上**，原因是 §0.2：`adb reverse` 只有 `tcp:2222→22`，**没有** `tcp:22`，
设备上 `127.0.0.1:22` 无人监听 ⇒ 拨号直接被拒。

> ⇒ 这条对照**只证明了「不带 port 走 22 且 22 不通」**，**没有**证明「不带 port = 22 解析正确」
> —— 因为它与「解析成别的什么东西」在 UI 上不可区分（都是秒失败 + 通用文案）。
> 要在设备上正面验证「不带 port ⇒ 22」，需要**额外加一条 `adb reverse tcp:22 tcp:22`**（本轮未做，属环境变更）。

---

## 3. 用例 B —— `ssh -p 2222 sshtest@127.0.0.1`（既有可用写法）

### 3.1 建条目

Host = `ssh -p 2222 sshtest@127.0.0.1`（`input text` 空格转 `%s`），Name = `E2ECHK5564p-dashp`，口令 `opencode`。

### 3.2 主机核验卡：**真的连到了 2222**

```
ctr=(793,1269) t='确认主机身份'                      ← Verify host identity
ctr=(799,1374) t='[127.0.0.1]:2222'                 ← ⭐ 端口 2222 出现在核验卡
ctr=(779,1442) t='ssh-ed25519'
ctr=(754,1512) t='MD5 指纹'
ctr=(1118,1552) t='MD5:9b:19:f4:e8:83:e3:ca:ad:1d:c0:62:76:76:48:9b:24'
ctr=(1429,1632) t='取消'
ctr=(1626,1632) t='信任并连接'
```

> 指纹 `MD5:9b:19:…` **与 F14 / 5554 诊断报告完全同一枚** ⇒ 真连到了同一台宿主 sshd。
> 这是「端口解析正确」最直接的 UI 证据（`HOST_KEY_CARD_FIX` 行为未被弄坏 ✅：
> `取消` / `信任并连接` 两按钮均在，`取消` 可用）。

### 3.3 认证与终态

点 `信任并连接` (1626,1632) 后，**未再弹口令卡**（本条目存的就是口令，直接使用），5 秒后：

```
ctr=(934,1318) t='E2ECHK5564p-dashp'
ctr=(812,1360) t='已连接'                      ← ⭐ Connected
ctr=(931,1554) t='127.0.0.1:36617'            ← 隧道本地端口
ctr=(867,1595) t='sshtest'                     ← 用户
```

```console
$ wsl -d Arch -e bash -lc "sudo journalctl -u sshd --since '-8 min' --no-pager | grep -Ev '^wsl:'"
Oct 03 13:46:24 HomeMINISFORUM sshd-session[50594]: Accepted keyboard-interactive/pam for sshtest from 127.0.0.1 port 40666 ssh2
Oct 03 13:46:24 HomeMINISFORUM sshd-session[50594]: pam_unix(sshd:session): session opened for user sshtest(uid=1001) by sshtest(uid=0)

$ adb -s emulator-5564 shell run-as com.xiufeigo.opencodeshell cat files/ssh/known_hosts
[127.0.0.1]:2222 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIP4xhCsSfuwShy6+t+va8TMG1R1brte/e+EImnSO8sQM
```

### 3.4 B 的判定

**B = PASS ✅**（在**修前**二进制上就已 PASS）。
`known_hosts` 写入 `[127.0.0.1]:2222` 一条 —— 属既有 `Trust and connect` 行为，**登记但不删**（其他轮依赖）。

> 这条同时是**回归闸的反面证据**：修前二进制上 `-p` 语义完好，**没有**被 `:port` 路径抢走
> —— 因为修前根本没有 `:port` 路径。修复版的 `-p` 优先级是否仍正确，**本轮无法验证**（需装修复版重测）。

---

## 4. 用例 C —— 畸形输入的可诊断性

### 4.1 C（任务书指定 `@@@`）：✅ 有可见反馈，但**不具区分力**

条目 `E2ECHK5564p-bad`，提交后行读数：

```
ctr=(917,1212) t='E2ECHK5564p-bad'
ctr=(1116,988)  t='请输入主机或 SSH 连接命令。不允许使用远程命令和不支持�?SSH 选项�?'
ctr=(737,1054)  t='连接失败'
```

**状态文案原文**：

- 行状态：`连接失败`
- 错误句：`请输入主机或 SSH 连接命令。不允许使用远程命令和不支持的 SSH 选项。`
  （`ssh_error_input` = `Enter a host or SSH connection command. Remote commands and unsupported SSH options aren't allowed.`）

**判定：✅ 通过「不得静默无反应」这一条。**

> ⚠️ **但 `@@@` 无法区分修前/修后**。推导：`@@@` 的 `lastIndexOf('@')=2` ⇒ `hostPart = "@@@".substring(3) = ""`
> ⇒ `host.isBlank()` ⇒ **修前** `parseSshHostPart` 也返回 `null` ⇒ 同样落到 `ssh_error_input`。
> 任务书给的这个例子**恰好落进两版解析器共有的拒绝分支**，属于非判别性输入。
> 真正能判别的是修复文档 §7 指定的尾冒号写法，故补测 C2。

### 4.2 C2（`sshtest@127.0.0.1:`，修复文档 §7 的判别输入）：⚠️ **修前行为**

条目 `E2ECHK5564p-trailing`，提交后行读数：

```
ctr=(939,1598) t='E2ECHK5564p-trailing'
ctr=(825,1640) t='连接失败'
ctr=(1048,1682) t='无法建立 SSH 连接。请检查网络和 SSH 配置。'
```

**状态文案原文**：行状态 `连接失败`；错误句 `无法建立 SSH 连接。请检查网络和 SSH 配置。`

**判别逻辑**：

| 版本 | `sshtest@127.0.0.1:` 的解析 | 应给文案 |
|---|---|---|
| **修后**（有 `splitSshHostPort`） | 尾冒号读不出端口 ⇒ **拒绝** | `请输入主机或 SSH 连接命令…`（`ssh_error_input`） |
| **修前**（`else -> hostPart` 原样） | `host="127.0.0.1:"` + `port=22` ⇒ 拨号失败 | `无法建立 SSH 连接。请检查网络和 SSH 配置。` |

**实测得到的是修后不应出现的通用文案 ⇒ 判定为修前解析器。**
这与 §0.3 的装机时间/MD5 证据**完全一致**，两条独立证据互相印证。

### 4.3 C 的判定

| 项 | 判定 |
|---|---|
| 畸形输入给出**可见**失败反馈 | ✅ 是（两条畸形输入都有） |
| 反馈**指向 target**（`ssh_error_input`）而非「检查网络」 | ❌ 尾冒号那条给的是**误导性的网络文案** |
| **不得静默无反应** | ✅ 通过 |

**C = PASS（弱）/ P2 未闭环。**
畸形输入不会静默，但**错误分类仍然错**：端口写错被翻译成「检查你的网络和 SSH 配置」，
运维会被指向错误方向 —— 这正是 `REPORT_SSH_DIAG` §5.b **P2** 的原文，**未被修复**。

---

## 5. 清理

### 5.1 条目：全部删净 ✅

`E2ECHK5564p*` 共 5 条，逐条走「行 kebab → `删除` → 确认条」：

| 条目 | 结果 |
|---|---|
| `E2ECHK5564p-trailing` | 已删除 |
| `E2ECHK5564p-colon` | 已删除 |
| `E2ECHK5564p-dashp` | 已删除 |
| `E2ECHK5564p-bad` | 已删除 |
| `E2ECHK5564p-noport` | 已删除 |

确认条原文（zh-CN 运行时）：`删除 SSH 服务器？` / `将把「E2ECHK5564p-xxx」从列表中移除，且无法撤销。`
（对应 `This removes "…" from the list.`）。

**终态截图读数**：服务器列表只剩两条**轮前既有**条目，`SSH 隧道` 区段已随 `dashp` 一并消失：

```
127.0.0.1:4096  no username
127.0.0.1:4095  v2.0.18 • opencode
```

> B 建立的活动隧道 `127.0.0.1:36617` / `sshtest` 已随条目删除而消失 ✅

### 5.2 `/sdcard`：无新增残留 ✅

```console
$ adb -s emulator-5564 shell ls -la /sdcard/
（全部文件日期为 2026-09-22 … 2026-09-27，**没有 2026-10-03 的新文件**）
```

本轮**所有 dump 一律写 `/data/local/tmp/`**（未用 `--compressed` 到 `/sdcard`），截图经 `adb pull` 落到宿主
`%TEMP%\e2echk5564\shots\`，`/sdcard` 零写入。

### 5.3 `/data/local/tmp`：本轮临时文件已删净 ✅

```console
$ adb -s emulator-5564 shell rm -f /data/local/tmp/e2e5564*.xml /data/local/tmp/s.png /data/local/tmp/shot.png
$ adb -s emulator-5564 shell ls -la /data/local/tmp/
total 24
drwxrwx--x 3 shell shell  4096 2026-10-03 05:55 .
drwxr-x--x 5 root  root   4096 2026-09-22 02:13 ..
drwxrwxrwx 3 shell shell  4096 2026-09-22 02:15 dalvik-cache
（仅剩系统目录）
```

### 5.4 按要求**未**动的两项

```console
$ adb -s emulator-5564 shell run-as com.xiufeigo.opencodeshell cat files/ssh/known_hosts
[127.0.0.1]:2222 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIP4xhCsSfuwShy6+t+va8TMG1R1brte/e+EImnSO8sQM
（**保留未清** ✅ —— B 的 `Trust and connect` 写入，属既有行为，其他轮依赖）

$ adb -s emulator-5564 reverse --list
host-15 tcp:4095 tcp:4095
host-15 tcp:2222 tcp:22
（**一条未动** ✅）
```

宿主侧本轮**零状态变更**（`faillock` 原本就是干净的，**未执行 `--reset`**）。

---

## 6. 未闭环

1. **【阻塞】修复本身在真机上未被验证。** 5564 装机 `13:11:22` 早于修复源码写入 `13:24:09`；
   装机 APK MD5 `6FDAFC98…` ≠ 仓库 `app-debug.apk` MD5 `7A9D1E4B…`；
   C2 的行为学证据也指向修前解析器。**修复版 APK 已构建好**（`app/build/outputs/apk/debug/app-debug.apk`，
   13:32:40，MD5 `7A9D1E4B4E78397301A5799E1E38D19F`），**装机需 Lead 授权**（本轮纪律禁装机/禁 gradle）。
   装好后 A/B/C2 三条必须**原样重跑**。
2. **`-p` 与 `:port` 的优先级回归闸未验证。** 本轮 B 在**修前**二进制上 PASS，只能证明「修前 `-p` 完好」。
   修后 `optionPort ?: hostPort ?: 22` 的优先级（`ssh -p 2222 sshtest@127.0.0.1:2200` 应取 2222）**仍是未测项**。
3. **「不带 port ⇒ 22」缺正向证据。** §2 的反证被 `adb reverse` 缺 `tcp:22` 污染，只能得到「22 不通」。
   要正面验证需加 `adb reverse tcp:22 tcp:22`（环境变更，未做）。
4. **P2 未闭环。** 尾冒号这类「目标写错」仍被报成「检查网络和 SSH 配置」（§4.2）。
   `sshFailure()` 仍无 `UnknownHostException` 分类（修复文档 §8.3 同项），需新句子 `unknown-host` ⇒ 属新增三语字符串，需单独决策。
5. **P3 未触碰。** 本轮全用口令认证，未复验私钥退化；`Accepted publickey` 在 5564 上**从未出现**
   （本轮唯一 `Accepted` 是 `keyboard-interactive/pam`），与 P3 描述一致，但**不构成独立验证**。
6. **app 侧 SSH 日志仍缺。** 1211 行 logcat 内零 Ssh/jsch/Exception 记录，A 的失败原因仍**完全不可诊断**。
7. **非本轮范围的两份文档未被本轮触及**：表单字段可达性 ✅ 与核验卡 `取消`/`信任并连接` ✅
   行为均正常（`SSH_FORM_REACHABILITY_FIX` / `HOST_KEY_CARD_FIX` **未被弄坏**），
   但正式结论仍需在**修复版二进制**上复测。

---

## 7. 复现命令索引（全部只读 + `input`，无 gradle / 装机 / 杀进程）

```powershell
$adb='C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'; $D='emulator-5564'

# 环境
wsl -d Arch -e bash -lc "sudo ss -ltnp 2>/dev/null | grep -E ':22\b'"
wsl -d Arch -e bash -lc "sudo faillock --user sshtest 2>&1 | tail -6"
& $adb -s $D reverse --list
& $adb -s $D shell dumpsys package com.xiufeigo.opencodeshell | Select-String 'lastUpdateTime|versionName'

# 每条用例：dump 解析坐标 → input tap/type → keyevent 61 逐字段下移
& $adb -s $D shell uiautomator dump --compressed /data/local/tmp/e2e.xml
& $adb -s $D shell cat /data/local/tmp/e2e.xml
& $adb -s $D shell input keyevent 61          # ⭐ 表单深字段唯一可达路径（IME 弹起时）

# sshd 侧
wsl -d Arch -e bash -lc "sudo journalctl -u sshd --since '-8 min' --no-pager | grep -Ev '^wsl:'"

# app 侧
& $adb -s $D logcat -c ; … ; & $adb -s $D logcat -d | Select-String 'jsch|UnknownHostException|SocketException'
```

**证据截图**（宿主本地，交付物目录外）：

```
%TEMP%\e2echk5564\shots\state.png          A 之前的全量行状态
%TEMP%\e2echk5564\shots\after_cleanup.png  删除过程
%TEMP%\e2echk5564\shots\final.png          清理终态（只剩 2 条既有条目）
```
