# 真机复验报告 — 自定义图标落盘修复（emulator-5564 / B11·B12·B13·C14）

> 仓库根：`C:\sensorsdata\main\program\opencode-android-shell`
> 执行时间：2026-10-02 19:42 – 20:10（本机 GMT+8）
> 缺陷来源：`research/v97_verify/5564/REPORT.md` B11 FAIL
> 修复来源：`research/v97_fix/PROJECT_ICON_PERSIST.md`（`ProjectIconImage.MAX_STORED_BYTES = 48KB` + `fitBudget` 重新编码）
> 清单来源：同上 §5 真机复验步骤 V1–V11（本次以 B11/B12/B13/C14 四条判据执行）
> 证据文件相对路径均以本文件所在目录 `research/v97_verify/5564/` 为基准。
>
> **纪律遵守**：未跑 gradle、未装机、未杀模拟器/gradle 进程、只操作 emulator-5564、
> 未碰 127.0.0.1:5555；仅用 `am force-stop com.xiufeigo.opencodeshell`（app 级重启，复验必需）；
> 全程未按 BACK(4)、表单内未用 ESC(111)；截图全部 `screencap -p` + `adb pull`（无 exec-out）；
> 空格用 `keyevent 62`、未用 `%s`；收键盘用键盘自身 chevron；未点任何 Delete 文案；
> 确认弹层用其自身取消关闭；健康检查只用 `GET /api/info`。
> **坐标全部运行期从 `uiautomator dump` 解析**（helper `_u.ps1`，输出见各步骤读数）。

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

**核对通过：AVD = `Pixel_10_Pro_Fold`**（与交接口径逐字一致）。

```powershell
& $adb -s emulator-5564 shell wm size     # → Physical size: 2076x2152
& $adb -s emulator-5564 shell wm density  # → Physical density: 390
```

### 0.2 APK ✅（Lead 19:38 闸门构建，含图标落盘修复）

```powershell
& $adb -s emulator-5564 shell dumpsys package com.xiufeigo.opencodeshell |
  Select-String -Pattern "versionName|lastUpdateTime"
```

```
versionName=1.4.0
lastUpdateTime=2026-10-02 11:39:37
```

**与交接口径 `lastUpdateTime≈2026-10-02 11:39:37`、versionName 1.4.0 逐字一致**，
本轮**未发生二次安装**（firstInstallTime 仍为 2026-09-22 02:15:37），即受测确为**含修复的构建**。

### 0.3 后端健康 ✅（只用 `GET /api/info`）

```powershell
$h=@{Authorization='Basic '+[Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('opencode:opencode'))}
(Invoke-RestMethod -Uri 'http://127.0.0.1:4095/api/info' -Headers $h) | ConvertTo-Json -Depth 4
```

```json
{ "version": "2.0.18", "pid": 260, "urls": ["http://127.0.0.1:4095"], "paths": { "tmp": "/tmp/opencode" } }
```

---

## 1. 前置：未注册 fixture 的选定（curl 原文）

### 1.1 交接口径指定的目录**已被注册**（不能用作 fixture）

```powershell
$h=@{Authorization='Basic '+[Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('opencode:opencode'))};
(Invoke-RestMethod -Uri 'http://127.0.0.1:4095/api/project' -Headers $h) | ForEach-Object { $_.id + ' | ' + $_.canonical }
```

输出中相关行（原文节选，共 63 行）：

```
a40e83043fc7c253a5a390e9614a022c55215f18 | /tmp/e2echk_proj5564
61b2a59f950f436f2f58a7e2f9c9635da74d560f | /tmp/e2echk_c_5564
326d1b42269cefd4d0d78509229965a9696ea98a | /mnt/c/tmp/E2ECHK5560_proj
```

→ **`/tmp/e2echk_proj5564` 已在服务器上**（上一轮之前就被别的轮次注册过），会走已注册分支，
与 B11 的「未注册 ⇒ 本地 meta 通道」前提冲突，**不能直接用**。

### 1.2 现造目录 `/tmp/e2echk5564b` 确认未注册

```powershell
wsl -d Arch -e bash -lc "mkdir -p /tmp/e2echk5564b && cd /tmp/e2echk5564b && git init -q; ... pwd"
→ /tmp/e2echk5564b
```

```powershell
$all = (Invoke-RestMethod -Uri 'http://127.0.0.1:4095/api/project' -Headers $h) | ForEach-Object { $_.id + ' | ' + $_.canonical }
$all.Count                                        # → 63
$all | Where-Object { $_ -like '*e2echk5564b*' }   # → (空)
```

