# FIX_F18_PHONE —— 手机形态两条专属缺陷（顶部 inset / BACK 误退）

> 执行日期：2026-10-05（硬期限 2026-10-07 之前 ✅）
> 依据证据：`research/web/E2E_PHONE_V13.md`（缺陷 D1/D2 = 顶部重叠；缺陷 D4 = BACK 误退）
> 改动范围：`webapp/**`（`oc-host.js` + `WebHostActivity.kt` + `InsetsHost.kt` + 新纯函数 `PhoneFormDefect.kt` + 新测试 `PhoneFormDefectTest.kt` + `build.gradle.kts` 输入声明）
> **未碰**：`app/**`、`_upstream/**`、`.github/**`

## 0. 一句话结论

**两条都修好了，且都在真机上验证到"按下 BACK 那一刻"的读数。**
缺陷 A 不是"页面没吃到 top inset"这么单一 —— 是**宿主侧投递丢失 + 页面侧不消费**两处各断一半；
缺陷 B 是宿主的 BACK 顺序写反了（`canGoBack()` 排在页面 `onBack()` **前面**），
而上游 SPA 用 History API 站内导航 ⇒ 进会话时已压了一条历史 ⇒ `canGoBack()==true` ⇒ 面板开着也直接退会话。
**平板未搞坏**（1280×800 实测 `overlapPx=0`、布局未被压塌）。

| 判据 | 改前（实测） | 改后（实测） |
|---|---|---|
| `--oc-safe-top` | **空串** | `24.00px` |
| `__ocAndroid.getInsets()` | **`null`**（从未收到过） | 完整 payload |
| `header.top` vs safeTop | `top=1` < `safeTop` ⇒ **重叠 36px** | `top=24` = `safeTop` ⇒ **重叠 0** |
| 面板开 + BACK 一次 | 直接退回会话列表 | **面板关闭、仍停会话视图**（`histLen` 不变） |
| 再 BACK 一次 | — | **才回会话列表** |

---

## 1. 环境与纪律

- **自建实例**：`OC_Deliver_R2`（AVD 名属我方白名单），`emulator-6410`，端口 **6410**（偶数、≥6410；启动前实测 6410/6412/6414/6420/6422/6424/6430 全部 FREE）。
- 镜像 `android-35;google_apis_playstore_tablet` + `pixel_tablet`（**未传 `-skin`**，承 V7 教训：`pixel_tablet` 非合法 skin 名）。
- **开轮现场他人设备**：`emulator-5680`(`DSH_T79_Phone`)。**全程未碰、未 kill。**
- 后端 `127.0.0.1:4095` **全程未起停**（开轮与收尾均 `LISTEN pid=3748`，同一 pid），只做 `adb reverse`。
- 抢锁：`.agent-gradle-lock` 开轮时不存在 → 写锁 → 前台跑完 → 用**单条顶层 `rm --`** 释放（回执 `mavis-trash: moved to trash`，**未绕过**）。
- 未执行 `adb kill-server`、未用 `-a`、未用通配符 kill。

### 1.1 几何算法（手机档 ≈360dp）

`wm size 1080x2340` + `wm density 480`
⇒ 有效宽度 `1080 / (480/160) = **360dp**`，高度 `2340 / 3 = 780dp`（19.5:9，Pixel 6/7/8 档）。
实测回读 `innerW=360 / innerH=780 / dpr=3` ✅ 自洽。

---

## 2. 缺陷 A：顶部与状态栏/挖孔重叠

### 2.1 定性：是"没送到"+"没消费"，两处各断一半

**决定性实测（改前，`before_inset_reading.txt`）**：

```
{"safeTop":"","getInsets":null,"hasStyle":true}
```

⇒ 页面侧 `--oc-safe-top` 是**空串**，`__ocAndroid.getInsets()` 是 **`null`**
⇒ **页面从来没有收到过任何 inset payload**。

