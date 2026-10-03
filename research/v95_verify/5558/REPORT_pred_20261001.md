# V95 真机复验报告 — emulator-5558 (Pixel_9a, 1080x2424@420dpi)

**包名** `com.xiufeigo.opencodeshell` · **轮次** F12a(promptPassphrase 留存答案) + F12b(行级 Reconnect 弹框出口)
**日期** 2026-10-01 · **设备** 仅 emulator-5558（未触碰 127.0.0.1:5555 及其他设备）

## 结论速览

| # | 复验项 | 结论 |
|---|--------|------|
| 1 | **F12b 行级 Reconnect 弹框出口** | **部分 PASS** — ①弹卡出现且内容正确 ✅ ②键盘态 footer 钉底（净空 82px）✅；**③输入正确密码 Continue → Connected 未跑到**、**④loopback 行未跑到**（设备中途消失，见 §5） |
| 2 | **F12b Cancel 语义（offered-once）** | **未验证** — 设备在第 1 项 ③ 之前消失 |
| 3 | **F12a 加密私钥 passphrase 闭环** | **未验证** — 尚未开始即被设备消失打断 |
| 4 | **清理测试条目** | **未完成（遗留风险）** — 见 §5.2，Pixel_9a AVD 的 userdata 里可能仍留着 `V95R` 条目 |

> **一句话**：F12b 的核心命题——「行级 Reconnect 不再卡死无出口」——在像素层面**已被证实**（认证卡确实由面板直接渲染，内容与键盘态几何都对）；但 **F12a 完全没跑到**，F12b 的 ③④ 与 F12b-Cancel 也没跑到，原因是 Pixel_9a 模拟器在测试中途被外部重启并被替换成另一台裸 AVD。

---

## 1. F12b 行级 Reconnect 弹框出口 — ①② PASS

### 1.0 造条目（密码故意填错）

`Settings → Servers → Add SSH server`，表单实际字段（以 uiautomator bounds 为准）：

| 字段 | bounds | 说明 |
|---|---|---|
| 搜索框 | `[96,560][985,686]` | **x1=96**，用来和表单字段区分 |
| Host or SSH command | `[138,1576][943,1702]` | 表单字段一律 **x1=138** |
| Server name | `[138,1782][723,1788]` | |
| Authentication 下拉 | 选项文案是 **`Private key`**，不是 `Key` | |
| Password | `[138,1615][943,1741]` `pw=true` | 7 个掩码点 = `wrongpw` |

target 必须是 `ssh -p 2222 sshtest@127.0.0.1`（`host:port` 语法本 app 不支持，见 v94 §0.2，本轮沿用）。

提交后条目落成 `V95R` + `Connecting over SSH…`，并**弹出认证框**（键盘收起基线，`mInputShown=false`）：

```
V95R                    [201,1215][287,1256]
Connecting over SSH…    [201,1261][510,1296]
Connecting to SSH server[384,1339][801,1349]
SSH authentication      [106,1412][458,1459]
Password:               [106,1524][296,1561]
密码输入框 (pw=true)     [138,1578][943,1704]
Cancel    [588,1742][699,1783]      Continue [794,1742][938,1783]
```
证据：`E2ECHK5558_R_26_submit_prompt.png` / `ui_E2ECHK5558_R_26_submit_prompt.xml`

按简报把该框 **Cancel** 掉（tap 643,1762），条目落成失败/断开态：`E2ECHK5558_R_27_cancelled.png`。

### 1.1 ① 行级 Reconnect 弹卡出现且内容正确 — **PASS**

点该行 **Reconnect**（tap 408,1434），`E2ECHK5558_R_28_before_reconnect.png` → `E2ECHK5558_R_29_reconnect_prompt.png`：

```
V95R                    [201,1215][287,1256]
Connecting over SSH…    [201,1261][510,1296]
Connecting to SSH server[384,1339][801,1349]
SSH authentication      [106,1412][458,1459]
Password:               [106,1524][296,1561]
密码输入框 (pw=true)     [138,1578][943,1704]
Cancel    [588,1742][699,1783]      Continue [794,1742][938,1783]
```

**关键点：此刻没有任何 SshDialog 打开**（Add 表单早已提交关闭），这张卡是**面板自己直接渲染**的——这正是 F12b 要补的出口。v94 记录的「行级 Reconnect 卡在 Connecting over SSH… 无出口」在本轮**不再复现**。

