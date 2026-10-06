# UX14-P5 — 添加第二台服务器后的全量存储转储 + 两行菜单对比（只读补全侦察）

> 只读轮次。**未修改任何代码**，未起停后端进程，未碰 gradle。
> 日期：2026-10-06 · 设备：`emulator-6790`（`Medium_Phone_API_37.0`，端口 6790）
>
> **一句话结论：P4 的核心结论被本轮推翻。** 服务器注册表**确实存在**，
> 键名是 **`opencode.global.dat:server`**，介质是 **localStorage**；
> 它只在**第一次成功 Add server 时才被惰性创建**，所以单服务器首启态（3 键）根本看不到它。
> `Remove` 菜单项**真实渲染**，条件是"该条目在 registry 的 `list` 里"——
> builtin（`location.origin`，即 4173）不在 `list` 里，所以它只有 Edit/Set as default。

---

## 0. 环境

| 项 | 值 |
|---|---|
| 包 | `research/deliver/opencode-shell-web.apk`（16,800,362 B，批 #6） |
| applicationId | `com.xiufeigo.opencodeshell.web.debug` |
| install -r / pm clear | Success / Success（全新首启态） |
| 应用 pid | **2661** |
| WebView devtools socket | `webview_devtools_remote_2661` |
| 页面 origin | `http://127.0.0.1:4173/` |
| CDP | `adb forward tcp:9333 localabstract:webview_devtools_remote_2661` |
| CDP page target | `92ED9F77C34D381578AAD6376A5A1A33` |
| 视口 | 411×914 CSS px，dpr **2.625**（截图 1080×2400） |
| 后端 | `127.0.0.1:4095` = `opencode.exe serve --hostname 127.0.0.1 --port 4095`，pid **21092**，**未起停** |
| 端口转发 | `adb reverse tcp:4095 tcp:4095` 已建 |

### 0.1 与派单的两处偏差（需上报）

1. **`adb forward` 必须去掉 socket 名前的 `@`。**
   从 `/proc/net/unix` 取到的是 `@webview_devtools_remote_2661`，
   直接 `forward ... localabstract:@webview...` 会建立一个**空名 socket**，
   `curl http://127.0.0.1:9333/json/list` 返回 **exit 52（空回复）**。
   正确写法：`localabstract:webview_devtools_remote_2661`（无 `@`）。
   *（P4 报告未记录此坑，但 P4 成功连上，可能当时手工处理过。）*
2. **4095 开了鉴权，Add server 必须填 Password。**
   服务器命令行**没有** `--password`，但
   `~/.config/opencode/service.json` 里有 `{"password": "eBWzrq6W6pU9RfXJ1hls9GZR1Qba3MkFQhH6ry_NECI"}`。
   该值**是失效的**——实测 8 种认证头变体全部 401：

   | 认证形式 | `/api/info` |
   |---|---|
   | `Basic base64(opencode:<service.json 值>)` | 401 |
   | `Basic base64(<值>:)` / `(:<值>)` | 401 |
   | `Bearer <值>` / `Basic <值>` / `?password=<值>` / `x-opencode-password` | 401 |
   | `Basic base64(opencode:opencode)` | **200** ✅ |

   ⇒ **实际口令就是字面量 `opencode`**（用户名 `opencode`，口令 `opencode`）。
   不填口令时对话框报 `Could not connect to server`，且服务端日志可见
   `/api/info` 在 **401 重试风暴**（14:23:18 一秒内 9 次）。

### 0.2 交互方法学坑（复用给后续轮次）

- **软键盘会遮住提交按钮。** 对话框 sheet 不随键盘重排；键盘弹出时提交按钮仍报 `cy=630`，
  但该位置被键盘覆盖。`keyevent 111`(ESC) 会**连对话框一起关掉**；
  `keyevent 4`(BACK) 只收键盘、对话框与已填值保留 —— **用 BACK**。
- **IME 会把 `http` 自动大写成 `Http`**（清空重输后必现），而应用**不做 scheme 归一化**：
  提交 `Http://127.0.0.1:4095` 实测同样报 `Could not connect to server`。
  本轮最终一次 re-add 的 URL 是用 **CDP 原生 setter + `input` 事件**写入的
  （`HTMLInputElement.prototype.value` 的 setter），**提交动作仍是真实 `input tap`**。
  这是本轮唯一一处"非纯点击"的输入，仅影响输入方式，**不影响被观测的存储/菜单行为**。

---

## 1. 基线转储（全新首启态，单服务器）

CDP `Runtime.evaluate`，`localStorage` 值截断 600。