`hasStyle:true` 说明 IME1 的修法资产在（`oc-ime-lift` 已注入），进一步佐证：
**底部那条之所以好使，是因为 IME 会 0↔非0 抖动，从而每次都触发 payload 变化而被重发；
而顶部 inset 是静态的，从不变化 ⇒ 被去重逻辑永久挡在门外。**

**两处断点（都给行号）**：

| 断点 | 位置 | 说明 |
|---|---|---|
| ① 宿主投递丢失 | `webapp/src/main/java/.../host/InsetsHost.kt:72-74` + `:145` | `init` 里 `requestApplyInsets()` 在 **Activity onCreate 阶段**就触发首次 `publish`；此时页面尚未加载，`pushInsets` 的 `evaluateJs` 打在**没有 `window.__ocAndroid` 的文档**上 ⇒ payload 静默丢失。`InsetsHost.kt:145` 的 `if (payload.toString() != last?.toString())` 去重，使这条**静态**读数**永不重发**。 |
| ② 页面不消费 | 上游 SPA | 即使变量到了，上游也不认 `--oc-safe-top`（与 IME1 同构）；`env(safe-area-inset-top)` 因 WebView 边到边（`InsetsHost.kt:72` `setDecorFitsSystemWindows(false)`）**恒为 0**，指望不上。 |

**运行时反证（排除"宿主没给"这一支）**：手动调 `setImeMode()` 触发 `refresh()` 后
`getInsets()` **仍是 `null`**、`--oc-safe-top` **仍是空串** ⇒ 不是"值没算出来"，
是"算出来了但送不到 + 送到了也没人用"。logcat 佐证 `refresh()` 确实跑了：
`I OcWebHost/Insets: imeMode = VARS`，但页面读数纹丝不动。

### 2.2 修法（依据 + 行号）

**断点①（宿主侧）**：`InsetsHost.kt` 新增 `replay()`（不去重地补发最近一次 payload），
由 `WebHostActivity.kt` 的 `onPageFinished` 调用 —— 那一刻 `documentElement` 一定存在
（与该函数原有注释"此刻 documentElement 一定在了"一致）。

**断点②（页面侧）**：`oc-host.js` 注入 `oc-top-inset` 样式，用
`--oc-safe-top-eff = max(var(--oc-safe-top), env(safe-area-inset-top))`
给**应用根容器**垫 `padding-top`。

**"怎么给 top inset"的直接回答**：
**不是 `env(safe-area-inset-top)`（边到边下恒 0），而是宿主实测的 `--oc-safe-top` 变量**，
再与 `env()` 取 `max` 兜底 —— 与底部 `--oc-safe-bottom-eff` 同一口径。
**唯一真值来源仍是 `InsetsHost` 的 `WindowInsetsCompat`**（statusBars ∪ navigationBars ∪ displayCutout 取最大，`InsetsHost.kt:117-121`），
没有引入第二个真值来源。

**为什么垫根容器而不是 `header`**：`#root` 是 `h-dvh` + `box-sizing:border-box`，
垫 `padding-top` 后**总高仍是视口高**（内容区等量收缩、不溢出、不出滚动条）；
若垫在 `header` 上则只把它顶下去而下方内容不动，形态更差。
选 `#root, body>div:first-of-type` 双选择器，避免上游改 id 时修法整条失效。

### 2.3 证据：手机档

几何读数（`after_header_geom.txt`）：

```
{"safeTop":24,"headerTop":24,"headerBottom":60,"overlapPx":0,"clear":true,"innerH":780}
```

⇒ `header.top(24)` 恰等于 `safeTop(24)`，**重叠 0**，且 `innerH` 仍是 780（视口未被撑高）。

截图 `s01_phone_home_after.png`：`4:40` 时钟与挖孔条在 y≈0–48 区域，
**"Home" 标题整体位于其下方，无字形重叠、无穿过挖孔条**（对照 V13 `s04_top_crop.png`）。

