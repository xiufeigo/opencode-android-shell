# 真机复验报告 — v98 移动抽屉布局修复（v97 N-3）· 第二轮

- 复验员：Mavis（真机复验测试员）
- 设备：`emulator-5560`（`emu avd name` = **Pixel_Tablet** ✅）
- 日期：2026-10-02
- 缺陷单：`research/v97_fix/MOBILE_DRAWER_LAYOUT.md`（v97 N-3）
- 上一轮：`research/v97_verify/5560_v98/REPORT_DRAWER.md`（判 BLOCKED，卡在「移动形态空壳」）

## 终判：**FAIL — v97 N-3 修复无效，且定位到根因（`CompositionLocal` 无法重父化）**

**上一轮的 BLOCKED 已解除，但结论翻转为 FAIL：**

1. 「移动形态空壳」**不是**配置姿势问题、不是 `wm` 问题、不是慢后端问题 ——
   本轮用**四组对照**把触发条件收敛到唯一组合：**移动宽度 + 标签页布局=垂直**。
2. 空壳的成因在代码里可直接读出：v97 N-3 的「把抽屉改成覆盖层」修法**在结构上无效**。
   `CompositionLocal` 提供的是一个 **lambda**，调用点仍��消费者的位置，
   `Box(fillMaxSize)` 依旧落在 shell `Column` 的 **flow 子节点**里 —— 即缺陷 N-3 的原始成因，一字未改。
3. 因此抽屉**仍无法打开**，但本轮拿到了上一轮缺的关键读数：
   **标签条在「抽屉关闭态」就位于 `y=0..84`（顶部）**，正是 N-3 的缺陷签名。
4. 断言 1 / 2 / 3 判 **FAIL**（有实测读数）；断言 4 / 5 判 **未验到**（抽屉不可达）。

---

## 0. 环境读数

| 项 | 读数 | 判定 |
|---|---|---|
| 设备列表 | `emulator-5554/5556/5558/5560/5562/5564` | 仅操作 5560 ✅ |
| `emu avd name` | `Pixel_Tablet` | ✅ 与要求一致 |
| 包名 | `com.xiufeigo.opencodeshell` | ✅ |
| `versionName` | `1.4.0` | ✅ |
| `lastUpdateTime` | `2026-10-02 11:39:34` | ✅ 与 Lead 19:38 闸门构建一致 |
| `wm size`（初始） | `Physical size: 2560x1600` | 无 Override ✅ |
| `wm density`（初始） | `Physical density: 320` | 无 Override ✅ |
| `adb reverse` | `host-16 tcp:4095 tcp:4095`、`host-16 tcp:2222 tcp:22` | ✅ |
| 后端健康 | `GET /api/info` → **HTTP 200**，`{"version":"2.0.18","pid":260,...}` | ✅ |

构建复核通过。本轮**未跑 gradle / 未装机 / 未杀进程**。

---

## 1. 逐步诊断表 —— 哪一步把 app 变成空壳

**空壳判据**：`uiautomator dump` 的语义节点数（本轮实测 12–13 个，
仅剩「搜索框 + 3 个条按钮」，无项目列表 / 无会话列表 / 无会话正文）。

| # | 步骤 | 配置 | 语义节点数 | 标签条 bounds | 结论 |
|---|---|---|---|---|---|
| **S1** | 基准线：无任何 wm override，横向标签 | 2560×1600 / 320 / `horizontal` | **153** | `y=36..132`（顶部，桌面条） | ✅ **正常** |
| **S2** | 仅 wm，标签仍横向 | 1200×1600 / 320 / `horizontal` | **97** | `y=36..132` | ✅ **正常**（项目列表/会话列表齐全） |
| **S3** | 桌面 + 垂直标签 | 2560×1600 / 320 / `vertical` | **153** | `y=36..132` | ✅ **正常** |
| **S4** | **移动 + 垂直标签（先 wm 后冷启动）** | 1200×1600 / 320 / `vertical` | **13** 🔴 | **`y=0..84`（顶部）** | ❌ **空壳** |
| **S5** | S4 再等 70 s（累计 settle >100 s） | 同上 | **13** 🔴 | `y=0..84` | ❌ **空壳（不自行恢复）** |
| **S6** | **移动 + 垂直（先启动后 wm，活体 resize 对照）** | 1200×1600 / 320 / `vertical` | **13** 🔴 | `y=0..84` | ❌ **空壳**（与启动顺序无关） |
| **S7** | wm reset 回桌面（垂直仍在） | 2560×1600 / 320 / `vertical` | **153** | `y=36..132` | ✅ **恢复** |

