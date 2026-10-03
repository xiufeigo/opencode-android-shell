# V95 真机复验报告 — emulator-5560 (Pixel_9a, 1080x2424@420dpi)

**包名** `com.xiufeigo.opencodeshell` v1.4.0 (code 26, lastUpdate 2026-10-02 01:41:34) · **轮次** F12a + F12b
**日期** 2026-10-02 · **设备** 仅 emulator-5560（`emu avd name` = `Pixel_9a`，已核实；未触碰 5554/5556/5558/5562/127.0.0.1:5555）
**证据目录** `research/v95_verify/5560/`（61 张截图 + 对应 `ui_*.xml`，全部非 0 字节）

## 结论速览

| # | 复验项 | 结论 |
|---|--------|------|
| 1 | **F12b Cancel offered-once** | **部分 PASS** — Cancel 确实关闭卡片（t+200ms…t+1500ms 卡片消失）、隧道行保留、再点 Reconnect 必重弹 ✅；**但「6 秒窗内不重弹」无法干净判定**，被 app 自身用存量密码的重试流污染（见 §2） |
| 2a | **F12b 键盘态 footer 钉底** | **PASS** — 净空 **82px**，与 v94/v95 前任独立复现完全一致 |
| 2b | **F12b 键盘态闭环 → Connected** | **FAIL** — 服务端 `Accepted password`（密码正确），app 仍落 `Authentication required` + `Could not establish the SSH connection`，**从未进入 Connected** |
| 3 | **F12a 加密私钥 passphrase 闭环** | **BLOCKED（阻塞，非 SKIP）** — auth 切到 `Private key` 后表单 target 始终以 `SshErrorCode.INPUT` 提交失败，**根本到不了 passphrase 弹卡**；这是可复现的 app 侧缺陷（见 §4） |
| 4 | **清理** | **PASS** — 两条测试条目删除后关闭/重开 Settings 均不复活；设备无我方残留 |

> **一句话**：F12b 的**交互出口与键盘几何都成立**（行级 Reconnect 弹卡、Cancel 可退、footer 钉底 82px 复现），但**两条闭环都没跑通**——密码闭环卡在「服务端已认证成功、app 仍判失败」，私钥闭环被「切 Private key 后 target 提交为空」直接堵死。

---

## 0. 环境核验（开工前，全绿）

| 项 | 实测 |
|---|---|
| 设备身份 | `adb -s emulator-5560 emu avd name` → `Pixel_9a` ✅ 与目标一致 |
| 屏幕 | `wm size` 1080x2424 · `wm density` 420 ✅ |
| app | `com.xiufeigo.opencodeshell` v1.4.0 (26)，焦点 `.OcActivity` ✅ |
| reverse | `tcp:4095→4095`、`tcp:4096→4096`、`tcp:2222→22` 三条全在 ✅ |
| 私钥 | `/sdcard/v95/id_ed25519` 444 字节，`u0_a218:media_rw` ✅ |
| 后端 | 4095 由 `wslrelay.exe` 转发；app 侧显示 `v2.0.18 • opencode` ✅ |
| sshd | `KbdInteractiveAuthentication yes` / `PubkeyAuthentication yes` / `PasswordAuthentication yes` / `UsePAM yes`（`10-v94-kbdint.conf` 先于 `99-archlinux.conf` 生效）✅ |
| authorized_keys | `-rw------- sshtest sshtest 90`，`ssh-ed25519 ...` ✅ |
| faillock | 开工时干净 ✅ |

**凭据独立验证**（`SSH_ASKPASS` 直连 WSL:22，绕开 app）：

```
$ ssh -o PreferredAuthentications=password -p 22 sshtest@127.0.0.1 'echo LOGIN_OK; whoami'
LOGIN_OK
sshtest          ← 密码 `opencode` 确实正确，后续 §3 的 FAIL 不是密码错
```

---

## 1. 条目搭建（口令条目 `E2EC5560C`）

- 设备上遗留了前人未清的 `V95K` 条目，**开工第一步已删除**（`E2ECHK5560_01_after_del_V95K.png`），列表只剩默认 `10.0.2.2:4095`。
- 新建 `E2EC5560C`：target `ssh -p 2222 sshtest@127.0.0.1`、name `E2EC5560C`、auth=Password、密码 `wrongpw`（7 位掩码 `•••••••` 已截图核验）。
  - target 精确落库证据：`ui_E2ECHK5560_27_host_typed2.xml` → `ssh -p 2222 sshtest@127.0.0.1	[138,1186][943,1312]`
  - 提交后**服务端真实发起 kbdint 挑战**（证明卡片不是前端伪造空壳）：

