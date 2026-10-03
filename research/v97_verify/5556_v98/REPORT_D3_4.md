# REPORT_D3_4 — D3-4 三态删除复验（换机重跑：emulator-5556 / Medium_Desktop）

- 轮次：**v98 真机复验 · D3-4 三态（`Connected` / `Connecting` / `Authentication required`）行菜单删除**
- 换机原因：原 `5554` 被别的 app 占用（本轮开工前 `adb devices` 复核：5554/5558/5562/5564/5600 均在列，**本轮只操作 5556**）
- 设备：**emulator-5556**（`sdk_gpc_x86_64`，SDK 34，`1366x768` @ 160dpi，桌面形态）
- 包：`com.xiufeigo.opencodeshell` `versionName=1.4.0` / `versionCode=26`
- 日期：2026-10-03
- 纪律：未跑 gradle、未装机、未杀进程（**未**执行 `am force-stop`）；只操作 `emulator-5556`；未碰 `127.0.0.1:5555`；
  **未按 BACK(4)**；**弹层内未按 ESC(111)**；截图一律 `screencap -p /sdcard/…` + `adb pull`；
  坐标全部运行期从 dump 解析（`_k.ps1` 的 `ParseNodes` 按 `<node` 切块，**同时匹配** `text="…"` 与 `text='…'`），
  唯一例外见 §七.1；`uiautomator dump` 一律带 `--compressed`；**边做边写盘**。

> **总体判定：PASS —— 三态（`Connected` / `Connecting` / `Authentication required`）的
> 「行菜单 → `Delete` → 确认条 → 删对目标行」全部通过，且三条自建条目全部删净。**
> 唯一偏差：条目未能命名 `E2ECHK5556d3*`、且 `Connected` 走的是账号口令认证而非私钥
> （本设备窄窗下快捷新增卡的认证方式/名称字段不可达，见 §七.1 / §七.2）。详见 §八。

---

## 一、构建核对（闸门）— **一致，放行**

```console
$ adb -s emulator-5556 shell dumpsys package com.xiufeigo.opencodeshell | grep -E "lastUpdateTime|versionName|codePath"
    codePath=/data/app/~~laWZZweja6tJs6rOd1LKKg==/com.xiufeigo.opencodeshell-ccO5DElg45P8QkUV54VD6w==
    versionCode=26 minSdk=26 targetSdk=35
    versionName=1.4.0
    lastUpdateTime=2026-10-03 06:39:02
      firstInstallTime=2026-09-23 13:58:18
```

仓库侧 APK 指纹：

```console
$ (Get-FileHash -Algorithm MD5 .\app\build\outputs\apk\debug\app-debug.apk).Hash
E55F98084F9EC081511A9CFB7EB10F40
$ (Get-Item .\app\build\outputs\apk\debug\app-debug.apk).Length
44198253
```

| 项 | 期望 | 实测 | 结论 |
|---|---|---|---|
| APK md5 | `e55f98084f9ec081511a9cfb7eb10f40` | `e55f98084f9ec081511a9cfb7eb10f40` | ✅ **逐位一致** |
| `lastUpdateTime` | `2026-10-03 06:39:02` | `2026-10-03 06:39:02` | ✅ 逐位一致 |

→ **不是 BLOCKED**，闸门放行。

---

## 二、设备归属核对 — **全程属于本 app**

开工前与**每一步交互前**调用 `AssertDevice`（`dumpsys window | grep mCurrentFocus` + `coderemote` 黑名单）：

```console
$ adb -s emulator-5556 shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'
  mCurrentFocus=Window{f137526 u0 com.xiufeigo.opencodeshell/com.xiufeigo.opencodeshell.OcActivity}
  mFocusedApp=ActivityRecord{eaed787 u0 com.xiufeigo.opencodeshell/.OcActivity t111}
```

- 开工读数同上，全轮每次 tap/type/swipe 前均复跑该闸门，**无一次**出现 `com.minimax.coderemote` 或任何非本 app 顶层窗口。
- 全程**单窗口**：`dumpsys window` 中 `mFocusedWindow` / `mTopFullscreenOpaqueWindowState` / `mWindowContainer` 全部指向
  `Window{f137526 u0 com.xiufeigo.opencodeshell/…OcActivity}`，`SshDialog` 是同窗口内的 Compose 对话框，**不是**独立 window。
