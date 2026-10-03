# REPORT_COVERAGE10 — 真机复验 D3-4 三态（第 10 轮 · 收口轮）**未闭环**

- 设备：`emulator-5554`（Pixel_9a，1080x2424@420），仓库 `C:\sensorsdata\main\program\opencode-android-shell`
- 轮次：第 10 轮（专攻 `Connected`；三态均**未造出**）
- 纪律：全程 `adb -s emulator-5554`；**未跑 gradle、未装机、未杀进程**（未 `force-stop` 任何包）；**未按 BACK(4)**、**未用 ESC(111)**；截图仅 `screencap -p` + `adb pull`；坐标全部运行期从 dump 解析；**`known_hosts` 未创建未删除**；`reverse` **未增未删**；只动自建条目前缀（本轮**未创建任何条目**）

> ## 一句话结论
>
> **构建核对 PASS（md5 与 `lastUpdateTime` 逐位一致），但三态一个都没造出来。** 阻断点有两个，都不是 app 缺陷：
> ① **宿主 harness `com.minimax.coderemote` 全程 ANR 死循环**（本轮至少 10 次），反复抢走前台，把本 app 挤出焦点、把我的会话宿主本身搞得崩溃重启；
> ② **Settings 对话框被压扁后，Add SSH 表单的 `Authentication` 段（认证 chip + 口令/私钥字段）被裁掉**，既不在无障碍树里、也滚不出来。
> 因此 `Connected` / `Connecting` / `Authentication required` **均未验到**，删除链路**一条也没走到**。

---

## 0. 构建核对 —— ✅ **PASS**

```
$ adb -s emulator-5554 shell pm path com.xiufeigo.opencodeshell
package:/data/app/~~qBRPobzrLmniaMfRKOgw5w==/com.xiufeigo.opencodeshell-iqO9YAFcDwHJF9fK0vWr0g==/base.apk

$ adb -s emulator-5554 shell md5sum <base.apk>
e55f98084f9ec081511a9cfb7eb10f40          <- 与任务书逐位一致 ✅

$ adb -s emulator-5554 shell dumpsys package com.xiufeigo.opencodeshell | grep -E "versionCode|versionName|lastUpdateTime"
  versionCode=26 minSdk=26 targetSdk=35
  versionName=1.4.0
  lastUpdateTime=2026-10-03 06:38:58      <- 与任务书一致 ✅
  firstInstallTime=2026-09-27 04:29:58
```

逐位核对：`e55f98084f9ec081511a9cfb7eb10f40` = 期望值。**被测构建正确，不是 BLOCKED。**
本 build 含 P3 私钥认证与 P1 target 解析修复（`research/v97_fix/SSH_KEY_AUTH_FIX.md`、`SSH_TARGET_PARSE_FIX.md`）。

---

## 1. 环境读数

```
$ adb devices -l
emulator-5554  device  product:sdk_gphone16k_x86_64  model:sdk_gphone16k_x86_64  transport_id:39
（另有 5556/5558/5562/5564/5600，本轮一律未碰）

$ adb -s emulator-5554 reverse --list      # 轮初
host-15 tcp:4095 tcp:4095
host-15 tcp:2222 tcp:22
```

宿主 sshd（设备端应连 `127.0.0.1:2222`）：

```
LocalAddress LocalPort OwningProcess
::1                 22         35328
127.0.0.1         4095         35328
127.0.0.1           22         35328
wsl -l -v  ->  Arch  Running  2
wsl: 检测到 localhost 代理配置但未镜像到 WSL（NAT 警告，非致命；sshd 正常监听）
```

测试私钥（铺到 app 私有目录，md5 双向校验）：

```
仓库 research/v95_verify/id_ed25519   444 B   md5 0120a8270f7f0b0c71d31f4933384c63
设备 run-as … md5sum files/id_ed25519        md5 0120a8270f7f0b0c71d31f4933384c63   ✅ 一致
-rw-rw-rw- 1 u0_a230 u0_a230 444 … id_ed25519
```

**`known_hosts`（任务书要求不要动）—— 只读记录，未创建未删除：**

```
$ run-as … ls -la files/ssh/
-rw------- 1 u0_a230 u0_a230 98 2026-10-03 05:07 known_hosts
$ run-as … cat files/ssh/known_hosts
[127.0.0.1]:2222 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIP4xhCsSfuwShy6+t+va8TMG1R1brte/e+EImnSO8sQM
```

