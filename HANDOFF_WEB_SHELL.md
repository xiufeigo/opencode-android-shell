# 交接文档 —— OpenCode Android 壳（路线 A：托管官方前端）

> 交接时间：**2026-10-05（Asia/Shanghai）** · 硬期限：**2026-10-07 之前**
> 上一版交接（原生 Kotlin 路线）见 `HANDOFF.md`；**本文件描述的是路线切换后的现状，以本文件为准。**
> **本轮（F8）只做文档刷新**：未碰设备、未起模拟器、未跑 gradle、未改生产代码。唯一写入 = 本文件 + `research/web/HANDOFF_REFRESH_F8.md`。
> **F8 的两件事**：① 把 **V10/V11 查实的"形态切换点更正"并进 §4.3**（两个断点 = 约 768dp + 约 1024dp）；② 把 **"V9 一批数据作废 + 为什么"并进 §4.4**（含撤回"第二切换点在 820–1366dp"那条推论），并把由此得的教训追加进 §6.1 ⑪。
> **F9（2026-10-05，当前状态）= 按 `research/audit/ADVERSARIAL_AUDIT_A5.md` 的对抗性审计结论修正本文件**。同样**纯文档**：未碰设备、未起模拟器、未跑 gradle、未改生产代码。唯一写入 = 本文件 + `research/web/HANDOFF_FIX_F9.md`。
> **F9 改了六类东西**（逐条对照见 `HANDOFF_FIX_F9.md`）：① **修掉 2 条被 A5 推翻的声明**（§0.1「一个前端字节都不打包」→ 实为 40,801 B facade 资产在包内；§0.2 裂缝①「三个 md5 两栏都不在」→ 实为**两个**，F5 规范件 `627f8ed8…` 已登记）；② **修掉 8 处自相矛盾 C1–C8**（重点 C3：`origin/v2` 三处互斥已统一到**主仓不存在 / `_upstream` 内 `ecfa884` 存在**，`0a46301` 标为无效）；③ **混轴数字加口径块**（§4.1 / §4.2 底层是**已作废包**）；④ **新增 §4.5「证据出处与强度」**（按证据类型分级，并写清设备证据取自哪两包、当前交付包是哪一份）；⑤ U1–U4 按 A5 建议改写或保留"无法复验"标注；⑥ md5 逐份 `Get-FileHash` 复核（4/4 仍一致）。
> ⚠️ **F9 没有做的事**：**没有重跑任何设备验证**。凡 A5 判为"仅方向性/无法复验"的数字，本轮**只加口径、不加证据**。
>
> **F16（2026-10-05，当前状态）= 把 F11–F15 这一批并进本文件，让明晚交付时它是完整的**。同样**纯文档**：未碰设备、未起模拟器、未跑 gradle、未改生产代码。唯一写入 = 本文件 + `research/web/HANDOFF_FINAL_F16.md`。
> **F16 改了五类东西**（逐条对照见 `HANDOFF_FINAL_F16.md`）：① **新增 §0.4「R8：从失败到 −63.90%」**（为什么第一次失败、这次靠什么成功、体积/首屏/风险逐条、证据怎么一步步变硬）；② **§0.1 交付物表改写 R8 那行**（常亮三段已闭环，不再写"未闭环"）并把 R8 包纳入 md5 复核表（**5/5 逐位一致**）；③ **§4.5 证据分级补进 R8 那一批**；④ **§5 未闭环重排**（R8"未产出"那条已过期、删除；换成"对外分发的正式包名+证书"，耗电写成"一行命令"）；⑤ **§6 追加 2 条教训**。
> ⚠️ **F16 没有做的事**：**没有重跑任何设备验证、没有改任何设备结论**。R8 的一切设备结论都**只做并入，不做升级**；凡本轮只是"转述"的地方都标了出处报告路径。**⚠️ 并且 F16 查出一处上一版的过期表述**：§1 装配体积对比里的「**web release+R8 未产出**」**已不成立**（R8 早已产出 1,435,193 B）—— 这是 F11 之前写的句子在 F11 之后没跟上，本轮已按磁盘实况订正（见 §1）。
>
> **F19（2026-10-05，当前状态）= 把"手机旅程闭环"与 F18 写进本文件**。同样**纯文档**：未碰设备、未起模拟器、未跑 gradle、未改生产代码。唯一写入 = 本文件 + `research/web/HANDOFF_F19.md`。
> **F19 改了四类东西**（逐条对照见 `HANDOFF_F19.md`）：① **新增 §0.5「手机形态：走一遍旅程才发现的两条缺陷，已经闭环」**（V13 发现 → F18 修 → V14 复验 10/10 的完整闭环，含两条缺陷的根因与设备读数）；② **md5 三行逐份 `Get-FileHash` 复核，3/3 与磁盘逐位一致**，并在 §0.1 补一条**过期口径警示**（本文件里仍有若干处拿 F18 之前的旧值当"交付包"）；③ **§6.1 追加 1 条教训**（"只有走完整条旅程才会发现这类缺陷"）；④ **§5 补 2 条手机形态遗留项**（D3 / D5），两条硬骨头原样保留。
> ⚠️ **F19 没有做的事**：**没有重跑任何设备验证、没有改任何设备结论**。V13/F18/V14 的一切结论都**只做并入，不做升级**；凡本轮只是"转述"的地方都标了出处报告路径。**F19 也一个字没改 `app/**`、`webapp/**`、`_upstream/**`、`.github/**`、`research/deliver/**`。**

> **UX7（2026-10-05）= 手机档四连修（B-1 终端条「+」误拦 / B-4 左滑与右上角按钮 / B-3 终端空白复查 / B-5 键盘裁切复查）并重建三份产物**。本轮**改了生产代码**（`webapp/src/main/assets/www/oc-host.js` 拦截收窄 + `WebHostActivity.kt` debug 注入通道扩展）+ 新增绊线 `Ux7PhoneSweepTripwireTest`（5 用例）；报告 `research/web/UX7_PHONE_SWEEP.md`。三份产物 md5 已变（见 §0.1 顶表与 UX7 复核块），**UX6 批三个 md5 全部作废**。
>
> **UX8（2026-10-05，当前状态）= 宿主侧重接「回合常亮（D1）+ 后台断 SSE（P6 刀 2）」，两条随 UX6 删代理失效的行为已补回**，并重建三份产物。实现：① 注入层新增**回合电源传感器**（`oc-host.js installRoundPowerSensor`：包装 `window.fetch`，发消息 POST 乐观置位 + tee `/api/event` 流按 `session.execution.*` 帧驱动 `oc.setKeepScreenOn`；**线上实测标记**，因真后端不发 `session.status` 帧）；② 宿主 `onPause/onResume` 合成派发 `pagehide/pageshow`，驱动上游 client 自有的 pageLifecycle 机制（`_upstream/packages/client/src/solid/connection.ts:219-240`）断/连 SSE，**未重建代理、未碰凭据**。设备证据（手机档主 + 平板档回归）：flag 置起有 logcat `flagSet=true` + 越 15s 超时物理常亮实证；回合结束释放 **Δ=4–17 ms**（旧事故是挂 4 分钟）；后台断开 **20–106 ms**、回前台重建 **92–245 ms**、会话循环后仍可用。新增绊线 `Ux8KeepAwakeSseTripwireTest`（13 用例，含三处撤回真红实跑）。报告 `research/web/UX8_RESTORE_KEEPAWAKE_SSE.md`。三份产物 md5 已变（见 §0.1 顶表与 UX8 复核块），**UX7 批三个 md5 全部作废**。

---

## 0. 一句话现状

**产品负责人对"原生重写界面"不满意（界面不一致）之后，我们改用"路线 A"：Android 端不再重写界面，而是用 WebView 直连服务端自托管的官方前端。**
⇒ **一致性满分（界面就是官方界面本身）**，代价集中在**输入延迟与耗电**；输入延迟**已被量化**（§4.1），耗电**必须真机才能测，本轮拒绝给数字**（§5）。

### 0.1 当前可交付（**UX8 批为当前唯一有效值，以磁盘为准**）

| 产物（`research/deliver/`） | 体积 | md5（磁盘实测值） | 用途 |
|---|---|---|---|
| **`opencode-shell-web-release-nor8.apk`** | **3,549,507 B**（≈3.38 MB） | `e2e6789b376282a0e226b3e117489d4d` | ⚠️ **仅内部灰度可用**（`sharedDebug` 签名 = `CN=Android Debug`、包名带 `.debug`；**对外分发前必须换证改包名，见 §5③**）。**UX8 宿主侧重接值**（2026-10-05，报告 `research/web/UX8_RESTORE_KEEPAWAKE_SSE.md`）：回合常亮驱动（注入层回合电源传感器）+ 后台断 SSE/回前台重建（合成 pagehide/pageshow 驱动上游 pageLifecycle）。**UX7 值 `3a9a1ced…` 及更早全部作废** |
| `opencode-shell-web.apk`（debug） | **5,437,979 B**（≈5.19 MB） | `aefb3bee019764ab775e9b121cd75cce` | 日常调试（debug 签名）；同上。**UX8 修复值**；**UX7 值 `fa613348…` 及更早全部作废** |
| ~~`…-f5.apk`~~ | — | — | ⚠️ **已删除**：F5 曾另起 `-f5` 后缀落产物 ⇒ 歧义 ⇒ 归拢回规范名（md5 差异来自签名时间戳） |
| **R8 版**（`opencode-shell-web-release-r8.apk`） | **1,268,829 B**（≈1.21 MB，比非 R8 **−64.3%**） | `5e3eea5314a911ce627792aab1346017` | 🟡 同 UX8 修复。**UX8 修复值**；**UX7 值 `8d5910ba…` 及更早全部作废**。⚠️ 此前🟡级设备结论（错误态三态/常亮三段等）**验于旧代理架构字节体**，UX6 起那些组件已不存在，引用旧结论必须带旧 md5 |
| `opencode-shell-debug.apk` | 44,198,253 B | `bd21ff21e72e75db62fc371d290f438e` | **原生壳** debug（对比用，未重建） |
| `opencode-shell-release-debugsigned.apk` | 29,393,986 B | `b6073ac63f5d8796708fb269fc855a8d` | **原生壳** release（对比用，未重建） |

包名统一 `com.xiufeigo.opencodeshell.web.debug`（**release 变体也用 debug 包名 + Android Debug 证书**——V5 §1.3 明确"不能据此判可用于对外分发"，是否改名/换证**待 Lead 定调**）。
**体积对比**：原生壳 release 29.4 MB vs web 壳 release 3.55 MB ⇒ **小 8.3 倍**（UX6 去代理后又缩了一档）。
> **⚠️ 口径更正（A5 推翻旧表述）**：本文件上一版在此处写的是「**一个前端字节都不打包**」——**这句字面为假，已撤回**。交付包里确实带着 **4 个壳自带的 facade 资产**（不是上游 SPA）：
>
> | 包内条目 | 未压缩 | 压缩 |
> |---|---|---|
> | `assets/www/index.html` | 17,621 B | 6,857 B |
> | `assets/www/oc-host.js` | 16,939 B | 6,038 B |
> | `assets/www/oc-perf.js` | 3,307 B | 1,524 B |
> | `assets/www/oc-preflight.js` | 2,934 B | 1,556 B |
> | **合计** | **40,801 B** | **15,975 B** |
>
> **准确表述**：「**不打包官方前端（上游 SPA）的任何字节**；壳自带的 4 个 facade 资产共 40,801 B 未压缩 / 15,975 B 压缩仍在包内。」复现：`unzip -l …apk 'assets/www/*'`（Windows：`System.IO.Compression.ZipFile`）。**"小 7.4 倍"这个事实不受影响**（40,801 B 占 release 包 1.0%），但那句"一个字节都不打包"是**一条 `unzip` 就能推翻的头牌级错话，不要再引用**。
**构建输出一致性**：`webapp/build/outputs/apk/{release,debug,releaseR8}/` 的 md5 与上表**逐位相同** ⇒ 拿构建目录那份也安全。

#### ✅ UX6 复核（2026-10-05，`Get-FileHash -Algorithm MD5` 重跑，**已被 UX7 批取代，仅存档**）

**UX6 亲手重跑 `Get-FileHash` 的实测结果**（`research/deliver/`）：

| 产物 | 体积（磁盘实测） | **磁盘 md5（实测，当前有效）** | 签名 |
|---|---|---|---|
| `opencode-shell-web-release-nor8.apk` | **3,545,375 B** | **`525b68470b01438a04bc5e0d026c9534`** | `CN=Android Debug`，证书 SHA-256 `e86b7006…0510a` |
| `opencode-shell-web-release-r8.apk` | **1,265,237 B** | **`a854824f3c4a0de3f1ea38ccea00d6dc`** | 同上 |
| `opencode-shell-web.apk`（debug） | **5,437,979 B** | **`fba7db16c87e0dab3f4bab53d5433490`** | 同上 |

**包内含源自查**：三份 APK 解包 `assets/www/oc-host.js` 的 md5 均为 **`784e5d27ac896251918a0ed3169ecd08`**，与 `webapp/src/main/assets/www/oc-host.js` 源文件**逐位一致**（3/3 MATCH=True，`System.IO.Compression.ZipFile` 解包实测）。

> 🔴 **UX6 批以下旧值全部作废，不得再被当作"当前交付包"引用**：`a3a88537…`(UX5 nor8) / `94baad39…`(UX5 R8) / `feae3854…`(UX5 debug) 及 UX4/UX3/UX2/F16 及更早的全部历代值。**本节以下的 UX5/UX4/UX3/F19/F8 复核块均为历史记录**，其中出现的 md5 一律指向旧字节体（旧代理架构）。引用 🟡 级设备结论时仍按 §4.5 口径**带它当时测的那个 md5**。

**UX6 架构变更一句话**：启动 = `loadUrl(SecretStore[backendUrl] ?: http://127.0.0.1:4095)`（G1 前置检查照旧）；**回环代理、未配置灰页、启动配置对话框、F5 探测、F21 闸门、口令存储/注入全部删除**；服务器与口令只在官方 UI「设置 → 服务器」里配置（官方表单支持密码字段，Basic `opencode:<password>`）；adb reverse tcp:4095 仍是 Android 侧访问宿主后端的前提。F21 在新形态下由**结构**保障（宿主无任何凭据路径），绊线 = `DirectConnectAuthBoundaryTest`。详见 `research/web/UX6_OFFICIAL_ENTRY.md`。

#### ✅ UX7 复核（2026-10-05，`Get-FileHash -Algorithm MD5` 重跑，**本表为当前唯一有效交付值**）

> **为什么重建（手机档四连修）**：产品负责人在**手机档（1200×2670）**实测发现四个问题（报告 `research/web/UX7_PHONE_SWEEP.md`，权威规格 `research/web/BUG_QUEUE.md`）：
> - **B-1（已修）**：手机档**终端标签条的「+」**被我们的选择面板拦截（应直接 `terminal.new()` 新建终端）。根因 = 旧 `findPlusButton()` 扫"页面上第一个非 header 的 `[role=tablist]`"，而审查条（SessionSidePanel）是**桌面限定**（`session-side-panel.tsx:257` `<Show when={isDesktop()…}>`）⇒ 手机档命中了终端条自己的「+」。修法 = 拦截**只锚 `aside#review-panel`**（`:259`）内的 tablist；手机档无审查条 ⇒ `findPlusButton()` 恒 null ⇒ **手机档全页面零拦截**。DOM 区分与两档设备证据（手机点 `+` 直出 Terminal 2 / 平板审查条 `+` 仍弹面板 / 平板终端条 `+` 直建终端）见报告。
> - **B-4（结论）**：上游手机档**没有右侧栏、也没有左滑手势**（SessionSidePanel 桌面限定；origin/v2 `app/src` 无 swipe 处理器）；手机档正规入口 = 会话页顶部的 会话/更改/文件/终端 视图标签 + `≡` More options 菜单（Usage / Session details）。「右上角按钮」实测 = `≡`，当前构建**未被拦截**（上游菜单正常弹出）——负责人截图里那个弹我们面板的"右上角"就是 B-1 的终端条「+」。
> - **B-3（当前构建不复现）**：手机终端"空白+打字无反应"在 UX7 构建上**两档均正常**（有提示符、能敲命令、有回显；PTY `connect-token` 200 + WS 建立 + xterm 渲染，浅色/深色主题都验过）。报告记录了完整对照证据与当时构建口径。
> - **B-5（当前构建不复现）**：软键盘弹起 composer 裁切在两屏（新建 session + 会话界面）**均不复现**；`--oc-ime-bottom` 抬升机制量化验证（IME 起：form padding-bottom=336.38px；收：归 0 自动恢复）。
> 新增绊线 `Ux7PhoneSweepTripwireTest`（5 用例）；**反向验证实测**：撤回收窄喂旧 oc-host.js ⇒ 2 条真红；撤回 `--oc-ime-bottom` 消费 ⇒ `ImeOcclusionTest.facadeConsumesImeBottomForComposer` 真红。另：`WebHostActivity.consumeDebugEval` 扩展 `b64:` 前缀（debug-only，绕开页面 CSP 的 unsafe-eval 限制），`DebugEvalChannelTest` 契约不变。

**UX8 亲手重跑 `Get-FileHash -Algorithm MD5` 的实测结果**（`research/deliver/`，当前唯一有效值）：

| 产物 | 体积（磁盘实测） | **磁盘 md5（实测，当前有效）** | 签名 |
|---|---|---|---|
| `opencode-shell-web-release-nor8.apk` | **3,549,507 B** | **`e2e6789b376282a0e226b3e117489d4d`** | `CN=Android Debug`，证书 SHA-256 `e86b7006…0510a` |
| `opencode-shell-web-release-r8.apk` | **1,268,829 B** | **`5e3eea5314a911ce627792aab1346017`** | 同上 |
| `opencode-shell-web.apk`（debug） | **5,437,979 B** | **`aefb3bee019764ab775e9b121cd75cce`** | 同上 |

**包内含源自查**：三份 APK 解包 `assets/www/oc-host.js` 的 md5 均为 **`b5057679b1a419af15d81ef72ff0fd9e`**（55,953 B），与 `webapp/src/main/assets/www/oc-host.js` 源文件**逐位一致**（3/3 MATCH=True，`System.IO.Compression.ZipFile` 解包实测）。

> 🔴 **UX8 批以下旧值全部作废，不得再被当作"当前交付包"引用**：**UX7 批** `3a9a1ced…`(nor8) / `8d5910ba…`(R8) / `fa613348…`(debug) / 资产 `6e1e7354…`；**UX6 批** `525b6847…`(nor8) / `a854824f…`(R8) / `fba7db16…`(debug) / 资产 `784e5d27…`；UX5 及更早全部历代值。**本节以下的 UX7/UX6/UX5/UX4/UX3/F19/F8 复核块均为历史记录**。
> **UX8 改了什么**：`oc-host.js` 新增 `installRoundPowerSensor`（回合电源传感器，见 §0 顶部 UX8 条与 `research/web/UX8_RESTORE_KEEPAWAKE_SSE.md`）；`WebHostActivity.kt` 新增 `dispatchPageLifecycleEvent`（onPause 派 pagehide / onResume 派 pageshow）+ 注释更新；`WebHostBridge.kt`/`RoundActivityTracker.kt`/`PowerPolicy.kt` 仅注释更新（行为未变）；新增绊线 `Ux8KeepAwakeSseTripwireTest`（13 用例）。

**UX7 亲手重跑 `Get-FileHash -Algorithm MD5` 的实测结果**（历史记录，已被 UX8 块取代）：

| 产物 | 体积（磁盘实测） | **磁盘 md5（实测，当前有效）** | 签名 |
|---|---|---|---|
| `opencode-shell-web-release-nor8.apk` | **3,545,875 B** | **`3a9a1ced56673589f34a075ceb06718b`** | `CN=Android Debug`，证书 SHA-256 `e86b7006…0510a` |
| `opencode-shell-web-release-r8.apk` | **1,265,633 B** | **`8d5910bab108dd5e50825467a9788ebd`** | 同上 |
| `opencode-shell-web.apk`（debug） | **5,437,979 B** | **`fa6133480c4816c98aafb7d6ac6f9173`** | 同上 |

**包内含源自查**：三份 APK 解包 `assets/www/oc-host.js` 的 md5 均为 **`6e1e7354fc670a5ce87c990d43c743ec`**，与 `webapp/src/main/assets/www/oc-host.js` 源文件**逐位一致**（3/3 MATCH=True，`System.IO.Compression.ZipFile` 解包实测）。

> 🔴 **UX7 批以下旧值全部作废，不得再被当作"当前交付包"引用**：**UX6 批** `525b6847…`(nor8) / `a854824f…`(R8) / `fba7db16…`(debug) / 资产 `784e5d27…`；UX5/UX4/UX3/UX2/F19/F18/F16 及更早全部历代值。**本节以下的 UX6/UX5/UX4/UX3/F19/F8 复核块均为历史记录**。
> 顺带（产品负责人问，行号级答案见报告）：新 session 底部 **`Temp`（T 图标+下拉）= 运行位置选择器**（`new-session/workspace/selector.tsx:107-132` 的 Menu.Trigger；label 规则 `:68-73`，"Temp" 是所选 worktree 目录名）⇒ 决定**新会话在哪个目录/分支运行**（主检出 / 新建 git worktree / 已有 worktree）；**`无 Git`（显示器图标）= `PromptGitStatus` 徽章**（`selector.tsx:374-410`，i18n `session.new.git.none`）⇒ 纯展示**所选工作目录不是 git 仓库**（是 git 时显示分支名），**不可点击**（渲染为 div，不是按钮）。

> ⚠️ 下列 UX7/UX6/F19/F8/UX5/UX4/UX3 复核块**已被 UX8 块取代**（历史保留）。

**F8 复核（2026-10-05，结论已由 A5 独立复算确认）—— 文档与磁盘一致，未发现脱节**：

> 📌 **F9 补注（A5 U2）**：「本轮**亲手**重跑」这类**过程性事实**（谁在什么时候跑的）**静态仓库无法证明**，对审计者没有价值。下表**结果**已由 A5 与 F9 两次独立复算确认（4/4 逐位一致），**"结果成立"才是可依赖的部分**。

