# REPORT_PALETTE — 命令面板「死区」修复真机复验（v98）

- **执行人**：Mavis（真机复验测试员）
- **日期**：2026-10-02 23:21 起（宿主本地时间；设备时间 15:22 GMT+0000）
- **仓库**：`C:\sensorsdata\main\program\opencode-android-shell`
- **设备**：`emulator-5562`（Small_Desktop，1366x768@160dpi）
- **缺陷来源**：`research/v97_verify/5564_v98/REPORT_PERF.md` §0 / §6.3（死区段）
- **修法来源**：`research/v97_fix/PALETTE_DEADZONE_FIX.md`
- **最终状态**：❌ **主判据未通过 —— 底栏死区在本构建上仍然存在**（部分修复：遮罩区已修好，底栏未修好）

---

## 0. 一句话结论

> **修复只生效了一半。** 面板打开后点**遮罩**（卡片外）会关闭面板 ✅；
> 但点**底栏**依旧是「点了什么都没有」❌ —— 点前/点后截图 **MD5 逐字节相同**。
> 且已用对照实验证明底栏坐标本身是**活的**（面板关闭时同一点能正常跳转）。
> **命令面板死区未修复。**

---

## 1. 环境读数

### 1.1 设备身份核实 ✅

```powershell
$ adb -s emulator-5562 emu avd name
Small_Desktop
OK
$ adb -s emulator-5562 shell wm size      → Physical size: 1366x768
$ adb -s emulator-5562 shell wm density  → Physical density: 160
```

✅ 与要求的 **Small_Desktop / 1366x768@160dpi** 完全一致。**只有 Physical 行、无 Override 行**（起始态干净）。
`mWakefulness=Awake`、`mShowingDream=false`（已唤醒未锁屏）、Android 14 / API 34。

### 1.2 `reverse --list` ✅

```
host-19 tcp:4095 tcp:4095
host-19 tcp:2222 tcp:22
```

✅ 非空，4095/2222 均已挂载，无需重挂。后端 `http://127.0.0.1:4095` 通道就绪。

### 1.3 安装包闸门复核 ✅

```powershell
$ adb -s emulator-5562 shell dumpsys package com.xiufeigo.opencodeshell | grep -E "lastUpdateTime|versionName"
      versionName=1.4.0
      lastUpdateTime=2026-10-02 15:20:52
$ adb -s emulator-5562 shell date
Fri Oct  2 15:22:10 GMT 2026
```

✅ `lastUpdateTime=2026-10-02 15:20:52` 与「Lead 23:20 闸门构建」**逐秒一致**。测的就是含本次改动的构建。

### 1.4 ⚠️ 本轮重大环境偏差（影响取证方式，必须先讲）

**(a) `uiautomator dump` 全程不可用 —— 本轮无法用 dump 取节点数 / bounds。**

```
$ adb -s emulator-5562 shell uiautomator dump
ERROR: null root node returned by UiTestAutomationBridge.     ← 反复 10+ 次，含 --compressed
$ adb -s emulator-5562 shell "dumpsys window | grep mCurrentFocus"
  mCurrentFocus=Window{33104bb u0 .../OcActivity}   ← 焦点最终拿到了，但 dump 仍失败
```

`dumpsys accessibility` 能看到 app 窗口在树里，但 `UiTestAutomationBridge` 拿不到 active root。
处置：**不杀进程、不重装**，改用 **`screencap -p` + `adb pull` 的截图 MD5 作为可观测签名**，
并从**本轮截图本身**读出卡片/底栏 bounds（运行期解析，非写死）。
> ⚠️ 因此任务要求的「dump 节点数」与「`text="…"`/`text='…'` 双模式匹配」**本轮未能使用**，以截图签名替代。
> 这不影响主判据的成立——因为"无变化"的最强证据恰恰是**逐字节相同**。

**(b) 冷启动极慢。** app 进程起来后 **>5.5 分钟**没出首帧，`OcActivity` 一直 `mViewVisibility=0x4 / mHaveFrame=false`，
logcat 显示在逐个做 Compose 类校验（`TextStyle.<init> took 268ms`、`TypographyTokensKt.<clinit> took 103ms`）。
**不是卡死**（8 台模拟器并发的宿主过载），等待后正常出帧。

**(c) 该 AVD 是「窗口化桌面」形态。** 顶部有窗口装饰条（最小化/最大化/关闭），且**屏幕并非全部属于 app**——
点 (150, 200) 会落到系统启动器的 all-apps 抽屉。取证只使用落在 app 窗口内的坐标。

**(d) 任务书给的 `input keycombination 113 39` 在本 AVD 打不开面板**（截图证明当时无面板）。
本轮改用底栏自带的面板按钮 `(683, 745)` 打开——该入口在面板关闭时确认可用。

---

## 2. 判定一：默认形态主判据 —— ❌ **未通过（死区仍在）**

### 2.1 面板打开态的实测几何（从 `v_02_palette_open.png` 读出）

