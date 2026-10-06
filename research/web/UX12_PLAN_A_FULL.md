# UX12 方案 A —— 最终摘要（给产品负责人）

- 轮次：**UX12**（Build → Phone → PhoneRe → PhoneDiag → Tablet → Docs）
- 日期：**2026-10-06**（Asia/Shanghai）
- 仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`
- 起点 HEAD：`dcbe158`
- 本摘要为**汇总转述**，不产生新证据；每条结论的原始输出都在下表所列报告里。

---

## 1. 一句话结论

**B-6 已修复并双档验收通过：壳现在自带官方前端（1613 个文件）并从本机 `http://127.0.0.1:4173` 加载页面，页面协议恒为 `http:` ⇒ 官方「添加服务器」既能加 http 也能加 https，那句「无法从 HTTPS 页面连接此 HTTP 服务器」红字永不触发。**
**代价是包体积变大：debug 5.44 MB → 16.8 MB、release 3.55 MB → 14.3 MB、R8 1.27 MB → 12.0 MB。** 凭据红线全程 0 命中，**没有把任何口令/代理逻辑加回来**。

---

## 2. 验收逐条对照表

| # | 验收项 | 结论 | 证据（原文所在） | 报告 |
|---|---|---|---|---|
| **1** | **官方前端自带进包**（不依赖服务器托管 UI） | ✅ **PASS** | `assets/www/ui/` **1613** 个文件 / 33.58 MiB；逐文件 MD5 `checked=1613 missing=0 hashdiff=0 bad=0`。三份 APK 包内 `assets/www/ui/*` 均 **1613** 条、`assets/www/ui/oa/*` 均 **1590** 条 | `UX12_PLAN_A_BUILD.md` §2.1–2.2、§7 |
| **2** | **页面从本机 http 来源加载**（协议恒 `http:`） | ✅ **PASS** | 注入读数 `{"href":"http://127.0.0.1:4173/","protocol":"http:","origin":"http://127.0.0.1:4173","title":"OpenCode"}`；logcat `OcLocalUi: local UI origin listening on http://127.0.0.1:4173 (static assets only)`、`OcWebHost: home=http://127.0.0.1:4173/` | `UX12_PHONE_REGRESSION.md` §1.1–1.2；`UX12_TABLET_REGRESSION.md` §1 |
| **3** | **官方「添加服务器」能加 http 并连上；红字消失** | ✅ **PASS** | 全程 `hasRed:false`（填表前 / 健康检查 14s 后 / 提交后三处）；提交后对话框关闭、段选择器新增 `127.0.0.1:4095`、Server 段**绿点**；`OcPerfJS` 出现直连 `http://127.0.0.1:4095/api/{info,location,project,session/active,vcs}` | `UX12_PHONE_REGRESSION.md` §3.1–3.6 |
| **4** | **同一页也能加 https**（不被混合内容规则拦） | ⚠️ **PASS（等价构造）** | 判据实测 `isMixedContent(http页面, https目标)=false`；设备实测 https 对话框走完全流程**零红字**，只报**通用** `Could not connect to server`（非 `…HTTP server from an HTTPS page`）。**声明：本环境无证书受信的 https 后端，"https 成功"未端到端验** | `UX12_PHONE_REGRESSION.md` §2、§4.1–4.4；`UX12_TABLET_REGRESSION.md` §4 |
| **5** | **12 项全量回归（手机档 + 平板档）** | ✅ **手机 11 PASS + 1 N/A；平板 12/12 PASS** | 手机：首轮 5 PASS + 1 N/A + 1 PARTIAL + 5 FAIL(阻塞)，补验 6 项全 PASS ⇒ 终态 11 PASS + 1 N/A。平板含审查条「+」仍弹面板、面板开终端/开文件、终端、主题、常亮（置起后 2.579 s 释放）、SSE（CLOSE→OPEN）等 | `UX12_PHONE_REGRESSION.md` §5.13；`UX12_PHONE_REVERIFY.md` §3.1–3.6；`UX12_TABLET_REGRESSION.md` §5 |
| **6** | **红线：零凭据 / 不代理 API / 无自定义页** | ✅ **PASS** | 两次凭据扫描 `HITCOUNT=0`；`refuseApiPath()` 对 `/api`、`/event` 一律 404；`/api/*` 0 命中以外 `416` 无转发代码；页面文档来自自带 `ui/index.html`（官方产物），非自研页 | `UX12_PLAN_A_BUILD.md` §3.1–3.3；`LocalUiOriginServer.kt:188-220` |
| **7** | **三份产物重建 + 包内自查 + md5 登记** | ✅ **PASS** | 三份 `assets/www/ui` 均 1613；包内 `oc-host.js` md5 **MATCH** 源文件（3/3）；`Get-FileHash` 实测三份 md5（见 §3） | `UX12_PLAN_A_BUILD.md` §6–§7 |

