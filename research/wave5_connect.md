# Wave 5-E1 — 首启连接页 + 服务器健康检查（D3）

状态：**已完成并自测通过**（构建门绿、0 failures）。工作目录
`C:\sensorsdata\main\program\opencode-android-shell`，2026-09-20。

```
suites=85  tests=818  failures=0  errors=0  skipped=0     # app/build/test-results/testDebugUnitTest
新增 suite：Wave5ConnectTest = 29 tests, 0 failures
APK：app/build/outputs/apk/debug/app-debug.apk（30,749,623 bytes, 23:11:25）
真机/模拟器安装：**未做**（按要求由 Lead 统一验证）
```

构建门（原文命令、`--rerun-tasks` 强制真跑，非 up-to-date 假绿）：

```powershell
$env:JAVA_HOME='C:\Users\xiufe\AppData\Local\Temp\jdk21\unzip\jdk-21.0.12.1+1'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --rerun-tasks --console=plain
# Task :app:testDebugUnitTest   ← 真实执行
# Task :app:assembleDebug       ← 真实执行
# BUILD SUCCESSFUL in 1m 11s
```

---

## 1. E1 首启连接页（整屏）

### 1.1 路由兜底 / 门控

| 项 | 内容 |
|---|---|
| 文件 | `app/src/main/java/com/xiufeigo/opencodeshell/ui/App.kt:198-235` |
| 上游锚点 | `_upstream/packages/app/src/shell/routes/routes.tsx:79` — `<Show when={servers.list.length > 0} fallback={<ConnectServerScreen/>}>`；`servers/connect/screen.tsx:18-40,50-58,82-137,158-161` |
| 实现 | `OcApp` 里新增 `serverRevisionFlow`（`App.kt:208-211`）+ `connectRequired`（`App.kt:212-214`）→ `DialogHost { if (connectRequired && serverStore != null) ConnectServerScreen(...) else AppScaffold(...) }`（`App.kt:221-235`） |
| 判定函数 | `ui/servers/ServerConnectModel.kt:64` `requiresConnectScreen(servers) = servers.none { it.isComplete }` |
| 自测 | `Wave5ConnectTest.connectScreenIsRequiredOnlyWithoutAUsableServer` / `bareHostGetsHttpSchemeAndLosesTrailingSlashes` |

要点：

* **`serverStore == null` 时不门控**（preview / 测试 / 未配置 store 的调用方保持原样渲染 shell）。
* 写盘由连接页自己做：成功后 `ServerConfigStore.replaceAll(...)` → `revision` 自增 → `App.kt:212` 重新求值 → 整屏自动换成首页，**无需重启、无需回调**（`onConnected` 只是可选的旁路钩子）。
* **现存 `configMissing` / `oc_setup_hint` 内联提示全部保留、零改动**（Home `home/HomeScreen.kt:80-83,171-184`、`home/HomeSessionsList.kt:104-106`、`session/SessionScreen.kt:398-399`、`home/NewSessionScreen.kt:86,210-212`）：那是「服务器已配置但连不上」的另一种状态，与「一个都没有」互斥，不能被这次门控吞掉。
* `OcActivity` 是 manifest 的 MAIN/LAUNCHER（`app/src/main/AndroidManifest.xml:18-29`），所以 Compose 首页确实是首启进入点，连接页会真的出现在首启。

### 1.2 页面本体

