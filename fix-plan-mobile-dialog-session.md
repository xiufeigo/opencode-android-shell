# 手机端两 Bug 修复计划（v7，已纳入 deepseek-v4.1-flash 六轮剩余 MUST，可执行版）

> 上游 pin：`frontend/OPENCODE_APP_VERSION` = `@opencode-ai/app v1.18.31`
> 报告：① 手机端对话框点击后弹键盘过程掉帧、对话框闪烁式上升；② 手机端点进 session 默认是上下文界面而非会话界面。
> 本计划只改 Android 分支（`data-android-webview` / `isAndroidWebView()` / `MainActivity`），桌面行为不动。
> v4 对照三轮审查剩余 MUST：M-F4-a 补跨文件共享机制（window 旗标）；M-F4-c 补可读源码定位步骤+可读变量名代码（minified 名仅作证据，不作实现）；M-M5-1 补全路径+三处全覆盖+收紧 filter。无“或/待验证”，无跨文件裸 let。

## 1. 自我检查（已读代码/产物）

### Bug1：对话框 + 键盘掉帧/闪烁上升

已确认链路（vendored 产物 + 壳 + 补丁交叉验证）：

- `app/src/main/assets/opencode-app/index.html`：`viewport ... interactive-widget=resizes-content` + `#root class="... h-dvh"`（`height:100dvh`）。
  补丁 `z-ime-session-layout` 用 `html[data-android-webview] #root {height:100% !important}` 覆盖，但对话框仍用 `100dvh`。
- `MainActivity.padWebViewForCutout()`：`DISPATCH_MODE_CONTINUE_ON_SUBTREE` + `onProgress()` 每帧调 `applyImeAwarePadding()` → `binding.root.updatePadding(bottom=imeBottom)`。
  WebView 每帧 relayout（Chromium 全量 layout，低端机必然掉帧）。`applyImeAwarePadding` 只跳过完全相同的四边，动画中间帧每次都不同，守卫无效。
- `frontend/patches/android-webview-z-tablet-dialog.patch`（手机 `<639px`）：`[data-component="dialog-v2"]:not(settings):not(palette) {align-items:flex-end}` + 三处 `max-height:min(calc(100dvh-16px),calc(100%-16px))`（通用 container、通用 content、`.settings-v2-server-dialog` container；后者即“设置→服务器→添加服务器”必现路径）。
  修正 containing block 表述：`[data-component="dialog-v2"]{position:fixed;inset:0}` 的 containing block 是 layout viewport，不是 `#root`。`%` 可靠的真实链是：`MainActivity.applyImeAwarePadding` 缩 `FrameLayout(root)` → `WebView match_parent` 收缩 → `interactive-widget=resizes-content` 使 Chromium layout viewport 收缩 → `fixed inset:0+%` 收敛。settled 态可靠；动画期间 viewport 比原生 `onProgress` 滞后约一帧（短暂偏大一帧），仍优于双源每帧打架。无需 `dvh` 回退。
  `flex-end` 让对话框贴底，键盘每长高 1px 对话框就上移 1px → “上升”；`min(dvh,%)` 同时订阅动态视口（dvh，经 `resizes-content` 逐帧变）和布局高度（`%`，随 root padding 变），每帧两次几何源打架 + 内容 `max-height` 重算 → 重排 + “闪烁”。
  进场动画定论（M-F1-1）：产物 CSS 实测 `overlayShow{opacity}` 仅透明度、`contentShow{scale(.98)}` 归 `[data-component="dialog"]`、`fadeUp translateY` 归 `.fade-up-text`，`[data-component="dialog-v2"]` 无位移进场。**删除 blanket `animation:none` 条，不引入任何动画改动。** 四重中的“进场动画”项关闭（证伪），剩余三重（原生每帧+F1 单源+F2 单次）即全量。
- `MainActivity.IME_SCROLL_JS`：`focusin` 双 tick（60ms + 320ms）`scrollIntoView` + `visualViewport.resize` 去抖 200ms，且遮挡判断用 `visualViewport.height`（动画中间值，不稳定）。
  60ms tick 在键盘半开时按中间视口误判“被遮挡”滚一次，320ms/去抖 settled 后再滚一次 → 两次跳动。原生 padding 已经在移动对话框，JS 再 `scrollIntoView` 就是双驱动。

 结论：是 **“每帧原生 relayout + 双视口 CSS + 双 tick JS 滚动”三重叠加**（进场动画已证伪删除）。只修一处仍会闪/掉帧，需三处同修但每处最小。

