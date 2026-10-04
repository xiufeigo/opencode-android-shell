# 交接文档 —— OpenCode Android 壳（路线 A：托管官方前端）

> 交接时间：**2026-10-04 22:3x（Asia/Shanghai）** · 硬期限：**2026-10-07 之前**
> 上一版交接（原生 Kotlin 路线）见 `HANDOFF.md`；**本文件描述的是路线切换后的现状，以本文件为准。**

---

## 0. 一句话现状

**产品负责人对"原生重写界面"不满意（界面不一致）之后，我们改用"路线 A"：Android 端不再重写界面，而是用 WebView 直连服务端自托管的官方前端。**
⇒ **一致性达到满分（界面就是官方界面本身）**，代价集中在 **输入延迟与耗电**，且**代价已被量化**（见 §4）。

**当前可交付**：`research/deliver/opencode-shell-web.apk`
· 8,636,901 B · md5 **`8dcffc572ad469f472f61594f12ea990`** · 包名 `com.xiufeigo.opencodeshell.web.debug` · 已签名（`CN=Android Debug`，SHA-256 `e86b7006…0510a`）

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
| **Web 壳（路线 A）** | **8,636,901 B** |

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

### 4.1 输入延迟（**唯一同机可信的短板**）
| 量 | 原生壳 | Web 壳 | 差 |
|---|---|---|---|
| p50 | **28.14 ms** | **32.00 ms** | **+3.86 ms（+13.7%）** |
| p90 | **32.84 ms** | **58.76 ms** | **+25.92 ms** |

**归因（CDP 实测）**：`longtask n=0`、**输入事件处理器 p90 仅 0.1 ms** ⇒ **"我们的代码在输入路径上做了重活"被证伪**；
⇒ **剩余代价 = WebView 渲染管线的固有成本 + 上游受控输入/重渲染架构 + 模拟器软件渲染地板**（**三者都不在壳侧可修范围**；`_upstream/**` 不改）。

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
4. **输入延迟若还要改善** —— 只剩"**输入条走原生、内容区走 Web**"这类混合方案（会牺牲一致性，需产品负责人拍板）
5. **AVD 定义占 ≈39.8 GB**（进程已关；`OC_Phone_Test`/`B`/`C`、`OC_Narrow_Test`、`Medium_Desktop_B`）
6. **上游快照** —— 工作区停在 `702a73d`（2026-09-20），**落后 `origin/v2` 526 提交**；所有审计已改用 **git 对象读 `origin/v2`**（不切工作区）

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
