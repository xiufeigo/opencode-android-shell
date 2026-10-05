# FORMFACTOR_V11 — 形态回归（正确口令重做）

**轮次**：V11 ｜ **日期**：2026-10-05 ｜ **存在理由**：V9 的形态扫描在**口令错（后端 401）**状态下取的，数据区永远等不到数据 ⇒ 那批形态结论不可用
**设备**：`emulator-6230`（AVD `OC_Deliver_R2`，镜像 `android-35;google_apis_playstore_tablet` + `pixel_tablet`）
**宿主端口**：6230（偶数、≥6230；起前实测 6230/6232/6234 全 FREE）
**物理面板**：`2560x1600` @ `320`
**后端**：`127.0.0.1:4095` 由 Lead 起好（收尾仍 `LISTEN pid=3748`），**我全程未起停**，只做 `adb reverse`
**被测包**：`research/deliver/opencode-shell-web-release-nor8.apk`
（实测 **3,975,389 B**，md5 **`76ca064b7de9246ee446f765a9198b37`**，与派单一致）
**包名**：`com.xiufeigo.opencodeshell.web.debug`（`aapt2 dump packagename` 读回；release 变体带 `.debug` 后缀，见 `webapp/build.gradle.kts:55`）

---

## 〇、前置断言（每一格测量前都必须过 —— 本轮存在理由）

派单要求"每一格都要先证明**已鉴权成功、数据真的到了**"。本轮把它写成了**脚本里的硬门禁**（`_v11_sweep.ps1`），
不满足就判 `PRECONDITION_VALID=False`，该格结论作废。四条同时成立才算过：

| # | 断言 | 判据 | 为什么选它 |
|---|---|---|---|
| 1 | **已鉴权** | 本次冷启后 logcat 出现 `F5 probe OK code=200, loading official UI` | 直接证明 `/api/info` 用**正确口令**返回 200，而不是 401 |
| 2 | **数据真的到了** | logcat 出现 `GET http://127.0.0.1:4095/api/session?limit=5000&order=desc&parentID=null` | 会话列表接口真的向后端取回了数据；骨架屏状态下**不会**有这一行 |
| 3 | **没有认证失败残留** | 本次冷启后 `AUTH FAILED` 计数 = **0** | 排除"部分 401 混进来"的污染 |
| 4 | **不是空白屏** | 截图 inkPct（≠白像素占比）≥ 1.5% | 排除"白屏/骨架屏也算测到了" |

**9/9 格全部通过**（`CELL_VERDICT … PRECONDITION_VALID=True`，见 §四与 `out_v11/sweep_raw.txt`）。
播种口令时的鉴权原始行（`out_v11/seed.log`）：

```
10-05 00:08:03.101  3131  3131 I OcWebHost: no password in SecretStore, asking
10-05 00:08:15.773  3131  3131 I OcWebHost: credentials stored backend=http://127.0.0.1:4095 passwordPresent=true
10-05 00:08:15.773  3131  3131 I OcWebHost: F5 probe before load path=/api/info
10-05 00:08:15.865  3131  3131 I OcWebHost: F5 probe OK code=200, loading official UI
10-05 00:08:15.867  3131  3131 I OcWebHost: home=http://127.0.0.1:38819/ backend=http://127.0.0.1:4095 passwordPresent=true
```

> ⚠️ 播种保真（沿用 F5 教训）：本镜像 `input text` 会丢字符。本轮**逐字符**发 8 次 `input text`
> （每次一个 adb 调用 + 220 ms 间隔）才播种成功 → 这正是 V9 会误判成"后端拒绝"的坑，本轮未踩。

---

## 一、⚠️ 方法论重大修正：这份包**没有 CDP**，也没有 uiautomator 文本

派单要求"CDP 关键选择器与节点数"。本轮**实测推翻了 CDP 可行的前提**，如实记录并改用其它证据通道：

**① CDP 通道不存在。** `dumpsys`/proc 里没有任何 devtools socket：

