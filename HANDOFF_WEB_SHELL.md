# 交接文档 —— OpenCode Android 壳（路线 A：托管官方前端）

> 交接时间：**2026-10-04 22:3x（Asia/Shanghai）** · 硬期限：**2026-10-07 之前**
> 上一版交接（原生 Kotlin 路线）见 `HANDOFF.md`；**本文件描述的是路线切换后的现状，以本文件为准。**

---

## 0. 一句话现状

**产品负责人对"原生重写界面"不满意（界面不一致）之后，我们改用"路线 A"：Android 端不再重写界面，而是用 WebView 直连服务端自托管的官方前端。**
⇒ **一致性达到满分（界面就是官方界面本身）**，代价集中在 **输入延迟与耗电**，且**代价已被量化**（见 §4）。

**当前可交付（三份，都在 `research/deliver/`）**：

> 🔄 **R2 重建（2026-10-05，`f989907` 之后）**：下表两份 APK 已**重新构建**，
> **已包含通知点击修复（`f989907`）**。旧数（3,948,209 B / 8,636,901 B）**已作废**。
> 重建与设备复验原始输出见 `research/web/DELIVER_REBUILD_R2.md`。

| 产物 | 体积 | md5 | 用途 |
|---|---|---|---|
| **`opencode-shell-web-release-nor8.apk`** | **3,962,969 B（≈3.78 MB）** | `188e019b81436d43dfd4639b08da6ae5` | ✅ **推荐对外分发**（已签名 `sharedDebug`）；**含通知点击修复（`f989907`）+ WebView 预检 + 省电策略（`c2eaa3b`）** |
| `opencode-shell-web.apk`（debug） | **5,198,170 B（≈4.96 MB）** | `c77a43fade7ffe9997ed990ac2a66162` | 日常调试（debug 签名）；**同上** |
| **R8 版** | **未产出** | — | ⚠️ **构建未成功**；12 项风险清单，**最高风险 = Tink 静默降级** ⇒ **设备验证前不要分发** |

> 🔄 **重建记录（2026-10-05）**：`f989907`（通知）→ R2 重建并设备复验；`c2eaa3b`（省电策略）→ **再次重建**。
> **"交付物 = 代码"已用机械方式自查**：APK 内 `assets/www/oc-host.js` 的 md5 **等于**仓库源码 md5（`bf54276929d5f044f92ab08670b2dbbe`）。
> ⚠️ **旧 md5 全部作废**（`9ab8ee30…` / `8dcffc57…` / `7b578cee…` / `b716cbeb…`），它们对应的产物**不含后续修复**。

包名统一 `com.xiufeigo.opencodeshell.web.debug` · 签名 `CN=Android Debug`（SHA-256 `e86b7006…0510a`）
**对比**：原生壳 debug **44.2 MB** / 原生壳 release **29.4 MB** ⇒ **web 壳 release 比原生 release 小 7.4 倍**（因为**一个前端字节都不打包**）。

### 0.1 最新一批验证（2026-10-05，**每条都带证据**）

