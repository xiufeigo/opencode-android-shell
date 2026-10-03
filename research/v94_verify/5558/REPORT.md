# V94 真机复验报告 — emulator-5558 (Pixel_9a, 1080x2424@420dpi)

**包名** `com.xiufeigo.opencodeshell` · **轮次** F9 复验（SshAuthenticate 键盘态 footer）
**日期** 2026-10-01 · **设备** 仅 emulator-5558（未触碰 127.0.0.1:5555 及其他设备）

## 结论速览

| # | 复验项 | 结论 |
|---|--------|------|
| 1 | 触发 SshAuthenticate（真 SSH 认证 prompt） | **PASS** |
| 2 | 键盘态 footer 钉底 + 实点可生效 | **PASS**（键盘上沿下 82px，实点后认证成功 → Connected） |
| 3 | 键盘收起后几何回归 | **PASS**（与基线逐 px 完全一致，零漂移） |
| 4 | 清理条目、重进面板不复活 | **PASS**（删净 3 条，重开面板仅剩原有 10.0.2.2:4095） |

---

## 0. 触发路径（关键，F9 留白项终于跑通）

### 0.1 代码路径

`SshConnection.kt` 的 `SshUserInfo` 有三个会弹 `SshAuthenticate` 的入口：

| jsch 回调 | prompt.confirm | 有无输入框 | 触发条件 |
|---|---|---|---|
| `promptYesNo` | `true` | 无（显示指纹） | 主机密钥未知/变更，`StrictHostKeyChecking=ask`（`SshConnection.kt:104`） |
| `promptPassword` | `false` | 有 | **`config.password == null`**（`SshConnection.kt:213-217`） |
| `uiKeyboardInteractive` | `false` | 有 | 服务端提供 keyboard-interactive（`SshConnection.kt:240-248`） |

**`promptPassword` 这条路在本 app 里走不通**：`SshDialog.submit()` 先过 `validateSshForm`，而 `SshModel.kt:107` 规定 `auth == PASSWORD && password.isNullOrEmpty() → PasswordRequired`，所以提交成功时 `config.password` 必非空 → `promptPassword` 永远直接 `return true`，不弹窗。**即"把密码框留空"是死路。**

**实际可达路径 = 表单密码被拒 → jsch 回落到 keyboard-interactive → `uiKeyboardInteractive` 弹框。** 本轮实测三条用例互相印证：

| 表单密码 | 服务端结果 | 现象 |
|---|---|---|
| `wrongpw`（错） | PAM 拒绝 | jsch 回落 kbdint → **弹 SshAuthenticate**，显示 `Password:` |
| `opencode`（对） | PAM 通过 | jsch 直接认证成功，**不弹框**，直接 Connected |
| 编辑框重连（错） | PAM 拒绝 | 先见 PAM 失败日志，随后回落 kbdint → **弹框** |

弹出的 challenge 文案是 PAM 的 `Password:`（而非 jsch `user@host's password:`），这正是 `uiKeyboardInteractive` 走 `prompt.joinToString()` 的指纹。

完整链路：
```
Settings → Servers → Add SSH server
  → 填 target（必须用 ssh -p 端口 形式，见 §0.2）
  → 填一个「错」的表单密码过闸门（v90 会把拒绝行滚进视野）
  → Add server
  → [确认=true] 主机密钥信任框「Verify host identity」→ Trust and connect
  → [确认=false] 「SSH authentication」+ Password: 输入框 + Cancel/Continue  ← F9 目标
```

### 0.2 两个必须绕开的坑

**坑 1（重要，影响所有人复现）：target 不支持 `host:port` 语法。**
简报给的 `sshtest@127.0.0.1:2222` 会失败。`parseSshHostPart`（`SshConnectionModel.kt:115-127`）只按最后一个 `@` 切 user/host，**端口只认 `-p <port>` 选项**，`host:port` 里的 `:2222` 被原样并进 hostname：

```
parseSshHostPart("sshtest@127.0.0.1:2222") → user="sshtest", host="127.0.0.1:2222", port=22
```
于是 app 去解析主机名 `127.0.0.1:2222` → 解析失败 → 归类 `SshErrorCode.CONNECTION` → 通用文案「Could not establish the SSH connection…」，且 **sshd 侧连一条连接日志都没有**（本轮实测：sshd 只记录了我自己 4 次 `nc` 探针，app 的拨号完全没到服务端）。
**正确写法**：`ssh -p 2222 sshtest@127.0.0.1`。
这可能值得单开一条：面板把 host 原样显示成 `127.0.0.1:2222` 时看着像对的，实际是废的。