| 路径 | 体积 | 磁盘 md5 | 与上表 |
|---|---|---|---|
| `research/deliver/opencode-shell-web-release-nor8.apk` | 3,975,389 B | `76CA064B7DE9246EE446F765A9198B37` | ✅ 一致 |
| **`research/deliver/opencode-shell-web-release-r8.apk`（R8，F11 新增）** | **1,435,193 B** | **`0598817026665544A08803055107A9FD`** | ✅ **一致（F16 第 5 次独立复核）** |
| `research/deliver/opencode-shell-web.apk` | 5,275,479 B | `16C6CF8B7330E5D02879F30333800AD9` | ✅ 一致 |
| `research/deliver/opencode-shell-debug.apk`（原生壳） | 44,198,253 B | `BD21FF21E72E75DB62FC371D290F438E` | ✅ 一致 |
| `research/deliver/opencode-shell-release-debugsigned.apk`（原生壳） | 29,393,986 B | `B6073AC63F5D8796708FB269FC855A8D` | ✅ 一致 |
| `webapp/build/outputs/apk/release/webapp-release.apk` | 3,975,389 B | `76CA064B7DE9246EE446F765A9198B37` | ✅ 与 deliver 同字节 |
| **`webapp/build/outputs/apk/releaseR8/webapp-releaseR8.apk`** | **1,435,193 B** | **`0598817026665544A08803055107A9FD`** | ✅ **与 deliver 的 R8 同字节** |
| `webapp/build/outputs/apk/debug/webapp-debug.apk` | 5,275,479 B | `16C6CF8B7330E5D02879F30333800AD9` | ✅ 与 deliver 同字节 |

⇒ **两行交付物 md5（release `76ca064b…` / debug `16c6cf8b…`）本轮复核有效，无需订正**；§4.3 的形态数据也正是取自这份 `76ca064b…`（V11 抬头实测同值同字节）。

#### ⚠️ F19 复核（2026-10-05，`Get-FileHash` 重跑，**以磁盘为准**）—— **三行 3/3 逐位一致**

**F19 亲手重跑 `Get-FileHash -Algorithm MD5` 的实测结果**（`research/deliver/`）：

| 产物 | 体积（磁盘实测） | **磁盘 md5（实测）** | 与 §0.1 顶表 | 
|---|---|---|---|
| `opencode-shell-web-release-nor8.apk` | **3,979,113 B** | **`03d7888fbc22779dfe7b10737f1ca151`** | ✅ **逐位一致** |
| `opencode-shell-web-release-r8.apk` | **1,437,969 B** | **`5227c2ac67f5a613c32adacdbb7783e0`** | ✅ **逐位一致** |
| `opencode-shell-web.apk`（debug） | **5,243,859 B** | **`9f511c5a2d865cca173d62a57ba956a0`** | ✅ **逐位一致** |

⇒ **结论：三份交付物与 §0.1 顶表登记的体积 + md5 完全对得上，文档与磁盘无脱节，无需订正。**
出处：`research/web/FIX_F18_PHONE.md` §5（这三份就是 F18 重建的产物，md5 与该报告表格逐位相同）；F19 复核记录 `research/web/HANDOFF_F19.md`。

#### ✅ UX5 复核（2026-10-05，`Get-FileHash` 重跑，**以本表为当前唯一有效交付物**）—— **本轮（UX5）又重建了三份 web 产物**

> **为什么重建**：产品负责人实测打回 UX3/UX4 版选择面板的两个动作（报告 `research/web/UX5_CHOOSER_ACTIONS.md`）：
> ① 「打开终端」按了没反应（终端面板收起时终端条不在 DOM，旧实现只 passThrough 点终端条「+」⇒ 找不到按钮什么都不做）；
> ② 「打开文件」错接成宿主原生 SAF（弹"选平板上的文件"），负责人要的是"原本点那个 + 触发的上游功能"。
> **修法（全在 `webapp/src/main/assets/www/oc-host.js`，宿主 Kotlin 零改动）**：
> - 「打开文件」= passThrough 把合成 click 还给**被拦截的那个上游「+」自己**（findPlusButton 命中）——上游原行为（审查条出现「Open file」标签页 + 应用内文件浏览器）逐字发生，构造上即与"直接点 +"等价，**不再弹 Android 系统文件选择器**；
> - 「打开终端」= 复刻上游自己的 openTerminal 序列（use-session-commands.tsx:195-199）：终端面板已开 ⇒ passThrough 点上游「New terminal」；面板未开 ⇒ 派发上游自己的 terminal.new 键位（ctrl+alt+t；合成 keydown 可触发上游键位派发器 command.tsx:401-428）+ 900ms 自检（oc:open-terminal 回读 found 标志）。
> 新增绊线 `Ux5ChooserActionsTripwireTest.kt`（5 用例；**反向验证实测**：撤回修法喂 HEAD 旧 oc-host.js ⇒ 2 条断言真红——检出旧 SAF 实现 + 检出"只点按钮"旧终端实现）。
> 设备证据（平板 emulator-6590 / 手机 emulator-6592，全部真实点击）：打开终端 ⇒ 终端面板出现 + Terminal 1 自动创建 + `pwd` 命令有回显；打开文件 ⇒ 应用内 Open file 标签页、topActivity 仍是自家 WebHostActivity（无 SAF）；两档截图 + logcat 原文在报告 §3。

**UX5 亲手重跑 `Get-FileHash -Algorithm MD5` 的实测结果**（`research/deliver/`）：

| 产物 | 体积（磁盘实测） | **磁盘 md5（实测，当前有效）** | 签名 |
|---|---|---|---|
| `opencode-shell-web-release-nor8.apk` | **3,994,336 B** | **`a3a88537abdfab20b481e99ff2a92c19`** | `CN=Android Debug`，证书 SHA-256 `e86b7006…0510a` |
| `opencode-shell-web-release-r8.apk` | **1,449,744 B** | **`94baad39812fa5e08a8e5fe386bd6632`** | 同上 |
| `opencode-shell-web.apk`（debug） | **5,438,212 B** | **`feae3854244badff58820607fe1d6913`** | 同上 |

**包内含源自查**：三份 APK 解包 `assets/www/oc-host.js` 的 md5 均为 **`ea950f4a8f061c67a07270787cf4a239`**，与 `webapp/src/main/assets/www/oc-host.js` 源文件**逐位一致**（3/3 MATCH=True，`System.IO.Compression.ZipFile` 解包实测）。

> 🔴 **UX5 批以下旧值全部作废，不得再被当作"当前交付包"引用**：
> **UX4 批** `9fb24886…`（nor8）/ `f3bee4f9…`（r8）/ `a7377e06…`（debug）；
> **UX3 批** `f47e281c…` / `4917af72…` / `5d3bcc15…`；
> **UX2 批** `d6015bf2…` / `28ee9ffc…` / `69bbe05f…`；
> **F19/F18 批** `03d7888f…` / `5227c2ac…` / `9f511c5a…` 及 F16 批更早全部历代值。
> 引用 🟡 级设备结论时仍按 §4.5 口径**带它当时测的那个 md5**，别把历史字节体当今天的磁盘文件。

#### ✅ UX4 复核（2026-10-05，`Get-FileHash` 重跑）—— **已被 UX5 批取代，仅存档**

> **为什么重建**：UX4 修两个主题缺陷（报告 `research/web/UX4_THEME_FIX.md`）：
> ① 「+」选择面板**硬编码深色**（`oc-host.js` 的 `buildPanel()`）⇒ 改为读**上游主题 token**
> （`--surface-raised-base` / `--surface-raised-base-hover` / `--border-weaker-base` /
> `--text-strong` / `--text-weak`，浅色/深色/换主题都跟随）；
> ② 「跟随系统」宿主侧缺口 ⇒ 新增 `values-night/colors.xml`（改前缺失）、昼版窗口底色
> `oc_host_bg` 由恒深 `#FF080808` 改为 `#FFFAFAFA`、`WebHostActivity` 重写
> `onConfigurationChanged`（`uiMode` 在 configChanges 里 ⇒ 不重推 scheme 就 stale）。
> 新增绊线 `Ux4ThemeTripwireTest.kt`（6 用例；**反向验证实测**：撤回修法 ⇒ 5/6 真红）。
> 设备四组合截图 + 实时切换证据均在 `research/web/UX4_THEME_FIX.md` §设备证据。

**UX4 亲手重跑 `Get-FileHash -Algorithm MD5` 的实测结果**（`research/deliver/`）：

| 产物 | 体积（磁盘实测） | **磁盘 md5（实测，当前有效）** | 签名 |
|---|---|---|---|
| `opencode-shell-web-release-nor8.apk` | **3,993,156 B** | **`9fb248860e312b33e7bb3b31322be65d`** | `CN=Android Debug`，证书 SHA-256 `e86b7006…0510a` |
| `opencode-shell-web-release-r8.apk` | **1,448,564 B** | **`f3bee4f9dce86759f6a32d1e64ce3acd`** | 同上 |
| `opencode-shell-web.apk`（debug） | **5,438,212 B** | **`a7377e069f04d543d46c59386770ffc9`** | 同上 |

**包内含源自查**：三份 APK 内 `assets/www/oc-host.js` 的 md5 均为 **`f2b4e33d28434d3b51ae3c14350e1189`**，与 `webapp/src/main/assets/www/oc-host.js` 源文件**逐位一致**（`System.IO.Compression.ZipFile` 解包实测，三份 match=True）。

> 🔴 **UX4 批以下旧值全部作废，不得再被当作"当前交付包"引用**：
> **UX3 批** `f47e281c…`（nor8）/ `4917af72…`（r8）/ `5d3bcc15…`（debug）；
> **UX2 批** `d6015bf2…`（nor8）/ `28ee9ffc…`（r8）/ `69bbe05f…`（debug）；
> **F19/F18 批** `03d7888f…` / `5227c2ac…` / `9f511c5a…`；
> **F16 批及更早** `76ca064b…` / `05988170…` / `16c6cf8b…` 以及 §0.1「已作废的旧 md5」清单里的全部历代值。
> 引用 🟡 级设备结论时仍按 §4.5 口径**带它当时测的那个 md5**，别把历史字节体当今天的磁盘文件。

#### ✅ UX3 复核（2026-10-05，`Get-FileHash` 重跑）—— **已被 UX4 批取代，仅存档**

> **为什么重建**：UX3 把「打开终端 / 打开文件」选择面板从**顶栏「+」**挪到**右侧栏标签条「审查」旁的「+」**（产品负责人截图批注纠错）。改动只在 `webapp/src/main/assets/www/oc-host.js`（页面注入层）+ 新增绊线测试 `PlusPositionTripwireTest.kt`；**宿主 Kotlin 零改动**。报告：`research/web/UX3_PLUS_POSITION_FIX.md`。

**UX3 亲手重跑 `Get-FileHash -Algorithm MD5` 的实测结果**（`research/deliver/`）：

| 产物 | 体积（磁盘实测） | **磁盘 md5（实测，当前有效）** | 签名 |
|---|---|---|---|
| `opencode-shell-web-release-nor8.apk` | **3,992,324 B** | **`f47e281c994b572784ba83d2fe511684`** | `CN=Android Debug`，证书 SHA-256 `e86b7006…0510a` |
| `opencode-shell-web-release-r8.apk` | **1,447,376 B** | **`4917af7293f2e15a3459d0945ae5476a`** | 同上 |
| `opencode-shell-web.apk`（debug） | **5,437,748 B** | **`5d3bcc15362acd113dea924ccb3da4fb`** | 同上 |

**包内含源自查**：三份 APK 内 `assets/www/oc-host.js` 的 md5 均为 **`a3ce11503f31cf03d6ee58c182c82cad`**，与 `webapp/src/main/assets/www/oc-host.js` 源文件**逐位一致**（`System.IO.Compression.ZipFile` 解包实测）。

> 🔴 **UX3 批以下旧值全部作废，不得再被当作"当前交付包"引用**：
> **UX2 批** `d6015bf2…`（nor8）/ `28ee9ffc…`（r8）/ `69bbe05f…`（debug）；
> **F19/F18 批** `03d7888f…` / `5227c2ac…` / `9f511c5a…`；
> **F16 批及更早** `76ca064b…` / `05988170…` / `16c6cf8b…` 以及 §0.1「已作废的旧 md5」清单里的全部历代值。
> 引用 🟡 级设备结论时仍按 §4.5 口径**带它当时测的那个 md5**，别把历史字节体当今天的磁盘文件。

> 🔴 **F19 顺带点出的过期口径（⚠️ 本文件内部新旧值并存，接手时务必以本表为准）**：
> **F18 重建过一次三份交付物**，所以本文件里**仍有若干处保留着 F18 之前的旧值**——上表（"F8 复核"那块）与 §0.1 顶表写的还是**旧值**，而顶表/F18 §5 已是**新值**。具体三处**仍需按新值理解**的地方：
> 1. **§0.1 上面那块"F8 复核"小表**里的 `76ca064b…`(3,975,389 B) / `0598817026…`(1,435,193 B) / `16c6cf8b…`(5,275,479 B) 是**F18 之前的字节体**；
> 2. **§0.4 / §4.5 / §5 多处仍以 `76ca064b…` 作为"当前交付包"指代**（F10/V12 的设备证据取自它）——**这个指代现在指向的是旧字节体**，引用那些🟡级设备结论时**必须带它当时的 md5 与体积**，不要当成今天磁盘上的那份；
> 3. §1 装配体积对比、§0.4.3 的 `−63.90%` 换算基准，同样是按**当时那一对**（`76ca064b…` vs `0598817026…`）算的，**F18 重建后这两个数不再等于今天磁盘上两包的字节差**。
>
> **⇒ 一句话**：F18 重建把"交付物"换成了本表这三份新字节；**凡是引用体积/md5，一律以本表与 §0.1 顶表为准**；**凡是引用 🟡 级设备结论（V12/F10/R8 各批），一律带它当时测的那个 md5**，别把"当时那台包"和"今天这台包"混为一台。F19 已按此口径把新一批（V13/F18/V14）的读数单独标注，**不与旧批混轴**。


#### 已作废的旧 md5（**全部不得再被引用**）

| 代次 | release | debug |
|---|---|---|
| 更早（`9ab8ee30` 代） | `9ab8ee300027f9847c2b220bb26f2b98`（3,948,209 B） | `8dcffc572ad469f472f61594f12ea990`（8,636,901 B） |
| R2 件 | `7b578ceec3d7e35e2fffa4da67fe2d3b`（3,961,217 B） | `b716cbeb4c66e55fcb0f497640ae5e0c`（5,145,604 B） |
| **V5 复验的包**（**未登记在 R2 §2.1 任何一栏**，见 §0.2 裂缝 ①） | `b063148df53a1364bf5c42ce6bdca0d3`（3,965,033 B） | — |
| F3 件 | `5aa0b63657cb599cd288ebc5597e9d21`（3,968,173 B） | `de1a476c32dc25c53d1034ea01ca1e6f`（5,200,219 B） |
| **F4 件 = V6 复验的包**（**未登记在 R2 §2.1 任何一栏**，见 §0.2 裂缝 ①） | `bc7f30180775485ce887dc74e4489c72`（3,968,477 B） | `90690556fb509b3da6ed47fdf44f1365`（5,201,814 B，仅 F4 轮设备实测物，未进 `deliver/`） |
| F5 `-f5` 副本（已删） | `a1484b271eab097bc3f6b9cd7e1e4b4d`（3,975,357 B） | `2b6f48224ed99d45c753a87cb6b11868`（5,275,479 B） |
| F5 规范件（`554feb4`）= **V8 复验的包** | `627f8ed8a38aaa8caa24a39c6fe1678d0`（3,975,357 B） | — |

> ⚠️ **上一版本文档把 `bf542769…`（13,804 B）列进了"已作废的 APK md5"，那是分类错误**：它是 **`assets/www/oc-host.js` 这份资产的 md5**，不是任何 APK 的 md5（R2 §2.4：source / debug APK / release APK 三者该资产 md5 均为 `bf542769…`，`MATCHES_SOURCE=True`）。**本版已订正。** 另注：F4 §8.1 记的 `oc-host.js` 是 `2b6ee8db…`（16,939 B），那是**另一份未提交的改动**（V5 §3 点名），两份后续包都带着它。

**"交付物 = 代码"已用机械方式自查**（release APK 的 `classes.dex` 逐串 `contains`）：F5 轮确认 F5 新增串全在包内（`FIX_F5.md` §6）；**F6 轮确认 `buildProbeRequest` 这个 F6 新增符号在包内**（`FIX_F6_PROBE.md` §8）⇒ 同时排除了"交付的是旧包"。

### 0.2 ⚠️ 文档与磁盘的裂缝（本轮发现，**需上报**）

**裂缝 ① —— 两个被设备复验过的 release 包，从未登记进交付台账。**（**F9 已按 A5 更正：原写"三个"，实为"两个"**）
`research/web/DELIVER_REBUILD_R2.md` §2.1 的现行表与"显式作废清单"加起来覆盖 **F6 / F5 / F3 / R2** 四个代次。逐条 `Select-String` 复核结果：

| 被复验的包 | 在 R2 §2.1 现行表里？ | 原文位置 |
|---|---|---|
| V5 复验的 `b063148d…`（3,965,033 B） | ❌ **未登记** | — |
| V6/F4 复验的 `bc7f3018…`（3,968,477 B） | ❌ **未登记** | — |
| V8/F5 规范件 `627f8ed8…`（3,975,357 B） | ✅ **已登记**（本文件上一版说它"两栏都不在"，**这句是错的**） | `DELIVER_REBUILD_R2.md` **L96**，落在 §2.1（L88）…§2.2（L118）**区间之内**：「F5 / 规范 release（`554feb4`）：release `627f8ed8a38aaa8caa24a39c6fe1678d0`，3,975,357 B」 |

⇒ **准确表述**：§2.1 现行表**已登记** F5 规范件 `627f8ed8…`、R2 件、F3 件、F6 件四代；**未登记**的是 V5 复验的 `b063148d…` 与 V6 复验的 `bc7f3018…` **两代**。**结论方向不变，但依据从"三个全没登记"改为"两个没登记"**（原话"这三个 md5 在 §2.1 里两栏都不在"对 V8 那一条不实——这条裂缝是本文件用来要求 Lead 复跑的核心论据，**自己的论据被查出 1/3 不实，故 F9 主动订正**）。
⇒ **后果（仍然成立）**：§3.5「常亮三段 PASS」与 §2「错误态三态 PASS」**都是在 `bc7f3018…` / `627f8ed8…` 上验的，不是当前交付包 `76ca064b…`**。三者的代码差异很小（F6 只改 `BackendProbe` 一处、+32 B；F5/F6 的接入健壮性改动都在），但**严格讲当前交付包本身没有单独跑过这三段**。**接手人若要对外承诺，请先在 `76ca064b…` 上复跑一次。**

**裂缝 ② —— R2 文件内部自相矛盾。** §2.1 宣布 R2 件（`7b578cee…` / `b716cbeb…`）"全部作废，不得再被引用"，但同一文件 **§8 交接 diff 与 §11 产物清单仍按旧值陈述**。

**裂缝 ③ —— 相机权限的取证时间差。** `research/audit/PARITY_LEDGER_A4.md` §7 N-2 与 §9-4 记「`webapp/src/main/**` scoped grep `onPermissionRequest` / `RESOURCE_VIDEO_CAPTURE` / `CAMERA` = **零命中** ⇒ 相机权限未转授」；但该取证时间是 **2026-10-05 02:43–02:45**（A4 §11），而 **`FIX_F3.md`（04:1x–04:5x）之后**，`FIX_F5.md` §3.1 记「`WebHostActivity` 有 `onPermissionRequest` 对可信 origin 授 `RESOURCE_VIDEO_CAPTURE`」，`RELEASE_VERIFY_V6.md` §1.1 记设备端 `android.permission.CAMERA: granted=true`。
⇒ **A4 的 N-2 结论已过期，需重取 grep 才能重新结账**。（N-2 的**另一半**——"用户可达路径未打通"——**依然成立**，见 §3.4。）

**裂缝 ④ —— `dumpsys window` 侧证口径两轮互斥。** V5 §6.1 拿到过 `fl=KEEP_SCREEN_ON …` 行（0→1→仍 1）；V6 §5.4 记**全窗口 grep `KEEP_SCREEN_ON` 计数恒为 0**，**包括已确知 `flagSet=true` 的那 43 秒窗口内**。V6 明确「**我不能拿这个"MISS"当"没置起"的证据** —— 它与 `flagSet=true` 直接矛盾，矛盾时以 `flagSet` 为准」。⇒ **引用常亮结论时，只用宿主 `D1 applyKeepScreenOn` 日志这一路信源。**

**裂缝 ⑤ —— 8,636,901 B 这个数字指向了两份不同的二进制。** G3 §4-3 指出 `RELEASE_DEVICE_VERIFY.md` 记的那版 debug `classes.dex` 与今天逐字节相同、但**当时没有 `classes5.dex`** ⇒ "那一版与今天不是同一个 APK，但其体积被当成了同一个"。引用这个旧数字时必须带上这句。

### 0.3 最新一批验证（每条带证据）

> ⚠️ **F9 读法提示（A5 U1，F10 已更新）**：本表**不是**同一等级的证据混在一张表里。**"✅ 静态可复验"** = 现在就能用命令在磁盘/APK 上重跑；**"🟡 设备运行期观测"** = 结论来自**真机 logcat / `dumpsys`**，本仓只有源报告里的转述、**静态磁盘上查不到原文**，要复验**必须重跑设备**。**接入健壮性三态 / 回合常亮三段这两行原为「验于 `bc7f3018…`/`627f8ed8…`、非交付包」；F10 依据 V12 轮已把它们在交付包 `76ca064b…` 上重做一遍**（md5 双向复核过），⚠️ **但级别仍是 🟡**（复验仍需重跑设备，不是静态可复验）。完整分级见 **§4.5**。