challenge 文案是 PAM 的 `Password:`（jsch `uiKeyboardInteractive` 的指纹），与服务端日志对得上：

```
19:54:28 pam_unix(sshd:auth): authentication failure; ... user=sshtest
19:54:31 error: PAM: Authentication failure for sshtest from 127.0.0.1     ← 提交时那次
19:57:16 pam_unix(sshd:auth): authentication failure; ... user=sshtest
19:57:18 error: PAM: Authentication failure for sshtest from 127.0.0.1     ← 行级 Reconnect 那次
19:58:13 Connection closed by authenticating user sshtest 127.0.0.1 port 35456 [preauth]   ← 我们 Cancel
```

即：**卡片的 challenge 是真的服务端 kbdint 回落，不是前端伪造的空壳**。

### 1.2 ② 键盘态 footer 钉底 — **PASS**

点密码框（tap 540,1641）起键盘，`E2ECHK5558_R_30_kbup.png` / `ui_E2ECHK5558_R_30_kbup.xml`：

| 元素 | 键盘收起（1.1 基线） | 键盘弹起 | 位移 |
|---|---|---|---|
| SSH authentication | [106,1412]-[458,1459] | [106,927]-[458,974] | −485 px（整卡上提） |
| Password: | [106,1524]-[296,1561] | [106,1039]-[296,1076] | −485 px |
| 密码输入框 | [138,1578]-[943,1704] | [138,1093]-[943,1219] | −485 px |
| **Cancel** | [588,1742]-[699,1783] | **[588,1327]-[699,1368]** | −415 px（**footer 独立钉底**） |
| **Continue** | [794,1742]-[938,1783] | **[794,1327]-[938,1368]** | −415 px |

footer 比卡身上提少 70px = footer 被单独钉在卡底边。

**footer 底 vs 键盘上沿**（`E2ECHK5558_rowscan.py` 逐行扫描，阈值 lum>248，x∈[100,980)）：

```
1449 : 440  ######…     ← 满宽白带（Gboard 面板顶）
1450 :   0              ← 突降，键盘面板起始行
```
```
键盘上沿 y = 1450
footer 底 y = 1368
────────────────────────────
净空 delta = 1450 − 1368 = 82 px   ← footer 完整在键盘之上，零遮挡
```

与 v94 独立复现得到的 82px **完全一致**，几何可重复。

### 1.3 ③ 键盘态输入正确密码 Continue → Connected — **未跑到**

正准备在键盘弹起状态输入 `opencode` 并点 `Continue`（[794,1327]-[938,1368] → tap 866,1348）时，设备掉线：

```
error: device 'emulator-5558' not found
```
`E2ECHK5558_R_31_typed.png` 长度 0 字节（截图根本没生成）。见 §5。

**因此「行级 Reconnect → 弹卡 → Continue → Connected」这条完整闭环本轮没有拿到证据**，尽管前两步（卡出现、键盘态可达）都已证实。**不能据此认为 F12b ③ 已通过。**

### 1.4 ④ loopback 行记录 — **未跑到**

同上。

---

## 2. F12b Cancel 语义（offered-once）— 未验证

需要的步骤（重建错密码条目 → 行级 Reconnect 弹卡 → Cancel → 验证不立即重弹、行保留 → 再点 Reconnect 新 promptId 弹卡重现）在第 1 项 ③ 之前即因设备消失中断，**一行都没跑**。

---

## 3. F12a 加密私钥 passphrase 闭环 — 未验证

**完全没开始。** 计划中的 `V95K` 条目（`Private key` 认证 + `/sdcard/v95/id_ed25519` + passphrase 留空/填错）尚未创建。

环境侧已就绪并已核验（本轮开工时）：
- 设备上 `/sdcard/v95/id_ed25519` 存在（`-rw-rw---- u0_a218:media_rw 444`，密钥以 `u0_a218` 身份可读，符合 jsch 读取 `/sdcard` 路径的常规做法）
- WSL 侧 `/home/sshtest/.ssh/authorized_keys` 已装对应公钥（`-rw------- sshtest sshtest`，`ssh-ed25519 ... v95-test`）
- `sshd_config.d/10-v94-kbdint.conf` 仍在（Pubkey/Password/KbdInteractive 全 yes，v94 reload 过，本轮未动）

