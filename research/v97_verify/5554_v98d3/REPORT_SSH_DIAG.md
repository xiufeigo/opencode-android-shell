# REPORT_SSH_DIAG — `Connection failed` 归因：环境 or 产品缺口？

- 设备：`emulator-5554`（Pixel_9a，1080x2424@420）
- 宿主：Windows 11 + WSL2 `Arch`（`HomeMINISFORUM`）
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 轮次：第 10 轮（**纯诊断轮**）
- 纪律：全程 `adb -s emulator-5554`；**未跑 gradle、未装机、未杀进程、未改任何源码**；**未按 BACK(4)**、**未用 ESC(111)**；截图仅 `screencap -p` + `adb pull`；坐标全部运行期从 dump 解析。
- **本轮唯一的宿主状态变更**：`faillock --user sshtest --reset`（解锁测试账号）+ 一次性 `/tmp/diag` 临时文件（已删）。二者都是诊断/复现的前提条件，非生产代码改动。

> ## 一句话结论
>
> **`Connection failed` 是产品缺口，不是环境问题。** app 的 SSH 目标解析器**丢弃 `:port` 后缀**，
> `sshtest@127.0.0.1:2222` 被解析成 `host="127.0.0.1:2222"` + `port=22`，
> 于是它去 **22 端口**拨一个**不存在的域名** → 秒失败 → **sshd 全程零日志**。
> 宿主 sshd / 密钥 / 端口 / reverse 隧道**全部健康**（本轮实测 `Accepted publickey` + `Accepted password` 均成功）。

---

## 1. 宿主机侧：sshd / 账号 / authorized_keys

### 1.1 sshd 在 22 上监听 ✅

```console
$ wsl -d Arch -e bash -lc "sudo ss -ltnp 2>/dev/null | grep -E ':22\b'"
LISTEN 0      128           0.0.0.0:22        0.0.0.0:*    users:(("sshd",pid=336,fd=6))
LISTEN 0      128              [::]:22           [::]:*      users:(("sshd",pid=336,fd=7))

$ systemctl is-active sshd
active
● sshd.service - OpenSSH Daemon
     Active: active (running) since Fri 2026-10-02 09:18:19 CST; 1 day 3h ago
     Main PID: 336 (sshd)
```

### 1.2 `sshtest` 账号存在 ✅

```console
$ getent passwd sshtest
sshtest:x:1001:1001::/home/sshtest:/bin/bash
```

### 1.3 authorized_keys 含测试公钥，权限全对 ✅

```console
$ sudo cat /home/sshtest/.ssh/authorized_keys
ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOAcQVV5+9a/R97T1QyWPNx/u3P8LJtCbPHp0hFHeiXS v95-test

$ sudo stat -c '%A %U:%G %n' /home/sshtest /home/sshtest/.ssh /home/sshtest/.ssh/authorized_keys
drwx------ sshtest:sshtest /home/sshtest
drwx------ sshtest:sshtest /home/sshtest/.ssh
-rw------- sshtest:sshtest /home/sshtest/.ssh/authorized_keys     # 700 / 700 / 600 ✅

$ sudo ssh-keygen -l -f /home/sshtest/.ssh/authorized_keys
256 SHA256:sYFitiUL7ZsVSAtodSjj4ob+l4DqiA05VZA9F7w4CR8 v95-test (ED25519)
```

### 1.4 sshd 生效配置 ✅

```console
$ sudo sshd -T | grep -E '^(pubkeyauthentication|passwordauthentication|authorizedkeysfile|usepam|authenticationmethods|maxauthtries) '
UsePAM yes
MaxAuthTries 6
PubkeyAuthentication yes
PasswordAuthentication yes
AuthorizedKeysFile .ssh/authorized_keys
AuthenticationMethods any
```

### 1.5 ⚠ faillock **确实锁过**（本轮启动时处于锁定期）

```console
$ sudo faillock --user sshtest
sshtest:
When                Type  Source                                           Valid
2026-10-03 12:57:43 RHOST 127.0.0.1                                            V
2026-10-03 12:58:02 RHOST 127.0.0.1                                            V
2026-10-03 12:58:05 RHOST 127.0.0.1                                            V

$ grep -vE '^\s*#|^\s*$' /etc/security/faillock.conf
（空 ⇒ 走默认值 deny=3, unlock_time=600）
```