```
Oct 02 10:53:45 sshd-session[2357]: pam_unix(sshd:auth): authentication failure; ... user=sshtest
Oct 02 10:53:47 sshd-session[2355]: error: PAM: Authentication failure for sshtest from 127.0.0.1
```

  - 卡片文案即 PAM 的 `Password:` 回落（jsch `uiKeyboardInteractive` 指纹）。证据 `E2ECHK5560_38_after_submit.png` / `ui_…38_after_submit.xml`。

---

## 2. 复验项 1 — F12b Cancel offered-once：**部分 PASS**

### 2.1 行级 Reconnect 由面板直接渲染出卡片 — **PASS**

点行内 `Reconnect`（`[323,1414][494,1455]`，tap 408,1434），**无任何 SshDialog 打开**，卡片由 Servers 面板同槽位渲染：

```
E2EC5560C                  [201,1215][394,1256]
Connecting over SSH…       [201,1261][510,1296]
Connecting to SSH server   [384,1339][801,1349]
SSH authentication         [106,1412][458,1459]
Password:                  [106,1524][296,1561]
口令框 (pw=true)           [138,1578][943,1704]
Cancel  [588,1742][699,1783]      Continue [794,1742][938,1783]
```

证据：`E2ECHK5560_T1_11_card1.png` / `ui_E2ECHK5560_T1_11_card1.xml`。
**v94 记录的「行级 Reconnect 卡在 Connecting over SSH… 无出口」本轮不再复现** —— F12b 的出口确实存在。

### 2.2 Cancel 后不重弹 + 行保留 — **PASS（行为层）**

点 `Cancel`(643,1762) 后高频采样：

```
t+200ms   card_present=False  connecting=True
t+400ms   card_present=False  connecting=True
t+800ms   card_present=False  connecting=True
t+1500ms  card_present=False  connecting=True
t+3000ms  card_present=False  connecting=False  failed=True
```

→ 卡片在 200ms 内即被关闭，**同一 promptId 没有重弹**；隧道行 `E2EC5560C` 全程保留。
证据：`E2ECHK5560_T1_06_rapid_*.png` + `ui_E2ECHK5560_T1_06_rapid_*.xml`。

### 2.3 新 promptId 必重弹 — **PASS**

再次点 `Reconnect` → 服务端在 `10:56:22` 重新发起 kbdint 挑战，卡片重新出现（`E2ECHK5560_T1_11_card1.png` 即此证据）。

### 2.4 为什么「6 秒窗」判不了（诚实说明）

简报要求的是「6 秒观测窗内不重弹」。**这条无法干净判定**，原因是 app 自身在服务端仍有存量密码时会自动重试：

```
Oct 02 10:56:25 Failed password for sshtest from 127.0.0.1 port 59904 ssh2
Oct 02 10:56:29 Failed password for sshtest from 127.0.0.1 port 59904 ssh2
Oct 02 10:56:31 Failed password for sshtest from 127.0.0.1 port 59904 ssh2
Oct 02 10:56:34 Disconnecting ... Too many authentication failures [preauth]
```

`SshConnection.kt:106` 有 `jschSession.setPassword(config.password)`，条目里存的是 `wrongpw`；服务端每拒一次就发**新的** kbdint 挑战（新 promptId），卡片按设计就该重弹。所以：
- 「同 promptId 不重弹」→ **已证实**（§2.2）
- 「6 秒内任何卡片都不出现」→ **被自动重试流污染，不成立也无法断言**

附带代价：连续错密触发 `pam_faillock` 锁号（`Consecutive login failures for user sshtest account temporarily locked`，卡面变成 `(10 minutes left to unlock)`、`Continue` 置灰）。后续每次 Reconnect 前都跑了 `ResetFaillock`。

---

## 3. 复验项 2 — F12b 键盘态

### 3.1 footer 钉底 — **PASS（硬指标）**

弹卡时点口令框 (540,1641) 起键盘（`mInputShown=true`）：