```
TOTAL=63
--- match for e2echk5564b ---
NO MATCH => /tmp/e2echk5564b IS UNREGISTERED
```

### 1.3 实际 fixture：**设备上既有的本地独有条目** `/home/xiufeigo/v38projA`

沿用上一轮报告 §1.2 的既定结论：**「新建项目 → 目录选择器」这条路会把目录同步注册进服务器
（62→63 行），因此无法用 UI 新建出「未注册」fixture**。因此改用设备 SharedPreferences 里
既有的**本地独有**行。交叉核对（prefs 逐行 × 服务器列表）：

```powershell
& $adb -s emulator-5564 shell run-as com.xiufeigo.opencodeshell cat shared_prefs/oc_home_projects.xml
```

| 本地行 worktree | name | 服务器有行？ |
|---|---|---|
| `/tmp/e2echk_c_5564` | – | ✅ 已注册 |
| `/tmp/.X11-unix` | E2ECHK_c16 | ✅ 已注册 |
| `/tmp/e2echk_proj5564` | E2ECHK5564D | ✅ 已注册 |
| **`/home/xiufeigo/v38projA`** | **E2ECHK5564N** | ❌ **无服务器行 → 本轮 fixture** |
| `/home/xiufeigo/v35projA` | – | ❌ 无 |

**选定 `/home/xiufeigo/v38projA`（Home 行名 `E2ECHK5564N`）**，证据 = 面板顶部 canonical 读数
（`01_V1_panel_before.png`）+ 上表服务器对照。该条目是分配给本机上一轮遗留的一次性夹具，
**本轮只改它自己**，未删除、未触碰其他任何行。

> **收尾复核（证明全程走本地 meta 通道而非服务器 PATCH）**：C14 保存并重启后再次查
> `GET /api/project`，`total rows=63`，
> `*v38projA* → NO MATCH => /home/xiufeigo/v38projA still UNREGISTERED`。
> 服务器行数**未因本轮任何一次保存而增加**。

### 1.4 测试图片

`e2echk_icon.png`（1890 B，源图 **256×256**）推送到 `/sdcard/Download/e2echk_icon.png`，
经系统照片选择器回传（选择器读数 `Selected Photo taken on Oct 2, 2026 11:43 AM`、`1 photos or videos selected`）。

---

## 2. B11 —— 选图 → 草稿态 → 保存 → Home 行头像 ✅ PASS

### 2.1 命令序列

```powershell
& $adb -s emulator-5564 shell input tap 1798 674      # Home 行「更多选项」(dump 解析)
& $adb -s emulator-5564 shell input tap 186 1092      # 菜单「编辑项目」(dump 解析)
& $adb -s emulator-5564 shell input tap 306 954       # 「选择图片」(dump 解析)
& $adb -s emulator-5564 shell input tap 452 1030      # 选中新推送图标 (dump: 11:43 AM)
& $adb -s emulator-5564 shell input tap 1657 1968     # 「Done」
& $adb -s emulator-5564 shell screencap -p /sdcard/s4.png; & $adb -s emulator-5564 pull ... 02_B11_panel_image_draft.png
& $adb -s emulator-5564 shell input tap 1899 1504     # 「保存」
```

### 2.2 草稿态断言（读数）

| 断言 | 期望 | 实测 dump 读数 | 判定 |
|---|---|---|---|
| 预览换成所选图 | 是 | 预览节点由字母徽标变为图像；`10_crop_panel.png` 可见 | ✅ |
| 色板整行消失 | 无「选择颜色」节点 | `has palette(选择颜色) = False`（count=0） | ✅ |
| 出现「清除图片」 | 有 | `has 清除图片 = True`，`@(508,1024) enabled=true clickable=true` | ✅ |
| 保存变亮 | enabled | `保存 -> enabled=true clickable=true bounds=[1699,1470][1819,1538]` | ✅ |

**草稿态全绿** —— 与上一轮 `34_B11_panel_image.png` 结论一致（选图/base64/渲染/状态机本身没问题）。

### 2.3 保存后 Home 行头像（关键判据）✅

落盘证据（`run-as cat shared_prefs/oc_home_projects.xml` 解码后）：