| # | 键 | 长度 | 值 |
|---|---|---|---|
| 0 | `opencode.global.dat:notification` | 11 | `{"list":[]}` |
| 1 | `opencode.global.dat:extension.updater.seen` | 20 | `{"version":"2.0.22"}` |
| 2 | `opencode.global.dat:command.catalog.v1` | 1211 | 命令目录（`btw.ask` / `terminal.toggle` / …） |

- `sessionStorage` 空；`cookie` = `oc_locale=en`
- `indexedDB.databases()` = `[{"name":"opencode-drafts","version":1}]`，
  `blobs` 0 行、`documents` 0 行
- `caches.keys()` = `["workbox-precache-v2-http://127.0.0.1:4173/"]`，**1611 条**（P4 未记条目数）

**⇒ 与 P4 完全一致：首启态没有注册表键。** 截图 `p5_s00_firstboot.png`、`p5_s01_servers_firstboot.png`

---

## 2. 添加 4095 成功

路径：Settings → `Server` 页 → `Add server` → 地址/名称/口令 → 提交 → **等 18 s**。
提交后对话框关闭、路由离开 Server 页（自动跳到 `/settings` Preferences）。

### 2.1 头部下拉：服务器分组出现

设置页头部下拉（`role=menuitemradio` 全量）此时为：

```
Preferences / Appearance / Notifications / Shortcuts
──────────────  (separator)
opencode            ← 新增（4095，displayName=opencode）
127.0.0.1:4173       ← builtin origin
──────────────  (separator)
About
```

⇒ 服务器在下拉里是**独立分组**，可点选切换（`menuitemradio`，非 `menuitem`）。
截图 `p5_s04_dropdown_two_servers.png`

### 2.2 ⭐ 第二条服务器落在哪个介质 / 什么键 / 什么结构

**介质：`localStorage`（不是 IndexedDB，不是 Cache Storage，不是 cookie）。**

**键名：`opencode.global.dat:server`**（新增键，**原样**）：

```json
{"list":[{"type":"http","http":{"url":"http://127.0.0.1:4095","password":"opencode"},"displayName":"opencode"}],"hidden":{},"projects":{},"lastProject":{},"recentlyClosed":{}}
```

- 值长度 **175** 字符
- 条目结构：`{ type:"http", http:{ url, password }, displayName }`
- 顶层 5 个并列容器：`list` / `hidden` / `projects` / `lastProject` / `recentlyClosed`
- ⚠ **`password` 明文存在 localStorage 里**

**同批新增的第 2 个键（按服务器分区）：**

```
opencode.global.dat:http://127.0.0.1:4095notification   (len=11, {"list":[]})
```

⚠ 注意 **URL 与 `notification` 之间没有分隔符**（是 `…:4095` 直接接 `notification`）。
这是按服务器前缀分区的通知队列键。

`localStorage` 键数：**3 → 5**。

### 2.3 未被写入的东西（重要否定证据）

| 键 | 状态 |
|---|---|
| `opencode.settings.dat:defaultServerUrl` | **仍不存在** —— Add server **不写默认服务器指针** |
| `opencode-drafts`（IndexedDB） | 无变化，仍 `blobs` 0 / `documents` 0 |
| Cache Storage | 无变化，仍单条 workbox 预缓存 1611 条 |

⇒ Add server 的全部持久化落点 = **1 个注册表键 + 1 个分区通知键**，别无其它。

---

## 3. 两行菜单对比（核心）

服务器行 `...` 按钮（`aria-label="More options"`，20×20 CSS px）。
两行按钮位置相同（`cx=364, cy=265`，设备坐标 `(955,696)`），分别在各自服务器页上。
页面路由为 `/settings?server=<urlencoded>`。

| 服务器行 | 状态点 | 菜单项（全量枚举 `role=menuitem*`） |
|---|---|---|
| **4095 `opencode`**（非 builtin） | 🟢 **绿点** | `Edit` / `Set as default` / **`Remove`** —— **3 项** |
| **4173 `127.0.0.1:4173`**（builtin） | 🔴 红点 | `Edit` / `Set as default` —— **2 项** |

**没有 `Hide`。** 两行都没有。两份菜单都**只有** `Server`(分组标题，非 menuitem) + 上表所列项，
无任何 `visibility:hidden` 的隐藏项。

截图：`p5_s06_menu_4095_has_remove.png`（3 项）、`p5_s07_menu_4173_no_remove.png`（2 项）

### 3.1 canRemove 的真实渲染条件（实测，不猜）