```
DEVTOOLS_SOCKETS=            <- 空
```

代码侧已定位原因（只读核对，未改任何代码）：全仓 `setWebContentsDebuggingEnabled` **只有一处**，
且在 `:app` 模块、用 `BuildConfig.DEBUG` 做参数 —— `app/src/main/java/com/xiufeigo/opencodeshell/MainActivity.kt:466`：

```kotlin
private fun configureWebView() {
    WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
```

而本包是 **`:webapp` 模块**，`webapp/src/**` **零调用**（`WebHostActivity` 从不开启）。
⇒ **`webapp/src/main/java/.../WebHostActivity.kt` 里没有这一行，是缺通道，不是本轮测法错。**
（注：`research/**` 里若干文档写"全仓零调用"是**过期**的 —— `:app` 那处已存在。）

**② uiautomator 读不到 WebView 文本**（V8 已记录，本轮复现）：

```
XML_LEN=788   NODE_COUNT=2   NONEMPTY_TEXT_COUNT=0
CLS=android.webkit.WebView BOUNDS=[0,0][2560,1600]
MARK[Projects]=False  MARK[Settings]=False  MARK[DRAFT_OK]=False
```

⇒ 只看得到 WebView 这一个壳节点，**页内文本/选择器/节点数一个都读不到**。

**③ 因此本轮的"数据已到"由三条独立证据共同支撑**（都不是 CDP）：
- **后端侧**：`OcWebHost/Proxy` 的 10–11 条 `/api/**` GET（含 `session?limit=5000`）；
- **像素侧**：截图 inkPct（每格都 ≥1.6%，V9 那批是 99%+ 白）；
- **人工判读**：我逐格**看了截图**，能读出真实会话标题（见下）。

**④ 另有一处命令纠正**：派单写的 `dumpsys window insets` **是无效子命令**，实测返回
`Bad window command, or no windows match: insets`。原始行实际在 **`dumpsys window`** / **`dumpsys window displays`**。
本报告所有 `type=ime frame=` 行都取自 `dumpsys window`。

---

## 二、逐格表（方法 + 原始输出 + 截图 + 判定）

**统一方法**（每格都一样，`_v11_sweep.ps1`）：
1. `wm size/density` 覆盖 → **等 4 s** 收敛 → **`wm` 读回**；
2. `am force-stop` → 静置 3 s → `am start`（**冷启**，`LaunchState: COLD`）；
3. **轮询前置断言**（最长 40 s）到"已鉴权 + 数据已到"才继续；
4. `screencap` 截图 + 像素分析（inkPct / 左栏墨量 / 底栏墨量）；
5. 点聚焦页内 `Search sessions` 输入框 → **先断言 `mInputShown=true`** → 抄 `type=ime frame=` 原始行 → 算重叠；
6. 算 ime 截图 → 下一格。

> 几何高度一律 ≤ 1600 px：**F4/R2/V6 记录过的"静默退出"形态与几何高度 > 物理 1600 一致**，本轮主动规避（本轮 9 格**无一格**静默退出）。