| 项 | 结论 | 证据 | 证据等级 |
|---|---|---|---|
| **接入健壮性三态** | 🟡 正确口令/错口令/不可达 **三条路径全 PASS**（**V12 已在交付包 `76ca064b…` 上重做**；更早的 `bc7f3018…`/`627f8ed8…` 轮次保留为历史） | §3.1；**`research/web/V12_DELIVERED_VERIFY.md` §2–§4**（`emulator-6250`）；历史：`FIX_F6_PROBE.md` §3（`emulator-6170`） | 🟡 **设备运行期观测**（符号侧已静态复验，见 §4.5） |
| **回合常亮三段** | 🟡 置起 → 释放 → **+95 s 兜底不复发**（**V12 已在交付包 `76ca064b…` 上重做**） | §3.5；**`research/web/V12_DELIVERED_VERIFY.md` §5**（`emulator-6250`）；历史：`FIX_F4_KEEPSCREEN.md` §5、`RELEASE_VERIFY_V6.md` §5.2、`V8_VERIFY.md` §5 | 🟡 **设备运行期观测** |
| **配对路径定性** | ✅ **上游行为，我们一致**（不是缺陷） | §3.3；`research/audit/PAIRING_ROUTE_VERDICT.md` | ✅ **静态可复验**（代码行号 + git 对象） |
| **剪贴板 N-7** | ✅ PASS —— `CTRL+A`→`KEYCODE_COPY`→清空→`KEYCODE_PASTE`，哨兵串 `OC_CLIP_98765` 原样回填 | `V7_CLOSEOUT.md` §3（`adb shell cmd clipboard` 在本镜像**无实现** ⇒ 走真实 UI 路径） | 🟡 **设备运行期观测** |
| **原生桥八项** | ✅ 全 PASS（含"通知点击回会话"，判为宿主缺陷并修复） | `research/web/NOTIFICATION_CLICK_ROOTCAUSE.md`（真机展开通知栏→解析 bounds→`input tap` 真点→回读；7 条绊线撤回即红） | 🟡 **设备运行期观测**（链路符号已静态复验） |
| **首屏** | 🟢 5 次冷启**中位数 1,676 ms**（极值 1,583 / 1,829；**UI 可用标记**口径）——⚠️ **取自已作废 R2 件 `7b578cee…` + `emulator-5910`，非交付包**，详见 §4.2 | `research/web/GAP_CLOSE_G2.md` §2 | 🟡 **仅方向性**（构建已作废，见 §4.2 口径块） |
| **dex 等价** | ✅ 两个宿主类**方法级等价、差异 0 条**（⚠️ 不是字节级等价，见 §5） | `GAP_CLOSE_G2.md` §1 | 🟡 **仅方向性**（比的是已作废 R2 件） |
| **IME 遮挡** | ✅ **重叠 0**（手机实测，336.38 CSS px × dpr 2.625 = 883 px 与 `2400−1517` 逐像素相等） | `GAP_CLOSE_G3.md` §1 | 🟡 **设备运行期观测** |
| **WebView 预检** | ✅ 阈值 105 / 12 s / 4 条绊线反向验证真红 / 不误挡 124·149·105 | `research/web/WEBVIEW_PREFLIGHT.md` | ✅ **静态可复验**（`dexdump` 搜到 `MIN_MAJOR` **实值 105**，见 §4.5） |
| **上游地基** | ✅ **`_upstream` 内 `origin/v2 = ecfa884`** 上四条全成立；区间 5 个提交**无一触及** `cli/src`/`app/src`/`gui-extensions/src` | `research/web/UPSTREAM_REGRESSION_A3.md`；**ref 口径见 §9（F9 已更正）** | ✅ **静态可复验** |
| **R8 版核心项**（`0598817026…`） | 🟡 **口令存储 / JS 桥 / 错误态三态 / `shrinkResources` / 无崩溃 = 全 PASS**；**常亮三段已闭环**（真点 Send 驱动 + tap 前基线 + 连续两回合 + +95 s 不回弹），**设备端 `base.apk` md5 逐位一致** | §0.4；`R8_ATTEMPT_F11.md` · `R8_KEEPAWAKE_F12.md` · `R8_UITAP_F13.md` · `R8_UITAP_F15.md` | 🟡 **设备运行期观测**（混淆产物侧已静态可复验，见 §4.5） |
| **R8 体积 −63.90%** | ✅ **1,435,193 B vs 3,975,389 B**（静态实测，两包同轮） | §0.4.3；`R8_ATTEMPT_F11.md` §3.4 | ✅ **静态可复验**（`Get-FileHash` + 字节数） |
| **R8 首屏 644 vs 691 ms** | 🔶 **只快 47 ms（−6.8%），n=3** ⇒ **不构成"明显更快"**，**不支撑改默认推荐**；且此为 `emulator-6290` 同机同轮，**不可与 §4.2 的 1,676 ms（已作废 R2 件）相减** | §0.4.3；`R8_ATTEMPT_F11.md` §5.3 | 🔶 **仅方向性**（n 太小） |

### 0.4 R8：从失败到 −63.90%（F11–F15 这一批的完整交代）

> **一句话**：R8 版**从"连编译都过不去"变成"设备验证核心项全过、体积只有非 R8 的 36.10%"**。
> **但默认推荐仍然是非 R8 版**——**收益几乎全部在体积/分发侧，不在首屏侧**，这条判断三轮（F11/F12/F13）都没变过。

#### 0.4.1 为什么第一次失败（E1 轮，`WEB_RELEASE_BUILD.md` §5）

E1 轮只做了**纯静态评估**，留下三样东西：一份 **12 项风险清单**、一个开关式方案 `-Pwebapp.r8=true`、以及一个**已经编译不过**的事实——`WebHostActivity.kt` 在 R8 编译下有 **7 个 `Unresolved reference`**（`webViewMajor` / `webViewVersionName` / `watchdogArmed` / `showDiagnostic` / `WebViewRenderProcessDetail` / `didCrash`）。⇒ **连包都产不出来**，谈不上验证。

#### 0.4.2 这次靠什么成功（三件事，各有出处）

**① 把 12 项风险逐条映射成 keep 规则，而不是"先把构建弄过再说"。**
落盘在 `webapp/proguard-rules.pro`（F11 整体重写，每条规则标注对应哪一项风险），并**先落盘再动手**。关键映射（F11 §2）：

| 风险 | 为什么会被裁坏 | keep/配置 | 实测 |
|---|---|---|---|
| 🔴 **#7 Tink / `EncryptedSharedPreferences`** | Tink 内部**基于反射**装配 keyset 与 AEAD；裁坏后**不崩**，只触发 `SecretStore.kt:76` 的 `falling back to in-memory only` ⇒ **静默降级** ⇒ 口令不落盘 ⇒ 重启反复要密码 | `proguard-rules.pro:63-66` 整包 keep Tink + `androidx.security.crypto` | ✅ **PASS** |
| **#1/#4 JS 桥 `WebHostBridge`** | `addJavascriptInterface` 是 **native 侧按方法名反射** ⇒ 改名/删方法时 JS 侧**静默拿到 `undefined`**，编译期与单测都测不出 | `:44-46` `@JavascriptInterface <methods>` + `:58` 整包 keep 兜底 | ✅ **PASS** |
| **#2 manifest 组件** | 被裁 ⇒ 启动即崩 | AGP 自动 keep + `:58` | ✅ 冷启 3 次无崩 |
| **#5 宿主工具类** | 反射调用点被裁 | `:58` `-keep class …web.** { *; }` | ✅ mapping 逐个查未改名 |
| **#3 `assets/www/*` 注入** | 被裁 ⇒ IME 抬升/首屏探针全哑 | **正确处置是"不加"**（R8 与 `shrinkResources` 不处理 `assets/`） | ✅ 三个探针注册齐全 |
| **#6/#8/#9 依赖侧** | 库自带 consumer rules | 只补 `:80-81` `-dontwarn`（**只压警告，不改裁剪行为**） | ✅ |
| **#10 `res/` 收缩** | `@string/app_name`/主题/`@mipmap` 被裁 ⇒ 首启配置框缺文案/图标 | **不加 `tools:keep` 兜底**，用**实机截图证伪**代替规则掩盖 | ✅ 截图正常 |
| **#11/#12 反射/序列化** | — | #12 静态闭环（本模块无 `@Serializable`/Gson/Moshi）；#11 🟡 未单独触发 | 🟡 见 §5 |

> **一条总原则钉死**：**没有为了让构建通过而把混淆放宽成"等于没混淆"**——文件里**没有任何** `-dontshrink` / `-dontobfuscate` / `-dontoptimize`。混淆确实生效的证据在 F11 §3.3：依赖类实改（`okhttp3.OkHttpClient -> q0.q`、`AppCompatActivity -> g.l`），`usage.txt` 被删 **21,927** 条，两个 dex（8,745,636 + 960,316 B）压成**单 dex 2,594,228 B**。

**② 把"开关式"改成**独立 `releaseR8` 变体**，从机制上保证非 R8 交付物一字未动。
E1 留的开关式隐患是：同一个输出路径 `outputs/apk/release/webapp-release.apk` 在带不带 `-P` 时**静默变成两份不同的包**——只要有人顺手带上那个开关，**已验收的分发物就被顶替**，而从变体列表上看不出来。F11 改成：

```kotlin
release      { isMinifyEnabled = false; isShrinkResources = false; … }   // 永远是已验收的非 R8
create("releaseR8") { initWith(getByName("release")); isMinifyEnabled = true; isShrinkResources = true }
```

⇒ **`assembleRelease` 仍产出非 R8**（已验收产物零风险）；`releaseR8` 沿用 `applicationIdSuffix = ".debug"` ⇒ 与 release **同 applicationId** ⇒ 可互相覆盖安装，**这正是同机同轮 A/B 需要的**。⚠️ 遗留事实：**`-Pwebapp.r8=true` 现在是 no-op**，仍依赖它的脚本会**静默拿到非 R8 包**（未闭环，见 §5）。
**F11 收尾实测**：非 R8 交付物 `research/deliver/opencode-shell-web-release-nor8.apk` **一个字节都没动**（md5 复核仍 `76CA064B…`）。

**③ 契约测试真跑过，不是静态推断**：`BUILD SUCCESSFUL` + `1 executed`（`build.gradle.kts` 本身是该任务的声明输入，改了就必重跑），**16 suites / 147 tests / 0 failures**，含 `DebugEvalChannelTest` 2/2、`KeepScreenOnWiringTest` 14/14。**未删、未放宽任何断言**，`webapp/src/**` 本轮 **0 个文件被改**。

#### 0.4.3 体积与首屏（**同机同轮**：`emulator-6290`，2026-10-05 10:03–10:14，不跨轮）

| 维度 | 非 R8 `76ca064b…` | R8 `0598817026…` | 差 | 判读 |
|---|---:|---:|---:|---|
| **体积** | 3,975,389 B | **1,435,193 B** | **−2,540,196 B（−63.90%）** | ✅ **R8 只有非 R8 的 36.10%**；收益显著且静态可复验 |
| **首屏中位数（n=3，冷启×3，已鉴权）** | **691 ms**（625/691/665） | **644 ms**（618/612/644） | **−47 ms（−6.8%）** | 🔶 **量级小，不构成"明显更快"**；n=3 不足以定论 |

- 首屏口径 = `M.g1.firstContent t=`（`kind=dcl`），冷启 = `force-stop` + 3 s 停顿，已鉴权，两包**同一台机同一轮**取数。
- ⚠️ **两个首屏数字绝对不许混轴**：`644 / 691 ms` 是 **F11 轮 `emulator-6290` 同机同轮**；§4.2 的 **1,676 ms** 是 **已作废 R2 件 `7b578cee…` + `emulator-5910`**；§4.5 的 **667 ms** 是 **V12 轮 `76ca064b…` + `emulator-6250`**。**三组不同轮、不同包、不同机，彼此不能相减。**

#### 0.4.4 风险逐条验证结果 + **默认推荐为什么仍是非 R8**

**F11–F15 逐条结果**：口令存储（Tink 静默降级）✅ · JS 桥 ✅ · 错误态三态 ✅ · `shrinkResources` ✅ · 注入层与 WebView 特性判定 ✅ · 全程无崩溃 ✅ · **常亮三段 ✅（F12 补置起段 → F13 改真点驱动 → F15 补基线/两回合/空格正文）**。

**但默认推荐仍是 `opencode-shell-web-release-nor8.apk`，理由三条**：
1. **首屏只快 47 ms（−6.8%）**，"改推荐必须有量化理由（如明显更快）"这个门槛**没达到**；n=3 也不足以定论。
2. **收益几乎全部在体积/分发侧**（−63.90%），不在体验侧。
3. F12 明确写过一条**方法论红线**：改默认推荐**必须用同机同轮的数**支撑，而 F12 轮**非 R8 对照侧没跑出来** ⇒ 理由不足。F13 虽然把两包都跑通了"真点 Send + 常亮三段"（**行为一致，R8 未因 minify/shrink 引入差异**），但**那一轮没有首屏对照数** ⇒ 门槛仍未跨过。

⇒ **表述**：`0598817026…` **已设备验证核心项**，可作为**体积敏感场景**（内网分发 / 带宽受限）的候选；**是否提为默认推荐，等产品负责人拍板**（见 §5）。

#### 0.4.5 证据是怎么一步步变硬的（这一段是本节最该读的）

同一个问题——"R8 版的常亮置起到底成不成立"——被四轮**逐层加固**，每一步都写清了自己的口径弱点：

| 轮次 | 怎么驱动的 | 拿到什么 | **当时的弱点（当轮自曝）** |
|---|---|---|---|
| **F11** | 只测**空闲态** | 三段空闲态 `flagSet=false`（**这是正确值**）+ `D1 applyKeepScreenOn` 日志存活 | ❌ **回合进行中那一段根本没验**；`dumpsys window` 三版探针全失败 ⇒ 0x80 位只能靠应用自身日志判读，**缺独立窗口属性佐证** |
| **F12** | ⚠️ **`curl` 打回环代理的 `…/message`**（**不是真点**） | 置起 `flagSet=true` @ `02:31:53.237` + 窗口属性 `fl=KEEP_SCREEN_ON` **16/16** 命中 | ⚠️ 自曝：**不覆盖"手指点官方 UI 发送按钮"这条交互路径**；且**设备端 `base.apk` md5 没取到**（正则被压平污染路径） |
| **F13** | ✅ **真点官方 UI Send 按钮**（`uiautomator` 解析 `text=[Send]` bounds → `input tap` → 2 s 后 `roundActive=true`），**零 `curl` 注入** | 三段齐全；**设备端 `base.apk` md5 = `0598817026…`，`MATCH=True`**；**非 R8 同机同轮也跑通**，行为一致 | ⚠️ **自曝 4 条**：① `SEG0_idle` **标签名不副实**（实际落在回合开始后 0.2 s，**不是真空闲基线**）② `VERDICT` 的 `release` 字段是**脚本 bug**（`Write-Output` 污染管道 ⇒ 数组比较恒 False）③ `input text` 词间**空格丢失** ④ **未测连续两回合** |
| **F15** | ✅ 仍是真点，但**每步单条命令 + 边做边写盘** | **4 条全部闭环**：`SEG0` 挪到 **tap 前 3 ms 纯读取**取样（`11:44:43.229` < tap `11:44:43.285`，**同一次执行里前后相邻的带时间戳日志**）；`VERDICT` bug 定位并修（`Write-Output`→`Write-Host`，**前后 flag 真值都是 `false`，只有比较结果不同** ⇒ 证明是脚本 bug 不是产品行为）；带空格正文**从屏幕读回验证**；**连续两回合 raise/release 均成立、+95 s 仍 false** | ⚠️ 仍如实记：`input text` **打不进 CJK**（`KeyCharacterMap` 只支持 ASCII，**超出派单范围、非被测应用缺陷**）；`_f12_keep.ps1` 等**同源脚本的同一 bug 未修** |

> **F14 中途夭折也记在这里**：F14 起了两回合循环，**轮次在脚本还在跑的时候就结束了** ⇒ **报告没写出来、还留下一个自建模拟器没关**。留下的半份产物按 git 提交信息如实登记（`wip(web): partial output from the cleanup round…`），那 4 条**保持未闭环**，由 F15 重做并闭环。⇒ 教训见 §6.1 ⑯。

---

### 0.5 手机形态：走一遍旅程才发现的两条缺陷，已经闭环（F19 新增）

> **一句话**：这两条缺陷**不是靠任何单项机制测试发现的**，是**像用户那样在手机档（≈360dp）上从冷启动一路走到返回**才撞出来的。V13 走一趟发现 → F18 定位并修 → V14 用同一条路重走一遍，**10/10 PASS**。
> **本节是 F19 的核心新增**：此前所有验证（V8/V12/F11–F15）都在**平板档**上做，**手机档的整条主链从未被走完过**。

#### 0.5.1 发现方式：为什么必须是"走一遍旅程"

| 维度 | 内容 |
|---|---|
| **形态** | 手机档 ≈ **360dp 宽**（`wm size 1080x2340` + `wm density 480` ⇒ `1080/(480/160)=360dp`；回读 `innerW=360/innerH=780/dpr=3`）。**此前所有轮次都在平板档**（1280×800 等） |
| **怎么走** | **像用户那样按顺序走完整条链**：冷启动 → 会话列表 → 进会话 → **真点发送** → 等回复 → 滚动 → 开面板 → 切会话 → **按 BACK 返回**（10 步） |
| **不走什么** | **不做单项机制测试**。不单独去测"inset 有没有算出来"、"BACK 顺序对不对"——**这两样当时都自认为是对的** |
| **被测物** | **最可能分发的 R8 包** `opencode-shell-web-release-r8.apk` |

⇒ **关键反差**：缺陷 A 的根因是 **inset 投递链路两处各断一半**、缺陷 B 的根因是 **BACK 顺序写反**——**这两件事在平板上都是"看起来正常"的**（平板没有状态栏层、不测 BACK）。**只有把 360dp 手机档的整条旅程走一遍，用户真正会踩的那两步才会露出来。**

#### 0.5.2 缺陷 A：顶部压状态栏时钟 / 穿过挖孔 —— ✅ 已闭环

**现象**（V13）：状态栏时钟 `4:22/4:23` 与 `Home`/会话标题**字形重叠**；会话标题文字**从摄像头挖孔条下方穿过**（`1 to 4000` 被黑条横切）。

**根因：是两处各断一半，不是单点**（F18 §2.1，这是本节最该记住的一点）：

| 断点 | 位置 | 说明 |
|---|---|---|
| ① **宿主侧根本没投递 inset** | `InsetsHost.kt:72-74` + `:145` | `init` 里 `requestApplyInsets()` 在 **Activity onCreate 阶段**就触发首次 `publish`；此时页面尚未加载，`evaluateJs` 打在**没有 `window.__ocAndroid` 的文档**上 ⇒ payload 静默丢失。`:145` 的去重判断让这条**静态**读数**永不重发**。**决定性实测（改前）：`getInsets()` 返回 `null`、`--oc-safe-top` 是空串** |
| ② **页面侧也没消费** | 上游 SPA | 即使变量到了，上游也不认 `--oc-safe-top`；`env(safe-area-inset-top)` 因 WebView 边到边**恒为 0**，指望不上 |

> ⚠️ **运行时反证**（排除"宿主没给"这一支）：手动调 `setImeMode()` 触发 `refresh()` 后 `getInsets()` **仍是 `null`** ⇒ 不是"值没算出来"，是"**算出来了但送不到 + 送到了也没人用**"。

**设备读数（改前 → 改后，截图像素级几何实测）**：

| 读数 | **改前 V13** | **改后 V14** |
|---|---|---|
| **左区暗带**（时钟 + 标题） | **`20-92`（连成一片 ⇒ 合并）** | **`20-49`（仅时钟）** + **`114-161`（标题）** —— **分离** |
| **中区暗带**（挖孔所在） | **`48-88`（穿进挖孔行）** | **`48-59`（仅挖孔条）** |
| **重叠** | 时钟带与标题带**合并 ⇒ 重叠** | **重叠 0 px** |
| **净空**（时钟底 → 标题顶） | **0 px** | **`114 − 49 − 1 = 64 px`（≈21 dp）** |
| 标题是否**穿进挖孔** | **穿进** | **不穿**（中区 `48-59` 只有挖孔条本身） |

> 📌 **几何自洽性**：标题带起点（114 px）**严格大于** `safeTop` 折算像素（`24 dp × dpr 3 = 72 px`）⇒ 既不重叠，**也没有把标题压在安全区里**——是"避让正确"，不是"留白过头"。

**修法（两处各补一半，F18 §2.2）**：
- **断点①**：`InsetsHost.kt` 新增 `replay()`（**不去重地**补发最近一次 payload），由 `WebHostActivity` 的 `onPageFinished` 调用（那一刻 `documentElement` 一定存在）。
- **断点②**：`oc-host.js` 注入 `oc-top-inset` 样式，用 `--oc-safe-top-eff = max(var(--oc-safe-top), env(safe-area-inset-top))` 给**应用根容器**（`#root, body>div:first-of-type`）垫 `padding-top`。
  - **为什么垫根容器而不是 `header`**：`#root` 是 `h-dvh` + `border-box`，垫 `padding-top` 后**总高仍是视口高**（不溢出、不出滚动条）；垫在 `header` 上只把它顶下去、下方内容不动，形态更差。

**✅ 平板 1280×800 回归：没搞坏** —— 实测 `overlapPx=0`、`headerTop=24`、`innerH=800` **未被压塌**、**三栏完整**。
（⚠️ 平板档 `safeTop` 也是 24px，属**正确避让**而非多余留白；真正无状态栏的桌面浏览器上 `safeTop=0` ⇒ 位移 0，有绊线守着这一支。）

#### 0.5.3 缺陷 B：面板打开时按 BACK 误退 —— ✅ 已闭环

**现象**（V13，**当时列为 FAIL**）：右侧面板处于打开态时按系统 BACK，**没有只关闭面板，而是把整个会话视图退回会话列表**（丢了会话上下文与滚动位置）。桌面端无系统返回键、用 Esc/点外部关，**不存在此问题**。

**根因：宿主里 BACK 的顺序写反了**（F18 §3.1）：

```kotlin
// 改前 WebHostActivity.installBackHandler
if (webView.canGoBack()) { webView.goBack(); return }   // ← 优先：先退历史
webView.evaluateJavascript("… onBack() …")              // ← 页面自己的 onBack() 排在后面
```

**为什么"没拦住"—— 就是这个顺序**：上游 SPA 用 **History API** 做站内导航，**进会话时已经压了一条历史**。实测佐证：面板打开态 `inSession=true, histLen=**2**` ⇒ **`canGoBack()==true`** ⇒ 直接 `goBack()` 把 SPA 路由退回首屏，**页面 `onBack()` 根本没被问到** ⇒ 面板没关、还退了两级。
> ⚠️ **与最初的猜想不同**：不是"面板是页面内 popover ⇒ WebView 自己没历史可退"，而是"**WebView 确实有历史可退，正因为有，才被优先退掉了**"。

**修法**：把顺序改成 **先问页面 → 页面消费不掉才退历史 → 都不成立则什么都不做**；`pageHandled=true` 时直接 return，绝不走 `goBack()`/`finish()`。页面侧注入层**不自己实现关闭逻辑**，而是**派发一个真实的 `Escape`** ⇒ 走的是面板自己的关闭路径，**与桌面浏览器里逐字一致**，不与上游状态机打架。

> ⚠️ **与本仓铁律"绝不按 BACK(4) 关面板"不冲突**（F18 §3.2 已澄清）：那条纪律针对的是**测试脚本把 BACK 当关闭手段驱动 UI**；而**用户真的按下 BACK 时，期望就是先关掉浮层**。**测试脚本关面板仍应点 `≡`**；修的是**用户真按 BACK 时的路由优先级**。

**改后设备读数（V14 在 **R8 交付包**上取的 logcat 原文）**：

| 动作 | **改后读数** | 判定 |
|---|---|---|
| 面板打开态按 **第 1 次 BACK** | `pageHandled=true (overlay closed), no history pop` ⇒ **面板关、仍停会话视图**（Tab 条/标题/历史/composer 全部完好） | ✅ |
| 再按 **第 2 次 BACK** | `pageHandled=false -> goBack()` + `GET /api/session?limit=5000` ⇒ **才回会话列表** | ✅ |
| 历史深度 | 改前**一次退两级** → 改后**一次一级，粒度正确** | ✅ |