### Bug2：进 session 默认上下文而非会话

已确认（minified `index-CKBO9Mty.js` + 补丁源码）：

- `H7=()=>({messageId:void 0,mobileTab:"session"})` 初始是对的（会话）。
- 上游 `contextOpen = active==="context" || all.includes("context")`，`activeTab = ... || (i()?"context":...)`（`i`=contextOpen）。`all` 残留 `"context"` 即劫持 `activeTab`。
- `sessionTabs` 按 `sessionKey` 持久在 `layout.v6`；`reviewPanel.opened` 按 server scope 全局持久。点过一次头部圆环（`EGe(): reviewPanel.open(...); tabs.open("context"); tabs.setActive("context")`，见 `session-context-usage`）后，同 session 重进即恢复 `active="context"+opened=true`。
- 补丁 `z-ime-session-layout` 新增同步 effect：`on([tabs().active(), reviewPanel.opened()]) => if active==="context"&&opened && (prev==null||prevActive!=="context") setStore mobileTab="context"`。
  首挂载（`prev==null`）即满足，`mobileTab` 从初始 `"session"` 被立刻覆盖为 `"context"` → 默认页变上下文。这就是本 bug 主因。
- 次因：`At` store（`mobileTab`）位于按 `scope+directory` keyed 的组件内（`fet: key=${scope} ${directory}`），同目录切 session 不重建，`mobileTab="context"` 跨 session 残留；`handoff.tabs()` 还会把旧 `all/active（含 context）` 搬到新 session（`_().setAll(xn), _().setActive(...)`）。
- 头部圆环 `_` 本身不写 `mobileTab`，完全依赖上述 effect 同步，所以不能简单删 effect，否则圆环在手机上点了也不会切到上下文 Tab。

结论：主因是 **effect 首挂载即同步持久态**，次因是 **session 切换不重置 mobileTab + handoff 搬运 context**。修法必须保留“用户本次点击→切上下文”，只切断“挂载恢复→自动切上下文”。

## 2. 修复方案（最小、Android-only、可验证）

### F1：对话框 CSS 单一几何源（唯一 owner：`android-webview-z-tablet-dialog.patch`，M-F1-2）

- 归属定死，避免双补丁改同一段导致 `git apply` context 漂移：
  - `android-webview-z-tablet-dialog.patch` 拥有全部手机对话框 CSS（含三处 `max-height`）。
  - `android-webview-z-ime-session-layout.patch` 的 `android-webview-home.css` 段中与对话框 `max-height` 重叠的 hunk 全部回退删除，只保留 `#root/app-shell/session-panel-row/terminal-dock/context-tab` 部分。`sync-opencode-app.sh` 按 alpha 先 apply `...-context` 再 `...-layout` 再 `...-tablet-dialog`，最终 CSS 以 tablet-dialog 为准，无冲突。
- 三处全改（M-F1-1/M-F1-2）：
  1. `z-tablet-dialog.patch:80` 手机通用 `dialog-container`：`max-height: min(calc(100dvh - 16px), calc(100% - 16px))` → `max-height: calc(100% - 16px)`；
  2. `z-tablet-dialog.patch:88` 手机通用 `dialog-content`：同上；
  3. `z-tablet-dialog.patch:93-94` `.settings-v2-server-dialog dialog-container`（添加服务器必现路径）：同上。
  桌面/平板/Settings 居中（`100vh-92px/600px/980px`）/palette（`100vh-96px/margin-top`）不动。
- 无动画改动（M-F1-1 定论删除）。
- 验证：`TabletDialogLayoutTest` / `ImeInsetPaddingTest.androidDialogCssCapsHeightForKeyboard` 改为选择器级断言：手机普通+server-dialog 段 `contains("calc(100% - 16px)") && !contains("min(100dvh")`（以该选择器块为单位切分断言），settings/palette 保留原断言。

### F2：JS 滚动 trailing settled 单次（壳，`MainActivity.IME_SCROLL_JS`，M-F2-1/2 精确伪代码）