**坑 2：`sshtest` 的真实密码不是 `opencode`。** 首次认证被 PAM 拒：
```
pam_unix(sshd:auth): authentication failure; user=sshtest
error: PAM: Authentication failure for sshtest from 127.0.0.1
```
已把该测试账号密码重置为简报约定的 `opencode`（`chpasswd`）并 `faillock --reset`；重置后先在 WSL 侧用 pty 驱动 openssh 独立验证通过，再回 app 复验通过。

### 0.3 为让 kbdint 可达所做的环境改动（服务端，非 app 代码）

WSL Arch 的 sshd 有效配置初始为 `KbdInteractiveAuthentication no`，服务端根本不提供 kbdint，上面的回落路径无从触发。已加 drop-in 并 **reload（SIGHUP，未杀进程）**：

- 新增 `/etc/ssh/sshd_config.d/10-v94-kbdint.conf`：`KbdInteractiveAuthentication yes`
- `sshd -t` 通过 → `systemctl reload sshd` → 生效
- 其余保持：`UsePAM yes` / `PasswordAuthentication yes` / `sshtest(uid=1001)` 有效
- PAM 栈：`sshd → system-remote-login → system-auth → pam_unix`（`pam_faillock` preauth）

> 注：这项改动只是让真 SSH 能产生 keyboard-interactive challenge。若后续不需要复现 F9，删掉该 drop-in 再 reload 即可回到原状。

---

## 1. 触发 SshAuthenticate — PASS

证据：`E2ECHK5558_29_prompt.png` / `ui_E2ECHK5558_29_prompt.xml`（主机密钥信任框）、`E2ECHK5558_30_authprompt.png` / `ui_E2ECHK5558_30_authprompt.xml`（认证框）

**主机密钥信任框（confirm=true）**
```
Verify host identity                              [106,1412]-[454,1459]
[127.0.0.1]:2222                                  [106,1524]-[354,1565]
ecdsa-sha2-nistp256                               [106,1597]-[453,1638]
MD5:c0:7b:d5:bd:49:7e:04:f8:15:52:62:5c:e3:0…      [106,1716]-[975,1758]
Cancel [440,1812]-[551,1853]   Trust and connect [646,1812]-[938,1853]
```

**认证框（confirm=false，F9 目标）**
```
SSH authentication                                [106,1412]-[458,1459]
Password:                                          [106,1524]-[296,1561]
密码输入框 (pw=true)                                [138,1578]-[943,1704]
Cancel [588,1742]-[699,1783]   Continue [794,1742]-[938,1783]
```
（此处 `mInputShown=false`，即**键盘收起基线**）

---

## 2. 键盘态 footer 钉底 + 实点可生效 — PASS

### 2.1 像素事实

点密码框起键盘（`mInputShown=true`），`E2ECHK5558_31_kbup.png` + `ui_E2ECHK5558_31_kbup.xml`：

| 元素 | 键盘收起（基线） | 键盘弹起 | 位移 |
|---|---|---|---|
| SSH authentication | [106,1412]-[458,1459] | [106,927]-[458,974] | −485 px（整卡上提） |
| Password: | [106,1524]-[296,1561] | [106,1039]-[296,1076] | −485 px |
| 密码输入框 | [138,1578]-[943,1704] | [138,1093]-[943,1219] | −485 px |
| **Cancel** | [588,1742]-[699,1783] | **[588,1327]-[699,1368]** | −415 px（**footer 独立钉底**） |
| **Continue** | [794,1742]-[938,1783] | **[794,1327]-[938,1368]** | −415 px |

footer 相对卡身上提量比 body 少 70px = footer 被单独钉在卡的底边，正是 v92 注释所述的 band + pinned footer 行为。

**footer 底 vs 键盘上沿（核心指标）**

键盘上沿由 screencap 逐行扫描测得（白卡 lum>248 的行在 y=1449 满宽 881px，y=1450 突降为 0，即键盘面板起始行）：

