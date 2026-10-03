# V96 真机复验报告 — emulator-5560 (Pixel_9a, 1080x2424@420dpi)

**包名** `com.xiufeigo.opencodeshell` v1.4.0 (code 26, `lastUpdateTime=2026-10-02 07:13:06` 设备时 = 15:13 CST，F13 构建)
**日期** 2026-10-02 · **设备** 仅 emulator-5560（`emu avd name` → `Pixel_9a` 已核实；未触碰 5554/5556/5558/5562/127.0.0.1:5555）
**证据目录** `research/v96_verify/5560/`（46 张截图 + 129 份 `ui_*.xml`）
**本轮目标** F13a（`OcTextField.onNext` 路由）+ F13b（`SshDialog.onCancel` 去掉 `onDismiss()`），并回归 F12a / F12b

## 结论速览

| # | 复验项 | 结论 |
|---|--------|------|
| 1 | **F13a 私钥条目建成** | **FAIL** — 条目**能建成**（行出现、按钮转 `Connect`），但**零连接尝试**（sshd 无任何条目），且报的是误导性的 `oc_ssh_error_input`。**根因不是 `onNext`，而是密钥文件 app 读不到**（见 §2） |
| 2 | **F12a passphrase 弹卡闭环** | **BLOCKED（阻塞，非 SKIP）** — 简报口径（条目存 `wrongpp`）在设计上**不可能**触发 passphrase 卡：`SshConnection.kt:240` 只在「未存 passphrase」时发卡（见 §3） |
| 3 | **F13b 口令闭环 → Connected** | **PASS** ✅ — app 行 `Connected`，sshd `Accepted keyboard-interactive/pam` + `session opened`（本轮主目标达成） |
| 4 | **F12b Cancel 回归** | **PASS** ✅ — Cancel 后 t+150ms…t+5000ms 共 7/7 采样卡片不再现、行 7/7 保留；二次 Reconnect（新 promptId）必重弹 |
| 5 | **清理** | **PASS** ✅ — 3 条测试条目删除后关闭/重开 Settings 均不复活；`/sdcard` 无我方残留；faillock 已复位 |

> **一句话**：**F13b（口令闭环 → Connected）这个本轮主目标真实达成**，F12b 回归也稳；**F13a 没有修好问题——私钥卡住的真正原因是 `/sdcard/v95/id_ed25519` 的属主/权限让 app uid 读不到，jsch 在开 socket 之前就抛异常，而异常被分类器归成 `INPUT`，于是用户看到一句「请输入主机或 SSH 连接命令」的假话。**

---

## 0. 环境核验（开工前，全绿）

| 项 | 实测 |
|---|---|
| 设备身份 | `adb -s emulator-5560 emu avd name` → `Pixel_9a` ✅ |
| 屏幕 | `wm size` 1080x2424 · `wm density` 420 ✅ |
| app | `com.xiufeigo.opencodeshell` v1.4.0 (26)，焦点 `.OcActivity` ✅ |
| reverse | `tcp:4095`、`tcp:4096`、`tcp:2222→22` 三条全在 ✅ |
| 后端 | app 侧显示 `v2.0.18 • opencode` · `Server healthy` ✅ |
| 私钥 | `/sdcard/v95/id_ed25519` 444 字节，`-rw-rw---- u0_a218 media_rw` ⚠️ **见 §2.1** |
| authorized_keys | `ssh-ed25519 AAAAC3…HeiXS v95-test`，`-rw------- sshtest sshtest` ✅ |
| faillock | 开工时干净 ✅ |
| 起始面板 | 只有默认 `10.0.2.2:4095`（v95 清理保持住了）✅ |

**修复在源码中确认存在**（不是装了旧包）：
- `SettingsComponents.kt:1487-1496` — `keyboardActions = KeyboardActions(onNext = { onImeAction?.invoke() }, …)`（F13a）
- `SshDialog.kt:235-248` — `onCancel = { connection?.cancelPrompt() }`，注释明确写了「only the cancel — no `onDismiss()`」（F13b）

---

## 1. F13b 口令闭环 → Connected：**PASS** ✅

条目 `E2EC5560C`：target `ssh -p 2222 sshtest@127.0.0.1`、name `E2EC5560C`、auth=Password、密码 `wrongpw`（7 位掩码已核）。