### 2.1 验收 #5 的首轮 FAIL 为何不算缺陷（重要，别误读）

首轮手机回归有 5 项判 **FAIL（阻塞）**，**根因只有一个**，且**不是产品缺陷**：

- 现象：会话/项目列表恒停在 `Loading`，**进不去任何会话** ⇒ 需要进入会话的 6 项全部无法取证。
- 诊断轮（CDP 网络级取证）结论：**跨源请求全程 200、零 CORS 错误**。
  `OPTIONS /api/session…` → **204**，`GET /api/session…` → **200 / 2,603 字节正文**，
  `Access-Control-Allow-Origin: http://127.0.0.1:4173` 精确回显页面 origin，`loadingFailed` **0 条**，
  console 里 `corsErrorStatus` **0 条**。
- 真实成因：Home 的服务器上下文默认停在 **`127.0.0.1:4173`**（本机静态资源来源，`static assets only`），
  该 origin **不实现 `/api/*`** ⇒ `/api/event` 90 秒内 88 次 **404 + `net::ERR_ABORTED`** ⇒ 状态机永不落地。
  **一旦把服务器上下文切到 4095（`ux12d_15_4095list.png`），列表立刻渲染出 6 个会话。**
- 首轮两处误读（已更正）：① 把 `OcPerfJS` 的 `xfer=0` 当成「响应字节为 0」——实际是**跨源请求 Resource Timing 不暴露 `transferSize`** 的测量假象（同源 323 条全有值 300–309，跨源 47 条全为 0）；② 把「4173 上下文」误当成「已连上 4095」。
- 补验轮（`UX12_PHONE_REVERIFY.md`）切到 4095 上下文后重跑，**6 项全部 PASS**。

---

## 3. 三份产物（`research/deliver/`，`Get-FileHash -Algorithm MD5` 实测）

| 产物 | 体积 | md5（当前唯一有效值） |
|---|---|---|
| `opencode-shell-web.apk`（debug） | **16,796,038 B** | **`BF688F8A033EDE65C3D4F8C5E8D1E930`** |
| `opencode-shell-web-release-nor8.apk` | **14,304,138 B** | **`B3F9D303BF557AB5CB02EED7D61D09F0`** |
| `opencode-shell-web-release-r8.apk` | **12,024,280 B** | **`F3D3911AA597122657C81072E51EDEAC`** |

> 🔴 **UX8 批三个 md5 全部作废**，不得再被当作"当前交付包"引用：
> `376ae50a…`(nor8) / `c19870db…`(R8) / `aefb3bee…`(debug)。
> UX7 批及更早的历代值同样作废。

**包内 `oc-host.js` 自查**：三份 APK 解包后 md5 均与源文件 **MATCH**。
⚠️ **口径注意**：本批**磁盘文件**是 CRLF，md5 = `8A3B3E93EBF61BEC347067D4E4074A69`；
**LF 归一化后** = `b5057679b1a419af15d81ef72ff0fd9e` —— **与 UX8 批是同一份字节内容**，只是换行符形态不同。
引用资产 md5 时必须写明是哪种形态。

---

## 4. 新旧体积对比

| 产物 | 旧（UX8 批） | 新（UX12 批） | 变化 |
|---|---|---|---|
| debug | 5,437,979 | **16,796,038** | **+11,358,059（×3.09）** |
| release nor8 | 3,549,507 | **14,304,138** | **+10,754,631（×4.03）** |
| release R8 | 1,268,829 | **12,024,280** | **+10,755,451（×9.48）** |

- 增量来源：`assets/www/ui` 下 **1613 个官方前端文件 / 33.58 MiB（未压缩）**。
- ⚠️ R8 仍从 1.27 MB 涨到 12.0 MB ⇒ **R8 仍压不动自带前端资源**（三份包内 `ui` 条目都是 1613，说明 `shrinkResources` 没有误删，但也不会去重压缩这些 chunk）。**"R8 能把包压到 1.3 MB"这个旧结论在自带前端形态下已不成立。**

---

## 5. 红线自检

### 5.1 凭据扫描：0 命中（命令 + 输出原文）

**（a）新增文件严格扫描**

