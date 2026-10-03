# 真机复验报告 — v98 fix2 移动抽屉（第三轮）

**设备** `emulator-5560` / **AVD `Pixel_Tablet`**（`emu avd name` 实测确认）/ `2560x1600@320`
**包** `com.xiufeigo.opencodeshell` / 启动入口 `.OcActivity`（`MainActivity` 未导出，用 LAUNCHER intent 启动）
**构建** `versionName 1.4.0`，`versionCode 26`，`lastUpdateTime=2026-10-02 12:39:05` —— 与 Lead 20:38 闸门构建一致（设备时）。
**后端** `http://127.0.0.1:4095` reverse 已挂（本轮未改任何会话，未触发任何 Delete）。

> 方法：`am force-stop` / LAUNCHER 冷启动，`uiautomator dump`（全节点）→ `adb pull`，
> 截图一律 `screencap -p` + `adb pull`（未用 exec-out）。坐标全部运行期从 dump 的 `clickable` bounds 解析。
> 实验开关 `appearance.tabLayout` 通过 `run-as` 改 pref 并**读回复核**（app 先 force-stop，避免内存态覆盖写回）。

**总体结论**
- ✅ **「移动形态空壳」回归已消失** —— 步骤 ④（vertical ∧ `wm 1200x1600@320`）**89 节点**，Home 完整渲染，标签条贴底 `y=1452..1548`，**不再是 13 节点空壳、不再是 `y=0..84`**。这是本轮 fix2 的核心目标，达成。
- ❌ **但抽屉在移动垂直形态下打不开** —— 步骤 ⑤ 打开态 FAIL：点标签条抽屉手柄 5 次（含冷启动干净复现），UI 零变化（dump 逐字节相同）；同一条上的「命令面板」按钮对照实验可正常响应，证明 tap 已送达，**抽屉打开路径本身失效**。因此「开抽屉时标签条 y」「开/关两次 dump 条 bounds 逐字比对」「scrim 覆盖」三条断言 **未验到**（打开态不可达）。
- ⚠️ 该 FAIL 与「空壳消失」并不矛盾：fix2 把抽屉内容移到外层 `Box` 后置兄弟节点后，**关闭态的壳问题解决，但打开态的挂载/可见性没打通**（详见 §5 现象分析，根因未定位到单行）。

---

## 1. 环境读数（复核实测）

| 项 | 实测值 |
|---|---|
| AVD 名 | `Pixel_Tablet` ✅ |
| 物理尺寸/密度 | `Physical size: 2560x1600` / `Physical density: 320`（测试全程除 §3 的 override 外保持） |
| 安装包 | `versionName 1.4.0` / `versionCode 26` / `lastUpdateTime=2026-10-02 12:39:05` |
| reverse | 4095 / 2222 已挂（沿用） |

---

## 2. 六步逐条

### 步骤 ① 默认形态基准（无 wm override，标签=horizontal） — **PASS**
- 配置：`wm size/density` 无 override；`appearance.tabLayout=horizontal`。
- 读数：**语义节点 153**；Home 正常渲染（左侧「项目」列表 9 个项目 + 右侧会话列表）；桌面条在**顶部** `y=36..132`（首页/新建会话/命令面板）。
- 判定：与复验清单期望「≈153 节点 / 桌面条 y=36..132」**一致**。
- 证据：`evidence/S1_default_horizontal.dump.xml` / `.png`

### 步骤 ② 只开垂直标签页（桌面宽度，标签=vertical） — **PASS**
- 配置：仅改开关为 `vertical`（桌面宽度），重启。
- 读数：**语义节点 153**；Home 正常渲染；桌面条仍在**顶部** `y=36..132`（桌面宽度下 `DesktopTabStrip` 不受 vertical 影响，符合 §1 第 2 环）。
- 判定：正常，**非空壳**。
- 证据：`evidence/S2_vertical_desktopW.dump.xml` / `.png`

### 步骤 ③ 只加窗口 override（`wm 1200x1600@320`，标签=horizontal） — **PASS**
- 配置：`wm size 1200x1600` + `wm density 320`（读回 `Override size: 1200x1600`）；标签仍 horizontal；重启。
- 读数：**语义节点 97**；Home 在 1200 宽下正常渲染（项目列表 + 会话列表）；条在顶部 `y=36..132`。
- 判定：正常，**非空壳**（移动宽度本身无害）。与清单「≈97」一致。
- 证据：`evidence/S3_wm_horizontal.dump.xml` / `.png`

