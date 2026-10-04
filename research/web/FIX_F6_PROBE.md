# FIX_F6 · 修「口令探测发匿名请求 ⇒ 正确口令也被判成错误」（F5 引入的用户可见缺陷）

- **执行 agent**：mavis（F6）　开始：2026-10-05 22:41　硬期限：2026-10-07 之前
- **仓库**：`C:\sensorsdata\main\program\opencode-android-shell`
- **依据**：`research/web/V8_VERIFY.md` §0「本轮发现的一个真实缺陷」
- **碰过的 serial**：**`emulator-6170`**（唯一一台；起于本轮，关闭于本轮）
- **后端**：`127.0.0.1:4095`（Lead 起好，**全程未停未改**；只做了 `adb reverse`）

---

## 0. 结论速览

| 项 | 结论 | 证据 |
|---|---|---|
| 根因 | `probeAsync` 造**匿名** `Call`，在 `probe()` 里顶替掉带凭据的 `builder` | §1（行号 + 原文） |
| 改后：正确口令能否直接进官方 UI | **能。这是本轮的核心断言，成立。** | §3.1 logcat + 截图 |
| 错口令是否仍明确"用户名或口令不对" + 可重试 | **是** | §3.2 logcat + 截图 + 按钮断言 |
| 不可达是否走友好错误页 | **是，且与认证失败严格分开** | §3.3 logcat + 截图 |
| 探测是否真的带 Basic | **是**，且日志里的 `authHeaderSent` 现在读的是**实际发出去的那条请求** | §2、§3.1 |
| 口令是否入日志 | **未入**（三处核对：日志串、`Result` 字段、dex 内字面量） | §5 |
| 绊线反向验证是否真红 | **真红**（但**第一版是假绿**，已修；见 §6 的教训） | §6 |
| 回归 | `:webapp` **147 / 0 失败**（+6 用例）；`:app` **6 136 / 0 失败** | §9 |

---

## 1. 根因：行号与原文

### 1.1 F5 引入的两处请求构造（缺陷态）

`webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/BackendProbe.kt`

**第一处** —— `probe()` 造了**带** `Authorization` 的 `builder`（F5 原始 `:139-148`）：

```kotlin
val builder = Request.Builder().url(url).get()
if (!password.isNullOrEmpty()) {
    val token = Base64.encodeToString(
        "$BASIC_USER:$password".toByteArray(Charsets.UTF_8),
        Base64.NO_WRAP,
    )
    builder.header("Authorization", "Basic $token")   // ← 头只加在 builder 上
}
return try {
    val call0 = call ?: httpClient.newCall(builder.build())   // ← :148
```

**第二处** —— `probeAsync()` 为了让 `cancel()` 能拿到在途 `Call`，**又造了一条不带 `Authorization` 的 `Request`**（F5 原始 `:167-173`）：

```kotlin
fun probeAsync(backend: String, password: String?, onResult: (Result) -> Unit) {
    val call = httpClient.newCall(
        Request.Builder().url(backend.trim().trimEnd('/') + PROBE_PATH).get().build(),  // ← :169 没有 Authorization
    )
    inflight.add(call)
    Thread({
        val result = probe(backend, password, call)   // ← :173 把这条匿名 call 传进去
```

### 1.2 缺陷如何形成（关键的一行）

`probe()` 的 `:148` 是 `call ?: httpClient.newCall(builder.build())`：

- `probeAsync` **总是**传进来一个非 null 的 `call`（它自己造的**匿名**那条）；
- 于是 `?:` 左边命中，**右边那个带凭据的 `builder` 被整份丢弃、永不执行**。

⇒ **每一次探测都是匿名请求** ⇒ 后端一律回 **401** ⇒ `classify()`（`:122-128`）判成 `AuthFailed`
⇒ 探测分支**永远走不到 `launch()`** ⇒ 正确口令的用户被告知"用户名或口令不对"。

