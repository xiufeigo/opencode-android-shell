# 真机复验报告 — v98 移动抽屉布局修复（v97 N-3）

- 复验员：Mavis（真机复验测试员）
- 设备：`emulator-5560`（`emu avd name` = **Pixel_Tablet** ✅）
- 日期：2026-10-02
- 缺陷单：`research/v97_fix/MOBILE_DRAWER_LAYOUT.md`（v97 N-3）
- **终判：`BLOCKED / 未闭环` — 未验到。** 复现路径在第 3 步（移动形态启动）即受阻：
  应用在 `wm size 1200x1600` + `wm density 320` 下渲染为空壳且无响应，
  **底部标签条从未出现在底部**，`input tap 110 1500` 没有目标，抽屉无法打开。
  因此断言 1–5 全部**未验到**，不判 PASS 也不判 FAIL。

---

## 0. 环境读数

| 项 | 读数 | 判定 |
|---|---|---|
| 设备列表 | `emulator-5554/5556/5558/5560/5562/5564` | 仅操作 5560 |
| `emu avd name` | `Pixel_Tablet` | ✅ 与要求一致 |
| 包名 | `com.xiufeigo.opencodeshell` | ✅ |
| `versionName` | `1.4.0` | ✅ |
| `lastUpdateTime` | `2026-10-02 11:39:34` | ✅ 与 Lead 19:38 闸门构建一致 |
| `firstInstallTime` | `2026-09-20 06:59:39` | 本轮未装机（纪律禁止） |
| `wm size`（初始） | `Physical size: 2560x1600` | — |
| `wm density`（初始） | `Physical density: 320` | — |
| `adb reverse` | `host-16 tcp:4095 tcp:4095`、`host-16 tcp:2222 tcp:22` | ✅ |
| 后端健康 | `GET /api/info` → **HTTP 200**，`{"version":"2.0.18","pid":260,...}` | ✅ |

构建复核通过：目标包确为闸门构建，本轮未执行 gradle / 装机 / 杀进程。

---

## 1. 复现路径实测

### 步骤 1 — 形态设置 ✅

桌面形态（2560×1600）下走 UI 设置：

```
input tap 2432 184        # 更多选项
input tap 64 84           # 首页
input tap 334 1381        # 设置
input tap 441 1246        # 实验性功能
input tap 1466 628        # 标签页 值
input tap 1910 786        # 垂直
```

结果（权威读数，`shared_prefs/oc_settings.xml`）：

```xml
<string name="appearance.tabLayout">vertical</string>
```

✅ 设置成功并持久化。证据：`evidence/04_settings.xml`、`05_exp.xml`、`06_tabdlg.xml`、`07_after.xml`

### 步骤 2 — 移动形态 ✅（系统侧全部到位）

```
adb shell wm size 1200x1600
adb shell wm density 320
```

```
Override size: 1200x1600
Physical density: 320
```

运行时配置（`dumpsys activity`）：

```
mOverrideConfig={... sw600dp w600dp h800dp 320dpi lrg port ...}
```

**600 dp < 768 dp 断点**（`MobileForm.DESKTOP_MIN_WIDTH_DP = 768`）→ 运行时确为移动形态。
`bottomTitlebar = !desktopTabs && bottomTabStrip`，而 `bottomTabStrip` 来自
`OcActivity.kt:143` → `AppearancePrefs.bottomTabStrip()` →
`appearance.tabLayout == "vertical"`（源码已 grep 复核，链路正确，`vertical` 预设已落盘）。
**按代码推导，底部标签条本应挂载。**

### 步骤 3 — 启动应用 ❌ **受阻点**

`am force-stop` → `monkey … LAUNCHER`，**共执行 4 次独立冷启动**（settle 8s / 20s / 50s / **95s**）。
4 次全部得到**完全一致**的节点树（`uiautomator dump` 全节点，含无文本节点）：

```
 1 FrameLayout            [0,0][1200,1600]
 …
 6 ComposeView            [0,0][1200,1600]
 7 View                   [0,0][1200,1600]
 8 View                   [0,0][1200,1600]
 9 EditText               [112,40][1144,136]          ← 首页搜索框
10 Button                 [1084,72][1180,136]  [新建会话]
11 Button                 [0,0][64,84]         [首页]
12 Button                 [64,0][132,84]       [新建会话]
13 Button                 [132,0][228,84]      [命令面板]
```

观察到的三点，均与「修复后应然状态」相悖：