- 定死单 timer 交互（同一 `settleTimer`，trailing debounce，无双驱动）：
```js
var SETTLE_MS = 250, FALLBACK_MS = 350, MAX_RETRY = 3;
var settleTimer = 0, fallbackTimer = 0, pendingScroll = false, retry = 0, lastH = 0;
function layoutH(){ return document.documentElement.clientHeight || window.innerHeight; }
function arm(){ // focusin 与 visualViewport.resize 唯一入口
  pendingScroll = true; retry = 0;
  if (settleTimer) clearTimeout(settleTimer);
  if (fallbackTimer) clearTimeout(fallbackTimer);
  settleTimer = setTimeout(settled, SETTLE_MS);      // vv 每次到来都重置（trailing）
  fallbackTimer = setTimeout(settled, FALLBACK_MS);  // 无 vv 事件 OEM 兜底；vv 先到则 settled 内清掉它
}
function settled(){
  if (!pendingScroll) return;
  if (settleTimer){ clearTimeout(settleTimer); settleTimer = 0; }
  if (fallbackTimer){ clearTimeout(fallbackTimer); fallbackTimer = 0; }
  var h = layoutH();
  if (h !== lastH && retry < MAX_RETRY){ // 双帧等高确认：不等则再等一轮，上界 3 次
    lastH = h; retry++;
    settleTimer = setTimeout(settled, SETTLE_MS);
    return;
  }
  pendingScroll = false; retry = 0; lastH = h;
  scrollFocused(); // 唯一 scroll 调用点，内含 settled 后遮挡判断（rect.bottom <= layoutH-8 则 no-op）
}
document.addEventListener('focusin', arm, true);
if (window.visualViewport) visualViewport.addEventListener('resize', arm);
```
  - 删除现状 `focusin 60ms+320ms` 双 `setTimeout` 与 `if(vvTimer) return` throttle（`MainActivity.kt:608-657`）。
  - 慢/分段动画（>400ms）：`h!==lastH` 则自动再等一轮，最多 `MAX_RETRY=3`（250ms×3=750ms 上界），不依赖固定 350ms 命中 settled；350ms 仅是“无 vv 事件”兜底，有 vv 时必被 `settled` 提前清掉，不存在自矛盾。
- `fillRoot()` 保留，`IME_RESTORE_JS` 保留。不新增监听，不改桌面。

### F3：原生阈值仅限 onProgress，onEnd/onApply 强制精确（壳，`MainActivity`，M-F3-1/2）

- 定死签名与三调用点（无“或”）：
```kotlin
private fun applyImeAwarePadding(insets: WindowInsetsCompat, force: Boolean = false)
```
  - `setOnApplyWindowInsetsListener(binding.root)`（`MainActivity.kt:160-163`）→ `applyImeAwarePadding(insets, force=true)`（精确比较，无阈值）。
  - `WindowInsetsAnimationCompat.Callback.onProgress`（`:169-175`）→ `applyImeAwarePadding(insets, force=false)`（阈值路径）。
  - `Callback.onEnd`（`:177-183`）→ `applyImeAwarePadding(ViewCompat.getRootWindowInsets(binding.root) ?: insets, force=true)` + 原有 `requestApplyInsets` 顺序不变（先强制精确再 `handleImeVisibility`）。
  - 阈值体：`if (!force && left/top/right不变 && abs(newBottom-lastBottom)<=2) return`。
- 正名作用（M-F3-2）：2px 阈值只去 1px 级抖动，不治每帧 relayout。键盘典型步长 10–20px/帧，动画期间仍每帧 `updatePadding`；主降帧靠 F1 单源（消双源重算）+ F2 单次滚动（消 JS 双驱动），阈值仅把 `logcat updatePadding` 风暴中的 1px 抖动帧合并。文档不再称“掉帧主因直接下降”，改为“抖动帧合并，余量由 F1/F2 消化；`logcat` 稀疏率实测见 §4-1”。2px ≈ 0.5–1dp（xhdpi 2px=1dp，mdpi 2px=2dp），对话框底边最大 2px 悬空/重叠，不可感知（手指 48dp 触点量级）。
- `imeAwareBottomPadding` 语义不动。不做 `translationY` 重构。

### F4：进 session 默认会话（补丁，`z-ime-session-layout` 内 `session.tsx` + `AndroidBackJs.kt`，M-F4-a/b/c/d）