`/etc/security/faillock.conf` **全默认**：`deny=3` 连续 3 次失败即锁，`unlock_time=600`（10 分钟）自动解锁。

sshd 侧锁定的**决定性原文**：

```
Oct 03 12:08:13 HomeMINISFORUM sshd-session[44531]: pam_faillock(sshd:auth): Consecutive login failures for user sshtest account temporarily locked
Oct 03 12:58:05 HomeMINISFORUM sshd-session[46135]: pam_faillock(sshd:auth): Consecutive login failures for user sshtest account temporarily locked
Oct 03 12:58:16 HomeMINISFORUM sshd-session[46135]: error: maximum authentication attempts exceeded for sshtest from 127.0.0.1 port 45078 ssh2 [preauth]
```

**当天 00:00 之后 sshd 的全部成功记录：0 条。** 最后一次成功是 **10-02 20:33:58**：

```console
$ sudo journalctl -u sshd --no-pager | grep -E 'Accepted publickey' | tail -1
Oct 02 20:33:58 HomeMINISFORUM sshd-session[20775]: Accepted publickey for sshtest from 127.0.0.1 port 54612 ssh2: ED25519 SHA256:sYFitiUL7ZsVSAtodSjj4ob+l4DqiA05VZA9F7w4CR8
```

10-03 当天 11:28–12:58 的记录**全部是 `Failed password` / `authentication failure`**，
**一条 `publickey` 尝试都没有** ⇒ 当时的 app 根本没在用私钥（与 §4.4 结论一致）。

> **重要旁证**：`publickey` 成功**不受**这把锁影响——见 §2.4，锁仍在生效时 `Accepted publickey` 照样成功。
> 所以 faillock **不是**本轮 `Connection failed` 的原因（它只影响**口令**通道）。

---

## 2. 宿主机侧 key 认证直连：**宿主 key 认证是健康的**

> 本段最重要的产出：把「宿主侧 22 端口 key 认证未能独立验证」这个**历史误判**彻底推翻。

### 2.1 复现历史那条"失败"命令 —— 失败原因不在服务端

```console
$ ssh -i research/v95_verify/id_ed25519 -o IdentitiesOnly=yes -o BatchMode=yes sshtest@127.0.0.1 true
@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@
@         WARNING: UNPROTECTED PRIVATE KEY FILE!          @
@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@
Permissions 0777 for '/mnt/c/.../research/v95_verify/id_ed25519' are too open.
It is required that your private key files are NOT accessible by others.
This private key will be ignored.
Load key "/mnt/c/.../id_ed25519": bad permissions
sshtest@127.0.0.1: Permission denied (publickey,password,keyboard-interactive).
ssh exit=255
```

**根因是客户端、不是 sshd**：
`research/v95_verify/` 在 Windows 的 `/mnt/c` 挂载上，权限恒为 **`0777`**，
OpenSSH 判定私钥"过于开放"→ **直接拒绝加载（`This private key will be ignored`）** → 一个公钥都没递出去 → sshd 无从接受 → 返回 `Permission denied`。
**`Permission denied` 是在说"我手上没钥匙"，不是"你的钥匙不认"。**

### 2.2 第二层坑：`BatchMode=yes` 无法索取 passphrase

私钥是**加密**的（passphrase `keypass123`）。`BatchMode=yes` 禁止任何交互提示，
所以即使权限修好，客户端也解不开私钥 → 仍然 `Permission denied`。**这正是历史报告反复"验不出来"的第二层原因。**

### 2.3 passphrase 与密钥指纹：✅ 完全正确

```console
$ cp $K /tmp/diag/k600 && chmod 600 /tmp/diag/k600
$ ssh-keygen -y -P keypass123 -f /tmp/diag/k600     # exit=0
ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOAcQVV5+9a/R97T1QyWPNx/u3P8LJtCbPHp0hFHeiXS v95-test

$ ssh-keygen -l -f <derived>              →  256 SHA256:sYFitiUL7ZsVSAtodSjj4ob+l4DqiA05VZA9F7w4CR8 v95-test
$ sudo ssh-keygen -l -f authorized_keys    →  256 SHA256:sYFitiUL7ZsVSAtodSjj4ob+l4DqiA05VZA9F7w4CR8 v95-test
$ md5sum $K  →  0120a8270f7f0b0c71d31f4933384c63
```

`keypass123` **正确**；私钥 ↔ `authorized_keys` ↔ 历史 `Accepted publickey` 的指纹**三者完全一致**。

