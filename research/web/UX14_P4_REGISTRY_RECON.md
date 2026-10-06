# UX14-P4 — 注册表存储模式全量转储 + 服务器菜单"移除/隐藏"出口排查

> 只读探查轮次。**未修改任何代码**，未起停后端进程，未碰 gradle。
> 日期：2026-10-06 · 设备：`emulator-6790`（`Medium_Phone_API_37.0`，端口 6790）
> 结论一句话：**上游 web 构建根本没有"服务器注册表"这个实体**——它只有一个隐式条目 `location.origin`
> 和一个单字符串指针 `opencode.settings.dat:defaultServerUrl`。所以"删不掉也藏不掉"不是菜单漏渲染的 bug，
> 而是**数据模型层就不存在可删的对象**。

---

## 0. 环境与起机（全新首启态）

| 项 | 值 |
|---|---|
| 包 | `research/deliver/opencode-shell-web.apk`（16,800,362 B，批 #6），install -r → Success |
| applicationId | `com.xiufeigo.opencodeshell.web.debug` |
| 入口 Activity | `com.xiufeigo.opencodeshell.web.WebHostActivity` |
| `pm clear` | Success（全新首启态） |
| 应用 pid | **37916** |
| WebView devtools socket | `webview_devtools_remote_4265` |
| 页面 origin | `http://127.0.0.1:4173/` |
| CDP page target | `EA434C2BD432371534FDAA318E93AC67`（另有 SW target `20415A972622641104D24818C3AE72BF`） |
| CDP | `adb forward tcp:9333 localabstract:webview_devtools_remote_4265` |

### ⚠ 环境漂移（与派单前提不符，需上报）

派单称"后端 `127.0.0.1:4095` 已在跑"。实测 **4095 无监听、连接被拒**：

| 实测监听 | 进程 |
|---|---|
| `0.0.0.0:4096` | `opencode.exe` pid 12512 |
| `127.0.0.1:4097` | `opencode.exe` pid 30688 |
| `0.0.0.0:4098` | `opencode.exe` pid 40764 |

遵守"不许起停"纪律 ⇒ 未启动也未停止任何后端。**顺带佐证**：Add server 对话框里地址占位符
本身就是 `http://localhost:4096`，上游默认值也是 **4096**，派单的 4095 是上一轮的旧端口。

---

## 1. localStorage 全量转储

> **方法学纠偏**：先按派单用 `am start --es oc_debug_eval b64:` 注入，回显被 **logcat 单行长度截断**
> （Eval 行仅 ~777 B，大 JSON 被腰斩，解析出一个伪键 `Length`）。遂改走 **CDP `Runtime.evaluate`**
> （`returnByValue` + `awaitPromise`），拿到未截断全量。后续所有 JS 探针均走 CDP。

**首启态 `localStorage.length = 3`**：

| # | 键名 | 值长度 | 值（截断 400） |
|---|---|---|---|
| 1 | `opencode.global.dat:notification` | 11 | `{"list":[]}` |
| 2 | `opencode.global.dat:extension.updater.seen` | 20 | `{"version":"2.0.22"}` |
| 3 | `opencode.global.dat:command.catalog.v1` | 400(截断) | `{"btw.ask":{"title":"Ask a side question","description":"Get a one-shot answer without adding to the conversation","category":"Session","section":"session","slash":"btw"},"debug.toggle":{"title":"Toggle debug bar","category":"View"},"terminal.toggle":{…}}` |

- **没有任何键包含 `127.0.0.1:4173`。**
- **没有任何键是服务器列表 / servers / connections 结构。**
- 键名前缀统一 `opencode.global.dat:*`（上游全局 KV 命名空间）：通知队列、扩展更新已读版本、命令目录。
- `sessionStorage` = `{}`（空）。
- `opencode.settings.dat:defaultServerUrl` → **`null`**，且该键**根本不存在**于这 3 键里（属"键不存在"而非"值为 null"）。首启确认 ✔

### 1.1 决定性实验：点「设为默认」后重转储

点开 4173 行的 `…` → 选 `Set as default`，再次全量转储：

```
localStorage.length: 3 → 4
新增且仅新增 1 个键：
  opencode.settings.dat:defaultServerUrl = "http://127.0.0.1:4173"
indexedDB.databases(): 仍只有 [{"name":"opencode-drafts","version":1}]（无变化）
```