| 项 | 结论 | 证据 |
|---|---|---|
| **原生桥八项** | ✅ **全部 PASS**——含此前唯一"带保留"的**通知点击回会话**：判为**宿主缺陷**并修复 | `research/web/NOTIFICATION_CLICK_ROOTCAUSE.md`（真机 **展开通知栏→当场解析 bounds→`input tap` 真点→回读 location**；**7 条绊线，撤回即红**） |
| **形态覆盖** | ✅ 小屏 **360dp** / 手机 **412dp** / 折叠 **442·708dp** / **切换边界 ≈768dp** / 平板 **1366×768** | `research/env/FORMFACTORS_F4.md`：**<768dp = 上游移动导航栏；≥768dp = 导航栏消失、出现 `review-toggle`**（**上游自己的响应式设计**） |
| **IME 遮挡** | ✅ **重叠 0**（手机 / 平板 / 折叠屏都测到） | `research/web/IME_OCCLUSION_FIX.md`（**60 → 0**）+ `research/env/logs/e7_g3_phone.log`（composer `[454,514]` **整条在键盘上方**） |
| **上游地基** | ✅ 最新 `origin/v2 = 0a46301` 上**四条全部成立**；区间 **5 个提交无一触及 `cli/src` / `app/src` / `gui-extensions/src`** | `research/web/UPSTREAM_REGRESSION_A3.md`（并纠正我们两处记录：`app-assets.ts:22` 非发布路径；`web-ui.ts` 行号原记录有误） |
| **交付物=代码** | ✅ **机械证明**：APK 内 `assets/www/oc-host.js` 与源码 **md5 逐字节相同** | `research/web/DELIVER_REBUILD_R2.md` |
| **dex 等价** | ✅ release 与 debug **方法级等价、差异 0 条**（条目 2 vs 5） | `research/web/GAP_CLOSE_G2.md` |
| **首屏** | 🟢 5 次冷启**中位数 1,676 ms**（极值 1,583 / 1,829；**UI 可用标记**口径，非「最后一个 `_assets` GET」） | `research/web/GAP_CLOSE_G2.md` |
| **设备兼容** | ✅ **WebView 预检**：阈值 **105** / 加载超时 **12 s** / **4 条绊线反向验证真红** / **不误挡 124·149·105 与"读不到版本"** | `research/web/WEBVIEW_PREFLIGHT.md` |

**仍未闭环（3 条，都写清了原因）**
1. **耗电** —— **必须真机**（模拟器无电池模型）；**套件已就绪**：`research/perf/battery_real_device.ps1` + `BATTERY_HOWTO.md`（**拒绝静默给 0**，7 条可比性判定 + 阈值 X=5% 及推导）。
2. **debug 体积 8.64→5.15 MB 的逐 entry 明细** —— 已归因到 **dex**、且**与 `webapp/**` 任何提交无关**；**旧 APK 已不可取回**，明细需一份旧产物副本或能复现旧依赖解析的构建环境。
3. **R8 版** —— **构建未成功**；12 项风险清单（最高：**Tink 静默降级**）⇒ **设备验证前不要分发**。

---

## 1. 为什么是路线 A（决策依据，含行号）

| 事实 | 证据 |
|---|---|
| **服务端自己就托管那套 Web UI** | `packages/cli/src/app-assets.ts:22` 指向 `packages/app/dist`；SPA 回落 `packages/cli/src/services/web-ui.ts:12-24,27-33` |
| 路由与资源前缀 | 根路径 **`/`**，资源前缀 **`/_assets/`**（无 `/app` 子路径） |
| 运行期 | `GET /` = **200 text/html（免认证）**；`/api/info` 无认证 **401**、带 Basic **200 JSON** |
| **上游演进速度** | `origin/v2` 两周 **526 个提交**；**会话界面已由 `packages/gui-extensions` 承载**（`btw/review/context/terminal/file/details/browser`），且该包**编译进 app**（`packages/app/package.json:66` workspace 依赖 + `gui-extensions/src/main.ts:19` 静态 `Extension.compose`） |
| 结论 | **Kotlin 重写永远追不上；托管官方 UI 才能长期一致** |

**装配体积对比**（同一代码库）：
| 形态 | 体积 |
|---|---|
| 原生壳 debug | 44,198,253 B |
| 原生壳 release（已签） | 29,393,986 B |
| 原生壳 release + R8 + shrinkResources | 17,809,425 B（−59.71%） |
| Web 壳 debug（原有基线） | 8,636,901 B |
| **✅ Web 壳 release 已签（非 R8，推荐分发）** | **3,948,209 B** |
| Web 壳 release + R8 | **未产出**（见下） |

### 1.1 交付产物清单（E1 发布工程）