提交前最后一屏核验（`E2ECHK5560_T2_31_up2.xml`）：

```
Host or SSH command                              [106,953][471,994]
ssh -p 2222 sshtest@127.0.0.1                     [138,1011][943,1137]   ← target 完整落库
E2EC5560C                                        [138,1211][943,1303]
Add server                                       [760,1294][938,1335]
```

**认证应答单脚本一气呵成**（`E2ECHK5560_answer_v96.ps1`，卡出现→聚焦→输入→Continue 之间无任何 dump/screencap）：

```
SUBMIT @ 849,1314
faillock reset
--- outcome samples ---
  s1  Connected
```

app 行最终态（`E2ECHK5560_T2_43_row.xml`）：

```
E2EC5560C    [201,1342][394,1383]
Connected    [201,1388][346,1423]     ← 进了 Connected
Reconnect    [323,1466][494,1507]
Disconnect   [631,1466][811,1507]
```

sshd 侧佐证：

```
Oct 02 15:41:32 sshd-session[6663]: pam_unix(sshd:auth): authentication failure; … user=sshtest
Oct 02 15:41:33 sshd-session[6661]: error: PAM: Authentication failure for sshtest from 127.0.0.1
Oct 02 15:41:36 sshd-session[6661]: Accepted keyboard-interactive/pam for sshtest from 127.0.0.1 port 53016 ssh2
Oct 02 15:41:36 sshd-session[6661]: pam_unix(sshd:session): session opened for user sshtest(uid=1001) by sshtest(uid=0)
```

**这正是 F13b 要修的那条**：15:41:32 那条 `authentication failure` 是条目里存的 `wrongpw` 被 `setPassword()` 自动重试了一次；15:41:36 我们在卡上答的 `opencode` 被服务端接受，**app 这次真正落到了 Connected**，而不是 v95c 的「服务端 Accepted 但 app 报 Could not establish」。

证据：`E2ECHK5560_T2_31_up2.png`（提交前表单）、`E2ECHK5560_T2_40_result.png`、`E2ECHK5560_T2_43_row.xml`（Connected 行）。

---

## 2. F13a 私钥条目建成：**FAIL** — 根因不是 `onNext`

### 2.1 现象（复现 3 次，口径完全按简报）

表单五项全部正确落库，**且 host 在点提交前的最后一屏仍然完整可见**（`E2ECHK5560_T3_33_up4.xml`）：

```
Host or SSH command          [106,1039][471,1080]
ssh -p 2222 sshtest@127.0.0.1 [138,1097][943,1223]   ← 提交前刚核过
E2EC5560K                    [138,1297][723,1303]
Add server                   [760,1294][938,1335]
```

点 `Add server` 后：

```
E2EC5560K                                        [201,1384][392,1425]   ← 行建成了
Connection failed                                [201,1430][435,1452]
Passphrase (optional)                            [106,903][455,944]
•••••••                                          [138,961][943,1087]  (pw=true, wrongpp)
Enter a host or SSH connection command. Remote commands and unsupported SSH options aren't allowed.   ← oc_ssh_error_input
Connection failed                                [106,1205][340,1240]
Cancel  [597,1294][708,1335]     Connect  [803,1294][938,1335]   ← 按钮已转 Connect
```

按钮从 `Add server` 变成 `Connect` ⇒ `connection != null`，即 `submit()` 走完了 `validateSshForm`、`onSubmit` 建行、`tunnel.connect()` 已启动（`SshDialog.kt:187-216`）。**条目确实建成了**。

sshd 侧（提交时刻窗口）：

```
$ sudo journalctl -u sshd --since '2026-10-02 15:44:00' --no-pager | tail -10
-- No entries --
$ sudo journalctl -u sshd --since '2026-10-02 15:57:30' --no-pager | tail -15
-- No entries --
```

**零会话**——连接根本没发起，与 v95c 观察一致。

### 2.2 真正的根因：私钥文件 app 读不到（不是 target 解析）

`parseSshTarget` **不是**元凶，证据有三条：

1. `SshConnectionModel.kt:49-80` 逐 token 解析，`ssh -p 2222 sshtest@127.0.0.1` 必然返回 `SshTarget(user=sshtest, host=127.0.0.1, port=2222)`；
2. **同一串 target** 在 auth=Password 的条目上本轮实测连上了（§1，sshd 有 `Accepted`）；
3. `validateSshForm` 已放行，而它对 KEY 认证要求 `privateKeyPath?.trim()` 非空（`SshModel.kt:108`），所以 `SshConnection.kt:90` 的「key path 为空」分支**不可达**。