```
键盘上沿 y = 1450
footer 底 y = 1368
─────────────────────────────
净空 delta = 1450 − 1368 = 82 px   ← footer 完整位于键盘之上，零遮挡
```

`E2ECHK5558_rowscan.ps1` 输出（节选）：
```
E2ECHK5558_31_kbup.png  1449 : 881 ######…
E2ECHK5558_31_kbup.png  1450 :   0
E2ECHK5558_37_kbup2.png 1449 : 881 ######…
E2ECHK5558_37_kbup2.png 1450 :   0
```
第二次独立复现得到**完全相同**的 footer 几何与同一键盘上沿（y1450），82px 净空可重复。

视觉佐证 `E2ECHK5558_31_kbup.png`：整张 SshAuthenticate 卡片（含 Cancel/Continue 行）完整浮在 Gboard 之上。

### 2.2 实点可生效 → 功能闭环

在**键盘仍弹起**的状态下点 `Continue`（[794,1327]-[938,1368] → tap 866,1348）：

- 输入正确密码 `opencode`（8 个掩码点）
- 服务端：`Accepted keyboard-interactive/pam for sshtest from 127.0.0.1 port 37848 ssh2` + `session opened for user sshtest(uid=1001)`
- app 侧：对话框按 `SshStage.READY` 自动关闭，条目转 **Connected**，端口转发起来 → 服务器列表新增 `127.0.0.1:40643`（标签 `sshtest`）并成为 Default

证据：`E2ECHK5558_32_typed.png`（输入后、footer 仍在 1327–1368）、`E2ECHK5558_38_success.png` / `ui_E2ECHK5558_38_success.xml`（Connected）

> 关键点：表单里填的仍是**错**的 `wrongpw`，最终却认证成功 —— 证明生效的是弹框里新输入的 `opencode`，footer 按钮不仅可见可点，而且**功能闭环正确**。

---

## 3. 键盘收起后几何回归 — PASS

收起键盘用 `input keyevent 111`（ESC，由 IME 解释为 hide），**全程未按 BACK**。`mInputShown` 由 true → false。

`E2ECHK5558_52_kbdown_again.png` + `ui_E2ECHK5558_52_kbdown_again.xml` 与基线 `E2ECHK5558_30_authprompt.png` 逐 px 对比：

| 元素 | 基线（kb 收起） | 收起后 | 差异 |
|---|---|---|---|
| SSH authentication | [106,1412]-[458,1459] | [106,1412]-[458,1459] | 0 |
| Password: | [106,1524]-[296,1561] | [106,1524]-[296,1561] | 0 |
| Cancel | [588,1742]-[699,1783] | [588,1742]-[699,1783] | 0 |
| Continue | [794,1742]-[938,1783] | [794,1742]-[938,1783] | 0 |

**完全一致，无残留抬升、无一位漂移。**

---

## 4. 清理 — PASS

本轮共创建 3 条 SSH 条目（target 均为 `ssh -p 2222 sshtest@127.0.0.1`），逐条 `⋮ → Delete → 确认` 删除：

```
E2ECHK5558_58_menu.png  ⋮ 菜单与 Delete 项
E2ECHK5558_59_confirm.png
E2ECHK5558_70_final.png  删除后仅剩 10.0.2.2:4095，"SSH tunnels" 段整体消失
```

删除后**关闭 Settings 对话框 → 重新进 Home → Settings → 横向滑到 Servers 标签**复验：
`E2ECHK5558_76_clean.png` / `ui_E2ECHK5558_76_clean.xml` 只有原有的 `10.0.2.2:4095 (Default)`，无 `127.0.0.1` 条目、无 "SSH tunnels" 段 —— **未复活**。

> 小注：锚在列表底部的 ⋮ 菜单，其 Delete 弹层贴近屏幕下沿，此时点 Delete 会被当成"点外部"而只关菜单（`E2ECHK5558_59_confirm.png` 即此现象）。删除靠上条目时一切正常。属交互健壮性小瑕疵，非本轮目标。

---

## 5. 顺带发现（未阻断，建议另立条目）