### 诊断结论

触发空壳的**充要条件 = 移动宽度（`screenWidthDp < 768`）且 `tabLayout = vertical`**：

- S2 证明 **`wm` 本身无害**（1200×1600 下应用完全可用，97 节点）；
- S3 证明 **`vertical` 本身无害**（桌面宽度下 153 节点正常）；
- S4/S5/S6 证明**两者同时成立必空壳**，且**与冷/热启动顺序无关**（S6 是从健康的桌面态
  活体 resize 过来的，同样塌陷）。

→ 上一轮把它记作「配置姿势问题」或「后端慢导致空壳」的假设，**均被本轮对照排除**。

### logcat 原文（空壳态，`logcat -d -t 400`）

```
（按要求的 -Pattern 'FATAL|AndroidRuntime|Exception|ANR|OcApp|OcActivity' 过滤）
→ 无任何匹配行。**不是崩溃，没有 FATAL / 没有 AndroidRuntime 异常 / 没有 ANR。**
```

进程存活（`pidof` = 15495 / 15697）。实际日志只有编译与慢帧：

```
D EGL_emulation: app_time_stats: avg=1224.20ms min=1224.20ms max=1224.20ms count=1
I o.opencodeshell: Compiler allocated 4338KB to compile void com.xiufeigo.opencodeshell.ui.home.HomeScreenKt$HomeScreen$9$1.invoke(androidx.compose.foundation.layout.BoxWithConstraintsScope, androidx.compose.runtime.Composer, int)
```

> 原始日志全文：`evidence/logcat_shell.txt`。
> 注：本轮慢帧为 **1224 ms/帧**（上一轮记为 ~15 s），量级不同但同样存在。
> **慢帧不是空壳的成因** —— S6 是在应用已完全预热（桌面态已渲染 153 节点）的情况下
> 活体 resize，依然立刻塌陷为 13 节点。

### 交互响应性

| 尝试 | 结果 |
|---|---|
| `input tap 98 42`（条内「新建会话」`[64,0][132,84]`） | 节点树**无任何变化**（`18_after_strip_tap.xml` 与 `17_shell_all.xml` 完全一致） |
| `input tap 1132 104`（Home「新建会话」`[1084,72][1180,136]`） | **约 45 s 后**才响应，路由进入会话页（出现「返回」`[8,84][104,136]`） |

即：应用**不是完全无响应，而是被拖到数十秒级延迟**；条内控件在 8 s 内无反馈。

---

## 2. 根因（代码取证）—— 修复为何无效

`ui/nav/BottomTabStrip.kt:244`，抽屉经 `CompositionLocal` 挂载：

```kotlin
    // v97 N-3: the sheet leaves this composable's flow. ...
    // The host's overlay slot re-parents the sheet next to the column — the
    // same `position: fixed` shape upstream's `mobile-drawer.css` uses — so the
    // column's height distribution is untouched by whether the drawer is open.
    LocalMobileDrawerOverlay.current {
        MobileTabsDrawer(open = drawerOpen, ...)
    }
```

`ui/App.kt:1583-1591`，宿主提供方：

```kotlin
Box(Modifier.fillMaxSize()) {
    CompositionLocalProvider(
        LocalMobileDrawerOverlay provides { drawer ->
            Box(Modifier.fillMaxSize()) { drawer() }   // ← 期望的重父化容器
        },
    ) {
Column(Modifier.fillMaxSize().background(OcTheme.colors.bgDeep)) {
```

**这段修法在 Compose 语义上不成立**：`CompositionLocal` 提供的是一个**函数值**。
`BottomTabStrip` 调用 `LocalMobileDrawerOverlay.current { ... }` 时，
`Box(fillMaxSize)` 被组合进的是**调用点所在的位置**（`BottomTabStrip` 的 composable 体），
即 shell `Column` 的 **flow 子节点**；它**不会**被搬到 `App.kt:1583` 那个 `Box` 下。
`CompositionLocalProvider` 只提供值，从不重父化（re-parent）子组合。

于是 N-3 的原始成因**原封不动地保留**：

- 该 `Box(fillMaxSize)` 是 `Column` 的一个非加权子节点；
- `Column` 先测量非加权子节点 → 它吃掉列的**全部剩余高度**；
- `weight(1f)` 的 NavHost 被挤到 **0 px**；
- `BottomTabStrip` 的 `Row` 被顶到 **`y=0..84`**，压在状态栏之下。