那么 `INPUT` 只剩一个来源——`SshConnectionModel.kt:156-159` 的 **JSchException 消息分类器**：

```kotlin
"privatekey" in lower || "identity" in lower || "filenotfound" in lower ||
"no such file" in lower || "passphrase" in lower && "fail" in lower ->
    SshFailure(SshStage.FAILED, SshErrorCode.INPUT, message)
```

而 `jsch.addIdentity(...)` 在 `SshConnection.kt:92`，**排在 `jschSession.connect()`（第 108 行）之前**——本地读钥失败就抛，**一个 socket 都不会开**，这完美解释了「零 sshd 会话」。

直接验证「app 读不到钥」：

```
$ adb shell ps -A | grep opencodeshell
u0_a230  28711 … com.xiufeigo.opencodeshell
$ adb shell ls -l /sdcard/v95/id_ed25519
-rw-rw---- 1 u0_a218 media_rw 444 2026-10-01 10:17 /sdcard/v95/id_ed25519
$ adb shell run-as com.xiufeigo.opencodeshell cat /sdcard/v95/id_ed25519
cat: /sdcard/v95/id_ed25519: Permission denied          ← 决定性
$ adb shell chmod 644 /sdcard/v95/id_ed25519            ← sdcardfs 忽略 chmod
$ adb shell run-as … cat /sdcard/v95/id_ed25519
cat: /sdcard/v95/id_ed25519: Permission denied
```

**app uid 是 `u0_a230`，文件属主是 `u0_a218:media_rw` 模式 660，app 既不是属主也不在 `media_rw` 组里，读不到。**

### 2.3 把钥放到 app 读得到的位置后，行为立刻变了（反证）

把同一份钥（base64 经 `run-as` 写入）放到 app 私有目录 `/data/data/com.xiufeigo.opencodeshell/files/id_ed25519`（`run-as cat` 可读），用**正确** passphrase `keypass123` 建 `E2EC5560K` 再提交：

- `INPUT` 消失，**真的建起了 TCP 连接**：sshd 出现本次会话
  ```
  Oct 02 15:59:39 unix_chkpwd[7249]: password check failed for user (sshtest)
  Oct 02 15:59:39 sshd-session[6995]: pam_unix(sshd:auth): authentication failure; … user=sshtest
  Oct 02 15:59:42 sshd-session[6993]: error: PAM: Authentication failure for sshtest from 127.0.0.1
  ```
- app 侧渲染出 **kbdint `Password:` 卡**（说明服务端发了键盘交互挑战），重试后落到
  `Could not establish the SSH connection. …` + `Authentication required`（`ssh_error_connection`，与 `INPUT` 是**不同**的错误码）。

**结论**：解密私钥这条路在本机仍没跑通到 `Accepted publickey`——服务端从未接受公钥，而是回落到键盘交互要密码。密钥本身是好的（`ssh-keygen -y -P keypass123` 推出的公钥与 `authorized_keys` **逐字节一致**）：

```
$ ssh-keygen -y -P keypass123 -f id_ed25519
ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOAcQVV5+9a/R97T1QyWPNx/u3P8LJtCbPHp0hFHeiXS v95-test
$ sudo cat /home/sshtest/.ssh/authorized_keys
ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOAcQVV5+9a/R97T1QyWPNx/u3P8LJtCbPHp0hFHeiXS v95-test   ← 完全一致
```

最可能的机制：**jsch 加载不了 OpenSSH 新格式（`BEGIN OPENSSH PRIVATE KEY`）的加密私钥**，`addIdentity` 静默不注册 identity，于是公钥根本没被 offer，服务端直接回落到 kbdint。这一条**本轮未做到端到端证实**（预算所限），但已定位到「公钥从未被 offer」这一事实。

### 2.4 复现步骤（F13a FAIL）