这与 `V8_VERIFY.md` §0.2 的定位完全一致，**L74 / L309-310 / L453** 三处结论由此而来。

### 1.3 为什么旧日志具有欺骗性（V8 §0.2 已指出）

旧日志的 `authHeaderSent=${!password.isNullOrEmpty()}` 只是 `if` 分支的**局部布尔值**，
它表达的是"**如果**有口令就**打算**加头"，**不是"真的加了头"**。
所以即使线上 401，日志照样打 `authHeaderSent=true` —— 排障时被这条日志带偏过。

---

## 2. 修法

### 2.1 diff（`BackendProbe.kt`，+90/-… 见 `git diff`）

三处改动：

1. **抽出唯一请求构造点** `buildProbeRequest(backend, password)`，认证头写死在里面；
2. `probeAsync` 改为 `httpClient.newCall(buildProbeRequest(backend, password))`（**匿名分支在结构上不复存在**）；
3. `probe()` 的 `call ?: …` 改为 `call ?: httpClient.newCall(buildProbeRequest(backend, password))`，且 `call` 若非 null 现在**必须**来自同一个构造点。

外加两处支撑改动：

4. **`android.util.Base64` → `java.util.Base64`**。
   前者是 Android framework 桩实现，**在 JVM 单测里抛 `RuntimeException("Stub!")`**，
   也就是说"探测请求是否带 Basic"这条断言**本来只能在设备上验**。
   `java.util.Base64` 自 **API 26** 起可用，而本模块 `minSdk = 26`（`webapp/build.gradle.kts:15`），
   于是该断言可以在 `:webapp:testDebugUnitTest` 里直接构造请求来守。**这不是可选项，是让绊线成立的前提。**
5. **日志的 `authHeaderSent` 改为读实际请求**：`call0.request().header("Authorization") != null`。
   这样一个"打算加头但没加"的 bug 在日志里**立刻可见**，不会再像 V8 那样误导排障。

### 2.2 超时值：**沿用 F5 的 8 000 ms，未改动**

依据（写在 `BackendProbe` 类注释里，本轮只补了一条第 4 点）：

1. **上界必须小于代理的连接超时**：`LoopbackProxy.defaultClient` 的 `connectTimeout` 是 **15 s**
   （`LoopbackProxy.kt:116`），探测定得比它还长就没有意义（页面早自己 502 了），故取其一半量级；
2. **必须远大于真实耗时**：本机回环 + `adb reverse` 下 `/api/info` 实测**毫秒级**
   （设备侧实测见 `research/web/FIX_F5.md` §3），8 s 是三个数量级以上，冷启动/模拟器 CPU 争抢下不会误杀；
3. **必须短到用户能接受**：这是挡在加载前的串行一步，>10 s 会被感知为"卡住"；
4. **（F6 补充）加 header 不会让这个值失效**：`PROBE_TIMEOUT_MS` 同时约束
   `connect/read/write/call` 四个超时（`:90-93`），**带凭据探测与匿名探测吃同一个 8 s 上界**，
   设备实测带凭据探测耗时 **31 ms**（`22:47:30.726 → 22:47:30.757`，见 §3.1），远在界内。

**为什么保留 8 s 而不是收紧**：本缺陷的性质是"请求没带凭据"，与超时无关；
在缺陷已由结构性修法消除的前提下改超时只会引入一个新的、未经论证的行为变化。

---

## 3. 设备正向对照（三条路径，serial `emulator-6170`）

**环境**（`_f6_devprep.txt` 原始输出）：

```
--- geometry ---
Physical size: 2560x1600 / Physical density: 320
--- after override ---           ← 走 wm，未用 -skin（`pixel_tablet` 不是合法 skin 名）
Override size: 1080x2400
Override density: 420
--- 后端在位（未停未改）---
HOST_NOAUTH_ERR=401
HOST_AUTH_STATUS=200
HOST_AUTH_BODY={"version":"2.0.14","pid":3748,"urls":["http://127.0.0.1:4095"],...}
--- adb reverse ---
host-16 tcp:4095 tcp:4095
```

