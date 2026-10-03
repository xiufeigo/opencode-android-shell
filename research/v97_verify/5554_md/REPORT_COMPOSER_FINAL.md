# Composer 收口终验 · 第 2 轮（emulator-5554 / Medium_Desktop）

**状态：BLOCKED**（本轮阻塞点已从「app 侧零套接字」推进到「**握手被 app 自己的代际探针判死**」，且已定位到代码行）

- 设备：`emulator-5554`（`sdk_gpc_x86_64` / Medium_Desktop）
- Override：`wm size 1366x768` / `wm density 160`
- 验收量口径来源：`research/v97_fix/TRIPWIRE_CRITERION_FIX.md`（Lead 裁定）
- 本轮**未触碰** `emulator-5590`（未对 5590 执行任何命令）

> 相对第 1 轮的进展：第 1 轮是「app 存着旧的加密地址 → 设备侧零套接字」。Lead `pm clear` 后本轮 app **确实开了套接字、确实拿到了 HTTP 200**，阻塞点前移到 app 的**代际探针判定**，可以精确定位到 `ServerHealth.kt:224-228 / 150-157` + `V2Capability.decideInfo`。

---

## 1. 构建与归属核对

| 项 | 期望 | 实测 | 判定 |
|---|---|---|---|
| APK MD5 | `940476cd6cca62f541449a1f6aea994e` | `940476CD6CCA62F541449A1F6AEA994E`（`app/build/outputs/apk/debug/app-debug.apk`） | ✅ 一致 |
| 设备 `lastUpdateTime` | `2026-10-03 14:46:48` | `lastUpdateTime=2026-10-03 14:46:48` | ✅ 一致 |
| `versionName` / `versionCode` | — | `1.4.0` / `26` | 记录 |
| 设备安装路径 | — | `/data/app/~~W1n_VFCokgXc8h98laXpKw==/com.xiufeigo.opencodeshell-6T8JhcfgDcpZYMvdDuWBkg==/base.apk` | 记录 |

**构建核对 PASS。** 未跑 gradle、未装机、未替换 APK。

### 设备归属

每次交互前 `mCurrentFocus` 取值：

| 阶段 | mCurrentFocus | 说明 |
|---|---|---|
| 冷启首帧 | `com.google.android.permissioncontroller/…GrantPermissionsActivity` | ⚠️ 见下方「纪律偏离」 |
| 授权后 / 全部配置交互 | `com.xiufeigo.opencodeshell/com.xiufeigo.opencodeshell.OcActivity` | ✅ 本 app |
| 两次 Connect 点击前 | `…oc.xiufeigo.opencodeshell.OcActivity` | ✅ 本 app |

> **纪律偏离（唯一一处，已记录）**：`pm clear` 后首次冷启，app 自身请求通知权限，系统 `permissioncontroller` 的 `GrantPermissionsActivity` 取得焦点，文案为 **“Allow OpenCode to send you notifications?”**。这是**本 app 首次运行的系统授权弹层**（不是外部 app 抢占焦点），不处理则第 0 步无法开始。故按 app 引导点了 `Allow`（按钮 bounds `[416,348][950,404]`，中心 `683,376`，由 `d0.xml` 解析）。授权后焦点立即回到 `OcActivity`。除此之外**全程无一次交互发生在非本 app 窗口上**。

### 通道 / 主机端

```
reverse: host-19 tcp:4095 tcp:4095     ✅
reverse: host-19 tcp:2222 tcp:22       ✅
主机 4095 LISTEN 127.0.0.1:4095 (PID 23888)  ✅
进程: opencode.exe serve --port 4095 --hostname 127.0.0.1
```

---

## 2. 第 0 步：配地址 + 证明能拿到会话 → ❌ 失败

### 2.1 首屏（清数据后）全部节点

`am start -n com.xiufeigo.opencodeshell/.OcActivity` → 首屏为 app 自身引导页 **Connect to a server**。`uiautomator dump --compressed` 共 **16 节点**（`d1.xml` / 截图 `s1.png`）：