| 几何 | `wm` 读回 | 前置断言 | inkPct / 左栏 / 底栏 | IME frame | 重叠 | 截图 | 判定 |
|---|---|---|---|---|---|---|---|
| **手机 360dp**<br>`720x1280@320` | `Override size: 720x1280`<br>`Physical density: 320` | auth=✅ data=✅<br>401=**0** apiGET=10 | 6.10 / 6.67 / 11.59 | `[0,1184][720,1280]` | **0 px** | `phone360dp.png`<br>118,125 B | **移动分支** ✅ |
| **手机 412dp**<br>`824x1280@320` | `Override size: 824x1280` | auth=✅ data=✅<br>401=**0** | 4.37 / 7.03 / 6.49 | `[0,1184][824,1280]` | **0 px** | `phone412dp.png` | **移动分支** ✅ |
| **折叠 442dp**<br>`884x1280@320` | `Override size: 884x1280` | auth=✅ data=✅<br>401=**0** | 4.09 / 7.34 / 6.21 | `[0,1184][884,1280]` | **0 px** | `fold442dp.png` | **移动分支** ✅ |
| **折叠 708dp**<br>`1416x1280@320` | `Override size: 1416x1280` | auth=✅ data=✅<br>401=**0** | 2.87 / 7.88 / 3.49 | `[0,1184][1416,1280]` | **0 px** | `fold708dp.png` | **移动分支** ✅ |
| **边界下档 700dp**<br>`1400x1280@320` | `Override size: 1400x1280` | auth=✅ data=✅<br>401=**0** | 2.66 / 7.89 / 4.70 | `[0,1184][1400,1280]` | **0 px** | `bound700dp.png` | **移动分支** ✅ |
| **边界上档 820dp**<br>`1640x1280@320` | `Override size: 1640x1280` | auth=✅ data=✅<br>401=**0** | 1.99 / 3.98 / 3.04 | `[0,1184][1640,1280]` | **0 px** | `bound820dp.png` | **桌面分支** ✅ |
| **探针 1000dp**<br>`2000x1280@320` | `Override size: 2000x1280` | auth=✅ data=✅<br>401=**0** | 1.63 / 3.50 / 2.50 | `[0,1184][2000,1280]` | **0 px** | `probe1000dp.png` | 桌面分支、**无左栏** |
| **探针 1024dp**<br>`2048x1280@320` | `Override size: 2048x1280` | auth=✅ data=✅<br>401=**0** | 2.24 / 1.31 / 3.47 | `[0,1184][2048,1280]` | **0 px** | `probe1024dp.png` | 桌面分支、**左栏出现** |
| **平板 1366×768**<br>`1366x768@160` | `Override size: 1366x768`<br>`Override density: 160` | auth=✅ data=✅<br>401=**0** apiGET=11 | 2.30 / 0.87 / 4.71 | `[0,720][1366,768]` | **0 px** | `tablet1366x768.png`<br>83,303 B | 桌面分支、**左栏真存在** |

### 逐格关键选择器/可见元素（人工判读截图，**非 CDP**）

| 几何 | 标题栏 | Projects 呈现 | Add project | Settings / Help | 移动导航栏 |
|---|---|---|---|---|---|
| **360dp** | **Home** | **All projects**（下拉，带 `⌄`） | ❌ 无 | ❌ **都没有** | **无底部栏**；导航靠标题栏图标 + 抽屉 |
| **412dp** | Home | All projects（下拉） | ❌ | ❌ 都没有 | 无底部栏 |
| **442dp** | Home | All projects（下拉） | ❌ | ❌ 都没有 | 无底部栏 |
| **708dp** | Home | All projects（下拉） | ❌ | ❌ 都没有 | 无底部栏 |
| **700dp** | **Home** | **All projects**（下拉） | ❌ 无 | ❌ **都没有** | 无底部栏 |
| **820dp** | 无"Home"字样 | **Projects**（**区块标题**） | ✅ 右上角图标 | ✅ **底部栏**：左 Settings / 右 Help | — |
| **1000dp** | — | Projects（区块标题） | ✅ 右上 | ✅ 底部栏 Settings / Help | — |
| **1024dp** | — | Projects（**左栏**） | ✅ 左栏 | ✅ **移进左栏**（左栏底部） | — |
| **1366×768** | — | Projects（**左栏**） | ✅ 左栏 | ✅ **左栏内** | — |

**每格都读到了真实会话**（数据到达的人工证据，随宽度换行/截断而不同）：
`Counting from 1 to 4000, one number per line / opencode-android-shell`、`Friendly greeting / DSH 临时会话`、
`android-e2e / System32`、`DRAFT_OK / chenjieyan_test`、`Count from 1 to 999…`、
`New session / DSH 临时会话`、`Android 启动时手动选择 server 连接 / DSH-Remote`、
`Plugin activation failure waiting for conversationEvents / DSH-Desktop`、
`排查 dsh 崩溃与插件缺失问题 / DSH-Remote`、`DSH插件致后端监听地址报错修复 / DSH-Desktop`…
⇒ **不是骨架屏**（V9 的 99.2–99.8% 白屏与此形成直接对照）。