### 2.4 ✅ 公钥认证直连**成功**（且当时 faillock 仍锁着）

```console
$ eval "$(ssh-agent -s)"
$ SSH_ASKPASS=/tmp/diag/pp.sh SSH_ASKPASS_REQUIRE=force setsid -w ssh-add /tmp/diag/k600
Identity added: /tmp/diag/k600 (v95-test)

$ ssh -o BatchMode=yes sshtest@127.0.0.1 "echo HOST_PUBKEY_AUTH_OK; id -un"
HOST_PUBKEY_AUTH_OK
sshtest
ssh exit=0
```

sshd 侧原文：

```
Oct 03 13:01:35 HomeMINISFORUM sshd-session[46517]: Accepted publickey for sshtest from 127.0.0.1 port 39470 ssh2: ED25519 SHA256:sYFitiUL7ZsVSAtodSjj4ob+l4DqiA05VZA9F7w4CR8
Oct 03 13:01:35 HomeMINISFORUM sshd-session[46517]: pam_unix(sshd:session): session opened for user sshtest(uid=1001) by sshtest(uid=0)
```

> 此时 `faillock` 仍显示 3 条 `V`（有效）失败记录 ⇒ **公钥路径不受这把锁影响**。

### 2.5 ✅ 口令通道也健康

```console
$ SSH_ASKPASS=... setsid -w ssh -o PubkeyAuthentication=no -o NumberOfPasswordPrompts=1 \
      -o PreferredAuthentications=password,keyboard-interactive sshtest@127.0.0.1 "echo PASSWORD_AUTH_OK; id -un"
PASSWORD_AUTH_OK
```
```
Oct 03 13:02:00 HomeMINISFORUM sshd-session[46627]: Accepted password for sshtest from 127.0.0.1 port 47568 ssh2
```

### 2.6 解锁（本轮唯一宿主状态变更）

```console
$ sudo faillock --user sshtest --reset    # exit=0
$ sudo faillock --user sshtest
sshtest:
When                Type  Source                                           Valid
（空）
```

**§2 小结**：宿主侧 **sshd / 账号 / 公钥 / passphrase / 口令 / 端口 全部健康**。
第八、九轮报告里"宿主侧 22 端口的 key 认证未能独立验证"是**测试方法造成的假阴性**（0777 私钥 + BatchMode 不给提示），**必须从后续结论里删除**。

---

## 3. 设备侧链路：✅ 通

```console
$ adb -s emulator-5554 reverse --list
host-15 tcp:4095 tcp:4095
host-15 tcp:2222 tcp:22            ✅ 隧道在位（2222→宿主 22）

$ adb -s emulator-5554 shell toybox netstat -ltn
tcp6       0      0        [::]:2222               [::]:*                  LISTEN
tcp6       0      0        [::]:4095               [::]:*                  LISTEN
tcp        0      0 127.0.0.1:37540         127.0.0.1:2222          TIME_WAIT   ← 真实完成过的连接
```

连通性探测（只读）：

```console
$ adb -s emulator-5554 shell "timeout 6 toybox nc 127.0.0.1 2222 < /dev/null"
```
sshd 侧对应记录（TCP 确实抵达了 sshd，只是没走到 userauth 就被关闭）：
```
Oct 03 13:00:31 HomeMINISFORUM sshd-session[46287]: Connection closed by 127.0.0.1 port 52168 [preauth]
```

app 私有目录：
```console
$ adb -s emulator-5554 shell run-as com.xiufeigo.opencodeshell md5sum files/id_ed25519
0120a8270f7f0b0c71d31f4933384c63      ✅ 与仓库测试私钥一致，未被破坏
$ adb -s emulator-5554 shell run-as … ls -la files/ssh/
（轮初为空目录，无 known_hosts）
```

app 版本：
```console
$ adb -s emulator-5554 shell dumpsys package com.xiufeigo.opencodeshell
versionCode=26 minSdk=26 targetSdk=35
versionName=1.4.0
lastUpdateTime=2026-10-03 03:09:27     ← F14 记录的是 2026-10-02 11:02:36
```

> ⚠ **同版本号、不同二进制**：`versionName`/`versionCode` 与 F14 完全一致，但 `lastUpdateTime`
> 晚了一天。F14 的 `Accepted publickey` 是在 **10-02 那份**二进制上取得的。
> 换言之，**F14 的 PASS 不能自动沿用到当前这份 APK**。