1. **标签条在 y=0..84（顶部），不在底部。** 缺陷单预期的修复后读数是 `y≈1452..1548`。
2. **正文为空。** 无会话列表、无项目列表、无会话页正文 —— 仅剩一个搜索框和 3 个条按钮。
3. **应用无响应。**
   - `input tap 98 42`（条内「新建会话」）→ 节点树无任何变化；
   - `input tap 600 88`（搜索 EditText）→ 键盘未弹出：
     `mInputShown=false`，`mIsInputViewShown=false`。

**不是崩溃。** 进程存活（`pidof` = 13898），logcat 无 `FATAL` / `AndroidRuntime` 异常；
仅有 JIT 编译与 `HWUI` 帧耗时告警：

```
D EGL_emulation: app_time_stats: avg=15198.73ms min=15198.73ms count=1
W HWUI: Davey! duration=2376ms
I Choreographer: Skipped 113 frames!
```

即**单帧耗时 ~15 s**，交互自然「看起来」无响应；95 s 的 settle 也不足以让空壳稳定出内容。

证据：`evidence/08_mobile_launch.{xml,png}`、`09_mobile.{xml,png}`、`10_mobile2.xml`、
`12_restart.{xml,png}`、`13_settled.xml`、`14_all.xml`、`15_session.xml`、
`16_clean.{xml,png}`、`17_ime.xml`、`19_mobile_final.{xml,png}`

### 步骤 3 的对照实验 —— 定位到「移动形态」而非设备/应用整体故障 ✅

`wm size reset` + `wm density reset` + 重启后，同一构建在 **2560×1600** 下**渲染完全正常**：

```
 9 Button  [16,36][112,132]     [首页]
10 Button  [116,36][184,132]    [新建会话]
11 Button  [184,36][280,132]    [命令面板]
12 TextView [260,386][708,423]  项目
13 Button  [708,356][804,424]   [添加项目]
14 View    [248,424][808,520]
15 TextView [275,460][281,484]  .
16 TextView [312,457][584,488]  .cache
17 Button  [584,424][656,520]   [更多选项]
 …（会话行、侧栏齐全）
```

即：**故障仅在 `1200×1600`+`320dpi` 移动形态下出现**，桌面形态同一构建健康。
证据：`evidence/18_reset.xml`

---

## 2. 逐条判定

前置状态未达成（底部标签条未挂载、抽屉无法打开），断言 1–5 均**未验到**。

| # | 断言 | 判定 | 读数 / 依据 |
|---|---|---|---|
| 1 | 底部标签条仍在屏幕底部（`y≈1452..1548`），不再跳到 y=0 | **未验到** | 抽屉**关闭态**下条的位置为 `y=0..84`（`evidence/14_all.xml`），已在顶部而非底部；但**打开态无法取得**（`input tap 110 1500` 处无任何节点，点击无效果）。故「开抽屉是否位移」这一核心问题**无数据**。 |
| 2 | NavHost 高度未被压零（会话内容仍在可见区域） | **未验到** | 会话正文在本形态下**从未渲染**（4 次启动节点树仅 5 个语义节点），无可测 bounds。 |
| 3 | 状态栏未被遮挡 | **未验到** | 抽屉未打开，遮挡场景未构造。截图 `19_mobile_final.png` 仅见系统状态栏，无应用标签条覆盖 —— 但这是空壳画面，不构成证据。 |
| 4 | 反例对照：抽屉开/关位置一致 | **未验到** | 两个状态均不可达。 |
| 5 | 回归：点会话切换 / 长按拖拽重排 / 长按 Rename / scrim 关闭 | **未验到 ×4** | 抽屉未打开；且应用在本形态下无响应（四项均无法触发）。 |

### 位移量（修前 / 修后）

**无法给出。** 「修后」值需要抽屉打开态的读数，而打开态不可达。

仅可给出**关闭态**的一条异常读数，供后续定位参考（**不可当作 v97 N-3 的复现**）：

| 状态 | 位置读数 | 与缺陷单预期（`y≈1452..1548`）之差 |
|---|---|---|
| 抽屉**关闭**（本轮实测，4 次一致） | `y = 0..84` | 条顶边比预期**高 1452 px**（落在缺陷单所述 y=0..84 区间） |
| 抽屉**打开** | 未取得 | — |

⚠️ 该读数**与 v97 N-3 的缺陷签名不符**：N-3 是「打开抽屉时」从 1452 跳到 0，
而这里是「**抽屉从未打开、条就在顶部**」，且伴随正文全空 + 无响应 + 帧耗时 ~15 s。
因此**不能据此判定 v97 N-3 修复失败**，也不能判定通过 —— 判 **BLOCKED**。
该现象应作为**独立问题**另开单排查（形态判定 vs 渲染/性能）。

