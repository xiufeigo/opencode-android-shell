# UX9：官方「添加服务器」拒绝 http 服务器（"无法从 HTTPS 页面连接此 HTTP 服务器"）

- **轮次**：UX9（协议与连接）
- **日期**：2026-10-06
- **BUG 编号**：B-6（P0）
- **本轮代码改动**：**0**（未改任何源码 ⇒ 未重建产物、未动凭据、未加自定义页）
- **一句话结论**：那句红字是**上游的文案选择器 + 浏览器的混合内容策略**，判据已用行号和原文钉死；但产品负责人想要的"从一页同时加 https 和 http 两台"这个能力，**在当前 UX6 架构下必然要求第二台服务器开 CORS**，而"零配置做到"的那条路（回环代理）正是 UX6 已按产品负责人确认删除、且被红线禁止的。**⇒ 按 B-6 第 54 行的指示停下，交选项与代价给产品负责人定。**

---

## 0. 现象（产品负责人实测，手机档，2026-10-06 09:12）

官方「添加服务器」对话框填 `http://8.138.19.15:4094` + 密码 ⇒ 红字：

> 无法从 HTTPS 页面连接此 HTTP 服务器。请使用 HTTPS 服务器地址。

产品负责人原话（09:12）：「官方我记得是可以添加 http 的服务器的」
产品负责人定性（09:2x，写入 B-6）：「最初我添加了一个 https 的后端，但是官方是可以既添加 https 也可以添加 http 的后端的。所以这个应该就是你们的 bug」

---

## 1. 上游那条校验在哪（**已查清，行号 + 原文**）

上游工作副本 `_upstream`，分支 `v2`，HEAD `702a73d`。

### 1.1 判据函数 —— `_upstream/packages/app/src/servers/connect/browser.ts:3-12`（全文）

```ts
export function isMixedContent(page: string, address: string) {
  if (new URL(page).protocol !== "https:") return false
  const normalized = serverAddress(address)
  if (!normalized) return false
  const url = new URL(normalized)
  if (url.protocol !== "http:") return false
  // Secure Contexts treats loopback HTTP origins as potentially trustworthy.
  const host = url.hostname.replace(/\.$/, "")
  return !(host === "localhost" || host.endsWith(".localhost") || host === "[::1]" || /^127(?:\.\d+){3}$/.test(host))
}
```

**判据到底是什么（逐条回答 B-6 三个候选）**：

| B-6 的候选 | 答案 | 依据 |
|---|---|---|
| `location.protocol === 'https'`？ | ✅ **正是** | `browser.ts:4` 的 `new URL(page).protocol !== "https:"`；实参 `page` 就是 `dialog.tsx:285` 传入的 `location.href` |
| `isSecureContext`？ | ❌ **不是** | 全文无 `isSecureContext`；它只被相机那两处用（`dialog.tsx:181`、`camera.ts:6`）。`file://` 的 `isSecureContext=true` 但 `protocol` 非 `https:`，仍判 false |
| 输入 URL 与页面协议比较？ | ⚠️ **半对** | 不是"协议相等性"，而是**页面 https ∧ 目标 http ∧ 目标非回环**这个**组合** |

**⇒ B-6 第 52 行的假设已被排除**：判据**不是** `isSecureContext`，所以"换成 `http://127.0.0.1:<port>` 页面就能解决"这条路**在判据上是成立的**（`browser.ts:4` 会短路返回 false）。

补充：`browser.ts:10-11` 有**回环豁免**（`localhost`/`*.localhost`/`[::1]`/`127.x.x.x`），依据注释是 Secure Contexts 把回环 HTTP 视为 potentially trustworthy。**所以官方并不是"一律禁止 http"。**

### 1.2 调用点 —— `_upstream/packages/app/src/servers/connect/dialog.tsx:280-291`（原文）

```tsx
      const result = await checkServerHealth(connection.http)
      if (!result.healthy) {
        setStore(
          "error",
          language.t(
            platform.platform === "web" && isMixedContent(location.href, normalized)
              ? "server.connect.mixedContent"
              : "dialog.server.add.error",
          ),
        )
        return
      }
```

**关键结构事实：闸门是第 281 行 `if (!result.healthy)`，不是第 285 行。**
`isMixedContent` 只在健康检查**已经失败之后**参与，用来**挑更具体的那句文案**；删掉第 285 行，`return` 依然执行、服务器依然加不上。
⇒ **那句话不是"规则"，是"诊断文案"。真正的失败发生在它之前。**
同源逻辑另有 `_upstream/packages/app/src/servers/connect/screen.tsx:25-30`（配对扫码页），判据一致。