**§3 小结**：设备→app→`127.0.0.1:2222`→宿主`22`→WSL sshd 这条链**完好**。

---

## 4. app 侧取证：复现 + 失败原因

### 4.1 表单实况 —— **推翻第九轮"私钥无入口"的说法**

`Add SSH server` 表单的可视结构（实测）：

| 阶段 | 观察到的字段 |
|---|---|
| 刚打开 | `Host or SSH command` / `Server name (optional)` / `Cancel` / `Add server` |
| **Host + Name 都填完** | 追加出现 `Authentication` = `Password` |
| 点开选择器 | 下拉项 **`Password`** 与 **`Private key`** |
| 选 `Private key` | 追加 `Key file path` / `Passphrase (optional)`(pw=true) |

```console
'Authentication'  [106,1349][337,1361]
'Password'        [138,1443][296,1484]      ← 当前选中值（=选择器）
'Private key'     [138,1540][317,1581]      ← ✅ 选项存在
'Key file path'   [106,1520][307,1561]
（pw=true EditText）[138,1778][723,1788]
```

> **REPORT_COVERAGE9 §3.1 的「本 UI 无 Username、无 Key path 字段 ⇒ 私钥认证无入口」= 误判。**
> 真实原因：认证区**只有在 Host 和 Name 都填完之后才渲染**，且位于**折叠线以下**（`Server name` 标签实测高度仅 `1px`，`[106,1724][473,1725]`）。
> 表单**不滚动**（两次 swipe 无任何位移），所以只看未填满的表单必然看不到认证区。
> 源码侧佐证：`SshDialog.kt:492` 确实渲染 `privateKeyPath` 字段。

### 4.2 复现 A —— 用**错误语法** `sshtest@127.0.0.1:2222`（即第九轮的写法）

| 步骤 | 操作 | 读数 |
|---|---|---|
| Host | `sshtest@127.0.0.1:2222` | ✅ 写入成功 |
| Name | `E2ECHK5554diag-a` | ✅ |
| Authentication | `Private key` | ✅ 选项可选 |
| Key file path | `/data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519` | ✅ 读回逐字一致 |
| Passphrase | 留空 | ✅ pw=true, text='' |
| 提交 | `13:04:29` | — |

结果（**与第九轮逐字一致**）：

```
'Could not establish the SSH connection. Check your network and SSH configuration.'   [106,1588][975,1658]
'Connection failed'                                                                  [106,1690][340,1725]
'Cancel' [597,1779][708,1820]   'Connect' [803,1779][938,1820]
```

**sshd 侧同期日志：**
```console
$ sudo journalctl -u sshd --since "13:04" --no-pager
-- No entries --
```

> 🎯 **决定性**：app 这次连接**根本没有抵达 sshd**（连 `[preauth]` 关闭记录都没有）。
> ⇒ **不是认证失败、不是主机键不匹配、不是超时**——是**拨号阶段就没成功**。

**logcat 同期（app PID 17376）**：
```
10-03 05:04:25.520 17376 17376 D InsetsController: show(ime())
10-03 05:04:29.429 17376 17376 D InsetsController: hide(ime())
10-03 05:04:29.490 17376 17376 D VRI[OcActivity]: WindowInsets changed: ime:null
（全窗口仅 15 行 app 日志，**无任何** Ssh / jsch / Exception / E / W 记录）
```
`logcat -d | Select-String 'E/|W/|Exception|SocketException|IOException'` 过滤 app PID → **0 命中**。
⇒ app **既不报错也不打日志**，只丢一句通用文案。**失败原因被完全吞掉**。

### 4.3 直接原因（源码级证明）

`app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshConnectionModel.kt`：

```kotlin
fun parseSshTarget(command: String): SshTarget? {          // :49
    var user: String? = null
    var port = 22                                            // :54  ← 默认 22
    ...
        token == "-p" || token == "-i" || token == "-l" -> {  // :61
            when (token) {
                "-p" -> { port = value.toIntOrNull() ?: return null }   // :65-68 ← 端口【只能】由 -p 设置
    ...
        else -> { val parsed = parseSshHostPart(token, user) ?: return null }  // :78
```

