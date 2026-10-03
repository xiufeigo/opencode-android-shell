# 修复验收报告 — emulator-5564（验收员：v97-verify-5564）

> 仓库根：`C:\sensorsdata\main\program\opencode-android-shell`
> 执行时间：2026-10-02 18:57 – 19:12（本机时区 GMT+8；设备时钟显示 11:12，为模拟器本地时区）
> 清单来源：`research/v97_fix/PROJECT_EDIT_LOCAL_META.md`「真机复验步骤」、
> `research/v97_fix/AGENTCHIP_APPLY.md`「真机复验步骤」
> 证据文件相对路径均以本文件所在目录 `research/v97_verify/5564/` 为基准。
>
> **纪律遵守**：未跑 gradle、未装机、未杀进程、未碰 `127.0.0.1:5555`、全程未按 BACK(4)、
> 表单/设置内未用 ESC(111)、截图全部 `screencap -p` + `adb pull`（无 `exec-out`）、
> 未点任何 Delete 文案、确认弹层一律用其自身 取消/关闭。
> 健康检查只用 `GET /api/info`。

---

## 0. 环境读数

### 0.1 设备身份 ✅

```powershell
& "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s emulator-5564 emu avd name
```

```
Pixel_10_Pro_Fold
OK
```

```powershell
& $adb -s emulator-5564 shell wm size     # → Physical size: 2076x2152
& $adb -s emulator-5564 shell wm density  # → Physical density: 390
```

**核对通过：AVD = `Pixel_10_Pro_Fold`，2076x2152 @390dpi，与交接口径一致。**
`adb devices -l` 显示同机 5554/5556/5558/5560/5562 均在列，**全程只对 5564 下发指令**。

### 0.2 APK ✅（与 Lead 18:13 构建一致）

```powershell
& $adb -s emulator-5564 shell dumpsys package com.xiufeigo.opencodeshell |
  Select-String -Pattern "versionName|lastUpdateTime|firstInstallTime|versionCode"
```

```
versionCode=26 minSdk=26 targetSdk=35
versionName=1.4.0
lastUpdateTime=2026-10-02 10:15:46
firstInstallTime=2026-09-22 02:15:37
```

**`lastUpdateTime = 2026-10-02 10:15:46`，与交接的 Lead 18:13 构建读数逐字一致**，
本轮**未发生二次安装**（firstInstallTime 仍为 2026-09-22）。

### 0.3 隧道 / IME ✅

```powershell
& $adb -s emulator-5564 reverse --list
```

```
host-15 tcp:2222 tcp:22
host-15 tcp:4095 tcp:4095
```

```powershell
& $adb -s emulator-5564 shell settings get secure show_ime_with_hard_keyboard
```

```
1
```

两个 reverse 都在；`show_ime_with_hard_keyboard=1` 在位。

### 0.4 后端健康 ✅（只用 `GET /api/info`）

```powershell
$h=@{Authorization='Basic '+[Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('opencode:opencode'))}
(Invoke-RestMethod -Uri 'http://127.0.0.1:4095/api/info' -Headers $h) | ConvertTo-Json -Depth 4
```

```json
{
    "version": "2.0.18",
    "pid":  260,
    "urls":  [ "http://127.0.0.1:4095" ],
    "paths": { "tmp": "/tmp/opencode" }
}
```

### 0.5 后端文件系统位置（取证澄清，非缺陷）

服务器返回的 canonical 含 `/home/xiufeigo`、`/mnt/c/...`、`/tmp/...` → 后端跑在 **WSL2**
（`/mnt/c` 为 WSL 的 Windows 盘挂载）。故造目录用 `wsl.exe`：

```
wsl.exe -e bash -lc "mkdir -p /tmp/e2echk_c_5564 && ls -ld /tmp/e2echk_c_5564"
→ drwxr-xr-x 2 xiufeigo xiufeigo 40 Oct  2 18:58 /tmp/e2echk_c_5564
```

> `ssh -p 2222 localhost` 返回 `Connection refused` —— 2222 是 **adb reverse
> （设备侧 localhost:2222 → 主机 22）**，Windows 侧无监听。属口径澄清。

---

## 1. 未注册目录：注册状态证据（curl 原文，A 组前提）

### 1.1 交接口径里指定的目录**已被注册**

