# opencode v2 — 动效与排版「存疑项」定案（P2 取证）

> 目标：核实 `research/03_motion_tokens.md` 中 3 个标为「未确证/存疑」的项，并补齐 Compose 复刻所需的字重映射证据。
>
> **取证范围（全部本地文件，无网络推测）**
> | 代号 | 路径 | 大小 |
> |---|---|---|
> | **MJS** | `app/src/main/assets/opencode-app/assets/index-CKBO9Mty.js` | 2,765,259 B（minified，单行） |
> | **MCSS** | `app/src/main/assets/opencode-app/assets/index-D_rl_FNw.css` | 491,836 B（minified，单行） |
> | **SRC** | `research/_raw/files/packages__ui__src__theme__*.ts`、`.../styles/theme.css`、`.../styles/tokens/theme.css`、`packages__app__src__*`、`packages__session-ui__src__*` | 上游源码副本（49 份） |
> | **TREE** | `research/_raw/tree-paths.txt` | 上游文件清单 |
> | **HTML** | `app/src/main/assets/opencode-app/index.html`、`oc-theme-preload.js` | — |
>
> 版本锚点（MJS 内）：`e$="1.17.19"`。基线笔记：`research/03_motion_tokens.md`。

---

## 1. `blinking-cursor`（流式文本光标）— **定案：无流式光标**

### 结论
**opencode v2（本 vendored 构建）在 assistant 文本流式输出时没有闪烁光标，既没有 `▍/|` 字符，也没有任何光标元素。**
`blinking-cursor` 是**确凿的死代码**（唯一出现位置是一条从未被引用的 CSS 规则）。流式"打字感"由 **节奏化文本揭示（paced reveal）** 实现，不是光标。

### 证据（逐条可复现）

**(1) 全量资源扫描：`blinking-cursor` 只有 1 处，且无任何引用方**
- 对 `app/src/main/assets/opencode-app/` 下**全部** js/css/html/json 逐文件文本检索：命中仅 **MCSS** 一处。
  - MCSS @offset **214442**：
    ```css
    @keyframes blink{0%,50%{opacity:1}51%,to{opacity:0}}
    .blinking-cursor{animation:1s step-end infinite blink}
    ```
    紧邻上下文是 `[data-component=tooltip]` 与 `[data-component=tool-trigger]` 的规则 —— 这条规则像是**历史遗留的孤立片段**。
- **MJS 内 `blinking-cursor` 出现次数 = 0**（`IndexOf` 精确计数）。
- MJS 内 `blink` 出现次数 = 1，且**不是**光标：
  ```
  const I8=Es(["a","abbr",...,"bdi","bdo","big","blink","blockquote",...])
  ```
  这是 solid-js 的 **HTML 标签名白名单**（`<blink>` 是废弃标签），与光标无关。

**(2) 关键帧总量核对**：MCSS 共 **72** 个 `@keyframes`，其中叫 `blink` 的只有上面这 1 个；
**没有任何 class / 属性选择器引用 `blink`**，也没有第二条 `animation:...blink` 声明。

**(3) DOM 层：文本 part 的模板里根本没有光标节点**
MJS 中文本 part 与 reasoning part 的模板是**完整的字面量**（solid 编译产物，模板即最终 DOM 骨架）：
```js
// MJS @2365102
yKe=P("<div data-component=text-part><div data-slot=text-part-body>")
// MJS @2365172
bKe=P("<div data-component=reasoning-part>")
// MJS @2016565
t9e=P("<div data-component=markdown dir=auto>")
```
MCSS 中 `[data-component=text-part]` 的**全部 9 条规则**只涉及：容器 `width/margin-top:24px`、
`[data-slot=text-part-body]`（`margin-top:0`）、`[data-slot=text-part-copy-wrapper]`（hover 才显现的复制按钮条，`opacity .15s` 过渡）、
`[data-slot=text-part-meta]`。**没有任何光标/`::after`/光标伪元素规则**。