> ✅ **"每次想看'每轮最新数据'都得重启一次"这一历史痛点，不再出现。**

#### 0.5.4 V14 复验结果：同一条路重走，**10/10 PASS**

被测物 = **F18 重建后的 R8 交付包** `5227c2ac67f5a613c32adacdbb7783e0`（1,437,969 B），**装前 `Get-FileHash` = 装后设备侧 `md5sum` = 派单基准 ⇒ MATCH=True**。

| 项 | 结果 |
|---|---|
| **10 步主干** | ✅ **10/10 PASS**（V13 是 9 PASS + 1 FAIL；**第 10 步"返回/后退"由 FAIL 转 PASS**） |
| **冷启动** | ✅ **659 ms**（V13 为 449 ms） |
| **真点发送** | ✅ 真点绿色发送钮 ⇒ `POST /prompt` **`code=200`**（upstream **12 ms**） |
| **等回复** | ✅ 助手消息到位，**5 s** 完成；**无 ANR、无白屏**；`/api/info` 心跳持续 200 |
| **有没有新问题** | ✅ **未观察到**（顶部留白 / 面板关闭后状态 / 切会话 / 滚动 / 平板档 5 项逐一复查均正常） |
| **平板档 spot check** | ✅ 顶部**分离、重叠 0 px**；**两栏布局完好未被压塌**；进会话正常；**平板档 BACK** 正常回退（补上了 F18 自认的未闭环项） |

> **取证方法（值得记）**：R8 包**无 CDP** ⇒ F18 那些 DOM 级读数（`--oc-safe-top` / `getInsets()` / `histLen`）**在 R8 上复现不了**。V14 改用**两条不依赖 CDP 的独立证据链**：**截图像素级几何**（证明缺陷 A 零重叠）+ **logcat 宿主回执**（证明缺陷 B 的 1 次/2 次 BACK）。**结论一致，但取证路径不同**——这是**面向最终交付的 R8 包**的直接证据。
> 另如实记录：全新模拟器首次聚焦 composer 会弹**系统** `Choose input method` 对话框挡住发送钮（首点无 POST），用 `KEYCODE_BACK` 关掉后重发成功；`input text` 空格须写 `%s`。**两者都是取证脚本坑，不是 App 缺陷。**

**出处**：`research/web/E2E_PHONE_V13.md`（走旅程发现）· **`research/web/FIX_F18_PHONE.md`**（根因与修法）· **`research/web/E2E_PHONE_V14.md`**（修后重走 10/10）。
**证据等级**：🟡 **设备运行期观测**（三份都在最终交付的 R8 包上取数；复验仍需重跑设备——见 §4.5）。回归：`:webapp` 159 / 0 失败、`:app` 6136 / 0 失败（F18 §7）。

---

## 1. 为什么是路线 A（决策依据）

| 事实 | 证据 |
|---|---|
| **服务端自己就托管那套 Web UI** | `packages/cli/src/app-assets.ts:22` 指向 `packages/app/dist`；SPA 回落 `packages/cli/src/services/web-ui.ts:12-24,27-33` |
| 路由与资源前缀 | 根路径 `/`，资源前缀 `/_assets/`（无 `/app` 子路径） |
| 运行期 | `GET /` = 200 text/html（免认证）；`/api/info` 无认证 **401**、带 Basic **200 JSON** |
| **上游演进速度** | `origin/v2` 两周 **526 个提交**；会话界面由 `packages/gui-extensions` 承载（`btw/review/context/terminal/file/details/browser`），且编译进 app |
| 结论 | **Kotlin 重写永远追不上；托管官方 UI 才能长期一致** |

**⇒ 由此派生一条贯穿全文的纪律**：**凡"官方界面自己就这么做"的现象，一律不改成"看起来更友好"**（配对页不可见就是典型，§3.3）。

**装配体积对比**（同一代码库）：原生 debug 44,198,253 B · 原生 release 29,393,986 B · 原生 release+R8 17,809,425 B · web debug 5,275,479 B · **web release（非 R8，默认推荐）3,975,389 B** · **web release+R8 1,435,193 B（F11 产出，F16 订正 —— 此前此处写的"未产出"已过期）**。
⚠️ **两个 R8 不是一回事，别混**：`17,809,425 B` 是**原生壳** `:app` 的 R8（早于路线 A 切换时的历史数据）；`1,435,193 B` 是**web 壳** `:webapp` 的 `releaseR8` 变体（现行，见 §0.4）。**两者不可相减、不可并列比较。**

---

## 2. 架构与实现

> 🟥 **UX6（2026-10-05）架构已换代，本节前四条描述的是旧代理架构，作废**。现行架构：**WebView 直连真实后端**（页面与 API 天然同源于后端 origin，CORS 不参与）；**`LoopbackProxy.kt`/`ConnectErrorPage.kt`/`BackendProbe.kt`/`NotConfiguredShell.kt`/`LaunchGuard.kt`/`StableOrigin.kt`/`SetupDialogMetrics.kt` 已删除**；无首启配置框、无灰态、无探测；服务器/口令只在官方 UI「设置 → 服务器」配置（官方 Add server 表单带 Password 字段，`Authorization: Basic base64(opencode:<password>)`，见 `_upstream/packages/app/src/servers/connect/dialog.tsx:149-161` + `runtime/server/api.ts:5-7`）。宿主侧 `SecretStore` 只剩非秘密的 `backendUrl`（启动入口）。F21 安全性质改由结构保障（`DirectConnectAuthBoundaryTest` 钉死）。报告：`research/web/UX6_OFFICIAL_ENTRY.md`。以下为历史描述，保留备查：

- **模块**：`webapp/`（独立 Gradle 子模块，产出第二个 APK，与原生壳并存便于 A/B）；R8 做成 `-Pwebapp.r8=true` 开关（默认关），不新增 buildType →（F11 起为独立 `releaseR8` 变体，开关已删）
- **核心文件**（旧架构）：`WebHostActivity.kt`、`LoopbackProxy.kt`、`ConnectErrorPage.kt`、`BackendProbe.kt`、`InsetsHost.kt`、`WebHostBridge.kt`、`SecretStore.kt`
- **为什么曾需要回环代理**（旧架构）：官方 UI 直连后端会撞 CORS；代理让页面与 API 同源、转发时注入 Basic 头、口令只存 EncryptedSharedPreferences。**UX6 起直连后端本身提供 SPA（`packages/cli/src/services/web-ui.ts` 的 SPA 回落），页面 origin == API origin，CORS 依然不参与，但不再需要代理**。
- **首启流程**（旧架构，已删）：口令为空 ⇒ 弹配置框；有口令 ⇒ `probeThenLaunch()` 先探 `/api/info`。
- ⚠️ **闸门命令**（唯一验收）：`$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'` 后 `.\gradlew.bat --console=plain cleanTestDebugUnitTest testDebugUnitTest assembleDebug :webapp:testDebugUnitTest :webapp:assembleDebug`

---

## 3. 接入健壮性、配对、相机（**本轮新增/改写的三节**）

### 3.1 接入健壮性（错误态）—— 三条路径全 PASS

**这是 F5 建立、F6 修好的一套机制**。核心改动：宿主在加载官方 UI **之前**主动探一次 `/api/info`（`BackendProbe.kt`），按**三态**判定分流。

| 场景 | 用户看到什么 | 判定依据 | 证据 |
|---|---|---|---|
| **正确口令** | ✅ **直接进官方 UI**，无任何对话框 | `probe /api/info -> 200 … authHeaderSent=true` + `F5 probe OK code=200, loading official UI`；`HAS_AUTH_DIALOG=False` | `FIX_F6_PROBE.md` §3.1（`emulator-6170`）· 截图 `_f6_path1_officialui.png` |
| **口令错** | ✅ 标题「**登录没通过**」；正文「服务器拒绝了登录：**用户名或口令不对**。请确认后重试，或重新输入口令。」；三按钮 **返回 / 继续加载 / 重试** | `probe -> 401` + `AUTH FAILED code=401; showing retryable dialog`；`uiautomator` 原文非看图转写 | `V8_VERIFY.md` §3.2 · `FIX_F6_PROBE.md` §3.2 · `FIX_F5.md` §2.4 |
| **后端不可达** | ✅ 「**连不上服务器**」+「请检查服务器地址是否正确、设备是否连上了服务器所在的网络，然后重试。」+ 一个大号 **[重试]** 按钮 | `F5 probe unreachable reason=IOException, loading anyway`（**是 "unreachable" 不是 "AUTH FAILED"** ⇒ 三态确实分开） | `V8_VERIFY.md` §3.1 · `FIX_F6_PROBE.md` §3.3 |

**四个硬要求逐条对账**（派单点名要的）：

1. **不吐裸 JSON** ✅ —— 改前是整页一行 `{"error":"unexpected end of stream on http://127.0.0.1:4095/…"}`（`research/env/v7/v7_n5_noreverse2.png`）；改后是中文标题+说明+按钮。根因是原实现在 `LoopbackProxy.onForward` catch 分支把 `e.message` 原文直接序列化给 WebView；改后**异常原文仍进 logcat，但不印到用户眼前**（"日志保留原文、界面只放分类"这条分界写在 `ConnectErrorPage` 类注释里）。
2. **不泄露内部 URL/端口** ✅ —— `ConnectErrorPage.redact` 把 `scheme://host:port/…` 整段替成 `<已隐藏>`；截图可见文本里**没有任何 host:port**。单测守着 `redact hides scheme url and host port` / `redact also covers bare host port without scheme` / `error page escapes html so detail cannot break out`。
3. **503** ✅ —— `LoopbackProxy.kt:860 writeFriendlyPage` 用 **503 而不是 502**：事实是"我们连不上上游"。API 请求仍回 JSON，但**先 redact**。
4. **`no-store`** ✅ —— `Cache-Control: no-store`，保证重试不被缓存挡。
5. **≥48dp 重试按钮** ✅ —— `ConnectErrorPage.kt:126` 写死 `button{width:100%;min-height:48px;height:48px;…}`，常量 `MIN_TOUCH_TARGET_CSS_PX = 48`；`ConnectErrorPage.kt:40-44` 论证 **48 CSS px ≡ 48dp**（WebView `textZoom=1`、宿主不注入缩放）。单测 `error page retry button meets 48dp hard requirement` 钉住。三个对话框按钮另测得 142 px ≈ **54.1 dp** @420dpi。

**三态判定**（`BackendProbe.kt:122 classify`，纯函数可单测）：`401/403 → AuthFailed` · `2xx/3xx → OK` · 无响应或 `5xx → Unreachable` · 其余 `→ Unknown`。探测超时 `PROBE_TIMEOUT_MS = 8_000L`，三条依据：必须小于代理 `connectTimeout` 15 s（`LoopbackProxy.kt:116`）、必须远大于实测耗时（设备侧 `/api/info` 毫秒级，带凭据探测实测 **31 ms**）、必须短到用户不感知卡住。设备侧实测带凭据探测与匿名探测**吃同一个 8 s 上界**。

**「口令不进日志」= 三处核对的结论**（`FIX_F6_PROBE.md` §5，不是"看一眼觉得没有"）：

| 核对面 | 方法 | 结果 |
|---|---|---|
| **① 日志串** | 单测扫 `BackendProbe.kt` 里每个 `Log.*(...)` 调用体，断言**不含** `$password` 插值；结论行只允许 `passLen=` 长度记法 | ✅ 通过 |
| **② `Result` 类型** | 反射列出 `Result` 全部子类字段名，断言不含 `pass`/`secret` | ✅ 通过 |
| **③ 打包产物** | 扫 release APK 的 `classes.dex` 字节，查字面量 `opencode:opencode` | ❌ 未找到 ⇒ 无硬编码口令 |
| 旁证 | F5 轮机械计数：全量 logcat 中 `wrongpass123` 出现 **0 次**；三份 logcat 里口令一律只以 `passLen=8` 形式出现 | ✅ |

**⇒ 落地口径：日志里只有 `passLen=<长度>` 与 `passwordPresent=true`，从不出现 `Basic <base64>`；错误文案 `HINT_AUTH` 是固定串，不含口令、不含内部 URL**（另有单测守 `HINT_AUTH` 不含"服务器地址"，避免把"改口令"误导成"查网络"）。

**回归**：`:webapp` **147 / 0 失败**（F5 基线 141 ⇒ **+6**）；`:app` **6 136 / 0 失败**。`ConnectRobustnessTest` 13 → 19。**用例数只增不减，未删未放宽任何断言。**

### 3.2 F6 修掉的那个缺陷（**新功能压坏旧行为的教科书案例**）

**V8 轮撞上的（F9 分栏，A5 U3：观测与根因证据等级不同，不要写在同一格里）**：
- 🟡 **运行期观测（需设备复验）**：即使口令**正确**，探测也必然 401。**设备侧**直连同一后端同一组凭据返回 **200**（`STATUS=200` / `NOAUTH_ERR=401`），而 **App 侧**播种了正确口令后拿到的是 `probe /api/info -> 401 … passLen=8 authHeaderSent=true`。⚠️ 这一条**离线不可复验**——静态磁盘上只能读到源报告的转述。
- ✅ **根因（可静态复验）**：下面三行代码 + 唯一请求构造点 `buildProbeRequest` 符号在**交付包 dex 内命中**（A5 §4.4：`buildProbeRequest` = 2 次命中）⇒ **"修法在包里"成立**；但"设备侧直连 200"这一观测仍需重跑设备才能复验。

**根因（`BackendProbe.kt` 两处）**：
- `probe()` 在 `:139-148` 造了**带** `Authorization` 的 `builder`；
- 但 `probeAsync()` 在 `:167-173` 为了让 `cancel()` 拿到在途 `Call`，**又造了一条匿名 `Request`**，并在 `:173` 把它传进 `probe()`；
- `probe()` 的 `:148` 是 `call ?: httpClient.newCall(builder.build())` ⇒ **`?:` 左边恒命中，右边那个带凭据的 builder 被整份丢弃、永不执行**。
⇒ **每一次探测都是匿名请求** ⇒ 后端一律 401 ⇒ 正确口令的用户被告知"用户名或口令不对"。

**为什么旧日志具有欺骗性**：`authHeaderSent=${!password.isNullOrEmpty()}` 只是 `if` 分支的**局部布尔值**，表达的是"如果有口令就打算加头"，**不是"真的加了头"**。所以即使线上 401，日志照样打 `authHeaderSent=true` —— 排障时被这条日志带偏过。
⇒ **F6 一并把它改成读实际请求**：`call0.request().header("Authorization") != null`，让"打算加头但没加"在日志里**立刻可见**。

**修法**：抽出**唯一请求构造点** `buildProbeRequest(backend, password)`（认证头写死在里面），`probeAsync` 与 `probe()` 都只能从它拿请求 ⇒ **匿名分支在结构上不复存在**。外加两处支撑：`android.util.Base64` → `java.util.Base64`（前者是 framework 桩实现，**在 JVM 单测里抛 `RuntimeException("Stub!")`** ⇒ 换掉之后"探测是否带 Basic"这条断言才能在单测里守；`minSdk=26` 满足 `java.util.Base64` 的可用性——**这不是可选项，是让绊线成立的前提**）。

**这一格曾被判成"界面 PASS / 判定逻辑 FAIL"**：V8 §0.3 的原话是——文案与三个按钮都对，**但触发条件是错的**（不是"口令错时出现"，而是"无论口令对错都出现"）⇒ **只能判「界面 PASS / 判定逻辑 FAIL」，不能整体记"通过"**。F6 修完后，V6/F6 各自拿到 `-> 200` + `loading official UI` 才升级为整体 PASS。

### 3.3 配对 / 扫码路径的定性 —— ✅ **上游行为（我们一致）**

**结论（二选一，必须选一个）：上游行为，我们在 WebView 壳里没有引入偏差，因此不是我们的缺陷。**

**判定依据（四段代码，全带行号）**：

| 环节 | 位置与原文 | 推论 |
|---|---|---|
| **① 播种点** | `_upstream/packages/app/src/runtime/platform/web.ts:55-64`，生产构建走 **`:63 return location.origin`**，恒非空 | 唯一能让它变 `undefined` 的开关是 `:56` 的 `VITE_OPENCODE_SERVER_MODE === "none"` |
| **② 注入口** | `entry.tsx:80-89, 94-98`，`:97 servers={server ? [server] : []}` | `servers` prop 在生产构建下**至少含 1 项** |
| **③ registry 合并** | `runtime/server/registry.tsx:213-215` `allServers = resolveServerList({ stored: store.list, props: props.servers })`；`resolveServerList` 在 `:101-123` 取**并集**；getter 在 `:255-257` | 只要 `props.servers` 非空，`servers.list.length` **就 ≥ 1** |
| **④ 判定点** | `shell/routes/routes.tsx:76-89`，`:79` `<Show when={servers.list.length > 0} fallback={<ConnectServerScreen />}>` | **`ConnectServerScreen` 是 `fallback`，只有 registry 为空才渲染 ⇒ 永远不渲染** |

**关键判据（回答"是不是我们的问题"）**：**桌面浏览器直连同一个后端，会完全一样。** 触发条件由 `entry.tsx:97` 决定，而该 prop 在**任何** web 构建下都由 `web.ts:63` 的 `location.origin` 填满；且这段判定**不在平台抽象之下** —— `routes.tsx` 是**共享路由层**，桌面浏览器打开同一个后端走的是同一份 bundle、同一个 `AppLayout`、同一个 `<Show>`。
⇒ 正向表述：**"一个已经配置好服务器的客户端，不会再看到配对页"是 opencode web 客户端的固有属性。** 设备侧佐证：`emulator-6150` 冷启动到进 Home 全程，`ConnectServerScreen` / `connect-server` / `server-connect-title` **0 命中**。
另：`servers/connect/screen.tsx:18-19, 44, 134-149` 显示 connect screen 本身**是健康的**（完整手填表单 + 扫码入口）—— **它没坏，只是"够不着"。**

**"需要时如何进入配对"（按上游机制，不臆造）**：
- **方式 A（推荐、唯一干净）**：用 `VITE_OPENCODE_SERVER_MODE=none` 构建上游前端。置 `none` 后 `web.ts:56` 返回 `undefined` ⇒ `entry.tsx:97` 的 `servers` 变成 `[]` ⇒ `registry.tsx:214` 合并后本地存储为空时 `list.length === 0` ⇒ `routes.tsx:79` 的 `fallback` 命中。**这正是配对页被设计出来的场景**（手填地址 / 扫 `opencode pair` 码）。
- **方式 B（等价于 A 的运行态）**：换一个新的客户端身份（另一台设备 / 另一个 WebView 数据目录）**且**用 `none` 模式构建的包。
- ⚠️ **常见误解纠正**：「清空浏览器/localStorage 就能看到配对页」**在默认 web 构建下不成立** —— `props.servers` 那一项来自 `location.origin`，与本地存储无关，**清空了 `store.list`，`length` 仍 ≥ 1**。要进配对页**必须**让构建侧的 `currentServerUrl` 为空。
- 已配置客户端的服务器增删走 **Settings → Servers**，**不是**配对页。

**"不改上游行为"是有意为之（一致性优先）**：强行让已配置的客户端再看到配对页，恰恰是**制造**与官方的不一致。若产品侧确实需要"已配置客户端里也能扫码换服务器"，那是**上游特性缺口**，应向上游提 issue 或在 Settings 里加入口，**不建议我们在壳里私自改上游路由判定**。

> ⚠️ **取证口径声明（F9 已按 A5 统一，见 §9 的权威口径）**：`PAIRING_ROUTE_VERDICT.md` §6 写的是「`origin/v2` 这个 ref **在本 clone（主仓）**里不存在」（`git rev-parse --verify origin/v2` → `fatal: Needed a single revision`）——**这句对主仓成立，但对 `_upstream/` 不成立**：`_upstream` 是独立 clone，其中 **`origin/v2` 明确存在**（`ecfa884ff82e9116e475b71a0c99383b8438d710`）。上述行号全部取自 **`_upstream/` 工作区检出**。**但因果链与"哪个 git ref"无关**，换 ref 不改变定性。

### 3.4 相机能力的现状 —— **两件事，必须分清**

