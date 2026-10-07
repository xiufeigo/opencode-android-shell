# UX18-P1 — 决定性实验：内置条目是否跟随 `defaultServerUrl`

> 日期：2026-10-07 · 设备：`emulator-6790`（自建克隆 AVD `UX18P1`，`Medium_Phone_API_37.0`）
> 包：`com.xiufeigo.opencodeshell.debug` / `opencode-shell-web.apk`（批 #15，16,832,748 / md5 `4865A530ED4299A56342CBABE5899144` — 装前已核对 ✅）
> 后端：`127.0.0.1:4095`（pid **1804**，全程未起停；收尾 health=200）
> **本轮只验证、不改代码。**

## 结论一句话

**产品负责人的方案不成立。** `defaultServerUrl` 是一个**只写不读**的悬空指针：它能被 `Set as default` 写入、
能被预置注入并在 reload/冷启后稳定存活，但**列表 / 切换器 / 事件流三方全部不消费它**——
内建行恒等于 `location.origin`（= 4173），事件流也恒打 4173。因此「预置 4096 + 设默认 ⇒ 4173 消失」在代码上无从发生。

---

## 0. 读数

### A0 基线（`install -r` → `pm clear` → `am start` → 5s）

| 项 | 读数 |
|---|---|
| `localStorage['opencode.settings.dat:defaultServerUrl']` | **`null`**（键不存在） |
| `localStorage['opencode.global.dat:server']` | `null` |
| `location.origin` | `http://127.0.0.1:4173` |
| localStorage 全部键 | `opencode.global.dat:notification`、`…:extension.updater.seen`、`…:command.catalog.v1` |
| 服务器列表（Settings → Server 段） | **1 行**：`127.0.0.1:4173` / `http://127.0.0.1:4173` |
| 事件流 | **`http://127.0.0.1:4173/api/event` status=404**，严格 1 Hz（`OPEN → NON2XX → disconnected` 循环）；`/api/info` 同打 4173 |
| Home | 顶部 `All projects / 127.0.0.1:4173`，正文 `Loading` 空转 |

> 截图 `a0_home.png`

### 步骤 1 — Add `http://127.0.0.1:4095` + `opencode`（真实 UI，等待 ≥14s）

| 项 | 读数 |
|---|---|
| 绿点 | ✅ `opencode` 行绿点，API 实流量打 4095（`/api/project`、`/api/provider`、`/api/model` …） |
| `defaultServerUrl` | **仍为 `null`** —— Add **不写**默认指针（复现 UX14-P5 结论） |
| `opencode.global.dat:server` | `{"list":[{"type":"http","http":{"url":"http://127.0.0.1:4095","password":"opencode"},"displayName":"opencode"}],"hidden":{},"projects":{},"lastProject":{},"recentlyClosed":{}}` |
| 列表 | **不是两行**：Settings 段整体变成**按服务器切分的单服务器页** `opencode`（副标题「Manage **this server's** connection and preferences」），`Connection` 下**只有 4095 一行** |
| 段选择器 | 由 `Preferences/Appearance/Notifications/Shortcuts/Projects/Worktrees/Providers/Models/Extensions/**Server**/About` 变成 `Preferences/Appearance/Notifications/Shortcuts/**opencode**/About` |

> 截图 `s2_added.png`、`s3_list2rows.png`
> **副产物（未在预期内，单独记录）**：添加服务器后，段选择器里 `Server/Projects/Models/Extensions/Providers/Worktrees` 六个全局段**塌缩成一个以 displayName 命名的段**。这本身就是一个可复现的导航退化。

### 步骤 2 — 把 4095 设为默认（真实 UI：`…` → `Set as default`）→ `force-stop` → 冷启

| 编号 | 问 | 读数 |
|---|---|---|
| **A1** | 内建/首行服务器 = 4173 还是 4095？ | **4173**。切换器里内建行**仍是第一行**，且此时**标签变为空白**（不再是 `127.0.0.1:4173`，也不是 4095），**红点**（不可达） |
| **A2** | 4173 行还在列表任何位置？ | **还在**。切换器第一行即它，红点；`a2_switcher.png` 可见两行＝「空白红点行」+「opencode 绿点行」 |
| **A3** | 切换器当前文本？ | **`127.0.0.1:4173`**（Home 顶部 `All projects / 127.0.0.1:4173`、搜索框 `Search sessions in 127.0.0.1:4173`） |
| **A4** | `defaultServerUrl` 值？ | **`http://127.0.0.1:4095`** ✅（键确实写进去了） |
| **A5** | 事件流打向 4095 还是 4173？ | **4173**。`http://127.0.0.1:4173/api/event` status=404，仍 1 Hz 空转；4095 **零请求**；Home 卡 `Loading` |

> 截图 `a1_home_cold.png`、`a2_switcher.png`
> **A4 与 A5 直接对撞**：指针写着 4095，事件流却打 4173 ⇒ 事件流**不跟随** `defaultServerUrl`。
> 早期「设默认后 4173 事件流空转仍在」的例外线索**至此澄清**：那不是「设默认没生效」，而是**设默认从来就不管事件流**——4173 空转是基线行为。

### 步骤 3 — 预置默认实验（B）

`pm clear` → 注入 `localStorage.setItem('opencode.settings.dat:defaultServerUrl','http://127.0.0.1:4096')` → `location.reload()`

注入即时回读校验：`"http://127.0.0.1:4096"` ✅（写进去了）