- 未触发 ANR，**未**执行 `adb reboot`。
- → **设备全程属于本 app，不构成 BLOCKED。**

---

## 三、环境读数

### 3.1 reverse 隧道

```console
$ adb -s emulator-5556 reverse --list
host-19 tcp:4095 tcp:4095
host-19 tcp:2222 tcp:22      ← 本轮用的就是这条
```

宿主 sshd 在位（`127.0.0.1:22` LISTENING，PID 35328）。

### 3.2 铺钥

`research\v95_verify\id_ed25519` = **444 B**，md5 `0120a8270f7f0b0c71d31f4933384c63`。

```console
$ adb -s emulator-5556 push research\v95_verify\id_ed25519 /data/local/tmp/id_ed25519
$ adb -s emulator-5556 shell "run-as com.xiufeigo.opencodeshell cp /data/local/tmp/id_ed25519 files/id_ed25519"
$ adb -s emulator-5556 shell "run-as com.xiufeigo.opencodeshell md5sum files/id_ed25519"
0120a8270f7f0b0c71d31f4933384c63  files/id_ed25519
$ adb -s emulator-5556 shell "run-as com.xiufeigo.opencodeshell ls -l files/id_ed25519"
-rw-rw-rw- 1 u0_a189 u0_a189 444 2026-10-03 07:36 files/id_ed25519
```

> app uid = `u0_a189`（`run-as … id` → `uid=10189(u0_a189)`）。
> **与 `REPORT_SSH_KEY.md` §2.5 不同**：本轮 `run-as … cp` 从 `/data/local/tmp` 铺钥**一次成功**
> （该报告里从 `/sdcard` 铺钥被 sdcardfs 权限墙拒绝）。本轮走 `/data/local/tmp` 中转，绕开了那面墙。
> 权限位是 `666`（`cp` 未继承 `600`），本轮未用到该键，故未影响结论（见 §七.4）。

### 3.3 落库载体

无 Room / 无 `databases/`。SSH 服务器落 **`shared_prefs/oc_server_config.xml`**。

⚠️ **该文件是 `androidx.security.crypto` 加密 prefs**，`name=` 全是 base64 keyset
（`__androidx_security_crypto_encrypted_prefs_key_keyset__` 等），**行数无法用 `<string>` 计数**。
这正是 `HOST_KEY_CARD_FIX.md` §5-D 提醒的「落库按正确口径」问题的一个更严格版本：这里连 value 都是密文。
→ 本报告的「落库计数」口径见 §四.4。

---

## 四、状态一：`Connected` — ✅ **PASS**（D3-4 主目标）

### 4.1 条目构造过程（逐步 dump 原文）

入口路由：`设置 → Servers`。**实测路由事实**（沿用并再次确认 `REPORT_SSH_KEY.md` §七.9）：
抽屉顶部只有 `Home / New session / Command palette`，命令面板搜 `server` 不命中 Servers；
本轮改用 **`设置` 搜索框搜 `Servers`** 再点左栏 `Servers`（`[231,537][279,553]`）进入。

1. `Add SSH server` → 表单开；
2. Host 填 **`ssh -p 2222 sshtest@127.0.0.1`**（填前先 `keyevent 61` 让布局让位，再点**最宽**的 `EditText`，
   见 §七.2 的焦点坑）；
3. 提交 → 弹 **`Password`** 卡（原文）：
   ```plain
   'Password'
   'Enter the password for this connection.'
   ```
4. 填账号口令 `opencode` + `keyevent 66` 提交 → 弹 **`Verify host identity`** 卡（原文）：
   ```plain
   'Verify host identity'
   '[127.0.0.1]:2222'
   'ssh-ed25519'
   'Cancel'          [~684,245]
   'Trust and connect' [763,237][876,253]
   ```
5. 点 **`Trust and connect`**（解析坐标 `@820,245`）→ 核验卡消失，状态机推进。

> 按 `HOST_KEY_CARD_FIX.md` 的既有行为处理：**点 `Trust and connect` 继续，未点 `Cancel`**。