### 2.4 证据：平板档回归（不能把平板搞坏）

`wm size reset` + `wm density reset` → 物理 `2560x1600 @320`（平板原生），实测 `tablet_after.txt`：

```
{"geom":{"innerW":1280,"innerH":800,"dpr":2},
 "safeTopVar":"24.00px","rootPadTop":"24px","headerTop":24,"overlapPx":0,
 "ins":{"safeTop":"24.00","safeBottom":"32.00",...}}
```

⇒ 平板同样 `overlapPx=0`、`headerTop=24`、`innerH=800` **未被压塌** ⇒ **平板未引入回归**。
截图 `s08_tablet_after.png`。

> 说明：平板档 `safeTop` 也是 24px（该 AVD 状态栏 24dp），垫 24px 属**正确避让**而非多余留白；
> 真正"无状态栏层"的桌面浏览器上 `safeTop=0` ⇒ 位移 0，绊线 `safeTop 为 0 …` 守着这一支。

---

## 3. 缺陷 B：面板打开时 BACK 误退

### 3.1 定性：宿主 BACK 顺序写反了（行号）

改前 `WebHostActivity.kt`（`installBackHandler`）：

```kotlin
if (webView.canGoBack()) { webView.goBack(); return }   // ← 优先
webView.evaluateJavascript("… onBack() …")              // ← 排在 else 后面
```

⇒ **页面自己的 `onBack()` 排在 `canGoBack()` 之后**。

**为什么"没拦住"—— 就是这个顺序**：上游 SPA 用 **History API** 做站内导航，
进会话时**已经压了一条 history**。实测佐证（`c1_panel_open.txt`）：
`inSession=true, histLen=**2**` ⇒ 面板打开态 `canGoBack()==true`
⇒ 直接 `goBack()` 把 SPA 路由退回首屏，**页面 `onBack()` 根本没被问到**
⇒ **popover 没关、还退了两级**。这与派单给的猜想
（"面板是页面内 popover ⇒ WebView 自己没历史可退"）**不同**：WebView **确实有**历史可退，
正因为有，才被优先退掉了。**所以不是"没历史"，是"有历史且被优先消费了"。**

### 3.2 修法（依据 + 取舍）

**宿主侧**：把顺序改成 **先问页面 → 页面消费不掉才退历史 → 都不成立则什么都不做**。
`pageHandled=true` 时**直接 return，绝不走 `goBack()`、绝不 `finish()`**。

**页面侧**：注入层注册默认 back handler，**不自己实现关闭逻辑**，而是**派发一个真实的 `Escape`**。

**取舍与依据**：
- 派发 Escape ⇒ 走的是 Radix dismissable layer **自己的**关闭路径，
  与桌面浏览器里这个 popover 的关闭方式**逐字一致**（V13 §缺陷 D4 的对照：桌面端就是 Esc/点外部关），
  **不会与上游状态机打架**，也不必逆向上游的内部状态。
- 选"宿主问页面"而不是"页面监听 `popstate`"：`popstate` 在 History API 路由变化时才触发，
  而"面板开"是**页面内 UI 状态**、**不产生历史项** ⇒ `popstate` 根本不会为它触发，
  用 `popstate` 等于测不到这个场景。故用**既有的 JS 桥**（`__ocAndroid.onBack` / `setBackHandler`，
  `oc-host.js:237-249`）—— 它本来就是为"宿主问页面要不要处理"设计的通道。

**⚠️ 关于本仓铁律"绝不按 BACK(4) 关面板"的澄清（派单点名要区分）**：
那条纪律针对的是**测试脚本把 BACK 当关闭手段去驱动 UI**（用 BACK 制造"关闭"这个副作用，
于是被误退掩盖了真正的关闭路径）。而**用户真的按下 BACK 时，期望就是先关掉浮层**。
两者不冲突：**测试脚本关面板仍应点 `≡`**（那才是"用 BACK 当关闭手段"）；
修的是**用户真按 BACK 时的路由优先级**。本轮证据正是按此区分设计的：
面板**打开态**按一次 BACK ⇒ 关面板且留在会话；**再按一次** ⇒ 才退列表。