---

## 3. 终态（已全部还原 ✅）

| 项 | 还原动作 | 复核读数 |
|---|---|---|
| `wm size` | `wm size reset` | `Physical size: 2560x1600` ✅ |
| `wm density` | `wm density reset` | `Physical density: 320` ✅ |
| 标签页布局开关 | 设置 → 实验性功能 → 标签页 → **水平** | UI `t='水平' b=[1440,610][1492,647]`；pref `<string name="appearance.tabLayout">horizontal</string>` ✅ |
| 设备 | 仅 `emulator-5560` | 未触碰 127.0.0.1:5555 / 5554 / 5556 / 5558 / 5562 / 5564 ✅ |
| 纪律 | 未跑 gradle / 未装机 / 未杀进程 / 未按 BACK / 未用 ESC / 未点任何「删除」 | 截图全部 `screencap -p` + `adb pull`，无 `exec-out` ✅ |
| 共享后端 | 只读 `/api/info`；未新建/改动他人会话 | ✅ |

还原证据：`evidence/20_restore.xml`、`21_exp.xml`、`22_ddl.xml`、`23_restored.xml`

**收尾复核（关闭设置弹层后再次读取，19:56）**：

```
wm size    → Physical size: 2560x1600     ✅ 无 Override
wm density → Physical density: 320        ✅ 无 Override
oc_settings.xml → appearance.tabLayout = horizontal   ✅
应用可用性 → 桌面形态恢复正常渲染（条 y=36..132、项目/会话列表齐全）✅
```

证据：`evidence/24_final.xml`

---

## 4. 未闭环

| 项 | 状态 | 原因 / 建议后续 |
|---|---|---|
| 断言 1–5 全部 | **未验到** | 移动形态下应用渲染空壳 + 无响应，抽屉不可达 |
| `BottomTabStrip` 开/关位移量 | **未验到** | 需先解决移动形态可渲染问题 |
| 修前 vs 修后位移量 px | **无法给出** | 同上 |
| 移动形态 1200×1600 渲染空壳 | **新发现，未定级** | 4 次冷启动稳定复现；桌面形态同构建健康。建议单独排查：① `w600dp` 下 `AppScaffold` 布局分支；② 空壳期 ~15 s/帧的根因（JIT + 慢后端）是否为阻塞主因，建议先给更长 settle 或预热后再复测 |
| 抽屉交互回归 4 条 | **未验到** | 依赖抽屉可达 |
| 闸门构建本身 | **已复核通过** | `lastUpdateTime=2026-10-02 11:39:34` / `versionName=1.4.0`，与 Lead 闸门一致 |

**复测前置建议**：先在 `emulator-5560` 上确认 `wm size 1200x1600`+`wm density 320` 能稳定渲染出
**底部**标签条与完整正文，再按缺陷单 §4 原路径复验抽屉。

---

## 5. 证据文件索引

均相对本报告所在目录 `research/v97_verify/5560_v98/`。

| 路径 | 说明 |
|---|---|
| `evidence/01_launch.xml` … `07_after.xml` | 桌面形态：设置 → 实验性功能 → 标签页=垂直 |
| `evidence/08_mobile_launch.{xml,png}` | 移动形态第 1 次启动（空壳） |
| `evidence/09_mobile.{xml,png}` | 移动形态（空壳，截图见白屏） |
| `evidence/10_mobile2.xml` | settle 12 s 后，仍空壳 |
| `evidence/12_restart.{xml,png}` | 第 2 次冷启动，仍空壳 |
| `evidence/13_settled.xml` | settle 45 s，仍空壳 |
| `evidence/14_all.xml` | **全节点树**（含无文本节点）—— 条 `y=0..84` 的关键证据 |
| `evidence/15_session.xml` | 点条内「新建会话」后，无任何变化 |
| `evidence/16_clean.{xml,png}` | 第 3 次冷启动，仍空壳 |
| `evidence/17_ime.xml` | 点搜索框后 IME 未弹（`mInputShown=false`） |
| `evidence/18_reset.xml` | **`wm reset` 后桌面形态渲染正常**（对照实验） |
| `evidence/19_mobile_final.{xml,png}` | 第 4 次冷启动 + **95 s** settle，仍空壳 |
| `evidence/20_restore.xml` … `23_restored.xml` | 终态还原（`wm` + 开关回水平） |
| `dump.ps1` / `dumpall.ps1` / `shot.ps1` | 本轮所用取证脚本（`screencap -p` + `adb pull`） |