**(4) 文本流式渲染路径：`PacedMarkdown` ≈ 纯节奏揭示，无光标**
- `RQ`（流式包装，MJS @2370784）→ `j0`（Markdown 组件）→ markdown worker 投影（`x7e` "project"，MJS @1973209）。
- 节奏控制器（MJS @2369930 附近）：
  ```js
  const QR = 24,           // ms —— 推进间隔
        JR = 512,          // 追赶阈值（落后 <=512 字直接补齐）
        VKe = /[\s.,!?;:)\]]/;
  function jKe(e){ return e<=12?2 : e<=48?4 : e<=96?8 : Math.min(256, Math.ceil(e/4)) }  // 每次推进字数
  function HKe(e,t){ /* 向前 8 字符内找词边界对齐，否则落在计算点 */ }
  ```
  即：每 **24ms** 推进若干字符，并在 `空白 / . , ! ? ; : ) ]` 处**按词收口**（避免半词闪现）；落后超过 512 字则直接跳跃。
- 该路径**只更新文本，不注入任何光标节点**（模板常量里没有，CSS 里也没有）。

**(5) 字符级排除**：对 `▍`(U+258D)、`█`(U+2588)、`░`(U+2591) 在 MJS+全部 CSS 中检索 = **0 命中**；
`caret` / `text-cursor` / `cursor-blink` / `textBlink` / `cursorBlink` 在 MJS 中 = **0 命中**。
`cursorBlink` 全仓库**仅 1 处语义相关**，在**终端**里（与聊天文本无关）：
```js
// MJS @2602813
new ge.Terminal({ cursorBlink:!0, cursorStyle:"bar", ..., fontSize:14, fontFamily:n$(...terminalFont()) })
```

**(6) 上游源码侧交叉验证**：`TREE` 中被 grep 命中的 `cursor` / `blink` 全部是**无关物**——
文件类型图标（`packages/ui/src/assets/icons/file-types/blink.svg`、`cursor.svg`）、
编辑器主题（`packages/ui/src/theme/themes/cursor.json` = Cursor 编辑器的配色）、
数据库分页游标（`20260312043431_session_message_cursor.ts`、`session-cursor.test.ts`）、
打开外部应用（"Cursor" App）。**不存在 `blinking-cursor` 相关的组件/样式源文件。**
流式相关源码只有 `packages/session-ui/src/components/markdown-stream.ts`（节奏揭示逻辑）。

### 给 Android 的定案
- **不要实现流式光标**。Compose 侧只需按 `QR=24ms` 节奏推进文本，并在 `[\s.,!?;:)\]]` 处对齐词边界；
  落后超过 `512` 字符直接补齐（防止长回复被"限速"）。
- `blinking-cursor` 的数值（`1s`、`step-end`、`0/50% → 1`，`51%/100% → 0` 的方波）**仅作废案记录**，
  除非产品显式决定加光标（那属于**新增**设计，不是复刻）。

---

## 2. `--font-family-text` / `--font-family-sans` 两版本差异 — **定案：以「构建产物（运行时）」为准**

### 结论（一句话）
**最终生效 = Inter（正文/UI）+ 系统等宽（代码/通用 mono）+ JetBrains Mono Nerd Font Mono（终端）。**
「IBM Plex Mono 440」**在本 vendored 构建里不存在**（无 `@font-face`、无任何引用、字符串 0 命中）；
它只存在于**上游源码副本**里，属于**未随本构建发布的差异**。

### 2.1 两套值并存（关键差异，务必区分"源码值"与"构建值"）

| 变量 | **上游源码副本值**（SRC，可能未发布） | **本构建运行时值**（MCSS / MJS，**最终生效**） |
|---|---|---|
| `--font-family-sans` | `"Inter", ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif`<br>（`SRC: packages__ui__src__styles__theme.css` L2） | `ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif`<br>（**无 Inter**；MCSS `:root` @26232，**后被 JS 覆写**，见 2.3） |
| `--font-family-mono` | `"IBM Plex Mono", ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace`<br>（同文件 L4–5） | `ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace`<br>（**无 IBM Plex Mono**；MCSS @26379，**后被 JS 覆写**） |
| `--font-family-text` | `var(--font-family-sans)`（`SRC: tokens__theme.css` L135） | `"Inter", sans-serif`（**字面量**；MCSS @54706） |
| `--v2-font-family-sans` | `var(--font-family-sans)`（`SRC: tokens__theme.css` L136） | `"Inter", sans-serif`（**字面量**；MCSS @54744） |
| `--v2-font-family-code` | `var(--font-family-mono)`（`SRC: tokens__theme.css` L137） | **构建产物中 0 命中**（未发布） |