| 元素 | 范围 |
|---|---|
| 面板**卡片** | x = 364 … 1002，y = 72 … 340 |
| **遮罩**（卡片外） | 卡片四周 |
| IME 键盘 | y = 340 … 716 |
| **底栏（BottomTabStrip）** | y ≈ 716 … 768；按钮 (24, 745) 与 (683, 745) |

→ `(24, 745)` 位于**卡片外、遮罩内**，是底栏的真实按钮中心。

### 2.2 关键实验（**同一个面板会话内的 A/B**）

```powershell
# 面板已打开（卡片 + 遮罩 + 键盘均可见）
$ adb -s emulator-5562 shell input tap 24 745      # 点底栏"查找"按钮
$ adb -s emulator-5562 shell screencap -p /sdcard/...   →  v_03
# 面板仍是打开态，再点遮罩
$ adb -s emulator-5562 shell input tap 1150 250    # 点卡片右侧遮罩
$ adb -s emulator-5562 shell screencap -p /sdcard/...   →  v_05
```

**点前/点后签名 diff（截图 MD5 = UI 层级签名的可观测替代）**：

| 时点 | 文件 | 字节 | MD5 | 画面 |
|---|---|---:|---|---|
| 点前（面板开） | `v_02_palette_open.png` | 72577 | `D08F2B21CD9A97AC1D1F281F495574DE` | 卡片+遮罩+键盘 |
| **点底栏后** | `v_03_after_bottombar_tap.png` | **72577** | **`D08F2B21CD9A97AC1D1F281F495574DE`** | **完全没变** |
| 点遮罩后 | `v_05_after_scrim_right.png` | 78244 | `1844654FD9515F025CE844FE8D3961B8` | **面板已关闭** |

```
点底栏 后 == 点前 ?  True      ← 逐字节相同 = 屏幕上什么都没发生
点遮罩 后 == 点前 ?  False     ← 同一面板会话，遮罩一点就关了
```

### 2.3 阴性/阳性对照：底栏坐标本身是"活的" ✅

为了排除"我这个点根本点不到东西"，做了对照实验——**面板关闭**时点**同一个坐标**：

```powershell
$ adb -s emulator-5562 shell input tap 24 745      # 面板已关闭
→ v_07_bottombar_nopalette.png  57918  5A96B5CDF1650DE2EEC9A218D0B41F2E
```

画面从会话页**切换到了 Find 搜索页**（顶部出现 `Find...` 搜索框）。
→ `(24, 745)` 是**确凿可响应**的底栏控件，不是死坐标。

### 2.4 判定

| 条件 | 结果 |
|---|---|
| 面板打开时点底栏 | ❌ 面板未关闭、底栏未响应，**截图逐字节不变** |
| 任务要求"面板关闭 **或** 事件到达底栏，二者取其一" | ❌ **二者皆无** |
| 同会话点遮罩 | ✅ 面板关闭（说明遮罩链路本身是通的） |
| 对照：面板关闭时点同一点 | ✅ 正常跳转（说明坐标有效） |

> **主判据 ❌ 未通过。** 底栏区域仍是死区——「点了什么都没有」。
> 这与 `REPORT_PERF.md` 记录的原始症状**一致**，即 v98 修复**未覆盖到底栏这条路径**。

---

## 3. 判定二：面板外其它区域（遮罩）—— ✅ 通过

```powershell
$ adb -s emulator-5562 shell input tap 1150 250     # 卡片右侧遮罩区
```
`(1150, 250)` 在卡片 (x≤1002) 之外。面板**立即关闭**（MD5 由 `D08F2B21…` → `1844654F…`，卡片与遮罩消失，
底层会话页恢复）。

> ✅ **遮罩区修复生效**：卡片以外点任意位置会关闭面板。
> ❌ 但**同一遮罩的底栏那段依然不生效**——说明"关闭判定"这条链路已修好，
> **底栏那一片根本没走到关闭判定上**（事件在到达 `scrimTapDismisses` 之前就丢了）。

---

## 4. 判定三：面板内正路径 —— ❌ 未取证（非判失败）

计划：面板打开 → 点卡片内 `New workspace` (683, 211) → 断言命令执行。

**实际未能完成**。原因：面板**只在首次成功打开过一次**（`v_02`）。此后
`input tap 683 745` 反复无法重新打开面板：

| 尝试 | 截图 | 字节 | 面板是否打开 |
|---|---|---:|---|
| `m_a` | `m_a_open.png` | 58706 | ❌ 未开（底栏出现 `Pin` 长按提示） |
| `c_a` | `c_a_palette.png` | 58023 | ❌ 未开（停在 Find 页） |
| `d_a` | `d_a_palette.png` | 57963 | ❌ 未开 |
| `e_a` | `e_a_palette.png` | 54473 | ❌ 未开 |

配合 dump 不可用（无法读取控件树确认底栏按钮的 enabled/焦点状态），**无法区分**是
"入口确实失灵"还是"我的坐标在特定页面下不对"。

