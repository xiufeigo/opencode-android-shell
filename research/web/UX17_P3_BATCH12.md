# UX17-P3 — 批 #12 组合复验（DOM 隐藏 v2 + 崩溃自愈 Kotlin + T6 长回合）

**轮次**：UX17-P3 · **日期**：2026-10-07 · **性质**：只验证，不改代码
**被验包**：`research/deliver/opencode-shell-web.apk` = 16,830,627 B / md5 `FE2A8AA88F7A893639AB11E2458D5FAB`（装前核对一致）
**被验组合**：DOM 隐藏 v2（回退自 `ac0bb01`） + WebView 重建式自愈 Kotlin（UX16-P2 未提交部分）

---

## 0. 环境与 serial

| 项 | 值 |
|---|---|
| 自建 AVD | `Medium_Phone_API_37.0`，端口 **6790**（serial `emulator-6790`） |
| 禁写设备 | `emulator-5554`（Pixel_Tablet）— **本轮零写操作** |
| 后端 | `127.0.0.1:4095`，pid **21136**，全程未起停（收尾复验 200） |
| reverse | `adb -s emulator-6790 reverse tcp:4095 tcp:4095`（收尾已 remove） |
| 包名 | `com.xiufeigo.opencodeshell.web.debug`（非 `com.opencode.shell`；activity `…web.WebHostActivity`） |
| 注入通道 | `am start --es oc_debug_eval "b64:<base64(utf8 js)>"` → `OcWebHost/Eval`（debug 构建有效） |

> 剧本坐标按给定值使用；`Settings (179,2324)`、`+ Add server (906,482)`、提交 `(907,1652)` 均命中。
> 例外：Home 的「All projects」下拉在**本包 1080×2400 布局**下真实位置是 `(541,263)`，且**被 DOM v2 隐藏**（见 §2），故剧本给的下拉路径不可走。

---

## 1. 步骤 0：首启基线 — ✅ PASS

| 判定 | 结果 | 证据 |
|---|---|---|
| 4173 builtin 行**可见** | ✅ | 首启 Home + 设置导航 → Server 段截图存在 `127.0.0.1:4173` 行，**红点**（未连接） |
| `localStorage['opencode.global.dat:server']` = null | ✅ | 注入读回 `{"ls_server":null,…}`；keys 仅 `notification` / `extension.updater.seen` / `command.catalog.v1` — **`server` 键根本不存在** |

`4173` 首启可见是 DOM v2 **条件隐藏**的预期行为（`hasUserServerRegistry()` = false ⇒ 不隐藏，builtin 行作为官方 onboarding 入口保留）。

---

## 2. 步骤 1：Add 4095 后的 DOM v2 行为 — ⚠️ 前半 PASS / **发现阻断级回归**

### 2.1 Add 成功与条件隐藏生效 — ✅

| 判定 | 结果 | 证据 |
|---|---|---|
| 绿点 | ✅ | Server 段 `127.0.0.1:4095` 行显示**绿点** |
| 4173 行从设置导航消失 | ✅ | 段选择器条目由 `Server` 变为 **`127.0.0.1:4095`**；`document.body.innerText.includes('4173')` = **false** |
| 注册表只剩 4095 | ✅ | `opencode.global.dat:server` = `{"list":[{"type":"http","http":{"url":"http://127.0.0.1:4095","password":"opencode"}}],…}` — **无 4173 条目** |
| v2 钩子状态 | ✅ | `hasUserServerRegistry()`=**true**、`startPhantomServerHide` 已装、`__ocPhantomHideObserver` 在跑 |

### 2.2 🔴 **回归：v2 隐藏过度匹配，把「服务器切换器」本身隐藏了**

Add 4095 之后，Home 页出现**永久 Loading**，且**没有任何可见途径切上下文**。定位过程：

1. Home 恒显 `Loading` + `Search sessions in 127.0.0.1:4173`；`document.body.innerText` = `Home|0|Loading`。
2. logcat 显示事件流打 **`http://127.0.0.1:4173/api/event` → 404**（每秒重连），而 `4095/api/project` / `4095/api/session` 虽在请求但 `xfer=0`。
3. 后端侧健康：直连 `http://127.0.0.1:4095/api/session` = **200 / 2699 B**（`ses_ef43fab08…` 等真实会话）；页面内 `fetch` 同 URL 也 **200 / 2699 B** ⇒ **网络与跨域均通**，不是后端或 cleartext 问题。
4. DOM 里存在切换器触发按钮，文本 `All projects127.0.0.1:4173`，但 `getComputedStyle().display = **none**`、`w=0,h=0`。
5. 注入样式表里只有一条隐藏规则（9 个 `<style>` 中的第 7 条，45 字符）：
   ```css
   [data-oc-phantom="1"]{display:none!important}
   ```
6. 全文档扫描 `[data-oc-phantom="1"]`：**count = 1**，唯一命中就是那个切换器触发按钮（`BUTTON`，class `flex h-10 w-full min-w-0 items-center gap-2 rounded-[6px] bg…`）。
7. **因果确证**：运行时 `b.removeAttribute('data-oc-phantom')` ⇒ 立刻 `display:flex`、`369×40 @ y=80`（设备坐标 ≈ `(541,263)`）。

**成因**：v2 的隐藏匹配是「元素直接文本含 `4173`」。首屏 Home 的**服务器切换器触发按钮**天然显示当前服务器名 `127.0.0.1:4173`（`bootUrl()` 恒为 `LocalUiOriginServer.origin()`，见 `WebHostActivity.kt:459-460`），于是这个**用来逃离 4173 的控件本身**被打上 phantom 标签隐藏 —— 典型的自锁死。

