# 真机复验报告 · 命令面板死区 · 第五轮（5554 / Pixel_9a）

- 轮次：**Round 5**（前四轮 5562 / 5558 / 5554 均**未判定**；根因已由 Lead 定位为卡死的 system ANR 对话框 + 宿主抢占）
- 设备：`emulator-5554` / AVD **Pixel_9a** / 全屏手机形态 / 1080x2424
- 执行方式：mcode + adb 直连。**未跑 gradle、未装机、未杀进程**（仅 `am force-stop` 本 app）；只操作 5554，未碰 127.0.0.1:5555；**未按 BACK(4)**；面板内**未按 ESC(111)**；截图全部走 `screencap -p` + `adb pull`
- 坐标来源：**全部运行期从 `uiautomator dump` 解析**，无硬编码
- 会话范围：只读现有会话，**未新建/未改动任何会话**（前缀 `E2ECHK5554pe` 仅用于本轮临时文件，已清理）

---

## 0. 总判定

> ## ✅ **PASS —— 命令面板"死区"在 Pixel_9a / v1.4.0 上无法复现。**
> A2 正向对照已过，B1 主判据点到底栏节点**有明确可观测结果**（命令真实执行），B2 遮罩点击**正常关闭**，B3 面板内命令**可执行**。
> 本轮**未出现**任何一路需要"三路交叉全无变化"才能判 FAIL 的情形，因此不触发 FAIL 判定。

---

## 1. 环境读数

| 项 | 实测值 |
|---|---|
| 设备序列号 | `emulator-5554`（`product:sdk_gphone16k_x86_64` / `model:sdk_gphone16k_x86_64`） |
| `adb emu avd name` | **Pixel_9a** ✅ 与 Lead 说明一致 |
| `sys.boot_completed` | `1` |
| `reverse --list` | `host-15 tcp:4095 tcp:4095`、`host-15 tcp:2222 tcp:22` ✅ 与 Lead 说明一致（复核通过） |
| 包名 | `com.xiufeigo.opencodeshell` |
| `versionName` | `1.4.0` |
| `lastUpdateTime` | **2026-10-02 16:18:08**（Lead 预期 ≈`2026-10-02 15:20:4x`，**实测为 16:18:08**，按要求记录实际值） |
| `firstInstallTime` | 2026-09-27 04:29:58 |
| 屏幕分辨率 | 1080 x 2424（dump 根节点 `bounds=[0,0][1080,2424]`） |
| 后端 `http://127.0.0.1:4095` | 可达，Basic `opencode:opencode` 认证通过（HTTP 200，返回 OpenCode SPA 入口页） |
| **本轮是否 reboot** | **否。** 开测前 `mCurrentFocus` 直接命中本 app 窗口，**无 `Application Not Responding` 对话框**，未触发 reboot 判据 |
| **冷启动首帧耗时** | **6412 ms** |
| 启动后 app PID | `4753`（全程未变，**未杀进程**） |

**冷启动原始读数**：`am start -W` → `LaunchState: COLD` / `TotalTime: 6412` / `WaitTime: 6413`；logcat `ActivityTaskManager: Displayed com.xiufeigo.opencodeshell/.OcActivity for user 0: +6s412ms`；`am start` 发出后 **6703 ms** 时 `mCurrentFocus` 命中 `OcActivity`。

> 注：logcat 出现两条 `Fatal signal 6 (SIGABRT)`，进程名为 `android.hardwar[e]`（传感器 HAL），**非本 app**，不构成 ANR 证据，也未触发本轮 reboot 条款。
> 冷启动 6.4s 明显优于 Lead 提供的"16s 有首帧"参考值。

---

## 2. A2 · 正向对照（必过项）—— ✅ **PASS**

**目的**：先证明 app 响应正常，把"死区"与"app 已死"彻底分开（A2 不过则后续一律 BLOCKED）。