安装件：`webapp/build/outputs/apk/release/webapp-release.apk`（= 本轮交付件，见 §7）。

> **输入口径说明**：设备中文输入法会把 `input text` 打成乱码（F5/V8 都踩过，见 `V8_VERIFY.md` §2.2）。
> 本轮沿用其已验证办法：`ime disable ...LatinIME` + `settings put secure show_ime_with_hard_keyboard 0`，
> 之后 `input text` 8 字符 ⇒ 口令框 8 位（非乱码）。

### 3.1 路径一：**正确口令 ⇒ 不进错误对话框，直接进官方 UI** ✅（核心断言）

logcat 原始输出：

```
I OcWebHost: credentials stored backend=http://127.0.0.1:4095 passwordPresent=true
I OcWebHost: F5 probe before load path=/api/info
I OcWebHost/Probe: probe /api/info -> 200; user=opencode passLen=8 authHeaderSent=true
I OcWebHost: F5 probe OK code=200, loading official UI
```

**`-> 200` + `authHeaderSent=true` + `probe OK code=200, loading official UI`** ——
这就是 V8 §0 里**从未出现过**的那一行。V8 当时拿到的是 `-> 401` + `AUTH FAILED`。

对话框断言（uiautomator 原始字段）：

```
HAS_AUTH_DIALOG=False
HAS_RETRY_BTN=False
```

官方 UI 真的进去了（logcat 里的资源/接口时序 + 截图）：
`tick+2 t=1277 nodes=845`，`/api/session`、`/api/project`、`/api/info` 全部 200。

**截图**：`research/web/_f6_path1_officialui.png` —— opencode **Home** 页，真实会话列表
（`Counting from 1 to 4000…` / `DRAFT_OK` / `Count from 1 to 999…` / `android-e2e` …）。

### 3.2 路径二：**错口令 ⇒ 明确"用户名或口令不对" + 可重试** ✅

logcat 原始输出：

```
I OcWebHost: credentials stored backend=http://127.0.0.1:4095 passwordPresent=true
I OcWebHost: F5 probe before load path=/api/info
I OcWebHost/Probe: probe /api/info -> 401; user=opencode passLen=8 authHeaderSent=true
W OcWebHost: F5 probe AUTH FAILED code=401; showing retryable dialog
```

可见文本（uiautomator，`_f6_p2.xml`）与逐项断言：

```
PATH2_VISIBLE_TEXT= 登录没通过 | 服务器拒绝了登录：用户名或口令不对。请确认后重试，或重新输入口令。 | 返回 | 继续加载 | 重试
PATH2_HAS_登录没通过=True
PATH2_HAS_用户名或口令不对=True
PATH2_HAS_重试=True
PATH2_HAS_继续加载=True
```

**截图**：`research/web/_f6_path2_wrongpw.png` —— 标题「登录没通过」，正文
「服务器拒绝了登录：**用户名或口令不对**。请确认后重试，或重新输入口令。」，三个按钮
**返回 / 继续加载 / 重试**。

> 注意 `authHeaderSent=true` 在 401 这一条里**也是真的** —— 这次凭据确实发出去了，
> 是后端**拒绝**了它。修复前后都是 `true`，但 200 vs 401 的差别说明了头这次真的生效了。

### 3.3 路径三：**不可达 ⇒ 友好错误页**（与认证失败分开）✅

模拟方式（`reverse --remove` 被安全策略拦，按规矩用"改错端口"法）：
`adb -s emulator-6170 reverse tcp:4095 tcp:4099`（4099 上无监听），原始输出 `host-16 tcp:4095 tcp:4099`。

logcat 原始输出：

```
I OcWebHost: F5 probe before load path=/api/info
W OcWebHost/Probe: probe /api/info failed: IOException: unexpected end of stream on http://127.0.0.1:4095/...
W OcWebHost: F5 probe unreachable reason=IOException, loading anyway
```