**"设为默认"没有创建任何服务器列表，只写了一个指向同一 origin 的字符串指针。**

---

## 2. IndexedDB / 其它存储排查

```
indexedDB.databases() => [{"name":"opencode-drafts","version":1}]
```

| store | 行数 |
|---|---|
| `blobs` | 0 |
| `documents` | 0 |

两 store 全空，是"草稿/附件"库，**不是服务器注册表**。

```
caches.keys() => ["workbox-precache-v2-http://127.0.0.1:4173/"]
```
仅 1 条 Workbox 预缓存（SW 静态资源壳），无用户数据。

### 存储介质总账（首启态）

| 介质 | 内容 | 是否注册表 |
|---|---|---|
| localStorage | 3 键，全为 `opencode.global.dat:*` | ❌ |
| sessionStorage | 空 | ❌ |
| IndexedDB | `opencode-drafts` v1（blobs/documents 均空） | ❌ |
| Cache Storage | workbox 预缓存 1 条 | ❌ |

---

## 3. bundle 静态取证：为什么"删不掉也藏不掉"

对实际下发的 `/_assets/index-D2r3WEwY.js`（613,877 B）做全文检索：

| 检索 | 结果 |
|---|---|
| `4173` 字面量 | **0 命中** —— 内置条目不是硬编码字符串 |
| `"connections"` | **0 命中** |
| `opencode.*dat*` 键 | 仅 2 个：`opencode.settings.dat:defaultServerUrl`、`opencode.updater` |
| `localStorage.setItem` 调用点 | 仅 3 处：主题 CSS、`defaultServerUrl`、`opencode.pwa.last-route` |

核心源码（原文）：

```js
function ZI(){ return location.origin }                                  // currentServerUrl
function QI(){ ... localStorage.getItem(YI) }   // YI = `opencode.settings.dat:defaultServerUrl`
function $I(e){ if(e!==null){ localStorage.setItem(YI,e); return } localStorage.removeItem(YI) }
… currentServerUrl: t, defaultServerUrl: n ?? t
```

**⇒ web 构建里"服务器"只有一个概念：`location.origin` 本身。** 没有数组、没有 id、没有记录可删。

对照：**桌面端才有多服务器注册表**。bundle 里 `Wd()` 通过 `Vr().list` 取列表，
插件通过 `stores:{servers: bi.main(nr(xF), [], {settings:'ssh.servers'})}` 注册——
`ssh.servers` / WSL 插件提供（SSH/WSL 桌面客户端能力）。**我们的 web 包不带任何插件**，
所以 `servers` 集合恒为空，界面只剩 origin 这一个隐式条目。

### i18n 幻影条目定位

`dialog.server.*` 键在 bundle 中**全部存在**（各 2 次＝声明+引用）：

```
dialog.server.menu.edit        dialog.server.menu.remove   ← 存在但未渲染
dialog.server.menu.default     dialog.server.menu.hide     ← 存在但未渲染
dialog.server.menu.defaultRemove  dialog.server.menu.show ← 存在但未渲染
dialog.server.add.{title,url,placeholder,error,checking,button,name,namePlaceholder,password,passwordPlaceholder}
dialog.server.edit.title   dialog.server.status.default
```

而**桌面端**的服务器菜单词条是另一套（`menu.default` / `menu.defaultRemove` /
`menu.remove` / `menu.delete`），属于带 `servers` store 的那套 UI。

⇒ 派单说的"文案存在但没渲染"属实，但根因是**渲染条件永不成立**（没有 list 数据源），
不是漏写一个分支。**P5 宿主侧无法靠补渲染修复，必须改数据模型。**

---

## 4. 菜单出口排查（真实 UI 全扫）

设置 → Server 页（`/settings?tab=servers`）。注意：左侧导航在手机断点下是
`hidden lg:flex`（`Settings/Help/Server` 全部 `vis:false`），只能经应用自身路由进入——
这是**另一个移动端可达性缺口**，一并记录。

### 面 1 — 设置 → Server → 4173 行 `…` 菜单

| 项 | 结果 |
|---|---|
| 按钮可及性 | ✔（`More options` / aria-label，CSS 354,299，20×20） |
| 菜单项（`role=menuitem` 全量枚举） | **仅 2 项**：`Edit`、`Set as default` |
| 隐藏项 | 无——枚举全部 `role=menuitem` 元素，无第三项、无 `vis:false` 项 |
| **移除/隐藏** | ❌ **无** |