- 命名/谓词统一（M-F4-b）：全用 `params.id`（补丁 `z-ime-session-layout.patch:234,243,251,271` 一致，不用 `w.id` 别名）；Android-only 谓词全用 `isAndroidWebView()`（即 `document.documentElement.hasAttribute("data-android-webview")`，与补丁 `164-166` 同定义），不用 `!isDesktop()`（后者在平板横屏 `isDesktop=true` 时会误关重置）。
- (a) 手势门 + prev 守卫（二轮 M-F4-a，四轮 M-F4-a 双文件各贴一份，无跨文件裸引用）：
```tsx
// A份+B份同文各贴一份（同 key 操作同一 window 槽，无 import；落盘归属见 §3）：
// A份位置：packages/app/src/pages/session.tsx 顶层（isAndroidWebView 164-166 旁），归属 z-ime-session-layout.patch 修订；
// B份位置：packages/app/src/components/session-context-usage.tsx 顶层（openContext 定义旁），归属 z-ime-session-context.patch 修订（该补丁已拥有此文件 8,17 两处 data-action，B份同文件同补丁，无新文件）：
const ANDROID_CTX_TAP_KEY = "__ocAndroidCtxTap" as const;
function requestAndroidContext(){ try { (window as any)[ANDROID_CTX_TAP_KEY] = true; } catch {} }
function consumeAndroidContext(){ try { const v = (window as any)[ANDROID_CTX_TAP_KEY] === true; (window as any)[ANDROID_CTX_TAP_KEY] = false; return v; } catch { return false; } }
function clearAndroidContext(){ try { (window as any)[ANDROID_CTX_TAP_KEY] = false; } catch {} }
// 手机上下文 Tab 点击（z-ime-session-layout.patch:207-226 onClick 内首行，A份函数）：requestAndroidContext(); setStore("mobileTab","context"); ...
// 头部圆环置门（执行者必跑）：grep -n "openContext\|session-open-context\|EGe" "$SRC/packages/app/src/components/session-context-usage.tsx" → R1（openContext/_ 函数定义行）、R2a/R2b（两处 onClick={openContext} JSX，z-ime-session-context.patch:8-9,16-17）；两按钮共用同一 handler，不逐按钮改 JSX（JSX prop 前加 if 语法无效），只在 R1 函数体首行加（B份函数）：
//   const openContext = () => { if (typeof document !== "undefined" && document.documentElement.hasAttribute("data-android-webview")) requestAndroidContext(); /* 原 EGe/open 逻辑 */ }
createEffect(on(() => [params.id, tabs().active(), view().reviewPanel.opened()] as const,
  ([id, active, opened], prev) => {
    if (!isAndroidWebView() || !params.id) return;
    if (id !== (prev?.[0] as string|undefined)) return; // id 变化本轮只重置（见 b），不同步
    if (active !== "context" || !opened) return;
    if (!consumeAndroidContext()) return; // 水合 session→context / opened false→true 非手势，一律不切；手势则消费一次
    if (prev && prev[1] === "context" && (prev[2] as boolean)) { clearAndroidContext(); return; } // 已在上下文重复 fire 防抖
    setStore("mobileTab", "context");
  }));
```
  真值表（Android-only，`params.id` 不变时）：
  | active | opened | prev | 手势门 | mobileTab |
  |---|---|---|---|---|
  | context | true | null（首挂载水合） | false | 保持 session（修本 bug 主因） |
  | context | true | [id,session,false]（水合 session→context） | false | 保持 session（补异步水合漏） |
  | context | true | [id,context,false]（opened 后到，ring EGe 已置门） | true | →context（ring 可用，不误拦） |
  | context | true | [id,session,*]（Tab 点击 session→context） | true | →context（Tab 可用） |
  | context | true | [id,context,true]（重复 fire） | * | 保持（防抖） |
  | 非context/!opened | * | * | * | 不动 |
- (b) 重置 effect 建在同步 effect 之前（M-F4-b 定序，无“或”）：
```tsx
createEffect(on(() => params.id, (id, prevId) => {
  if (!isAndroidWebView()) return;
  if (id === prevId) return;
  clearAndroidContext();
  setStore("mobileTab", "session");
}));
```
  置于 (a) 之前创建，利用 Solid effect 按创建顺序执行，保证 `id` 变化轮 (a) 见 `id!==prevId` 直接 return，只有重置生效，消除 A→B 竞态。`At` 位于 `scope+directory` keyed 组件（`fet: key=${scope} ${directory}`）同目录切不重建，此条必备。
- (c) handoff 过滤（M-F4-c v5：可执行定位+签名核对+无fallback置空）：
  - 定位步骤（执行者在 OPENCODE_SRC 上必跑，记录数字行号再改）：