- 起点：`am force-stop` 后 `mCurrentFocus` 落回 `nexuslauncher`（干净冷启动）
- 操作：点击底栏 `Settings`
- **坐标运行期解析**：
  - 文本节点 `text="Settings"` bounds `[132,2177][263,2218]`
  - 可点击行容器 `clickable="true" bounds="[53,2134][995,2219]"` → 中心 **(524, 2176)**

### 三路证据（全部为正向变化）

| 通道 | 前 | 后 | 结论 |
|---|---|---|---|
| 截图像素差分 | `A2_base.png` | `A2_after.png` | **326030 / 654480 = 49.82%** ✅ |
| `gfxinfo` 帧数 | Total frames rendered: **2** | **6** | +4 帧 ✅ |
| dump 节点集 | 36818 bytes（会话列表） | 27410 bytes（设置页） | ✅ 换页 |

### dump 语义确认（排除"像素噪声"）
`A2_after.xml` 出现真实设置页节点：`General` / `Appearance` / `Notifications` / `Shortcuts` / `Language` / `English` / `Auto-accept permissions` / `Show agent` / `Terminal placement` / `Choose where the terminal opens in sessions`。

**结论：A2 ✅ PASS。底栏点击 → 页面真实切换。Android 输入分发、Compose 命中测试、渲染管线全部健康。**

---

## 3. B1 · 主判据（命令面板底栏节点点击）—— ✅ **PASS**

### 3.1 打开命令面板
`adb -s emulator-5554 shell input keycombination 113 39`（CTRL+I）**一次成功**。

- 前：`B1_pre.png` / `B1_pre.xml`（36818 bytes，home 会话列表）
- 后：`B1_pal.png` / `B1_pal.xml`（13576 bytes，面板）
- 打开动作本身即有可观测变化：**PIXEL_DIFF = 339634 / 654480 = 51.89%** ✅

### 3.2 面板结构（从 dump 解析，非猜测）
面板卡片内 8 个 `clickable="true"` 的命令行容器（全部 `x∈[48,1032]`）：

| # | 行容器 bounds | 命令文本 | 文本 bounds |
|---|---|---|---|
| 1 | `[48,361][1032,487]` | Open settings | `[80,403][304,445]` |
| 2 | `[48,490][1032,616]` | New session | `[80,532][286,574]` |
| 3 | `[48,619][1032,745]` | New workspace | `[80,661][341,703]` |
| 4 | `[48,748][1032,874]` | Previous session | `[80,790][351,832]` |
| 5 | `[48,877][1032,1003]` | Next session | `[80,919][288,961]` |
| 6 | `[48,1006][1032,1132]` | Toggle terminal | `[80,1048][329,1090]` |
| 7 | `[48,1135][1032,1261]` | Toggle review panel | `[80,1177][404,1219]` |
| **8（底栏 / 最末行）** | **`[48,1264][1032,1390]`** | **Open file** | `[80,1306][226,1348]` |

- 可滚动列表容器：`[48,284][1032,1380]`
- 遮罩/overlay 根节点：`[0,141][1080,2284]`，**`clickable="false"`**（该层未单独暴露可点击遮罩节点，遮罩手势由 `DialogHostKt$DialogLayer$3$1$2$1` 处理，见 B2）
- 键盘占据 y ≳ 1549，卡片底部 y = 1390，二者之间为**遮罩条**

### 3.3 操作
从 dump 解析**最末行（底栏节点）中心** → **`input tap 540 1327`**（`(48+1032)/2=540`，`(1264+1390)/2=1327`）

### 3.4 三路交叉证据 —— 全部为**有**变化

| 通道 | 结果 |
|---|---|
| ① 截图像素差分 | `B1_pre2.png` vs `B1_post.png` = **191966 / 654480 = 29.331%** ✅ |
| ② logcat 输入处理 | ✅ 命中**点击分发栈**：`DialogHost.kt:163 close` ← `AppKt.PaletteDialogLayer$lambda$243$lambda$242(App.kt:2913)` ← `CommandPaletteKt$CommandPalette$6$2$3$1$3$1$1(CommandPalette.kt:341)` ← `ClickableNode$clickPointerInput$3.invoke(Clickable.kt:633)` ← `TapGestureDetectorKt$detectTapAndPress` ← `AndroidComposeView.dispatchTouchEvent` —— **指针事件被 palette 的 ClickableNode 完整接收并派发** |
| ③ `gfxinfo` 帧数 | reset 后 **Total frames rendered: 20**（点击前 0）✅ 确有重绘 |
| （附加）dump | 13576 bytes（面板）→ 5595 bytes，节点集出现 **`No results found`** `[407,405][674,446]` |