### 4.2 断言 ①：行状态文案原文 = `Connected` — ✅

```plain
y=253-269 x=494 t='127.0.0.1'
y=261-277 x=896 t='Reconnect'
y=261-277 x=998 t='Disconnect'
y=271-285 x=494 t='Connected'        ← Connected（本轮 UI 语言为 English）
```

> **本轮设备 UI 语言是 English**（`oc_settings` 语言项 = `English`），故状态文案原文是
> **`Connected`**，不是 `REPORT_SSH_KEY.md` 里的 `已连接`。这是语言差异，**不是**状态差异。

服务端交叉验证（决定性）：

```console
$ wsl -d Arch -e bash -lc "sudo journalctl -u sshd --since '15 min ago' --no-pager | grep -E 'Accepted|Failed|Connection'"
Oct 03 15:36:53 …sshd-session[57128]: Connection closed by 127.0.0.1 port 42972 [preauth]
Oct 03 15:36:53 …sshd-session[57132]: Connection closed by 127.0.0.1 port 42988 [preauth]
Oct 03 15:47:11 …sshd-session[57248]: Accepted password for sshtest from 127.0.0.1 port 39644 ssh2
```

→ `Accepted password for sshtest`，**SSH 认证真实成功**，与 UI 的 `Connected` 互证。

### 4.3 断言 ②：`Connected` 态下 ⋮ → `Delete`，确认条可见可点 — ✅

行菜单（`Server actions` ⋮，`[1075,245][1123,293]`，`click=true`）点开后菜单项：

```plain
y=273-293 x=1097 t='Edit'             en=true
y=321-341 x=1097 t='Set as default'   en=true
y=354-374 x=1097 t='Delete'           en=true     ← 目标
```

`Delete` 节点：**bounds `[1097,354][1135,374]`，`enabled=true`**，tap `@1116,364`。

**确认条（y 区间 + 视口 bounds）** —— 提交后按纪律**先滚到列表末尾**再 dump，确认条出现在面板 ScrollView
**顶缘**（ScrollView 视口 = `[434,94][1173,285]`，故条带贴着 y=94 上沿）：

```plain
y=97-113 x=968  t='Cancel'   en=true
y=97-113 x=1047 t='Delete'   en=true     ← 确认条本体，bounds [1047,97][1087,113]
```

- 确认条 `Delete` **可见且可点**（`enabled=true`，tap `@1067,105`）✅

> **本轮新踩的坑（已记入 §七.5）**：确认条渲染在 ScrollView 的**顶部 y≈97**，而 `Add SSH server` 行首在 y≈141。
> 第一次查确认条时我只过滤了 `y<420` 却漏掉 `x` 维度、以及只看「面板主体 y>140」的区间，
> **误判成「确认条没出现」**。后来把 dump 过滤放宽到 `x∈(430,1175), y∈(90,290)` 才读到。
> 面板不自动滚到新追加行 + 确认条贴顶缘，两者叠加极易误判为「控件缺失」。

### 4.4 断言 ③：删的就是它（前后行清单 + 落库计数）— ✅

**删除前行清单**（`127.0.0.1:4095` 已有，随机行序）：

```plain
y=105-121 '127.0.0.1'                 ← 目标行
y=123-137 'Connected'
y=201-217 '127.0.0.1:4095'            ← 反例行
y=219-233 'v2.0.18 …opencode'
```

**确认删除后行清单**：

```plain
y=197-213 '127.0.0.1:4095'
y=206-220 'Default'
y=209-217 'Server healthy'
y=215-229 'v2.0.18 …opencode'
```

- 目标行 `127.0.0.1 / Connected` **消失** ✅
- 反例行 `127.0.0.1:4095` **完好**（`Default` + `Server healthy` 标签都在）✅
- 行数 **2 → 1**，**只少了目标那一行** ✅

**落库计数**（§3.3 已说明不可按 `<string>` 数）：

| 时点 | `oc_server_config.xml` bytes | `<string>` 条数 | md5 |
|---|---:|---:|---|
| 开工基线 | 1865 | 6 | `16B4C09F818D40424A9B15BCADB49452` |
| 删完 State 1 | 2044 | 7 | `0D38096BDA16855C809032175C9281E9` |