| 元素 | 键盘收起 | 键盘弹起 | 位移 |
|---|---|---|---|
| SSH authentication | [106,1412]-[458,1459] | [106,927]-[458,974] | **−485px**（整卡上提） |
| Password: | [106,1524]-[296,1561] | [106,1039]-[296,1076] | −485px |
| 口令框 | [138,1578]-[943,1704] | [138,1093]-[943,1219] | −485px |
| **Cancel** | [588,1742]-[699,1783] | **[588,1327]-[699,1368]** | **−415px** |
| **Continue** | [794,1742]-[938,1783] | **[794,1327]-[938,1368]** | **−415px** |

footer 比卡身上提**少 70px** → footer 被单独钉在卡底。

逐行像素扫描（`E2ECHK5560_rowscan.py`，阈值 lum>248，x∈[100,980)）：

```
1389 : 440  #############################   <<< TRANSITION  (满宽白带起)
1449 : 440  #############################
1450 :    0                              <<< 突降，键盘面板起始
```

```
键盘上沿 y = 1450
footer 底 y = 1368
净空 delta = 1450 − 1368 = 82 px     ← footer 零遮挡
```

与 v94、v95(5558) 两次独立复现的 **82px 完全一致**，几何可重复。
证据：`E2ECHK5560_T2_01_kbup.png` + `ui_E2ECHK5560_T2_01_kbup.xml`。

### 3.2 输 `opencode` 点 Continue → Connected — **FAIL**

必须**一气呵成**完成「卡出现→聚焦→输入→Continue」：SSH 认证会话有服务端超时，中途插入截图/dump 会让会话过期。前任也踩过这个坑（`REPORT_pred_20261001.md`）。收紧成单脚本 `E2ECHK5560_answer.ps1` 后：

- 口令框掩码 **8 位 `••••••••` = `opencode`** ✅（`E2ECHK5560_T2_02_typed.png`）
- 点 `Continue`(866,1347) 后，**服务端认证成功**：

```
Oct 02 11:00:07 sshd-session[2728]: Accepted password for sshtest from 127.0.0.1 port 35786 ssh2
Oct 02 11:00:07 sshd-session[2728]: pam_unix(sshd:session): session opened for user sshtest(uid=1001)
Oct 02 11:00:07 sshd-session[2728]: pam_unix(sshd:session): session closed for user sshtest
```

- **但 app 侧没有进入 Connected**。高频采样 14 次（每次间隔约 0.8s，覆盖 Continue 后 ~11s），全程无 `Connected`：

```
s1..s14  Authentication required | Could not establish      ← 14/14 相同
```

行最终态（`E2ECHK5560_T2_result.png`）：

```
E2EC5560C
Authentication required
Could not establish the SSH connection. Check your network and SSH configuration.
```

**判 FAIL 的理由**：密码已被服务端接受、session opened，认证环节本身成功；`connect()` 在 `jschSession.connect()` 之后只剩 `setPortForwardingL` + `emit(Ready)`，却在 300ms 内落到 `SshStage.AUTHENTICATION`。按 `SshConnectionModel.kt:161-163` 的分类，落到 AUTHENTICATION 说明异常 message 命中了 `auth cancel`/`userauth` 之一 —— 而 jsch 的 `UserAuthKeyboardInteractive` 在 `promptKeyboardInteractive()` 返回 `null` 时抛的正是 `"Auth cancel"`。即：**挑战被回答成功后，认证流程仍以「取消」收场**。这条闭环本轮**没有拿到 Connected 证据**，不能算通过。

---

## 4. 复验项 3 — F12a 加密私钥闭环：**BLOCKED**

计划：auth=`Private key`、key=`/sdcard/v95/id_ed25519`、passphrase 先填 `wrongpp` → 连接触发弹卡 → 输 `keypass123` → Connected + sshd `Accepted publickey`。

**实际：一条都没跑到，卡在提交环节。**

### 4.1 现象（可复现，2 次）

表单所有字段都正确落库（截图核验）：

```
ui_E2ECHK5560_T3_41_retyped.xml : ssh -p 2222 sshtest@127.0.0.1	[138,1186][943,1312]
ui_E2ECHK5560_T3_38_pp.xml      : /sdcard/v95/id_ed25519	[138,957][943,1083]
                                  •••••••	(pw=true)         ← 7 位 = wrongpp
                                  E2EC5560K
```

`Add server` 也是 enabled（`ui_…T3_41_retyped.xml` 可见 `Add server [760,1383][938,1424]`），点下去却得到：

```
E2EC5560K
Connection failed
Enter a host or SSH connection command. Remote commands and unsupported SSH options aren't allowed.   ← oc_ssh_error_input
```