> **包内权限全表（F9 补，A5 §3.1）—— `aapt dump badging` 实读，共 4 条，无遗漏**：
>
> | 权限 | 性质 | 用途 |
> |---|---|---|
> | `android.permission.INTERNET` | 普通 | 访问后端（**UX6 起 WebView 直连**；旧架构经应用内回环代理，代理已删） |
> | `android.permission.CAMERA` | **敏感** | 取流 / 扫码（见本节） |
> | `android.permission.POST_NOTIFICATIONS` | **运行时权限（Android 13+ 需用户授权）** | 通知渠道（代理事件推送）；⚠️ **本文件此前全文 0 次提及它**——不敏感，但"我已列全权限"的人会**漏掉一条** |
> | `…web.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | 无运行时风险 | AGP 自生成的内部自定义权限 |
>
> ⇒ **`CAMERA` 是包内唯一敏感权限**；`uses-feature` 侧 `android.hardware.camera` 声明为 **`required="false"`**（`uses-feature-not-required`）⇒ 无相机也能装。debug 变体 `opencode-shell-web.apk` **权限面与 release 完全相同**（多一个 `application-debuggable`）。

> **❗ 别把这两件事混成一句。**

**第一件：能力在宿主里 —— F3 用调试注入取到过 live 流（成立，未被推翻）。**
- `FIX_F3.md` 记：`gUM=OK label=camera2 0, facing back state=live`。
- **但 F3 自述**：取流探针走 **`oc_debug_eval` 调试注入通道，仅 DEBUG 构建**（`FIX_F3.md:195`）；探针脚本 `research/env/f3/camera_probe.js:4-34` **自建 `<video>` 挂到 `document.body`**，**不是在扫码组件里**。
- **F3 明确写下的未验项**：**"未验的一步：没有真的走到 `PairingScanner` 组件去扫一个配对二维码……本轮验的是取流这一层"**（`FIX_F3.md:222`）；走到 `PairingScanner` 需上游 UI 进 `servers/connect` 并出示特定配对二维码，**未构造该场景**（`FIX_F3.md:379`）。
- V7 §1.5 的结论：**取流那一层本身没有被推翻，也没有反证**；只只能说"**在交付用的 release 包上，这条证据不可复现**"。

**第二件：用户可达路径未打通（在 release 交付包上）—— 三条退路全断。**
1. **没有调试注入通道**：release 包 `BuildConfig.DEBUG=false` ⇒ `WebHostActivity.kt:725` `if (!BuildConfig.DEBUG) return` ⇒ `oc_debug_eval` 编译关闭。⚠️ **陷阱**：dex 里能搜到 `oc_debug_eval` 字符串常量，**容易误判成"通道可用"**。两条独立反证：全量 logcat（2,104 行）里 `OcWebHost/Eval` **零命中**；`run-as` 报 **`package not debuggable`**。
2. **没有 CDP**：全仓 `WebView.setWebContentsDebuggingEnabled` **零调用** ⇒ 无 `webview_devtools_remote_*` socket，无法注入 JS、无法 `Page.navigate`。
3. **没有 deep link**：manifest 只注册 `MAIN/LAUNCHER`（`AndroidManifest.xml:58-61`）；`STATE_URL="oc_web_url"`（`WebHostActivity.kt:69`）**只写不读**（写于 `:792`，`:391` 只从 `savedInstanceState` 取，**没有任何 intent extra 能指定 URL**）；`loadUrl` 的 origin 恒为 `LoopbackProxy.publicOrigin()`。
4. **根因仍是 §3.3 那条**：唯一会渲染扫码入口的页面是 connect screen，而它是 `fallback` ⇒ 真机 UI 上到不了。

**第三件（这属上游 connect 页的 fallback 行为，不是壳侧缺陷）**：见 §3.3 全文。

**因此，当前对相机的准确表述是**：
> **相机权限与取流能力在宿主侧具备（F3 在 DEBUG 构建上取到过 live 流；V6 实测设备端 `CAMERA: granted=true`、`uses-feature` 声明齐全）；但"用户从 App 里点进扫码"这条路径在 release 交付包上未打通，且打不通的原因是上游 connect 页的 fallback 语义（我们与官方一致）。三件分开记，不要合并。**

**V7 固化的可复现脚本**：`research/env/camera_route.ps1`（12,490 B，7 个带断言的 STEP，卡在 **STEP 5 → STEP 7**）。
🔴 **V7 明确标注的最大残留不确定性**：「**左边缘抽屉 → Settings → Add server → "Scan QR code"**」这条**唯一理论可达路径**是「**未测到**」而不是「**已排除**」—— 探测在设备掉线时中断，**不能声称它不存在**。**下一轮必须串行跑（同一时刻只开一个 adb 客户端）重试这条。**
**要真正跑通，需要其一**：出一个 **DEBUG 构建**用于验收；**或**给 release 加一个**受控**的非 DEBUG 注入/深链钩子；**或**让 registry 可空。**需产品负责人决定。**

### 3.5 回合常亮 —— 从"从未生效"到"置起 + 释放"

| 阶段 | 状态 | 证据 |
|---|---|---|
| **V4 之前** | ❌ **死代码，功能从未生效**：`WebHostBridge.setKeepScreenOn` **零个生产调用点**；`PowerPolicy.kt:23` 的注释**自认是死代码**；`onPause` 无防御性 `clearFlags` | `FIX_D1.md` §1.2/§1.4 |
| **D1** | ✅ **置起**：`applyKeepScreenOn` 成为**唯一 flag 写入口**（`shouldKeepScreenOn(foreground, roundActive)`），回合状态由代理喂宿主（新增 `RoundActivityTracker`），加 90 s 兜底 | `FIX_D1.md` §2/§3.4（设备原文 `0 → 1 → 0`） |
| **D1 阶段 3** | 🔴 **兜底差点失效**：官方 UI **常驻**一条 `GET /event`，每 10 s 心跳把 `lastActivityAtMs` 顶住 ⇒ **90 s 空闲超时永不到期**。取舍原文：「超时的失败方向是"屏幕多灭一次"（无害），无限期的失败方向是"屏幕永远亮着"（耗电大户）。**宁可多灭，不可常亮。**」 | `FIX_D1.md` §3.3 |
| **V5 抓到回归** | ❌ **"回合正常结束 ⇒ 释放"不成立**：置起 ✅（`20:11:01 flagSet=true`）、回合 4 s 正常结束 ✅、释放 ❌ —— **全程没有任何一条 `roundActive=false` 的释放行**；`+90s / +180s / +270s` **三次兜底复查全部读到 `roundActive=true` 并重新挂上**；回合结束 ~4 min 后 `dumpsys window` **仍是 1 命中**。五种办法全试过全失败（真实点击/等 35 s/等 90 s/离开会话断 EventSource/等 30 min 天花板**未等到**） | `RELEASE_VERIFY_V5.md` §6.1–§6.5（`emulator-6030`，包 `b063148d…`） |
| **F4 修** | ✅ 在**字节层**认 `session.status`→`idle` / `session.idle`（窗口 `SSE_SCAN_WINDOW_CHARS = 2048`），**并且把心跳与回合数据分开：心跳不续命** ⇒ 90 s 兜底重新变成一条**真正可达**的安全网 | `FIX_F4_KEEPSCREEN.md` §4 |
| **V6 独立复现 ✅** | 置起 `21:09:10 flagSet=true` → 释放 `21:09:53 flagSet=false`（+42.7 s）→ `+90 s` 落点仍 `roundActive=false flagSet=false` ⇒ **F4 改前那种"兜底又挂一次"不复发**；采样到 **T+235 s**（释放后 +192 s）全程 `WINDOW_FLAG=MISS` | `RELEASE_VERIFY_V6.md` §5.2/§5.3（`emulator-6090`，包 `bc7f3018…`） |
| **V8 第三轮复现 ✅** | 官方 UI 真发一个回合，置起/释放/兜底三段齐全；回合结束 `+90 s` 落点 = `+121 s` 时 `dumpsys window` 无 `KEEP_SCREEN_ON` | `V8_VERIFY.md` §5（`emulator-6150`，包 `627f8ed8…`） |

**⚠️ 必须一起读的三条诚实标注**：
1. **F4 §5.4 的机制归因**：「设备上实际生效的是"**心跳不再续命 ⇒ 既有 35 s 判据首次可达**"；我新增的 `ROUND_END` 标记快路径**未被观测到触发**。」⇒ **"标记快路径已验证生效"这句话不成立**；释放延迟约 +42~59 s，**不是"回合结束的即刻"**。F4 §10 把它列为未闭环第 1 条。
2. **30 min 硬天花板仍未在设备上验证**（V5 §6.5 明确"未等到"，D1 §3.4、F4 §10 沿用此未闭环）。
3. **结论只对被验的那几份包成立**（`b063148d…` ❌ / `bc7f3018…` ✅ / `627f8ed8…` ✅ / `76ca064b…` **未单独验**），且**只用宿主 `D1 applyKeepScreenOn` 日志这一路信源**（裂缝 ④）。

**离开前台 ⇒ 释放 ✅**（F4 §6）：回合中按 HOME，**4 s 内 `HIT → MISS`**；释放走 `onPause` 的无条件 `clearFlags`，**那条路径不打印 `D1` 行** ⇒ **没日志 ≠ 没释放**。
**回归**：F4 恢复后 `:webapp` **128 / 0 失败**（新增 `RoundStreamSignalReleaseTest` 13 例）；⚠️ `:app:testDebugUnitTest` **F4 轮未跑**（未改 `app/**`），F6 轮才补跑（6 136 / 0）。

---

## 4. 量化结果（**同机 / 跨机必须分清**）

### 4.1 输入延迟（三层分离实验，同一台设备、同一几何、每层 n=240）

> ⚠️ **口径块（F9 补，A5 §6.2 / C7）—— 本节数字不是取自当前交付包，先读这段再引用**：
>
> | 口径项 | 本节实际取值 | 来源 |
> |---|---|---|
> | 轮次 | **B1 轮**（2026-10-05） | `research/perf/LATENCY_FLOOR_B1.md` |
> | 设备 | **`emulator-5854`** / AVD `OC_Perf_Test` / 端口 5854（**≠ §4.3 的 `emulator-6230`**） | B1 §0 |
> | web APK | **`8DCFFC57…`（8,636,901 B）= §0.1 已列作废的 debug 包** | B1 §0 |
> | native APK | `BD21FF21…`（= 现存 `deliver/opencode-shell-debug.apk`） | B1 §0 |
> | 样本 | **n=240 = 两轮各 120 合并**（B1 摘要句写"每层 n=120"、表格写 240，是同一件事的两种说法，**A5 C8 判为源报告内部表述不一致，不影响 240 这个合并值**） | B1 §一 / §5 / §8 |
>
> ⇒ **本节的"三层分离"结论方向可信，但每一个数字都取自已作废的 `8DCFFC57…` 与另一台设备 `emulator-5854`。**
> ⇒ **禁止**把本节数字与 §4.3（`76ca064b…` / `emulator-6230`）或任何 V9/F4/P5 轮**放在同一张表里横比**——那正是 §6.1 ⑧ 自己立的规矩（「对比数字必须同轮、同参数、同设备指纹」）在 §4.1 被自己破掉。
> ⇒ **若要交付包口径的输入延迟，必须在 `76ca064b…` 上重测**（本轮纯文档，未重测）。

| 层 | 被测物 | p50 | p90 |
|---|---|---|---|
| **L0** | **同一个 WebView 宿主里的极简 `<textarea>`** | **24.68 ms** | **29.22 ms** |
| **L1** | **官方 UI 的 composer** | **30.73 ms** | **62.10 ms** |
| **L2** | **原生壳的输入框** | **26.40 ms** | **32.71 ms** |

| 差 | p50 | p90 | 含义 |
|---|---|---|---|
| **L1 − L0** | **+6.05 ms** | **+32.88 ms** | ⇒ **代价来自"官方 UI 自己"**（受控输入 + 每次按键重渲染） |
| **L0 − L2** | **−1.72 ms** | **−3.49 ms** | ⇒ **WebView 基线并不慢，p50 还比原生略快** |

**判定：`可修（需改上游）`** ⇒ **剩余差距 = 使用官方 UI 的票价，不是壳侧缺陷**；**"输入条走原生"的混合方案对 p50 并无必要**；要压 p90 只能改上游的输入/渲染方式，**而按规矩不许改上游**。
⚠️ **F9 加的强度限定（A5）**：这个判定**方向可信**（L1−L0 与 L0−L2 两条差的**相对**关系来自同一轮同设备，可比），但**具体毫秒数取自已作废的 `8DCFFC57…`** ⇒ **不要把 `+6.05 ms` / `+32.88 ms` 当作当前交付包的输入延迟代价向 Lead 承诺**。
**⇒ 若产品负责人仍要求压 p90，那需要改上游，与"不改上游"的约束冲突，需拍板。**（前情：P5 测得"我们代码在输入路径上无重活"——`longtask n=0`、输入处理器 p90 仅 0.1 ms——依然成立。**P5 同样是 `8DCFFC57…` 那批，与 §4.1 同源，不构成独立佐证。**）

### 4.2 其它量

> ⚠️ **口径块（F9 补，A5 §6.3 / C6）—— 本表三行分属三个不同包/轮次，彼此不可横比**：
> | 行 | 取值来源构建 | 设备 | 可用性 |
> |---|---|---|---|
> | **首屏 1,676 ms** | **已作废 R2 件 `7b578cee…`（3,961,217 B）** | **`emulator-5910`** | 🟡 **仅方向性**——**不是当前交付包 `76ca064b…` 的首屏** |
> | 帧时 | 更早一轮（早于 V9/V11） | — | 🟡 **仅方向性**（文档已自降级，见下） |
> | 耗电 | — | — | ❌ **测不到**（无真机，见 §5 ①） |
>
> **⚠️ 文件名陷阱（A5 §9）**：`opencode-shell-web-release-nor8.apk` **这个文件名对应过至少两个不同字节体** —— `7b578cee…`（3,961,217 B，已作废）vs `76ca064b…`（3,975,389 B，现行交付包）。**引用 APK 必须带 md5，只写文件名就是混轴的邀请。**

| 量 | 值 | 备注 |
|---|---|---|
| 首屏 | **5 次冷启中位数 1,676 ms**（UI 可用标记口径）⚠️ **验于 `emulator-5910` + 已作废 R2 件 `7b578cee…`，非交付包** | 与原生相当（方向性）；⚠️ 早期报过的 `≥15,395 ms` **是量错**（量成了"最后一个 `_assets` GET"）。⚠️ **V9 轮另报的 `7,252 ms` 已作废**（未鉴权 + harness 开销，见 §4.4-④）。**⇒ 如需交付包首屏，须在 `76ca064b…` 上重测；本轮未重测** |
| 帧时 | Web 28–31 ms vs 原生 17/26 ms | **87% 是模拟器地板**；跨轮/跨机**只有方向可信**（与首屏**不同轮不同包，不相减、不混轴**） |
| 耗电 | ❌ **测不到** | 模拟器无电池模型/无 rail 电流 ⇒ **只能真机**（§5 ①） |

### 4.3 形态与切换点（**已鉴权重测**）—— **两个断点：约 768dp 与约 1024dp**

> **口径声明（先钉死，防跨轮串用）**：
> **本节每一格 = `opencode-shell-web-release-nor8.apk`（md5 `76ca064b7de9246ee446f765a9198b37`，3,975,389 B）+ `emulator-6230`（AVD `OC_Deliver_R2`）+ 2026-10-05 这一天这一轮 + 已鉴权（`F5 probe OK code=200, loading official UI`）。**
> **本节不与 V9 / F4 / P5 / B1 的任何数字同轴**；V9 那批已作废（§4.4）。
> 出处：`research/perf/FORMFACTOR_V11.md`（下引 §号 = V11 报告自身的节号）。
> **复验入口（F9 补，A5 U4）**：本节 9 格与 IME 重叠量**不可离线复算**（依赖 `emulator-6230` 的截图与 `dumpsys window` 原始行），但**可重跑**。脚本在磁盘上：**`research/perf/_v11_sweep.ps1`（9,622 B）**；9 格判读凭据：**`research/perf/out_v11/tablet1366x768.png`（83,303 B，在磁盘）**。⚠️ 本节结论**只能重跑设备复验**，静态命令无法确认。

**每格先过 4 条前置断言（9/9 全过，否则该格作废）**（V11 §〇）：

| # | 断言 | 判据 |
|---|---|---|
| 1 | **已鉴权** | logcat 出现 `F5 probe OK code=200, loading official UI`（证明 `/api/info` 用**正确口令**返回 200，不是 401）。⚠️ **静态检索请勿搜整串**（A5 §4.4：该串在代码里是**两段拼接的运行时模板**，dex 里搜整串 = **0 命中**）——搜 `F5 probe OK code=`（命中 1）+ `, loading official UI`（命中 1）两段 |
| 2 | **数据真的到了** | logcat 出现 `GET http://127.0.0.1:4095/api/session?limit=5000&order=desc&parentID=null`（骨架屏状态下不会有这一行） |
| 3 | **无认证失败残留** | 本次冷启后 `AUTH FAILED` 计数 = **0** |
| 4 | **不是空白屏** | 截图 inkPct（≠白像素占比）≥ **1.5%** |

#### ⚠️ 更正：**「≥768dp = 桌面分支」只是第一档，那样写是不完整的**

此前只写"`<768` 移动分支 / `≥768` 桌面分支"，**漏了桌面分支内部还有第二个切换点**。准确说法是**两个切换点**：

| 切换点 | 区间 | 变化（实测） |
|---|---|---|
| **① 约 768dp** | `<768` ↔ `768–1023` | **移动分支 → 桌面分支**：`<768` 是标题栏 `Home` + 首行 `All projects`（**可折叠下拉**，带 `⌄`），**无 `Add project`、无 `Settings`/`Help`、无底部栏**，导航靠标题栏图标 + 抽屉；`768–1023` 首行变成 `Projects` **区块标题** + `Add project`（右上角图标），**底部栏**左 `Settings` / 右 `Help` |
| **② 约 1024dp** | `1024` ↔ `>1024` | **左栏出现，`Settings`·`Help` 移进左栏**：`1000dp` 仍是 `Projects` 区块 + **底部栏** Settings/Help、**无左栏**；`1024dp` 变成**左侧独立栏**（`Projects` + `Add project`），`Settings`/`Help` **移进左栏底部**、**不再有底部栏** |

⇒ **第二切换点夹逼到 1000–1024dp，落在区间 (1000, 1024]，断点 = 1024**，与上游 Tailwind 断点 `lg = 64rem = 1024px` **完全吻合**（V11 §4.2）。
**代码侧只读印证**（V11 §4.2，未改任何代码）：`_upstream/packages/app/…/home/route.tsx:43` `max-md:grid-rows-[…] lg:grid-cols-[280px_minmax(0,720px)]`（**≥1024 才有 280px 左栏**）、`:52` `hidden … md:block lg:hidden`（**768–1023 才把 Settings/Help 放底部栏**）、`home/projects/view.tsx:290` `mb-8 mt-4 hidden shrink-0 lg:flex`；移动/桌面主切换是 `767/768` 一对（`shell/shell.tsx:26` `(max-width: 767px)` vs `session/…` 的 `(min-width: 768px)`）。上游 Tailwind 刻度 `sm 40rem=640 / md 48rem=768 / lg 64rem=1024 / xl 80rem=1280`（`_upstream/packages/ui/src/styles/theme.css:25-28`）。

**1366×768 的左栏有实证**（V11 §4.1）：截图 `out_v11/tablet1366x768.png`（83,303 B）上肉眼可读左侧一列 `Projects → Add project → Settings → Help`，会话列表整体右移、`Settings`/`Help` **不在底部**；像素侧佐证：左栏带 inkPct **0.87**、底栏带 4.71（**底栏墨量来自系统 Taskbar 导航条，不是 Settings/Help**）。

#### 逐格形态谱（9 格，全部 `401=0`、`apiGET≥10`、inkPct 1.63–6.10）

| 几何 | 分支 | Projects 呈现 | `Add project` | `Settings` / `Help` | 左栏 |
|---|---|---|---|---|---|
| **360dp** `720x1280@320` | 移动 | `All projects`（下拉带 `⌄`） | ❌ 无 | ❌ **都没有** | — |
| **412dp** `824x1280@320` | 移动 | `All projects`（下拉） | ❌ | ❌ 都没有 | — |
| **442dp** `884x1280@320` | 移动 | `All projects`（下拉） | ❌ | ❌ 都没有 | — |
| **708dp** `1416x1280@320` | 移动 | `All projects`（下拉） | ❌ | ❌ 都没有 | — |
| **700dp**（<768）`1400x1280@320` | 移动 | `Home` + `All projects`（下拉） | ❌ | ❌ **都没有** | — |
| **820dp**（≥768）`1640x1280@320` | **桌面** | `Projects` **区块标题** | ✅ 右上角图标 | ✅ **底部栏**：左 Settings / 右 Help | ❌ 无 |
| **1000dp**（探针）`2000x1280@320` | 桌面 | `Projects` 区块标题 | ✅ 右上 | ✅ 底部栏 | ❌ **无** |
| **1024dp**（探针）`2048x1280@320` | 桌面 | `Projects`（**左栏**） | ✅ 左栏 | ✅ **移进左栏底部** | ✅ **有** |
| **1366×768** `1366x768@160` | 桌面 | `Projects`（**左栏**） | ✅ 左栏 | ✅ **左栏内** | ✅ **有** |

**每格都读到了真实会话**（不是骨架屏）：`Counting from 1 to 4000, one number per line / opencode-android-shell`、`Friendly greeting / DSH 临时会话`、`android-e2e / System32`、`DRAFT_OK / chenjieyan_test`、`DSH插件致后端监听地址报错修复 / DSH-Desktop` 等（V11 §二）。**逐格标题栏的 `Home` 字样：移动分支有，桌面分支无**（V11 §二表"无 `Home` 字样"）。

#### IME 重叠量（逐格；**每格都先断言 `mInputShown=true` 才读数，9/9 通过**）

| 几何 | `type=ime frame=` 原始行（`dumpsys window`） | `contentTopInsets` | `windowBoundsTop` | contentBottom | **重叠(窗口口径)** | **重叠(输入框口径)** |
|---|---|---|---|---|---|---|
| 360dp | `…frame=[0,1184][720,1280]… visible=true` | 1136 | 48 | 1184 | **0 px** | **0 px** |
| 412dp | `…frame=[0,1184][824,1280]…` | 1136 | 48 | 1184 | **0 px** | **0 px** |
| 442dp | `…frame=[0,1184][884,1280]…` | 1136 | 48 | 1184 | **0 px** | **0 px** |
| 708dp | `…frame=[0,1184][1416,1280]…` | 1136 | 48 | 1184 | **0 px** | **0 px** |
| 700dp | `…frame=[0,1184][1400,1280]…` | 1136 | 48 | 1184 | **0 px** | **0 px** |
| 820dp | `…frame=[0,1184][1640,1280]…` | 1136 | 48 | 1184 | **0 px** | **0 px** |
| 1000dp | `…frame=[0,1184][2000,1280]…` | 1136 | 48 | 1184 | **0 px** | **0 px** |
| 1024dp | `…frame=[0,1184][2048,1280]…` | 1136 | 48 | 1184 | **0 px** | **0 px** |
| 1366×768 | `…frame=[0,720][1366,768]… visible=true` | 696 | 24 | 720 | **0 px** | **0 px** |

**两个口径都报**（V11 §三）：**窗口口径** = `max(0, contentBottom − imeTop)`，`contentBottom = windowBoundsTop + contentTopInsets`（例 360dp：`48+1136=1184`，`imeTop=1184` ⇒ 0 px ⇒ 窗口已完整缩到 IME 之上）；**输入框口径** = `max(0, 聚焦的 `Search sessions` 输入框底边 − imeTop)`，底边由 IME 弹起后的截图重定位得到（例 1366×768：底边 `y=97`，+6 容差 = 103，`imeTop=720` ⇒ 0 px）⇒ **不只是窗口，连被聚焦的输入框本身也没被 IME 压住**。
**⇒ 9/9 格重叠 = 0 px，符合预期，IME 这一格无一格需要写"测不到"。**（V9 那批 6 格全是 `frame=[0,0][0,0] visible=false` / `mServedView=null`，因为 IME 根本没被服务给 WebView —— 见 §4.4。）

#### 测不到的量（写"测不到 + 原因"，**不写"通过"**）

| 量 | 状态 | 原因（可复核） |
|---|---|---|
| **CDP 节点数 / 关键选择器** | ❌ **9/9 测不到**（不是通过，是**通道不存在**） | 本包是 `:webapp` 模块，`webapp/src/**` 对 `setWebContentsDebuggingEnabled` **零调用**（全仓只有 `:app` 的 `MainActivity.kt:466`，用 `BuildConfig.DEBUG` 做参数）⇒ WebView 不开 `webview_devtools_remote_*`，`DEVTOOLS_SOCKETS=` 空。**要拿 CDP 数字必须先在 `WebHostActivity` 加开关（属改代码，超本轮权限，未动）** |
| **uiautomator 页内文本** | ❌ **读不到** | `XML_LEN=788 / NODE_COUNT=2 / NONEMPTY_TEXT_COUNT=0 / CLS=android.webkit.WebView` ⇒ 只看得到一个壳节点。⇒ 本节形态判定改由**像素 + 人工读图 + 后端日志**三条证据支撑，**不是** `querySelector` 命中（口径已降级并标注） |
| **会话消息 composer 的 IME 重叠** | ✅ **内容级 0 px（F10 已补测，交付包上）** | F10 轮（`emulator-6270`，md5 `76ca064b…`）：进真实会话 → 聚焦 composer → 断言 `mInputShown=true` → 键盘顶边 `y=1474`（`dumpsys` frame + `touchable region` + 截图像素扫描三重互证）→ composer 内容最低节点 `y=1422` ⇒ **重叠 0 px，余量 52 px**。⚠️ **窗口级 189 px ≠ 内容级**，见 §4.5 与 `research/perf/IME_CONTENT_OVERLAP_F10.md` |
| **每格重复次数** | ⚠️ **单次采样** | 每格只冷启 1 次（未取中位数），inkPct 波动未评估（V11 §八-6） |
| **真实折叠/横屏/多窗口** | ⚠️ **未测** | 折叠档（442/708）是用 `wm` 覆盖模拟**宽度**，不是真机折叠态（无半折 posture）；横屏/多窗口未测（V11 §八-3） |
| **412dp 与 V10 的同轴性** | ⚠️ **不严格同轴** | 本轮 412dp 用 `824x1280@320`（压到 1280 规避静默退出），V10 用 `824x1640@320`（高 1640 > 物理 1600）⇒ **宽度同、高度不同**，仅形态分支结论可比（V11 §八-8） |

**另注**：原始行取自 `dumpsys window`，**不是** `dumpsys window insets` —— 后者在本 build 返回 `Bad window command, or no windows match: insets`，是**无效子命令**（V11 §一-④）。

### 4.4 被作废的一批数据（**含原因**）

> **一句话**：**V9（`research/perf/REGRESSION_V9.md`）那批形态/性能数据整体作废**。原因**不是"测得不准"，而是"测的时候被测系统根本没进入可用状态"** —— ① **未鉴权（后端 401）**；② **切完几何立刻取证**，而骨架屏 ~2.2 s 才出现、数据 ~2.4–2.6 s 才到。**两个测量缺陷叠加**，把"根本没数据"伪造成了"小屏渲染不出来"。

| # | 被作废的结论 | 撤回依据 | 现在以什么为准 |
|---|---|---|---|
| 1 | **V9 的全部形态结论**（6 格 `uiAvailableMs≈10.7–11.0s`、`renderWhitePct=99.2–99.8`、`proxyGET=0`、`dialogLikely=true`） | V11 §5.1-1：那 6 格 `proxyGET=0` + `AUTH FAILED code=401` ⇒ **从未拿到数据，无法判断任何断点行为** | **§4.3**（V11：9/9 格 `401=0`、`apiGET≥10`、inkPct 1.63–6.10） |
| 2 | **V9「第二切换点落在 820–1366dp 之间」这条推论** | V11 §5.1-2 明列**作废**。⚠️ 附带说明：V10 §七-1 只写"我没有复测、不敢背书 / **不评价、不推翻**" ⇒ **正式撤回权在 V11，不在 V10** | **断点 = 1024**，实测区间 **(1000, 1024]**（1000dp 无左栏 / 1024dp 有左栏） |
| 3 | **V9 那张「360dp 停在 `Loading` 骨架屏」的画面 —— 作为形态证据** | V11 §5.1-3；V10 查实那是**两个测量缺陷叠加**（**那轮口令错 401** + **切完几何立刻截图**），**不是 360dp 的缺陷** | V10：正确口令下 **360dp 与 412dp 六次冷启全部离开 `Loading`**（离开均值 2499 ms vs 2404 ms），两档差 **~95 ms** **小于**轮内抖动 **191 ms** ⇒ 落在噪声里 |
| 4 | **V9 §2.1 首屏「5 次冷启中位数 7252 ms」** | 该数字建立在 **401 + 每档人工点「继续加载」**的路径上，且 V9 §2.1 **自述**含 harness 固定开销约 5–6 s（`am start` + 固定 `Start-Sleep 3s` + `uiautomator dump`≈2 s + tap + 400 ms 轮询）⇒ **不是交付包首屏** | 只作为"V9 那一轮"的历史记录留在 `REGRESSION_V9.md` §2.1，**不进本交接的结论轴**；§4.2 的 **1,676 ms**（G2 口径）仍是交接用数字。**两者不同轮不同包，不相减、不混轴** |

**⇒ 教训（已同步追加进 §6.1 ⑪，不覆盖已有条目）**：
> **测量前必须先断言被测系统处于"真正可用"的状态 —— 这里是"已鉴权 + 数据已到"；否则量到的可能是空转。**
> V9 那 6 个 cell **无一例外** `proxyGET=0`（**后端从未向页面交付过数据**），**连 1366dp 平板也是 99.7% 白屏** ⇒ **与视口宽度毫无相关性**，却被读成了"360dp 小屏问题"（V10 §三-4）。V11 已把这两条做成**脚本里的硬门禁**（`_v11_sweep.ps1`）：任一不满足即 `PRECONDITION_VALID=False`、该格结论作废。

**F16 追加两条（F11–F15 这一批换来的，均为过程性教训）**：

⑯ **"轮次结束但脚本还在跑"会同时毁掉两样东西：报告和设备。**
F14 轮起了两回合常亮循环，**轮次在脚本还在跑的时候就结束了** ⇒ ① **报告根本没写出来**（四条的证据散在未落盘的输出里）；② **留下一个自建模拟器还开着**（违反 §6.3「谁起谁关」）。留下的半份产物只能按 git 提交信息如实登记（`wip(web): partial output from the cleanup round…`），那 4 条**保持未闭环**，由 F15 重做并闭环。
**⇒ 纪律（F15 就是照这三条做的）**：
- **边做边写盘**：每完成一步就追加落盘，**不要攒到最后一次性写**（F15 的 `R8_UITAP_F15.md` 是逐步追加的，每节都有"状态：闭环 ✅"）。
- **不许长脚本空等**：**每步单独执行 + 单独取证**，不要"启动一个大脚本然后等它跑完"——轮次可能在它跑完前就结束。
- **收尾无论成败先关实例**：先按 AVD 名 + 端口确认是自己的，`emu kill`，再抄复核输出（残留 = 0 / 端口 FREE / `adb devices` 回读）。**报告没写成事小，设备没关是持续伤害别人的资源。**

⑰ **"标签不能代替事实" —— 自己起的变量名/段名也会骗自己。**
F13 的 `SEG0_idle` 那一格，**标签写着 `idle`（空闲基线），实际却落在「回合已开始后 0.2 s」**（`11:16:42.6` vs `ROUND_STARTED_BY_UI_TAP=YES @ 11:16:42.454`）⇒ **它其实是"回合进行中"，不是空闲基线**。若照着标签解读，会得出"flag 一直就 true"或"空闲态也置旗"这类**与事实相反**的结论。F13 自曝、F15 重采（tap **前 3 ms 纯读取**取样）才真正闭环。
**⇒ 纪律**：**命名必须与实际采样时刻绑定**；关键段要能**用同一次执行里相邻的两条带时间戳日志证明先后顺序**（F15 用 `11:44:43.229`（SEG0 取样）< `11:44:43.285`（tap），差 3 ms，因果才闭合）。**⇒ 推广：任何"基线/对照/空闲态"这类断言，都要能自证它真的发生在被比较的事件之前，否则就不是基线。**

### 4.5 证据出处与强度（本节由 F9 新增，依据 A5）

> **为什么加这一节**：A5 复核 33 条编号声明后发现，**本文最大的风险不是某个数字算错，而是"不同构建/不同设备的数字被当成同一口径引用"**。本节把关键结论按**证据类型**分三级，让接手人一眼看出**哪些现在就能自己验、哪些必须重跑设备、哪些只能当方向**。
>
> **✅ 本节最重要的一句话（F10 已更新，务必先读）**：
> **错误态三态（§3.1）与回合常亮三段（§3.5）的设备证据，已在当前交付包 `76ca064b…`（3,975,389 B）上重做完成（V12 轮，`emulator-6250`）。**
> 装前 `Get-FileHash` 与装后设备端 `md5sum` **双向逐位复核 = `76ca064b7de9246ee446f765a9198b37`**，
> **未沿用** `bc7f3018…` / `627f8ed8…` / `b063148d…` 的任何结论。
> 原表述「证据取自旧构建」**已于 F10 作废**，替换为「已在交付包上重做（V12）」。
> 报告：`research/web/V12_DELIVERED_VERIFY.md`。
>
> **⚠️ 但有一格是另一回事，已在 F10 补齐**：**IME 内容级重叠量**（网页可视内容是否被键盘盖住）
> 在该构建上**无法用 CDP 测**（`webapp/` 从不调 `setWebContentsDebuggingEnabled`，release 包无
> devtools socket，F10 实测 `DEVTOOLS_SOCKETS=0`）—— 但 F10 改用 **Android 无障碍节点树的 composer
> 真实布局矩形** + 截图像素扫描，在**交付包 `76ca064b…` 上测得内容级重叠 = 0 px（余量 52 px）**，
> 该缺口**已闭环**。仍测不到的只剩**页面级**可视视口度量（`visualViewport.height` / `document.body` 可视高）。
>
> **窗口级 ≠ 内容级，两者不是一回事**：V12 那个**窗口级 inset 189 px** 是
> 「宿主窗口矩形 ∩ 键盘矩形」，在 edge-to-edge + `FIT_INSETS_CONTROLLED` 下**必然等于键盘高度**，
> **不能**当成「内容被键盘盖住」的答案；内容级口径见 §4.3 与
> `research/perf/IME_CONTENT_OVERLAP_F10.md`。

**分级定义**

| 级别 | 含义 | 复验成本 |
|---|---|---|
| ✅ **静态可复验** | 结论能在**磁盘 / APK / 命令**上当场重跑复核，不碰设备 | 一条命令 |
| 🟡 **设备运行期观测** | 结论来自**真机 logcat / `dumpsys` / 截图**；本仓**有源报告原文**，但**静态磁盘上查不到原始输出**，**要复验必须重跑设备** | 需起模拟器 + 复跑 |
| 🔶 **仅方向性** | 口径不同 / 底层构建已作废 / 跨轮跨机，**数字不可与当前包横比** | 需在当前包上**重新测**才有意义 |

**按级别归档的关键结论**

| 结论 | 级别 | 出处与复验方式 |
|---|---|---|
| **交付物 4 份 APK 体积 + md5** | ✅ 静态可复验 | `Get-FileHash research/deliver/*.apk`（F7/F8/A5/F9 **四次独立复算，逐位一致**） |
| **构建目录与 `deliver/` 同字节** | ✅ 静态可复验 | 两个 md5 双证（`webapp/build/outputs/apk/{release,debug}/`） |
| **签名 = `CN=Android Debug`；包名带 `.debug`** | ✅ 静态可复验 | `apksigner verify --print-certs` + `aapt dump badging`；SHA-256 `e86b7006…0510a` |
| **包内权限共 4 条；camera `required="false"`** | ✅ 静态可复验 | `aapt dump badging`（§3.4 全表） |
| **修复确实在包内**（`MIN_MAJOR` = **实值 105**、`LOAD_STALL_TIMEOUT_MS=12000`、通知点击链路 extra、`RoundActivityTracker`、F5/F6 错误页） | ✅ 静态可复验（**实值级，非仅类名**） | `dexdump -d` 静态字段块；A5 §4.1–4.5 |
| **闸门数字 677 suites / 6136 tests / 0 failures；webapp 16 / 147 / 0** | ✅ 静态可复验 | `app/build/test-results/testDebugUnitTest/*.xml` 逐 suite 复算（A5 §2.1–2.2 逐位吻合） |
| **无 deep link；`oc_debug_eval` 通道在 release 关闭** | ✅ 静态可复验 | manifest 只注册 MAIN/LAUNCHER；dex 有串但编译期关闭（**这是文档的自证诚实，A5 点名保留**） |
| **上游 526 提交** | ✅ 静态可复验 | `git -C _upstream rev-list --count 702a73d..origin/v2` → 526 |
| **`origin/v2` = `_upstream` 内 `ecfa884`；主仓无此 ref** | ✅ 静态可复验 | §9 口径块（**F9 核对于 2026-10-05**） |
| **"一个前端字节都不打包"= 假** | ✅ 静态可复验 | 包内 4 个 facade 资产 40,801 B / 15,975 B（§0.1 口径更正） |
| **接入健壮性三态 PASS** | 🟡 **设备运行期观测（V12 已在交付包 `76ca064b…` 上重做）** | `V12_DELIVERED_VERIFY.md` §2–§4（`emulator-6250`）—— 正确口令/口令错/不可达三态全 PASS；更早的 `FIX_F6_PROBE.md` §3（`emulator-6170`，`bc7f3018…`）保留为历史 |
| **回合常亮三段 PASS** | 🟡 **设备运行期观测（V12 已在交付包 `76ca064b…` 上重做）** | `V12_DELIVERED_VERIFY.md` §5（`emulator-6250`）—— 置起 → 回合结束释放 → **+95 s 仍 `flagSet=false`**；更早的 `FIX_F4_KEEPSCREEN.md` §5 / `RELEASE_VERIFY_V6.md` §5.2 / `V8_VERIFY.md` §5（旧包）保留为历史 |
| **首屏 3 次（V12，`76ca064b…`）** | 🟡 设备运行期观测 | `V12_DELIVERED_VERIFY.md` §1.4：中位数 **667 ms**（极值 664–680，n=3，UI 可用标记 `M.g1.firstContent`） |
| **IME 内容级重叠量 = 0** | 🟡 设备运行期观测（**F10 已在交付包 `76ca064b…` 上补测，闭环**） | F10（`emulator-6270`）：`mInputShown=true` ✅、键盘顶边 `1474`（三重互证）、composer 内容最低节点 `1422` ⇒ **重叠 0 px / 余量 52 px**。报告 `research/perf/IME_CONTENT_OVERLAP_F10.md` |
| **IME 窗口级 inset 189 px** | 🟡 设备运行期观测（**≠ 内容级**） | V12 §6：edge-to-edge + `FIT_INSETS_CONTROLLED` 下窗口全高不缩 ⇒ 窗口矩形 ∩ 键盘必然 = 键盘高度，**该数不能当「内容被盖」用** |
| **IME 页面级可视视口（`visualViewport.height` / `document.body` 可视高）** | ❌ **测不到**（缺 CDP，结构性） | `webapp/` 不开 devtools、release 无 socket（F10 实测 `DEVTOOLS_SOCKETS=0`）。F10 闭环的是 **composer 这个具体元素**，范围比整个视口窄 |
| **离开前台 4 s 内释放** | 🟡 设备运行期观测（同上两包） | `FIX_F4_KEEPSCREEN.md` §6 |
| **配对页 0 命中**（`ConnectServerScreen` 等） | 🟡 设备运行期观测 | `emulator-6150` 冷启 logcat（`PAIRING_ROUTE_VERDICT.md`）——代码侧结论另属 ✅ |
| **配对路径"上游行为、我们一致"** | ✅ **静态可复验**（代码行号 + git 对象） | §3.3 四段代码行号（取自 `_upstream` `origin/v2`） |
| **F6 根因（`?:` 丢弃带凭据 builder）** | ✅ 静态可复验（`buildProbeRequest` 在包内命中） | §3.2；**但"设备侧直连 200"这一观测仍是 🟡** |
| **§4.3 形态 9 格 + IME 重叠 0 px** | 🟡 设备运行期观测 | `emulator-6230` 截图 + `dumpsys window`；**重跑入口 `research/perf/_v11_sweep.ps1`**；判读凭据 `out_v11/tablet1366x768.png` |
| **§4.3 断点 = 约 768dp + 约 1024dp** | ✅ 静态（代码侧印证） + 🟡 设备（实测夹逼） | Tailwind `lg=64rem=1024` 代码印证 + 1000dp/1024dp 实测 |
| **剪贴板 N-7 / 原生桥八项 / IME 遮挡 0** | 🟡 设备运行期观测 | `V7_CLOSEOUT.md` §3 等（**符号侧另有静态佐证**） |
| **§4.1 输入延迟三层分离** | 🔶 **仅方向性**（底层 **已作废 `8DCFFC57…`** + `emulator-5854`） | §4.1 口径块；**须在 `76ca064b…` 上重测** |
| **§4.2 首屏 1,676 ms** | 🔶 **仅方向性**（**已作废 R2 件 `7b578cee…`** + `emulator-5910`） | §4.2 口径块；**须在 `76ca064b…` 上重测** |
| **§4.2 帧时 28–31 vs 17/26 ms** | 🔶 **仅方向性**（文档已自降级："87% 是模拟器地板"） | 跨轮/跨机**只有方向可信** |
| **dex ⇄ debug 方法级等价** | 🔶 仅方向性（比的是已作废 R2 件；**方法级，非字节级**） | §5 第 7 条 |
| **"用例数只增不减"（128→141→147）** | 🟡 **过程性断言，静态无法证伪** | 当前值 147/6136 ✅ 可复算；**历史值 141/128 磁盘无证据**（A5 §2.3） |
| **耗电 mAh** | ❌ **无证据**（不是"待复验"，是**从来没测过**） | §5 ①；套件就绪未跑 |

**F16 追加：R8 那一批（`0598817026…`）的归档 —— 按同一三级标准，不因"是新证据"就升格**

| 结论 | 级别 | 出处与复验方式 |
|---|---|---|
| **R8 产物体积 1,435,193 B + md5 `0598817026…`** | ✅ 静态可复验 | `Get-FileHash research/deliver/opencode-shell-web-release-r8.apk`（F11/F12/F13/F15/**F16** 复核，**逐位一致**）；构建目录 `webapp-releaseR8.apk` 同字节 |
| **构建目录与 `deliver/` 的 R8 同字节** | ✅ 静态可复验 | 两个 md5 双证（`webapp/build/outputs/apk/releaseR8/`） |
| **体积 −63.90%（vs 非 R8 同轮）** | ✅ 静态可复验 | 两个字节数相减即可；**同轮 `emulator-6290` 取的**（`R8_ATTEMPT_F11.md` §3.4） |
| **`releaseR8` 是独立变体；`assembleRelease` 仍产非 R8** | ✅ 静态可复验 | `webapp/build.gradle.kts`（F11 §3.1 diff）；⚠️ 与之相对的一条**同样静态可复验**：`research/deliver/opencode-shell-web-release-nor8.apk` **一字节未动**（md5 仍 `76CA064B…`） |
| **混淆确实生效**（依赖实改 `q0.q`/`g.l`/`i0.d`、删 21,927 条、双 dex→单 dex 2,594,228 B） | ✅ 静态可复验 | `webapp/build/outputs/mapping/releaseR8/{mapping,usage}.txt`（F11 §3.3） |
| **12 项风险 → keep 规则映射** | ✅ 静态可复验 | `webapp/proguard-rules.pro`（每条规则自带风险编号注释）+ `R8_ATTEMPT_F11.md` §2 |
| **R8 的口令存储 / JS 桥 / 错误态三态 / `shrinkResources` / 无崩溃** | 🟡 **设备运行期观测** | `R8_ATTEMPT_F11.md` §5.4–§5.6（`emulator-6290`）—— 复验**必须重跑设备** |
| **R8 的常亮三段（含真点驱动 + 基线重采 + 两回合 + +95 s）** | 🟡 **设备运行期观测** | `R8_KEEPAWAKE_F12.md` §4（`emulator-6310`）→ `R8_UITAP_F13.md` §4（`emulator-6330`，真点 Send）→ `R8_UITAP_F15.md` ②④（`emulator-6370`，闭环 4 条） |
| **R8 设备端 `base.apk` md5 = `0598817026…`** | 🟡 设备运行期观测 | `R8_UITAP_F13.md` §2.3（`DEVICE_MD5_MATCH=True`；F12 那轮**没取到**，已由 F13 补上） |
| **R8 首屏 644 vs 非 R8 691 ms（同机同轮 n=3）** | 🔶 **仅方向性** | `R8_ATTEMPT_F11.md` §5.3 —— **−47 ms / −6.8%，n 太小不足定论**；**且不可与 §4.2 的 1,676 ms（已作废 R2 件）相减** |
| **R8 与非 R8 的"真点 Send + 常亮三段"行为一致** | 🟡 设备运行期观测 | `R8_UITAP_F13.md` §5 —— **两包同机同轮**（`emulator-6330`），这是 R8 批里**唯一一组真正的双包对照** |
| **R8 的通知点击回跳（风险 #11）** | ❌ **未验** | `R8_ATTEMPT_F11.md` §9.1-3：**非 R8 基线同轮也未测** ⇒ **不构成 R8 特有缺口**，但也**没有证据** |
| **`-Pwebapp.r8=true` 现为 no-op** | ✅ 静态可复验（**是事实，不是缺陷**） | F11 §3.1；⚠️ **任何仍依赖它的脚本会静默拿到非 R8 包**（§5 留账） |

**F19 追加：手机形态这一批（V13 / F18 / V14）的归档 —— 同样按三级标准，不因"修好了"就升格**

| 结论 | 级别 | 出处与复验方式 |
|---|---|---|
| **三份交付物体积 + md5（3/3 逐位一致）** | ✅ 静态可复验 | `Get-FileHash research/deliver/*.apk`（F19 重跑；与 `FIX_F18_PHONE.md` §5 表格同值） |
| **F18 两条修法确实在 R8 包内** | ✅ 静态可复验（**符号级**） | `FIX_F18_PHONE.md` §5.1：解包 `assets/www/oc-host.js` 核到 `oc-top-inset` / 根容器 padding 规则 / `Escape` 派发 / `role=dialog` 已剔除 |
| **两条绊线反向验证会红** | ✅ 静态可复验 | `FIX_F18_PHONE.md` §4.1（`RED_EXIT=1`，撤回修法后两条 FAILED）——⚠️ 首版曾**假绿**，靠剥注释后断言才真红 |
| **回归 `:webapp` 159 / `:app` 6136，0 失败** | ✅ 静态可复验 | `FIX_F18_PHONE.md` §7（`:webapp` 净增 11 个 `PhoneFormDefectTest` 用例） |
| **缺陷 A 已消失（重叠 0 px、净空 64 px）** | 🟡 **设备运行期观测** | `E2E_PHONE_V14.md` §3.1（`emulator-6430`，R8 包 `5227c2ac…`）—— **截图像素级几何**；⚠️ **不是 DOM 读数**（R8 无 CDP） |
| **缺陷 B 已消失（1 次关面板 / 2 次才回列表）** | 🟡 **设备运行期观测** | `E2E_PHONE_V14.md` §3.2 —— **logcat 宿主回执** `back: pageHandled=…` |
| **V14 主链 10/10 PASS（V13 第 10 步由 FAIL 转 PASS）** | 🟡 **设备运行期观测** | `E2E_PHONE_V14.md` §2（冷启 659 ms、`POST /prompt code=200` upstream 12 ms、回复 5 s 无 ANR 无白屏） |
| **平板 1280×800 未引入回归**（`overlapPx=0`、布局未被压塌） | 🟡 设备运行期观测 | `FIX_F18_PHONE.md` §2.4（`tablet_after.txt`）+ `E2E_PHONE_V14.md` §4.1 |
| **`--oc-safe-top` / `getInsets()` / `header.top` / `histLen` 等 DOM 级读数** | ❌ **在 R8 包上测不到** | `webapp/` 不调 `setWebContentsDebuggingEnabled`（承 F10/F12/F16）⇒ 这些读数**只在 F18 的 debug 包上取到过**；V14 改用像素几何 + logcat 间接证明，**结论一致但取证路径不同** |

**⇒ 使用规则（并入 §6.1 ⑧ 的执行细则）**
1. **凡 🟡 / 🔶 级数字，禁止写进对外承诺、SOP 或验收标准**；只能写"方向 + 来源构建"。
2. **引用任何 APK 必须带 md5**（§4.2 文件名陷阱：`…-nor8.apk` 对应过 ≥2 个字节体）。
3. **要拿 🟡 结论升级为"交付包已验"，唯一路径是在 `76ca064b…` 上重跑**——**F10 已为「接入健壮性三态 + 回合常亮三段 + 首屏 3 次」做完这一步**（V12 轮）；**仍靠旧包或仍是"测不到"的项见上表，🟡/❌ 标注一律不变，不得因这一条而顺手升级。**

---

## 5. 未闭环（**接手第一件事；诚实、短**）

### 🔴 两条硬骨头（F16 重排：**原"② R8 版未产出"已过期，本轮删除**）

> ⚠️ **F16 改了 §5 的结构**：上一版的「② R8 版未产出 —— 设备验证前不要分发」**整条已经不成立**——R8 早已产出（1,435,193 B）且核心项已设备验证（§0.4）。**过期条目不留在文档里当"另一种说法"**（§6.1 ⑪ 的同一条纪律）。它下面的 3 条子项去向：① **7 个 `Unresolved reference` 已不复存在**（F11 §3.2：`compileReleaseKotlin` 正常通过，只剩 4 条 deprecation 警告）→ 转为历史，见 §0.4.1；② **Tink 静默降级风险已实测排除**（杀进程重开免密 + `EncryptedSharedPreferences unavailable` 命中 0）→ 见 §0.4.4；③ **12 项风险清单**仍是有效文档 → 见下。**取代它的新 ② 是"对外分发的正式包名 + 证书"。**

**① 耗电 mAh —— 必须真机，我们拒绝编数字。现在只差"插上手机跑一行"。**
模拟器**无电池模型、无 rail 电流** ⇒ 前几轮**全部是代理量，不是耗电**。P6 轮 §7.1 原文：「**本轮没有任何耗电数字。我们没有真机。**」P6 测到的 CPU **−5.6%** 低于其自定阈值 ⇒ 判为"**在测量噪声内，不构成证据**"（**这不等于"两者一样"，更不等于"省了 5.6% 电"**）；WakeLock 两边都是 `size=0` ⇒ **对本项目不具区分度**。
**T2 已把这一步做到最省事（F16 并入）**：套件离线自检 **11/11 PASS**（语法 `ParseErrors.Count=0`、依赖逐项核对、新加的 `-DryRun` 干跑、四条错误路径全是人话），**自检过程真抓出并修掉一个 bug**（`adb devices -l` 长格式下 `$` 行尾锚定会把**在线真机**误判成"不是 device"，已改为取第二个 token，短/长格式各跑一个用例都过）。⚠️ **自检用的是假 adb 夹具，不产生任何耗电读数**——**本仓至今没有任何 mAh 数字**。
**产品负责人要做的（就这三步，完整版 `research/perf/BATTERY_HOWTO.md` §0）**：
```powershell
adb devices -l                                    # 拿串号（emulator-* 开头测不了耗电）
pwsh -NoProfile -File research\perf\battery_real_device.ps1 -DryRun -Serial <串号> -Package com.xiufeigo.opencodeshell.web.debug   # 3 秒确认这台机能测
pwsh -NoProfile -File research\perf\battery_real_device.ps1 -Serial <串号> -Package com.xiufeigo.opencodeshell.web.debug -Tag web    -Minutes 10
pwsh -NoProfile -File research\perf\battery_real_device.ps1 -Serial <串号> -Package com.xiufeigo.opencodeshell          -Tag native -Minutes 10
```
两轮跑完自动出 `research/perf/out_battery/COMPARE.md`，**把整个 `out_battery/` 目录贴回来即可**。前置条件与判定阈值见 `BATTERY_HOWTO.md` / `BATTERY_READY_T2.md` §6–§7。
**禁止**：没有真机数据就写 mAh 数字，或拿"连接断了"当"省电了"的证据；**`0 mAh` 不是"很省电"，是"没测到"**（五道闸已逐闸验过）。
出处：`research/perf/BATTERY_READY_T2.md` · `BATTERY_HOWTO.md` §0 · `battery_real_device.ps1`（40,542 B / 740 行）。

**② 对外分发的正式包名 + 证书 —— 现为 debug 包名 + Android Debug 证书，文档已标注"仅内部灰度"。**
本包**包名带 `.debug` 后缀**（`com.xiufeigo.opencodeshell.web.debug`；`webapp/build.gradle.kts:55` 的 release 变体带后缀，V11 抬头 `aapt2 dump packagename` 读回一致）**且用 Android Debug 证书签名**（`sharedDebug`）。`RELEASE_VERIFY_V5.md` §1.3 明确「**不能据此判可用于对外分发**」。
⇒ **§0.1 两行 release（R8 与非 R8）都已标注 ⚠️「仅内部灰度可用」**。是否改名/换证**待 Lead 定调**。
**同一条卡着两件事**：§3.4 的相机扫码路径要验收，就必须有一个**DEBUG 构建**或给 release 加**受控**的非 DEBUG 注入/深链钩子；§4.3 想要的 CDP 数字也要在 `WebHostActivity` 里加 `setWebContentsDebuggingEnabled`。**⇒ 这三条（对外分发 / 相机验收 / CDP 读数）其实卡在同一个待裁决项上，建议一次性拍板。**

### 🟡 其余（照报告如实列出）

| # | 项 | 状态 | 出处 |
|---|---|---|---|
| ★ | ~~D1 回合常亮 / P6 后台断 SSE 随 UX6 删代理失效~~ **已补回（UX8）** | UX6 删除回环代理后这两条**曾如实记录为失效**；**UX8 已在宿主侧重接并设备双档验证**：常亮 flag 真置起（logcat `flagSet=true` + 越 15s 超时物理常亮）+ 回合结束 **4–17 ms** 释放 + 后台断 SSE **20–106 ms** + 回前台 **92–245 ms** 重建 + 会话循环后可用。**不再需要"待上游接线"** | §0 顶部 UX8 条 · `research/web/UX8_RESTORE_KEEPAWAKE_SSE.md` |
| 3 | **相机扫码 live 流** | release 包不可复现；「左边缘抽屉→Settings→Add server」**未测到而非已排除**。⚠️ **定性要说清**：**"用户可达路径打不通"属上游 connect 页的 `fallback` 语义、我们与官方一致，不是我们的缺陷**（§3.3）——真正缺的是**验收手段**（需要一个 DEBUG 构建或受控注入钩子），不是缺一个修复 | §3.4 · §3.3；`V7_CLOSEOUT.md` §6；`PAIRING_ROUTE_VERDICT.md` |
| 4 | **N-4 文件选/存/上传三条分支** | 选文件成功路径未跑；`saveContent` 与 `onShowFileChooser` **从未设备验证** —— **这是最大的未测面** | `PARITY_LEDGER_A4.md` §7 N-4 |
| 5 | **N-3 终端 PTY / WebSocket 通道** | 面板 UI 由官方 UI 提供，但**通道从未设备验证**；`pty.exited` 链未核对；SSE 无 soak | A4 §7 N-3；`ROUTE_A_WIRING.md:203-205` |
| 6 | **N-1 通知投递策略** | "该会话有打开 tab 则静音"门控**未实现**（路线 A 下上游门控被绕过）；"有焦点即放弃 vs 延迟复查"策略差异**待产品裁决** | A4 §7 N-1 |
| 7 | **release ⇄ debug 的 dex 逐条比对** | 只做了**方法签名集合**比对（差异 0 条）；**dex 字节级不等价**（5/5 条目全不同）。"携带同一路由路径"是**强推断，不是设备实测** | `GAP_CLOSE_G2.md` §1.4/§1.5 |
| 8 | ~~当前交付包未单独复验三段结论~~ **已闭环（V12）** | F9 订正的论据（V5 `b063148d…` / V6 `bc7f3018…`）仍然成立；**但 F10 依据 V12 轮实测已把「接入健壮性三态 + 回合常亮三段 + 首屏 3 次」在交付包 `76ca064b…` 上重做完**，本条**不再是未闭环项**。**注意**：`ROUND_END` 快路径 / 30 min 天花板仍未验（见第 9 条），V12 的回合起止由直接 POST 端点驱动、非亲手按发送键 | `V12_DELIVERED_VERIFY.md` §2–§5 |
| 9 | **常亮 `ROUND_END` 快路径 / 30 min 天花板** | 前者未被观测到触发；后者未在设备验证 | `FIX_F4_KEEPSCREEN.md` §10 |
| 10 | **debug 体积 8.64→5.15 MB 的逐 entry 明细** | 已归因到 **dex**、与 `webapp/**` 任何提交无关（8 次提交逐行比对只加了 release 块）；但**旧 APK 既不在 git 也不在磁盘**（`.gitignore` 含 `*.apk`）⇒ **明细产不出来**，需一份旧产物副本 | `GAP_CLOSE_G3.md` §2.2/§2.4 |
| 11 | **`onRenderProcessGone` 未接** | `WebViewPreflight.Reason` 枚举有 `RENDER_CRASH`，但未找到接线 ⇒ **该分支文案可能永远走不到** | `FIX_D1.md` §5 N-5 |
| 12 | **模拟器静默退出根因** | F4/R2/V6/V7 多轮记录，形态与"几何 2400 > 物理 1600"一致，**各轮均不下因果结论** | `HOUSEKEEPING.md` 已执行记录 |
| 13 | **CDP 在 release 包不可用** | 所有"CDP 取 title/节点数/可见文本"的口径均未按 CDP 完成，已降级为 `uiautomator` + 截图 + 应用自身 `web[LOG]`，并逐处标注 | `V8_VERIFY.md` §2.4/§8-2 |
| 14 | **AVD 定义占 ≈39.8 GB** | 进程已关；`OC_Phone_Test`/`B`/`C`、`OC_Narrow_Test`、`Medium_Desktop_B` 仍在磁盘，**待产品负责人决定** | `HOUSEKEEPING.md` |
| 15 | **上游快照滞后** | 工作区停在 `702a73d`（2026-09-20）；审计一律改用 git 对象读 **`_upstream` 仓内的 `origin/v2`（`ecfa884`）**（**不切工作区**；主仓无此 ref，口径见 §9） | A4 抬头；**§9（F9 更正）** |
| 16 | **原生壳 `app/**` 的最终去留** | 属产品/架构决策 | A4 §10-6 |
| 17 | **形态扫描拿不到 CDP 数字与 DOM 级断言** | `:webapp` 无 `setWebContentsDebuggingEnabled` ⇒ 9/9 格 `nodes` 数 / `data-slot`·`data-action` 选择器命中**一个都没取到**；形态判定靠**像素 + 人工读图 + 后端日志**。要 CDP 数字**必须先改代码**（超本轮权限，未动） | §4.3「测不到的量」；`FORMFACTOR_V11.md` §八-1/§八-2 |
| 18 | **形态扫描为单次采样、未覆盖真实形态** | 每格只冷启 1 次（未取中位数）；折叠档是 `wm` 覆盖模拟宽度，**不是真机折叠态**；横屏/多窗口未测 | §4.3「测不到的量」；`FORMFACTOR_V11.md` §八-3/§八-6 |
| 19 | ~~会话消息 composer 的 IME 重叠未测~~ **已闭环（F10）** | F10 在交付包 `76ca064b…` 上进入真实会话、聚焦 composer、断言 `mInputShown=true`，composer 内容最低节点 `1422` vs 键盘绘制带 `1474` ⇒ **内容级重叠 0 px / 余量 52 px**。⚠️ 口径提示：composer 矩形取自**无障碍节点树**（非 CDP `client rect`），且**页面级**可视视口度量仍测不到 | §4.3；`research/perf/IME_CONTENT_OVERLAP_F10.md` |
| 20 | **R8 的通知点击回跳（风险 #11）未触发** | R8 批**没有单独触发**通知点击；**非 R8 基线同轮也未测** ⇒ **不构成 R8 特有缺口，但也没有证据**。要补需在 R8 上单独触发一次 | `R8_ATTEMPT_F11.md` §9.1-3 |
| 21 | **R8 首屏 n=3，−47 ms 不足以定论** | 想用它支撑"改默认推荐"需 **n≥5 同机同轮复测**；现状只能写"方向性偏好 R8，收益几乎全在体积" | `R8_ATTEMPT_F11.md` §9.1-5 · §0.4.3 |
| 22 | **`-Pwebapp.r8=true` 开关已失效（no-op）** | **遗留事实**：`release` 的 minify 已改成常量，任何仍依赖那个 `-P` 的脚本会**静默拿到非 R8 包**且从变体列表看不出来。要么删掉这些调用点，要么在 CI 里禁用 | `R8_ATTEMPT_F11.md` §3.1/§9.1-6 |
| 23 | **`input text` 打不进 CJK** | `KeyCharacterMap` 只支持 ASCII 可打印字符 ⇒ 这是 **`input` 命令本身的限制，不是被测应用的缺陷**，也**超出 F15 派单范围**。若将来要用它驱动中文回合，需另找输入方法（如 ADB 键盘） | `R8_UITAP_F15.md` ③ |
| 24 | **同源脚本的同一 `VERDICT` bug 未修** | F15 只修了主脚本 `_f13_uitap.ps1`；`_f12_keep.ps1` 与 `_f13_uitap_b/_v2/_v3/_v4.ps1` **同源同 bug 未修**（不在那 4 条派单内） | `R8_UITAP_F15.md` ① + 未闭环清单 |
| 25 | **手机形态 D3（360dp 下 Tab 条挤压）** | 5 项 Tab 挤满整行、`≡` 紧贴右边缘（`Terminal`↔`≡` 净空约 75 px）。**F18 未修**；V14 已用字形坐标证明**横向排布与 V13 逐字节一致 ⇒ 非修复引入**，维持原状 | `E2E_PHONE_V13.md` 步骤 4 · `E2E_PHONE_V14.md` §4.2 · §0.5 |
| 26 | **手机形态 D5（冷启动首屏缺 `New session` 按钮）** | 首屏 `Home` 顶栏只有项目图标 + `Home` + 计数，**无 `+ New session`**；BACK 返回后的同一列表才有 ⇒ **首帧控件未渲染完**（非骨架屏）。**F18 未修**，V14 维持观察 | `E2E_PHONE_V13.md` 步骤 8 · `E2E_PHONE_V14.md` §6 · §0.5 |
| 27 | **顶部 inset 未在真挖孔设备上验证** | 几何覆盖下 `displayCutout` **恒为 0** ⇒ 真实挖孔机上 `safeTop` 会更大。修法按**同一变量**自适应（理论上成立），但**未在真挖孔机实测** | `FIX_F18_PHONE.md` §11.2 · `E2E_PHONE_V14.md` §10-2 |
| 28 | **手机档的 IME 遮挡 / `Changes`·`Files`·`Terminal` 页未走查** | V13/V14 走的是"列表→会话→发消息→返回"主链；**键盘遮挡在 360dp 下未重测**，三个 Tab 页在手机档**未逐个走查** | `E2E_PHONE_V14.md` §10-4 · `FIX_F18_PHONE.md` §11.1 |

> 📌 **F19 对 §5 的处理**：**上面两条硬骨头（① 真机耗电 mAh ② 对外分发的正式包名 + 证书）原样保留、一条没动**——F18/V14 **没有产生任何能解掉它们的新证据**。新补的 25–28 都是**手机形态这一批的遗留项**，与那两条硬骨头**性质不同**（它们是"某一形态的体验没做到位"，不是"对外发布的前置条件没满足"）。

---

## 6. 血泪教训（**接手必读，别再踩**）

### 6.1 关于"测到了"

| # | 教训 |
|---|---|
| ① | **「0 命中」可能只是「从没发生过」**。V4 记"事后全机 `KEEP_SCREEN_ON` 命中 0"、看着像"策略在位、按预期清干净了"——**错**。D1 §1.5 原文：**那 0 命中是"空集上的真命题"，因为置起从未发生过**；这是**功能缺失**，不是省电优化。**⇒ 不许把"清干净了"当成"功能正常工作"。反向验证时"无差异"同样要问：是"两者一样"，还是"这指标本来就没区分度"（P6 的 WakeLock 就是后一种）。** |
| ② | **"修一半"是最难发现的一类**。F5 修好冷启动那条路径后，**"在配置对话框里新播错口令"那条仍原样落回骨架屏**（它绕过探测直接 `launch`），F5 §0 自曝并补修（`WebHostActivity.kt:649`）。**⇒ "新功能可能压坏旧行为"必须写成逐条检查项：新逻辑接了哪几条入口？每条入口都走到了吗？** |
| ③ | **新加的"防御"可能把对的判成错的**。F6 的匿名化把**正确口令**判成"用户名或口令不对"——一个安全向的改动压坏主流程。**⇒ 同上检查项反向适用：新逻辑改变了"什么算成功"的判据吗？判据变了就要重跑旧的成功用例。** |
| ④ | **改断言必须反向验证，且要防"假绿"**。F6 第一版绊线只断言 `buildProbeRequest` **单独调用**时带凭据，而真实路径 `probeAsync` 走的是另一条 ⇒ 把 `probeAsync` 改回匿名请求后 `BUILD SUCCESSFUL / TEST_EXIT=0`（**假绿**）。补上"守接线不守构造器"的用例后才真红。D1 另抓到一个**绊线自身失效**：原断言只判 `contains("scheduleKeepScreenOnBackstopCheck()")`，而函数**定义**就紧跟其后 ⇒ 删掉**调用点**照样绿。**⇒ 原文：「一个不会失败的绊线比没有绊线更危险（它给的是假安全感）。」两条教训都已写进对应用例的 KDoc。** |
| ⑤ | **绊线的判据必须与守护对象一致**，且**用例数只增不减**（`ConnectRobustnessTest` 13→19、`:webapp` 128→141→147）。 |
| ⑥ | **不许拿旧数顶替新数，不许把"没测到"写成"通过"**。R2 §7 原文：「"0"写上去就是造假」；G3 抬头：「**没有用旧数顶替、没有把"没测到"写成"通过"**」。G2 §3.1：「**我不拿新产物自己跟自己比来伪造差值表。**」 |
| ⑦ | **矛盾时以更强的信源为准，并写明矛盾**。V6 §5.4 原文：「**我不能拿这个"MISS"当"没置起"的证据** —— 它与 §5.2 的 `flagSet=true` 直接矛盾，**矛盾时以 §5.2 为准**。」 |
| ⑧ | **量对了才算数**：不许把"最后一个 `_assets` GET"当首屏（曾因此报出错的 `≥15.4s`）；对比数字必须同轮、同参数、同设备指纹，**不许跨轮沿用**（本冲刺三次结论反转都源于此）。 |
| ⑨ | **`BUILD SUCCESSFUL` + `UP-TO-DATE` 不等于重建**（R2 §1.1：gradle 根本没重新打包，**收工的仍是旧字节**）。真重建的口径是"每一个 task 都真跑了"（R2 记 `35 actionable tasks: 35 executed`）。 |
| ⑩ | **验证"修复在包里"要用真实标识符**。R2 §2.4 自曝：用自造关键字 grep 资产打出 `fix-marker hits: 0`，一度像"修复没进包"——**该结论是错的**，关键字根本不是代码里的真实标识符；改用与源码**逐字节比 md5** 后确认。 |
| ⑪ | **测量前必须先断言被测系统处于"真正可用"的状态，否则量到的可能是空转**（F8 由 V9/V10/V11 三轮换来）。V9 的形态扫描跑在**未鉴权（401）**状态下：6 个 cell **无一例外** `proxyGET=0`（后端**从未向页面交付过数据**）、`renderWhitePct=99.2–99.8`，**连 1366dp 平板也是 99.7% 白屏** ⇒ **与视口宽度毫无相关性**，却被读成"360dp 小屏缺陷"；再加上**切完几何立刻截图**（骨架屏 ~2.2 s 才出现、数据 ~2.4–2.6 s 才到）⇒ **两个测量缺陷叠加，把"没数据"伪造成了"渲染慢/渲染不出来"**。**⇒ 任何视口/时序对比都必须先过"已鉴权 + 数据已到"门禁**（V11 已写进脚本：断言 `probe OK code=200` + `/api/session?limit=5000…` 已发出 + `AUTH FAILED=0` + inkPct ≥1.5%，任一不满足即 `PRECONDITION_VALID=False`、该格结论作废）。**⇒ 并且：已作废的数据要显式撤回，不许留在文档里当"另一种说法"** —— V9 声称"第二切换点在 820–1366dp"这条推论，被 V11 用 1000dp/1024dp 两格**夹逼撤回**（V11 §5.1 逐条列了作废台账）。 |

**F19 追加一条（F13/F14/V13 这几轮换来的）**：

⑱ **只有把用户的整条旅程走一遍，才会发现"顶部压系统栏"和"BACK 把面板吞掉"这类缺陷 —— 单项机制测试永远碰不到它们。** V13 之前的所有验证（V8 / V12 / F11–F15）**都在平板档**做，每一项单独测都"通过"；可缺陷 A（inset 投递链路**两处各断一半**：宿主侧从未投递 ⇒ `getInsets()` 返回 `null`，页面侧也不消费）与缺陷 B（宿主 BACK 顺序写反：上游 SPA 用 History API 站内导航 ⇒ 进会话时已压历史 ⇒ `canGoBack()==true` ⇒ 面板开着也被弹掉）**在平板上都是"看起来正常"的**——平板没有状态栏层、没人测 BACK。**⇒ 是"在 ≈360dp 手机档上像用户那样从冷启动一路走到返回"才让这两步露出来的。V14 用同一条路重走 ⇒ 10/10 PASS。教训：**机制测试回答"这个零件坏没坏"，旅程测试才回答"用户能不能用"；新增形态/新增交互入口时，先补一条端到端旅程，再谈机制覆盖率。** 出处：`E2E_PHONE_V13.md` · `FIX_F18_PHONE.md` · `E2E_PHONE_V14.md` · §0.5。

### 6.2 关于交付物

| # | 教训 |
|---|---|
| ⑫ | **交付物命名不许另起后缀**。F5 用 `opencode-shell-web-release-f5.apk` 落产物，与规范名 `…-nor8.apk` 并存 ⇒ 直接造成"**哪个才是交付物**"的歧义。**⇒ 新一轮一律落规范名。** |
| ⑬ | **文档里的 md5 必须与磁盘产物一致**。Lead 两次靠自查抓到脱节（R2 轮、F6 轮各一次）。**⇒ 每次重建后第一件事：`Get-FileHash` 复核，并把该代次的 md5 显式登记进 `DELIVER_REBUILD_R2.md` §2.1 的现行表 + 作废清单两栏**（V5/V6/V8 复验过的那三个包当时就没登记进去，见 §0.2 裂缝 ①）。 |
| ⑭ | **区分"APK 的 md5"和"包内资产的 md5"**。`bf542769…`（13,804 B）是 `oc-host.js` 的 md5，曾被误列进"已作废的 APK md5"（见 §0.1 末注）。 |
| ⑭' | **别用文本级往返写回改含历史编码破损的文件**。F6 第一次改 `DELIVER_REBUILD_R2.md` §2.1 时用了"读成字符串再整体写回"，**把该文件里既有的非法 UTF-8 字节洗成了替换字符**，diff 从 10/3 炸到 160+/152-。已 `git checkout --` 还原，改用**按字节切行、只替换目标行字节**重做。**⇒ 该文件至今仍含坏字节，任何工具都读不回原字。** |
| ⑮ | **改断言前先想清楚：是改断言还是改产品**。F5 有一条测试先红后绿（`auth page never echoes password`），**按规矩改的是产品文案、不是断言**（`HINT_AUTH` 补上"用户名或口令不对"），复跑通过。 |

### 6.3 设备与安全纪律（**产品负责人明确要求**）

**硬规矩**（原文见 `research/HOUSEKEEPING.md`）：

1. **谁起谁关** —— 起了实例的必须收尾 `adb -s <serial> emu kill`，并**抄出复核输出**（`OC_*/Medium_Desktop_B` 残留进程 = 0 + 端口已释放 + `adb devices` 回读）。
2. **只关自己起的** —— 判据是 **AVD 名**（我们的是 `OC_*` / `Medium_Desktop_B`）。**用户的 `MCRemote_*` / `DSH_*` / `T7x_*` 与任何来源不明的实例，一律禁止 kill。**
3. **禁止 `adb kill-server`**（它服务的是**共享设备连接**，会扰动用户的设备）。
4. **禁止 `-a` / 全量 kill / 通配符杀进程**；只按 **serial** 或**已确认属自己的 AVD 名**关。
5. **故意保留必须显式声明** + 下一轮补关。⚠️ `emu kill` 会自拉一个**瞬时拆卸辅助进程**（`emulator -kill <pid> -sleep 20`，命令行**不带 `-avd`**）—— 按规则 2 判据**不属于你**，**不许 kill**，25 s 后自然归零再复查。
6. **后端 `4095` 不是你的** —— 由 Lead 起，各轮**全程未起停**，只做 `adb reverse tcp:4095 tcp:4095`；收尾复核同一 pid 才算合格。
7. **gradle 锁** `.agent-gradle-lock`：等 → 写 → 前台跑 → 用**顶层 `rm --`** 释放（`Remove-Item` 被本地安全策略拦，**不许绕过**）。
8. **同一时刻只开一个 adb 客户端** —— V7 轮后台任务与前台检查并发发 adb，出现过"进程仍在但 `adb devices` 已空"的不一致状态；**串行跑是规避手段**。

**两条环境坑（每轮都会踩）**：

| 坑 | 现象 | 正确做法 |
|---|---|---|
| **`-skin pixel_tablet` 不是合法 skin 名** | 首次起机直接 `ERROR \| unknown skin name 'pixel_tablet'` 退出。`pixel_tablet` 是**设备档位/几何**，不是 skin 目录 | **不传 `-skin`**；几何一律走 `wm size 1080x2400` + `wm density 420` 覆盖，收尾 `wm size reset` / `wm density reset` 并回读确认**无 `Override` 行** |
| **`adb reverse --remove` 被本机安全策略拦**（判为 delete-like） | 命令被拦下 | **未绕过**；改用"**指向错端口法**"制造不可达：`adb reverse tcp:4095 tcp:4099`（4099 无人监听），事后用 `reverse tcp:4095 tcp:4095` 覆盖恢复 |

**其它两条测量坑**（本镜像）：① **软键盘下 `adb shell input text` 会丢字符/错位** ⇒ 播种口令前先 `ime disable …LatinIME` + `settings put secure show_ime_with_hard_keyboard 0`，并做一次**可见字段对照实验**证明输入保真（V8 §2.2），否则会把"输入丢字符"误判成"后端拒绝正确口令"；② **`dumpsys window insets` 在本 build 不存在**（`Bad window command, or no windows match: insets`）。

**写盘纪律**：先写盘再动手；并发期间**禁止 `git add -A`**；**禁用 git 回退/checkout 改行尾**（`core.autocrlf=false`）。

---

## 7. 关键文件索引

| 主题 | 文件 |
|---|---|
| **交付物台账（体积/md5/作废清单）** | `research/web/DELIVER_REBUILD_R2.md` §2.1 |
| 路线决策（服务端自托管） | `research/web/SERVER_HOSTS_UI.md` |
| Web 宿主骨架 / 路线 A 接线 | `research/web/WEBHOST_SKELETON.md` · `ROUTE_A_WIRING.md` |
| **接入健壮性（错误态）** | `research/web/FIX_F5.md` · **`FIX_F6_PROBE.md`** |
| **复验（规范名包）** | `research/web/V8_VERIFY.md` |
| **配对路径定性** | `research/audit/PAIRING_ROUTE_VERDICT.md` |
| **一致性结账（97 条）** | `research/audit/PARITY_LEDGER_A4.md` |
| **相机 / N-5 / N-7 收尾** | `research/web/V7_CLOSEOUT.md` · 脚本 `research/env/camera_route.ps1` |
| 常亮（置起 / 释放 / 兜底） | `research/web/FIX_D1.md` · `FIX_F4_KEEPSCREEN.md` · `RELEASE_VERIFY_V5.md` · `RELEASE_VERIFY_V6.md` |
| 省电策略 | `research/perf/POWER_ACTIONS_P6.md` |
| 发布工程 / R8 风险 | `research/web/WEB_RELEASE_BUILD.md`（E1 轮 12 项风险清单，**历史**） |
| **R8 试产（F11，−63.90% + 首项设备验证）** | `research/web/R8_ATTEMPT_F11.md` |
| **R8 常亮置起段（F12，curl 驱动）** | `research/web/R8_KEEPAWAKE_F12.md` |
| **R8 真点官方 UI Send（F13，设备端 md5 匹配 + 自曝 4 条）** | `research/web/R8_UITAP_F13.md` |
| **R8 收尾（F15，4 条全闭环）** | `research/web/R8_UITAP_F15.md` |
| **R8 12 项风险 → keep 规则** | `webapp/proguard-rules.pro`（每条规则自带风险编号注释） |
| 桥契约 / 桥验收 / 通知点击 | `research/web/NATIVE_BRIDGES.md` · `BRIDGE_ACCEPTANCE.md` · `NOTIFICATION_CLICK_ROOTCAUSE.md` |
| 预检 / IME / 触摸靶 / 窄屏 | `WEBVIEW_PREFLIGHT.md` · `IME_OCCLUSION_FIX.md` · `TOUCH_TARGET_FIX.md` · `NARROW_SIDEBAR_CHECK.md` |
| 缺口收口（G2 / G3） | `research/web/GAP_CLOSE_G2.md` · `GAP_CLOSE_G3.md` |
| 上游回归 | `research/web/UPSTREAM_REGRESSION_A3.md` |
| 性能四量 | `research/perf/{STARTUP_AND_FRAMETIME,FRAMETIME_AND_GZIP,POST_FIX_RETEST,INPUT_PATH_P5}.md` |
| **形态 / 切换点（已鉴权重测，V11）** | `research/perf/FORMFACTOR_V11.md` |
| **360dp「停在 Loading」调查（V10）** | `research/perf/PHONE360_INVESTIGATION.md` |
| **⚠️ 已作废的 V9 形态/性能数据** | `research/perf/REGRESSION_V9.md`（未鉴权状态下取的，结论见 §4.4） |
| **耗电套件（本轮未跑）** | `research/perf/battery_real_device.ps1` · `BATTERY_HOWTO.md` |
| **环境与清理纪律** | `research/HOUSEKEEPING.md` |
| **本轮改动摘要** | `research/web/HANDOFF_REFRESH_F8.md`（前一轮：`HANDOFF_REFRESH_F7.md`） |
| **手机档走完整条旅程（V13，发现两条缺陷）** | `research/web/E2E_PHONE_V13.md` |
| **手机形态两条缺陷的修法（顶部 inset / BACK 顺序）** | `research/web/FIX_F18_PHONE.md` |
| **修后重走同一路径（V14，10/10 PASS）** | `research/web/E2E_PHONE_V14.md` |
| **F19 交接摘要（本轮）** | `research/web/HANDOFF_F19.md`（前一轮：`HANDOFF_FINAL_F16.md`） |

---

## 8. 后端启动口径

```powershell
$env:OPENCODE_SERVER_PASSWORD='opencode'
Start-Process "$env:USERPROFILE\.bun\bin\opencode2.exe" -ArgumentList 'serve','--port','4095','--hostname','127.0.0.1' -WindowStyle Hidden
# 必须用 v2 可执行文件（PATH 上的 opencode 是 npm opencode-ai@1.18.33 = v1，会遮住 v2）
# 认证：用户名固定 opencode + Password = OPENCODE_SERVER_PASSWORD
```

---

## 9. 分支与远端

- `main` —— 路线 A 的全部工作（**已推到 `origin/main`**）
- `native-kotlin-snapshot` —— 路线切换**之前**的原生 Kotlin 版本快照（`55c27a5`，**已推到远端**，便于回看/回退）
- 远端：`https://github.com/xiufeigo/opencode-android-shell.git`
- ⚠️ **`origin/v2` 的唯一权威口径（F9 统一自 A5 C3，本节为全文准绳）**：
  | 仓 | `origin/v2` | 核对命令与实测结果 | 核对时间 |
  |---|---|---|---|
  | **主仓**（本仓根） | **不存在** | `git rev-parse --verify origin/v2` → `fatal: Needed a single revision` | **2026-10-05（F9 复跑，A5 同日独立复跑一致）** |
  | **`_upstream/`**（独立 clone） | **存在 = `ecfa884ff82e9116e475b71a0c99383b8438d710`** | `git -C _upstream rev-parse --verify origin/v2` → `ecfa884ff82e…` | **2026-10-05（F9 复跑）** |
  - **本文所有上游行号/提交数，一律以 `_upstream` 的 `origin/v2`（`ecfa884`）为准。**
  - ⚠️ **`0a46301` 在本机不是有效对象**（`git -C _upstream cat-file -t 0a46301` → `fatal: Not a valid object name`）。早期版本文档把它写成 `origin/v2 = 0a46301`，**是错的，已撤回，请勿引用**（F9 已更正 §0.3 该行）。
  - 上游证据一律走 `git show` / `git grep … origin/v2 -- packages/*/src`（**在 `_upstream` 里执行**），**或**读 `_upstream/` 只读检出（并注明行号取自哪一处）。
  - 复核锚点：`git -C _upstream rev-list --count 702a73d..origin/v2` → **526**（§1 的"上游两周 526 提交"，精确吻合）。