```
worktree = /home/xiufeigo/v38projA
dataUrlTotalChars   = 11046
prefix              = data:image/png;base6
base64PayloadChars  = 11024
decodedPackedBytes  = 8266      (预算 49152，within=True)
sha256 = B5C271AD0E99AE1CED2AAA3174240E17FB515E9E2648AFE6BB720B1FEBC7A065
decoded stored image: 246x256   (源图 256x256 → 被 fitBudget 重新编码)
```

> prefs 文件整体 **3109 B → 14370 B**，新增 `iconOverride` 成员。
> 8266 B **远在 48KB 预算内**（占 16.8%），且为**重新编码**后的 246×256，
> **不是**被截断的 data URL（截断会产生解码失败、头像回落字母徽标）。

Home 行读数（截图 `03_B11_saved_home.png` + 裁剪 `03_crop_row.png`）：

- 裁剪图目视：头像**已换成本次所选图片**（深色终端截图图），不再是橙色字母 `E` 徽标。
- 像素均值对照（头像区域 60,645 55×60）：
  - 保存后 `E2ECHK5564N` 行头像 = **`#141414`**（图像内容）
  - 参照行 `E2ECHK5564D`（仍是字母徽标）= **`#135A6A`**
  - 二者显著不同 ⇒ 头像确已换成图片，而非徽标。

> **B11 判定：✅ PASS**（上一轮同一断言为 FAIL）

---

## 3. B12 —— 强杀重启后头像仍在（真落盘）✅ PASS

### 3.1 命令

```powershell
& $adb -s emulator-5564 shell am force-stop com.xiufeigo.opencodeshell
& $adb -s emulator-5564 shell pidof com.xiufeigo.opencodeshell     # → (空输出，进程确已死亡)
& $adb -s emulator-5564 shell monkey -p com.xiufeigo.opencodeshell -c android.intent.category.LAUNCHER 1
```

`pidof` 空输出证明这是**真实的跨进程死亡**，内存态已全部丢弃；重启后的头像只能来自磁盘。

### 3.2 读数

| 时点 | `E2ECHK5564N` 头像区域平均色 |
|---|---|
| 保存后（重启前） | `#141414` |
| **force-stop + 重启后** | **`#141414`**（完全一致） |
| 参照：字母徽标行 | `#135A6A` |

- 行名读数 `h3 E2ECHK5564N @(941,674)`，行仍在位。
- 截图 `04_B12_after_restart_home.png` / 裁剪 `04_crop_row.png` 目视一致。

> **B12 判定：✅ PASS** —— 上一轮该项「⏸ 未验到（纪律禁止杀进程）」，本轮经授权用
> app 级 `force-stop` 完成闭环。

---

## 4. B13 —— 重开面板读回 / 清除图片 / 回落色板 ✅ PASS

### 4.1 重开面板读回（上一轮的 B11 指纹）✅

重新打开 `E2ECHK5564N` 的编辑面板（`05_B13_before_clear.png`，dump `_ui_p2.xml`）：

| 断言 | 实测读数 | 判定 |
|---|---|---|
| 预览**仍为所选图片** | 预览节点为图像（`10_crop_panel.png` 同一形态） | ✅ |
| 色板**仍隐藏** | dump 无 `选择颜色` 节点 | ✅ |
| 「清除图片」**仍在** | `@(508,1024) text='清除图片'` | ✅ |
| canonical 读数 | `/home/xiufeigo/v38projA` | ✅ |

> 上一轮此处**预�� FAIL**（`36_B13_before_clear.png` 预览回到字母徽标、色板重现、「清除图片」消失）。
> 本轮读回全对 ⇒ 落盘丢失的指纹已消失。

### 4.2 点「清除图片」→ 色板回来 ✅

```powershell
& $adb -s emulator-5564 shell input tap 508 1024     # 「清除图片」(dump 解析)
```

读数（`_ui_p3.xml`，截图 `06_B13_after_clear_tap.png`）：

```
p3  @(306,954)  text='选择图片'
p3  @(186,1156) desc='选择颜色：orange'
p3  @(256,1156) desc='选择颜色：pink'
p3  @(324,1158) desc='选择颜色：cyan'
p3  @(394,1158) desc='选择颜色：green'
p3  @(486,1158) desc='选择颜色：gray'
p3  @(1899,1574) text='保存'
```

→ **色板 5 个色块整行重新出现**，「清除图片」消失（互斥 `<Show when={!iconOverride}>` 成立）。

### 4.3 保存 → 头像回色板色，且跨重启保持 ✅

```powershell
& $adb -s emulator-5564 shell input tap 1899 1574     # 「保存」(dump 解析)
```