| # | class | bounds | clickable | text / desc |
|---|---|---|---|---|
| 4 | TextView | `[626,117][740,146]` | false | `opencode` |
| 5 | TextView | `[588,178][779,206]` | false | `Connect to a server` |
| 6 | TextView | `[510,214][856,234]` | false | `Enter your server address and password to get started.` |
| 7 | TextView | `[503,266][598,282]` | false | `Server address` |
| **8** | **EditText** | `[515,289][851,337]` | **true** | （空，服务器地址输入框） |
| 9 | TextView | `[503,354][564,370]` | false | `Password` |
| **10** | **EditText** | `[515,377][851,425]` | **true** | （空，密码输入框） |
| **11** | **View** | `[503,436][863,484]` | **true** | 包 `Connect` 文本 |
| 12 | TextView | `[653,451][714,470]` | false | `Connect` |
| **13** | **View** | `[503,496][863,544]` | **true** | 包 `Scan QR code` 文本 |
| 14 | TextView | `[632,511][734,530]` | false | `Scan QR code` |
| 15 | TextView | `[503,574][863,614]` | false | `Run this command on your computer to get your connection details.` |
| 16 | TextView | `[638,634][729,654]` | false | `opencode pair` |

**可点节点共 4 个**：地址框 `#8`、密码框 `#10`、`Connect #11`、`Scan QR code #13`。

### 2.2 地址配置过程（两种地址都试过）

坐标全部**运行期从 dump 解析**（键盘弹出后布局上移，每次动作前重新 dump 重解析；切换地址用 `am force-stop` + 冷启把表单复位为空，避免清空竞态）。

| 尝试 | 地址 | 密码 | 结果文案（dump 原文） | 截图 |
|---|---|---|---|---|
| 首试 | `127.0.0.1:4095` | 空 | **`Something answered, but it is not an OpenCode server.`** `[503,420][863,440]` | `s4.png` |
| **A** | `http://127.0.0.1:4095` | 空 | **`Something answered, but it is not an OpenCode server.`** `[503,420][863,440]` | `sA.png` |
| **B** | `http://10.0.2.2:4095` | 空 | **`Something answered, but it is not an OpenCode server.`** `[503,420][863,440]` | `sB.png` |

> 也曾以密码 `opencode` 试过；两种地址在**带/不带密码、带/不带 scheme** 下均得到**逐字相同**的失败文案，故判定与凭据、与 scheme 无关。

### 2.3 拿到会话的证据 → ❌ **没有拿到**

- 会话列表：**不可达**（`Connect` 永远失败，无法进入 Home）
- 自建会话（`E2ECHK5554u1n`）：**未创建，0 个**
- 无任何截图/dump 显示会话列表或 composer

⇒ **第 0 步失败，按裁定立刻停手 BLOCKED，不硬耗。**

---

## 3. 阻塞根因（已定位到代码行 + 主机侧实测）

### 3.1 设备侧：套接字**确实**打开了（对比第 1 轮是重大进展）

`logcat --pid=$appPid`（`$appPid=6423 / 8332`，全程未使用 `$pid`）：

```
D/TrafficStats( 6423): tagSocket(94) with statsTag=0xffffffff, statsUid=-1
D/TrafficStats( 8332): tagSocket(99) with statsTag=0xffffffff, statsUid=-1
```

app **没有**报「连不上」，而是报「有东西应答了、但它不是 OpenCode 服务器」——即 **HTTP 2xx 已收到**。故第 1 轮的「零套接字」已解除。

### 3.2 主机侧实测（Lead 起的 4095）

```
GET /api/health      -> 200  {"healthy":true}
GET /global/health   -> 200  {"healthy":true,"version":"1.18.33"}
GET /api/info        -> 200  <!doctype html><html …>   ← SPA 兜底页，不是 JSON
```