| 项 | 内容 |
|---|---|
| 文件 | `app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/ConnectServerScreen.kt:78-263`（页面）、`:274-317`（`ConnectField`）、`:323-340`（`ConnectButton`） |
| 上游锚点 | `servers/connect/screen.tsx:50-137,158-161` + `servers/connect/screen.css:1-97`（逐条对齐：`.server-connect-content` `min(100%,360px)`、`gap:16px`、`margin:auto` 居中、padding `48/24/32`、brand 槽、`h1` 20/28 `530`、`p` 13 muted、field `gap:8px`、输入 44px/16px、`form` gap 20px、按钮 full-width ≥44px、错误行 `--v2-state-fg-danger`、footer + `code` 块 `8px 16px`/radius 6/`layer-01`） |
| 表单 | 服务器地址（`KeyboardType.Uri`，`ImeAction.Next`）+ 密码（`PasswordVisualTransformation`，`ImeAction.Go` → 提交）；按钮 `disabled = checking \|\| url.isBlank()`（`screen.tsx:134`） |
| 校验 + 探测 | `ConnectServerScreen.kt:93-126`：`serverAddress()` 先做地址归一化/拒绝（`screen.tsx:33-37`），再用 `ServerHealthProbe` 探一次；不健康 → `healthFailureMessage(result.failure)` **显示可读错误**（`screen.tsx:25-30,40-43`），健康 → 写 `ServerConfigStore` |
| CJK IME 防护 | 复用 `ui/settings/ServersPanel.kt:291` `normalizeUrlInput`（全角 `：/．` → ASCII），避免 Gboard 中文把地址写坏 |
| 扫码 | **未做**，且是刻意的：上游 `screen.tsx:138-157` 用 `platform.platform === "web"` 门控，Android 无 pairing 流程 |

### 1.3 纯逻辑（JVM 可测）

| 函数 | 文件 | 上游锚点 |
|---|---|---|
| `normalizeServerUrl` | `ui/servers/ServerConnectModel.kt:28` | `runtime/server/registry.tsx:15-20` |
| `serverAddress` | `ui/servers/ServerConnectModel.kt:48` | `servers/connect/pairing.ts:12-20` |
| `requiresConnectScreen` | `ui/servers/ServerConnectModel.kt:64` | `shell/routes/routes.tsx:79` |
| `connectedServer` / `withConnectedServer` | `ui/servers/ServerConnectModel.kt:75,86` | `servers/connect/screen.tsx:38-44`（`{type:"http",http:{url,password}}`，无 username） |

---

## 2. D3 健康检查接线（服务器列表三态灯）

| 项 | 内容 |
|---|---|
| 探测实现 | `ui/servers/ServerHealth.kt:196-252`（`ServerHealthProbe`），端点常量 `:47` `GET /global/health`、`:55` 兼容回退 `/api/health`、`:58` `HEALTH_TIMEOUT_MS = 3_000` |
| 纯映射 | `ui/servers/ServerHealth.kt:121` `healthResult(code, body)`、`:128` `healthBody(body)`、`:154` `healthFailureMessage(failure)` |
| 三态折叠 | `ui/servers/ServerHealth.kt:178` `ServerListItem.withHealth(result)`：`HEALTHY`（并回填 `version`）/ `UNHEALTHY` / `null → 保持 UNKNOWN`（“未探测”＝上游 `health === undefined`，`servers/registry/row.tsx:122-129`） |
| 探测接线 | `ui/settings/ServersPanel.kt:83-96`：`LaunchedEffect(bridge, bridgeRevision)` → `withContext(Dispatchers.IO) { probe.checkAll(targets) }`，结果折进 `servers`（`:96`）供 `sortServers`/行渲染使用 |
| 目标来源 | `ui/settings/SettingsServerBridge.kt:49-53` `healthTargets()`（实现 `ui/servers/ServerHealth.kt:186` `ServerHealthSource`）——**没有改 `ServerBridge` 接口**，所以 `ui/settings/SettingsScreen.kt` 零改动 |
| 渲染 | `ui/settings/ServersPanel.kt:186-201` 灯的颜色仍走 `healthColor()`（`:268-274`：success / faint / danger）；新增 `:276` `stringResourceHealthLabel` 给圆点加 `contentDescription`（`oc_health_healthy|unhealthy|unknown`），上游的灯是个无标签 `div`，加这层是为了无障碍和 QA 能在 accessibility tree 里读三态 |
| 自测 | `Wave5ConnectTest` 的 9 个 MockWebServer 用例 + 3 个映射/折叠用例 |

规格遵守情况：