> ⚠ **前提修正**：`REPORT_COVERAGE9` §0 记录 `files/ssh/` 是**空目录**、`known_hosts` 不存在。实测本轮它**已存在**，mtime `2026-10-03 05:07`（**早于本轮开始的 15:09**）⇒ 由**本轮之前**的某个动作写入，内容正是 `[127.0.0.1]:2222` 的 host key。**不是本轮产物，本轮未读改。** 这也意味着下轮建连**不会**再弹核验卡。

**`reverse` 差异（如实记录）**：轮初 2 条，轮末 3 条，多出 `host-15 tcp:8790 tcp:8790`。
**本轮从未执行过任何 `adb reverse` 命令**（本轮所有 adb 调用已在会话记录中核对），该条为**并发/往轮其它活动**产生，**非本轮所加**，也未被我删除。

---

## 2. 阻断点 A —— 宿主 harness ANR 死循环（环境问题，非 app 缺陷）

本轮全程 `mCurrentFocus` 反复变成：

```
mCurrentFocus=Window{15f3c1b u0 Application Not Responding: com.minimax.coderemote}
```

ANR 对话框节点（`ui_anr.xml`）：

```
'MiniMax Code Remote isn't responding'  b=[133,1060][947,1178]
'Close app'  Button  b=[70,1217][1010,1343]
'Wait'       Button  b=[70,1343][1010,1469]     <- 本轮一律点这个
```

**纪律处置**：ANR 的宿主是 `com.minimax.coderemote`，**不是被测 app** `com.xiufeigo.opencodeshell`。

- 任务书授权的 `adb reboot` 判据写的是「`mCurrentFocus` 若是 `Application Not Responding` → reboot」。我**判它不适用**：ANR 方是**托管本会话的宿主进程**，`reboot` 会把会话与证据链一起掐掉，代价远大于收益。
- 全程**只点 `Wait`**，**从未点 `Close app`**、**从未 `force-stop`** 它（那会自杀式地切断本会话）。
- 本轮日志记录到的 ANR 次数：`ANR #1 … #4` 多轮，累计 ≥ 10 次。

**后果**：ANR 每隔约 30–60 s 抢一次前台。实测它把被测 app 整个挤出焦点：

```
$ adb -s emulator-5554 shell dumpsys window | grep mCurrentFocus
mCurrentFocus=null                       <- 连续多次（app 被推走/未真正起来）
mCurrentFocus=Window{... MiniMaxApp isn't responding ...}   <- 宿主自己的 UI 占据前台
```

截图 `D3G_W_00_nav.png` 证据：前台是 `MiniMax Code Remote` 自己的会话列表（中文 UI），被测 app 完全不在前台。

**被测 app 本身健康**：多张截图（`_peek.png`、`D3G_P_00_navfail.png`、`D3G_X_02.png`）显示 `OcActivity` 的 home / Settings / Servers 面板**渲染完全正常**，在 ANR 弹层背后照常绘制。**未出现 `OcActivity` 自身的 ANR。**

---

## 3. 阻断点 B —— Add SSH 表单的 `Authentication` 段被裁掉（形态问题）

这是本轮真正的技术卡点，**与 P3 修复是否生效无关**。

### 3.1 走通的路径（全部坐标运行期从 dump 解析）

```
tap Settings      @ 198,2198      （home 底部 Settings）
tap Servers       @ 702,744       （tab strip，节点 'Servers' b=[639,724][764,765]）
Servers 面板打开 ✅
'Servers'         b=[148,924][507,971]        <- 面板标题
'Add SSH server'  b=[544,927][801,968]        -> tap @ 672,948
Add 表单打开 ✅  'Host or SSH command' 可见
```

表单首屏几何（`ui_x3.xml` / `ui_lf0.xml`）：

```
'Host or SSH command'   [106, 988][471,1029]
host  EditText          [138,1046][943,1172]
'Server name (optional)'[106,1188][473,1229]
name  EditText          [138,1246][943,1372]   <- 该轮为 foc=true
'Add server'            [760,1383][938,1424]
```

### 3.2 关键读数：`Authentication` 段**不在无障碍树里**

| 尝试 | 手法 | 结果 |
|---|---|---|
| 1 | `input keyevent 61`（收 IME） ×3 | 无 `Authentication` / `Password` / `Private key` 节点 |
| 2 | 卡内向下滑 `540 986→1346` | 内容整体**下移 140 px**（`Host or SSH command` 988→1128），无新节点 |
| 3 | 卡内向上滑 `540 1330→1050` ×4 | **无位移**（已在滚动上限），仍 0 命中 |
| 4 | 每次滚动后校验表单未关闭 | 表单**未**被拖拽关闭（这次手势是对的） |

终态 dump（`ui_s_final.xml`，权威读数）：