---

## 10. B-6（UX9，2026-10-06）：官方「添加服务器」拒 http —— **判据已钉死，架构需产品负责人拍板**

> 完整报告：`research/web/UX9_HTTP_SERVER.md`（含全部原文读数）。**本轮 0 代码改动，未重建产物。**

### 10.1 那句红字的判据（`file:line` + 原文）

`_upstream/packages/app/src/servers/connect/browser.ts:3-12`：

```ts
export function isMixedContent(page: string, address: string) {
  if (new URL(page).protocol !== "https:") return false
  ...
  if (url.protocol !== "http:") return false
  // Secure Contexts treats loopback HTTP origins as potentially trustworthy.
  const host = url.hostname.replace(/\.$/, "")
  return !(host === "localhost" || host.endsWith(".localhost") || host === "[::1]" || /^127(?:\.\d+){3}$/.test(host))
}
```

- 判据 = **页面协议为 `https:`**（实参 `page` 就是 `dialog.tsx:285` 传的 `location.href`）**∧ 目标 `http:` ∧ 目标非回环**。
- **不是 `isSecureContext`**（全文没有；它只用于相机，`dialog.tsx:181`）。
- ⚠️ **它不是闸门**：`dialog.tsx:281` 的 `if (!result.healthy)` 才是。`:285` 只在**已失败之后挑文案**（`server.connect.mixedContent`，原文 `runtime/i18n/en.ts:436-437`）。
- 上游**没有**禁止 http：`runtime/server/registry.tsx:15-20` 原样保留 `http://`。