| 编号 | 问 | 读数 |
|---|---|---|
| **B1** | 首行/内建服务器行 = 4096 吗（红/绿）？ | **否，仍是 4173**。Server 段 `Connection` 下：`127.0.0.1:4173` / `http://127.0.0.1:4173`。**页面全文无 `4096` 字样** |
| **B2** | 4173 出现了吗？ | **出现**——它就是内建行本身 |
| **B3** | 切换器文本 = 4096？ | **不是 4096**，也**不是 4173**：切换器**整组服务器行未渲染**，只有 `All projects` / `Add project`。`document.body.innerText` = `Home \| 0 \| All projects \| Loading` |
| **B4** | 事件流打向 4096 还是 4173？ | **4173**，`/api/event` 404 @1 Hz |

**注入是否太晚？（任务点名要澄清）—— 否，两轮法已排除竞态：**

reload 后 `def` 仍为 `http://127.0.0.1:4096`（键存活，`srv` 为 `null`）。随后再做一次**冷启**：
`force-stop` → `am start` ⇒ **首屏 hydration 时键已在**，根本不存在「注入晚于读取」的可能。

冷启读数：
- **API 主机分布：`4173` × 26，`4096` × 0**（`/api/*` 全量统计）
- 事件流：4173 / 404 / 1 Hz
- Server 段内建行：`127.0.0.1:4173`
- `def` = `http://127.0.0.1:4096` 仍在

⇒ **`defaultServerUrl` 在 hydration 时点可读、却依然完全不被消费。**「注入太晚」不成立。

> 截图 `b1_home.png`、`b2_switcher.png`、`b3_server_section.png`、`b4_cold_home.png`、`b5_cold_server_section.png`

---

## 4. 结论表（给产品负责人的答案）

| 问题 | 答案 | 证据 |
|---|---|---|
| 内置条目跟随 `defaultServerUrl`？ | **否** | A1：设默认 4095 后内建行仍首行、**标签变空白**、红点，仍非 4095；B1/B-cold：预置 4096 后内建行仍是 `127.0.0.1:4173` |
| 设默认后 4173 从内建行消失？ | **否** | A2：切换器仍两行，4173 行在（红点）；B2：4173 仍在内建行 |
| 事件流跟随 `defaultServerUrl`？ | **否** | A4 键=4095 而 A5 流=4173；B4=4173；冷启 `4096` **零请求**、`4173` 26 次 |
| **方案（预置 4096 + 设默认可删 4173）是否成立？** | **不成立** | 三个消费方（列表/切换器/事件流）全部锚定 `location.origin`；`defaultServerUrl` 只写不读 |

### 为什么（三条硬约束）

1. **内建行的身份是 `location.origin`，不是 `defaultServerUrl`。**
   本壳用 `LocalUiOriginServer` 在 4173 同源托管 SPA，于是 `location.origin = http://127.0.0.1:4173`。
   UX14-P4 已从产物里定位过 `currentServerUrl = location.origin`、`defaultServerUrl = n ?? t`——
   注意 `defaultServerUrl` 只是**对外暴露的一个值**，内建行的**渲染与选择**读的是前者。
   旁证：把默认设成注册表里**确实存在**的 4095 后，内建行标签变成**空白**而非 `opencode`/`4095`——
   这正是「两套身份对不上」的表现。

2. **事件流的 base 同样钉死在 `location.origin`。**
   `LocalUiOriginServer` 明确**不代理 `/api/`**（一律 404），页面直连「用户配置的那台服务器」；
   而「用户配置的当前服务器」在冷启时解析回 origin ⇒ 4173 ⇒ 404 ⇒ 1 Hz 空转 ⇒ Home 永久 `Loading`。
   这解释了 UX13-P12、UX14-P6、UX17-P3 三处同款现象，是**基线行为**，不是回归。

3. **`defaultServerUrl` 是死指针。**
   能写、能读回、能跨 reload 与冷启存活（A4 / B 两次均验证），但**三个消费方一个都不读**。
   ⇒ 靠这个键做「预置默认服务器」在本宿主里**不可能生效**。

### 对方案的修正建议（供拍板）

- 想让 4096 真正成为 API 基址，必须让 `location.origin`/当前服务器**指到 4096**，可行的宿主侧手段是
  **① 同源反代（壳内把 `/api` 代理到 4096）**，或 **② 导航到 `http://127.0.0.1:4096/` 让页面同源**；
  单靠 `localStorage` 键无效。
- 若目标只是**让 4173 行不显示**，那与 `defaultServerUrl` 无关，属 UX14-P6 / UX17-P1 已论证过的
  **宿主侧过滤 `hidden` 键**路线（`registry.hidden`），且需注意上游**不做级联清理**（UX14-P5 留档）。
- **顺带待决**：步骤 1 发现的「添加服务器后 Settings 段选择器塌缩成单个 `opencode` 段」是独立于本方案的导航退化，建议单开一轮。

---

## 5. 收尾核验

| 项 | 结果 |
|---|---|
| 自建 AVD | `UX18P1`（克隆自 `Medium_Phone.avd`，因 `Medium_Phone_API_37.0` 被 5680 占用；`-read-only` 亦被实例锁拒绝）→ `emu kill` ✅ |
| `adb reverse` | `tcp:4095` 已 `--remove`，`reverse --list` 为空 ✅ |
| 后端 | pid **1804** 未变、`StartTime 2026/10/7 14:35:13` 未变、`/global/health` = **200** ✅ 全程未起停 |
| serial | 本轮自建并已关闭：`emulator-6790`。现有且**未写入**：`emulator-5554`、`emulator-5680` |
| 代码改动 | **无**（只读验证） |

### 证据清单

`research/v18_verify/`：`a0_home.png`、`s1_drawer.png`、`s1_server.png`、`s2_added.png`、`s3_list2rows.png`、
`a1_home_cold.png`、`a2_switcher.png`、`b1_home.png`、`b2_switcher.png`、`b3_server_section.png`、
`b4_cold_home.png`、`b5_cold_server_section.png`