**条件 = 该条目存在于 `opencode.global.dat:server` 的 `list` 数组中。**

证据链：

| 观察 | registry `list` | 渲染 Remove？ |
|---|---|---|
| 首启态单服务器 | 键不存在 | ❌ 无 |
| 添加 4095 后（4173 行） | `[{url:…:4095}]` | ❌ 无（4173 自身不在 list） |
| 添加 4095 后（4095 行） | `[{url:…:4095}]` | ✅ 有 |
| 移除 4095 后 | `[]` | ❌ 全部消失 |

⇒ **builtin（`location.origin`）永远不在 `list` 里**，因此永远不可 Remove。
**「非默认」「非当前」都不是条件** —— 4173 行未设默认、也非当前服务器，仍无 Remove。
真正的判别式只有一条：**在不在 `list` 里**。

### 3.2 canHide 的真实渲染条件

**无渲染条件 —— `Hide` 从不出现在任何菜单中。**
尽管 registry 里有 **`hidden`** 容器（`"hidden":{}`，结构上就是个可用的隐藏表），
上游 web 构建**没有任何入口写它**。`dialog.server.menu.hide` / `.show` 文案在 bundle 里存在
（P4 已定位）但同样不可达。
⇒ **P6 若要做"隐藏 4173 builtin"，没有任何上游出口可复用**：
隐藏 builtin 需要宿主自己实现（要么往 `hidden` 里塞 origin 再自绘 UI，要么另起一套）。

### 3.3 附带的可达性变化（此前未记录）

- **`/settings?tab=servers` 在"两服务器态"会被重定向掉**（落到 `/settings` Preferences），
  `Server` 页签消失；**移除 4095 后它又恢复可达**。即 `tab=servers` 的可达性**依赖 registry 非空**。
- 每台服务器有独立设置页 `/settings?server=<encoded>`，页头标题即 `displayName`。

---

## 4. 移除语义实测（对 4095 行真实点了一次 `Remove`）

### 4.1 操作

4095 行 `...` → `Remove`（`menuitem` @ CSS `(216,372,156,28)`，设备 `(772,1013)`）→ 单击，无二次确认对话框。

### 4.2 移除后立即转储

`localStorage` 键数 **5 → 7**：

| # | 键 | 长度 | 移除后 |
|---|---|---|---|
| 0 | `opencode.global.dat:server` | **74** | `{"list":[],"hidden":{},"projects":{},"lastProject":{},"recentlyClosed":{}}` |
| 1 | `opencode.window.browser.dat:tabs` | 2 | `[]` ← **新增** |
| 2 | `opencode.global.dat:notification` | 11 | `{"list":[]}` |
| 3 | `opencode.global.dat:extension.updater.seen` | 20 | `{"version":"2.0.22"}` |
| 4 | `opencode.global.dat:http://127.0.0.1:4095notification` | 11 | `{"list":[]}` ← **仍在，孤儿** |
| 5 | `opencode.window.browser.dat:tabs.closed` | 2 | `[]` ← **新增** |
| 6 | `opencode.global.dat:command.catalog.v1` | 1272 | — |

### 4.3 语义要点（供 P6 设计"移除 4173 builtin"对照）

1. **`list` 被置空，但 `opencode.global.dat:server` 键本身被保留**（175→74 字符，不是 `removeItem`）。
   空 `list` 仍是个有效状态，UI 照常工作。
2. **分区通知键 `opencode.global.dat:<url>notification` 不被清理** ⇒ 上游移除**不做级联清理**，
   每次 add/remove 循环都会留下一个孤儿键。P6 若要"干净移除 4173"，**宿主必须自己做级联**。
3. **`defaultServerUrl` 全程未被触碰** —— 没有"默认服务器回退"逻辑被触发
   （因为 add 时就没写过它）。⇒ 移除**不会**改默认指针；**默认指针指向谁：不变 / 从未被设置**。
4. **无二次确认**：`Remove` 一击即执行。
5. **UI 侧联动**：
   - 路由被弹回 `/settings`（不再是 `?server=` 页）；
   - 下拉里的**服务器分组整体消失**，只剩完整设置页签
     （`Preferences/Appearance/Notifications/Shortcuts/Projects/Worktrees/Providers/Models/Extensions/**Server**/About`）；
   - **`Server` 页签恢复可达**，`/settings?tab=servers` 正常打开，且**只剩 4173 一行**。
6. 移除不触碰 IndexedDB / Cache Storage。

截图 `p5_s08_after_remove_4095.png`

### 4.4 恢复两服务器态