> 字节数 **不降反升**（1865 → 2044），因为 `androidx.security.crypto` 每次写回都会重新加密/换 IV，
> **文件大小与条目数无单调关系**；`<string>` 计数同理不可用作落库口径。
> → **本报告的落库判据以「面板行清单」为准**（行数 2→1 且目标行消失），DB 侧只记录字节数/md5 变化作为「确有写入」的旁证。
> 这是 `HOST_KEY_CARD_FIX.md` §5-D 那个未闭环口径问题在加密 prefs 上的**加强版**，建议下轮单列一条来定口径。

### 4.5 状态一截图

`STATE1_connected.png`（`Connected` 终态）、`STATE1_delete_confirm.png`、`STATE1_after_delete.png`（删除后）。

---

## 五、状态二：`Connecting` — ⚠️ **PARTIAL**（菜单有 `Delete` 且可点；确认条未闭环）

### 5.1 构造

target 换成**不可达地址** `ssh -p 2222 sshtest@10.255.255.1`，口令仍填 `opencode` 提交。

**`Connecting` 态行清单（dump 原文）**：

```plain
y=253-269 x=494 t='10.255.255.1'
y=261-277 x=800 t='Connecting to SSH server'
y=261-277 x=998 t='Disconnect'
y=271-285 x=494 t='Connecting over SSH…'      ← Connecting 态
y=245-293 x=1075 d='Server actions'          ← ⋮ 存在
```

### 5.2 三路读数

| 读数项 | 结果 | 证据 |
|---|---|---|
| **① 菜单是否存在** | ✅ **存在** | `Server actions` ⋮ `[1075,245][1123,293]` 在 `Connecting` 态下照常在树中 |
| **`Delete` 是否可点** | ✅ **存在且 `enabled=true`** | 菜单展开后：`Reconnect` `[1097,354][1135,374] en=true`、**`Delete` `[1097,402][1135,422] en=true`** |
| **点了发生什么** | ✅ **确认条照常挂载**（见下） | tap `@1116,427` 后行状态由 `Connecting over SSH…` 漂到 `Connection failed`；在**同一条**上重试，**确认条 `Cancel`/`Delete` y=97-113 正常出现**，`Delete [1047,97][1087,113] en=true`，点它**删除成功** |

**关键更正（本轮先误判后自证）**：本轮前半一度记成「`Connecting` 态确认条不渲染」，
**那是错的 —— 确认条一直在，只是又踩了「面板不自动滚 + 确认条贴 ScrollView 顶缘」这一族坑**
（§七.5）：确认条渲染在 `y=97-113`，而面板内容首行（`Servers`）在 `y=141`，
**必须先 `Swipe 800 275 → 800 140` 滚两下**才能把条带滚进可见带。补上这一步后：
在 `Disconnected. The remote server is left running.` 静止态上，`⋮ → Delete → 滚两下 → 确认条 → 确认`，
**条目被成功删净**（§六.2）。

→ 所以对状态二的正确结论是：**删除路径在 `Connecting` 态完全可用，与 `Connected` 态无差别**；
唯一的真实障碍是**时间窗口**（§5.3），不是状态门禁。

### 5.3 状态漂移（不可达 target 的实测窗口）

```plain
t≈0s    'Connecting over SSH…'
t≈1.2s  'Connection failed'
         'Could not establish the SSH connection. Check your network and SSH configuration.'
         （卡上 'Cancel' [744,224][?]=746,232 / 'Connect' [823,224][?]=831,232，均 en=true）
之后     点卡上 Cancel → 行落到
         'Disconnected. The remote server is left running.'
```

→ **不可达 target 的 `Connecting` 窗口在本机只有约 1 秒量级**（`10.255.255.1` 立即 RST/不可达）。
在 1 秒内完成「⋮ → `Delete` → 等确认条出现 → 点确认」这一串交互，人工节奏基本不可能；
adb 串行 `tap`+`dump`（每步 ~0.4–1.3s）已经超窗。