即主机上的 opencode 服务是 **1.18.33（V1 代际）**，且 **`/api/info` 走 SPA 兜底返回 HTML**。

### 3.3 代码根因

`app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/ServerHealth.kt`

```kotlin
// :38  探针只有这一条腿
const val V2_INFO_PATH: String = "/api/info"

// :224-228
fun check(target: HealthTarget): HealthResult {
    val url = healthUrl(target.url, V2_INFO_PATH) ?: return unhealthy(HealthFailure.InvalidUrl)
    return attempt(url, target)
}

// :150-157  2xx 的响应体只交给 V2 判代
fun healthBody(contentType: String?, body: String?): HealthResult {
    val info = V2Capability.decideInfo(200, contentType, body)
    return if (info.generation == ServerGeneration.V2) HealthResult(ServerHealth.HEALTHY, info.version)
    else unhealthy(HealthFailure.InvalidResponse)      // ← 本次命中
}

// :181
HealthFailure.InvalidResponse -> HealthMessage(R.string.oc_connect_error_invalid_response)
// strings.xml:826 -> "Something answered, but it is not an OpenCode server."
```

`V2Capability.kt:14-15` 明确：`GET /api/info` → JSON 且 `version` 以 `"2."` 开头 ⇒ V2，否则 UNKNOWN。

**结论：app 的首连流程是「V2-only 探针」，而 Lead 起的服务器是 V1（1.18.33）。**
`/api/health` 与 `/global/health` 都返回合法的 `{"healthy":true}`，但 app 的原生探针**不再读这两条路径**（`ConnectCorsTest.kt:64-65` 里关于 `/api/health`、`/global/health` 的断言是针对**内置 JS bundle**的，不是这条原生探针）。⇒ **只要服务器是 V1，任何地址都不可能被接受。**

这与 `research/wave4_fold_v2_report.md` Bug #1 属同一族（首连探针误判），但根因已收窄：不再只是「v2+auth」，而是**探针腿被收敛成 `/api/info` 且判定只认 V2**。

> 同族的 settings 添加服务器对话框复用同一个 `ServerHealthProbe`，预期同样失败（该结论来自 wave4 报告，**本轮未复测**，因为首连未通过时无法在不按 BACK 的前提下到达设置页）。

### 3.4 为什么本轮不能自行绕过

修掉它需要改 `ServerHealth.kt` 并重打包安装 —— 纪律明令**不许跑 gradle / 装机**。故只能停手报 BLOCKED。

### 3.5 给 Lead 的解法（择一）

1. **换服务器代际**：起一个 `version` 以 `2.` 开头的 opencode（`/api/info` 返 JSON），app 首连即可通过；或
2. **改 app**：在 `healthBody` 判定失败时回退到 `/api/health` / `/global/health`（两者在本机都返 `{"healthy":true}`），把 V1 也判为 HEALTHY；
3. 修 `ConnectServerScreen` 的地址归一化（当前会静默给无 scheme 地址补 scheme，不影响本次结论，但会掩盖用户输入错误）。

---

## 4. 主判据（两档）→ 未执行

| 档位 | 宿主盒 | 并集(排除 Open terminal) | band | unionPx | paneBottom (i) dump侧 | paneBottom (ii) 生产口径 | 差值 | mInputShown | readable | 判定 |
|---|---|---|---|---|---|---|---|---|---|---|
| 1366x768 | — | — | — | — | — | — | — | — | — | **未执行** |
| 600 档 | — | — | — | — | — | — | — | — | — | **未执行** |

原因：主判据量测必须在一个**已进入的会话的 composer** 上进行；第 0 步失败 ⇒ composer 屏幕从未可达 ⇒ 无任何宿主盒/并集可量。**未产出数字，不做任何推测性填写。**

⇒ 本轮**无法回答**：两档 ① 差值（统一口径下应 ≈48px）、768 档 `readable 92` / 时间线盒 `140`、600 档 `readable 51` 是否保持。