且 **sshd 侧完全没有新会话**（`journalctl --since 11:02` 无任何条目）——连接根本没发起。

### 4.2 判据与定位

- 该文案 = `SshErrorCode.INPUT`，按 `SshDialog.kt:84-87` 的注释，它由**拨号时** `parseSshTarget` 返回 `null` 产生（不是表单 gate 的非空校验——否则不会建行、也不会有 `Connection failed`）。
- `Add server` 的 enabled 条件是 `!busy && target.isNotBlank()`，说明提交瞬间 `target` **非空**。
- 同一串 target 在 **auth=Password** 条目上完全正常（§1：真连上服务端、拿到 kbdint 挑战、`Accepted password`）。
- 为排除「先填 host 再切 auth 导致 host 被清」这一路径，第二轮**故意把 host 放在最后填**：填完 host+name+keypath+passphrase 后，滚回顶部、清空 host 重打一遍（截图确认 `ssh -p 2222 sshtest@127.0.0.1` 完整在框内），**紧接着**点 Add server —— 仍报同一个 INPUT 错误。

**结论：切到 `Private key` 后，提交给 `SshConnectionConfig.target` 的值不可被 `parseSshTarget` 解析，与 host 字段的可见文本不一致。这是 app 侧缺陷，不是本轮操作问题。** 阻塞点在此，F12a 的 passphrase 闭环无法在本构建上验证。

证据：`E2ECHK5560_T3_14_submitted.png`（第一次）、`E2ECHK5560_T3_42_submitted.png`（第二次，host 最后填）、`E2ECHK5560_T3_13_pp_typed.png`（keypath + wrongpp 入框）。

---

## 5. 复验项 4 — 清理：**PASS**

| 步骤 | 证据 |
|---|---|
| 删 `E2EC5560K` | `E2ECHK5560_T3_27_deleted.png` |
| 删 `E2EC5560C` | `E2ECHK5560_CL_18_after_delC.png` |
| 列表只剩默认 `10.0.2.2:4095`（Server healthy · Default · v2.0.18） | `E2ECHK5560_CL_21_after_delK.png` |
| 关闭 Settings 再重开 → Servers 面板 | `E2ECHK5560_CL_22_settings_closed.png` / `E2ECHK5560_CL_24_no_revival.png` — **两条均未复活** |
| 设备无我方残留 | `/sdcard` 下无 `_e2e5560.png`、无 `E2ECHK5560_*.xml`（`Ui()` 每次自删） |
| 未动他人物件 | `/sdcard/*.xml`（`v85_*`、`u*`、`w*` 等）为**其他 agent 遗留**，非本轮产生，按越界原则未删 |
| 预置资产保留 | `/sdcard/v95/id_ed25519` 原样保留 |
| 环境复原 | reverse 三条仍在；`faillock --user sshtest --reset` 已跑，当前干净 |

---

## 6. 环境/工具链踩坑（供后续复验直接用）

1. **Settings 对话框里绝对不能发 ESC**（`keyevent 111`）——它会**直接关掉整个对话框**。前任 §4.2 已记，本轮再次实测确认（一次误操作把 Settings 连同 SSH 表单一起关掉，还误触了首页 session 行）。**替代收键盘手段：`input keyevent 61`（TAB）**——它会移焦并顺带收起 IME，且不关对话框。
2. **Add SSH 表单里「向下拖」会被当成 drag-to-dismiss**，直接把表单/确认框甩掉；确认框按钮被挤到视口外时**不要**去滚，点「上方的条目」让列表变短即可让按钮回到可视区。
3. **表单滚动手势必须落在表单卡片内**。本机实测可靠的一对：
   - 往下露更多内容（回顶）：`input swipe 540 1500 540 1950 400`
   - 往上露后面内容：`input swipe 540 1600 540 1150 350`
   固定起点（如 y=1650）在键盘弹起时会落到键盘上，表单**静默不滚**（前任 §4.4）。