1. **target 不支持 `host:port`**：面板会把 `sshtest@127.0.0.1:2222` 原样显示成条目名，实际拨号到不存在的 hostname，失败文案是通用网络错误，真实原因（hostname 非法）不可见。`parseSshHostPart` 至少应在发现 host 含 `:` 且非 IPv6 时给出 `SshErrorCode.INPUT` 之类的可诊断归因。
2. **行级 Reconnect 不呈现 SshAuthenticate**：条目行的「Reconnect」会把行推进到 `Connecting over SSH…` 并一直挂着（`E2ECHK5558_39_reprompts.png` / `E2ECHK5558_40_prompt3.png`），点条目本身也不打开编辑对话框。若这条路径上服务端要 challenge，UI 没有出口，只能等 `PROMPT_TIMEOUT_SECONDS = 600` 超时。相比之下对话框内的 Connect 路径是好的。
3. **`promptPassphrase` 丢弃答案**：`SshConnection.kt:224-228` 弹框拿到 passphrase 后只 `return accepted`，从未把值写进 `suppliedPassword`，而 `getPassphrase()` 又只读 `config.passphrase`。结果是加密私钥场景下弹框能弹、但输入的 passphrase 被丢弃，必然解密失败。
4. **`sshtest` 密码与简报不符**：交接时约定 `opencode`，实际不匹配（PAM 拒绝）。本轮已重置为 `opencode`，但这说明该测试账号的密码在多次会话间不可靠。

---

## 6. 证据文件清单

目录 `research/v94_verify/5558/`

| 文件 | 内容 |
|---|---|
| `E2ECHK5558_09_servers.png` | Servers 面板入口 |
| `E2ECHK5558_14_refuse.png` | v90 拒绝行自动滚入视野 |
| `E2ECHK5558_26_target.png` | 正确 target 填入 |
| `E2ECHK5558_29_prompt.png` | 主机密钥信任框（confirm=true） |
| **`E2ECHK5558_30_authprompt.png`** | **认证框基线（键盘收起）** |
| **`E2ECHK5558_31_kbup.png`** | **键盘弹起，footer 钉底 1327–1368，键盘上沿 1450** |
| `E2ECHK5558_32_typed.png` | 输入 `opencode` 后 footer 未动 |
| `E2ECHK5558_33_after.png` | 首轮点 Continue → Authentication required（密码未重置前） |
| `E2ECHK5558_37_kbup2.png` | 第二次键盘弹起，几何逐 px 复现 |
| **`E2ECHK5558_38_success.png`** | **Connected，端口转发 127.0.0.1:40643** |
| `E2ECHK5558_50_prompt4.png` | 第三条：基线再次复现 |
| `E2ECHK5558_51_kbup3.png` | 键盘弹起 |
| **`E2ECHK5558_52_kbdown_again.png`** | **键盘收起后与基线逐 px 一致** |
| `E2ECHK5558_39_reprompts.png` | 行级 Reconnect 卡在 Connecting |
| `E2ECHK5558_58_menu.png` / `59_confirm.png` | 删除菜单与确认 |
| `E2ECHK5558_70_final.png` | 删除后列表 |
| **`E2ECHK5558_76_clean.png`** | **重进面板，仅剩 10.0.2.2:4095** |
| `ui_E2ECHK5558_*.xml` | 上述各步的 uiautomator dump（bounds 即像素依据） |
| `E2ECHK5558_nav.ps1` | 导航/输入/截图/探针脚本 |
| `E2ECHK5558_rowscan.ps1` | 逐行像素扫描（测键盘上沿） |
| `E2ECHK5558_del.ps1` | ⋮→Delete→确认 一体化删除 |
| `E2ECHK5558_pwtest.py` | WSL 侧 pty 驱动 openssh 独立验证密码 |
| `E2ECHK5558_kbdint_probe.py` | paramiko 探针（环境无 paramiko，未用上） |
| `E2ECHK5558_logcat.txt` | logcat 全量 |

## 7. 纪律自检

- 仅操作 emulator-5558；未触碰 127.0.0.1:5555 / 5554 / 5556 / 5560 / 5562
- 未改 app 代码、未跑 gradle、未装机、未杀进程（sshd 走 `reload` = SIGHUP；`Disconnect` 是 app 内按钮）
- 全程未按 BACK（keyevent 4）；收起键盘用 ESC keyevent 111
- 证据文件全部带 `E2ECHK5558` 前缀
- 输入分段 ≤8 字符、段间 sleep 1.5s、空格用 `%s`；IME 全程为 Gboard Latin（未出现中文标点污染）