1. Settings → Servers → Add SSH server
2. Host 填 `ssh -p 2222 sshtest@127.0.0.1`，Server name 填任意，Authentication 切 `Private key`
3. Key file path 填 `/sdcard/v95/id_ed25519`，Passphrase 填任意（`wrongpp` / `keypass123` 结果相同）
4. 提交 → 行建成、按钮转 `Connect`，但红字是 `Enter a host or SSH connection command…`，sshd 零会话
5. 换成 app 读得到的钥路径 → `INPUT` 消失、真的连上（但公钥未被 offer，见 §2.3）

**判定**：F13a **没有解决**私钥链路。`onNext` 路由是真实的修复，但它修的是「IME 抢焦点把文本吃掉」，与本轮观察到的失败无因果关系。

### 2.5 顺带发现的一个真 bug（建议单独立项）

**「私钥文件读不到 / 私钥格式不支持」被报成「请输入主机或 SSH 连接命令」。**
`SshConnectionModel.kt:156-159` 把 `filenotfound` / `no such file` / `privatekey` 一律归到 `SshErrorCode.INPUT`，而 `INPUT` 的文案（`oc_ssh_error_input`）说的是 target。用户完全无法从这句话推断真因。v95 的报告正是被这句话带偏，才判成「app 侧 target 缺陷」。

---

## 3. F12a passphrase 弹卡闭环：**BLOCKED**（简报口径在设计上不可达）

简报要求：条目存 `passphrase=wrongpp` → 期望触发 passphrase 弹卡 → 答 `keypass123` → `Accepted publickey`。

**这条路径走不通，有两道各自独立的门：**

1. **存了错 passphrase 时，卡根本不会出现。** jsch 在 `addIdentity()` 阶段就要用这个 passphrase 解密，错了直接抛异常（§2.2），**在 `connect()` 之前**就结束了，服务端连 kbdint 挑战都不会发。
2. **就算 passphrase 是对的，卡也不会出现。** `SshConnection.kt:239-245`：

   ```kotlin
   override fun promptPassphrase(message: String): Boolean {
       config.passphrase?.let { return true }        // ← 存了就直接返回，不发卡
       val prompt = publishPrompt(message, confirm = false, hostKey = null)
       …
   }
   ```

   只有**条目里完全没存 passphrase**（`config.passphrase == null`）时才会 `publishPrompt` 出版卡卡面。

**要验 F12a，口径必须改成「条目不存 passphrase」**（本轮未执行，预算所限）。另需先解决 §2.3 的公钥未被 offer 问题，否则即使卡弹出来也拿不到 `Accepted publickey`。

**F12a 判定：BLOCKED（阻塞，非 SKIP）** —— 不是操作问题，是复验口径与实现不匹配。

---

## 4. F12b Cancel 回归：**PASS** ✅

在 `E2EC5560C` 行上跑（`E2ECHK5560_f12b.ps1`，每次 Reconnect 前 `ResetFaillock`）。

**① 行级 Reconnect 由面板同槽位渲染卡片**（`E2ECHK5560_B_10_card1.png` / `.xml`）：

```
RECONNECT#1 @ 408,1562  (row title y=1342)
SSH authentication   [106,1412][458,1459]
Password:            [106,1524][296,1561]
Cancel  [588,1742][699,1783]     Continue [794,1742][938,1783]
card1 present = True
```

**② Cancel 后高频采样（7 点）** —— 卡片消失、行保留，**同一 promptId 不重弹**：

```
CANCEL @ 644,1762
  t+150   card=False row_kept=True
  t+300   card=False row_kept=True
  t+600   card=False row_kept=True
  t+1000  card=False row_kept=True
  t+2000  card=False row_kept=True
  t+3000  card=False row_kept=True
  t+5000  card=False row_kept=True
```

**③ 新 promptId 必重弹**：

```
RECONNECT#2 @ 408,1562
card2 present = True
SSH authentication   [106,1412][458,1459]
The account is locked due to 3 failed logins.     ← 错密自烧 faillock，符合预期
```

证据：`E2ECHK5560_B_10_card1.png`、`E2ECHK5560_B_11_after_cancel.png`、`E2ECHK5560_B_12_card2.png`。

---

## 5. 清理：**PASS** ✅