* **超时 ≤3s**：`probeClient()`（`ServerHealth.kt:268`）把 `connectTimeout/readTimeout/callTimeout` 全部设为 3s。最坏路径＝首个路径 404/405（快）+ 回退路径超时（3s），即 ~3.1s；两个路径都挂死也不会超过 6s，且**永远在 IO 线程**、不阻塞 Compose。
* **失败静默**：`ServerHealthProbe.attempt()` 只 catch（`SocketTimeoutException` / `InterruptedIOException` / `IOException`）→ 返回 `HealthResult(UNHEALTHY, failure=…)`，**从不抛出**；面板不弹 toast/对话框，只把灯变红。
* **Basic auth 规则**：复用 `BasicAuth.kt:26` `basicAuthorizationHeader`（`health.ts:81-85`）——密码为空 ⇒ 完全不发 `Authorization`；用户名缺省 → `opencode`。有 MockWebServer 断言（无密码 → header 为 null；`opencode:pw`；`alice:pw`）。
* **不新增依赖**：只用已有 OkHttp + `org.json`。

---

## 3. 可选项：添加服务器对话框的保存前探测

| 项 | 内容 |
|---|---|
| 文件 | `ui/settings/ServersPanel.kt:312-401`（状态 + `submit()`）、`:503-517`（按钮文案/禁用） |
| 行为 | 保存前探一次；**不健康 → 显示可读原因 + 把主按钮换成「仍要保存 / Save anyway」，再按一次才真正保存** |
| 上游差异 | 上游 `dialog-server-v2` 直接**拒绝保存**。我们允许保存（先给理由，再让用户确认），理由写在这里：手机用户如果服务器暂时不可达或没有 health 端点，不能被挡在配置之外——这是**明说的偏差**，不是遗漏 |
| 文案 | 新增 `oc_health_save_anyway`（en/zh-rCN/zh-rTW 三语） |

---

## 4. 文案与三 locale

新增 20 个 key（en 8 个上游对应 key + 12 个 Android-only 细化 key），**只追加**：

| 文件 | 行 |
|---|---|
| `app/src/main/res/values/strings.xml` | `569-605` |
| `app/src/main/res/values-zh-rCN/strings.xml` | `478-501` |
| `app/src/main/res/values-zh-rTW/strings.xml` | `477-500` |

上游 key ↔ 本仓库 key：

| 上游 (`runtime/i18n/en.ts:415-431`) | Android key | 备注 |
|---|---|---|
| `server.connect.title` | `oc_connect_title` | 对齐 |
| `server.connect.description` | `oc_connect_description` | 对齐 |
| `server.connect.button` | `oc_connect_button` | 对齐 |
| `server.connect.address.invalid` | `oc_connect_address_invalid` | 表单校验与 `HealthFailure.InvalidUrl` 共用 |
| `server.connect.failed` | `oc_connect_failed` | `HealthFailure.Unreachable` / `null` |
| `server.connect.pair.description` | `oc_connect_pair_description` | 对齐 |
| `dialog.server.add.url` | `oc_connect_url_label` | 对齐 |
| `dialog.server.add.placeholder` | `oc_connect_url_placeholder` | **值取本仓库既有的 Android 口径 `http://10.0.2.2:4096`**（`SettingsModel.DEFAULT_SERVER_URL_PLACEHOLDER`），非上游 `localhost:4096` |
| `dialog.server.add.password` | `oc_connect_password_label` | 对齐（上游连接页就是「Password」不带「(optional)」） |
| `dialog.server.add.checking` | `oc_connect_checking` | 对齐 |
| — | `oc_connect_error_timeout` / `_auth` / `_unhealthy` / `_invalid_response` / `_status`（含 `%1$s` HTTP code） | Android-only：上游把一切失败压成一句话，这里的 exp 需要可执行的提示 |
| — | `oc_health_healthy` / `oc_health_unhealthy` / `oc_health_unknown` | 灯的无障碍标签 |
| — | `oc_health_save_anyway` | 见 §3 |

`opencode` 字标与 `opencode pair` 命令是**代码/品牌字面量，不进 i18n**（`ConnectServerScreen.kt:143-153,238-254`）。

自测：`Wave5ConnectTest.everyConnectAndHealthKeyExistsInAllThreeLocales`（三文件都存在、20 个 key 都在、中文两个 locale 必须真的翻译了——只有 `oc_connect_url_placeholder` 豁免，因为它是一个地址不是文案）。