**"unreachable" 而不是 "AUTH FAILED"** ⇒ 三态确实分开了。宿主不硬拦，交给代理的友好错误页。

**截图**：`research/web/_f6_path3_unreachable.png` —— 「连不上服务器」+
「请检查服务器地址是否正确、设备是否连上了服务器所在的网络，然后重试。」+ 一个大号「重试」按钮。

---

## 4. 探测请求头证据（确实带 Basic）

三层证据，从强到弱：

1. **设备侧实测**：§3.1 `probe /api/info -> 200 ... authHeaderSent=true`。
   无凭据请求后端只回 401（同一台设备、同一份口令、唯一变量就是 header，见 `_f6_devprep.txt` 的
   `HOST_NOAUTH_ERR=401` / `HOST_AUTH_STATUS=200`）⇒ 拿到 200 **只可能**是凭据生效了。
2. **单测构造请求**（`ConnectRobustnessTest.probe request carries basic credentials…`）：
   断言 `req.header("Authorization")` 非空、以 `Basic ` 开头，且**解码后等于 `opencode:opencode`**
   （用户名固定 `opencode`、口令原样带上）。
3. **日志口径**：`authHeaderSent` 现在读 `call0.request().header("Authorization") != null`，
   即**实际发出去的那条请求**，不再是 `if` 分支的局部布尔值。

---

## 5. 口令不入日志的核对（专门核对，三处）

| 核对面 | 方法 | 结果 |
|---|---|---|
| 日志串 | 单测 `probe log line never contains the password`：扫 `BackendProbe.kt` 里每个 `Log.*(...)` 调用体，断言**不含** `$password` 插值；并断言结论行只出现 `passLen=` 长度记法 | ✅ 通过 |
| `Result` 类型 | 单测 `probe result carries no password field anywhere`：反射列 `Result` 全部子类的字段名，断言不含 `pass`/`secret`（F5 已有，本轮保留） | ✅ 通过 |
| 打包产物 | 扫 release APK 的 `classes.dex` 字节，查找字面量 `opencode:opencode` | ❌ **未找到**（`DEX_HAS_literal_opencode_pw_pair=False`）⇒ 无硬编码口令 |

设备侧三份 logcat（§3.1/3.2/3.3）里，口令一律只以 `passLen=8` 形式出现，**无明文**。
错误文案侧：`ConnectErrorPage.Kind.AUTH_FAILED` 的 `HINT_AUTH` 是固定串
「服务器拒绝了登录：用户名或口令不对。请确认后重试，或重新输入口令。」——
**不含口令、不含内部 URL**（`ConnectRobustnessTest` 另有断言守 `HINT_AUTH` 不含"服务器地址"，
避免把"改口令"误导成"查网络"）。

---

## 6. 绊线与反向验证

### 6.1 新增用例（`ConnectRobustnessTest` 13 → **19**，只增不减）

| 用例 | 守什么 |
|---|---|
| `probe request carries basic credentials with fixed user and given password` | 请求头带 Basic，解码 = `opencode:opencode`，端点 = `/api/info` |
| `probe request drops trailing slash and appends probe path exactly once` | 地址拼接不产生 `/api//api/info` |
| `probe request without password carries no authorization header` | 空口令**确实不发**头（不硬塞空 Basic） |
| `three state classification is distinct for credentialed responses` | 三态互不吞并：200/401/无响应分别 OK/AuthFailed/Unreachable；带状态码；文案不串 |
| `probe log line never contains the password` | 日志串不插值口令 |
| `every probe call site must build its request from buildProbeRequest` | **接线**：文件里每处 `httpClient.newCall(` 都必须用 `buildProbeRequest(` |

### 6.2 反向验证：**真红**（含一次"假绿"教训）