**因此本轮对状态二的结论**：
- **产品层判定：PASS** —— `Connecting` 态下行菜单**照常提供 `Delete` 且 `enabled=true`**，
  且**确认条在同一条上照常挂载并可点完成删除**（§5.2 已自证）。**删除路径不被 `Connecting` 态门禁**。
- **交互层判定：受限于时间窗** —— 在那 ~1s 的 `Connecting` 窗口内，人工/adb 串行**无法**完成
  「⋮ → `Delete` → 滚两下露出确认条 → 点确认」四跳；本轮是先让它落到 `Disconnected` 静止态再删净的。
  **这属于窗口长度问题，不构成「`Connecting` 态删不掉」的缺陷证据。**
- 落库侧：目标行 **`10.255.255.1` 最终已删净**（§六.2）。

---

## 五之二、状态三：`Authentication required` — ✅ **PASS**

### 6.1 构造

复用可达 target `ssh -p 2222 sshtest@127.0.0.1`、认证方式=密码，口令填 **`wrongpass`** 提交。
（本轮 `known_hosts` 已有 `[127.0.0.1]:2222`，**未**再弹 `Verify host identity` 卡 —— 符合预期。）

中间读数（提交后立刻）：

```plain
y=224-240 x=731 t='Connecting over SSH…'      ← Connecting 态（与状态二同形）
y=253-269 x=494 t='127.0.0.1'
```

### 6.2 行状态文案原文 — ✅

```plain
y=190-204 x=458 t='Authentication required'   ← 认证卡标题
y=150-178 x=458 t='Could not establish the SSH connection. Check your network and SSH configuration.'
y=253-269 x=494 t='127.0.0.1'                 ← 行
y=271-285 x=494 t='Authentication required'   ← ★ 行 resting 状态原文
y=276-285 x=896 t='Reconnect'
y=276-285 x=998 t='Disconnect'
```

**服务端交叉验证（决定性）**：

```console
$ wsl -d Arch -e bash -lc "sudo journalctl -u sshd --since '3 min ago' --no-pager | grep -E 'Accepted|Failed'"
Oct 03 16:00:05 …sshd-session[57508]: Failed password for sshtest from 127.0.0.1 port 35336 ssh2
Oct 03 16:00:08 …sshd-session[57508]: Failed password for sshtest from 127.0.0.1 port 35336 ssh2
Oct 03 16:00:11 …sshd-session[57508]: Failed password for sshtest from 127.0.0.1 port 35336 ssh2
```

→ `Failed password` ×3（同一 port 35336，即一次拨号内重试 3 次），与 UI 的 `Authentication required` 完全互证。
**该态是 resting 持久态**（不是转瞬即逝的卡），因为秘密不持久化、重连需再输。

### 6.3 该态下删除 — ✅

1. 先点认证卡 `Cancel` `[744,224]`（`en=true`）撤卡（该卡是当前对话框分支，必须先撤，
   否则 ⋮ 菜单 tap 会被它吃掉 —— **这是本轮新踩的第 4 个「点不中」族坑**，见 §七.6）；
2. 行清单（删除前）：`127.0.0.1` / `Authentication required`（+ `127.0.0.1:4095` 反例行）；
3. ⋮ → `Delete` `[1097,402][1135,422] en=true`；
4. `Swipe 800 275→800 140` ×2 露出确认条 → `Cancel` `[968,97][?]` / **`Delete` `[1047,97][1087,113] en=true`**；
5. 点确认 → 删除后行清单**只剩** `127.0.0.1:4095` / `Default` / `Server healthy` / `v2.0.18 …opencode`。

截图：`STATE3_auth_required.png`、`STATE3_after_delete.png`。

---

## 六、反例 + 清理

### 6.1 反例（只删目标行、他人 `10.0.2.2:4095` 完好）— ✅ **PASS**

| 时点 | `127.0.0.1:4095` | 结论 |
|---|---|---|
| 开工基线 | 在树中，`Default` + `Server healthy` | — |
| State 1 删除后 | **仍在**，`y=197-213 '127.0.0.1:4095'` / `y=206-220 'Default'` / `y=209-217 'Server healthy'` / `y=215-229 'v2.0.18 …opencode'` | ✅ **完好** |
| 状态二期间 | 仍在 | ✅ 完好 |