### 3.3 证据：两段 BACK 行为

触发器（面板本体）= `[aria-label='More options']`（`aria-haspopup=true`）。

| 阶段 | 读数 | 文件 |
|---|---|---|
| 面板打开 | `visOverlays=1, trigExpanded=**true**, inSession=true, histLen=2` | `d1_panel_open.txt` |
| **按 1 次 BACK** | `visOverlays=0, trigExpanded=**false**, inSession=**true**, histLen=**2**` | `d2_after_back1.txt` |
| **按第 2 次 BACK** | `inSession=**false**` | `d3_after_back2.txt` |

logcat（同一序列，原文）：

```
I OcWebHost: back: pageHandled=true (overlay closed), no history pop     ← 第 1 次：关面板，不退
I OcWebHost: back: pageHandled=false -> goBack()                         ← 第 2 次：才退历史
```

截图：`d2_panel_open_shot.png`（面板开）、`d3_after_back1_shot.png`（第 1 次后，面板已关、仍在会话）、
`d4_after_back2_shot.png`（第 2 次后，回会话列表）。

### 3.4 ⚠️ 真机抓到并修掉的一个自造 bug（如实记录）

首版 `overlayCount()` 只数选择器命中数。**真机第一次跑就暴露了**：第 1 次 BACK 后面板确实关了，
但**第 2 次 BACK 仍被消费**（`pageHandled=true`），**退不回列表**。
定位到：上游常驻预挂载一个 `role="dialog"` 的**底部 sheet**，实测
`top=780 / h=205`，而视口高恰为 780 ⇒ **完全在视口外**；它把命中数**长期钉住 ≥1**。

两道修正（都在 `oc-host.js`）：
1. `overlayCount()` 增加**可见性闸门**：`w>0 && h>0 && r.top<vh && r.bottom>0`；
2. 选择器**剔除 `[role='dialog']`**，并以**触发器自报**为主判据
   （`[aria-haspopup][aria-expanded='true']` —— Radix 打开时置 true、关闭时置回 false，
   是**上游自己的权威信号**，面板一关立刻回 false）。

修完复测即 §3.3 的通过序列。

---

## 4. 绊线与反向验证

新增 `webapp/src/test/java/.../host/PhoneFormDefectTest.kt`（**11 个用例**）+ 纯函数件
`webapp/src/main/java/.../host/PhoneFormDefect.kt`（不进生产路径，仅供绊线）。

**可断言量**（几何层，纯 JVM）：
- **"top inset 已应用"**：`topOverlapPx(header=[1,37], safeTop=48) == 36`（改前读数）；
  按 `shiftedByTopInset` 下移 48 后 `== 0`；`safeTop=0` 时位移 `== 0`（不凭空造留白）。
- **"面板打开时 BACK 被消费"**：`shouldGoBack(panelOpen=true, canGoBack=true) == false`；
  `levelAfterBack(from=2, panelOpen=true) == 1`（仍会话）而 `panelOpen=false → 0`（退列表）。

### 4.1 反向验证：**真红**（且过程中抓到一次"假绿"）

`webapp/build.gradle.kts:126-137` 把 `oc-host.js` / `WebHostActivity.kt` /
`InsetsHost.kt` / `PhoneFormDefect.kt` 声明为测试任务输入（否则只改资产会被判
UP-TO-DATE 整段跳过 ⇒ 绊线假绿）。