### 步骤 ④ 两者同时（**关键回归点**：vertical ∧ `wm 1200x1600@320`） — **PASS（空壳已消失）**
- 配置：`appearance.tabLayout=vertical` + `wm size 1200x1600` + `wm density 320`；清 logcat 后冷启动；`logcat -d -t 400` 无 FATAL/ANR/Exception。
- 读数：
  - **语义节点 89**（**≠ 13**，不再是空壳；对照步骤 ③ 的 97，属移动宽度正常量级）。
  - Home 正常渲染：「项目」列表 8 个项目 + 「最近会话」列表多条。
  - 底部标签条在**底部**：`首页 [0,1452][84,1548]`、`新建会话 [461,1452][529,1548]`、`命令面板 [529,1452][625,1548]`，即 **`y=1452..1548`** —— 与清单期望区间一致，**未出现 `y=0..84`**。
- 判定：**PASS**。空壳回归（13 节点 + 条压顶）已消除，NavHost 未被压零。
- 证据：`evidence/S4_vertical_wm_KEY.dump.xml` / `.png`

### 步骤 ⑤ 抽屉覆盖层（打开态） — **FAIL（抽屉打不开）；关闭态子项 PASS**
关闭态（抽屉收起、会话已开、键盘已收）可测项：

| 子断言 | 读数 | 判定 |
|---|---|---|
| 底部标签条仍在底部（关闭态） | `y=1452..1548`（修前空壳态为 `y=0..84`） | **PASS**（贴底，接近屏幕底部 1600） |
| NavHost/会话内容未被压零 | 会话正文 `构建任何内容 [522,626..663]`、输入框 `[56,1164][1144,1260]`、动作行 `[1360..1452]` 等**均有可测非零 bounds** | **PASS** |
| 状态栏未被遮挡 | 顶部标签 `会话/变更/文件/终端` 在 `y=72..144`，状态栏图标（12:48/电池）可见 | **PASS** |

打开态 —— 抽屉**无法打开**（详见 §5）：
- 打开态标签条 y / 开↔关两次 dump 条 bounds 逐字比对 / scrim 覆盖 bar：**未验到**（打开态不可达）。

- 证据：`evidence/S5clean_pre.dump.xml`（关）、`evidence/S5clean_post.dump.xml`（点后，与前者**逐字节相同 16667 B**）、`evidence/S5clean_post.png`、`evidence/S5_control_palette.png`（对照实验）、`evidence/tabstrip_clickables.txt`

### 步骤 ⑥ 收尾还原 + 终态复核 — **PASS**
- `wm size reset` + `wm density reset` → 读回 `Physical size: 2560x1600` / `Physical density: 320`（**无 Override 行**）。
- `appearance.tabLayout` 改回 `horizontal` → 读回 `horizontal`。
- 冷启动 → 点首页回 Home 仪表盘：**语义节点 158**（≥ 步骤 ① 的 153），Home 完整渲染，桌面条回到顶部 `y=36..132`。
- 判定：**PASS** —— 设备已还原为健康默认形态，未被留在坏状态。
- 证据：`evidence/S6_home_final.dump.xml` / `.png`、`evidence/S6_final_default.dump.xml` / `.png`

---

## 3. 「是否空壳」判定表（步骤 ②/③/④）

空壳基准 = 复验二 `REPORT_DRAWER2.md` 的 **13 节点 + 条 `y=0..84`**。

| 步骤 | 配置 | 语义节点数 | 是否空壳(13) | Home 是否渲染 | 标签条 y | 判定 |
|---|---|---:|---|---|---|---|
| ② | vertical ∧ 桌面宽度(2560) | **153** | 否 | 是 | `36..132`(顶) | PASS |
| ③ | horizontal ∧ `wm 1200x1600@320` | **97** | 否 | 是 | `36..132`(顶) | PASS |
| ④ | **vertical ∧ `wm 1200x1600@320`** | **89** | **否** | **是** | **`1452..1548`(底)** | **PASS** |

> 四步全部远离 13 节点；步骤 ④ 的 89 落在移动宽度健康量级（对照 ③ 的 97），且条贴底。
> **判定：空壳回归已消失。**

---

## 4. 标签条 y 对照（修前 vs 修后）

| 形态 | 读数 |
|---|---|
| 修前（v97 空壳态，关闭态即塌陷） | `y=0..84`（压状态栏，NavHost=0） |
| 修后（v98 fix2，步骤 ④/⑤ 关闭态） | **`y=1452..1548`**（贴底） |
| 修后（开抽屉时） | **未验到** —— 抽屉打不开，打开态不可达 |

---

## 5. 步骤 ⑤ 抽屉打不开 —— 现象与证据（未闭环，需回单）

**复现（冷启动干净复现，`S5clean_*`）**：开 1 个会话 → 收键盘 → 点底部标签条里的抽屉手柄
（`SessionTab`，clickable bounds `[84,1452][471,1548]`，center 277,1500）→ 屏幕零变化：
- 节点数 49 → 49，dump **逐字节相同（16667 B）**，截图仍是普通会话视图，**无覆盖层/无 scrim**。
- 总计 5 次尝试（含 246/272/277 三个 x 位点、一次长按）均不开。长按那次因前一步 scrim 未真正关闭命令面板而被键盘/表情面板污染，**不计为有效证据**；有效的是键盘已收、无遮挡的 tap 复现。