| 时点 | 头像平均色 | prefs `iconOverride` |
|---|---|---|
| 清除并保存后 | **`#8A4C2B`**（橙色徽标） | `present = False`（key 已移除） |
| **再次 force-stop + 重启后** | **`#8A4C2B`**（保持） | — |

截图 `07_B13_saved_home.png` / `08_B13_after_restart.png`。
prefs 读数：`name=E2ECHK5564N color=orange iconOverride present = False` ——
清除语义闭环，**清除跨重启不被复生**（对应 JVM pin #5/#6 的真机覆盖）。

> **B13 判定：✅ PASS**

---

## 5. C14 —— 一次保存里 改名 + 改色 + 换图 ✅ PASS

### 5.1 操作（一次保存内完成三项）

```powershell
# 1) 改名：清空并输入 C14（分段 + keyevent 67 逐字删，未用 %s）
& $adb -s emulator-5564 shell input tap 1038 852         # 「项目名称」EditText (dump 解析)
& $adb -s emulator-5564 shell input keyevent KEYCODE_MOVE_END
1..20 | ForEach-Object { & $adb -s emulator-5564 shell input keyevent 67 }
& $adb -s emulator-5564 shell input text "C14"
# 2) 改色：cyan
& $adb -s emulator-5564 shell input tap 324 1158         # '选择颜色：cyan' (dump 解析)
# 3) 换图
& $adb -s emulator-5564 shell input tap 306 954          # 「选择图片」
& $adb -s emulator-5564 shell input tap 1234 1812        # 目标图 (dump: 11:43 AM)
& $adb -s emulator-5564 shell input tap 1657 1968        # 「Done」
& $adb -s emulator-5564 shell input tap 1899 1504        # 「保存」— 三项一次落盘
```

草稿态读数（`10_C14_draft.png` / `_ui_c14c.xml`）：
`EditText text='C14'`、`has palette = False`、`has 清除图片 = True` —— 改名+换图同时在草稿里成立。

### 5.2 落盘读数（prefs 解码）

```
prefs size = 14360
name         = C14
iconColor    = cyan
startup      = echo ok
iconOverride = YES, packed = 8266 bytes
```

**三项全在**（改名 ✅ / 改色 ✅ / 换图 ✅），且 `startup` 未被牵连。

### 5.3 Home 行 + 跨重启

| 时点 | Home 行读数 | 头像平均色 |
|---|---|---|
| 保存后 | `C14 @(941,674)` | `#141414`（图像） |
| **force-stop + 重启后** | `C14 @(941,674)` | `#141414`（保持） |

截图 `11_C14_saved_home.png` / `12_C14_after_restart.png` / 裁剪 `12_crop_row.png`。

> **C14 判定：✅ PASS**（上一轮为「⏸ 未验到：前置 B11 失败，该组合无法成立」）

---

## 6. 逐条判定汇总

| # | 断言 | 本轮 | 上一轮 | 关键读数 |
|---|---|---|---|---|
| V1 | 面板加载：橙色 `E` 徽标、色板可见、无「清除图片」 | ✅ | ✅ | `_ui_p1.xml` |
| V2 | 草稿态：预览换图、色板消失、「清除图片」出现、保存变亮 | ✅ | ✅ | palette count=0 |
| V3/V4 | **B11** 保存后 Home 行头像 = 所选图 | ✅ **PASS** | ❌ FAIL | `#141414` vs 徽标 `#135A6A` |
| V2.5 | 落盘值存在且 ≤ 预算 | ✅ | ❌ | **8266 B / 49152 B** |
| V5 | **B12** 强杀重启后仍为所选图 | ✅ **PASS** | ⏸ 未验 | `pidof` 空；重启后 `#141414` |
| V6 | 重开面板：预览为图、色板隐藏、「清除图片」在 | ✅ | ❌ | `_ui_p2.xml` |
| V7/V8 | **B13** 清除图片 → 色板回来 → 头像回色板色，跨重启保持 | ✅ **PASS** | ⏸ 未验 | `#8A4C2B`；key 已移除 |
| C14 | 一次保存 改名+改色+换图 → 三项全在 | ✅ **PASS** | ⏸ 未验 | `C14` + `cyan` + 8266 B |
| V9 | 回归：只改名字/颜色/启动脚本仍正常 | ✅ **间接覆盖** | ✅ | C14 中 `startup=echo ok` 未被牵连 |
| V10 | 回归：已注册项目图标走服务器 PATCH | ⏸ **未执行** | ✅ | 见 §7 未闭环 ① |
| V11 | 回归：低对比/小图视觉可接受 | ⏸ **未执行** | — | 见 §7 未闭环 ② |