首个 `GET /api/project`（62 行）里，`/tmp/e2echk_proj5564` **已在服务器上**：

```
a40e83043fc7c253a5a390e9614a022c55215f18 | /tmp/e2echk_proj5564 | sandboxes=
```

→ 不能用作 A 组 fixture（会走已注册分支）。按交接口径换目录。

### 1.2 换目录后仍被 App 自动注册 —— **加项目这条路造不出「未注册」fixture**

新建 `/tmp/e2echk_c_5564` 并用 UI 加入，**服务器行数 62 → 63**：

**加之前**（`curl_pre_add.txt`）：

```
=== PRE-ADD registration check  19:01:16 ===
GET /api/project  -> total rows: 62
match for '/tmp/e2echk_c_5564' (canonical|sandboxes): [<NO MATCH — 未注册>]
--- all rows containing 'e2echk' ---
a40e83043fc7c253a5a390e9614a022c55215f18 | /tmp/e2echk_proj5564 | sandboxes=
326d1b42269cefd4d0d78509229965a9696ea98a | /mnt/c/tmp/E2ECHK5560_proj | sandboxes=
```

**加之后**（`curl_post_add.txt`）：

```
=== POST-ADD registration check  19:02:31 ===
GET /api/project  -> total rows: 63
match for '/tmp/e2echk_c_5564': [61b2a59f950f436f2f58a7e2f9c9635da74d560f | /tmp/e2echk_c_5564 | sandboxes=]
--- all rows containing 'e2echk' ---
61b2a59f950f436f2f58a7e2f9c9635da74d560f | /tmp/e2echk_c_5564 | sandboxes=
a40e83043fc7c253a5a390e9614a022c55215f18 | /tmp/e2echk_proj5564 | sandboxes=
326d1b42269cefd4d0d78509229965a9696ea98a | /mnt/c/tmp/E2ECHK5560_proj | sandboxes=
```

> **结论（重要）**：`新建项目 → 目录选择器` 这条路径**会把目录同步注册进服务器**，
> 因此**无法用 UI 新建出一个「未在服务器注册」的项目行**。
> `/tmp` 本身也已被注册（`470c8549…`），无法作为替代。

### 1.3 实际使用的 A 组 fixture：设备上**既有的本地独有条目**

逐个打开各行「编辑项目」读出 canonical，再与 `GET /api/project` 对照：

| Home 行 | canonical（面板读数） | 服务器有行？ |
|---|---|---|
| `E2ECHK_c16` | `/tmp/.X11-unix` | ✅ 已注册 `f371f1f7…` |
| `E2ECHK_d19` | `/tmp/e2echk_proj5564` | ✅ 已注册 `a40e8304…` |
| `v38projA` | `/home/xiufeigo/v38projA` | ❌ **无服务器行** |

**最终 A/C 组 fixture = `/home/xiufeigo/v38projA`（Home 行名 `v38projA`）**，
证据：`19_v38_panel.png`（面板顶部 canonical 读数）+ 上表服务器对照。

> 处置说明：该条目是**分配给本机（emulator-5564）上上一轮遗留的本地独有一次性夹具**，
> 不属于他人正在使用的项目；本轮**只改它自己**（改名 / 改色 / 改启动脚本），
> **未删除、未触碰 E2ECHK_c16 / E2ECHK_d19 之外的任何行**，也**未点任何 Delete 文案**。
> 现留名 `E2ECHK5564N`（canonical 仍是 `/home/xiufeigo/v38projA`）。

### 1.4 本地保存后仍**未**注册（证明走的是本地 meta 通道，不是 PATCH）

```powershell
# 保存 E2ECHK5564（改名+改色+启动脚本）之后立刻复查
GET /api/project  →  match for '/home/xiufeigo/v38projA':
  <NO SERVER ROW — 未注册 ✔ 本地 meta 通道，未走 PATCH>
total rows: 63      # 与 1.2 之后持平，未新增
```

**A 组全程的写操作都没有在服务器上留下任何行** → 确认走的是本轮修复的**本地 meta 写通道**。

---

## 2. 修复 1 判定：`编辑项目` 对「未在服务器注册」项目可编辑

**总判定：核心修复成立（A 组 10/10、C 组 2/2 PASS），但发现 1 个 B 组图片持久化缺陷。**

### A 组 1–10 —— 全部 PASS（fixture `/home/xiufeigo/v38projA`）