> 说明：SRC 的两份主题文件（`styles/theme.css`、`styles/tokens/theme.css`）是 **Tailwind 输入源**；
> 构建产物把它**展开/改写**成了 `:root` 里的字面量，并把 `"Inter"` / `"IBM Plex Mono"` 从
> `--font-family-sans` / `--font-family-mono` 里**移除**（只剩字面量版本落在 `--font-family-text` / `--v2-font-family-sans` 上）。

### 2.2 每种 font-family 的**完整 CSS 值 + fallback + `@font-face` 来源**

**(a) 正文 / UI（最终）** — 由 JS 写回 `:root` 的 `--font-family-sans`：
```js
// MJS @973513 —— Settings 生效副作用
D.style.setProperty("--font-family-mono", Ube(t.appearance?.mono));
D.style.setProperty("--font-family-sans", Wbe(t.appearance?.sans));
// Ube = lD(user, Hbe) ; Hbe = Nbe ; Wbe = lD(user, zbe) ; zbe = Vbe
// MJS @970398 常量：
Nbe = 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace'
Vbe = 'ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif'
jbe = '"JetBrainsMono Nerd Font Mono", ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace'
// lD(e,t) = e?.trim() ? `"${escaped(e)}", ${t}` : t   ← 用户自定义字体前缀 + 内置 fallback
```
用户设了字体时：`"<用户字体>", ui-sans-serif, system-ui, ...`；未设时：**纯系统栈**。

**BUT（重要）** `body` 的排版类走的是另一条链，最终落到 Inter：
```css
/* MCSS @238 & 338400：建站时生成的工具类 */
.font-\(family-name\:--font-family-text\){ font-family: var(--font-family-text) }
```
```js
// MJS @2754650 —— BodyTypography 等价实现（本构建内联版）
const t = e.general.newLayoutDesigns();
document.body.toggleAttribute("data-new-layout", t);
document.body.classList.toggle("text-12-regular", !t);
document.body.classList.toggle("font-(family-name:--font-family-text)", t);
document.body.classList.toggle("text-[13px]", t);      // 13px
document.body.classList.toggle("font-[440]", t);       // 440
```
`--font-family-text` = **`"Inter", sans-serif`**（MCSS @54706，字面量，不被 JS 覆写）
→ **正文/UI 名义与实测均为 Inter**（`Inter.ttf` 存在，`@font-face` 见下）。
> 上游源码同一处以 `font-[440]` + `text-[13px]` 施加到 `document.body`：
> `SRC: packages__app__src__app.tsx` L49–53（`BodyTypography()`）。
> 注意 HTML 里初始 `body` 类名是 `text-12-regular`（`HTML index.html`），由 JS 在 `data-new-layout` 时替换为 13px/440。

**(b) v2 组件显式 family**：`var(--v2-font-family-sans, "Inter", sans-serif)` → `"Inter", sans-serif`
（MCSS @221451 `task-tool-title`、@431379 `attachment-card-v2-title/subtitle`、@263652 `button-v2`）。

**(c) 通用等宽（代码块 / 诊断 / 内联 code）**：
```css
/* MCSS @225051 markdown 根：正文用 sans */
[data-component=markdown]{ ... font-family:var(--font-family-sans); font-size:var(--font-size-base); line-height:160% }
/* MCSS @118059 / @118431 / @241999 / @247284 / @229460：mono 场景 */
font-family: var(--font-family-mono); font-feature-settings: var(--font-family-mono--font-feature-settings)
```
`--font-family-mono` 运行时 = 系统 mono 栈（同上，无 IBM Plex Mono）。

**(d) 终端 (terminal panel)** — **默认值**（`ar.appearance.terminal = ""`）：
```js
// MJS @970769
function n$(e){ return lD(e, qbe) }        // qbe = jbe
// MJS @2602884 传给终端引擎：
new ge.Terminal({ ..., fontSize:14, fontFamily: n$(r.appearance.terminalFont()) })
```
→ `"JetBrainsMono Nerd Font Mono", ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace`

**(e) 数学 (KaTeX)**：17 个 `KaTeX_*` family，同基线笔记 §1.11。