---

## 三、IME 重叠量（逐格）

**每格都先断言 `mInputShown=true` 才读数**，9/9 通过：
`IME_ASSERT mInputShown=true => True [mInputShown=true]`，`mServedView=android.webkit.WebView{...}`。

| 几何 | `type=ime frame=` 原始行（`dumpsys window`） | `contentTopInsets` | `windowBoundsTop` | contentBottom | **重叠(窗口)** | **重叠(输入框)** |
|---|---|---|---|---|---|---|
| 360dp | `InsetsSource id=3 type=ime frame=[0,1184][720,1280] visibleFrame=[0,1184][720,1280] visible=true flags= sideHint=BOTTOM boundingRects=null` | 1136 | 48 | 1184 | **0 px** | **0 px** |
| 412dp | `…type=ime frame=[0,1184][824,1280]…` | 1136 | 48 | 1184 | **0 px** | **0 px** |
| 442dp | `…type=ime frame=[0,1184][884,1280]…` | 1136 | 48 | 1184 | **0 px** | **0 px** |
| 708dp | `…type=ime frame=[0,1184][1416,1280]…` | 1136 | 48 | 1184 | **0 px** | **0 px** |
| 700dp | `…type=ime frame=[0,1184][1400,1280]…` | 1136 | 48 | 1184 | **0 px** | **0 px** |
| 820dp | `…type=ime frame=[0,1184][1640,1280]…` | 1136 | 48 | 1184 | **0 px** | **0 px** |
| 1000dp | `…type=ime frame=[0,1184][2000,1280]…` | 1136 | 48 | 1184 | **0 px** | **0 px** |
| 1024dp | `…type=ime frame=[0,1184][2048,1280]…` | 1136 | 48 | 1184 | **0 px** | **0 px** |
| 1366×768 | `InsetsSource id=3 type=ime frame=[0,720][1366,768] visibleFrame=[0,720][1366,768] visible=true flags= sideHint=BOTTOM boundingRects=null` | 696 | 24 | 720 | **0 px** | **0 px** |

**重叠量算法（两个口径都报）**：
- **窗口口径** = `max(0, contentBottom − imeTop)`，其中 `contentBottom = windowBoundsTop + contentTopInsets`。
  例（360dp）：`48 + 1136 = 1184`，`imeTop = 1184` ⇒ **0 px** ⇒ 窗口已**完整缩到 IME 之上**（`adjustResize` 正常）。
- **输入框口径** = `max(0, 聚焦的 Search 输入框底边 − imeTop)`，底边由 **IME 弹起后的截图**重定位得到。
  例（1366×768）：重定位底边 `y=97`（+6 容差 = 103），`imeTop=720` ⇒ **0 px**。
  该口径证明**不只是窗口、连被聚焦的输入框本身也没被 IME 压住**。

**9/9 格重叠 = 0 px，符合预期，无一格需要写"测不到"。**

---

## 四、1366×768 有无左栏 & 第二切换点在哪

### 4.1 1366×768 **真多出左栏**（有证据）

截图 `out_v11/tablet1366x768.png`（83,303 B）上**肉眼可读**：
左侧一列自上而下是 **Projects → Add project → Settings → Help**，
会话列表（`Counting from 1 to 4000…` / `Friendly greeting` / `android-e2e` / `DRAFT_OK` / …）
整体**右移**到右栏，`Settings`/`Help` **不在底部**。
像素侧佐证：左栏带 inkPct **0.87**、底栏带 4.71（底栏墨量来自系统 Taskbar 导航条，不是 Settings/Help）。
同批 2560×1600 静置态截图（`seed.png`）也一致。

### 4.2 第二切换点：**存在，就在 1024**（V9 只说"在 820–1366 之间"，本轮把它收窄到 24 dp）