但**没有一条 app 侧证据**，所以 F12a 结论只能是「未验证」。

---

## 4. 环境与工具链踩坑记录（对后续复验有用，均为非 bug 或小瑕疵）

1. **`input text` 的 `%s` 不可靠 —— 会把字面量 `%s` 送进 app。**
   早期用 `$t -replace ' ','%s'` 再 `input text`，字段里实际出现 `ssh -p%s2222 sshtest@127.0.0.1`（首个空格变字面量）。后果：target 被判成远程命令，提交后弹
   `Enter a host or SSH connection command. Remote commands and unsupported SSH options aren't allowed.` + `Connection failed`，条目落成 host 为空的废条目。
   **正确做法**：按空格切词，词间发 `input keyevent 62`（KEYCODE_SPACE）真实空格键，**且不要在最后一个词后补空格**（否则字段多一个尾随空格）。
   注：v94 的 `E2ECHK5558_nav.ps1` 用的就是 `%s` 方案，建议同步改掉。

2. **Settings 对话框会被 ESC 关掉。** 收键盘用 `input keyevent 111`(ESC) 会直接把整个 Settings 对话框 dismiss 掉（v94 能在 SshAuthenticate 里用 ESC，是因为那个对话框不消费 ESC）。在 Settings 里**不要**用 ESC 收键盘。

3. **`mInputShown` 不在 `dumpsys window` 里**，这个 build 要用 `dumpsys input_method | grep mInputShown`。v94 报告里那条检测命令在本机是静默失效的。

4. **键盘弹起时表单内滚动条要跟着 footer 走。** 固定 swipe 起点（如 y=1650）会落到键盘上导致滚不动；应从 pinned footer 上沿往回取（如 `footerY-90 → footerY-510`）。

5. **`uiautomator` 只报可见子树**：键盘弹起时 host 字段会被裁出 dump，提交前**无法**目视确认 host，只能靠提交后的报错反推。

6. **PowerShell 两个坑**（都真实卡过流程）：
   - 数组上 `-notmatch` 是**过滤**不是返回布尔 → `if ($arr -notmatch 'x')` 永远为真。要用 `Select-String`。
   - `.ps1` 文件若含非 ASCII（`—`、`⋮`）且无 BOM，PS 5.1 按 ANSI 读会直接 `ParserError`。脚本里别放中文标点/符号。

7. **表单字段 x1=138，Settings 搜索框 x1=96** —— 靠 x 起点区分二者，比按 hint 文本可靠（搜索框一旦有内容，hint 文本就消失了）。搜索框误入内容会把面板过滤成 "No matching settings"。

8. **认证方式下拉的选项文案是 `Private key`**，简报里写的「Key」在 UI 上不存在。

---

## 5. 设备消失事件（阻塞原因）

### 5.1 经过

| 时间 | 事件 |
|---|---|
| ~19:58 | 行级 Reconnect 弹卡已验证、键盘态已验证，准备输密码点 Continue |
| ~19:58+ | `adb devices` 里 **emulator-5558 直接消失**（连同 5554、5556）；`Get-Process qemu-system-x86_64` 只剩 2 个 |
| ~20:03 | 3 个新 emulator 进程启动（pid 7944 / 32944 / 39280） |
| ~20:05 | 5554/5556/5558/5560/5562 全部回来，但**已经不是原来的 AVD 集合** |

现在的端口映射（与本轮开工时完全不同）：

| 端口 | model | 屏幕 | app 装机 |
|---|---|---|---|
| emulator-5554 | sdk_gphone16k_x86_64 | 1080x2424 @420 | 有 |
| emulator-5556 | sdk_gpc_x86_64 | 1366x768 @160 | 有 |
| **emulator-5558** | **sdk_gpc_x86_64** | **3840x2160 @320** | **无** |
| emulator-5560 | sdk_gphone16k_x86_64 | 2076x2152 @390 | 有 |
| emulator-5562 | Pixel Tablet | 2560x1600 @320 | 有 |

**我负责的 Pixel_9a（1080x2424@420、已装 app）已经不在运行集合里**，`adb reverse` 也没了，`/sdcard/v95` 也没了。