**`@font-face` 来源（MCSS 共 22 条，含 20 条 KaTeX；非 KaTeX 仅 2 条）**：
```css
/* MCSS @386637 */
@font-face{font-family:JetBrainsMono Nerd Font Mono;src:url(../assets/JetBrainsMonoNerdFontMono-Regular.woff2)format("woff2");font-weight:400;font-style:normal}
/* MCSS @386797 */
@font-face{font-family:Inter;src:url(../assets/Inter.ttf)format("truetype");font-weight:100 900;font-style:normal}
```
- 字体文件实测存在：`assets/Inter.ttf`、`assets/JetBrainsMonoNerdFontMono-Regular.woff2`。
- **不存在 `IBMPlexMono-*.{woff2,ttf}` 文件，也不存在对应 `@font-face`。**

### 2.3 「440」的特殊性（与字体家族强相关）
上游源码里 IBM Plex Mono 的 face **专为 440 字重**声明：
```css
/* SRC: packages__app__src__index.css L20–26 */
@font-face{ font-family:"IBM Plex Mono"; src:url(".../IBMPlexMono-Text.woff2") format("woff2");
           font-weight:440; font-style:normal; font-display:swap }
```
```ts
// SRC: packages__app__src__settings__model.tsx
export const monoDefault = "IBM Plex Mono"          // L19
export const sansDefault = "Inter"                  // L20
export const terminalDefault = "JetBrainsMono Nerd Font Mono"  // L21
```
```js
// MJS 运行时默认（settings schema）：appearance:{ fontSize:14, mono:"", sans:"", terminal:"" }
// MJS @970769 附近 —— 全部为空串 ⇒ 走内置 fallback 栈（即上面 2.2）
```
→ **本构建发布时把这三条默认（Inter / IBM Plex Mono / JetBrains）从 mono/sans 的 fallback 栈里去掉了**，
只在 `--font-family-text`、`--v2-font-family-sans`（Inter）与终端常量（JetBrains）中保留。
**若 Android 侧对齐"上游设计意图"应把 IBM Plex Mono(440) 视为可选；对齐"本构建实测"则不需要。**

### 2.4 字体栈最终答案表（可直接抄进 Compose）
| 用途 | 最终 family 值 |
|---|---|
| **正文 / UI** | `"Inter", sans-serif`（`--font-family-text` / `--v2-font-family-sans`；可变字重 100–900） |
| `--font-family-sans`（运行时回退） | `ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif`（用户设自定义字体时前缀为用户字体） |
| **通用等宽** | `ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace` |
| **终端** | `"JetBrainsMono Nerd Font Mono", ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace`，`fontSize:14` |
| 数学 | `KaTeX_*`（17 family） |
| **IBM Plex Mono** | **本构建不存在**（无文件、无 `@font-face`、无引用） |

---

## 3. spinner 随机种子 — **定案：确实随机，无固定种子**

### 结论
4×4 点阵 spinner 的 `duration` / `delay` **确为每次随机的无种子随机数**（`Math.random()`），
且**在模块加载时一次性生成**（并非每次渲染重算）。**Android 复刻可自行随机，无需固定 seed。**

### 证据

**(1) 随机生成（MJS @1770868 起，同一条语句内）**
```js
const RMe = new Set([1,2,4,7,8,11,13,14]),    // outer（外圈 8 点）
      FMe = new Set([0,3,12,15]),             // corner（四角 4 点）
      NMe = Array.from({length:16}, (e,t) => ({
        id: t,
        x: t % 4 * 4,                          // 0,4,8,12
        y: Math.floor(t/4) * 4,                // 0,4,8,12
        delay:    Math.random() * 1.5,         // 0 – 1500 ms  ← 随机
        duration: 1 + Math.random() * 1,       // 1.0 – 2.0 s  ← 随机
        outer:  RMe.has(t),
        corner: FMe.has(t),
      }));
```
**没有任何种子**：全文件检索无 `seed` / `mulberry32` / `xorshift` / 自定义 PRNG 与 spinner 的关联；
也无 `Date.now()`/索引派生值参与。