派单只要求"到底有没有、大概在哪"，我加测了 **1000dp 与 1024dp 两个探针格**（同样过了前置断言）：

| 探针 | 宽度 | Projects 在哪 | Settings/Help 在哪 | 左栏 |
|---|---|---|---|---|
| `probe1000dp` | 1000 CSS px | 顶部**区块**（Projects + Add project 在内容区上方） | **底部栏**（左 Settings / 右 Help） | ❌ **无** |
| `probe1024dp` | 1024 CSS px | **左侧独立栏**（Projects + Add project） | **移进左栏底部** | ✅ **有** |

⇒ 切换发生在 **(1000, 1024]**，与上游 Tailwind 断点 `lg = 64rem = 1024px` **完全吻合**。

代码侧交叉印证（只读核对，未改动）：
`_upstream/packages/ui/src/styles/theme.css:25-28` 的 Tailwind 刻度
`sm 40rem=640 / md 48rem=768 / lg 64rem=1024 / xl 80rem=1280`；
`_upstream/packages/app/.../home/route.tsx:43`
`max-md:grid-rows-[…] lg:grid-cols-[280px_minmax(0,720px)]`（**≥1024 才有 280px 左栏**）、
`:52` `hidden … md:block lg:hidden`（**768–1023 才把 Settings/Help 放底部栏**）、
`home/projects/view.tsx:290` `mb-8 mt-4 hidden shrink-0 lg:flex`。
移动/桌面主切换则是 `767/768` 一对（`shell/shell.tsx:26` `(max-width: 767px)` vs `session/…` 的 `(min-width: 768px)`）。

### 4.3 本轮测到的完整形态谱（3 档）

| 视口 | 分支 | 形态 |
|---|---|---|
| < 768 | 移动 | 标题栏 `Home` + `All projects` 下拉；**无 Add project 按钮**、**无 Settings/Help**、**无底部栏** |
| 768 – 1023 | 桌面 | `Projects` 区块 + `Add project`；**底部栏** Settings/Help；**无左栏** |
| ≥ 1024 | 桌面+栏 | **280px 左栏**：Projects / Add project / Settings / Help 全在左栏；**无底部栏** |

---

## 五、与旧结论的关系

**本轮每格 = 这份包（`76ca064b…`，3,975,389 B）、这台设备（`emulator-6230`）、这一轮（2026-10-05）、且已鉴权（`probe OK code=200`）。**

### 5.1 明确作废

| # | 旧结论 | 处置 | 依据 |
|---|---|---|---|
| 1 | **V9 的全部形态结论**（6 格 `uiAvailableMs≈10.7–11.0s`、`renderWhitePct=99.2–99.8`、`proxyGET=0`、`dialogLikely=true`） | **作废** | 那些格子 `proxyGET=0` / `AUTH FAILED code=401` ⇒ 从未拿到数据，无法判断任何断点行为。本轮 9/9 格 `apiGET≥10`、`401=0`、inkPct 1.63–6.10。 |
| 2 | **V9"第二切换点在 820–1366dp 之间"这条推论** | **作废，但由本轮实测重新给出**：第二切换点 = **1024**（实测区间 (1000,1024]） | V9 无一格拿到数据，支撑不了该推论；本轮用 1000dp/1024dp 两格夹逼 + 上游 `lg=1024` 佐证。 |
| 3 | V9 那张"360dp 停在骨架屏"的画面 **作为形态证据** | **作废** | 同 1。本轮 360dp 读到 10+ 条真实会话，inkPct 6.10。 |

> 与 V10（`PHONE360_INVESTIGATION.md`）**不冲突**：V10 测的是 360 vs 412 的**冷启到达时间**，结论"360dp 不慢"本轮同向印证（两格都正常渲染出数据）。

### 5.2 只作参照、不混轴

- `FORMFACTORS_F4.md`（`research/env/`）结论来自**更早的构建**，**只作参照**，不与本轮同轴比较。
  它同样记录过"无 CDP 只能靠截图"的方法论限制，与本轮 §一 一致。