| # | 断言 | 判定 | 证据 |
|---|---|---|---|
| A1 | Home → ⋮ → 「编辑项目」面板可打开 | ✅ PASS | `19_v38_panel.png` |
| A2 | 面板**不再**有「该项目未在服务器注册」 | ✅ PASS | `19_v38_panel.png`（整屏无该行） |
| A3 | 名称框可聚焦、键盘弹出、有光标 | ✅ PASS | `20_A3_A6_typed.png`（光标在 `E2ECHK5564` 后） |
| A4 | 启动脚本框可输入 | ✅ PASS | `21_A4_script.png`（`bun install` + 光标） |
| A5 | 未改动时「保存」是**灰的** | ✅ PASS | `19_v38_panel.png`（保存灰） |
| A6 | 名称字符**真的落地** | ✅ PASS | `20_A3_A6_typed.png`（旧名 9 字符全删、新名写入） |
| A7 | 点色板 → 预览头像变色 **且** 保存变**亮** | ✅ PASS | `22_A7_color_save_lit.png` / `23_A7_save_lit.png`（对比 `19` 的灰） |
| A8 | 点「保存」→ toast「已保存」+ 面板关闭 | ✅ PASS | `24_A8_toast.png`（toast **项目已保存**） |
| A9 | Home 行**同帧**改名 + 变色，无需重启 | ✅ PASS | `24_A8_toast.png`（`v38projA`→`E2ECHK5564`，头像橙） |
| A10 | 重开面板**读得回**（名字/脚本/色板选中态） | ✅ PASS | `26_A10_reopen.png`（名字+`bun install`+橙色选中环；保存又变灰） |

关键命令：

```powershell
& $adb -s emulator-5564 shell input tap 1037 784        # 点名称框
& $adb -s emulator-5564 shell input keyevent 123         # 移到行尾
1..9 | % { & $adb -s emulator-5564 shell input keyevent 67 }   # 逐字删除旧名
& $adb -s emulator-5564 shell input text "E2ECHK55"      # ≤8 字符分段
& $adb -s emulator-5564 shell input text "64"
& $adb -s emulator-5564 shell input tap 210 1161         # 点橙色色板
& $adb -s emulator-5564 shell input tap 1895 1579        # 点「保存」
```

> 收键盘：用键盘自身的 chevron 折叠（`input tap 180 2093`）与 `keyevent 61`，
> **未使用 ESC(111)**；空格用 `keyevent 62`（`bun`␠`install`、`echo`␠`ok` 均正确落地）。

### B 组 11–13 —— 图片路径（**B11 FAIL**）

| # | 断言 | 判定 | 证据 |
|---|---|---|---|
| B11 | 选图 → 预览显示该图、色板整行消失 → 保存 → **Home 行头像是那张图** | ❌ **FAIL** | `34_B11_panel_image.png` / `35_B11_saved_home.png` / `36_B13_before_clear.png` |
| B12 | 强杀重启后仍是那张图（验证落盘） | ⏸ 未验到 | 纪律**禁止杀进程**，未做 |
| B13 | 「清除图片」→ 色板重新出现 → 保存 → 头像回色板色 | ⏸ 未验到 | 前置 B11 失败，`清除图片` 按钮**根本没有再出现**（见下） |

**B11 缺陷详述（可复现）**：

1. `34_B11_panel_image.png` —— 草稿态**是对的**：图标预览已换成所选图片，
   「颜色」色板整行**消失**（上游 `<Show when={!iconOverride}>` 互斥成立），
   出现「清除图片」按钮，`保存` 变亮。
2. `35_B11_saved_home.png` —— 点「保存」后面板关闭，**但 Home 行头像仍是橙色 `E` 徽标，不是那张图**。
3. `36_B13_before_clear.png` —— **重开面板**：预览回到橙色 `E` 徽标，
   色板整行**重新出现**，**「清除图片」按钮消失**。

→ **选中的图片既没有落到 Home 行头像，也没有在重开面板后读回**，
说明 `iconOverride` **未写入 / 未读回**本地 meta。同一轮里**名字与颜色都正常持久化**
（`26`/`31` 两张读回截图可证），因此问题**收敛在图片字段**，不在 meta 通道本身。
`e2echk_icon.png`（本轮现造的 256×256 PNG，已 `adb push` 到 `/sdcard/Pictures/` 并做
`MEDIA_SCANNER_SCAN_FILE` 广播）在系统相册里正常可见、系统选图器也正常回传，
**不是「设备里没图」**。