```kotlin
private fun parseSshHostPart(token: String, fallbackUser: String?): Pair<String?, String>? {   // :115
    val at = token.lastIndexOf('@')
    val user = if (at >= 0) token.substring(0, at).ifEmpty { fallbackUser } else fallbackUser
    val hostPart = if (at >= 0) token.substring(at + 1) else token
    val host = when {
        hostPart.startsWith("[") && hostPart.endsWith("]") && hostPart.length > 2 ->
            hostPart.substring(1, hostPart.length - 1)     // 只剥 IPv6 方括号
        else -> hostPart                                    // :123  ← 【原样返回，不剥 :port】
    }
    if (host.isBlank()) return null
    return user to host
}
```

对 `sshtest@127.0.0.1:2222` 求值：

| 字段 | 解析结果 |
|---|---|
| `user` | `sshtest` |
| `host` | **`"127.0.0.1:2222"`**（把 `:2222` 当成了域名的一部分） |
| `port` | **`22`**（默认值，`-p` 从未出现） |

于是 app 去**端口 22** 拨一个叫 `127.0.0.1:2222` 的主机 → 无法解析 → **立即失败**。
**sshd 零日志、app 零日志、UI 只给通用文案** —— 三方观测与代码推导**完全吻合**。

> 这也解释了第九轮报告里那句「`user@host` 语法**被 app 正确解析**——行名回落为 `host:port`」：
> 行名回落恰恰是**破绽**——它把整串 `127.0.0.1:2222` 当成了主机名，而不是 `host`+`port`。

### 4.4 复现 B —— 用**正确语法** `ssh -p 2222 sshtest@127.0.0.1`

| 步骤 | 读数 |
|---|---|
| 提交 `13:07:32` | 行 `E2ECHK5554diag-b` → **`Connecting over SSH…`** |
| 主机核验卡 | `Verify host identity` / `[127.0.0.1]:2222` / `ssh-ed25519` / `MD5:9b:19:f4:e8:83:e3:ca:ad:1d:c0:62:76:76:48:9b:24`（**与 F14 同一指纹** ⇒ 真的连到了 sshd） |
| 点 `Trust and connect` `13:07:53` | 弹 **`SSH authentication` / `Password:`** 卡 ⚠ **不是** 私钥 passphrase 卡 |
| 输入 `opencode` → `Continue` `13:08:20` | — |
| 结果 | 行状态 **`Connected`**，隧道 `127.0.0.1:42097`，用户 `sshtest` |

sshd 侧原文：
```
Oct 03 13:08:21 HomeMINISFORUM sshd-session[46845]: Accepted keyboard-interactive/pam for sshtest from 127.0.0.1 port 33730 ssh2
Oct 03 13:08:21 HomeMINISFORUM sshd-session[46845]: pam_unix(sshd:session): session opened for user sshtest(uid=1001) by sshtest(uid=0)
```

> ⚠ **`Connected` 是用「口令」达成的，不是用「私钥」。**
> 条目明明存的是 `Private key` + keypath，但**首连**就退化成了 keyboard-interactive 口令卡，
> sshd 记的是 `Accepted keyboard-interactive/pam`，**全窗口没有 `Accepted publickey`**。
> ⇒ F14 报告的 **D1 缺陷在当前这份 APK（`lastUpdateTime 2026-10-03 03:09:27`）上依然存在，且发生在首连路径**，
> 不只是 F14 描述的 Reconnect 路径。**这是 F12a/F14b 的回归。**

---

## 5. 结论

### a) 这次 `Connection failed` 的直接原因

**app 侧 `parseSshTarget` 不解析 `host:port`，把 `:2222` 当成了主机名的一部分，于是用默认 `port=22` 去拨一个无法解析的主机名，拨号阶段立即失败。**

- 精确到组件：`app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshConnectionModel.kt:49-127`
- **不是**认证失败（sshd 没有任何认证记录）
- **不是**主机键不匹配（核验卡根本没机会弹）
- **不是**超时（失败是瞬时的）
- **不是**网络/tunnel/宿主问题（`adb reverse`、sshd、公钥、口令本轮全部实测健康）

> faillock 锁（`deny=3` / `unlock_time=600`，10-03 12:08 与 12:58 各锁过一次）**确实存在**，
> 且 11:28–12:58 的口令尝试全部失败——但它**不是**本次 `Connection failed` 的原因：
> 本次私钥条目**压根没走到认证阶段**（sshd 零日志）；且实测证明 **publickey 通道不受该锁影响**（§2.4）。
> 它是**上一轮遗留的独立环境隐患**，已在本轮 reset。