`webapp` 现已有 **`release` 构建类型**，**对齐原生壳约定**：`sharedDebug` 签名 + `applicationIdSuffix = ".debug"`
（见 `webapp/build.gradle.kts`；原先 web 壳的 release 缺 `.debug` 后缀，与原生壳不一致，已补齐）。
R8 做成 **`-Pwebapp.r8=true` 开关**（默认关），不新增 buildType。

| 产物 | 路径 | 体积 | MD5 | 签名 | 用途 |
|---|---|---:|---|---|---|
| Web 壳 debug（**R2 重建**） | `research/deliver/opencode-shell-web.apk` | **5,145,604 B** | `b716cbeb4c66e55fcb0f497640ae5e0c` | debug 签名 | 日常调试；**已含 `f989907`** |
| **✅ Web 壳 release 已签（非 R8，R2 重建）** | `research/deliver/opencode-shell-web-release-nor8.apk` | **3,961,217 B** | `7b578ceec3d7e35e2fffa4da67fe2d3b` | **`sharedDebug`（v2，`apksigner verify` = `Verifies`）** | **推荐对外分发**；**已含 `f989907`** |
| Web 壳 release + R8 | ❌ **未产出** | — | — | — | 被另一路的不可编译源码阻塞 |

**推荐对外分发 = `opencode-shell-web-release-nor8.apk`。**

**为什么暂不发 R8 版**：
1. **未在设备上验证**（本轮无设备、无模拟器，未起停后端）——**不能**声称"已验证可运行"；
2. R8 版**连构建成功都还没证明**：`:webapp:compileReleaseKotlin` 失败于
   `webapp/src/.../WebHostActivity.kt` 的 7 个 `Unresolved reference`（`webViewMajor` /
   `webViewVersionName` / `watchdogArmed` / `showDiagnostic` / `WebViewRenderProcessDetail` / `didCrash`），
   **该文件属另一路在途改动**（本路无 `webapp/src/**` 写权限，已退避重试 11 次，未越权修）；
3. 风险集中在**依赖侧**：本 APK **94.31% 是 dex**，R8 缩的主要是 okhttp / coroutines / **Tink**，
   而**最可能被裁坏的 Tink 会让 `SecretStore` 静默退化成内存存储**（口令不落盘、首启反复要密码）——
   这种"**不崩溃只降级**"的失败在静态检查里看不出来。

⚠️ **另路完工提醒**：请确认 `WebHostActivity.kt` 能编译，否则**连 debug 包也构建不出来**（不只是 R8）。

完整取证（命令、`BUILD SUCCESSFUL` 原文、badging、证书指纹、APK 拆包、12 项 R8 风险清单、
建议 keep 规则、实机验收清单）见 **`research/web/WEB_RELEASE_BUILD.md`**。

---

## 2. 架构与实现（现在有什么）

- **模块**：`webapp/`（独立 Gradle 子模块，产出第二个 APK，与原生壳并存，便于 A/B）
- **核心文件**：`WebHostActivity.kt`（托管 + 首启流程 + 兜底）、`LoopbackProxy.kt`（**应用内回环 HTTP 代理**）、`InsetsHost.kt`（IME/insets）、`WebHostBridge.kt`（桥）、`SecretStore.kt`（凭据）
- **为什么需要回环代理**：官方 UI 直接连后端会**撞 CORS**（旧壳靠 `LocalGateway` 剥 `Origin` 转发解决）。现在**页面与 API 同源**（都由应用内代理提供）⇒ **彻底免 CORS**，且**代理在转发时注入 Basic 头**，**口令只存 `EncryptedSharedPreferences`，页面永远拿不到口令**
- **桥（八类，已真机验收 7 PASS + 1 PASS 带保留）**：通知 / 返回 / 文件选择保存 / 分享 / 凭据 / **IME+insets** / 屏幕常亮 / 主题
- **首启流程**：`WebHostActivity.startProxyAndLoad()` —— 口令为空 ⇒ 弹配置框（后端地址 + 口令）；**新设备必须先播种口令**（见 §6 陷阱①）