### 1.3 报错原文 —— `_upstream/packages/app/src/runtime/i18n/en.ts:436-437`

```ts
  "server.connect.mixedContent":
    "Could not connect to this HTTP server from an HTTPS page. Use an HTTPS server address instead.",
```

与产品负责人看到的中文红字逐字对应。

### 1.4 上游**没有**禁止 http —— `_upstream/packages/app/src/runtime/server/registry.tsx:15-20`

```ts
export function normalizeServerUrl(input: string) {
  const trimmed = input.trim()
  if (!trimmed) return
  const withProtocol = /^https?:\/\//.test(trimmed) ? trimmed : `http://${trimmed}`
  return withProtocol.replace(/\/+$/, "")
}
```

填 `http://` 原样保留 ⇒ **上游本身完全接受 http 服务器。**

---

## 2. 官方什么形态下允许 http（**已查清，不含糊**）

`dialog.tsx:285` 带前置条件 `platform.platform === "web"`，**这条文案只在 web 平台出现**；闸门（`!result.healthy`）与平台无关。

| 形态 | 页面 origin | `isMixedContent` | 能否加 http 服务器 | 原因 |
|---|---|---|---|---|
| **桌面 Electron（官方主战场）** | `file://`（打包后） | **false** | ✅ **能** | `browser.ts:4` 协议非 `https:`，直接短路 |
| 官方 web 端用 http 打开后端页面 | `http://<host>:<port>` | **false** | ✅ **能** | 同上 |
| 官方 web 端 https 托管 + **回环**目标 | `https://<host>` | **false** | ✅ **能** | `browser.ts:10-11` 回环豁免 |
| 官方 web 端 https 托管 + **非回环** http 目标 | `https://<host>` | **true** | ❌ **不能** | 混合内容被网络层拦下，健康检查失败 |

**⇒ 对"官方可以既添加 https 也可以添加 http"这句话的精确回答（B-6 第 53 行要求的核实）：**

> **在官方桌面 Electron 端：成立。** 页面 origin 是 `file://`，`browser.ts:4` 直接短路，**混用判断根本不生效**，http 服务器照常能加。官方能"既加 https 又加 http"，靠的就是**桌面外壳有一页不隶属于任何服务器**。
>
> **在官方 web 端：不成立。** 官方 web 与我们的壳**跑的是同一份上游代码**（`servers/connect/dialog.tsx` / `browser.ts`），所以**官方 web 端在 https 页面下同样会拒非回环 http**。B-6 第 53 行问的"官方 web 形态是否也拒"——**答案是：也拒，同一份代码。**
>
> ⇒ **"官方可以"的记忆来源是桌面 Electron 形态；我们当前是"服务端托管页面"形态，没有那一页独立的 origin。** 这是形态差异，不是我们对规则的实现有偏差。

---

## 3. 我们 APP 里页面实际是什么协议（**已设备实测，原文读数**）

### 3.1 读数环境

- 自建实例 `emulator-6752`（AVD `OC_UX6_Phone`，**产品负责人实例一台未碰**），几何 `1200x2670` / density 420，WebView `124.0.6367.219`（非 83，无白屏）。
- 包 `com.xiufeigo.opencodeshell.web.debug`（**debug 包：CDP 注入通道 DEBUG-only**），入口 `com.xiufeigo.opencodeshell.web.WebHostActivity`。
- `adb reverse tcp:4095 tcp:4095`（**只 reverse，后端全程未起停**）。
- CDP：`adb forward tcp:9339 localabstract:webview_devtools_remote_3056` → `Runtime.evaluate`（脚本 `research/web/ux9/cdp-eval.mjs`）。

### 3.2 原文读数（verbatim）

```json
{
  "location_protocol": "http:",
  "location_origin": "http://127.0.0.1:4095",
  "location_href": "http://127.0.0.1:4095/",
  "document_URL": "http://127.0.0.1:4095/",
  "isSecureContext": true,
  "hasOpenCodeAndroid": "object",
  "proxyOrigin": "",
  "proxyPrefix": ""
}
```

`/json/list` 页面条目同样是 `"url": "http://127.0.0.1:4095/"`。

### 3.3 在真实页面里执行上游判据（verbatim）

把 `browser.ts:3-12` 逻辑原样注入真实页面跑：

```json
{
  "pageIsHttps": false,
  "caseA_pageHttp_targetLoopbackHttp": false,
  "caseB_pageHttp_targetRemoteHttp": false,
  "caseC_pageHttps_targetRemoteHttp": true,
  "caseD_pageHttps_targetLoopbackHttp": true
}
```