---

## 5. 测试清单（新增 29 个，全部 JVM）

`app/src/test/java/com/xiufeigo/opencodeshell/Wave5ConnectTest.kt`

**纯逻辑（地址）**：`bareHostGetsHttpSchemeAndLosesTrailingSlashes`、`ipv6LoopbackIsAUsableAddress`、`nonHttpSchemesAreRejected`（含 `HTTP://` 大小写敏感，同上游正则）、`credentialsQueryAndHashAreRejected`、`blankAndSchemeOnlyInputsHaveNoAddress`、`pathIsKeptBecauseUpstreamKeepsIt`

**纯逻辑（健康映射）**：`liveHealthDocumentIsHealthyWithVersion`（用**本机实测原文** `{"healthy":true,"version":"1.18.31"}`）、`healthDocumentWithoutAVersionIsStillHealthy`、`documentThatReportsItselfUnhealthyIsUnhealthy`、`nonJsonAndEmptyBodiesAreInvalidResponses`、`statusCodesMapToTheirOwnFailure`、`failureMessagesAreReadableAndDistinct`、`statusFailureCarriesTheCodeAsFormatArgument`

**行折叠 / 门控 / 写盘**：`healthyProbeFillsTheRowVersionAndUnhealthyOverridesIt`、`unprobedRowStaysUnknown`、`connectScreenIsRequiredOnlyWithoutAUsableServer`、`connectingReplacesTheSameUrlAndAppendsANewOne`、`connectedRowHasNoUsernameAndNoEmptyPassword`

**网络（MockWebServer，项目已有依赖）**：`probeHitsGlobalHealthAndSendsNoAuthorizationWithoutAPassword`、`probeSendsTheUpstreamBasicCredentialWhenAPasswordExists`、`unauthorizedIsReportedAsItsOwnFailureAndNeverThrows`、`serverErrorIsReportedWithItsStatusCode`、`aSlowServerHitsTheProbeBudgetInsteadOfHanging`（`setBodyDelay` + 400ms 预算，断言耗时 < 3s）、`aDeadHostIsUnreachableNotAThrow`（`shutdown()` 后探测）、`unusableAddressNeverOpensASocket`、`aMissingGlobalHealthFallsBackToTheLegacyPath`、`aMissingBothPathsReportsTheFirstMissingPathNotAHealthyServer`、`checkAllKeysEveryResultByUrl`

**资源契约**：`everyConnectAndHealthKeyExistsInAllThreeLocales`

另外做过的**仓库外**验证：本机 `opencode` 服务在线，`GET http://127.0.0.1:4096/global/health` → `200 {"healthy":true,"version":"1.18.31"}`（PowerShell，确认映射假定与线上一致）。

---

## 6. 与上游的偏差（全部有意，逐条列出）

1. **地址 `http://` 不再返回 `http:`**。上游 `serverAddress("http://")` 会返回 `"http:"`（`normalizeServerUrl` 先补 scheme 再 `replace(/\/+$/,"")`，而浏览器 `URL.canParse("http:")` 为真）——那是个 bug，且 OkHttp 不接受无 host 的 URL，所以这里返回 `null`，表单显示 `oc_connect_address_invalid`。测试 `blankAndSchemeOnlyInputsHaveNoAddress` 钉住。
2. **健康端点带 404/405 回退**。上游只走 SDK 的 `server.info()`（即 `/global/health`）；本仓库 vendored bundle 同时探测 `/api/health` 与 `/global/health`（见 `ConnectCorsTest.vendoredBundleUsesNativeProxyNotRemoteCors`）。为了不让「只实现了旧路径的服务器」把用户永远锁在连接页，`/global/health` 返回 404/405 时才试 `/api/health`；其他状态码一律按其判决返回。
3. **`{"healthy":false}` 判为不健康**。上游只读 `version`（请求成功即 healthy，`health.ts:99`），这是本仓库能表达得更准的一点。
4. **探测无重试、3s 预算**。上游默认 30s + 2 次重试（`health.ts:17-19`）；设置面板打开时不能等 30s。
5. **细化错误文案**（上游一句话 → 6 句），见 §4。
6. **`10.0.2.2` 占位符**（Android 模拟器口径），上游是 `localhost:4096`。
7. **添加服务器允许「仍要保存」**，上游拒绝保存，见 §3。
8. **灯加了 content description**，上游灯无标签。
9. **没有扫码入口**（上游 web-only 门控）。
10. **没有逐行 `checking` 中间态**：上游 `ServerHealth.checking` 只在 SSH 隧道建立时出现（`health.ts:174`），Android 没有隧道，所以三态足够；探测期间保持 UNKNOWN（灰）。