**第一轮反向验证 —— 假绿（必须记录）**：
把 `probeAsync` 改回 V8 原始形态（自造匿名 `Request`）后跑测试 ⇒
`BUILD SUCCESSFUL` / `TEST_EXIT=0`。**任务确实重跑了**（`:webapp:compileDebugKotlin` + `:webapp:testDebugUnitTest` 均执行），
但测试**照样绿**。原因：第一版绊线只断言了 `buildProbeRequest` **单独调用**时带凭据，
而 `probeAsync` 走的是**另一条路** —— **断言的对象与真实发出去的那条请求不是同一条路**。

**第二轮反向验证 —— 真红**：
补上 `every probe call site must build its request from buildProbeRequest`（守接线而非守构造器）后，
再次把 `probeAsync` 改成匿名请求：

```
> Task :webapp:testDebugUnitTest FAILED
ConnectRobustnessTest > every probe call site must build its request from buildProbeRequest FAILED
    java.lang.AssertionError at ConnectRobustnessTest.kt:268
147 tests completed, 1 failed
TEST_EXIT=1
```

**恢复修法后**：

```
> Task :webapp:testDebugUnitTest
BUILD SUCCESSFUL in 10s
FINAL_EXIT=0
```

> **这条教训已写进用例注释**（`every probe call site…` 的 KDoc），避免下一个人再写出只守构造器的假绿绊线。
> ⚠️ 但要注意：`webapp/build.gradle.kts:110-121` 的 `inputs.file(...)` 声明列表里**没有** `BackendProbe.kt`，
> 本轮能重跑是因为**主源码集编译产物变了**带动 `testDebugUnitTest` 重跑（已实测确认）。
> 若将来有人只改注释/格式而字节码不变，可能触发 UP-TO-DATE 跳过。**`build.gradle.kts` 本轮无权修改，故未处理**（见 §12）。

---

## 7. 新产物（体积 / md5 / 签名指纹）

`:webapp:assembleDebug` + `:webapp:assembleRelease` ⇒ **BUILD SUCCESSFUL**（`_f6_build.log` / `_f6_final.log`）。
落盘用**规范名**，**未新增带后缀的名字**。

```
FILE=opencode-shell-web.apk
  SIZE=5275479
  MD5=16c6cf8b7330e5d02879f30333800ad9
  SHA256=6308275a082c9885c35a62d8ad08ad5214bc7a7e2e4a18a9b8d6e582dd90cc0a

FILE=opencode-shell-web-release-nor8.apk
  SIZE=3975389
  MD5=76ca064b7de9246ee446f765a9198b37
  SHA256=293f25bfd2266a0e2fd47c497e3b67af104851f434527718926868f4aa2c9892
```

签名（`apksigner verify --print-certs`，build-tools 36.0.0，原样）：

```
Signer #1 certificate DN: C=US, O=Android, CN=Android Debug
Signer #1 certificate SHA-256 digest: e86b70066546bd0bdc2e6cb4d970704bc48b1a244af59c664564f030cca0510a
Signer #1 certificate SHA-1 digest: 426b682d492e08b8b5cff4a57bac2bef4070f18d
Signer #1 certificate MD5 digest: a8a01dcb84f4932ae6a181cf2524afbd
```

**签名指纹与 F5/规范件一致**（同一份 sharedDebug keystore）⇒ 覆盖安装可行。

**体积变化**：release 3,975,357 B（`627f8ed8…`）→ **3,975,389 B**（`76ca064b…`），**+32 B**。
debug 5,275,479 B（`16c6cf8b…`）。增量与 §2.2 的判断一致（只改 `BackendProbe` 一处）。

---

## 8. 包内含源自查

从 `research/deliver/opencode-shell-web-release-nor8.apk` 直接解出 dex 后扫字节：

```
EXTRACTED classes.dex 8800468
EXTRACTED classes2.dex 960316
DEX_HAS_buildProbeRequest=True      ← 修法确实进了包
DEX_HAS_api_info=True
DEX_HAS_passLen=True
DEX_HAS_authHeaderSent=True
DEX_HAS_literal_opencode_opencode=False   ← 无硬编码口令
```