**(2) 参数如何落到 DOM（MJS @1771180 的 `yu` 组件）**
```js
var MMe = P("<svg>"), OMe = P("<svg><rect width=3 height=3 rx=1></svg>");

mn(t, ie(e, { viewBox:"0 0 15 15", "data-component":"spinner", fill:"currentColor" }))
S(t, f(gt, { each: NMe, children: n => {                        // 16 个 rect
  const a = n.corner ? 0 : void 0;                               // 角点 → opacity:0
  const l = n.corner ? void 0
    : `${n.outer ? "pulse-opacity-dim" : "pulse-opacity"} ${n.duration}s ease-in-out infinite`;
  const c = n.corner ? void 0 : "both";
  const u = n.corner ? void 0 : `${n.delay}s`;
  mt(r,"opacity",a); mt(r,"animation",l); mt(r,"animation-fill-mode",c); mt(r,"animation-delay",u);
}}))
```
→ 角点 `{0,3,12,15}`：`opacity:0` 且**不设 animation**；
→ 外圈 `{1,2,4,7,8,11,13,14}`：`pulse-opacity-dim`；内 2×2 `{5,6,9,10}`：`pulse-opacity`；
→ 每点 `animation-duration = <random duration>s`、`animation-delay = <random delay>s`、`fill-mode: both`、`infinite`、`ease-in-out`。

**(3) 关键帧（MCSS @379588）**
```css
@keyframes pulse-opacity      { 0%,to{opacity:.4}    50%{opacity:1}    }
@keyframes pulse-opacity-dim  { 0%,to{opacity:.15}   50%{opacity:.35}  }
@keyframes pulse-scale        { 0%,to{transform:scale(1)} 50%{transform:scale(.666667)} }
```

**(4) 几何 & 尺寸（MCSS）**
- viewBox `0 0 15 15`，每个 `rect width=3 height=3 rx=1`，格距 4。
- 默认容器 `width:18px; aspect-ratio:1`（MCSS @168602）；工具卡/任务卡内统一 `16×16`（@215142、@219307、@243246）。

**(5) 随机发生时机**：`NMe` 是**模块级 `const`**，因此 16 组随机值在**模块首次求值时生成一次**，
此后**同一页面生命周期内所有 spinner 实例共用同一组参数**（除非重新加载页面）。
→ 复刻时若想"更活"，需自行按实例重算；若想严格复刻，应"每进程/每页面一次"。

### 给 Android 的定案
- **可以随机**：`delay = Random.nextFloat()*1500f`(ms)，`duration = 1000 + Random.nextFloat()*1000`(ms)。
- **无需固定 seed**；若要可复现（截图测试/视觉回归），再注入 seed 并**在进程级缓存**这 16 组值，
  以复刻"同一页面内所有 spinner 参数一致"的实际观感。
- `ease-in-out` 对应 `FastOutSlowInEasing` 或 `CubicBezierEasing(0.42f, 0f, 0.58f, 1f)`（CSS `ease-in-out`）。

---

## 4. 字重基线：正文 440 / 强调 530 / 组件 500（含 Compose 映射）

### 结论
- **正文默认 = 440**（精确 440，非 400）：由 `body` 类 `font-[440]` 施加；`.font-\[440\]{--tw-font-weight:440;font-weight:440}`。
- **强调/标题 = 530**（v2 新布局几乎所有"重点文字"）：v2 tokens 里**没有** 530，是**组件/工具类硬编码**。
- **500 = `var(--font-weight-medium)`**（Tailwind `font-medium`），仅剩 **6 处**，属**旧版/边缘组件**。
- **400 = `var(--font-weight-regular)`**，多用于旧版组件与 Tailwind `font-[400]`。
- **600 / 610** 也存在（markdown `h1/h2` = 600；一处大标题 = 610）。

### 4.1 证据（位置 + 值）

**(A) 440 —— 正文默认**
| # | 位置 | CSS / JS 值 |
|---|---|---|
| 1 | MJS @2754650（全局排版副作用） | `classList.toggle("font-[440]", t)` + `toggle("text-[13px]", t)` ⇒ body 13px/440 |
| 2 | MCSS @339515（工具类） | `.font-\[440\]{--tw-font-weight:440;font-weight:440}`，另 `.\!\[font-weight\:440\]{font-weight:440!important}` |
| 3 | MCSS @262932 | `body[data-new-layout] [data-component=user-message]{font-weight:440}` |
| 4 | MCSS @221916 | `body[data-new-layout] [data-component=task-tool-card] [data-slot=basic-tool-tool-subtitle]{ ... font-weight:440; line-height:20px }` |
| 5 | MCSS @184711 / @198585 | 终端页签 / review 页签标题：`font-weight:440; font-size:13px; line-height:16px; font-family:Inter,sans-serif` |
| 6 | MCSS @421816 / @426494 | 菜单项 `[data-slot=menu-v2-item-content]{font-size:13px;font-weight:440}` / `[data-slot=select-v2-value-text]{font-weight:440;font-size:13px}` |