### 10.2 "官方可以加 http" 成立于哪种形态

`browser.ts:4` 只看页面协议 ⇒ **页面不是 https 就一律允许**：桌面 Electron 的 `file://`、web 端用 http 打开页面，都直接短路。
⇒ **"官方可以既加 https 又加 http"是桌面 Electron 形态的记忆**（它有一页不隶属任何服务器的 origin）。
**官方 web 端同样会拒** —— 官方 web 与我们跑同一份代码。

### 10.3 我们页面的实测协议（`emulator-6752` / `OC_UX6_Phone` / debug 包 / CDP 原文）

```json
{"location_protocol":"http:","location_origin":"http://127.0.0.1:4095",
 "isSecureContext":true,"proxyOrigin":"","proxyPrefix":""}
```

真实页面里执行上游判据：`caseA(页面http→回环http)=false`、**`caseB(页面http→远端http)=false`**、
**`caseC(页面https→远端http)=true`**、回环豁免复测 `127.0.0.1=true` / `8.138.19.15=false`。
⇒ **只有 `caseC` 能产出那句红字** ⇒ 产品负责人那台当时页面是 https。

### 10.4 https 从哪来

`webapp/.../WebHostActivity.kt:323-324` `bootUrl()`（`SecretStore.KEY_BACKEND_URL`，默认 `http://127.0.0.1:4095`）→ `:340` → `:360 loadUrl(url)`。
**页面 URL 就是后端地址**（UX6：origin == API origin，无回环代理）。先配 https 后端 ⇒ 页面即 https。
⚠️ 更正两处旧猜测：**不是** `app/.../MainActivity.kt:737-739` 的 asset loader（旧 `app` 模块；现行壳只剩诊断页 `WebHostActivity.kt:79`），也**不是**我们把 http 改成 https。

