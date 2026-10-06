# UX14-P2 —— last-route 恢复归属查证 + 新架构首屏中位数 n=5

> **日期**：2026-10-06 · **轮次**：UX14-P2
> **性质**：**只验证，不改代码**。未碰 `ui/` / `app/**` / 五个注入件；未 commit；未碰 gradle。
> **纪律执行**：4095 后端（pid **12748**，v2.0.24）只读**未起停**；仅关闭自建 `emulator-6790`；**未 kill-server**；`adb reverse tcp:4095` 已收尾 remove。

---

## 0. 结论速览

| 项 | 结论 |
|---|---|
| ① last-route 恢复归属 | **B —— 上游固有（web 非 standalone：既不写、也不读）** |
| ② 首屏中位数 n=5 | **2,344 ms**（min 2,306 / max 2,897） |
| ③ 批 #6 启动冒烟 | **PASS**（5/5 冷启均渲染官方 Home + 4095 会话列表） |

---

## 1. 环境与工件登记

| 项 | 值 |
|---|---|
| 包 | `research/deliver/opencode-shell-web.apk`（批 #6） |
| **装前 SHA-256** | `CDD3EE9BBB61ED84FE00B7BEDC7D489D14838ACB30C9D36C67CEB76F76EA7524` |
| 实际包名 | `com.xiufeigo.opencodeshell.web.debug`（**不是** `…opencodeshell`） |
| 主 Activity | `com.xiufeigo.opencodeshell.web.WebHostActivity` |
| AVD / serial | `Medium_Phone_API_37.0` / **`emulator-6790`**（sdk 37，boot_completed=1） |
| 页面 origin | `http://127.0.0.1:4173`（UX12 方案 A 本地源） |
| 后端 | `127.0.0.1:4095`，Basic `opencode/opencode`，`adb reverse` 映射 |
| 上游 bundle | `webapp/src/main/assets/www/ui/oa/index-D2r3WEwY.js`（613,877 chars） |
| **碰过的 serial** | **仅 `emulator-6790`** |

> `adb devices` 上另有一条 `emulator-5554`（AVD `dshr_tab`，pid 34408，**非本轮自建**）。
> 全程**未触碰**，收尾**未关闭**。`adb` 不在 PATH，实际用
> `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`。

### Step 0 实测要点

`install -r` ✅ → `pm clear` ✅ → `adb reverse tcp:4095 tcp:4095` ✅ → `am start` → UI 登记：
Home 抽屉 → Settings → **Server** 分区 → **Add server** → `http://127.0.0.1:4095` + password `opencode`
→ 等 **16 s**（≥14 s）提交 → 回 Home → 项目行右侧 chevron（`991,262`）→ 服务器列表点 **4095** 行（**绿点**）。

4095 上下文下官方会话列表正常加载（v2 smoke test / Greeting ×2 / New session / Yesterday 组）。

---

## 2. 步骤 1：last-route 恢复归属 —— 判定 **B**

### 2.1 实测证据（注入只读打点）

**① 进会话后（`v2 smoke test`）注入读：**

```json
{"pathname":"/server/aHR0cDovLzEyNy4wLjAuMTo0MDk1/session/ses_ef43fab08ffeoHiNxtinjW9V0c",
 "lastRoute":null,
 "standalone":false,
 "lsKeys":["opencode.global.dat:server","opencode.global.dat:layout.scoped",
           "opencode.global.dat:notification","opencode.global.dat:layout",
           "opencode.window.browser.dat:tabs","opencode.window.browser.dat:tabs.recent",
           "opencode.global.dat:extension.updater.seen",
           "opencode.global.dat:http://127.0.0.1:4095 notification",
           "opencode.window.browser.dat:tabs.info","opencode.global.dat:command.catalog.v1"]}
```

**② `am force-stop` → 静置 5 s → `am start` → 等 8 s 后再注入读：**

```json
{"pathname":"/","lastRoute":null,"standalone":false}
```

**③ 读上游自己写在挂载根节点上的标记（决定性）：**

```json
{"found":true,"standaloneAttr":"false","mql":false,"navStandalone":false,
 "lastRoute":null,"pathname":"/"}
```

三条事实合起来：

1. `pathname` 确实变过真实会话路由（`/server/<base64>/session/<id>`）—— **路由切换真实发生**；
2. `last-route` **自始至终 `null`**，而同一个 localStorage 里 **10 个其它键都在** ⇒
   **不是 localStorage 不可用，是这个键上游压根没写**；
3. 上游**自己**把 `data-standalone="false"` 写在了挂载根节点上 ⇒ `tL()` 判负。

### 2.2 上游 bundle 原文（三函数 + 挂载点）