### b) 环境问题 还是 产品缺口？

> ## **判定：产品缺口（Product Gap）。宿主环境 100% 健康。**

| # | 判定 | 严重度 | 证据 |
|---|---|---|---|
| **P1（主因）** | **`parseSshTarget` 不支持 `user@host:port` 语法。** 表单接受这种写法、甚至把它当**行名**回显，但解析器只认 `-p <port>`。用户照着最自然的写法填，得到的是**静默失败** | **高** | `SshConnectionModel.kt:54,65-68,115-127`；§4.3 |
| **P2** | **失败完全不可诊断。** 失败时 app **不打任何日志**、不抛任何异常，只给 `Could not establish the SSH connection. Check your network and SSH configuration.` —— 把"目标串写错"误导成"网络或 SSH 配置有问题"，运维会去查错方向 | **高** | §4.2（app PID 全窗口 0 条 E/W/Exception） |
| **P3** | **`Private key` 条目首连退化�� `Password:` keyboard-interactive 卡**，私钥从未被使用（`Accepted publickey` 缺席）⇒ **F14b/F12a 在当前 APK 上回归** | **高** | §4.4；对比 `5554_f14/REPORT.md` 的 `Passphrase for ...id_ed25519` |

> **附带的环境隐患（非本轮直接原因，但必须记）**：`pam_faillock` 用默认 `deny=3 / unlock_time=600` 保护一个被自动化反复用**错口令**轰炸的测试账号。
> 它不影响 publickey，但**只要走口令就会互相踩**：一旦锁住，正确的口令也会被判 `Failed password`。
> 建议（环境侧，不属本轮改动范围）：把 `sshtest` 加入 `faillock.conf` 的 `deny = 0` 或显著调大 `unlock_time`。

### c) 照抄式复现 `Connected` 的步骤

**先决条件（缺一不可）**

| # | 条件 | 校验命令 | 预期读数 |
|---|---|---|---|
| 0 | `adb` 不在 PATH，须用全路径 | — | `C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe` |
| 1 | 宿主 sshd 在 22 监听 | `wsl -d Arch -e bash -lc "sudo ss -ltnp \| grep :22"` | 两行 `LISTEN … 22 … sshd` |
| 2 | `sshtest` **未被锁**（**最易踩的坑**） | `wsl -d Arch -e bash -lc "sudo faillock --user sshtest"` | **输出为空**；有 `V` 行就先 `sudo faillock --user sshtest --reset` |
| 3 | 公钥在位 | `wsl -d Arch -e bash -lc "sudo ssh-keygen -l -f /home/sshtest/.ssh/authorized_keys"` | `SHA256:sYFitiUL7ZsVSAtodSjj4ob+l4DqiA05VZA9F7w4CR8` |
| 4 | 私钥在 app 私有目录 | `adb -s emulator-5554 shell run-as com.xiufeigo.opencodeshell md5sum files/id_ed25519` | `0120a8270f7f0b0c71d31f4933384c63` |
| 5 | reverse 隧道在位 | `adb -s emulator-5554 reverse --list` | 含 `tcp:2222 tcp:22` |

**步骤**

| # | 动作 | 预期读数（决定性） |
|---|---|---|
| 1 | `adb -s emulator-5554 shell input tap <Add SSH server 中心>` | 出现 `Host or SSH command` / `Server name (optional)` |
| 2 | tap Host 输入框 → `input text "ssh%s-p%s2222%ssshtest@127.0.0.1"` → `keyevent 61` | dump 读回 `text='ssh -p 2222 sshtest@127.0.0.1'` |
| 3 | tap Name 输入框 → `input text "E2ECHK5554diag-b"` → `keyevent 61` | 读回 `text='E2ECHK5554diag-b'`；**认证区此刻才出现** |
| 4 | tap `Authentication` 的当前值（`Password`）→ 下拉里 tap `Private key` | 字段集换成 `Key file path` + `Passphrase (optional)` |
| 5 | tap Key file path → `input text "/data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519"` → `keyevent 61` | 读回路径逐字一致；Passphrase **留空** |
| 6 | tap `Add server` | 行状态 = **`Connecting over SSH…`**，弹 **`Verify host identity`** 卡 |
| 7 | 校验指纹 | `MD5:9b:19:f4:e8:83:e3:ca:ad:1d:c0:62:76:76:48:9b:24`（与 F14 一致） |
| 8 | tap `Trust and connect` | 弹认证卡 |
| 9 | **⚠ 当前 APK 会弹 `Password:` 卡**（P3 缺陷）→ 输 `opencode` → `Continue` | 若弹的是 `Passphrase for …id_ed25519` 则输 `keypass123` |
| 10 | 读 sshd 日志 | `wsl -d Arch -e bash -lc "sudo journalctl -u sshd --since '-2min' \| tail -5"` → 应见 `Accepted … for sshtest` |
| 11 | 读 UI | 行状态 **`Connected`**，出现 `Reconnect` / `Disconnect`，隧道形如 `127.0.0.1:42097` |