## 5. 队列几何（Queue chip / Steer / ×）→ 未执行

未测到。触发条件是「回合在跑 + composer 非空」，前提是会话可达。**不给基线 43 / 48 的实测值，也不做回退判定。**

## 6. 缩窗往返 2 轮（768→600→768）→ 未执行

未做 `wm` 档位切换量测。仅记录一条**顺带**的观测：在第 0 步全部配置交互期间，按 `$appPid` 过滤的 logcat 中

```
FATAL | IllegalArgumentException | maxHeight   →  (empty)
```

全程为空，但这**不能**替代缩窗往返结论（从未切过档位）。

## 7. 收尾

| 项 | 动作 | 结果 |
|---|---|---|
| `wm` 复位 | `wm size reset` → `wm size 1366x768`；`wm density reset` → `wm density 160` | ✅ 显式重设完成，实测 `Override size: 1366x768` / `Override density: 160` |
| 本 app 停止 | `am force-stop com.xiufeigo.opencodeshell` | ✅ 已停止 |
| `E2ECHK5554u1n` 会话清理 | 从未创建 | ✅ 无需清理（0 个） |
| `reverse` 通道 | 未改动 | ✅ `tcp:4095` / `tcp:2222` 原样保留 |
| `/sdcard` 残留 | 删除本轮 14 个取证文件（`d0-d4/dx/dy/de.xml`、`s0/s1/s2/s4/sA/sB.png`）并复查 | ✅ 本轮文件**零残留** |
| `emulator-5590` | 未执行任何命令 | ✅ 未触碰 |
| 未按 BACK(4) | 全程无 `keyevent 4` | ✅ |
| 弹层内未用 ESC(111) | 授权弹层用坐标点击按钮，未发 ESC | ✅ |
| logcat 过滤 | 一律 `--pid=$appPid`（`$appPid` 变量），**未使用 `$pid`** | ✅ |

> `/sdcard` 仍存有**其它轮次/他人**的历史取证文件（如 `d10…d51`、`v51_*`、`tb_*`、`tE_*`，时间戳 2026-09-24 ~ 2026-10-03 02:39），**非本轮产物，未删除**——不属于本轮职责范围，交由 Lead 决定。

---

## 8. 未闭环

1. **【阻塞·最高优先】首连探针 V2-only** — app 无法连 V1（1.18.33）服务器，`/api/info` 返回 SPA HTML 被判 `InvalidResponse`。需 Lead 换 V2 服务器或改 `ServerHealth.kt`。详见 §3。
2. 主判据两档全部量测（宿主盒 / 并集 / band / unionPx / paneBottom (i)(ii) / 差值 / mInputShown / readable）。
3. 队列几何（`Steer` h / `×` h / clickable vs 基线 43 / 48 / 全 clickable）。
4. 缩窗往返 2 轮的 `FATAL|IllegalArgumentException|maxHeight` 断言。
5. `ConnectServerScreen` 地址归一化：输入无 scheme 地址会被静默补 `http://`，不报 `oc_connect_address_invalid`，属可观测性缺口。
6. settings 添加服务器对话框是否同样失败 —— 本轮未复测（wave4 报告称同样失败）。

---

## 9. 证据文件

均在 `research/v97_verify/5554_md/`：

| 文件 | 内容 |
|---|---|
| `s1.png` | 清数据后首屏（Connect to a server 引导页） |
| `d1.xml` | 首屏 16 节点完整 dump |
| `s4.png` / `d4.xml` | 首试 `127.0.0.1:4095` 的失败文案 |
| `sA.png` | 尝试 A `http://127.0.0.1:4095` 失败 |
| `sB.png` | 尝试 B `http://10.0.2.2:4095` 失败 |
| `d0.xml` | 通知授权弹层 dump（纪律偏离取证） |
| `d2.xml` / `d3.xml` / `dx.xml` / `dy.xml` | 中间态 dump（地址回填、表单滚动） |