**语义确认**：`Open file` 命令**被真实执行**——面板关闭后进入了文件选择/搜索层（因当前目录无可选文件而显示 `No results found`），而非仅仅"面板消失"。

**B1 判定：✅ PASS。有明确可观测结果，命令真实执行。底栏（最末行）节点不是死区。**

> 依照方法论红线：这里**不是**拿"dump 签名变化"单独当证据，而是三路交叉（像素 29.3% + Compose 点击栈 + 20 帧）一致为正，并有 `Open file` 的语义级佐证。

---

## 4. B2 · 阳性对照（点卡片外遮罩区 → 面板应关闭）—— ✅ **PASS**

> 首次执行时面板未真正打开（上一轮 B1 遗留的文件选择层仍在），那次遮罩点击关掉的是文件层。**为严格起见，本节结果取自对命令面板本体的重测。**

- 打开面板：`keycombination 113 39` → `B2s_pal.xml` = 13576 bytes，`Commands=true` ✅ 确认面板确实打开
- **遮罩点选坐标**（运行期从 dump 推导）：卡片底部 `1390` < 遮罩点 `1465` < 键盘顶部 `≈1549` → **`input tap 540 1465`**

### 三路证据

| 通道 | 结果 |
|---|---|
| ① 截图像素差分 | `B2s_pal.png` vs `B2s_post.png` = **321542 / 654480 = 49.129%** ✅ |
| ② logcat 输入处理 | ✅ `DialogHost.kt:163 close` ← `DialogHostKt$DialogLayer$3$1$2$1(DialogHost.kt:401)` ← `TapGestureDetectorKt$detectTapGestures` —— 遮罩层的手势被接收 |
| ③ `gfxinfo` 帧数 | Total frames rendered: **22** ✅ |
| （附加）dump | 13576 bytes（面板，`Commands=true`）→ **36818 bytes（home，`Search sessions=true`，`Commands=false`）** ✅ 面板已关闭 |

**B2 判定：✅ PASS。点卡片外遮罩区，面板正常关闭。**

---

## 5. B3 · 面板内正路径（点一条命令 → 断言被执行）—— ✅ **PASS**

- 打开面板 → `B3r_pal.xml` = 13576 bytes，`Commands=true` ✅
- **目标坐标**（运行期从 dump 解析第 1 行容器 `[48,361][1032,487]`）→ 中心 **`input tap 540 424`**（命令 = `Open settings`）
- 该坐标经自校验（`1400 < y < 2400`）后才下发，避免越界误判

### 三路证据

| 通道 | 结果 |
|---|---|
| ① 截图像素差分 | `B3r_pre.png` vs `B3r_post.png` = **378477 / 654480 = 57.829%** ✅ |
| ② logcat 输入处理 | ✅ `DialogHost.kt:163 layer close`（面板正常让位） |
| ③ `gfxinfo` 帧数 | Total frames rendered: **8** ✅ |
| （附加）dump | 13576 bytes（面板）→ **27411 bytes**，节点集出现完整设置页 |

**语义确认（与 A2 独立的第二条正路径）**：设置页真实渲染 —— `General` / `Appearance` / `Notifications` / `Shortcuts` / `Language` / `English` / `Auto-accept permissions` / `Show agent` / `Terminal placement` / `Choose where the terminal opens in sessions`。

**B3 判定：✅ PASS。面板内命令可执行，且执行结果可观测。**

---

## 6. 收尾 / 残留检查