**通过判据核对**（修复文档 §5）：V3–V6 全绿 ✅、V7–V8 清除闭环 ✅。

---

## 7. 未闭环 / 遗留

1. **V10（已注册项目零回归）本轮未执行**。原因：本轮时间预算优先给 B11/B12/B13/C14
   四条主判据；已注册项目分支（`openRows` 走服务器 override）本轮**未触碰**，
   上游 v96 报告 D 组 17–22 已验证 PASS。建议后续单独一轮补 V10。
2. **V11（小尺寸/低对比图的视觉代价）未执行**。本轮 `e2echk_icon.png` 是**深色高对比**
   终端截图，48KB 预算下以 246×256 存下，头像尺度目视清晰（`03/04/10/12` 各图），
   但**未覆盖**「极端低对比图在 MIN_EDGE 48 降采样后是否还糊」这一分支，
   即修复文档 §6.4 的 `fitBudget` 返回 `null` 路径**真机仍未覆盖**。
3. **结构性脆弱点仍在**（修复文档 §6.2 已登记，本轮再次得到印证）：
   prefs 整体 3109 B → 14360 B，全部 state 仍编成**单个** SharedPreferences 值、
   `apply()` 静默落盘。本次靠**单个成员压到 8.3KB** 落进预算内而闭环；
   若将来 `sandboxes` 列表或项目数增长，同类故障会复发。
4. **一次操作返工（已闭环，非缺陷）**：C14 首次点「保存」时 IME 仍在屏，
   该点击落到软键盘 `p` 键上（名字变成 `C14p`、面板未关闭、保存未发生）。
   处置：把光标移到末尾后按一次 `keyevent 67` 删掉误输入的 `p`，
   用**键盘自身 chevron**（`input tap 180 2093`）收键盘后重新 dump 解析坐标，
   再点「保存」—— 未使用 BACK(4) 或 ESC(111)。**记此仅为避免后续复验者重踩。**
5. **本轮遗留的 fixture 状态**：`/home/xiufeigo/v38projA` 现为
   `name=C14` / `iconColor=cyan` / `iconOverride=8266B 图` / `startup=echo ok`。
   该条目是本机一次性夹具，**未删除、未注册**（服务器仍 63 行、无 v38projA）。
6. **本报告未闭环的只有 V10 / V11 两条回归**；四条主判据 B11/B12/B13/C14 **全部 PASS**。

---

## 8. 证据文件清单

| 文件 | 内容 |
|---|---|
| `_u.ps1` | dump 解析 helper（UTF-8，坐标运行期解析） |
| `01_V1_panel_before.png` | V1 面板基线（canonical `/home/xiufeigo/v38projA`、橙色 `E` 徽标、色板可见） |
| `02_B11_panel_image_draft.png` | B11 草稿态（预览换图、色板消失、「清除图片」出现） |
| `03_B11_saved_home.png` / `03_crop_row.png` | B11 保存后 Home 行（头像=所选图） |
| `04_B12_after_restart_home.png` / `04_crop_row.png` | B12 强杀重启后（头像不变） |
| `05_B13_before_clear.png` | B13 重开面板（预览为图、色板隐藏、「清除图片」在） |
| `06_B13_after_clear_tap.png` | B13 点「清除图片」后（色板 5 色块回来） |
| `07_B13_saved_home.png` | B13 保存后（头像回橙色徽标） |
| `08_B13_after_restart.png` | B13 清除跨重启保持 |
| `09_C14_rename.png` | C14 改名中间态 |
| `10_C14_draft.png` / `10_crop_panel.png` | C14 草稿态（名字 C14 + 换图，色板隐藏） |
| `11_C14_saved_home.png` | C14 保存后 Home 行 |
| `12_C14_after_restart.png` / `12_crop_row.png` | C14 跨重启（三项全在） |
| `_prefs_pre.xml` | 初始 prefs（3109 B） |
| `_prefs_after_save.xml` | B11 保存后 prefs（14370 B，含 iconOverride 8266 B） |
| `_prefs_after_clear.xml` | B13 清除后 prefs（iconOverride 已移除） |
| `_prefs_c14c.xml` | C14 保存后 prefs（`C14`/`cyan`/图/`echo ok`） |
| `_ui_*.xml` | 各步骤 uiautomator dump 原文 |