**这一条同时排除了"交付的是旧包"**：包里存在 `buildProbeRequest`（F6 新增符号），
而 F5 及其之前的包**没有**这个符号。

---

## 9. 回归

| 任务 | 结果 |
|---|---|
| `:webapp:testDebugUnitTest` | **147 tests / 0 failures / 0 errors**（F5 基线 141 ⇒ **+6**） |
| `:app:testDebugUnitTest` | **6 136 tests / 0 failures / 0 errors**（`APPTEST_EXIT=0`） |

`ConnectRobustnessTest` **13 → 19**；其余 16 个 webapp 用例类数量与基线一致
（Camera 9 / DebugEval 2 / FallbackPage 2 / Ime 8 / NotifyClick 7 / NotifySession 7 /
Power 11 / RoundActivity 16 / RoundStream 13 / InputHotspot 6 / KeepScreenOn 14 /
NotifyPolicy 9 / PowerWiring 10 / SetupDialog 8 / WebViewPreflight 6）。
**用例数只增不减；未删、未放宽任何断言。**

> `:app` 侧无与 `BackendProbe` 相关的类（该类属 `webapp` 模块），故 `:app` 是**纯回归**，
> 跑它是为了证明本轮改动没有波及原生壳。

---

## 10. 交接文档改动（只改交付物小节）

`research/web/DELIVER_REBUILD_R2.md` **§2.1 体积/md5 表**（`git diff --stat` = **10 insertions / 3 deletions**，
确认只动了这一节）：

- 换成新值：release `76ca064b7de9246ee446f765a9198b37`（3,975,389 B）/ debug `16c6cf8b7330e5d02879f30333800ad9`（5,275,479 B）；
- **显式作废**三代旧值：F3 件（`5aa0b636…` / `de1a476c…`）、F5 规范件（`627f8ed8…`）、R2 件（`7b578cee…` / `b716cbeb…`）。

> **过程留痕**：第一次改这节时用了"读成字符串再整体写回"的做法，**把该文件里既有的非法 UTF-8 字节
> 洗成了替换字符**，导致 diff 炸到 160+/152-。已 `git checkout --` 还原原件，
> 改用**按字节切行、只替换目标行字节**的方式重做，最终 diff 为 10/3。**教训：该文件含历史编码破损，
> 任何文本级往返写回都必须按字节做。**

---

## 11. 收尾复核（原始输出，`_f6_cleanup.txt`）

```
=== F6 cleanup 2026-10-05T06:56:53 ===
MINE_COUNT=2
  pid=31812 avd=OC_Deliver_R2 port=6170 MINE=True
  pid=39596 avd=OC_Deliver_R2 port=6170 MINE=True
--- wm reset ---
Physical size: 2560x1600
Physical density: 320                      ← 无 Override 行 ⇒ 覆盖已复位
--- reverse list ---
host-16 tcp:4095 tcp:4095                  ← 改回正确端口（不是 4099）
--- emu kill emulator-6170 ---
OK: killing emulator, bye bye
OK
post-kill emulator/qemu process count (expect 0):  0
OC_*/Medium_Desktop_B process count (expect 0):  0
--- ports 6170/6172/6174 ---
  6170 : FREE
  6172 : FREE
  6174 : FREE
--- adb devices ---
List of devices attached                  ← 空
--- backend 4095 MUST still be alive ---
  4095 LISTEN pid=3748                     ← 与起前同一 pid ⇒ 后端全程未停未改
--- gradle lock ---
  .agent-gradle-lock exists = False
CLEANUP_DONE
```

**逐条对照硬规矩**：