### 5.3 本轮新增（可复用的方法论）

1. **任何视口对比都必须先过"已鉴权 + 数据已到"门禁**，否则结论必被污染（V9 的教训，本轮脚本已硬编码）。
2. **`:webapp` 包没有 CDP**（`:app` 有、`:webapp` 无）⇒ 想用 CDP 读 DOM，必须先在 `WebHostActivity` 加 `setWebContentsDebuggingEnabled`（**属改代码，超本轮权限**）。
3. `dumpsys window insets` **不是有效子命令**，`type=ime frame=` 要从 `dumpsys window` 取。
4. **几何高度 > 物理 1600 px 有静默退出风险**（F4/R2/V6 反复记录）；本轮全部 ≤1600，9 格零静默退出。

---

## 六、收尾复核（原始输出，`out_v11/closeout.txt`）

```
=== V11 CLOSEOUT 2026-10-05T08:15:38 ===
--- 关闭前确认归属（housekeeping 规则 2：按 AVD 名判定）---
MINE pid=51464 avd=OC_Deliver_R2 port=6230 MINE=True
MINE pid=47952 avd=OC_Deliver_R2 port=6230 MINE=True
MINE_COUNT=2
4095_BEFORE: LISTEN pid=3748 (Lead's; never touched by me)
--- wm size/density reset ---
RESET_SIZE:
RESET_DENSITY:
RESET_READBACK_SIZE: Physical size: 2560x1600            <- Override 行已消失
RESET_READBACK_DENSITY: Physical density: 320           <- Override 行已消失
--- adb reverse 清理（仅 tcp:4095）---
REVERSE_LIST_BEFORE: host-16 tcp:4095 tcp:4095
REVERSE_REMOVE:
REVERSE_LIST_AFTER:                                        <- 空
--- emu kill（仅 emulator-6230，按 serial）---
EMU_KILL: OK: killing emulator, bye bye | OK
--- 复核（kill 后 25s）---
emulator/qemu proc count = 0
OC_*/Medium_Desktop_B residual = 0
port 6230 : FREE
port 6232 : FREE
port 6234 : FREE
adb devices: List of devices attached                      <- 空
4095_AFTER: LISTEN pid=3748  <- same pid as before = backend never started/stopped by me
gradle lock exists = False
=== 未执行 adb kill-server / 未用 -a / 未用通配符 kill / 只按 serial 关 ===
=== CLOSEOUT DONE ===
```

**纪律声明**：
- ✅ 只 kill 了**我自己起的** `emulator-6230`（AVD `OC_Deliver_R2`，关前按 AVD 名+端口确认 `MINE=True`）
- ✅ **未**执行 `adb kill-server`、**未**用 `-a`、**未**用通配符 kill、**只按 serial** 关
- ✅ 用户的 `MCRemote_*` / `DSH_*` / `T7x_*` 与任何来源不明实例**一个都没碰**
  （开轮时 `adb devices` 为空、全机 emulator/qemu 进程数 0 ⇒ 也没有可被波及的用户设备）
- ✅ `4095` 后端**全程未起停**（收尾仍是**同一个** `pid=3748`）
- ✅ **`wm size/density` 已 reset 并复核**（无 Override 行）
- ✅ `adb reverse tcp:4095` **已清理**（`REVERSE_LIST_AFTER` 为空）
- ⚠️ **故意保留**：① `research/perf/_v11_*.ps1`（采集/播种/收尾脚本，可复用于后续形态轮）——**原因**：重建成本高于收益，
  且 §5.3 的方法论修正需要它们才能复现；② `OC_Deliver_R2` AVD **定义未删** —— **原因**：长期资产，
  R2/G2/P6/F4/V6/V7/F5/V10 等轮次共用，只启动/关闭不属于"收尾泄漏"。
- ⚠️ 收尾时全机 emulator/qemu 进程数 **0**（含"瞬时拆卸辅助进程"也已自然退出，本轮**未**出现 V6/V10 记录的 `process count = 2` 瞬时残留）。