> ⚠️ **首次反向验证是"假绿"，如实记录**：
> 第一版断言写成 `src.contains("#root")` / `src.contains("\"Escape\"")`，
> 撤回修法后 **gradle 仍 BUILD SUCCESSFUL（EXIT=0）** —— 因为这两个串在**注释里也出现过**
> （"根容器是 h-dvh" 段落、`KeyboardEvent` 注释），属典型的「绊线假绿」。
> 已改为新增 `code()` 辅助函数**先剥掉行注释与块注释再断言**，并把断言收紧到**可执行代码的精确片段**
> （`var TOP_STYLE_ID = "oc-top-inset";`、`"#root,body>div:first-of-type{"`、
> `"padding-top:var(--oc-safe-top-eff,0px);"`、`key: "Escape",`）。
> 收紧后复测**真红**。

**绿**（`green.log`）：`BUILD SUCCESSFUL`，`GRADLE_EXIT=0`。
**红**（`reverse_red.log`）：撤回两处修法（摘掉根容器 padding 规则 + 把 `key: "Escape"` 改掉）后：

```
RED_EXIT=1
PhoneFormDefectTest > facade 消费 --oc-safe-top 给根容器垫顶部内边距 FAILED
PhoneFormDefectTest > facade 注册默认 BACK 处理器 用 Escape 关浮层 FAILED
BUILD FAILED in 5s
```

**⇒ 两条修法各自被独立绊住，反向验证确实会红。** 撤回后已还原并复跑绿。

---

## 5. 重建交付物

命令：`:webapp:assembleDebug` + `:webapp:assembleRelease` + `:webapp:assembleReleaseR8`
（`BUILD SUCCESSFUL in 26s`）。

| # | 产物 | 体积 | **md5** | 签名 |
|---|---|---:|---|---|
| 1 | `opencode-shell-web.apk`（debug） | 5,243,859 B | `9F511C5A2D865CCA173D62A57BA956A0` | `CN=Android Debug`（`sharedDebug`） |
| 2 | `opencode-shell-web-release-nor8.apk` | 3,979,113 B | `03D7888FBC22779DFE7B10737F1CA151` | `CN=Android Debug`（`sharedDebug`） |
| 3 | `opencode-shell-web-release-r8.apk` | 1,437,969 B | `5227C2AC67F5A613C32ADACDBB7783E0` | `CN=Android Debug`（`sharedDebug`） |

- **R8 变体也重建**（第 3 行）⇒ 两份 release 都可分发，签名与旧包一致 ⇒ 可互相覆盖安装。
- ⚠️ **一处需要点出的判断**：`opencode-shell-web.apk` 这个规范名在 F16 交接文档里登记的是
  **debug** 包（5,275,479 B / `16C6CF8B…`）。本轮我一度把它拷成了 release-nonR8，
  **发现这会静默改变该文件名的既有语义**，已改回 **debug 产物**，保持三份产物语义与旧约定一致。

### 5.1 包内含源自查

对 **R8 包**解包 `assets/www/oc-host.js` 逐项核对：

```
oc-top-inset present      = True
root padding rule present = True
Escape dispatch present   = True
installTopInsetStyle calls= 2
installDefaultBackHandler = 2
role=dialog NOT in sel    = True
```

⇒ 修法**真的进了发布包**，且两条 readyState 路径都在。

---

## 6. 交接文档改动

`research/web/HANDOFF_FINAL_F16.md` §1 交付物小节已更新：三份新 md5 + 体积，
并**显式作废旧值** `76CA064B…` / `05988170…` / `16C6CF8B…`（表内标注 🆕 + 作废说明）。
另加一段变更摘要（改了哪三个文件）与包内含源自查结论。

## 7. 回归

| 模块 | 测试类 | 用例数 | 失败 |
|---|---:|---:|---:|
| `:webapp:testDebugUnitTest` | 17 | **159** | **0** |
| `:app:testDebugUnitTest` | 677 | **6136** | **0** |

`:webapp` 本轮净增 **11 个用例**（`PhoneFormDefectTest`），**只增不减**；未删改任何既有断言。

---

## 8. 收尾复核输出（原文）