| 规矩 | 遵守情况 |
|---|---|
| 谁起谁关 | ✅ 只关本轮起的 `emulator-6170`（起前 emulator/qemu 进程 = 0 ⇒ 纯新增设备） |
| 关前先确认归属 | ✅ `MINE_COUNT=2`，两个 pid 均 `avd=OC_Deliver_R2 port=6170 MINE=True` |
| 只关 `OC_*`/`Medium_Desktop_B` | ✅ 关的就是 `OC_Deliver_R2`；关后 `OC_*/Medium_Desktop_B` 进程 = **0** |
| 不许碰用户 `MCRemote_*`/`DSH_*`/`T7x_*` | ✅ 本轮从头到尾**只**用过 `emulator-6170` 一个 serial，未对任何其他 AVD 执行命令 |
| 禁止 `adb kill-server` | ✅ **未执行** |
| 禁止 `-a`/通配符 kill | ✅ **未用**；只 `adb -s emulator-6170 emu kill` 按 serial |
| 不许起停后端 | ✅ `4095` 全程 `LISTEN pid=3748`（同一 pid）；只做 `adb reverse`，未碰后端进程 |
| `reverse --remove` 被拦 ⇒ 改错端口 | ✅ 模拟不可达用 `reverse tcp:4095 tcp:4099`，事后改回 `tcp:4095` |
| 故意保留必须声明 + 原因 | ✅ 见 §12.5（两个过程解压目录 + 未手工清除的 reverse 映射） |
| 抄复核输出 | ✅ 本节即复核原文 |
| 抢锁纪律 | ✅ 每次 gradle 前等锁消失 → 写锁 → 前台跑完 → 释放；三次构建后均 `exists = False` |

**`.agent-gradle-lock` 释放确认**：`False`（`_f6_cleanup.txt` 末）。
本轮共跑了 4 次 gradle（build / test / reverse-verify×2 / final / app-test），
每次 `finally` 都释放锁，三次失败路径也确认释放（`TEST_EXIT=1` 之后仍打印 `LOCK_RELEASED`）。

**`research/HOUSEKEEPING.md` 已登记本轮关闭记录**（插入在"本轮故意保留的设备"小节之前，
字节级插入，文件原 LF 行尾与既有编码破损均未改动）。

---

## 12. 未闭环 / 需要别人接手的

1. **CDP 这次仍不可用，V8 那条弱证据未被强化**。
   实测：`adb shell cat /proc/net/unix | grep devtools` **无输出**；
   源码里**根本没有** `setWebContentsDebuggingEnabled` 调用。
   ⇒ "不可达页可见文本不含内部 URL/端口"这条，本轮证据仍是
   **截图**（`_f6_path3_unreachable.png`，肉眼可见无 URL/端口）+ **单测**
   （`unreachable page is human readable not bare json` 断言 HTML 不含 `127.0.0.1`/`4095`/`http://`）。
   **没有 CDP 级证据。** 想要 CDP 需在 release/debug 打开 `setWebContentsDebuggingEnabled`，
   那是**安全面决策**（会让任何接到 adb 的人能调 WebView），**不在本轮授权范围**，未做。
2. **`webapp/build.gradle.kts` 的 `inputs.file` 声明列表缺 `BackendProbe.kt`**（见 §6.2 末）。
   本轮实测仍会重跑，但这是**脆的**。该文件本轮**无权修改**，未动。
3. **错误对话框只做了正向断言**（标题/正文/三按钮），**未在设备上点"重试"验证它真的重新探一次**。
   `probeThenLaunch` 的重试分支是 F5 代码、本轮未改，故未额外耗时验证；**记为未验证，不记为通过**。
4. **APK 的 `aapt2 dump badging` 未重跑**（`DELIVER_REBUILD_R2.md` §2.2 那节仍是旧件的输出）。
   本轮 manifest **无改动**，但严格说该节现在对应的是旧包。**未闭环。**
5. `research/web/_f6_apkx`（失败的解压残留）与 `_f6_apk2`（成功解压）为过程目录，**未清理**（清理需删除动作，本轮未在授权动作清单内明确，且其中 `_f6_apkx` 已被后续解压覆盖）。**故意保留，原因：过程产物留档，不影响交付。**