```bash
SRC="${OPENCODE_SRC:-${TMPDIR:-/tmp}/opencode-app-src}" # 与 sync-opencode-app.sh 同源，可执行
grep -n "handoff" "$SRC/packages/app/src/pages/session.tsx"          # 行 N1：handoff effect（st(()=>params.id,(id,prev)=>{if(!id||prev) return; ...},{defer:true})）
grep -n "setAll\|setActive" "$SRC/packages/app/src/pages/session.tsx" # 行 N2/N3：tabs().setAll/setActive 两行（vendored 证据：index-CKBO9Mty.js `ne(st(()=>w.id,(X,Te)=>{if(!X||Te)return; ...` 段，混淆名仅证据）
grep -n "60.*000\|handoff.clearTabs\|scope.*id.*dir" "$SRC/packages/app/src/pages/session.tsx" # 行 N4：60s过期/scope-id-dir失配 early-return，五分支不动
grep -rn "setActive" "$SRC/packages/app/src/context/tabs.tsx" "$SRC/packages/app/src/context/layout.tsx" 2>/dev/null | head -20 # 行 N5：精确签名行（二选一，见下；禁裸 setActive.*string）
# N5 裁决：若行含 "| undefined" 或 "?:" 则为可空签名走分支U；若行仅 "(tab: string)" 无 undefined 则走分支S
grep -n "close" "$SRC/packages/app/src/context/tabs.tsx" 2>/dev/null | head -20 # 行 N6：close 清 active 语义行（要求命中 active 清除逻辑，记录行号；若无则改用 setAll([]) 后不依赖 close，见分支S）
```
  - Android-only 实现（可读变量名，直接粘贴，N2/N3 处分支）：
```tsx
// N2/N3 原两行：
//   tabs().setAll(mappedAll); tabs().setActive(mappedActive);
if (isAndroidWebView()) {
  const filteredAll = mappedAll.filter((t: string) => t !== "context");
  const fallback = filteredAll.find((t: string) => t !== "review" && t !== "context");
  if (fallback === undefined) {
    tabs().setAll([]); // 连 review 一起丢
    try { tabs().close("context"); } catch {} // N6 行证：close 移除对应 tab 并在 active 相等时清 active
    try { tabs().close("review"); } catch {} // 同理
    // 分支U（N5 可空签名）：tabs().setActive(undefined); // 类型合法，显式 empty
    // 分支S（N5 string-only）：不调 setActive；empty 等价态由 all=[]+close 双清达成，不声称“保持 undefined 类型”（修类型矛盾）
  } else {
    tabs().setAll(filteredAll); // 例：["context","review","file:///a"] → ["review","file:///a"]
    tabs().setActive(mappedActive && mappedActive !== "context" && filteredAll.includes(mappedActive) ? mappedActive : fallback);
  }
} else {
  tabs().setAll(mappedAll); tabs().setActive(mappedActive);
}
```
  （`mappedAll/mappedActive` 指该 effect 内经 `U(Je.all)/Z(Je.active)` 归一化后的变量，执行者以 N1–N3 实测名为准替换，保持语义同上；禁止直接粘 minified `Je/xn/fr`。）
  - early-return 五分支不动（`!handoffTabs / all空&&active空 / 目标已有all/active / 60s过期 / scope-id-dir失配`，N4 核对）。
  - 不绕回（N6 行证）：本分支 fallback undefined 时已 `setAll([])+close(context)+close(review)`，`all=[]` 且 N6 证明 close 清掉相等 active；`activeTab` 公式 `h || (i()?"context":t()&&n()?"review":"empty")` 中 `i()` 双 false、`h` 无、`t()&&n()` false → `empty`。`setAll(["review"])+active残留` 中间态不存在（已置空+双 close）。
  - 归因正名：A→B 切旧 session 不走此分支（`prev` 真值 return），主路径 (a)(b)；(c) 只保新建 session。
- (d) Back 显式分支（M-F4-d：给定代码+新断言，旧 `session-context-panel` 已删）：
```kotlin
// AndroidBackJs.kt: overlayOpen() 选择器删除 "[data-component=\"android-session-context\"]"，改为旧 overlay 精确槽（已删则永假，无害保留防回退）：
//   '[data-slot="session-context-panel"]'  // 旧 z-ime-session-context.patch:67-72 overlay，已在 z-ime-session-layout.patch:288-314 删除
// 新增手机上下文 Tab 显式分支（overlayOpen 之后、buttons 之前）：
var tabCtx = document.querySelector('[data-slot="session-mobile-context"]'); // z-ime-session-layout.patch:234-242 relative 新 Tab
if (tabCtx && tabCtx.getBoundingClientRect().width > 0) {
  var back = document.querySelector('[data-action="session-mobile-tab-session"]'); // z-ime-session-layout.patch:183-184 会话 Tab
  if (back) { back.click(); return 'nav'; } // 返回会话，不依赖 fireEscape；MainActivity handleAndroidBack NAV 分支已是 Unit（不 finish）
  return 'nav';
}
```
  语义：新 Tab 常驻挂载时旧 `[android-session-context]` 恒命中 `overlay→Unit` 死等 Escape 竞态；改后 Back 点会话 Tab 必回会话。旧 `session-context-panel` 匹配空（防旧包回退），`[role="dialog"]` 残留无害保留。
  测试：`SessionContextRingTest.androidBackTreatsContextOverlayAsDismissible` 改断言 `INSTALL contains session-mobile-context + session-mobile-tab-session` 且 `!contains [data-component="android-session-context"]` 作为 overlay 选择器；`AndroidBackJsTest` 同步；新增 `SessionDefaultTabTest`（见 §4）。

### 非目标（明确不做）

- 不改 `adjustResize` / `setDecorFitsSystemWindows(false)` / `interactive-widget`。
- 不改 Settings/ palette 居中（1.3.18 已定）。
- 不把 `reviewPanel.opened/sessionTabs` 改为按 session 隔离（改动大，`fix-plan-android-session-attachment.md §6-Q3` 已否决渲染层忽略之外的重隔离）。
- 不动桌面 `mobileTab:"session"|"changes"` 类型之外的上游默认。

## 3. 改动面（M-M5-1 精确指纹，已定“精确指纹”，不走强制重打）

- `app/src/main/java/com/xiufeigo/opencodeshell/MainActivity.kt`：`IME_SCROLL_JS` 按 §F2 伪代码逐行替换（`SETTLE_MS=250/FALLBACK_MS=350/MAX_RETRY=3/pendingScroll/arm/settled`）+ `applyImeAwarePadding(insets,force)` 按 §F3 三调用点改。约 30 行。
- `app/src/main/java/com/xiufeigo/opencodeshell/AndroidBackJs.kt`：`overlayOpen()` 删除 `[data-component="android-session-context"]` 改 `[data-slot="session-context-panel"]` + 新增 §F4(d) `session-mobile-context→click session-mobile-tab-session→return 'nav'` 分支。约 12 行。
- `frontend/patches/android-webview-z-tablet-dialog.patch`（唯一 owner）：三处 `max-height` 单源（§F1 1/2/3）。`z-ime-session-layout.patch` 删除重叠对话框 CSS hunk。
- `frontend/patches/android-webview-z-ime-session-layout.patch`（`session.tsx` 段）：§F4(a)A份+三元 effect、(b) 重置前置、(c) handoff 分支。约 30 行。
- `frontend/patches/android-webview-z-ime-session-context.patch` 修订（`session-context-usage.tsx` 段）：§F4(a)B份 4 行 + R1 体首行置门。约 8 行。
- `scripts/sync-opencode-app.sh` 精确指纹（全路径+三处全覆盖+收紧，M-M5-1 v5 落盘形态）：
```bash
# 插入位：sync-opencode-app.sh apply_android_embed_patch 内，PATCH_DIR 定义后（:11-12）新增 CSS/TSX/USAGE 三变量；替换旧 :87（*z-session-context*，实测不匹配 z-ime-session-context，见下）与 :99-106 两 if
CSS="$SRC/packages/app/src/pages/home/android-webview-home.css"
TSX="$SRC/packages/app/src/pages/session.tsx"
USAGE="$SRC/packages/app/src/components/session-context-usage.tsx"
if [[ "$base" == *z-tablet-dialog* ]]; then
  if grep -q 'max-height: calc(100% - 16px)' "$CSS" \
    && [ "$(grep -c 'max-height: calc(100% - 16px)' "$CSS")" -ge 3 ] \
    && ! grep -B3 -A5 'dialog-v2.*:not(\[data-variant' "$CSS" | grep -q '100dvh' \
    && ! grep -B5 -A5 'settings-v2-server-dialog' "$CSS" | grep -q '100dvh'; then
    echo "Already applied $base"; continue
  fi
fi
if [[ "$base" == *z-ime-session-layout* ]]; then
  if grep -q '__ocAndroidCtxTap' "$TSX" \
    && grep -q 'if (!consumeAndroidContext()) return' "$TSX" \
    && grep -q 'filter((t: string) => t !== "context")' "$TSX" \
    && grep -q 'clearAndroidContext()' "$TSX"; then
    echo "Already applied $base"; continue
  fi
fi
if [[ "$base" == *z-ime-session-context* ]]; then
  if grep -q '__ocAndroidCtxTap' "$USAGE" && grep -q 'requestAndroidContext()' "$USAGE"; then
    echo "Already applied $base"; continue
  fi
fi
# 未命中才 git apply；命中则 Already applied。壳 Back（AndroidBackJs.kt）指纹不进 sync（类别错），放单测。
# 注1：旧 'min(100dvh' 恒真空转已修为 '100dvh'（实码 min(calc(100dvh）。
# 注2：旧 :87 '*z-session-context*' 不匹配实名 'android-webview-z-ime-session-context.patch'（'z-ime-session' 阻断 contiguous 'z-session'），必须用 '*z-ime-session-context*'（本计划已用）。
```
- 单测：更新 `ImeInsetPaddingTest/TabletDialogLayoutTest/ImeSessionLayoutTest/SessionContextRingTest/AndroidBackJsTest` + 新增 `SessionDefaultTabTest`（§4）。vendored `assets/` 由脚本重打，不手改。

## 4. 自测 / 验收（M-TEST-1 可复执行版）

- 单测（多 --tests 分写，禁止空格多 pattern 单 flag）：
```bash
./gradlew :app:testDebugUnitTest --tests "*ImeInsetPaddingTest*" --tests "*TabletDialogLayoutTest*" --tests "*ImeSessionLayoutTest*" --tests "*SessionContextRingTest*" --tests "*AndroidBackJsTest*" --tests "*SessionDefaultTabTest*"
```
  `SessionDefaultTabTest` 新增断言：补丁含 `androidCtxTap`+`if (!androidCtxTap) return`+`setStore("mobileTab", "session")`+`filter.*context` 且 Back 含 `session-mobile-context`/`session-mobile-tab-session` 且不以 `[data-component="android-session-context"]` 作 overlay 选择器；vendored CSS 手机普通+server-dialog 块含 `calc(100% - 16px)` 且不含 `min(100dvh`。
- 重打幂等双 case：`OPENCODE_REF=v1.18.31 ./scripts/sync-opencode-app.sh`（全新 clone 零失败）+ `OPENCODE_SRC=<已打目录> OPENCODE_REF=v1.18.31 ./scripts/sync-opencode-app.sh`（复用已打目录，精确指纹命中 `Already applied`，无 `apply --check` 失败）；`vite build` 产物含 `session-mobile-tab-context`+`androidCtxTap`，CSS 同上。
- 真机（手机竖屏 <639px，必现路径）：
  1. 设置→服务器→添加服务器→点 URL/用户名输入框：键盘 0–400ms 内对话框一次跟随、无二次跳、无闪烁；`adb logcat -s OpenCodeShell` 无每帧 `updatePadding` 风暴（阈值后稀疏，尾帧精确校准无 2px 悬空）。
  2. 进任意旧 session（曾开过上下文）：默认 Tab 高亮会话，上下文需手动点才开；同目录切新/旧 session 同样默认会话；点头部圆环仍能进上下文，返回会话后重进仍是会话；Back 在上下文 Tab 回会话（不死等 Escape）。
  3. 平板横屏/桌面宽度：Settings 仍 980px 居中，palette 仍顶部居中（回归 1.3.18）。
- 回退：revert 本计划处即回现状；持久 `layout.v6` 未删数据。

## 5. 请 deepseek-v4.1-flash 复审（v7 待裁决）

1. M-M5-1 B份可执行（非注释）+ 正确基名 '*z-ime-session-context*'（旧 '*z-session-context*' 不匹配实名）+ 插入位（变量+替换 :87 与 :99-106）是否闭环？
2. M-F4-c S分支去“保持 undefined 类型”矛盾（改 empty 等价态）+ N6 close 行证是否闭环？
3. M-F4-a 已闭环有无退化？其余 PASS 有无退化？