截图：`research/web/p4_s03_servers.png`、`p4_s04_dotsmenu.png`

### 面 2 — Home 顶栏 `All projects ▾`

**派单对该面的描述有误**：它是**项目选择器**，不是服务器切换器。
展开内容仅 `All projects` / `Add project` 两项，**无任何服务器行、无 `…`、无长按菜单**。
⇒ 该面**结构上不存在**，无法提供出口。

截图：`research/web/p4_s02_projects.png`

### 面 3 — 设置左侧导航 / 服务器行长按

| 动作 | 结果 |
|---|---|
| 4173 行长按（`input swipe` 同点 900 ms） | ❌ 无任何菜单弹出，页面文本无变化 |
| 左侧导航行 | 手机断点下 `hidden`，**元素不存在于可见树**，无从长按 |

截图：`research/web/p4_s07_longpress.png`

### 面 4 — 加第二条服务器后复扫

**未能构造出第二条**（重要发现）：

- Add server 对话框提交 `http://localhost:4096` → 前端弹 **`Could not connect to server`**
  （`dialog.server.add.error`），条目**不落库**。
- 已补 `adb reverse tcp:4096 tcp:4096` 后重试，仍失败——页面 origin 是 `http://127.0.0.1:4173`，
  跨源探测被拒（CORS/cleartext 组合问题），非存储问题。
- 派单设想的"加 4095 后两条"这一状态**在本环境不可达**；4095 更是死端口。

### 判定

**四个面全部确认：上游 web 构建无任何「移除/隐藏」出口。**
且根因已定位到数据模型层（§3），非 UI 分支缺失。

---

## 5. 4173 全局流空转计数

12 s 采样窗（`logcat -v threadtime` 全量抓取后计数）：

| 指标 | 值 |
|---|---|
| 窗口内 `event stream OPEN` 行数 | **12** |
| 序号跨度 `#360 → #371` | delta 11 |
| 折算频率 | **≈ 1.07 次/秒** |
| `127.0.0.1:4173/api/event` 相关行 | 24 |
| **`status=404` 行** | **24（100%）** |

样例：

```
[oc-pow] event stream OPEN #360 http://127.0.0.1:4173/api/event status=404 @783
OcPerfJS res #544 api start=366540 dur=9 enc=9 dec=9 xfer=309 http://127.0.0.1:4173/api/event @19
[oc-pow] event stream OPEN #361 http://127.0.0.1:4173/api/event status=404 @783
```

**首启态空转确认存在：~1 次/秒，且每次都是 404**——4173 只提供静态壳，不提供 `/api/event`。

---

## 6. 收尾

- `adb reverse tcp:4095 tcp:4095` → 已 remove；本轮临时加的 `tcp:4096 tcp:4096` → 已 remove
- `adb forward tcp:9333` → 已 remove
- 自建 `emulator-6790` → 已关闭；`emulator-5554`（他人 AVD）**未触碰**，未 kill-server
- 本轮临时件 `_scratch_cdp_eval.mjs` / `_scratch_p4_*.txt|json` → 已清理

---

## 7. 给 P5 的结论

1. **不存在可删的注册表。** 无需改存储层去"加一个删除按钮"——没有数据可删。
2. 若要支持多服务器，宿主必须补齐**插件侧能力**（bundle 里的 `servers` store 由 SSH/WSL 插件注入，
   web 包未打包），或自行实现 server 列表 + 持久化。
3. 若只想解 4173 的空转，应在宿主侧拦掉 `/api/event` 的 1Hz 重试（404 后退避/停止重连），
   这与"移除条目"是两件独立的事。

---

## 8. 遗留 / 未验证

- 未能在"两条服务器"状态下复扫菜单（Add server 被 `Could not connect to server` 挡住）；
  但由于 §3 已证明列表数据源不存在，该状态即便达成也不会多出出口。
- `adb reverse` 重建后仍连不上 `localhost:4096`，具体是 CORS 还是 cleartext 未逐层定位（超出本轮只读范围）。
- 设置页左侧导航在手机断点下整体隐藏，属**新发现的可达性缺口**，未在本轮修复（只读纪律）。