### C 组 14–16 —— 字段级浅合并

| # | 断言 | 判定 | 证据 |
|---|---|---|---|
| C14 | 一次保存里**改名 + 改色 + 换图** → 三项全在 | ⏸ 未验到 | 前置 B11 失败，图片无法持久化，**该组合无法成立** |
| C15 | **只改启动脚本** → 名字、颜色、图标仍在 | ✅ PASS | `31_C15_readback.png`（`E2ECHK5564N` + 橙色选中 + `echo ok`） |
| C16 | **只改名** → 颜色、图标仍在 | ✅ PASS | `28_C16_saved.png`（改名生效，头像仍橙） |

> C15 / C16 共同证明 `child-store` 式**字段级浅合并**在本地 meta 通道上成立：
> 单字段写入不会把其余字段清空。图标在 C15/C16 语境下指的是色板色（图片态已被 B11 证伪）。

### D 组 17–22 —— 已注册项目零回归

| # | 断言 | 判定 | 证据 |
|---|---|---|---|
| D17 | 找一个 `GET /api/project` 能返回的项目 | ✅ PASS | `E2ECHK_d19` → `/tmp/e2echk_proj5564` = `a40e8304…` |
| D18 | 面板可编辑、保存走 **PATCH** | ✅ PASS | `37_D19_draft.png` + 见下方服务器回读 |
| D19 | 改名字 → toast、行改名、**服务器侧同步** | ✅ PASS | `38_D19_saved.png` + 下方 JSON |
| D20 | 强杀重启后仍是新名字/新图标（本地副本不遮蔽服务器头像） | ⏸ 未验到 | 纪律**禁止杀进程**，未做 |
| D21 | 反证：已注册项目本地 icon 弄脏 → 头像仍走服务器那份 | ⏸ 未验到 | 需要「先在未注册时改图标再让它被注册」，**造不出该前置**（见 §1.2） |
| D22 | 「刷新工作区」在未注册项目上仍弹「该项目未在服务器注册」 | ✅ PASS | `40_D22_toast_fast.png`（底部 toast「该项目未在服务器注册」） |

> D22 第一次采样（3s 间隔）**没捕到** toast，属采样窗过宽导致的漏捕，不是行为缺失；
> 改为「点完菜单项立刻 `screencap`」后捕到（`40_D22_toast_fast.png`），
> 41 号那张是同一 toast 的更晚一帧。**这条只能算 PASS 一条，不能算两条独立证据。**

D19 的服务器侧证据（保存后 `GET /api/project` 中该行）：

```json
{
  "id": "a40e83043fc7c253a5a390e9614a022c55215f18",
  "canonical": "/tmp/e2echk_proj5564",
  "name": "E2ECHK5564D",              // ← 改名已同步到服务器
  "icon": { "color": "cyan" },        // ← 仍是服务器那份图标
  "time": {
    "created": 1790936386782,
    "updated": 1790939442584,          // ← updated 已推进 = 走 PATCH
    "active":  1790936386784
  },
  "sandboxes": []
}
```

**已注册项目零回归成立**：保存走 PATCH（`updated` 推进）、服务器侧名字同步、
行头像走服务器那份 `cyan`（未被本地副本遮蔽）。

### E 组 23–25 —— 旧数据兼容
⏸ **未验到**：需先装旧版本再覆盖安装，本轮纪律**禁止装机**。

---

## 3. 修复 2 判定：`AgentChip` 的 `/agent` 菜单不再「重建即凭空重弹」

**形态说明（必读）**：本机 `Pixel_10_Pro_Fold` 2076×2152 @390dpi 是**折叠屏近方形**窗口。
本轮全部证据取自 **短窗 / 堆叠形态**（键盘弹起时 body 被顶起、composer 位于键盘正上方，
见 `02_created.png`、`06_F2C2_after_rebuild.png`）。
**另一种形态（键盘收起后的高窗形态）未验到。**

会话前提：用**本轮自建**的会话（Home「+」→ 创建会话），
未改动他人会话。