**⚠️ 附带发现（对修复方有价值）**：`f_01_palette_try.png` 抓到 `input tap 683 745` 之后，
底栏按钮上方弹出了 **`Pin` 长按提示气泡**（见图）。说明底栏按钮存在**长按态**，
而本机 `input tap` 在该坐标上有被判成长按（而非点击）的倾向。
这与 §2 的死区现象方向一致、值得一并排查：
**底栏这一行的指针事件在本 AVD 上存在"按下/长按"被截获、既不触发点击也不上抛到遮罩的可能。**
（首轮同样的 `input tap 683 745` 是正常打开面板的，所以这是**间歇性**行为，不是恒定。）

> ⚠️ **此项判定为「未取证」，既不算通过也不算失败。** 需要一台 dump 可用的设备补测。

---

## 5. 判定四：移动形态（vertical + 1200x1600@320）—— ❌ **未执行**

未执行。**前置条件不具备**：移动形态复验必须先成功打开面板，而本轮面板入口在第 1 轮之后即无法稳定打开（见 §4），
叠加 `uiautomator dump` 全程不可用，无法在该形态下可靠定位卡片与底栏 bounds。

为避免留下脏状态，**本轮从未设置过 `wm` Override**，故移动形态"建了又还原"的风险为零。
（收尾读数见 §7。）

---

## 6. 判定五：退出语义回归（v50 退出期穿透）—— ❌ **未执行**

需要"开面板 → 关闭 → 立刻点底栏"的时序。面板无法稳定重开，无法构造该时序，故未执行。
> 补充：本轮**全程未按 BACK(4)、未用 ESC(111)**，遵守展示层纪律。

---

## 7. 判定六：收尾 —— ✅ 全部干净

```powershell
$ adb -s emulator-5562 shell wm size reset ; wm density reset
$ adb -s emulator-5562 shell wm size       → Physical size: 1366x768      (无 Override 行)
$ adb -s emulator-5562 shell wm density   → Physical density: 160       (无 Override 行)
```

| 检查项 | 结果 |
|---|---|
| `wm size` / `wm density` | ✅ 只有 Physical 行，**本轮从未设置 Override**，并显式 reset 兜底 |
| `appearance.tabLayout` 开关 | ✅ `oc_settings.xml` 中**无该键** → 本轮从未修改，无需还原 |
| 本轮 `/sdcard` 残留 | ✅ **零残留**（`s5562_*` / `v_*` / `w_*` / `p[123]` / `r[12]` / `c[12]` / `d[12]` / `e1` / `w5562_*` / `pal.xml` 全部已清） |
| `/sdcard` 既有遗留 | ⚠️ 809 个 png/xml **为往轮 agent 遗留，非本轮产生**。**未删除**——不属于本轮产物，擅自清理会毁掉他人取证材料 |
| `reverse --list` | ✅ 4095/2222 仍在 |
| 进程 | ✅ 全程未杀任何进程（`kill -3` 被设备拒绝，也未强杀） |
| gradle / 装机 | ✅ 全程未执行 |

---

## 8. 未闭环清单

1. **🔴 主判据失败**：底栏死区**依然存在**，修复不完整。这是本轮最重要的结论。
2. **🔴 根因线索（供修复方用）**：遮罩点**能**关、遮罩点**在底栏那段不能**关——说明不是 `scrimTapDismisses`
   的判定逻辑问题（那部分已修好），而是**底栏那一片的触摸根本没送到遮罩的 `pointerInput`**。
   建议排查：底栏是否在 `DialogHost` 的可命中区域之外 / 是否被 IME inset 排除 /
   遮罩 `backdropBounds` 是否未覆盖到底栏。建议加日志确认 `detectTapGestures` 在 (24,745) 是否触发。
3. **面板内正路径（§4）** 未取证——面板入口失灵 + dump 不可用。
4. **移动形态（§5）** 未执行——同上，且从未设置 `wm` Override，无脏状态。
5. **退出语义回归（§6）** 未执行。
6. **取证方式降级**：全程无 `uiautomator dump`，节点数、双引号/单引号文本匹配、
   "点前点后节点签名 diff" 均无法使用，改用截图 MD5。**建议换一台 dump 可用的设备复验补齐。**
7. **`/sdcard` 809 个历史遗留**未清理（不属本轮，留给其所有者）。
8. 该 AVD 的 `keycombination 113 39` 快捷键打不开面板（任务书指定入口在本机无效），
   `BottomTabStrip` 首开后即失灵的原因未查。

---

## 9. 证据文件

| 文件 | 内容 |
|---|---|
| `v_02_palette_open.png` | 面板打开（点前签名 `D08F2B21…`） |
| `v_03_after_bottombar_tap.png` | 点底栏后 —— **与上者逐字节相同** |
| `v_05_after_scrim_right.png` | 点遮罩后 —— 面板已关闭 |
| `v_07_bottombar_nopalette.png` | 面板关闭 + 点同一点 → Find 页（对照） |
| `v_04_after_scrim_tap.png` | 点 (150,200) 落到启动器抽屉（窗口化形态取证） |
| `dump5562.ps1` / `sigdiff.ps1` | dump 辅助脚本 / 像素差分脚本（dump 不可用时的备用） |