---

## 7. 未决项 / 风险（给 Lead）

1. **两套健康实现并存（需要一次合并，见 §8）**。`OpenCodeClient.health()`（`OpenCodeClient.kt:1071`，由并发 wave 新增）与本 wave 的 `ServerHealthProbe`（`ui/servers/ServerHealth.kt:196`）都打 `GET /global/health`。为避免漂移，建议按 §8 收口。
2. **`ServerConfigStore` 未改（按边界）**，连接页只用它的既有公开 API（`listServers()` / `replaceAll()`）。副作用：`replaceAll` 的已知限制仍在——**native 写入不会回推 WebView 的 localStorage**（`ServerConfigStore.kt:186-211` 的 M4 备注，另见 `research/_M4_settings_palette_status.md`）。用连接页配置的服务器，在 WebView 端（`MainActivity`）看不到，直到 WebView 自己再 sync 一次。
3. **设备级验证未做**（未安装模拟器，按要求留给 Lead）：EncryptedSharedPreferences 落盘、IME 行为、44dp 触控高度、真机首启直显连接页，都需要一次真机/模拟器走查。建议 Lead 的验证脚本：清数据 → 启动 → 应看到整屏连接页；填 `http://10.0.2.2:4096`（或无密码的 LAN 地址）→ 点「连接」→ 进首页；随后 设置→服务器 检查灯：健康=绿；停掉服务器重开面板=红；未探测=灰。
4. **`ui/App.kt` 门控只在 Compose 入口生效**。`MainActivity`（WebView 壳）不经过 `OcApp`，其空配置提示仍是既有路径（`ChatView.kt:223` 的 `configMissing` + `view_chat.xml:88` 的 `oc_setup_hint`）。如果要让 WebView 壳也有首启连接页，那是另一个 wave 的事。
5. **`/api/health` 回退没有真机验证对象**（本机 1.18.31 走的是 `/global/health`；回退路径只有 MockWebServer 覆盖）。
6. **并发构建失败原文（按要求记录，未改任何代码）**：
   * `e: Wave5PromptHistoryTest.kt:32,88,90,111,112,113,122,141,142 No value passed for parameter 'shell'`（另一 agent 的测试文件正处于半成品状态，随后其自行修好，非本 wave 文件）；
   * `java.io.IOException: Unable to delete directory '...\app\build\test-results\testDebugUnitTest\binary'`（另一 agent 的 Gradle 同时占用同一 build 目录）；
   * `e: Daemon compilation failed: null / Failed to compile with Kotlin daemon: java.lang.Exception`（同上）。
   处理方式：等 75-110s 重试（最多重试 4 次），最后一次用 `--rerun-tasks` 得到干净的真跑绿。**没有为此改动任何仓库文件。**
7. **`assembleDebug` 有一次假绿**：普通重试时 Gradle 报 `BUILD SUCCESSFUL in 1s` 且任务全 UP-TO-DATE（说明并发的另一次构建已经产出过同样输入的输出）。为不把「别人跑绿」当自己的证据，最终结果用 `--rerun-tasks` 强制真执行取得（`Task :app:testDebugUnitTest` / `Task :app:assembleDebug` 均实际执行，1m11s）。

---

## 8. 合并建议：把两套健康实现收口为一套（给 Lead 的具体做法）

现状（两处打同一个端点）：