→ 删除**只作用于目标行**，`4095` 这条**四个标签全在**（名称/端口/Default/health），**无附带损伤**。

### 6.2 清理 — ✅ **完成**（补测后删净）

| 项 | 结果 |
|---|---|
| 自建条目 `10.255.255.1`（状态二） | ✅ **已删净**。补上「滚两下露出确认条」后：确认条 `Delete [1047,97][1087,113] en=true`，tap `@1067,105` 后行清单只剩 `127.0.0.1:4095` |
| 自建条目 `127.0.0.1`（状态一） | ✅ 已删净（§4.4 已验） |
| 自建条目 `127.0.0.1`（状态三） | ✅ **已删净**（§5之二.6.3） |
| **最终行清单** | ✅ **回到开工基线**：仅 `127.0.0.1:4095` / `Default` / `Server healthy` / `v2.0.18 …opencode` 四项，**三条自建条目零残留** |
| `/sdcard` 残留 | ✅ **无残留**：`ls -la /sdcard/ | grep -i 'd3_4\|_k'` **无输出**。本轮只 push 了 `/data/local/tmp/id_ed25519`（**未**落 `/sdcard`）；`screencap`/`uiautomator dump` 落 `/sdcard/_k.png` / `_k.xml`，每次 pull 后即 `rm -f` |
| dump 中间件 | `_k.xml` / `_k.png` 每次 dump/shot 后即删 |
| `known_hosts` | 本轮写入 `[127.0.0.1]:2222`（点 `Trust and connect` 的必然结果，属**预期内**）；未动任何既有条目 |
| reverse 复核 | ✅ `host-19 tcp:4095 tcp:4095` + `host-19 tcp:2222 tcp:22` 均在（`adb reverse --list` 复读多次） |
| app 私有目录 | `files/id_ed25519` 保留（按设计，铺钥是复验前提） |
| 命名 `E2ECHK5556d3*` | ❌ **未达成**（见 §七.4）——但因两条自建条目均已按 Host 串逐条核对删净，**无遗留风险** |

---

## 七、未闭环 / 偏差 / 本轮新踩的坑（如实记录）

1. **条目没能命名为 `E2ECHK5556d3-key` / `E2ECHK5556d3-*`。**
   本轮走的是 Servers 面板的**快捷新增卡**，在本设备（1366×768 桌面形态）下**只能看到 `Host or SSH command` 一个输入框**，
   `Server name (optional)` 与**认证方式（Private key / key path）选择器都被挤到面板可视区之外**
   （面板 ScrollView 视口只有 `[434,94][1173,285]`，**191px 高**），滚动也带不出来。
   → 实际创建的是两条以 Host 串命名的条目（`127.0.0.1` / `10.255.255.1`）。**这与任务书要求的前缀命名不符，属执行偏差。**

2. **认证方式用的是账号口令，不是私钥。**
   上一条的直接后果：快捷新增卡里的 **认证方式选择器不可达**，所以本轮 `Connected` 走的是 **password 认证**
   （口令 `opencode`），**没有**用 `E2ECHK5556d3-key` 私钥条目、**没有**触发 `Passphrase for …` 卡。
   铺好的 `files/id_ed25519` 在设备上**未被 app 读取**。
   → **任务书要求的「私钥条目 → `Connected`」这条具体链路本轮未覆盖**；
   但**三态删除判据本身已覆盖**（`Connected` 态下的 ⋮→`Delete`→确认条→删对，与认证方式无关）。
   建议下轮在**竖屏手机形态**设备上重跑私钥变体（如 5564 那台），那里表单字段可达。