---

## 3. 已闭环（都有取证）

| 项 | 结论 | 证据 |
|---|---|---|
| **一致性** | ✅ **满分**（就是官方界面） | 上机渲染，**Lead 亲自复核**：包已装、前台是 `WebHostActivity`、1366×768 真机截图、**684 行 logcat 里 CORS 命中 0** |
| **手机窄屏** | ✅ **与官方一致** —— 窄屏用**上游的移动端视图导航栏**替代侧栏，非缺陷 | 结构化取证：`[data-slot=session-mobile-view-navigation]`、`box=[9,45,394,36]`、4 tab + `More options` |
| **平板真实全屏** | ✅ `mode=fullscreen`、`mBounds=Rect(0,0-1080,2400)`、官方 UI 渲染（`title="OpenCode"`、909 DOM 节点、可见文本命中） | `research/env/NARROW_PHONE_E6.md` |
| **IME 遮挡（可用性硬伤）** | ✅ **修复：重叠 60px → 0（手机与平板两侧）** | `research/web/IME_OCCLUSION_FIX.md` + 前后截图 |
| **触摸靶** | ✅ 首启框 45dp → **≥48dp**，**8 条绊线 + 两次反向验证** | `research/web/TOUCH_TARGET_FIX.md` |
| **原生桥** | ✅ 7 项 PASS + 1 项 PASS 带保留（通知"点击回会话"未从 UI 观测到，用等价 intent 验证并标注）；**修掉 1 处宿主高危安全 bug** | `research/web/BRIDGE_ACCEPTANCE.md` |
| **闸门** | ✅ **APP 677 suites / 6136 tests / 0 failures / 0 errors / 0 skipped**；**WEBAPP 5 suites / 26 tests / 0 failures** | `research/v97_verify/gate_final.log` |
| **v146 绊线** | ✅ 被结构变更触发后**等价改写**（未删未放宽，criteria ①–⑥ 与 `2068/2/0` 触发器一字未改） | `research/fix/CELL5_PIN_REALIGN.md` |

---

## 4. 量化结果（**同机 / 跨机必须分清**）

### 4.1 输入延迟（**三层分离实验，2026-10-05 更新**）
**同一台设备、同一几何、同一方法、每层 n=240（2 轮 × 120）**：

| 层 | 被测物 | p50 | p90 |
|---|---|---|---|
| **L0** | **同一个 WebView 宿主里的极简 `<textarea>`** | **24.68 ms** | **29.22 ms** |
| **L1** | **官方 UI 的 composer** | **30.73 ms** | **62.10 ms** |
| **L2** | **原生壳的输入框** | **26.40 ms** | **32.71 ms** |

**分解**：
| 差 | p50 | p90 | 含义 |
|---|---|---|---|
| **L1 − L0** | **+6.05 ms** | **+32.88 ms** | ⇒ **代价来自"官方 UI 自己"**（受控输入 + 每次按键重渲染） |
| **L0 − L2** | **−1.72 ms** | **−3.49 ms** | ⇒ **WebView 基线并不慢，p50 还比原生略快** |

**判定：`可修（需改上游）`** —— 且**这推翻了更早"是 WebView 管线固有"的怀疑**。
⇒ **剩余差距 = 使用官方 UI 的票价**，已量化（**+6.05 ms p50 / +32.88 ms p90**），**不是壳侧缺陷**；
⇒ **因此"输入条走原生"的混合方案对 p50 并无必要**（WebView 不是瓶颈）；要压 p90 只能改上游的输入/渲染方式，**而按规矩不许改上游**。
（前情：P5 曾测得"我们代码在输入路径上无重活"——`longtask n=0`、**输入处理器 p90 仅 0.1 ms**——那条**依然成立**。）