### 5.2 未完成的清理（需要接手的人处理）

- Pixel_9a 的 AVD **userdata 是持久化的**（`%USERPROFILE%\.android\avd\Pixel_9a.avd` 仍在磁盘）。条目 `V95R` 很可能**仍留在**该 AVD 的数据里。本轮创建的其它废条目（host 为空的那两条 `V95R`）**已被我用 `⋮→Delete` 删净**（`E2ECHK5558_del_1.png` / `_2.png` / `_final.png`），删完后列表只剩 `10.0.2.2:4095`。
- 因此：Pixel_9a 一旦按 `-avd Pixel_9a` 重启，**需要再删一次 `V95R`**。
- 反向端口转发 `tcp:2222→22` 也需重建。

### 5.3 我没有做的事（刻意）

- **没有重启任何模拟器**：新的 emulator 会去抢 5554/5556 这些**别的 agent 的端口**，越界；也没有 `adb kill-server`（会打断 5560/5562/5555）。
- 没有装机、没有跑 gradle、没有改代码、没有杀进程、没有按 BACK（keyevent 4）。
- 全程只用 `screencap` + `uiautomator dump` + `input`（tap/text/keyevent 62/keyevent 111）。

---

## 6. 证据文件清单

目录 `research/v95_verify/5558/`，全部带 `E2ECHK5558` 前缀。

| 文件 | 内容 |
|---|---|
| `E2ECHK5558_R_20_form_typed.png` / `_21_form_ready.png` | 表单三字段填好（V95R + 7 位掩码 wrongpw，auth=Password） |
| `E2ECHK5558_R_22_pwfilled.png` | wrongpw 入框，foc=true |
| **`E2ECHK5558_R_26_submit_prompt.png`** | **提交时弹出的 SSH authentication 卡（键盘收起基线）** |
| `E2ECHK5558_R_27_cancelled.png` | Cancel 后条目落成断开态 |
| `E2ECHK5558_R_28_before_reconnect.png` | 行级 Reconnect 点击前 |
| **`E2ECHK5558_R_29_reconnect_prompt.png`** | **① 核心：行级 Reconnect 由面板直接渲染出 SshAuthenticate 卡** |
| **`E2ECHK5558_R_30_kbup.png`** | **② 键盘弹起，footer 钉底 1327–1368，键盘上沿 1450，净空 82px** |
| `E2ECHK5558_R_31_typed.png` | **0 字节**（设备已掉线，③ 未发生） |
| `E2ECHK5558_del_1.png` / `_2.png` / `_final.png` | 早期两条 host 为空的废条目删除过程与结果 |
| `ui_E2ECHK5558_R_*.xml` | 上述各步 uiautomator dump（bounds 即像素依据） |
| `E2ECHK5558_nav.ps1` | 导航/输入/截图/收键盘助手 |
| `E2ECHK5558_nodes.py` | dump → `text\tbounds\tpw=\tclass\tfoc=` |
| `E2ECHK5558_clickable.py` / `_band.py` | 找可点节点 / 按 y 带筛节点（用于定位 `Server actions` = ⋮） |
| `E2ECHK5558_rowscan.py` | 逐行像素扫描，测 Gboard 上沿 |
| `E2ECHK5558_fillform.ps1` | 填 SSH 表单（焦点校验 + 自适应滚动） |
| `E2ECHK5558_del.ps1` | `Server actions → Delete → 确认` 循环删除 |

---

## 7. 给接手者的最短路径

1. 重启 Pixel_9a：`-avd Pixel_9a -no-snapshot-save -no-boot-anim`（必须后台跑，前台会 SIGPIPE 杀）。
2. 重建 reverse：`adb -s emulator-5558 reverse tcp:2222 tcp:22`；确认 `/sdcard/v95/id_ed25519` 还在（AVD userdata 持久，应该在）。
3. **先删掉遗留的 `V95R` 条目**（§5.2）。
4. 继续跑：F12b ③ 键盘态输 `opencode` 点 `Continue` → Connected → 记 loopback 行；然后 F12b-Cancel、F12a `V95K` 私钥 passphrase 闭环。
5. 输 target 时**用 `keyevent 62` 打空格**，不要用 `%s`（§4.1）。