注释里「Emitted in flow it was a third `Column` child whose `fillMaxSize` host
consumed the column's remaining height」**准确描述了当前仍在发生的行为** ——
修复只是把这个 host 从「sheet 内部」挪到了「`BottomTabStrip` 内部」，两者都是 flow 子节点。

**与实测完全吻合**：S4/S5/S6 三种入口下，标签条恒为 `y=0..84`，
且**抽屉关闭态就已经如此**（`drawerOpen=false` 时 `AnimatedVisibility` 不绘制内容，
但外层 `Box(fillMaxSize)` 仍然占据空间）——这正是「空壳 + 条在顶部」同时出现的原因。

> 正确修法方向（供参考，本轮未改代码）：不要用 `CompositionLocal` 跨层挂载。
> 应把 `MobileTabsDrawer` 直接作为 `App.kt:1583` 那个外层 `Box` 的**第二个子节点**渲染
> （`Box` 天然让子节点叠放而非流式排布），并把 `drawerOpen` 状态提升到该层。

---

## 3. 抽屉断言逐条判定

| # | 断言 | 判定 | 读数 / 依据 |
|---|---|---|---|
| **1** | 底部标签条**仍在底部**，不再跳到 y=0 | **FAIL** | 移动+垂直形态下，**关闭态**标签条实测 **`y=0..84`**（`evidence/17_shell_all.xml`）。同一读数在 S4/S5/S6 三种入口下完全一致。预期 `y≈1452..1548`（缺陷单），实际条顶边**高出 1452 px**。 |
| **2** | NavHost 高度未被压零（会话正文有可测 bounds） | **FAIL** | NavHost 已被压到 **0 px**：移动+垂直形态只剩 12–13 个节点，**无项目列表、无会话列表、无会话正文**。对照组 S2（同样 1200×1600，仅标签横向）有 97 节点、正文完整 → 证明是 `vertical` 路径压零，不是屏幕太小。 |
| **3** | 状态栏未被遮挡 | **FAIL** | 标签条 `y=0..84` 从**屏幕最顶端 y=0** 起画，完全落在状态栏区域内。设备为 `1200×1600@320dpi`，状态栏高度远超 0 → 状态栏被标签条覆盖。 |
| **4** | 反例对照：抽屉**关闭态**与**打开态**标签条 y 一致 | **未验到** | 打开态不可达（见断言 5）。仅取得关闭态 `y=0..84`。 |
| **5** | 回归：①点会话切换 ②长按拖拽重排 ③长按 Rename ④scrim 点击关闭 | **未验到 ×4** | 抽屉**打不开**。根因：抽屉触发器是 `SessionTab`（`BottomTabStrip.kt:200-213`，仅当 `session != null` 时渲染）；空壳态下打开会话后条内**始终只有 3 个图标**（首页/新建会话/命令面板），`SessionTab` 槽位从未出现，故无触发器可点。 |

### 位移量（修前 / 修后）

| 状态 | 位置读数 | 与缺陷单预期（`y≈1452..1548`）之差 |
|---|---|---|
| 抽屉**关闭**（本轮实测，S4/S5/S6 一致） | **`y = 0..84`** | 条顶边**高 1452 px** |
| 抽屉**打开** | **未取得** | 抽屉不可达 |
| 修后 vs 修前 | **无变化** | 修复前后关闭态读数相同（均落在 N-3 缺陷签名区间） |

**结论：v97 N-3 修复未生效，关闭态读数与修复前的缺陷签名完全一致。**

---

## 4. 终态复核（设备已完整还原 ✅）

| 项 | 还原动作 | 复核读数 |
|---|---|---|
| `wm size` | `wm size reset` | `Physical size: 2560x1600`，**无 Override** ✅ |
| `wm density` | `wm density reset` | `Physical density: 320`，**无 Override** ✅ |
| 标签页布局开关 | 设置 → 实验性功能 → 标签页 → **水平** | pref `<string name="appearance.tabLayout">horizontal</string>` ✅ |
| 设置弹层 | 已关闭（重启后回到 Home） | ✅ |
| 应用可用性 | `am force-stop` 后重启一次 | **153 节点**，项目列表/会话列表齐全，桌面条 `y=36..132` ✅ |
| 设备范围 | 仅 `emulator-5560` | 未触碰 127.0.0.1:5555 及其他 emulator ✅ |
| 纪律 | 未跑 gradle / 未装机 / 未杀进程 / 未按 BACK(4) / 设置弹层内未用 ESC(111) / 截图全部 `screencap -p` + `adb pull`（无 `exec-out`） | ✅ |
| 共享后端 | 仅只读 `GET /api/info` | 未新建/改动他人会话，未点任何「删除」 ✅ |