```powershell
Select-String -Path 'webapp\src\main\java\com\xiufeigo\opencodeshell\web\host\LocalUiOriginServer.kt' -Pattern 'Authorization','password','passwd','Basic ','authTokenFromCredentials','KEY_SERVER_PASSWORD','Proxy','proxy' -CaseSensitive:False
```

```text
=== 3.1 LocalUiOriginServer.kt strict scan (expect 0 hits) ===
HITCOUNT=0
```

**（b）整个 main 源码树严格扫描**

```powershell
Select-String -Path (Get-ChildItem webapp\src\main\java -Recurse -Filter *.kt).FullName -Pattern 'Authorization|authTokenFromCredentials|KEY_SERVER_PASSWORD|BASIC_USER'
```

```text
=== 3.2 whole main tree strict scan ===
HITCOUNT=0
```

**（c）允许命中项核对**：`WebHostActivity.kt:64` 有一条 KDoc 注释含 `Basic opencode:<password>` 字样 —— 它**在本轮之前就存在**（旧版 `dcbe158` 同一行在第 63 行），是描述**官方 UI 自身**认证机制的文档，不是代码，宿主不持有也不转发任何口令。

### 5.2 不代理 API：结构证据

`webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/LocalUiOriginServer.kt`：

- `:188-191` —— `// 🔴 红线 3：API 一律 404，宿主不做任何转发。` `if (refuseApiPath(path)) { respond(out, 404, ...) }`
- `:217-220` —— `refuseApiPath()`：`p == "/api" || p.startsWith("/api/") || p == "/event" || p.startsWith("/event")`
- `:45-46` —— 类注释：「**只服务 UI 静态资源**…**不存在任何转发逻辑**」
- `:127` —— `ServerSocket(port, 64, InetAddress.getByName("127.0.0.1"))` ⇒ **只绑回环，外部网络访问不到**
- `:71` / `:81-82` —— `ASSET_ROOT = "www/ui"`、`URL_BUNDLE_PREFIX = "/_assets/"` → `BUNDLE_DIR = "oa"`（只做 vite 路径到磁盘目录的映射，绕开 AGP 丢弃下划线开头目录的限制，**不改写任何官方产物内容**）
- 兜底：SPA 回落（深链回 `index.html`）；资源目录里本就没有 `api/`，即使有人误塞也不会变成回环代理。

设备侧交叉印证：`/api/*` 走本机来源端点时**全部 404**，页面因此改为**直连用户配置的那台服务器**（`UX12_PHONE_REGRESSION.md` §1.4、`UX12_PHONE_DIAGNOSIS.md` §3.4）。

### 5.3 无自定义页

- 页面文档 = 自带的**官方产物** `assets/www/ui/index.html`（`ux12p_home.png` 实拍为官方 Home：`All projects` / `Search sessions` / 会话列表骨架）。
- 壳自带的注入件只有 5 个小体积 facade 文件（`index.html` / `oc-host.js` / `oc-perf.js` / `oc-plus-chooser.js` / `oc-preflight.js`），它们是**垫片**不是页面主体；**未新增任何自研界面**。

---

## 6. 环境偏差清单（本机 vs 原任务书，**逐条如实标注**）