### 4.2 其它三量
| 量 | 值 | 备注 |
|---|---|---|
| 首屏 | **FCP 2130 ms / DOM 挂载 2572 ms**（P2） | 与原生（~2.7–3.3 s）相当；⚠️ 早期报过的 `≥15,395 ms` **是量错**（量成了"最后一个 `_assets` GET"） |
| 帧时 | Web 28–31 ms vs 原生 17/26 ms | **87% 是模拟器地板**（400 行纯文字 DOM 对照 + 关动画对照证实）；跨轮/跨机，**只有方向可信** |
| 耗电 | ❌ **测不到** | **模拟器无电池模型/无 rail 电流**；**只能真机** |

---

## 5. 未闭环（接手第一件事）

1. **耗电** —— **必须真机**（模拟器给不了）；需要跑 `batterystats` 的固定脚本（脚本已存在于 `research/measure/`）
2. **真机帧时 / 真机输入延迟** —— 所有数字目前来自模拟器（软件渲染地板占 87%），**真机上的绝对值未知**
3. **帧时同机复测** —— P5 补了一部分，跨机部分**不作结论**
4. **输入延迟** —— 已定性为**上游 UI 的固有代价**（§4.1），**不是壳侧缺陷**；**混合输入条对 p50 无必要**（WebView 基线并不慢）。**若产品负责人仍要求压 p90，那需要改上游，与"不改上游"的约束冲突，需拍板。**
5. **R8 版未产出** —— 构建未成功；有 12 项风险清单（最高：Tink 静默降级）；**设备验证前不要分发**。另：G1 自报 `onRenderProcessGone` 分支与 `:app` 回归两项未闭环（`:app` 由 Lead 跑过：**677/6136/0**）。
6. **AVD 定义占 ≈39.8 GB**（进程已关；`OC_Phone_Test`/`B`/`C`、`OC_Narrow_Test`、`Medium_Desktop_B`）
7. **上游快照** —— 工作区停在 `702a73d`（2026-09-20），**落后 `origin/v2` 526 提交**；所有审计已改用 **git 对象读 `origin/v2`**（不切工作区）

---

## 6. 血泪教训（**接手必读，别再踩**）

| # | 教训 |
|---|---|
| ① | **新 AVD 必须先播种口令**，否则 `SecretStore` 为空 ⇒ 弹口令对话框 ⇒ **四量全测成对话框却看起来跑完了**（口令 = `opencode`）。**任何测量前先做前置检查：确认跑的是官方 UI** |
| ② | **WebView 版本是硬门槛**：**WebView 83 解析不了官方前端的现代 ESM ⇒ 页面纯白**；**124/149 可解析** ⇒ 目标设备需较新 WebView |
| ③ | **边到边 `setDecorFitsSystemWindows(false)` 会抵消 `adjustResize`**；**宿主给的 IME inset 正确，但页面必须自己用它** |
| ④ | **不许把"最后一个 `_assets` GET"当成首屏**（曾因此报出错的 `≥15.4s`） |
| ⑤ | **对比数字必须同轮、同参数、同设备指纹**；**不许跨轮沿用**（本冲刺三次结论反转都源于此：首屏 ≥15.4s、原生 17ms、输入延迟"无差异"） |
| ⑥ | **`5636` 等 PC 形态镜像是 freeform**，**改 `wm size` 也不会让它全屏** ⇒ 验"真实全屏"必须用非 freeform 实例 |
| ⑦ | **Goldfish 图形 HAL（`/vendor/lib64/hw/mapper.ranchu.so`）在导入图形缓冲时会 SIGABRT**，打死 `surfaceflinger` ⇒ `system_server` 无限重启（某台手机镜像上实测） |
| ⑧ | **绊线必须能被反向验证触发**；**判据与守护对象必须一致**（本仓最看重的一条） |

---

## 7. 环境与纪律