文件 `ui/oa/index-D2r3WEwY.js`。**先修正本轮自己的一处误判**（见 §2.3）：
最初按 `rL(` 搜调用点得到「零调用」的结论**是错的** —— 上游把 `rL` 当 **Solid 组件**用，调用形态是
`x(rL,{})` 而非 `rL()`，正则漏掉了。改用**裸标识符**扫描后，各函数恰为 **2 处引用**（定义 + 唯一使用点）：

**键名与三个函数（原文，@569121 起）：**

```js
var eL=`opencode.pwa.last-route`;
function tL(){
  return window.matchMedia(`(display-mode: standalone)`).matches
      || `standalone`in navigator&&navigator.standalone===!0
}
function nL(){
  if(!(location.pathname!==`/`||location.search||location.hash))
    try{
      let e=localStorage.getItem(eL);
      if(!e)return;
      let t=new URL(e,location.origin);
      if(t.origin!==location.origin||t.searchParams.has(`auth_token`)
         ||t.pathname!==`/`&&t.pathname!==`/new-session`
         &&!/^\/server\/[^/]+\/session\/[^/]+$/.test(t.pathname))return;
      history.replaceState(history.state,``,t.pathname+t.search+t.hash)
    }catch{}
}
function rL(){
  let e=yt();
  return F(()=>{
    let t=e.pathname+e.search+e.hash;
    try{localStorage.setItem(eL,t)}catch{}
  }),null
}
```

> 注：`rL()` **函数体内部没有** standalone 判定 —— 它是无条件写的。

**挂载点（原文，@612521 起，决定性）：**

```js
cL.dataset.opencodeMounted===void 0&&(cL.dataset.opencodeMounted=``,xr().then(e=>{
  let t=Hr(new URLSearchParams(location.search).get(`auth_token`));
  lL();
  let n=tL();                                   // ← 唯一的门
  cL.dataset.standalone=String(n),
  n&&nL();                                      // ← 读：仅 standalone 才恢复
  let r=uL.currentServerUrl?{type:`http`,authToken:!!t,http:{url:uL.currentServerUrl,...t}}:void 0;
  R(()=>x(gr,{get value(){return uL.platform},get children(){return x(OI,{locale:e,
    get children(){return x(kI,{ ..., get children(){
      return [x(iL,{}), a(()=>n&&x(rL,{}))]      // ← 写：仅 standalone 才挂载写入器
    }})}})}}))
```

裸标识符扫描全量结果（各 **2** 处 = 定义 1 + 使用 1）：

| 标识符 | 定义 | 唯一使用点 |
|---|---|---|
| `tL` | `function tL(){…}` | `let n=tL()` |
| `nL` | `function nL(){…}` | `n&&nL()` |
| `rL` | `function rL(){…}` | `a(()=>n&&x(rL,{}))` |
| `iL` | `function iL(){…}`（键盘/visualViewport，与本题无关） | `x(iL,{})` |

（`chunk-FOHPRMQF-DHwB1DNv.js` 另有 `rL`/`tL` 字样，经核对是 **Shiki 语言文法注册表**的
`Grammar:o(()=>rL(),\`Grammar\`)`，与 `last-route` 无关，属误命中。）

### 2.3 归属判定

**B —— 上游固有：`last-route` 的「写」和「读」被同一个 standalone 闸门挡在门外。**

链路是一条，不是两条：

- `n = tL()` 在 WebView 里恒为 **false**（实测 `matchMedia("(display-mode: standalone)")=false`、
  `"standalone" in navigator = false`、上游自写 `data-standalone="false"`）；
- 于是 **写侧** `a(()=>n&&x(rL,{}))` 里的 `rL` 组件**根本没被挂载** ⇒ 监听器从未存在 ⇒
  `localStorage.setItem("opencode.pwa.last-route", …)` 从未执行 ⇒ 键**永远不会被创建**；
- 同时 **读侧** `n&&nL()` 同样短路，`nL()` 从不执行。

所以实测到的「重进后 `/`」**不是**恢复失败，而是**恢复链路两端都没启动**。
A（写了没生效）与 C（被拒绝条件挡下）都不成立：**`lastRoute` 在会话里就已经是 `null`**，值根本不存在。

补充：`nL()` 自身另带白名单（`origin` 同源、禁 `auth_token`、路径限 `/`、`/new-session`、
`^/server/[^/]+/session/[^/]+$`）。本次真实会话路由 `/server/<base64>/session/<id>`
**本来完全符合**该白名单 —— 即便强行写入该键并绕过 standalone 门，恢复也应当成立。
**结论：这属于「上游 PWA 恢复特性在 WebView 容器下不启用」的架构差异，不是本宿主的缺陷。**

> 与 UX13-P14 既有记载（「重进恢复 = 上下文 + defaultServerUrl ✅、last-route 未恢复」）一致；
> 本轮把「未恢复」的**原因**从「内存 history 架构差异」精确到**上游 standalone 闸门**这一行代码。