4. **表单在未聚焦时只渲染视口附近的字段**（紧凑态只剩 Host 一项），必须先点一下字段才会展开。
5. **`Server actions` 是 `content-desc` 不是 `text`**；按行定位它要用「行标题 y + 300」窗口，`+200` 会漏。
6. **`input text` 的 `%s` 仍然不可靠**，沿用 `keyevent 62` 打空格（前任 §4.1，本轮沿用未复现问题）。
7. **认证会话有时限**：「卡出现→输入→Continue」必须单脚本连续完成，任何 `screencap`/`uiautomator` 都会让会话过期、导致正确密码被判失败（§3.2 的第一次失败就是这个原因）。
8. **错密条目会自己烧 faillock**：`setPassword(config.password)` + 服务端 kbdint 会形成 3 次自动重试，3 次即锁号，锁号后卡面变成 `(10 minutes left to unlock)` 且 `Continue` 置灰。**每次 Reconnect 前先 `ResetFaillock`**。
9. **PowerShell**：`$pid` 是只读内置变量（`$apppid` 之类）；数组上 `-notmatch` 是过滤不是返回布尔；`.ps1` 含非 ASCII 且无 BOM 会被 PS 5.1 按 ANSI 解析报错（本目录所有 `.ps1` 保持 ASCII）。

---

## 7. 证据文件清单（节选，均在 `research/v95_verify/5560/`）

| 文件 | 内容 |
|---|---|
| `E2ECHK5560_01_after_del_V95K.png` | 开工先清掉前人遗留 `V95K` |
| `E2ECHK5560_38_after_submit.png` / `.xml` | 提交即弹卡，`Password:` + sshd PAM 失败佐证 |
| **`E2ECHK5560_T1_11_card1.png` / `.xml`** | **① 行级 Reconnect 由面板直接渲染卡片（F12b 出口）** |
| `E2ECHK5560_T1_06_rapid_*.png` / `.xml` | ② Cancel 后 t+200…1500ms 卡片消失、行保留 |
| `E2ECHK5560_T1_13_cancel_t6.png` | 6s 窗快照（被自动重试污染，见 §2.4） |
| **`E2ECHK5560_T2_01_kbup.png` / `.xml`** | **③ 键盘态 footer 1327–1368，卡身上提 485 / footer 上提 415** |
| `E2ECHK5560_T2_02_typed.png` | ④ 8 位掩码 `opencode` 入框 |
| **`E2ECHK5560_T2_result.png` / `E2ECHK5560_T2b_final.png`** | **⑤ Continue 后仍 `Authentication required`，未 Connected（FAIL 证据）** |
| `E2ECHK5560_T3_13_pp_typed.png` | ⑥ keypath + `wrongpp`(7 位) 入框 |
| **`E2ECHK5560_T3_42_submitted.png` / `.xml`** | **⑦ host 最后填仍报 `oc_ssh_error_input`（F12a 阻塞证据）** |
| `E2ECHK5560_CL_24_no_revival.png` | ⑧ 清理后重开 Settings，两条测试条目未复活 |
| `E2ECHK5560_run.ps1` | 设备无关执行件（Shot/Ui/Nodes/TypeText/Tap/ResetFaillock/SshdLog） |
| `E2ECHK5560_answer.ps1` / `_answer2.ps1` | 收紧版一次性认证应答（含高频采样） |
| `E2ECHK5560_fillform.ps1` / `_addentry.ps1` | 表单填充（label 锚点 + 自适应滚动） |
| `E2ECHK5560_cleanup.ps1` | 按行标题删除条目 |
| `E2ECHK5560_nodes.py` / `_rowscan.py` | dump→节点表 / 逐行像素扫描测键盘上沿 |

---

## 8. 给下一位的最短路径

1. **先修两处再谈 F12a/F12b 闭环**：
   - 切 `Private key` 后 `SshConnectionConfig.target` 为空/不可解析（§4.1）——这直接堵死私钥链路。
   - 口令认证服务端已 `Accepted password` 但 app 落 `AUTHENTICATION`（§3.2）——疑似 jsch `promptKeyboardInteractive` 返回 `null` 触发 `"Auth cancel"`。
2. 跑 **F12a** 时：私钥条目一旦能建成，预期卡面应是 **passphrase 挑战**（`SshConnection.kt:239-245` 的 `promptPassphrase` 留存答案正是 F12a 的修复点）；验收到 `sshd` 的 `Accepted publickey for sshtest` 才算闭环。
3. 每次 Reconnect / 建错密条目前**先 `ResetFaillock`**，否则卡面会变 `(10 minutes left to unlock)`、`Continue` 置灰，极易误判为 Cancel 失效（我第一轮就误判过一次）。
4. 表单内**禁用 ESC**，收键盘一律 `keyevent 61`（TAB）。