重新 Add server（地址/名称/口令同 §2）→ 等 18 s → 成功。
registry 回到 **175 字符**、与移除前**逐字节一致**：

```json
{"list":[{"type":"http","http":{"url":"http://127.0.0.1:4095","password":"opencode"},"displayName":"opencode"}],"hidden":{},"projects":{},"lastProject":{},"recentlyClosed":{}}
```

⇒ add/remove 对该键是**幂等可逆**的，宿主可依赖它做服务器态的增删。

---

## 5. 对 P4 结论的修正

| P4 结论 | 本轮实测 | 判定 |
|---|---|---|
| 「上游 web 构建根本没有服务器注册表这个实体」 | 存在：`opencode.global.dat:server`，含 `list/hidden/projects/lastProject/recentlyClosed` | ❌ **推翻** |
| 「没有 id、没有记录可删」 | `list` 是数组，条目有 `url`/`displayName`/`password`，可增可删 | ❌ **推翻** |
| 「四个面全部确认无移除/隐藏出口」 | 4095 行的 `Remove` **真实渲染且可用** | ❌ **推翻**（仅对 builtin 成立） |
| 「`localStorage.setItem` 仅 3 处」 | 至少还有 registry 写入点 | ❌ **推翻**（P4 静态检索不完整） |

**P4 为何会漏**：P4 只观测了**单服务器首启态**。该状态下 registry 键**尚未被创建**
（惰性初始化），所以"键不存在"被误读成"数据模型不存在"。
**只有 add 成功一次，实体才会现形。** 这是典型的"用未初始化状态否定模型"。

### 仍然成立的 P4 结论

- **`dialog.server.menu.hide` / `.show` 确实无任何渲染路径**（§3.2 实测两行菜单均无 Hide）。
- **`servers` store 与 SSH/WSL 插件**仍是另一条线索；web 包注册表走的是完全独立的
  `opencode.global.dat:server` 全局 KV，与插件 store 无关。
- builtin = `location.origin`，**结构上不可删**，因为它不在 `list` 里。

---

## 6. 给 P6 的结论

1. **要"移除/隐藏 4173 builtin"，上游没有出口。** `Remove` 的渲染条件是"在 `list` 里"，
   builtin 永远不在其中；`Hide` 连渲染分支都没有。
2. **宿主侧两条可行路径**（均需宿主实现）：
   - **伪 remove**：向 registry `list` 里塞一条 4173 的副本 → `Remove` 立刻出现、可点；
     但移除后 builtin 仍以 `location.origin` 身份存在，**条目会"删了还在"**，需配合隐藏。
   - **伪 hide**：直接写 `opencode.global.dat:server` 的 `hidden` 容器（结构已存在），
     再自绘隐藏列表 UI —— 上游不消费 `hidden`，所以**光写没用**，必须宿主自己渲染过滤。
3. **务必自己实现级联清理**：上游 remove **不删** `opencode.global.dat:<url>notification`
   分区键，会残留孤儿。
4. **注意口令明文**：`password` 明文存 localStorage，这是既有上游行为，
   宿主若要暴露多服务器凭据需自行考虑。
5. **两台服务器都真实可达时**，`/settings?tab=servers` 会**被重定向掉**，
   "服务器列表页"仅在 registry 为空时可直达 —— 做列表页可达性设计时要留意。

---

## 7. 收尾

- `adb reverse tcp:4095 tcp:4095` → **已 remove**
- `adb forward tcp:9333` → **已 remove**
- 自建 `emulator-6790` → **已关闭**
- `emulator-5554`（他人 AVD，Pixel_Tablet）**未触碰**；**未** `kill-server`
- 后端 `opencode.exe` pid 21092（4095）**全程未起停**
- 本轮临时件（`p5_*.js` / `p5_*.mjs` / `p5_*.json` / `p5_*.ps1`，均在 `%TEMP%`）→ 已清理
- **未修改任何仓库代码**，未碰 gradle

**本轮碰过的 serial：`emulator-6790`（仅此一个）。**

---

## 8. 遗留 / 未验证

- 未验证 `hidden` 容器的上游消费逻辑（无任何入口可写，只能由宿主构造后观察）。
- 未验证"把 4173 塞进 `list` 后 `Remove` 是否真的可用"——那属于 P6 的**改代码**范围，本轮只读。
- `opencode.window.browser.dat:tabs` / `tabs.closed` 两键在移除后出现，
  推测与移除时的路由跳转有关，**未单独定位触发条件**。
- 4095 的 `/api/event` 事件流是否真正建立成功未测（本轮只关心注册表与菜单）。