> **只改一处就能从 `Connection failed` 变成 `Connected`**：Host 字段必须写成 **`ssh -p 2222 sshtest@127.0.0.1`**（带 `-p`），
> **不能**写成 `sshtest@127.0.0.1:2222`。

---

## 6. 清理

| 项 | 结果 | 证据 |
|---|---|---|
| `E2ECHK5554diag-b` | ✅ 已删 | 先 `Disconnect` → 行 kebab(名 cy+203) → `Delete` → 确认条原文 `This removes "E2ECHK5554diag-b" from the list.` → `Delete` |
| `E2ECHK5554diag-a` | ✅ 已删 | 同上，确认条原文 `This removes "E2ECHK5554diag-a" from the list.` |
| 最终条目清单 | ✅ 只剩原有两条 | `E2ECHK5554d3e-c2` \| `10.0.2.2:4095` |
| `/sdcard` 本轮残留 | ✅ **0 条** | `ls /sdcard \| grep -i diag` → 无输出（dump 全部写在 `/data/local/tmp`，未落 `/sdcard`） |
| `/data/local/tmp` | ✅ 本轮产物已清 | 已删 `diag*/q*/p*/r*/cl*/s1.xml` 及两张 png；目录内 `dump.xml`/`nc.*`/`r2.txt` 等为**其它轮次**既有，未动 |
| `reverse` 隧道 | ✅ 一条没动 | `host-15 tcp:4095 tcp:4095` / `host-15 tcp:2222 tcp:22` |
| 私钥 `files/id_ed25519` | ✅ 未破坏 | md5 `0120a8270f7f0b0c71d31f4933384c63`（复核 2 次一致） |
| 宿主 `/tmp/diag` | ✅ 已删 | 含 0600 私钥副本与 askpass 脚本，全部清除（`rm -rf /tmp/diag`） |
| `faillock` | ✅ 已 reset | `sudo faillock --user sshtest` → 输出为空 |
| 设备健康 | ✅ | 全程 `mCurrentFocus=…OcActivity`，未 ANR、未 reboot、未 force-stop |
| **`files/ssh/known_hosts`** | ⚠ **本轮新产生，未删** | `Connected` 复现时 `Trust and connect` 写入 `-rw------- 98 2026-10-03 05:07 known_hosts`（F14 核验指纹已落盘）。**故意保留**并在此登记：删它属于改 app 私有状态，任务书只要求清条目 + `/sdcard`。后续轮次若要回到"首次核验"状态，可删此文件或走 app 的 `ResetFaillock` |

---

## 7. 未闭环

1. **app 侧 publickey 通道未闭环**：P3 使私钥条目首连就退化为口令卡，本轮**没有**在当前 APK 上取得 `Accepted publickey`。
   → **"F14b 在当前二进制上是否还 PASS" 仍未知**，需先修 P3 才能验。
2. **P1 的修复方案未实施**（本轮纯诊断，不改源码）。候选：`parseSshHostPart` 识别 `host:port` 并剥离、或在表单校验里显式拒绝该写法并给出可读错误。**均需 Lead 决策后再动。**
3. **P2 未量化**：app 全程不打 SSH 日志这一点，是"从未实现"还是"本路径被 catch 吞掉"，未读全量 `SshConnection.kt` 异常链确认。
4. **10-03 11:28–12:58 口令失败风暴的源头未定位**：是哪个轮次、用什么口令内容打崩的，未追查（已随 faillock reset 而清零）。
5. **当前 APK 与 F14 那份的差异面未知**：只确认了 `lastUpdateTime` 不同、且 F14b 行为已回归；未做完整回归对比。
6. `Authentication required` 态在本轮**未**构造（口令被接受，直接到 `Connected`），该态删除仍未闭环。