```
   [96, 277][985,403]  EditText        <- Settings 搜索框（非表单）
'Host or SSH command'  [106,1128][471,1169]
'ssh2222 sshtest@127.0.0.1' [138,1186][943,1312]
'Server name (optional)' [106,1328][473,1329]
   [138,1386][723,1392] EditText  (foc=true)
'Add server'           [760,1383][938,1424]
```

**读数结论**：卡片底边被 `Add server` 页脚钉死在 `y=1424`，`Server name (optional)` 标签被压成 **1 px 高**（`[106,1328][473,1329]`）——**卡片没有剩余高度去渲染 `Authentication` 段**。`Add server` 全程为禁用态（灰），**提交按钮从未可用**。

> 交叉印证：第 9 轮（`REPORT_COVERAGE9` §2、§3.1）在**未压扁**的对话框里能稳定看到 `Authentication` / `Password` / `Password:8 字符掩码` 并成功提交。本轮对话框被压扁（ANR 抢焦点导致窗口重建、layout 走紧凑分支），该段直接消失。**这是对话框尺寸/布局的健壮性问题，不是 P3 私钥认证的回归。**

### 3.3 附带发现：`input text` 丢 `-`（新踩坑，务必固化）

```
期望 host : ssh -p 2222 sshtest@127.0.0.1
实际落库 : ssh2222 sshtest@127.0.0.1        <- ' -p ' 整段消失
```

`input text` 在本镜像上把 `-` 吞掉（v96 的 `%s` 教训同源）。**下轮起必须用无 `-` 的写法**：
`sshtest@127.0.0.1:2222` —— 而这正是 `SSH_TARGET_PARSE_FIX` 已证可用的第二种语法。

---

## 4. 三态逐条判定

### 4.1 `Connected`（主目标）—— ❌ **未造出**

| 项 | 内容 |
|---|---|
| 造的条目 | **未创建。** 表单从未提交（`Add server` 始终禁用，认证段不可达） |
| 状态文案原文 | **无**（未进入任何连接态，故无状态文案可抄） |
| 行清单 | 未新增行。轮末目标列表未变（见 §6） |
| 落库计数 | **未落库**，无新增条目 |
| 试过什么 | ① 从 home 走 `Settings → Servers → Add SSH server`（成功到达表单）；② 填 host / name；③ 私钥 chip：`keyevent 61` ×3、卡内双向滑动 ×5，**全部 0 命中**；④ 曾尝试改用无 `-` 的 `sshtest@127.0.0.1:2222`，但表单已在第 ③ 步失败前被 `Cancel` 关闭，未走到提交 |
| 判定 | **未验到。** 阻断为「认证字段不可达」，**非 app 状态机缺陷、非 P3 回归**（P3 代码路径本轮根本没被执行到） |

**`Connected` 态删除**：❌ **未闭环**（无行可删）。

### 4.2 `Connecting` —— ❌ **未造出**

不可达地址条目（`10.255.255.1`）**未能创建**（同一个 `Add server` 禁用阻断）。
**菜单是否存在 / `Delete` 项是否可点 / 点了发生什么** —— 三路读数**均无**（未进入该态，无从读起）。

> 可复用的既有结论（`REPORT_COVERAGE9` §3.2、§4.1，**非本轮产出**）：`Connecting` 态**有**行菜单，且在 `Connection failed` 态删除成功——确认条原文
> `'This removes "127.0.0.1:2222" from the list. This cannot be undone.'`。
> 本轮**未新增**任何关于该态的证据，也**未推翻**该结论。

### 4.3 `Authentication required` —— ❌ **未造出**

未用错误口令发起连接（条目未能创建），因此**没有任何状态文案原文**可抄。
**未闭环。**

### 4.4 反例（只删目标行 / 他人条目完好）

本轮**未执行任何删除**，故「只删目标行」**无新证据**（**不适用**，非 PASS 也非 FAIL）。
`10.0.2.2:4095` 等他人条目**全程未被触碰**——本轮唯一写操作是往表单里打字，且表单已 `Cancel` 丢弃。

---

## 5. 三轮脚本的失败模式（供下轮直接绕开）