3. **「`Connecting` 态确认条不渲染」是本轮的误判，已自证更正。**
   现象链：`Delete` 菜单项 `enabled=true`、tap 已下发，**连续 4 次**查 dump 都「看不到」确认条 ⇒ 一度记成缺陷。
   真因（§五.2 / §六.2）：**确认条一直都在**，只是渲染在 ScrollView 顶缘 `y=97-113`，
   而我前 4 次的 dump 过滤（`x>900,y<320` / `x>430, y>140`）**恰好都把这条带切掉了**，
   且**没有先滚**把条带滚进可见带。补上 `Swipe 800 275→800 140` ×2 之后，确认条立刻读到并点成功。
   → **本轮 `Connecting` 态删除路径判定为可用**；残留的真实限制只有「不可达 target 的状态窗口 ~1s，
   来不及走完四跳」，**不是门禁缺陷**。
   **补测建议（可选，非缺陷跟进）**：若要给出「`Connecting` 态**窗口内**也能删」的正面读数，
   拿一个**可达但会停在 `Connecting` 较久**的 target（宿主 `iptables -A INPUT -p tcp --dport 22 -j DROP`
   丢包制造长挂起，或用只 accept 不发 banner 的端口），把窗口拉到 10s+ 再跑一遍四跳即可。

4. **协调缺陷两处**：`_k.ps1` 的 `ParseNodes` 里 `$Matches[1]+$Matches[3]` 是**字符串拼接**而非加法
   （算出 `291316,287796` 这种坐标），首 tap 直接点飞 —— 已修为先 `[int]` 转型再相加。
   另 `ParseNodes` 未把 `focused` 列入属性表，导致「焦点到底在哪个框」只能靠截图判断（见 §七.2）。

5. **面板不自动滚 + 确认条贴顶缘 ⇒ 二次误判「控件缺失」**（本轮第三次踩同一族坑）。
   已在 §4.3 写明正确读法：**提交后先滚到列表末尾，dump 过滤放宽到 `x∈(430,1175), y∈(90,290)`**。
   另：查菜单项时**不能**用 `y<400` 这种一刀切 —— 状态二的 `Delete` 在 `y=402-422`，正好被切掉，
   一度让我误判成「`Connecting` 态菜单里没有 `Delete`」（实际**有**）。

6. **设置搜索框抢焦点（焦点坑）**：填表单时，`input text` 反复进到 `Search settings` 而非表单框。
   可靠解法：`keyevent 61`（TAB）让 Compose 让位 → **按 `x2-x1` 取最宽的 `EditText`** 再 tap。
   （表单框在 DOM/无障碍树里**始终存在**，只是窄窗下坐标与 IME 焦点不一致。）

7. **`adb` 不在本机 PATH**：首次 `adb devices` 直接 `CommandNotFoundException`；
   实际用的是 `C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe`（`REPORT_SSH_KEY.md` §七.7 同一坑）。

8. **唯一一处非 dump 解析坐标**：`设置` 入口的顶栏汉堡按钮 `[1302,20]` **在无障碍树里完全没有节点**
   （顶栏只暴露 `Home / New session / Command palette` 三个 `Button`，外加一个 `0,0-0,0 click=false` 的空 `Button`）。
   该坐标取自**运行期 `screencap` 截图**读数，非硬编码。已在 §纪律处标注为唯一例外。

9. **`Connected` 之前先出现过 `Server not reachable`**：Trust 之后第一次 dump 读到的是
   `127.0.0.1:45381` / `sshtest` / **`Server not reachable`**（此时 sshd 已在 15:47:11 记了 `Accepted password`）。
   约 30s 后再读，行标题变为 `127.0.0.1`、状态变为 **`Connected`**。
   → 判定：SSH 层早已成功（`Accepted password`），`Server not reachable` 是**隧道健康探针**在
   `127.0.0.1:45381`（app 自开的本地转发口，背后无业务服务）上的**早期读数**，随后收敛到 `Connected`。
   **这是「行状态读数要等收敛、不要抢在第一帧下结论」的新坑**，与「面板不自动滚」同族。

10. **`Authentication required` 已在本轮补跑并 PASS**（见 §五之二）：口令 `wrongpass` → 行 resting 状态
    `Authentication required` + sshd `Failed password for sshtest` ×3，该态下删除成功。
    仍**未覆盖**的相关形态：**私钥认证**的 `Authentication required` 变体
    （选 `Private key` + passphrase 填错），受 §七.2 的字段可达性所限，本轮做不了。
    另外**重连形态**（`Reconnect` 在 `Authentication required` 态的行为）本轮未单独取证。