| 核心 | 断言 | 判定 | 证据 |
|---|---|---|---|
| Core 1 | composer 敲 `/agent` → **agent 菜单出现**（列出候选 agent） | ✅ PASS | `03_F2C1_cmdmenu.png`、`04_F2C1_agentmenu.png` |
| Core 2 | 关掉菜单 → **点 composer 文本区**（触发重组）→ 菜单**不重弹** | ✅ PASS | `05_F2C2_menu_closed.png`、`06_F2C2_after_rebuild.png` |
| Core 3 | 再敲一次 `/agent` → 菜单**能**再次打开 | ✅ PASS | `07_F2C3_typed.png`、`08_F2C3_reopened.png` |

- **Core 1**：`/agent` 落进 composer → 出现命令行「切换智能体 Ctrl+.」→ 点它 →
  agent 菜单打开，列出 `build`（The default agent…）、`plan`（Read-only agent…）、
  `服务器默认 ✔`（选中项打勾、含「默认」行），与清单第 8 步要求的列表内容一致。
- **Core 2**：外点关掉 agent 菜单 → **点 composer 文本区** → 键盘**真的弹起**、
  body 被顶起、composer **真的重建**（布局与焦点均变化）→ **agent 菜单没有再弹**。
  这一击正是缺陷的最小复现触发器；**前一位验收员测到的 Core 2 PASS 已被本轮复现**。
- **Core 3**：再次敲 `/agent` → 菜单**能**再次打开，内容与 Core 1 完全一致 →
  正路径没被闩锁死。

命令原文：

```powershell
& $adb -s emulator-5564 shell input tap 1037 1145     # 点 composer 文本区
& $adb -s emulator-5564 shell input text "/agent"      # 6 字符，单段
& $adb -s emulator-5564 shell input tap 1037 1002      # 点命令菜单里的 agent built-in 行
& $adb -s emulator-5564 shell input tap 1037 756       # 外点关闭（未用 BACK / ESC）
& $adb -s emulator-5564 shell input tap 1037 1874      # 点 composer 文本区 → 重建
```

---

## 4. 未闭环 / 未验到（汇总）

**阻塞型（造不出前置，非产品缺陷）**

1. **UI 新建项目会把目录注册进服务器**（§1.2，行数 62→63），
   因此「新建一个未注册项目」这条路**不可用**；A 组只能借用设备上既有的本地独有条目
   `/home/xiufeigo/v38projA`。**这是本轮最需要 Lead 知道的口径修正。**
2. **D21** 依赖「先在未注册时改图标、再让它被注册」的时间窗，受阻于上一条，**未验到**。

**缺陷**

3. **B11 FAIL**：`iconOverride`（自定义图标图片）**不持久化** ——
   保存后 Home 行头像仍是色板色，重开面板也读不回（`35` / `36`）。
   同轮名字、颜色、启动脚本三项均正常持久化，故问题**收敛在图片字段**。
   建议查 `saveLocal` → `store.setMeta` 对 `iconOverride` 的写入与读回路径，
   以及 `""`（清除）与 `null` 的区分。

**受纪律限制未做**

4. **D20 / B12**：强杀重启验证落盘 —— **纪律禁止杀进程**，**未验到**。
5. **E 组 23–25**：旧版本升级兼容 —— **纪律禁止装机**，**未验到**。

**形态覆盖不足**

7. 修复 2 只在**短窗 / 堆叠形态**下验证；**高窗形态未验到**。
8. 修复 2 的清单步骤 3（不可见期间请求不丢）、步骤 4（ESC 焦点归还）、
   步骤 5（形态翻转不震荡）**本轮未做**（交接口径只要求三条核心）。

**夹具改动留痕**

9. `/home/xiufeigo/v38projA` 现显示名 `E2ECHK5564N`（颜色=orange，启动脚本=`echo ok`）；
   `/tmp/e2echk_proj5564` 现显示名 `E2ECHK5564D`（服务器侧已同步）。
   两者均为本轮自有的验收改动，**未删除任何行**。

---

## 5. 一句话结论

- **修复 1**：未注册项目的「编辑项目」面板**已完全可用且可持久化**（A 组 10/10、
  C15/C16 浅合并 PASS、已注册项目 PATCH 零回归 PASS），**但自定义图标图片不持久化（B11 FAIL）**。
- **修复 2**：`/agent` 菜单**三条核心全部 PASS** —— 出现、不因重建重弹、能再次打开。