### 7.1 后端
```powershell
$env:OPENCODE_SERVER_PASSWORD='opencode'
Start-Process "$env:USERPROFILE\.bun\bin\opencode2.exe" -ArgumentList 'serve','--port','4095','--hostname','127.0.0.1' -WindowStyle Hidden
# 必须用 v2 可执行文件（PATH 上的 opencode 是 npm opencode-ai@1.18.33 = v1，会遮住 v2）
# 认证：用户名固定 opencode + Password = OPENCODE_SERVER_PASSWORD
```

### 7.2 闸门（唯一验收命令）
```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --console=plain cleanTestDebugUnitTest testDebugUnitTest assembleDebug :webapp:testDebugUnitTest :webapp:assembleDebug
```

### 7.3 设备与清理（**产品负责人明确要求**）
见 `research/HOUSEKEEPING.md`：
- **谁起谁关**：起了实例的必须收尾 `adb -s <serial> emu kill`，并**抄出复核输出**（进程 0 残留 + 端口已释放）
- **只关自己起的**：按 AVD 名判定（我们的是 `OC_*` / `Medium_Desktop_B`）；**用户的 `MCRemote_*`/`DSH_*`/`T7x_*` 与来源不明实例一律禁止 kill**
- **禁止 `adb kill-server`**（扰动共享设备连接）、**禁止 `-a`/通配符杀进程**
- 故意保留必须显式声明 + 下一轮补关
- **可复用的现代镜像**：`android-35;google_apis_playstore_tablet` + `pixel_tablet` 外形（**boot ~71 s、WebView 124**）；**端口挑 ≥5830 偶数、冲突就换、不抢**
- **gradle 锁**：`.agent-gradle-lock`（等→写→前台跑→**用顶层 `rm --`** 释放；`Remove-Item` 被本地安全策略拦截，**不许绕过**）

### 7.4 写盘纪律
- 先写盘再动手；并发期间**禁止 `git add -A`**（会把别的 agent 的半成品一起提交）
- **禁用 git 回退/checkout 改行尾**（`core.autocrlf=false`）
- 改断言必须有**反向验证**（撤回 ⇒ 必须红），**用例数只增不减**

---

## 8. 关键文件索引

| 主题 | 文件 |
|---|---|
| 路线决策（服务端自托管） | `research/web/SERVER_HOSTS_UI.md` |
| Web 宿主骨架 | `research/web/WEBHOST_SKELETON.md` |
| 路线 A 接线（代理/认证/上机） | `research/web/ROUTE_A_WIRING.md` + `ROUTE_A_device-5636_02-sessions.png` |
| 桥契约 / 桥验收 | `research/web/NATIVE_BRIDGES.md` / `BRIDGE_ACCEPTANCE.md` |
| IME 遮挡修复 | `research/web/IME_OCCLUSION_FIX.md`（60→0） |
| 触摸靶修复 | `research/web/TOUCH_TARGET_FIX.md` |
| 窄屏一致性 | `research/web/NARROW_SIDEBAR_CHECK.md` / `research/env/NARROW_BOTTOMBAR_E7.md` |
| 形态验证（手机/平板） | `research/env/NARROW_PHONE_E6.md` / `PHONE_AVD_VERIFY.md` |
| 性能（四量） | `research/measure/AB_NATIVE_VS_WEB.md` · `research/perf/{STARTUP_AND_FRAMETIME,FRAMETIME_AND_GZIP,POST_FIX_RETEST,INPUT_PATH_P5}.md` |
| 一致性审计（v2.0.22 基线） | `research/audit/PARITY_GAP_V2BASELINE.md` |
| 环境清理规矩 | `research/HOUSEKEEPING.md` |
| 交付 APK | `research/deliver/opencode-shell-web.apk` |

---

## 9. 分支与远端

- `main` —— 路线 A 的全部工作（**已推到 `origin/main`**）
- `native-kotlin-snapshot` —— 路线切换**之前**的原生 Kotlin 版本快照（`55c27a5`，**已推到远端**，便于回看/回退）
- 远端：`https://github.com/xiufeigo/opencode-android-shell.git`