| # | 偏差 | 实测/处置 | 影响 |
|---|---|---|---|
| 1 | **后端夹具** | `opencode` / `opencode` @ `127.0.0.1:4095`，**由协调方按任务口径重建**（pid 31812，版本 2.0.23，监听回环） | 测试可用；三份报告的收尾均复验 **pid 31812 全程未变** |
| 2 | **无 `_upstream` 工作副本** | `Test-Path _upstream` = **False** ⇒ 官方前端**不能从上游仓库构建**；资源改为**从在跑的官方服务端 v2.0.23 的 PWA precache 清单完整取回**（`research/env/opencode-dist-2.0.23/`），逐文件 MD5 校验 0 差异 | 产物**等价于构建产物**（服务端 dist 本身就是官方前端构建输出），但**构建路径与 UX11 当时（从 `_upstream` 跑 `vite build`）不同** |
| 3 | **JBR = Java 25** | Android Studio JBR 在本机是 **Java 25.0.3**，被 AGP 8.7.3 拒绝 ⇒ 改用 `C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot` | 必须显式设 `JAVA_HOME`；**建议把 `JAVA_HOME` 钉到 21**（与 §10 环境坑同源） |
| 4 | **无 `local.properties` / `ANDROID_HOME`** | 命令内联 `$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"` | 每条 gradle 命令都要带上 |
| 5 | **新 debug keystore** | 本机无 `~/.android/debug.keystore` ⇒ 按标准参数新生成（`CN=Android Debug, O=Android, C=US`） | **证书与历史交付物不同属预期**；md5 台账**以本批为准**。⚠️ 包名仍是 `…web.debug` + Android Debug 证书，**对外分发前必须换证改包名** |
| 6 | **`:app` 57 条本机预置失败** | 6136 用例中 **57 failed**。全部是本机环境缺口：① 缺 gitignore 的设备工件 `research/v97_verify/5554_md/artifacts/*.xml`；② 缺 `_upstream` 工作副本（`SshRowMenuUpstreamParityV115Test` / `ProjectParityV97Test`）；③ 同类源文件解析断言（`Mobile*` / `Composer*` / `Dialog*` / `TerminalTabStripV49Test` 等） | **与本轮无关**：`git status` 证明 `app/**` **零改动**。`:webapp` **194/194 全绿**（含新增 `LocalUiOriginServerTest` 10 条），用例数只增未减 |
| 7 | **手机 AVD 规格** | `Medium_Phone_API_37.0`：**API 37 / Android 17**、WebView **`com.google.android.webview` 145.0.7632.218**、1080×2400 / density 420 | WebView 145 满足宿主 `min=105` 门槛（`tooOld=false`）。⚠️ 与平板档**不是同 OS**（平板 API 35） |
| 8 | **上游判据函数来源** | 无 `_upstream` ⇒ `isMixedContent` 取自 `UX9_HTTP_SERVER.md:26-38` 的**逐字存档**（非当场读文件） | 函数体逐字一致（报告已声明），但**行号 3-12 无法当场复核** |

---

## 7. 已知 caveat（**必须随结论一起引用**）

1. **⚠️ https 为等价构造（最重的一条）**：本环境**没有证书受信的 https 后端**。「https 服务器可添加」由**判据实测 + 设备实测双重支撑**（`isMixedContent(http页面, https目标)=false`；设备上 https 对话框走完流程零红字，只报**通用** `Could not connect to server`），**但不是端到端验证**。若要硬证据，需一台真实 TLS 后端。
2. **⚠️ 平板档键盘项带 caveat**：该 AVD 的 WebView 视口**不随 IME 收缩**（`visualViewport.height` 恒 = `innerHeight` = 800），因此改以**不裁切**判据验证（`composerBottom=367`，`overflowPx=-433`）。**手机档**同理（`vv.height = innerHeight = 914`），已补第二重证据：交互控件实测位置 + 截图目视确认（`UX12_PHONE_REVERIFY.md` §3.5）。
3. **⚠️ 手机档 5.4「面板打开文件/终端」= N/A**：手机档上游**无审查条、无面板**（注入实测 `aside:0`、`[data-oc-panel]` 不存在、`window.ocHost` 为 `undefined`）⇒ **该入口在手机档不存在，N/A 为设计使然，不是缺失**。**平板档该功能正常**（审查条「+」仍弹面板，面板能开终端/开文件，且**不弹 Android SAF**）。
4. **⚠️ 手机档列表 `Loading` 的真实成因是"服务器上下文停在 4173"**：4173 是**只发静态资源**的 origin，不实现 `/api/*` ⇒ 事件流 404 刷屏。**把服务器上下文切到有 API 的那台，列表立刻正常**。**不需要改任何代码**。⚠️ 这不是"跨源 CORS 问题"——诊断轮已用 CDP 证明跨源 200 且有正文。
5. **⚠️ R8 收益基本消失**：自带前端后 R8 只从 1.27 MB 压到 12.0 MB（见 §4）。
6. **⚠️ 包名/证书未解决**：仍是 `com.xiufeigo.opencodeshell.web.debug` + Android Debug 证书，**仅内部灰度可用**。
7. **⚠️ 4173 端点占用**：本机来源端点固定 `4173`，若该端口被别的东西占了，壳会 bind 失败（`OcLocalUi: local UI origin failed to bind port …`）。

---

## 8. 收尾复核结果

### 8.1 协调方负责部分