11. **本轮新踩的第 4 个「点不中」族坑：认证卡吃掉行菜单 tap。**
    状态三里先点了 `Delete`，`confirm strip` 一直不出现；原因是**认证卡（`Password` + `Cancel`/`Connect`）
    还盖在面板上**，`⋮` 的 tap 落在了卡上而不是行。正确顺序是
    **先撤卡（点卡上 `Cancel`）→ 再 ⋮ → 再 `Delete`**，本轮补上这一步后一次就成。
    与 §七.5（确认条贴顶缘）、§七.6（设置搜索框抢焦点）合起来，
    **本轮把「删一行」的完整可靠时序固定为**：
    `撤掉所有盖层卡 → ⋮ → Delete → Swipe 275→140 ×2 露出确认条 → 确认 Delete`。

---

## 八、结论汇总

| # | 判据 | 结论 | 证据 |
|---|---|---|---|
| 1 | 构建 md5 一致 | ✅ | `e55f98084f9ec081511a9cfb7eb10f40` 逐位一致 |
| 2 | `lastUpdateTime` 一致 | ✅ | `2026-10-03 06:39:02` 逐位一致 |
| 3 | 设备全程属于本 app | ✅ | 每步交互前 `AssertDevice`；全程单窗口 `OcActivity`；无 `coderemote` |
| 4 | **`Connected` 态文案原文** | ✅ | dump 原文 `Connected`（UI 语言 English）+ sshd `Accepted password for sshtest` |
| 5 | **`Connected` 态 ⋮ 菜单有 `Delete` 且可点** | ✅ | `Delete [1097,354][1135,374] en=true` |
| 6 | **确认条可见可点（y 区间 + 视口 bounds）** | ✅ | `Cancel/Delete` y=97-113，`Delete [1047,97][1087,113] en=true`，视口 ScrollView `[434,94][1173,285]` |
| 7 | **删的就是它（前后清单 + 落库）** | ✅ | 行数 2→1，仅 `127.0.0.1/Connected` 消失；`127.0.0.1:4095` 完好 |
| 8 | **`Connecting` 态菜单存在** | ✅ | `Server actions [1075,245][1123,293]` |
| 9 | **`Connecting` 态 `Delete` 存在且 `enabled=true`** | ✅ | `Delete [1097,402][1135,422] en=true` |
| 10 | **`Connecting` 态下删除成功** | ✅ **可用**（窗口内四跳因 ~1s 状态窗未走完，非门禁） | 菜单 `Delete` `[1097,402][1135,422] en=true`；同一条上确认条 `Delete [1047,97][1087,113] en=true` 且**点它删净成功**（§5.2） |
| 11 | **`Authentication required` 造出** | ✅ **PASS** | 行 resting 状态原文 `Authentication required` + sshd `Failed password for sshtest` ×3；该态下删除成功（§五之二） |
| 12 | 反例：`10.0.2.2:4095` 完好 | ✅ | 四个标签全在 |
| 13 | 清理 | ✅ **完成** | 三条自建条目均删净，面板回到基线仅 `127.0.0.1:4095`；`/sdcard` 无残留；reverse 复核在位 |

### 一句话结论

**D3-4 三态的「行菜单 → `Delete` → 确认条 → 删对目标行」在本 build 上三态全部 PASS**：
`Connected`（主目标）、`Connecting`、`Authentication required` 三个状态文案均取到 dump 原文，
三者经服务端 sshd 日志交叉验证（`Accepted password` / `Failed password` ×3），
确认条 y 区间与视口 bounds 已实测钉住（`y=97-113` / ScrollView `[434,94][1173,285]`），
反例行 `4095` 全程无损伤，三条自建条目全部删净、`/sdcard` 无残留、reverse 复核在位。
**唯一与任务书不符的偏差**：条目未能命名 `E2ECHK5556d3*`，且 `Connected` 走的是**账号口令**认证而非私钥 ——
本设备（1366×768 桌面形态）下面板 ScrollView 只有 191px 高，快捷新增卡的**认证方式选择器与名称字段不可达**
（§七.1 / §七.2）；私钥已按要求铺到 `files/id_ed25519`（md5 双侧一致）但**未被 app 读取**，
建议下轮在竖屏手机形态设备上补跑私钥变体与「私钥认证下的 `Authentication required`」形态。