**对照实验（证明 tap 已送达）**：点同一条上「命令面板」`[539,1452][635,1548]` → 命令面板**正常打开**
（截图 `S5_control_palette.png`：新建会话/新建工作区/上一个会话/下一个会话…）。
→ **同一条、同一 y 的相邻按钮 tap 生效，唯独抽屉手柄不生效** ⇒ 非「tap 没送到」，而是**抽屉打开路径本身失效**。

**代码侧定位到但未闭环**：`BottomTabStrip.kt:237` `SessionTab.onClick = { drawerOpen = true }`；
`BottomTabStrip.kt:313-316` `SideEffect{ drawerMount.content=…; drawerMount.open=drawerOpen }`；
`App.kt:2040-2042` `if (mobileDrawerMount.open && mobileSheet != null){ Box(fillMaxSize){ mobileSheet() } }`。
代码形状与修复单 §2 一致，但真机上 overlay 不出现。**根因未定位到单行**——可能分支（未逐一排除）：
① `drawerOpen=true` 后经 `SideEffect` 写入 `drawerMount.open` 的**状态订阅/重父化**在真机上未触发兄弟节点重组；
② `SessionTab` 手柄的 clickable 在真机上 hit-test 未命中（但同层对照按钮命中，故更可能是 ①）；
③ 需要 ≥2 个已开 tab 才挂载（**未测** 2-tab 场景）。

**影响**：fix2 的「抽屉变真覆盖层」这一半**在真机上未生效/不可达**；关闭态的 N-3 位移与空壳已修复，但抽屉本体功能（覆盖层 + scrim + 抽屉行交互）**无法打开，故其内部交互回归（切 tab/关闭/重命名/设置/Help 行）全部未验到**。

---

## 6. 「~45s 交互延迟」是否还在

- 在步骤 ④ 恢复（不再空壳）之后，本轮**未观察到 ~45s 交互延迟**：开会话、点标签条、开关面板、收放键盘等轻交互均在 **~1–2s** 内响应，dump/截图无等待。
- 但本轮**未做长会话/重负载压测**，故只能判定「**空壳态的 45s 延迟未复现**」，**不宣称已彻底消除**。

---

## 7. 未闭环

| 项 | 状态 | 说明 |
|---|---|---|
| 移动垂直形态下**抽屉无法打开** | ❌ **未闭环 / 需回单** | §5；打开态所有断言（条 y、scrim、开↔关逐字比对、抽屉内交互）**未验到** |
| 抽屉打开态标签条 y | 未验到 | 依赖「抽屉可打开」 |
| 抽屉内部交互回归（切 tab/关 tab/长按重排/重命名/设置/Help 行/scrim 点击关闭） | 未验到 | 抽屉不可达 |
| 2 个及以上 tab 时抽屉是否可打开 | 未测 | §5 分支 ③ |
| 抽屉打开失败的根因定位 | 未闭环 | §5 只到「状态订阅/重父化」层面，未定位单行 |
| ~45s 延迟彻底消除 | 未验到 | 本轮仅轻交互未见复现；重负载未测 |
| 全量 JVM 单测 | 未跑 | 纪律仅真机复验；JVM 93 例由 Lead 侧提供 |

---

## 8. 证据文件索引（相对本报告路径）

- 环境/工具：`tools/capture.ps1`（dump+screencap+pull+节点解析）
- 步骤①`evidence/S1_default_horizontal.{dump.xml,png}`
- 步骤②`evidence/S2_vertical_desktopW.{dump.xml,png}`
- 步骤③`evidence/S3_wm_horizontal.{dump.xml,png}`
- 步骤④`evidence/S4_vertical_wm_KEY.{dump.xml,png}`、`evidence/bottom_band_all.txt`、`evidence/bottom_strip_labels.txt`
- 步骤⑤`evidence/S5clean_pre.dump.xml`（关）、`evidence/S5clean_post.{dump.xml,png}`（点后，逐字节相同）、
  `evidence/S5clean_strip.txt`、`evidence/tabstrip_clickables.txt`、`evidence/S5_control_palette.png`（对照）、
  `evidence/S5_kbdhidden.{dump.xml,png}`、`evidence/S5_drawer_open.{dump.xml,png}`、`evidence/S5_pre_sessionopen.{dump.xml,png}`
- 步骤⑥`evidence/S6_home_final.{dump.xml,png}`、`evidence/S6_final_default.{dump.xml,png}`

**测试员**：真机复验（mcode / adb emulator-5560）  
**日期**：2026-10-02