**影响面**：这是一个**条件触发的死锁**——
- 首启（registry 空）hide 不激活 ⇒ 正常；
- **一旦用户添加了自己的服务器，hide 激活，而上下文尚未切走 ⇒ 切换器消失 ⇒ 用户无法切上下文 ⇒ Home 永远 Loading。**
- 剧本给的「Home 服务器下拉点 4095 行」与「抽屉→Settings→Server 段里的 4095 条目」两条路径中，**前者被本回归直接堵死**；后者实测**不会切换上下文**（点 4095 行无反应，点 `…` 菜单的 `Set as default` 只写了 `opencode.settings.dat:defaultServerUrl`，冷启后上下文仍回到 4173）。

### 2.3 手动切上下文（绕过回归后）⇒ 列表渲染 — ✅ PASS

去掉 phantom 标签 → 点开切换器 → 选 4095（绿点行）后：

- 占位符变为 **`Search sessions in 127.0.0.1:4095`**；
- 列表**正常渲染 6 条**：`Today / v2 smoke test (OpenCodeUI)`、`Yesterday / Greeting (OpenCodeUI)`、`Greeting (/)`、`New session (OpenCodeUI)`、`Older / Single word reply request (OpenCodeUI)`、`New session (OpenCodeUI)`。

**结论**：「手动切上下文 ⇒ 列表渲染」这一格本身**功能是好的**；但**在不改代码的前提下用户无法触达该路径**，因为触发控件被 v2 自己隐藏。判定 **步骤 1 = ⚠️ 部分 PASS + 阻断级回归**。

---

## 3. 步骤 2：T6 长回合 — ⚠️ 1/4 格

进入 `v2 smoke test` 会话（`ses_ef43fab08ffeoHiNxtinjW9V0c`），`pm grant` 授予 `POST_NOTIFICATIONS`（10235），发 `Count from 1 to 500 comma separated`，**发送后 ~0.9 s 按 HOME**。

| 格 | 判定 | 证据 |
|---|---|---|
| 保活（后台 round 继续） | ✅ | `P7 onPause: round active -> keep SSE alive (blur only, no pagehide)` @02:20:57.797 |
| `round end succeeded` | ❌ | 实际为 `round end **session.execution.failed**` @02:20:59.698；紧接 `P7 background round ended -> dispatch pagehide (release SSE)` @02:20:59.799 |
| 通知存在 | ❌ | `dumpsys notification --noredact` 中 `pkg=com.xiufeigo.opencodeshell.web.debug` 的 `NotificationRecord` 计数 = **0**（只有 `android` / `com.android.systemui` 的记录） |
| 点回会话 | ⚠️ | **无通知可点**（上一格已失败）。仅能复验「重开任务回会话」：`am start` 后 `href` 仍为 `/server/…/session/ses_ef43fab08…`，转录与模型输出（`1, 2, 3, …`）在位 |

**失败线索（如实记录，未深挖）**：
- prompt 请求确实发出：`…/api/session/ses_ef43fab08…/prompt` dur=31（但 `xfer=0`）。
- 事件流被终止：`K2.onReceivedError url=http://127.0.0.1:4095/api/event code=-1 desc=net::ERR_FAILED` @02:20:59.815。
- 发送后约 2 s 即判 `session.execution.failed`，**早于任何正常完成**；需下一轮定位是后端执行失败还是 SSE 提前释放导致。
- 附带观察：注入文本经 `%s` 转义后落到 UI 显示为 `Count tofrom 1 to 500, comma separated, think step by step`（多一个 `to`，且 UI 追加了 `, think step by step`）—— 属注入/文案层面，未影响本轮判定。

---

## 4. 步骤 3：崩溃自愈组合快验 — ⏭️ 未执行

时间预算（步骤 1 的过度隐藏排查占用）耗尽，**未做 CDP `Page.crash`**。本轮不对崩溃自愈下任何结论；节流与中文已在 UX16-P2 证过，不重复。

---

## 5. 结论

1. **DOM v2 条件隐藏的「该藏」侧成立**：Add 4095 → 绿点、4173 行从设置导航消失、注册表只剩 4095、v2 三钩子全活。
2. **DOM v2 存在阻断级过度匹配回归**：切换器触发按钮因文本含 `4173` 被打 `data-oc-phantom="1"` 隐藏（唯一命中，规则 `display:none!important`），造成「加了服务器反而切不动上下文、Home 永久 Loading」的自锁死。**这是本轮最有价值的发现，属组合面问题**。
3. **T6 仅 1/4 格**：保活 ✅；`round end succeeded` ❌（实为 `session.execution.failed`）；通知 ❌（0 条）；点回 ⚠️（无通知可点，重开任务可回会话）。
4. 崩溃自愈组合面**未覆盖**。

**建议下一步**（只给方向，本轮不改代码）：把 v2 匹配从「直接文本含 `4173`」收紧为「**位于服务器列表容器内**且**该行自身**代表 4173 条目」，或对已知切换器/当前服务器标签类节点做显式豁免；并补一条断言：hide 激活后，服务器切换器触发控件必须仍可见可点。

---

## 6. 收尾

- 自建 AVD `emulator-6790` 已 kill；`emulator-5554` 全程未写。
- `adb -s emulator-6790 reverse --remove tcp:4095` 已执行。
- 后端 pid **21136** 未变、`http://127.0.0.1:4095` → **200**。

（本节数值以收尾实测为准，见汇报最后一行。）