### 10.5 🔑 第二道墙是 CORS，不是混合内容

实测在跑的后端：`GET /global/health` 带 `Origin` ⇒ `200`，但 **`Access-Control-Allow-Origin` 为空**。
UX6 不需要 CORS 的**唯一依据就是"页面 origin == API origin"**。
⇒ **`setMixedContentMode` 放行只拆得掉墙①（混合内容），拆不掉墙②（CORS）⇒ 无效。**
（现行 `WebHostActivity.kt:388` 是 `MIXED_CONTENT_COMPATIBILITY_MODE`，最弱档，**不允许混合 XHR/fetch**。）

### 10.6 待产品负责人拍板（B-6 第 54 行：必须停下上交，不得偷偷加回凭据处理）

| # | 方案 | 代价 | 红线 |
|---|---|---|---|
| **A（推荐，零代码、今天可用）** | 把应用后端地址指向 `http://8.138.19.15:4094` | 页面变 http ⇒ `caseB=false` ⇒ **红字消失、官方对话框对 http 正常可用**（= 官方 web-over-http 形态）。代价：Basic 口令走明文 http | ✅ |
| **B** | 服务端加 TLS，全程 https | 需证书与运维 | ✅ |
| **C** | 第二台开 CORS（`opencode serve --cors <页面origin>`） | 改服务端启动参数；http 目标仍需配合 A | ✅ |
| **D** | 改架构：本机 http 来源加载页面 | **撞 `ServerSocket` 0 命中红线**，且会**打掉"无需 CORS"这个 UX6 现有优点** | ❌ |
| **E** | 重新引入回环代理 | 必须转发含 Basic 认证头的 API ＝ 凭据处理 | ❌ **禁止** |

> ⚠️ **B-6 自相矛盾处**：第 50 行建议的"本机 http 来源"必然用 `ServerSocket`，而第 51 行红线要求 `ServerSocket` **0 命中**。

### 10.7 回归与产物

`:webapp` 184 + `:app` 6136 = **6320 用例，0 失败 0 错误 0 跳过**（与上轮一致）；`DirectConnectAuthBoundaryTest` **10/10 绿**。
**0 代码改动 ⇒ 0 新产物**，交付物体积/md5 **维持原值**（见 §0 产物清单表）。
⚠️ **环境坑（与本轮无关但会复发）**：`JAVA_HOME` 现指向 JDK `25.0.4.1`，Gradle 8.11.1 抛 `IllegalArgumentException: 25.0.4.1`；**换 JBR 21.0.10 即全绿 ⇒ 建议把 `JAVA_HOME` 钉到 21**。

---

# 附录：B-6「官方添加服务器拒绝 http 服务器」—— 判据、机制、以及**未实施的方案 A**

> 追加时间：2026-10-06 10:2x ｜ 触发：产品负责人实测「**直接添加 http 的也不行，这高低是你的 bug 了吧**」
> **详细证据**：`research/web/UX9_HTTP_SERVER.md`（判据与 CORS 初判）· `research/web/UX10_HTTP_HTTPS_TOGETHER.md`（**端到端复现 + CORS 更正 + 修法代价**）· `research/web/UX11_LOCAL_UI_ORIGIN.md`（**方案 A 的技术实现与踩坑**）
> **决定**：产品负责人明确「**新功能可以先不做留着写交接文档就行**」⇒ **方案 A 已整体回退**，本附录即其存档。

## 1. 现象
在官方「连接到服务器」屏 / 「添加服务器」对话框里填 **`http://…`** ⇒ 红字：
> **无法从 HTTPS 页面连接此 HTTP 服务器。请使用 HTTPS 服务器地址。**
**连"还没连上任何服务器"的那一屏也一样拒** —— 这是关键线索。

## 2. 判据（**已钉死，行号 + 原文**）
- `_upstream/packages/app/src/servers/connect/browser.ts:3-12`：判据 = **`new URL(page).protocol !== "https:"`**，`page` 实参来自 `dialog.tsx:285` 的 **`location.href`**；
- **不是** `isSecureContext`（全文无此用法；`file://` 的 `isSecureContext=true` 但协议非 https，仍判 false）；
- 上游 `runtime/server/registry.tsx:15-20` **没有任何"禁止 http"的规定**；
- 同源逻辑另有 `servers/connect/screen.tsx:25-30`（扫码配对页），判据一致。

## 3. 机制：**鸡生蛋死锁**（这是本项目的形态决定的）
- 我们的壳 `loadUrl(配置过的后端地址 ?: http://127.0.0.1:4095)` ⇒ **界面是从"已配置的那台服务器"取来的**；
- ⇒ **页面协议 = 所配置后端的协议**；先配了 https 后端 ⇒ **整页（含"连接到服务器"屏）都是 https** ⇒ **http 服务器永远加不进去**；
- **官方桌面端没这问题**：它的界面**不是"由某台服务器提供的一页"**（外壳自带）⇒ 连接屏 origin 非 https ⇒ http/https 都能加。
⇒ **一句话**：「**想加 http 服务器，得先有一个非 https 的页面；而页面只能从服务器来**」。

## 4. 实测更正：CORS 不是"没发"，而是"**只放行 http 回环来源**"
- UX9 初判「服务端 0 个 CORS 头」；**UX10 实测更正为**：**服务端有 CORS 白名单，且放行 `http` 的回环来源**；
- ⇒ 这解释了为什么"页面为 http 回环"时 `http://localhost:4095` **能添加成功**（UX10 §3 端到端复现成功，日志可见 API 切到新服务器）；
- ⇒ **这条也说明：只要页面来自 `http://127.0.0.1:<port>`，跨源那道墙本来就不挡**（对我们的场景友好）。

## 5. 方案 A（**技术已跑通，但按决定未采用**）—— 与官方桌面端同形
> **把官方前端作为 App 自带资源 + 用本机 `http` 静态来源加载**，使页面协议恒为 `http:`，从而**一页可同时添加 http 与 https 服务器**。

**UX11 已实测到的关键事实（留档，供日后重启该方案）**：
- **资源来源走通**：**从 `_upstream` 自己构建官方前端**（`_upstream` 自带 `node_modules`、`bun` 在 PATH ⇒ 无需额外安装）；**排除 sourcemap 后 913 个文件 / 32.49 MiB**；
- **本机来源**：端点跑在 **`http://127.0.0.1:4173`**，`location.protocol` **实测恒为 `http:`** ⇒ UX10 §4.3 的双向死锁从根上消失；
- **踩到的坑（必须留档）**：**AGP 会丢弃下划线开头的资源目录**（自带资源目录命名要避开）；
- **代价（当时写明的）**：APK **体积暴涨**（debug 5.44 MB → **16.13 MB**；release 3.55 MB → **13.61 MB**；R8 1.27 MB → **11.33 MB**）；
- **未完成的部分（UX11 自己声明）**：**没有证明"跨源化"不回归已验收项**（终端/键盘/主题/常亮/SSE/加服务器等全量双档回归**未跑**）。⇒ **这正是当时不能直接交付的原因。**

## 6. 回退记录（**已执行**）
- 回退：`WebHostActivity.kt` / `DirectConnectAuthBoundaryTest.kt` / `TerminalReattachTest.kt` 恢复；**删除** `host/LocalUiOriginServer.kt` 与 `LocalUiOriginServerTest.kt`；**删除自带前端资源目录（913 文件）**；
- **三份产物已重建为"已验证架构"**（体积回到 **5,437,979 / 3,549,507 / 1,268,829 B**）；
- ⚠️ 因重建，**md5 与重建前不同**（签名时间戳），本节所记 md5 为**当前磁盘值**；`oc-host.js` 仍为 **`b5057679b1a419af15d81ef72ff0fd9e`**（= 已验证值，包内可自证）。

## 7. 今天就能用的绕行（**已实测可用**，见 UX10 §3）
1. 设备上让 **`127.0.0.1:4095` 可达**（`adb reverse tcp:4095 tcp:4095`，或设备本机有服务）；
2. **清除 App 数据**（设置→应用→OpenCode Web→存储→清除数据）⇒ 壳回到默认地址 ⇒ **页面变 `http`**；
3. 在官方「添加服务器」里填 **`http://…` + 密码** ⇒ **可添加成功**（此路径已端到端复现）。
⚠️ 前提：设备必须够得到 `127.0.0.1:4095`；**够不到时只能采用 §5 的方案 A**。