| | `ServerHealthProbe`（本 wave，**已接线到 UI**） | `OpenCodeClient.health()`（并发 wave 新增，**当前无人调用**） |
|---|---|---|
| 位置 | `ui/servers/ServerHealth.kt:196` | `OpenCodeClient.kt:1071` |
| 目标 | 任意 `HealthTarget`（url+username+password），可批判多个 | 只能探客户端自己的 active server |
| 超时 | 3s（connect/read/call） | 继承 `defaultClient()`：read 30s / connect 10s |
| 失败 | 返回 `HealthResult(UNHEALTHY, failure=…)`，不抛 | 抛 `ApiException`/`IOException` |
| 返回值 | 三态 + version + 失败原因 | 原始 `JSONObject` |
| 端点 | `/global/health` + 404/405 回退 `/api/health` | 只有 `/global/health` |

建议（按优先级）：

1. **保留 `ServerHealthProbe` 作为唯一实现**，把 `OpenCodeClient.health()` 改成薄委托（保留签名以免打断正在改 `OpenCodeClient` 的那个 wave）：
   ```kotlin
   /** 委托给 ui/servers 的唯一 health 实现，避免两套超时/鉴权规则漂移。 */
   fun health(): JSONObject = ServerHealthProbe()
       .check(HealthTarget(baseHttpUrl.toString(), username, password))
       .let { JSONObject().put("healthy", it.healthy).also { o -> it.version?.let { v -> o.put("version", v) } } }
   ```
   若 `OpenCodeClient.health()` 确实无人调用（现状），**更干净的是直接删掉**，并在提交信息里点名由 `ServerHealthProbe` 取代。
2. 若将来需要「探测 + 重试 + 30s 长预算」的语义（例如 SSH 隧道建立后重探），**不要**在 `OpenCodeClient` 里再写一遍，而是给 `ServerHealthProbe` 加 `retryCount` / `timeoutMs` 参数（它已经是构造参数化设计）。
3. 鉴权规则只允许有一份：`BasicAuth.kt:26` 的 `basicAuthorizationHeader`。任何新健康/连接代码都必须调它，不要自己拼 `Authorization`。
4. 若 Lead 之后决定让 `ChatViewModel` 也用健康探测（例如给 `configMissing` 更细的原因），请让它调 `ServerHealthProbe`，这样连接页 / 设置面板 / 聊天页三处对「不健康」的判定天然一致。

---

## 9. 变更文件清单（本 wave）

新增：

* `app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/ServerHealth.kt`
* `app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/ServerConnectModel.kt`
* `app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/ConnectServerScreen.kt`
* `app/src/test/java/com/xiufeigo/opencodeshell/Wave5ConnectTest.kt`
* `research/wave5_connect.md`（本文）

修改：

* `app/src/main/java/com/xiufeigo/opencodeshell/ui/App.kt`（E1 门控 + KDoc）
* `app/src/main/java/com/xiufeigo/opencodeshell/ui/settings/ServersPanel.kt`（D3 探测、灯的无障碍标签、添加对话框预探测）
* `app/src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsServerBridge.kt`（实现 `ServerHealthSource.healthTargets()`；KDoc 更新）
* `app/src/main/res/values/strings.xml`、`values-zh-rCN/strings.xml`、`values-zh-rTW/strings.xml`（只追加）

**未触碰**（按边界）：`OpenCodeClient.kt`、`ChatViewModel.kt`、`ChatStore.kt`、`ui/session/**`、`ui/theme/**`、`gradle` 文件、`_upstream/**`、`ui/settings/SettingsScreen.kt`（`ServerBridge` 接口与 `SettingsPanelExtras` 保持原样）、`ServerConfigStore.kt`、`AndroidManifest.xml`。无新增第三方依赖。未 `git commit`。

复现：

```powershell
$env:JAVA_HOME='C:\Users\xiufe\AppData\Local\Temp\jdk21\unzip\jdk-21.0.12.1+1'
.\gradlew.bat :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.Wave5ConnectTest" --console=plain
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --rerun-tasks --console=plain
# 并发 agent 占用 build 目录时：等 75-110s 重试（最多 4 次）
```