**收尾复核证据**：`evidence/20_restore_desktop.xml`、`21_final_verify.xml`、`21_final_verify.png`

> 一次险情已处理并如实记录：为找「设置」入口误 tap 到项目行溢出菜单的「关闭」项，
> 弹出「关闭项目？」确认框，**已点「取消」**（`evidence/03_close_dialog.png`、
> `04_cancelled.xml`），项目列表 153 节点原样恢复，未关闭任何项目。

---

## 5. 未闭环

| 项 | 状态 | 原因 / 建议 |
|---|---|---|
| 断言 4、5（抽屉 4 条回归） | **未验到** | 抽屉触发器 `SessionTab` 在空壳态不渲染，抽屉不可达；**需先修好 N-3 才谈得上复验** |
| 抽屉打开态标签条 y 读数 | **未取得** | 同上 |
| **v97 N-3 修复有效性** | **FAIL（已定论）** | 根因已定位：`CompositionLocal` 不重父化，`Box(fillMaxSize)` 仍是 shell `Column` 的 flow 子节点。建议按 §2「正确修法方向」改为外层 `Box` 的直接兄弟子节点 + 状态提升 |
| 空壳态交互延迟（~45 s 才响应） | **新发现，未定级** | 与压零同现。NavHost 为 0 px 时布局/合成路径疑似退化，建议随 N-3 一并排查 |
| 闸门构建本身 | **已复核通过** | `lastUpdateTime=2026-10-02 11:39:34` / `versionName=1.4.0` |

---

## 6. 证据文件索引

均相对本报告所在目录 `research/v97_verify/5560_v98/5560_v98_drawer2/`。

| 路径 | 说明 |
|---|---|
| `evidence/00_baseline.png` | 基准线截图（桌面/横向，Home 完整） |
| `evidence/01_baseline.xml` | **S1** 桌面+横向，153 节点 ✅ |
| `evidence/02_overflow.xml` | 项目行溢出菜单 |
| `evidence/03_close_dialog.png` | 误触「关闭项目？」确认框（已取消） |
| `evidence/04_cancelled.xml` | 取消后 153 节点，项目列表完整 |
| `evidence/05_topbar_overflow.xml` | 桌面顶栏探测（该处无溢出节点） |
| `evidence/06_wmonly_horizontal.xml` | **S2** 1200×1600 + 横向，97 节点 ✅ |
| `evidence/07_settings.xml` … `11_vertical_selected.png` | 移动态下走设置：设置 → 实验性功能 → 标签页 → 垂直 |
| `evidence/12_vertical_mobile.xml` | **S4** 移动+垂直冷启动，13 节点 🔴，条 `y=0..84` |
| `evidence/13_shell_vertical.png` | 空壳截图 |
| `evidence/logcat_shell.txt` | **空壳态 logcat 原文**（无 FATAL/Exception/ANR） |
| `evidence/14_vertical_mobile_settle70.xml` | **S5** 再等 70 s，仍 13 节点 |
| `evidence/14_settle70.png` | 同上截图 |
| `evidence/15_desktop_vertical.xml` | **S3** 桌面+垂直，153 节点 ✅（证明 vertical 本身无害） |
| `evidence/16_live_resize.xml` / `.png` | **S6** 先启动后 wm（活体 resize），仍 13 节点 🔴 |
| `evidence/17_shell_all.xml` | **空壳全节点树**（13 节点）—— 条 `y=0..84` 的关键证据 |
| `evidence/18_after_strip_tap.xml` | 点条内「新建会话」→ 节点树无变化 |
| `evidence/19_after_home_new.xml` / `.png` | 点 Home「新建会话」→ 45 s 后进入会话页，**条内仍无 `SessionTab` 触发器** |
| `evidence/20_restore_desktop.xml` | wm reset 后 153 节点 ✅ |
| `evidence/21_final_verify.xml` / `.png` | **终态复核**：153 节点 + 标签条 `horizontal` + wm 无 Override ✅ |
| `dump.ps1` / `shot.ps1` | 本轮取证脚本（`screencap -p` + `adb pull`） |