| 步骤 | 证据 |
|---|---|
| 删 `E2EC5560K` | `E2ECHK5560_CL_29_afterDelK.png` |
| 删 `E2EC5560P` | `E2ECHK5560_CL_26_afterDelP.png` |
| 删 `E2EC5560C` | `E2ECHK5560_CL_23_afterDelC.png` |
| 列表只剩默认 `10.0.2.2:4095`（Server healthy · Default · `v2.0.18 • opencode`） | `E2ECHK5560_CL_29_afterDelK.png` |
| 关闭 Settings 再重开 → **3 条均未复活** | `E2ECHK5560_CL_30_settingsClosed.png` / `E2ECHK5560_00_servers_panel.png` |
| `/sdcard` 无我方残留 | `ls /sdcard \| grep -iE '_e2e5560\|E2ECHK5560'` → 无输出 |
| reverse 三条仍在 | `adb reverse --list` → 4095 / 4096 / 2222→22 |
| faillock 复位 | `faillock --user sshtest` → 表为空 |
| 预置资产保留 | `/sdcard/v95/id_ed25519` 原样（444 字节，未改权限） |

**唯一我方新增的设备文件**（为解除 §2.2 的读权限阻塞而建，**已如实记录**）：
`/data/data/com.xiufeigo.opencodeshell/files/id_ed25519`（444 字节，属主 `u0_a230`）。需要的话可 `run-as … rm files/id_ed25519` 移除。

---

## 6. 本轮踩坑（给下一位）

1. **PowerShell 里 `"$Matches[1]" + "$Matches[3]"` 是字符串拼接**，不是相加。`[132,2177][263,2218]` 会算出 `66132,10886109` 这种坐标，脚本里静默点空。**必须先 `[int]$Matches[n]` 再算**——已封装进 `E2ECHK5560_tap.ps1`。
2. **`-match '^X\t'` 对 join 后的多行字符串只在整串开头匹配**，永远漏。行级判断一律用 `Select-String -Quiet`（封装为 `Has()`）。
3. **`Search settings` 是 EditText 的 hint**，不进节点表；判断「Settings 打开」要用**任意一个 tab 标签**（General/Appearance/…/About），因为 tab 条可横向滚动，`General` 可能已被滚出视口。
4. **表单打开时是紧凑态，只渲染 Host 一项**，不点一下不会展开、也不会滚动。
5. **卡在表单卡片里拖不动**：只有 `x=1000`（字段右边界 943 之外）能真正滚动面板，从 x∈[138,943] 起手会被当成文本选择。`keyevent 61`(TAB) 反而最可靠——Compose 会把下一个字段自动带进视口。
6. **TAB 走过头会把焦点送出对话框**（跑到首页按钮上）。表单字段数是已知的，别多按。
7. **切到 `Private key` 之后 TAB 进不去新出现的 key path 字段**（焦点还留在 `OcSelect` 上），必须**直接点** key path 框。
8. **Reconnect → 卡片出现不是瞬时的**，服务端要现发 kbdint 挑战；实测 3s 不够，**用 8s**。
9. **删条目的确认框渲染在可视区之外**（在折叠以下），点完 `Delete` 要**先把面板往下滚**才能看到 `Delete SSH server?`；有时 `Delete` 菜单项要点两次才生效。
10. **删条目前先把卡消掉**，否则卡的 Cancel/Continue 与行尾弹出菜单重叠，点 Delete 会打偏。最快的办法是**关掉再打开 Settings**（卡片是面板状态，重建即清）。
11. **认证会话时限**：卡出现→输入→Continue 必须单脚本连续完成，任何 dump/screencap 都会让会话过期。
12. `Shot` 必须走 `cmd /c` 转发；PowerShell 的 `>` 会把 PNG 二进制转成文本（产物 322KB 垃圾 vs 正常 170KB）。

---

## 7. 建议（按性价比排序）

1. **把「私钥类」错误从 `INPUT` 里拆出来**（§2.5）——现在用户看到的是一句关于 target 的假话，排障成本极高。这一条独立于 F13a，且是 v95 误判的直接源头。
2. **修 `/sdcard/v95/id_ed25519` 的provisioning**（属主/权限），否则任何私钥用例都跑不起来；建议直接把测试钥放 app 私有目录。
3. **验 F12a 必须改口径**：条目**不存** passphrase 才会发卡（§3）。同时要先解决 jsch 是否能加载 OpenSSH 格式加密私钥（§2.3）。
4. **F13b 已闭环**，建议补一条自动化回归（口令应答后必须出现 `Connected`），防止 `onDismiss()` 那类「卡片自拆隧道」的回归再发生。