---

## 七、我碰过的 serial

- **`emulator-6230`** —— 本轮**唯一**碰过的 serial（起 → 播种 → 9 格扫描 → reset → 关）。
- **全程只有这一个**。未接触任何其它设备或 serial（开轮 `adb devices` 为空，收尾复核亦为空）。

---

## 八、未闭环（明确不装作"通过"）

1. **没有 CDP 节点数/选择器。** 本包 `:webapp` 无 `setWebContentsDebuggingEnabled` ⇒
   `nodes` 数、`data-slot`/`data-action` 选择器命中**一个都没取到**（不是"通过"，是**通道不存在**）。
   本报告的形态判定改由"像素 + 人工读图 + 后端日志"三条证据支撑；**若要 CDP 数字，需先改代码加开关**（超本轮权限，未动）。
2. **未做 DOM 级断言。** 由于 ②，本轮的"Projects/Add project/Settings/Help 有无"是**截图人工判读**，
   **不是** `querySelector` 命中。结论可靠但口径与派单要求的 CDP 口径不同，已如实标注。
3. **横屏/多窗口/折叠真实展开态未测**：折叠档（442/708）是用 `wm` 覆盖模拟**宽度**，
   **不是**真机的折叠态切换（无 `VirtualDevice`/半折 posture）。
4. **"移动导航栏是否存在"的口径**：本轮在 ≤708dp 各格**未见任何底部导航栏**（既无 Settings/Help，也无 tab 栏）；
   导航走标题栏 + 抽屉。这与上游 `shell.tsx:26,32-33` 的 `mobile()` 分支一致，但**未做抽屉展开态的逐格截图**。
5. **IME 只测了页内 `Search sessions` 一个输入框**。会话消息输入框（左侧 composer）未测其 IME 重叠
   —— 因为本轮未进入任何会话详情页，**该口径未闭环**。
6. **单次采样**：每格只冷启 1 次（未重复取中位数），inkPct 波动未评估。
7. **后端负载未受控**：`127.0.0.1:4095` 上有其它会话，响应时间受共享影响；本轮不涉及时间对比结论。
8. **1 格几何与 V9 不同**：412dp 档本轮用 `824x1280@320`（高 1280），
   而 V9 的 412dp 未单列、V10 用 `824x1640@320`（高 1640 > 物理 1600）。本轮为规避静默退出主动压到 1280，
   ⇒ **宽度相同、高度不同**，与 V10 的 412dp **不严格同轴**（仅形态分支结论可比）。
9. **`webapp/build.gradle.kts:55` 的 release 带 `.debug` 后缀**这一"命名反直觉"事实，是本包能被
   `com.xiufeigo.opencodeshell.web.debug` 安装/启动的原因；若后续出 `.apk` 名字不带 `-nor8` 的变体，需重新核对包名。

---

## 九、证据文件

| 文件 | 内容 |
|---|---|
| `research/perf/out_v11/sweep_raw.txt` | **主证据**：9 格逐格 `wm` 读回 / 前置断言 / API GET / 像素 / IME 原始行 / 重叠 / 判定 |
| `research/perf/out_v11/seed.log` | 播种与鉴权原始日志（`probe OK code=200`、10 条 API GET） |
| `research/perf/out_v11/boot.log` | 起机与起前环境记录（端口 FREE、4095 pid、零残留） |
| `research/perf/out_v11/closeout.txt` | 收尾复核原始输出 |
| `research/perf/out_v11/formfactor_v11.json` | 机器可读结果（9 格） |
| `research/perf/out_v11/{phone360dp,phone412dp,fold442dp,fold708dp,bound700dp,bound820dp,probe1000dp,probe1024dp,tablet1366x768}.png` | 9 格终态截图（真实会话数据） |
| `research/perf/out_v11/*_ime.png` | 9 格 IME 弹起态截图 |
| `research/perf/_v11_{boot,seed,probe,sweep,closeout}.ps1` | 本轮脚本（含前置断言门禁，可复用） |