| 纪律项 | 结果 | 证据 |
|---|---|---|
| **自建实例全关** | ✅ 手机 `emulator-6790`、平板 `emulator-6792` 均已 `adb emu kill`；收尾 `adb devices -l` 为空、全机 `emulator.exe`/`qemu-system-x86_64.exe` 进程数 **0** | `UX12_PHONE_DIAGNOSIS.md` §5.1/§5.4；`UX12_PHONE_REVERIFY.md` §5.3；`UX12_TABLET_REGRESSION.md` §6 |
| **端口释放** | ✅ 6790 无 `Listen`，仅剩 `TimeWait`（`OwningProcess=0`，正常 TCP 析构） | 同上 |
| **`adb reverse` / `forward` 已撤销** | ✅ `reverse --remove tcp:4095` + `forward --remove tcp:9333/9334`；**未对非自建实例建过任何绑定** | `UX12_PHONE_REVERIFY.md` §5.2 |
| **后端未起停** | ✅ **pid 31812 三轮开轮/收尾逐位一致**；进程启动时间 10:48:58 早于开工；全程只做 `Invoke-WebRequest` 只读探测 | `UX12_PHONE_REGRESSION.md` §6.5；`UX12_PHONE_DIAGNOSIS.md` §5.2；`UX12_PHONE_REVERIFY.md` §5.1 |
| **gradle 锁已释放** | ✅ `.agent-gradle-lock` 已单条顶层 `rm`，`Test-Path` = False | `UX12_PLAN_A_BUILD.md` §8 |
| **未碰非自建实例** | ✅ 手机轮发现 `emulator-6792`（他人/平板轮拉起），全程**只对 6790 下命令**，未安装/未启停/未截图/未清数据 | `UX12_PHONE_REGRESSION.md` §0.5、§6.1–6.2 |
| **未 commit** | ✅ 全链 6 个轮次**零 commit**（工作树改动待负责人统一提交） | `git status --porcelain` |
| **临时脚本已删** | ✅ `_scratch_diag.mjs` / `_scratch_ux12r_*.{mjs,ps1}` 已删；原始抓包落盘 `research/env/ux12d_capture.json` | `UX12_PHONE_DIAGNOSIS.md` §5.3 |

### 8.2 各轮改动边界

| 轮次 | 写了什么 | 是否改 `webapp/**` / `app/**` |
|---|---|---|
| UX12-Build | 5 个 Kotlin 文件（3 改 2 增）+ 1613 个资源文件 | ✅ 改（方案 A 本体）· `app/**` **零改动** |
| UX12-Phone | 只写 `research/web/**` | ❌ 未改 |
| UX12-PhoneDiag | 只写 `research/web/**` + `research/env/**` | ❌ 未改 |
| UX12-PhoneRe | 只写 `research/web/**` | ❌ 未改 |
| UX12-Tablet | 只写 `research/web/**` | ❌ 未改 |
| **UX12-Docs（本轮）** | 只写 `HANDOFF_WEB_SHELL.md` / `BUG_QUEUE.md` / 本文件 | ❌ **未改** |

---

## 9. 报告索引

| 报告 | 内容 |
|---|---|
| `research/web/UX12_PLAN_A_BUILD.md` | 落地：5 文件逐字节恢复、1613 资源拷入与校验、凭据扫描、单测 194/194、反向验证真红 3 条、三产物构建与包内自查、收尾 |
| `research/web/UX12_PHONE_REGRESSION.md` | 手机档：页面来源、判据实测、http 添加成功（绿点+API 流量）、https 等价构造、首轮 12 项逐条 + 收尾 |
| `research/web/UX12_PHONE_DIAGNOSIS.md` | **CDP 抓包诊断**：17 条跨源请求全 200、`xfer=0` 是测量假象、`Loading` 真实成因、最小复现 |
| `research/web/UX12_PHONE_REVERIFY.md` | 手机档补验：切到 4095 上下文后 6 项全 PASS（终端 / 终端条「+」/ 发消息 / 切会话 / 键盘两屏 / 回合常亮） |
| `research/web/UX12_TABLET_REGRESSION.md` | 平板档 12/12 PASS（含审查条「+」仍弹面板、面板开终端/开文件）+ 键盘 caveat + 收尾 |
| **`research/web/UX12_PLAN_A_FULL.md`** | **本文件**（最终摘要） |

---

## 10. 遗留与建议（供决策，不在本轮范围）

1. **对外分发前**必须换证书 + 改包名（§7.6）。
2. **体积**（§4）是方案 A 的已知代价；若要压回 1–3 MB 量级，需要重新讨论"是否必须自带完整官方前端"（例如只带关键路由 chunk、或退回"服务端托管 UI"形态并接受 B-6 无法修复）。
3. **https 硬证据**需要一台真实 TLS 后端（§7.1）。
4. **4173 被占**时的降级行为（bind 失败后壳起什么页面）目前没有专门验收项。
5. `JAVA_HOME` **钉到 21**（§6.3）——否则 gradle 会在别的机器上复现同一个 `IllegalArgumentException: 25.x` 失败。