回环豁免单独复测：`127.0.0.1` → 豁免 true；`8.138.19.15` → 不豁免 false。
（首轮注入时正则 `$` 转义有误导致 caseD 误报，已单独复测修正。）

**⇒ `caseC` 是唯一命中那句红字的组合。默认配置下我们页面是 `http:`（caseB=false），不会出现这句话。**
**⇒ 产品负责人看到那句红字 ⇒ 他那一台当时的页面协议是 `https:`，与他"最初添加了一个 https 的后端"的自述完全吻合。**

---

## 4. https 从哪来（**已查清；B-6 的两处初始猜测都需更正**）

### 4.1 更正一：不是 asset loader

`app/src/main/java/com/xiufeigo/opencodeshell/MainActivity.kt:737-739`（`EMBEDDED_INDEX_URL = "https://appassets.androidplatform.net/..."`）属于**旧原生壳 `app` 模块**；现行 WebView 壳是 **`webapp` 模块的 `WebHostActivity`**，不再用它承载首页。
在现行壳里 `https://appassets.androidplatform.net` 只剩**诊断页**用途 —— `WebHostActivity.kt:74-79`：

```kotlin
        /**
         * 诊断页 URL 基座（资产托管）。R1 起**不再**用它当首页，
         * 只在 G1 前置检查失败/主文档加载失败时呈现 …
         */
        const val LEGACY_PLACEHOLDER_URL = "https://$ASSET_DOMAIN$ASSET_PREFIX/www/index.html"
```

### 4.2 更正二：不是"我们把 http 改成了 https"，而是**页面 URL 就是后端地址**

`webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt:318-324`：

```kotlin
     * 唯一来源 = [SecretStore.KEY_BACKEND_URL]（**不是秘密**，只是"应用壳从哪个
     * 后端取官方 UI"的入口；默认 `http://127.0.0.1:4095`，靠 adb reverse 打通）。
    private fun bootUrl(): String =
        SecretStore.getSecret(this, SecretStore.KEY_BACKEND_URL) ?: SecretStore.DEFAULT_BACKEND_URL