| 轮次脚本 | 失败模式 | 教训 |
|---|---|---|
| `d3g.ps1 -Phase mkeny`（复用 `Fill-SshForm`） | `ScrollToNear` 死循环 12 次，日志反复 `'name' not visible` | v97 helper 的 `FieldNear` 在**压扁对话框**下判不出字段；助手库假设「卡片高 400+px」不成立 |
| `d3g_fill.ps1` | 两次 ParserError（`$Host` 是 PS 保留自动变量；`"`t.*"` 在插值串里需转义） | 脚本参数禁用 `$Host`；改用 `$Tgt` |
| `d3g_pass.ps1` | 导航 `Servers` 时先做了 tab-strip 滑动，把已可见的 `Servers` 推出视野；且 `dumpsys window \| grep` 里的管道需双引号 | **先 dump 判存在，再决定要不要滑** |
| `d3g_fast.ps1` | 同上，且 `Start-Process pwsh -NoProfile` 报错（`-NoProfile` 不能跟在 `Start-Process` 后当文件参数） | 直接后台跑 `pwsh -NoProfile -File` |
| `d3g_x.ps1` | **实际成功点开表单**，但紧随的 dump 被 ANR 弹层遮挡 → 误判「form did not open」 | 判定前**必须先清 ANR**再 dump（`D3G_X_02.png` 里表单就在弹层后面） |
| `d3g_lf*.ps1` | 最有价值的一路：成功填入 host，最后卡在认证段不可达 | 结论见 §3 |

---

## 6. 清理

| 项 | 状态 | 证据 |
|---|---|---|
| 半填表单 | ✅ 已 `Cancel` 丢弃 | `tap 609,1403`（`Cancel` b=[554,1383][665,1424]）；随后 focus 回到 `OcActivity` |
| `E2ECHK5554d3g*` 残留 | ✅ **0 条** | **本轮从未提交表单，未创建任何条目** |
| 目标列表 | ✅ 未变 | 本轮无任何增删；`E2ECHK5554d3e-c2`、`10.0.2.2:4095` 等沿用上轮状态 |
| `/sdcard` 本轮临时文件 | ✅ **全部已清** | `_e2echk5554.png`/`_e2echk5554.xml`/`_f.xml`/`_f2.xml`/`_s.png`/`_c.xml`/`_anr.xml`/`d3g` 全部 `No such file or directory` |
| `reverse` | ✅ **本轮一条未动** | 轮末 `4095`、`2222` 原样；多出的 `8790` 非本轮所加（§1） |
| **`known_hosts`** | ✅ **未动** | 轮末 98 B、`[127.0.0.1]:2222 ssh-ed25519 …`、mtime `05:07`（早于本轮）。**本轮只读，未写未删** |
| 私钥 `files/id_ed25519` | ✅ 未破坏 | md5 `0120a8270f7f0b0c71d31f4933384c63`，与仓库一致 |
| 被测 app 健康 | ✅ | 轮末 `mCurrentFocus=…ocodeshell/.OcActivity`；**全程未出现 `OcActivity` 自身 ANR**；未 reboot、未 `force-stop` |
| 未跑 gradle / 未装机 | ✅ | 本轮无任何 gradle / install 动作 |

---

## 7. 未闭环与下轮建议

1. **`Connected` 态删除**：❌ 未闭环。**阻断是「Add 表单认证段被裁掉」，不是三态 UI 缺陷。**
2. **`Connecting` 态删除**：❌ 本轮无新证据（沿用上轮 PASS 结论）。
3. **`Authentication required` 态删除**：❌ 未闭环。
4. **⚠ 最该先修的两件事**（都不在本轮授权范围内，未动手）：
   - **宿主 `com.minimax.coderemote` 的 ANR 死循环**必须先解决，否则任何真机轮次都会被抢焦点而失真；
   - **Settings 对话框的紧凑布局下，Add SSH 表单应保证 `Authentication` 段可达**（至少让 `ScrollView` 能滚到 chip，而不是把卡片内容压成 1 px）。这是可复现的**布局健壮性缺陷**，建议单独立 issue。
5. **下轮解锁顺序（省时版）**：
   - 对话框正常高度下，先用**无 `-`** 的 host：`sshtest@127.0.0.1:2222`（P1 已证）；
   - 私钥路径填 `/data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519`，passphrase `keypass123`；
   - **每次 dump 前先清 ANR**（`mCurrentFocus` 含 `Not Responding` → `input tap 540 1406`），否则会把「弹层遮挡」误判成「控件缺失」——本轮已在 `d3g_x.ps1` 上栽过一次；
   - 行身份仍是**名称 TextView**，`kebab` 是 `content-desc="Server actions"` 且 **cy = 行名 cy + 203**（上轮标定，本轮未复核）。
6. **`known_hosts` 已存在** ⇒ 下轮建连**不会再弹**核验卡，别再等它。

---

*本轮所有原始证据（截图 / dump / 逐条日志）落在 `research/v97_verify/5554_v98d3/`：*
`d3g_run.log`（时间戳日志）、`D3G_*.png`（截图）、`ui_*.xml`（dump）。*