### 2.4 顺带记录：什么**确实**恢复了

冷启后落到 `/`（无会话路由恢复），但**服务器上下文确实恢复**了：Home 顶部直接显示
`All projects / 127.0.0.1:4095` 并列出 4095 的真实会话（§3 表下截图）。
即：`defaultServerUrl`/服务器上下文走的是**另一条**（非 PWA `last-route`）机制，**不受该闸门影响**。

---

## 3. 步骤 2：新架构冷启首屏 n=5

### 3.1 口径定义

- **冷启** = `am force-stop` + **3 s** 停顿 + `am start`；
- **计时锚点** = logcat `OcPerf` 的 `M.g1.firstContent` 的 **`t=`** 值（UX 口径沿用）；
  `t=` 由 `PerfMarks.mark` 计算 = `elapsedRealtime() - Process.getStartUptimeMillis()`，
  即**以进程创建为 0**，不受设备时钟影响（`PerfMarks.kt:19-31`）；
- **状态**：已鉴权、已连 4095（`force-stop` 不清数据，localStorage 保留）⇒
  **不含**「添加服务器」耗时。

### 3.2 实测 5 次

| # | `t=` | kind |
|---|---|---|
| 1 | **2,897 ms** | `dcl` |
| 2 | **2,344 ms** | `raf` |
| 3 | **2,321 ms** | `raf` |
| 4 | **2,306 ms** | `raf` |
| 5 | **2,347 ms** | `raf` |

排序：`2306, 2321, **2344**, 2347, 2897`

- **中位数 = 2,344 ms**
- 极值：min **2,306 ms** / max **2,897 ms**（n=5，仅供参考，样本小）

### 3.3 口径偏差（如实标注，不粉饰）

UX 口径写的是 `kind=dcl`，但**实测只有第 1 次是 `dcl`，其余 4 次是 `raf`**。
原因在 `oc-preflight.js`：`report()` 有 `done` 单次闸门，`fcp` / `raf`(2 帧) / `dcl` **谁先到谁上报**，
而 2 帧 rAF 通常早于 `DOMContentLoaded`。宿主 `WebHostActivity.onFirstContent()` 亦有
`if (firstContentSeen) return`，**每次启动只落一条**。

⇒ **无法保证每次都取到 `kind=dcl`**。上表按**实际发生的 kind** 记录，未做任何挑选或替换。
第 1 次 `dcl` 的 2,897 ms 相对偏高，与「dcl 本身更晚到达」一致。

### 3.4 与旧数字的关系（明确不相减）

旧的 **1,676 ms** 属**已作废的 R2 件**（UX12 批之前、旧前端架构）。
本表是 **批 #6 + 本机（API 37 AVD）+ 新架构** 的**新基准**，
**只作新架构记录，不与旧数相减、不作「快了/慢了」结论**。

---

## 4. 步骤 2 附带：批 #6 启动冒烟 —— **PASS**

5 次冷启**全部**渲染出官方 Home（标题 `Home`、`All projects / 127.0.0.1:4095`、
真实会话列表、输入框），无白屏 / 无诊断页 / 无 crash。
⇒ UX14-P1 接线 `onRenderProcessGone` **未把正常启动弄坏**。

---

## 5. 收尾核对

| 项 | 状态 |
|---|---|
| `adb reverse --remove tcp:4095` | ✅ 已执行，`reverse --list` 为空 |
| 自建 `emulator-6790` | ✅ 已关闭 |
| 他人 `emulator-5554` | ❌ 未触碰（保持运行） |
| `adb kill-server` | ❌ 未执行 |
| 4095 后端 pid 12748 | ❌ 未起停 |
| 代码 / gradle / 注入件 | ❌ 未修改（`git status` 仅存本轮前既有条目） |

---

## 6. 方法论留档：一次**被自己推翻的 grep**

本轮先按 `rL\(` 搜调用点，得 1 命中 ⇒ 一度判「`rL` 是死代码、从不被调用」。
**该结论错误**：上游以 Solid 组件形态调用它（`x(rL,{})`），正则要求 `(` 紧跟，
把唯一的真实调用点漏掉了。若就此写报告，归属会从「B：standalone 闸门」
错成「B：上游死代码」——**结论字母相同，归因不同**，后者会让宿主背上不属于它的锅。

改用裸标识符 `(?![\w$])rL(?![\w$])` 复核后才拿到 §2.2 的挂载点原文。
**教训**：在压缩产物上做「调用点」判断，**函数名 + `(` 不是充分模式**；
组件式调用（`x(f,{})`）与成员式调用（`o.f()`）都要单独覆盖，否则「零调用」是**假阴性**。