统计（MCSS 全文）：`font-weight:440` **38 处**；其他分包 CSS：`index-GGAIvKVQ.css` 14、`dialog-command-palette-v2` 4、`dialog-edit-project-v2` 2、`row-BjpwzvFU.css` 1。

**(B) 530 —— 强调（v2 的"medium"实为 530）**
| # | 位置 | CSS 值 |
|---|---|---|
| 1 | MCSS @263625 | `[data-component=button-v2]{ font-family:var(--v2-font-family-sans,"Inter",sans-serif); font-variation-settings:"wght" 530,"slnt" 0; font-weight:530 }` |
| 2 | MCSS @221490 | `body[data-new-layout] [data-component=task-tool-card] [data-component=task-tool-title]{ height:20px; font-size:13px; font-weight:530; line-height:20px; letter-spacing:-.04px }` |
| 3 | MCSS @339531 / @339815 | 工具类 `.font-\[530\]{--tw-font-weight:530;font-weight:530}`、`.\[font-weight\:530\]{font-weight:530}` |
| 4 | MCSS @414270 / @434264 / @455846 | hovercard `[data-slot=title]{font-weight:530}`；`[data-slot=dialog-title]{font-weight:530;font-size:15px;line-height:20px;letter-spacing:-.13px}`；`session-review-v2-empty-*{font-weight:530}` |
| 5 | MCSS @483385 / @490442 / @491606 | `settings-v2-provider-name`、`settings-v2-servers-name`、`settings-v2-server-dialog-label`：`font-size:13px;font-weight:530` |

统计：MCSS `font-weight:530` **40 处**；`index-GGAIvKVQ.css` 1、`dialog-command-palette-v2` 2、`dialog-edit-project-v2` 1。
> 并与 `research/03_motion_tokens.md` 笔记一致：`--font-weight-medium: 500`（MCSS 同一 `:root` 块内紧随 `--font-size-x-large` 之后，约 @26591）**不是** v2 重点文字用的值。

**(C) 500 —— 组件级（旧版为主，仅 6 处）**
| # | 位置 | CSS 值 |
|---|---|---|
| 1 | MCSS @103190 | `[data-component=avatar]{ ... font-family:var(--font-mono); font-weight:500 }`（头像首字母） |
| 2 | MCSS @480710 | `.settings-v2-section>summary.settings-v2-section-title{ font-size:13px; font-weight:500; line-height:1 }` |
| 3 | MCSS @446358 / @450669 | `session-review-v2-sidebar-title` / `session-review-v2-toolbar-title`：`font-size:13px; font-weight:500` |
| 4 | MCSS @272555 | `[data-slot=message-nav-title-preview][data-active]{color:var(--text-strong);font-weight:500}`（旧版消息导航） |
| 5 | MCSS @386239 | `[data-component=android-session-context] .android-session-context-title{font-size:14px;font-weight:500}` |
| — | MCSS @339767 | `--font-weight-medium: 500` ⇒ `.font-medium{font-weight:var(--font-weight-medium)}` |

**(D) 400 / 600 / 610（补充）**
- `--font-weight-regular: 400` / `--font-weight-medium: 500`（MCSS 同一 `:root` 块内，紧随 `--font-size-x-large` 之后，约 @26591）；`font-weight:400` **17 处**（多为旧版组件：`[data-component=button]`、`checkbox`、`input`、`session-review` 等）。
- markdown `h1{font-weight:600;font-size:17px;line-height:var(--line-height-large)}`、`h2{font-weight:600;font-size:15px}`（MCSS @225200 区，`font-weight:600` 全 CSS 仅 3 处，第 3 处为 `.settings-v2-servers-row--active .settings-v2-servers-name{font-weight:600}`）；
  Tailwind 另有 `.font-\[610\]{--tw-font-weight:610;font-weight:610}`（MCSS @339599，MJS @1545635 处一处 21px 大标题用）。