```
# 杀前确认是自己起的
PID=28396 MINE_CHECK avd=OC_Deliver_R2 port6410=True

# 撤销 override（按 serial）
Physical size: 2560x1600
Physical density: 320            ← 无 Override 行

# 摘 reverse
$ adb -s emulator-6410 reverse --list      →  host-16 tcp:4095 tcp:4095
$ adb -s emulator-6410 reverse --remove tcp:4095
$ adb -s emulator-6410 reverse --list      →  （空）

# 只按 serial 关
$ adb -s emulator-6410 emu kill
OK: killing emulator, bye bye
OK

# 复核
emulator/qemu process count = 3
PID=52244 CMD=… -avd T88_Phone -port 5700 -no-window …      ← 别人的实例，未碰
PID=36412 CMD=…\emulator  -kill 47516 -sleep 20            ← emu kill 自拉拆卸辅助进程
PID=42452 CMD=…\emulator  -kill 47516 -sleep 20            ← 同上
OC_*/Medium_Desktop_B residual = 0
PORT 6410 LISTEN=False
PORT 6412 LISTEN=False
PORT 6414 LISTEN=False
$ adb devices
List of devices attached
emulator-5700	device            ← 别人的 T88_Phone，未碰
.agent-gradle-lock exists = False （用单条顶层 `rm --` 释放，回执 mavis-trash: moved to trash）
4095 LISTEN pid=3748              ← 与开轮前同一 pid，后端全程未起停
```

**未执行** `adb kill-server`、**未用** `-a`、**未用**通配符 kill；`emu kill` **只带一个 serial**。

> ⚠️ 如实记录两点：
> ① 复核时残留的 3 个进程**都不是我的 AVD**：一个 `T88_Phone`（别人的）、
> 两个是 `emu kill` 自拉的 `-kill … -sleep 20` 拆卸辅助进程（命令行**不带 `-avd`**），
> 按 HOUSEKEEPING 规则 2 判据**不属于我** ⇒ **未 kill**，其自然退出。
> ② 开轮时的 `emulator-5680`(`DSH_T79_Phone`) 在收尾时已不在 `adb devices` 里。
> 本轮**只对 `emulator-6410` 发过一条 `emu kill`** ⇒ 其消失**不是本轮造成的**，
> **未从本轮日志得出因果**，不代其它轮次下结论。

## 9. 碰过的 serial

- **唯一自建**：`emulator-6410`（`avd=OC_Deliver_R2`，`pid=28396`）——
  本轮所有 install / tap / keyevent / screencap / logcat / wm / reverse / emu kill **只针对它**。
- **只读旁观、未执行任何写操作**：`emulator-5680`(`DSH_T79_Phone`，开轮时在列)、
  `emulator-5700`(`T88_Phone`，收尾时在列)。

## 10. 是否碰过非自建实例

**否。** 未对 `MCRemote_*` / `DSH_*` / `T7x_*` / `Pixel_Tablet` 执行任何 kill / install / tap / wm / reverse 写操作。

---

## 11. 未闭环

1. **平板档只验了顶部 inset 回归**，未走完 V13 的平板全链路（平板档 BACK 行为、IME 遮挡未在本轮重测）。
2. **顶部 inset 未验横屏 / 刘海真机**：几何覆盖下 `displayCutout` 恒为 0，
   真实挖孔设备上 `safeTop` 会更大，修法按同一变量自适应，但**未在真挖孔机上实测**。
3. **V13 的 D3（360dp 下 Tab 条挤压）/ D5（首屏 `New session` 缺失）本轮未修**，不在 F18 派单范围。
4. **R8 交付包上仍无 CDP**（`webapp/` 不调 `setWebContentsDebuggingEnabled`，承 F10/F12/F16），
   故本轮真机证据取自 **debug 包**；R8 包只做了 §5.1 的**包内含源自查**，
   **未在 R8 包上跑一遍交互**（如实标注，未验证≠通过）。
5. **未测横屏 / 折叠屏 / 深色模式**。