| 项 | 结果 |
|---|---|
| `/sdcard` 本轮临时文件 | 清理前 **30** 个（`E2ECHK5554pe_*.png` / `*.xml`） |
| 清理命令 | `adb shell rm -f /sdcard/E2ECHK5554pe*.png /sdcard/E2ECHK5554pe*.xml` |
| 清理后残留 | **0** ✅ `residual count=0` |
| `/sdcard` 总条目 | 1036（与本轮无关的历史文件未触碰） |
| app 进程 | PID `4753` 全程未变，**未杀进程** ✅ |
| 最终焦点 | `Window{c2831d6 u0 com.xiufeigo.opencodeshell/.OcActivity}`（仍在 app 内，无 ANR 对话框） |
| 本地产物 | 截图 `shots\*.png`、dump `*.xml` 已存 `research\v97_verify\5554_v98\`（供复核，非设备残留） |

---

## 7. 未闭环 / 遗留项

1. **`lastUpdateTime` 与 Lead 预期不一致**：实测 `2026-10-02 16:18:08`，Lead 预期 ≈`15:20:4x`。本轮所有结论仅对**当前实际安装的这一版（versionName 1.4.0 / lastUpdateTime 16:18:08）**成立；若 Lead 所指"v98k"是另一构建，需确认 5554 上装的就是目标构建。
2. **冷启动 6.4s ≠ Lead 提供的 16s 参考**：不影响判定（A2 已过），但说明 Lead 的 16s 可能是另一测量口径（首帧 vs 完全可用），值得对齐。
3. **只覆盖了最末行（Open file）作为"底栏节点"**：本轮按"命令面板卡片内最底行"理解并验证通过。若 Lead 所指"底栏"是**面板打开时其下方露出的 app 底栏（Settings/Help 那一行，位于键盘已顶出的压缩布局中）**，该区域在本轮布局下**被软键盘遮挡**（键盘顶 y≈1549 覆盖了 app 底栏原位置 2134–2345），无法在不关面板的前提下直接点击，**此解释分支本轮未闭环**，需 Lead 确认口径。
4. **遮罩层无独立可点击节点**：overlay `[0,141][1080,2284]` 在 dump 中 `clickable="false"`，遮罩手势由 `DialogHostKt$DialogLayer$3$1$2$1` 处理且工作正常（B2 已验证）。若后续要做自动化断言，建议按此回调路径而非节点可点击性来定位。
5. **未做多点/边界扫描**：本轮为定点验证（底栏 1 点 + 遮罩 1 点 + 命令 1 点），未对 8 个命令行做全量点击回归。
6. **B3 首次执行的 0% 像素差已作废**：原因是本轮脚本 bounds 解析缺陷（`][` 拼接导致 y 算出 `1805516` 越界），属**测试工具缺陷、非被测缺陷**；已用修正解析重测得到 57.829%，报告仅采用修正后数据。此处记录以免后续误读旧截图 `B3_pre/B3_post`。
7. **单一设备单一分辨率**：结论仅覆盖 Pixel_9a / 1080x2424 / 软键盘弹起态，未覆盖横屏、分屏、硬件键盘态。

---

## 8. 证据文件清单

均位于 `research\v97_verify\5554_v98\`：

- 环境/启动：`A2_base.png` / `A2_base.xml`
- A2 正向对照：`A2_after.png` / `A2_after.xml`
- B1 主判据：`B1_pre.xml`、`B1_pre2.png`、`B1_pal.png`、`B1_pal.xml`、`B1_post.png`、`B1_post.xml`
- B2 阳性对照：`B2_home.xml`（首测，对象为遗留文件层）、`B2s_pal.xml`、`B2s_pal.png`、`B2s_pre.png`、`B2s_post.xml`、`B2s_post.png`（**严格重测，采用**）
- B3 面板内命令：`B3r_pal.xml`、`B3r_pre.png`、`B3r_post.xml`、`B3r_post.png`（**采用**）；`B3_pre.png` / `B3_post.png`（首测作废，坐标解析缺陷）