### 4.2 Compose `FontWeight` 映射建议
| 语义 | CSS | Compose |
|---|---|---|
| 正文默认 | `440` | `FontWeight(440)`（**需 Inter 可变字体**；`FontWeight.W400` 会偏细） |
| 强调/标题/按钮（v2） | `530` | `FontWeight(530)`；同时 `FontVariation.Settings(FontVariation.weight(530), FontVariation.slant(0f))` |
| Tailwind `font-medium`（旧版） | `500` | `FontWeight.W500` |
| Tailwind `font-regular` | `400` | `FontWeight.W400` |
| markdown h1/h2 | `600` | `FontWeight.W600`（`SemiBold`） |
| 超大标题 | `610` | `FontWeight(610)` |
> **前提**：Android 必须加载 **Inter 可变字体（wght 100–900）**（字体文件：本仓库同目录 `assets/Inter.ttf`），
> 否则 `440` / `530` / `610` 在静态字重下会被吸附到最近的静态字重（400/500/600…），观感偏细。
> 另注意大量 v2 规则同时声明 `font-variation-settings:"wght" 530,"slnt" 0` 与 `font-variation-settings:"slnt" 0`（无 wght）——
> 后者**不会**改变字重，仅关闭斜体轴。

---

## 5. 仍未确证 / 明确不臆造

| 项 | 状态与理由 |
|---|---|
| `blinking-cursor` 的真实用途 | **无法在构建产物内确证**。CSS 有规则、JS 0 引用、DOM 模板无光标节点。**最可能是历史遗留死代码**；不排除由运行时（非本 bundle）动态注入，但**本仓库内无任何证据**。**结论：不实现**。 |
| 上游 `dev` 源码 vs 本 vendored 构建的差异原因 | 已**观测到**差异（源码有 `"Inter"/"IBM Plex Mono"` 与 `IBM Plex Mono @font-face`，构建产物没有），但**未确证**是构建脚本剥离、tag 不同、还是打包裁剪。**未验证**，不作因果断言。 |
| `--v2-font-family-code` | 上游源码 `tokens/theme.css` L137 有定义，**本构建产物 0 命中**。是否被其它 CSS 文件定义：**已全量扫描本仓库 *.css，未发现**。 |
| `packages/session-ui/src/components/markdown-stream.ts` 的具体实现 | 该文件**不在本地 49 份源码副本中**（仅见于 `tree-paths.txt`）。其行为由**构建产物中的 `j0`/`RQ`/`x7e` 反推**（§1 证据 4），**未逐行比对源码**。 |
| `spinner` 的随机值是否每实例重算 | 由 `NMe` 为模块级 `const` 判定为**每模块装载一次**；**未做运行时实验验证**（无浏览器执行环境）。 |
| `Inter.ttf` 是否真为可变字体（wght 100–900） | MCSS 声明 `font-weight:100 900`，**字体二进制内部 axis 未解析验证**。 |
| 25 点进度指示器几何、dialog-v2 进出场等 | 仍按 `research/03_motion_tokens.md` §7 保持不变（本次未涉及）。 |

---

## 6. 一页速查（给复刻实现）

```
流式光标        ：无（不实现）。节奏揭示：每 24ms 推进 2/4/8/≤256 字，
                  在 [\s.,!?;:)\]] 处对齐词边界；落后 >512 字直接补齐。
                  blinking-cursor 的 1s/step-end 方波 = 死代码，不采用。
正文/UI 字体    ："Inter", sans-serif          （--font-family-text = --v2-font-family-sans，字面量）
通用等宽        ：ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas,
                  "Liberation Mono", "Courier New", monospace
终端字体        ："JetBrainsMono Nerd Font Mono", ui-monospace, ...（fontSize 14，bar 光标，cursorBlink:true）
IBM Plex Mono   ：本构建不存在（无文件/无 @font-face/无引用）→ 无需实现
spinner         ：16 点 4×4，rect 3×3 rx=1，格距 4；角点 opacity:0；
                  外圈 pulse-opacity-dim (.15↔.35)、内 2×2 pulse-opacity (.4↔1.0)；
                  duration = 1+rand*1 s，delay = rand*1.5 s（无种子，模块装载时生成一次）
字重            ：正文 440 / 强调 530 / 旧版组件 500 / markdown h1-h2 600 / 一处大标题 610
                  → Compose 必须用 Inter 可变字体 + FontWeight(440/530/610)
```