```

`WebHostActivity.kt:337-361`（UX6 启动链）：

```kotlin
    private fun launch(backend: String) {
        ...
        val url = if (restored != null && restored.startsWith(backend)) restored else "$backend/"
        ...
        webView.loadUrl(url)
```

架构说明 `WebHostActivity.kt:56-63`：

```
 * UX6（当前架构）：**直连真实服务器，无回环代理**。
 * 启动链 = `bootUrl()`（SecretStore 里的后端地址，默认 `http://127.0.0.1:4095`，
 * 靠 `adb reverse tcp:4095 tcp:4095` 打通）→ G1 前置检查 → `loadUrl(后端地址)`。
 * 官方前端由后端自托管 … 页面 origin == API origin，CORS 不参与；
```

### 4.3 结论

**页面协议 = 用户配置的后端地址的协议（`WebHostActivity.kt:323-324` → `:340` → `:360`）。我们没有改写来源，也没有把 http 升级成 https。**
他先加了 https 后端 ⇒ 那一页就是 https ⇒ 官方按 `location.protocol` 的判断命中。**这与他的自述逐字吻合。**

---

## 5. 决定性发现：真正的第二道墙是 **CORS**，不是混合内容

### 5.1 实测：opencode 服务端默认不发 CORS 头

对在跑的后端发起带 `Origin` 的请求（只读探测，未起停后端）：

```
GET http://127.0.0.1:4095/global/health   (Origin: https://appassets.androidplatform.net)
→ status=200
→ Access-Control-Allow-Origin = （空）
→ Access-Control-Allow-Credentials = （空）
```

**⇒ 服务端默认 0 个 CORS 头。**

### 5.2 这意味着什么（架构层面，如实陈述）

UX6 之所以删掉回环代理、之所以"不需要 CORS"，**唯一的依据就是"页面 origin == API origin"**（`WebHostActivity.kt:59-61`）。
一旦页面与目标服务器**不同源**，就必然要走 CORS，而服务端默认不发 ⇒ **失败**。

于是有两条各自独立的墙：

| 墙 | 触发条件 | 我们能不能单方面解决 |
|---|---|---|
| **① 混合内容** | 页面 https ∧ 目标非回环 http | 理论可 `setMixedContentMode` 放行 |
| **② CORS** | 页面 origin ≠ 目标 origin（**任何协议组合都成立**） | **不能** —— 必须目标服务器发 `Access-Control-Allow-Origin` |

**⇒ 放行 ① 不解决 ②。** B-6 设想里的"方案 b：`setMixedContentMode`"**在跨源场景下无效**。
⇒ 顺带：我们壳里 `WebHostActivity.kt:388` 现在是 `MIXED_CONTENT_COMPATIBILITY_MODE`（最弱档，**不允许混合 XHR/fetch**），改成 `ALWAYS_ALLOW` 只能拆掉 ①，② 依然在。

### 5.3 所以"加第二台服务器"这件事的真实前提

| 页面协议 | 想加的目标 | 混合内容 | CORS | 结果 |
|---|---|---|---|---|
| http | 远端 http | 不涉及 | **需要**（服务端默认不发） | ❌ |
| http | 远端 https | 不涉及（http 页面引 https 合法） | **需要** | ❌ |
| https | 远端 http | **需要放行** | **需要** | ❌ |
| https | 远端 https | 不涉及 | **需要** | ❌ |

**⇒ 当前 UX6 形态下，"添加第二台（不同源的）服务器"无论协议怎么组合，都要求那台服务器开 CORS。** 这不是我们实现有偏差，是"页面由后端自己托管"这一形态的固有约束。

---

## 6. 决策：按 B-6 第 54 行**停下并上交选项**（不自行绕过）

B-6 第 54 行原文要求：
> 「若最终结论是"必须改架构/无法在不碰凭据的前提下做到"，**必须停下并把选项与代价交给产品负责人定**，**不许偷偷把凭据处理加回来**。」

**本轮正是这个情形**，且还有一处 B-6 自身的矛盾必须点明：

> B-6 第 50 行建议的修法是"重新引入一个本机 http 来源，让页面从 `http://127.0.0.1:<port>` 加载"；
> 而 B-6 第 51 行的红线要求新代码 **`ServerSocket` 0 命中**。
> **一个本机 http 来源必然要用 `ServerSocket` ⇒ 建议修法与自家红线自相矛盾。**

### 6.1 四个选项与代价（供产品负责人选）

| # | 方案 | 代价 / 风险 | 是否偏离"以官方为准" | 红线 |
|---|---|---|---|---|
| **A** | **把应用的后端地址指向 `http://8.138.19.15:4094`**（零代码，今天就能用） | 该页变成 http ⇒ `caseB=false` ⇒ **红字消失，官方对话框对 http 正常可用**。代价：这一台要先能 `adb reverse`/网络可达；且 Basic 口令走明文 http | 否 —— 正是官方 web-over-http 形态 | ✅ 零改动 |
| **B** | **给服务端加 TLS**，全程 `https://` | 需证书与运维成本 | 否 | ✅ 零改动 |
| **C** | **让第二台服务器开 CORS**（`opencode serve --cors <页面origin>`） | 需改服务端启动参数；**注意 http 目标仍受墙①约束**，所以还得配合"页面为 http"（即 A）才生效 | 是（改服务端约定） | ✅ 零代码 |
| **D** | **改架构：页面改从本机 http 来源加载** | ①需要 `ServerSocket`（撞 B-6 红线）；②**会把"无需 CORS"这个现有优点打掉** —— 所有服务器都变成跨源，全部需要 CORS；③必须重建三份产物 + 设备双档验证 | **是**（官方桌面靠 `file://` 拿独立 origin，不是这条路） | ❌ 撞红线 |
| **E** | **重新引入回环代理** | 能做到零配置、协议全兼容 —— 但**代理必须转发 API（含 Basic 认证头）＝ 凭据处理** | — | ❌ **明确禁止** |

### 6.2 本轮为什么不自己动手

- 选 D：撞 B-6 自家 `ServerSocket` 红线，且会**回退** UX6 刚换来的"不需要 CORS"这个产品价值。
- 选 E：撞"不许引入凭据处理"红线 + `DirectConnectAuthBoundaryTest`。
- 选 A/B/C：**零代码、零回归风险、当天可用**，但属于**产品/运维决策**（要不要给服务端加证书、要不要开 CORS、用不用明文 http），**不该由我替他定**。

**⇒ 本轮不擅自改代码，按 B-6 第 54 行上交。**

---

## 7. 为什么没重建产物

判定为「需要产品负责人先做架构/运维决策」⇒ 无代码改动 ⇒
- **未修改任何源码**（`webapp/src/main/assets/www/oc-host.js` 与各 patch 均原样）；
- **未重建三份产物**（无改动即无新产物；强重建只会产出与 `research/deliver/` 字节相同的文件并制造无意义的 md5 变动）；
- **未新增/删除自定义页或灰页**；
- **未引入任何凭据处理**（`DirectConnectAuthBoundaryTest` 10/10 全绿，见 §8）。

本轮**新增文件仅两个**：本报告与取证脚本 `research/web/ux9/cdp-eval.mjs`（研究用，不进包）。

---

## 8. 回归与红线自检

### 8.1 回归

命令：`.\gradlew.bat --console=plain :webapp:testDebugUnitTest :app:testDebugUnitTest`

| 模块 | 测试类数 | 用例数 | failures | errors | skipped |
|---|---|---|---|---|---|
| `webapp` | 23 | **184** | **0** | 0 | 0 |
| `app` | 677 | **6136** | **0** | 0 | 0 |
| **合计** | **700** | **6320** | **0** | **0** | **0** |

`BUILD SUCCESSFUL`；**用例数与上一轮一致（0 增 0 减，本轮未加也未删用例）**。

⚠️ 首次执行**失败于环境，与本轮无关**：`JAVA_HOME` 指向 JDK `25.0.4.1`，Gradle 8.11.1 的
`CacheConfigurationsHandlingSettingsLoader.findAndLoadSettings` 抛 `IllegalArgumentException: 25.0.4.1`。
改用 Android Studio 自带 **JBR 21.0.10** 重跑即全绿。**建议把 `JAVA_HOME` 钉到 21，否则下一轮会再撞。**

### 8.2 红线

| 红线 | 结果 |
|---|---|
| 不许引入凭据处理 | ✅ 未改任何代码；`DirectConnectAuthBoundaryTest` **tests=10 failures=0 errors=0** |
| 不许加回自定义页/灰页 | ✅ 未新增页面；`webapp/src/main/assets/www/` 仍为原有 5 个注入件（`index.html` / `oc-host.js` / `oc-perf.js` / `oc-plus-chooser.js` / `oc-preflight.js`），无自定义页/灰页 |

---

## 9. 本轮未做到 / 需说明的边界（如实记录）

1. **没有在设备上端到端复现那句红字** —— 需要一个受信任的 https 后端才能让页面变成 https。本轮改为**在真实页面里执行上游判据函数**并给出 `caseC=true` 的原文输出，配合 §3.2 的页面协议实测，逻辑链闭合。**结论可靠，但严格说是"判据实测 + 推理"，不是端到端复现。**
2. §3.3 那次 `fetch` 失败**不能区分**混合内容与网络不可达（模拟器到 `8.138.19.15` 的路由未验证）。
3. **没有验证产品负责人那台的后端地址确实是 https**（不允许碰他的实例）—— 依据是他自己的自述 + `caseC` 是唯一命中组合。
4. **CORS 结论（§5.1）只对本机 4095 后端实测**；`8.138.19.15:4094` 那台是否发 CORS 头**未实测**（不在本机，且不应去探测他的服务器）。
5. **"官方桌面 Electron 的 origin 是 `file://`"是代码层推断**（依据 `browser.ts:4` 只看协议、且本项目 `WebHostActivity.kt:49` 记录了 `file://` 的 origin 为 opaque），**本轮未实际运行官方 Electron 验证**。

---

## 10. 收尾复核输出（原始输出）

```
=== (a) 自建实例残留 ===
adb devices: 0 台（自建 emulator-6752 已关，无残留）
=== (b) 端口释放 ===
6752 / 9339: 均未监听（已释放）
=== (c) 后端未起停 ===
4095 LISTEN pid=3748 addr=127.0.0.1     ← 与开工前同一 PID，全程未起停
=== (d) gradle 锁 ===
锁已释放
=== (e) 工作区改动（本轮只应动文档）===
 M HANDOFF_WEB_SHELL.md
 M research/HANDOFF.md
 M research/web/BUG_QUEUE.md
 M research/web/UX6_OFFICIAL_ENTRY.md
?? research/web/UX9_HTTP_SERVER.md
?? research/web/ux9/                     ← 取证脚本 cdp-eval.mjs（研究用，不进包）
```

**⇒ 改动全部落在文档，零生产源码改动**（`git status` 无任何 `app/**`、`webapp/**`、`frontend/**` 条目）。

### 碰过的 serial

- **`emulator-6752`** —— 本轮自建（AVD `OC_UX6_Phone`，端口 6752）。**已关，无残留。**
- **产品负责人的实例（`DSH_*` / `T*` / `Pixel_*` / `MCRemote_*`）：一台都没有碰过。** 全程未对它们执行任何 `adb` 命令。
- 未执行 `adb kill-server`、未使用 `-a`、未使用任何通配符；关闭只针对 `emulator-6752` 单台。
- 只做了 `adb reverse tcp:4095 tcp:4095`（任务允许）与 `adb forward tcp:9339`（取证用，收尾已 `--remove`）。
