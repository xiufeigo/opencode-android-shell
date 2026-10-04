# IME_OCCLUSION_FIX —— IME1：composer 被键盘完全遮挡的复现、根因与修复

- **执行日期**：2026-10-04（硬期限 2026-10-07 之前 ✅）
- **唯一设备**：`emulator-5824`（`OC_Narrow_Test`，Android 15 / API 35，WebView 124.0.6367.219）
- **被测物**：`webapp` 模块 → `com.xiufeigo.opencodeshell.web.debug` / `WebHostActivity`
- **官方 UI**：经 `LoopbackProxy` 代理 `http://127.0.0.1:4095`（后端由 Lead 托管，本轮**只做 `adb reverse`，0 次启停**）
- **改动范围**：`webapp/**`（注入层 + 一个纯 Kotlin 几何件 + 一个测试类 + build 输入声明）—— 未碰 `app/**`、`_upstream/**`、`.github/**`

> ## ✅ 一句话结论
> **修好了，两侧都归零。** 复现到的重叠像素量是 **60 CSS px（composer 全高，整条被吞）**；
> 根因判定为 **「宿主给的 IME inset 是正确的，但页面完全没用它」**——
> 宿主的 `adjustResize` 因 `setDecorFitsSystemWindows(false)` 边到边而失效（这是前置条件），
> 但它仍把**实测正确**的 `--oc-ime-bottom=336.38px` 交到了页面上（336.38 × 2.625 = **883**，
> 与 `type=ime frame` 高度**逐像素相等**），是上游 SPA 完全不消费它。
> 修法：在宿主注入层 `oc-host.js` 里给 composer 容器垫 `--oc-ime-bottom`。
> **手机 60 → 0，平板 60 → 0**；绊线**已真跑验证可反向变红**；回归 **16 用例 / 0 失败**（原 8，只增不减）。

---

## 1. 结论速查表

| 项 | 手机 412×915 @420 | 平板 1366×768 @160 |
|---|---|---|
| IME `mInputShown` 断言 | ✅ `true`（PASS） | ✅ `true`（PASS） |
| `type=ime frame=` 原始行 | `frame=[0,1517][1080,2400]` | `frame=[0,342][1366,768]` |
| 宿主 `--oc-ime-bottom` | `336.38px`（×2.625 = **883** = frame 高） | `426.00px`（×1 = **426** = frame 高） |
| 宿主 `--oc-safe-bottom` | `24.00px` | （未单独取，机制同） |
| `innerH` 是否随键盘变矮 | ❌ 915 → 915 | ❌ 768 → 768 |
| **改前重叠像素量** | **60** | **60** |
| **改后重叠像素量** | **0** ✅ | **0** ✅ |
| 改后余量（imeTop − composerBottom） | **+63.9** px | **+65** px |

---

## 2. 复现：原始读数

> 纪律（沿用 E6/BR1）：**任何 ime 数值读取之前**先断言 `dumpsys input_method | grep mInputShown`，
> 不为 `true` 就直接终止、不读数值。全部在**同一条原子脚本**里连采，
> 中间不插 `am start`（会把 IME 收掉）。
> 可复现脚本：`research/env/ime_repro_e7.ps1`（`-Tag before|after|final -Geom phone|tablet`）。

### 2.1 改前（手机 412×915 @420，`wm size 1080x2400` / `wm density 420`）

**触碰前**（IME 未起）：

```
{"dpr":2.625,"innerW":412,"innerH":915,"visualViewportH":915,"vvOffsetTop":0,
 "imeVar":"","safeVar":"",
 "edTag":"div","edAria":"Prompt",
 "ed":{"x":21,"y":790,"w":370,"h":60,"top":790,"bottom":850}}
```

**真实手指 tap**（设备 px = CSS × dpr 2.625 ⇒ CSS(206,820) → 设备(541,2152)）：

```
=== TAP device(541,2152) from CSS(21,790) dpr=2.625 ===
```

**断言 ✅**：

```
=== [1] ASSERT mInputShown (读任何 ime 数值前必做) ===
  mInputShown=mInputShown=true
  mServedInputConnection=mServedInputConnection=RemoteInputConnectionImpl{connection=WV.QT@cfb94c7
    mDeactivateRequested=false mServedView=android.webkit.WebView{4d88f9b VFEDH.C.. .F...... 0,0-1080,2400 aid=1073741824}}
  ASSERT PASS
```

> ⇒ IME 确实被服务给了 **WebView 本身**（与 E6 同结论），所以焦点确实落在 composer 上。

**`type=ime` 原始行**（API 35 上 `dumpsys window insets` 只回 67 字节，
真值在 `dumpsys window` 的 `InsetsSource` 段 —— E6 已记录，本轮沿用）：

```
InsetsSource id=3 type=ime frame=[0,1517][1080,2400] visibleFrame=[0,1517][1080,2400] visible=true flags= sideHint=BOTTOM boundingRects=null
InsetsSourceControl: {3 mType=ime mSurfacePosition=Point(0, 63) mInsetsHint=Insets{left=0, top=0, right=0, bottom=883}}
  ime frame top=1517 bottom=2400 height=883px  => CSS imeTop=577.9
```

**IME 弹起后的视口与 composer**：

```
{"dpr":2.625,"innerW":412,"innerH":915,"visualViewportH":915,"vvOffsetTop":0,
 "imeVar":"336.38px","safeVar":"24.00px",
 "ed":{"x":21,"y":790,"w":370,"h":60,"top":790,"bottom":850}}
```

**重叠像素量（改前）**：

```
=== [3] OVERLAP ===
  composer(css) top=790 bottom=850
  ime(css)     top=577.9 bottom=914.3
  innerH before=915 after=915   visualViewportH=915 vvOffsetTop=0
  --oc-ime-bottom = 336.38px   (CSS px)
  OVERLAP_PX = 60     (目标 0)
  composerFullyVisible = False
```

**量化算术**：交集 = `[max(790, 577.9), min(850, 914.3)]` = `[790, 850]`，高 **60 px**。
composer 高 60 ⇒ **整条落在键盘带内，一个像素都没露出**（与 E6 结论一致）。
像素佐证：`research/env/e7_before_phone.png` —— 消息列表下方直接是键盘，**没有 composer**。

> ⚠️ **判据自身的一次修正（诚实记录）**：本轮第一版 `ime_repro_e7.ps1` 把 IME **下**沿
> （`2400/2.625=914.3`）当成了上沿去算交集，于是改后仍报 60。已定位为**脚本判据 bug**，
> 改成 `imeTop = 1517/2.625 = 577.9`（并改用标准区间交集）后改后归零。
> 改前的 60 在两种写法下**都**是 60（composer 全高被吞），结论不受影响。

---

## 3. 根因判定

### 3.1 两个候选，各自的证据

**候选 A：WebView 没跟着 IME 缩（`adjustResize` 缺失 / `WindowInsets` 没消费）**

| 环节 | 读数 / 代码位置 | 判定 |
|---|---|---|
| manifest 声明 | `webapp/src/main/AndroidManifest.xml` → `android:windowSoftInputMode="adjustResize"` | ❌ **不是缺失**，声明在 |
| 实际是否 resize | `innerH` 915 → 915，`visualViewportH=915`，`vvOffsetTop=0` | ❌ **窗口确实没变矮** |
| 为什么没变矮 | `InsetsHost.kt:72` `WindowCompat.setDecorFitsSystemWindows(window, false)` | ✅ **边到边**。API 30+ 边到边窗口不再因 IME 压矮 ⇒ `adjustResize` 的效果被**抵消** |
| 补偿逻辑是否有效 | `InsetsHost.kt:159-170` `applyResize()` | ❌ **死代码**。① 只在 `imeMode == RESIZE` 跑（默认 `VARS`）；② 守卫 `lp.height != -1 && lp.height != -2` 意味着**它永远改不了 MATCH_PARENT 的 WebView**（`android.R.id.content` 的 `layoutParams.height` 就是 -1） |

⇒ 候选 A **成立**，且能定位到具体一行。但它是**前置条件**，不是可以直接消掉的那一环。

**候选 B：页面没把安全区变量用上（CSS 变量没接到 composer）**

| 环节 | 读数 | 判定 |
|---|---|---|
| 宿主有没有把 IME inset 交给页面 | `--oc-ime-bottom = 336.38px` | ✅ **交了，而且完全正确** |
| 正确性交叉验证 | `336.38 CSS px × dpr 2.625 = 883 设备 px`；`type=ime frame` 高 = `2400 − 1517 = 883` | ✅ **逐像素相等** |
| 旁证 | `BRIDGE_ACCEPTANCE.md` §6b/§6c：原生壳侧页面 `--oc-ime-bottom=399.00px` 与 ime frame 高 399 完全一致 | ✅ 同一套通道已被独立验收过 |
| 页面有没有用 | 官方 SPA 不认 `--oc-ime-*`；`env(safe-area-inset-bottom)` 因 WebView 边到边**恒为 0** | ❌ **完全没用** |
| 结果 | `innerH` 915→915、composer `[790,850]` 一个像素没动 | ❌ 静止在视口底部，被键盘盖住 |

⇒ 候选 B **成立，且是直接根因**：宿主已经把**正确且可用**的读数交到页面手上了，
是**消费侧没接**。

### 3.2 判定

> **根因 = 「页面没用安全区变量」。**
> 更完整的因果链是：
> **宿主主动关掉了 `adjustResize` 的效果（边到边）⇒ 布局视口不随键盘变矮；
> ⇒ 按 API 30+ 的契约，IME inset 必须由应用自己消费；
> ⇒ 宿主确实把实测正确的 `--oc-ime-bottom` 交了出来，但上游 SPA 没有任何消费代码
> ⇒ composer 静止在视口底部、被键盘完全盖住。**

判 A/B 的**决定性依据**是那条交叉验证：
**`--oc-ime-bottom` 的值与 `type=ime frame` 的高度逐像素相等（336.38 × 2.625 = 883 = 883）**。
如果宿主给的读数是错的、或者根本没给，那才轮到"宿主没缩"当根因；
读数既然是**对的**，责任就落在**没消费它的页面侧**。
这也决定了修法必须落在页面消费侧 —— 恰好在 `webapp/**` 的注入层里，可改。

---

## 4. 修复

### 4.1 改动 1：注入层给 composer 垫 `--oc-ime-bottom`（主修法）

`webapp/src/main/assets/www/oc-host.js`（`oc-host.js` 由 `WebHostActivity.installFacade()`
用 `WebViewCompat.addDocumentStartJavaScript(..., setOf("*"))` 在**文档开始**注册，
早于页面任何脚本 ⇒ 天然是官网上游 SPA 唯一的注入点）：

```diff
+  // ======================================================================
+  // IME 抬升（composer 被键盘完全遮挡的修法）
+  //
+  // 前提事实（不要改坏）：
+  // - AndroidManifest 已声明 windowSoftInputMode="adjustResize"；
+  // - 但 InsetsHost.init 调了 WindowCompat.setDecorFitsSystemWindows(window, false)
+  //   ⇒ 边到边。API 30+ 上边到边窗口不再因 IME 压矮，adjustResize 的效果被抵消
+  //   （InsetsHost.applyResize 又被 "lp.height != -1" 守卫挡住，对 MATCH_PARENT
+  //     的 WebView 永远不生效）⇒ **布局视口不随键盘变矮**。
+  // - 宿主仍然把**实测正确**的 IME 高度交到 --oc-ime-bottom（IME1 实测
+  //   336.38 CSS px × dpr 2.625 = 883px = type=ime frame 高度，完全一致）。
+  // - 上游 SPA 不认识 --oc-ime-*，也不消费 env(safe-area-inset-bottom)
+  //   （WebView 边到边 ⇒ env() 恒为 0）⇒ composer 停在视口底部、被键盘盖住。
+  //
+  // 所以修法落在**页面消费侧**：由宿主注入层给 composer 容器垫 --oc-ime-bottom。
+  // ======================================================================
+  var IME_STYLE_ID = "oc-ime-lift";
+
+  function installImeLiftStyle() {
+    if (document.getElementById(IME_STYLE_ID)) return;
+    var css = [
+      // 边到边下 env() 恒为 0；用宿主实测的 nav/cutout 补出一个可用的"有效安全区"。
+      ":root{--oc-safe-bottom-eff:max(var(--oc-safe-bottom,0px),env(safe-area-inset-bottom,0px));}",
+      // composer 容器是 <form class="group/composer ...">（上游结构）。
+      // 用 :has() 按**语义**锚定，不绑上游类名/标签名。
+      // WebView 124 = Chrome 124，:has() 已支持。
+      "form:has(textarea),form:has([contenteditable=\"true\"]){",
+      "padding-bottom:var(--oc-ime-bottom,0px);",
+      "box-sizing:border-box;",
+      "transition:padding-bottom .12s ease;",
+      "}",
+      // 兜底：若 composer 不是 <form>，退到"最近 fixed/sticky 祖先"上抬。
+      "[data-oc-ime-lift]{padding-bottom:calc(var(--oc-ime-bottom,0px) + var(--oc-safe-bottom-eff,0px));",
+      "box-sizing:border-box;transition:padding-bottom .12s ease;}",
+    ].join("\n");
+    var st = document.createElement("style");
+    st.id = IME_STYLE_ID;
+    st.textContent = css;
+    (document.head || document.documentElement).appendChild(st);
+  }
+
+  /** 兜底路径：给"聚焦可编辑元素最近的 fixed/sticky 祖先"打标。*/
+  function tagFixedAncestor(ed) {
+    var c = ed && ed.parentElement;
+    while (c && c !== document.body) {
+      var pos = window.getComputedStyle(c).position;
+      if (pos === "fixed" || pos === "sticky") {
+        c.setAttribute("data-oc-ime-lift", "1");
+        return true;
+      }
+      c = c.parentElement;
+    }
+    return false;
+  }
+
+  function onImeFocusIn(e) {
+    var t = e && e.target;
+    if (!t || !t.closest) return;
+    if (!t.matches("textarea, [contenteditable=\"true\"]")) return;
+    // :has() 规则不生效（composer 不是 <form>）时才需要兜底，代价极小。
+    tagFixedAncestor(t);
+  }
```

```diff
   if (document.readyState === "loading") {
     document.addEventListener(
       "DOMContentLoaded",
       function () {
+        installImeLiftStyle();
         if (lastInsets) doApply(lastInsets);
         window.dispatchEvent(new CustomEvent("oc:ready", { detail: { version: 1 } }));
       },
       { once: true }
     );
   } else {
+    installImeLiftStyle();
     window.dispatchEvent(new CustomEvent("oc:ready", { detail: { version: 1 } }));
   }
+  // focusin 委托：composer 是 SPA 动态挂载的，不能只绑一次。
+  document.addEventListener("focusin", onImeFocusIn, true);
 })();
```

**每处为什么安全**

| 改动 | 为什么安全 |
|---|---|
| `padding-bottom: var(--oc-ime-bottom)` 挂在 **`<form>`** 上 | composer 容器实测就是 `<form class="group/composer relative min-h-[96px] w-full overflow-clip ...">`。它是 `flex-col` 容器里的 flex item，加 padding 会把 composer 上推、让消息列表收缩 —— 正是期望行为，**不产生重叠、不产生横向溢出**。实测 `form` 高 `104 → 440`（+336 = ime 高），composer `[790,850] → [454,514]`。 |
| 用 `:has()` 而非上游类名 `.group\/composer` | 不把修法绑死在 Tailwind 生成的类名上。上游改类名不会让修法静默失效。`WebView 124 = Chrome 124`，`:has()` 自 105 起支持。 |
| 抬升量来自 `var(--oc-ime-bottom)` 而非硬编码 | 跟宿主唯一的 inset 通道同源，自动跟随 IME 动画、跟随 `ImeMode`、跟随 `InsetsHost` 的 px→dp 换算。**没有引入第二个真值来源。** |
| `box-sizing: border-box` | 只让 padding 计入既有高度预算，不额外撑大外框。 |
| `transition: padding-bottom .12s ease` | 与 `index.html` 里 `body` 已有的 `transition: padding-bottom 120ms ease` 同量级，键盘动画期间不跳变。 |
| IME 收起时 `--oc-ime-bottom = 0` ⇒ padding 自动归 0 | 实测改前那一轮 `imeVar` 就是 `""`/`0.00px` ⇒ **收起态零残留**，不需要额外的开关逻辑，也就没有"忘关开关导致永久留白"这类 bug。 |
| `installImeLiftStyle()` 幂等（`if (getElementById) return`） | 两条 readyState 路径都调也不会重复插 `<style>`。 |
| `focusin` 用**委托 + capture** | 官方 SPA 的 composer 是动态挂载的，直接绑元素会丢事件。委托在 `document` 上，任何时候挂载都生效。 |
| 兜底 `[data-oc-ime-lift]` 只在**真有 fixed/sticky 祖先**时打标 | 当前 composer 全链路是 `relative`/`static`（实测 chain 8 层全是），兜底**不会触发**，不产生副作用；它只在上游把 composer 改成 fixed 布局时才救场。 |
| 保留 `--oc-safe-bottom-eff = max(--oc-safe-bottom, env(safe-area-inset-bottom))` | 任务书要求"若上游用 `env()` 要保证宿主给它正确值"。WebView 边到边 ⇒ `env()` 恒 0；这里显式给出**有效安全区**（实测 `--oc-safe-bottom=24.00px`），供兜底规则用。**不改写 `env()` 本身**，只提供自有变量。 |
| 未改 `doApply` / `applyInsets` / `--oc-ime-bottom` 通道 | 修法是**在既有机制上加一层消费**，没有替换任何既有通道（有单测 `facade 仍保留 insets 变量注入` 守着）。 |

### 4.2 改动 2：判据抽成纯函数（供绊线用）

新增 `webapp/src/main/java/com/xiufeigo/opencodeshell/webhost/ImeOcclusion.kt`：

```kotlin
object ImeOcclusion {
    data class Rect(val top: Double, val bottom: Double)
    fun imeTopCss(imeBottom: Double, viewportHeight: Double): Double
    fun overlapPx(composer: Rect, imeTopCss: Double): Double
    fun requiredLiftPx(composer: Rect, imeBottom: Double, viewportHeight: Double): Double
    fun isFullyVisible(composer: Rect, imeBottom: Double, viewportHeight: Double): Boolean
}
```

**为什么安全**：纯 Kotlin、无 Android/JS 依赖、不进生产路径（`InsetsHost` / `WebHostActivity` 一行未改），
只为把"重叠面积 = 0"这条判据变成**可在 JVM 单测里跑**的形式。

### 4.3 改动 3：让绊线真的会被触发（**这是本轮踩到的一个真问题**）

`webapp/build.gradle.kts`：

```diff
+// IME1 绊线（IME_OCCLUSION_FIX.md §5）：`ImeOcclusionTest` 的「资产契约层」直接读
+// `src/main/assets/www/oc-host.js`，断言 IME 抬升修法真的在注入层里。
+//
+// **为什么必须显式声明**：单测任务默认不把 `src/main/assets` 当输入。
+// 只改注入层、不改任何 .kt 时，`testDebugUnitTest` 会被判为 UP-TO-DATE **整段跳过**，
+// 绊线于是"假绿"——本轮真跑验证过：撤回修法后 gradle 仍然 BUILD SUCCESSFUL。
+// 声明成输入后，注入层一改，测试必重跑；撤回修法 ⇒ 必红。
+tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
+    inputs.file(layout.projectDirectory.file("src/main/assets/www/oc-host.js"))
+        .withPropertyName("ocHostFacadeAsset")
+        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
+}
```

**为什么安全**：只给测试任务**追加一个只读输入声明**，不改变生产构建、不改变打包产物。

---

## 5. 量化验证（改前 / 改后，同一判据、同一脚本）

判据：**重叠像素量 = `[max(composerTop, imeTop), min(composerBottom, imeBottom)]` 的高度（CSS px）**，
其中 `imeTop = 视口高 − --oc-ime-bottom`、`imeBottom = 视口高`。
与 `ImeOcclusion.overlapPx` 同一口径（脚本与单测不会漂移）。

### 5.1 手机 412×915 @420

| 阶段 | composer (CSS) | IME (CSS) | **重叠** | 余量 | `mInputShown` |
|---|---|---|---|---|---|
| **改前** | `[790, 850]` | `[577.9, 914.3]` | **60** | −336.4 | `true` |
| **改后** | `[454, 514]` | `[577.9, 914.3]` | **0** ✅ | **+63.9** | `true` |
| **改后复跑（final）** | `[454, 514]` | `[577.9, 914.3]` | **0** ✅ | **+63.9** | `true` |

改后原始输出：

```
=== [1] ASSERT mInputShown (读任何 ime 数值前必做) ===
  mInputShown=mInputShown=true
  ASSERT PASS
  InsetsSource id=3 type=ime frame=[0,1517][1080,2400] visibleFrame=[0,1517][1080,2400] visible=true flags= sideHint=BOTTOM boundingRects=null
  ime frame top=1517 bottom=2400 height=883px  => CSS imeTop=577.9
=== [3] OVERLAP ===
  composer(css) top=454 bottom=514
  ime(css)     top=577.9 bottom=914.3
  innerH before=915 after=915   visualViewportH=915 vvOffsetTop=0
  --oc-ime-bottom = 336.38px   (CSS px)
  OVERLAP_PX = 0     (目标 0)
  clearance (imeTop - composerBottom) = 63.9 CSS px
  composerFullyVisible = True
=== SUMMARY after/phone OVERLAP_PX=0 ===
=== SUMMARY final/phone OVERLAP_PX=0 ===
```

**机制在线确认**（CDP 读回真实计算样式）：

```
{"styleInjected":true,
 "cssSnippet":":root{--oc-safe-bottom-eff:max(var(--oc-safe-bottom,0px),env(safe-area-inset-bottom,0px));} form:has(textarea),form:has(",
 "imeVar":"336.38px","safeBottom":"24.00px",
 "formPadBottom":"336.38px",
 "imeMode":"vars"}
```

⇒ `form` 的 `padding-bottom` 实测 **336.38px**，与 `--oc-ime-bottom` **完全相等** ⇒ 抬升链路闭合。

### 5.2 平板 1366×768 @160（同一实例临时切几何，未触碰 `5636`）

| 阶段 | composer (CSS) | IME (CSS) | **重叠** | 余量 | `mInputShown` |
|---|---|---|---|---|---|
| **改前**（E6 读数） | `[643, 703]` | `[342, 768]` | **60** | −361 | `true` |
| **改后** | `[217, 277]` | `[342, 768]` | **0** ✅ | **+65** | `true` |

改后原始输出：

```
=== [1] ASSERT mInputShown (读任何 ime 数值前必做) ===
  mInputShown=mInputShown=true
  ASSERT PASS
  InsetsSource id=3 type=ime frame=[0,342][1366,768] visibleFrame=[0,342][1366,768] visible=true flags= sideHint=BOTTOM boundingRects=null
  ime frame top=342 bottom=768 height=426px  => CSS imeTop=342
=== [3] OVERLAP ===
  composer(css) top=217 bottom=277
  ime(css)     top=342 bottom=768
  --oc-ime-bottom = 426.00px   (CSS px)
  OVERLAP_PX = 0     (目标 0)
  clearance (imeTop - composerBottom) = 65 CSS px
  composerFullyVisible = True
=== SUMMARY after/tablet OVERLAP_PX=0 ===
```

> 平板侧 `--oc-ime-bottom = 426.00px` × dpr 1 = **426** = frame 高 `768−342` ⇒ 与手机侧**同样逐像素自洽**。
> 两侧重叠均从 **60 → 0** ⇒ 与 E6 的判定一致：**这是通用缺陷，不是窄屏回归**；本轮在两侧都修掉了。

### 5.3 切回手机几何后的复核输出

```
$ adb -s emulator-5824 shell wm size 1080x2400
$ adb -s emulator-5824 shell wm density 420
=== [复核] 切回手机几何后的读数 ===
Physical size: 2560x1600
Override size: 1080x2400
Physical density: 320
Override density: 420
WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)
  Display: mDisplayId=0 (organized)
    init=2560x1600 320dpi mMinSizeOfResizeableTaskDp=220 base=1080x2400 420dpi cur=1080x2400 app=1080x2400 rng=1080x1080-2400x2400
    deferred=false mLayoutNeeded=false
system_server pid = 580      （与轮次开始时同 pid，全程零重启）
port fwd = host-16 tcp:4095 tcp:4095
```

### 5.4 截图

| 截图 | 路径 | 字节 | 内容 |
|---|---|---|---|
| 改前，手机，IME 弹起 | `research/env/e7_before_phone.png` | 214567 | 消息列表下方**直接是键盘，composer 不在画面上** |
| **改后，手机，IME 弹起** | **`research/env/e7_after_phone.png`** | **208370** | **composer（"Ask anything…" + 模型选择 + 发送键）完整可见，浮在键盘上方** |
| 改后，平板，IME 弹起 | `research/env/e7_after_tablet.png` | 64742 | 平板几何下 composer 同样完整可见 |
| 改后复跑确认 | `research/env/e7_final_phone.png` | — | 同改后 |

已肉眼确认 `e7_after_phone.png`：**输入框、"MiMo-V2.6-Pro" 模型选择器、"+"、"↑" 发送键全部在画面内**，
位于键盘顶栏之上 —— 修法达成"能打字"这个目标。

---

## 6. 绊线与**反向验证**

### 6.1 判据

> **IME 弹起时，composer 与 IME 区域的重叠面积为 0。**

两层实现：

1. **几何层**（`ImeOcclusion` 纯函数）—— 用 IME1 改前的**真机原始读数**做固定输入，
   既证明"改前 60"这个缺陷，也证明"上抬后精确归零"这个修法的数学前提。
2. **资产契约层**（读 `src/main/assets/www/oc-host.js`）—— 断言修法**真的在**注入层里。
   **这一层才是可反向验证的**。

新增 `webapp/src/test/java/.../host/ImeOcclusionTest.kt`，**8 个用例**：

| # | 用例 | 断言 |
|---|---|---|
| 1 | `ime 上沿等于视口高减 ime 高度` | 手机 578.62 / 平板 342.0 |
| 2 | `ime 收起时上沿贴视口底 不遮挡任何东西` | 重叠 0，`isFullyVisible` true |
| 3 | `改前读数 手机侧重叠等于 composer 全高 60px` | **60**，`isFullyVisible` false |
| 4 | `改前读数 平板侧重叠同样等于 60px 证明非窄屏特有` | **60**，`isFullyVisible` false |
| 5 | `按 requiredLift 上抬后重叠精确归零` | 两侧均 **0.0**（`delta = 0.0`） |
| 6 | `键盘比 composer 矮时 requiredLift 为 0` | 边界：35.0 / 0.0 |
| 7 | **`facadeConsumesImeBottomForComposer`** | 含 `oc-ime-lift` + `var(--oc-ime-bottom` + `:has` 锚定 + 两条 readyState 路径各装一次 |
| 8 | `facade 仍保留 insets 变量注入 不得被修法挤掉` | `--oc-ime-bottom` / `--oc-safe-bottom` / `--oc-window-height` / `applyInsets` 都在 |

### 6.2 反向验证（真跑，原文）

**撤回修法**（把 `oc-host.js` 里 `oc-ime-lift` 那一整段切掉，验证它变成不存在）：

```
=== 撤回状态确认: oc-ime-lift present = False ===
```

**第一次跑 —— 没红，绊线是假的**（真跑发现的问题，如实记录）：

```
> Task :webapp:preBuild UP-TO-DATE
> Task :webapp:preDebugBuild UP-TO-DATE
> Task :webapp:generateDebugBuildConfig UP-TO-DATE
> Task :webapp:preDebugUnitTestBuild UP-TO-DATE
BUILD SUCCESSFUL in 2s
--- REVERT_EXIT=0 ---
```

**根因**：单测任务不把 `src/main/assets` 当输入 ⇒ 只改注入层时 `testDebugUnitTest` 判为
`UP-TO-DATE` **整段跳过**。⇒ 补上 §4.3 的输入声明。

**加上输入声明后、同一份撤回状态再跑 —— 红了**：

```
=== 撤回状态确认: oc-ime-lift present = False ===

> Task :webapp:generateDebugBuildConfig
> Task :webapp:preDebugUnitTestBuild UP-TO-DATE
> Task :webapp:testDebugUnitTest FAILED
ImeOcclusionTest > facadeConsumesImeBottomForComposer FAILED
    java.lang.AssertionError at ImeOcclusionTest.kt:118
16 tests completed, 1 failed
FAILURE: Build failed with an exception.
> There were failing tests. See the report at: file:///C:/sensorsdata/main/program/opencode-android-shell/webapp/build/
reports/tests/testDebugUnitTest/index.html
BUILD FAILED in 10s
--- REVERT_EXIT=1 ---
```

**恢复修法后 —— 绿**：

```
=== restored: oc-ime-lift present = True ===
BUILD SUCCESSFUL in 3s
--- GREEN_EXIT=0 ---
```

⇒ **绊线可反向验证，确认。**

### 6.3 设备侧判据（可复现脚本）

设备侧那条"重叠 = 0"无法在 JVM 里跑（输入只在设备上），因此给的是**可复现脚本**：

```powershell
cd C:\sensorsdata\main\program\opencode-android-shell
# 手机几何（改前/改后同一判据）
pwsh -NoProfile -File research\env\ime_repro_e7.ps1 -Tag after -Geom phone
# 平板几何
pwsh -NoProfile -File research\env\ime_repro_e7.ps1 -Tag after -Geom tablet
```

脚本自带：断言 `mInputShown=true`（不为 true 直接 `exit 1` 且**不读 ime 数值**）、
抓 `type=ime frame=` 原始行、算区间交集、输出 `OVERLAP_PX` 与
`composerFullyVisible`、存截图。`EXIT=0` 且 `OVERLAP_PX = 0` 即通过。
辅助探针：`research/env/probe_ime_fix_e7.js`（读回 `formPadBottom` 与 `--oc-ime-bottom` 做同源核对）。

---

## 7. 回归

```
$ ./gradlew :webapp:testDebugUnitTest
BUILD SUCCESSFUL
```

| suite | tests | failures |
|---|---|---|
| `com.xiufeigo.opencodeshell.web.SetupDialogTouchTargetTest`（既有） | 8 | **0** |
| `com.xiufeigo.opencodeshell.web.host.ImeOcclusionTest`（本轮新增） | 8 | **0** |
| **合计** | **16** | **0** |

原始汇总：

```
suites=2 tests=16 failures=0 errors=0 skipped=0
  com.xiufeigo.opencodeshell.web.host.ImeOcclusionTest: tests=8 failures=0
  com.xiufeigo.opencodeshell.web.SetupDialogTouchTargetTest: tests=8 failures=0
```

* **用例数 8 → 16，只增不减。**
* **未删除、未放宽任何既有断言**：`SetupDialogTouchTargetTest` 一行未动（`git diff` 里根本没有它）。
* `assembleDebug` 同时通过，APK 已在 `5824` 上安装并跑完两侧取证。

---

## 8. 未闭环 / 已知局限

| # | 项 | 状态 | 说明 |
|---|---|---|---|
| 1 | **不是真机** | ⚠️ 声明 | 模拟器上的真实全屏窗口。IME 高度（336dp / 426dp）取决于模拟器 **LatinIME**，真机可能不同。判据与修法是按"宿主实测 inset"设计的，**与具体键盘高度无关**，但具体像素会变。 |
| 2 | `InsetsHost.applyResize()` 仍是死代码 | ⏳ **本轮未修** | `InsetsHost.kt:159-170` 的 `lp.height != -1` 守卫让它对 MATCH_PARENT 的 WebView 永不生效（§3.1 候选 A）。本轮判定"页面消费侧"才是直接根因，故**未动它**。但它是一个**真实存在的隐患**：一旦有人切 `ImeMode.RESIZE`，会以为压矮了其实没有。建议下一轮要么删、要么修。 |
| 3 | 兜底路径 `[data-oc-ime-lift]` 未被真实触发 | ⚠️ 打折 | 当前 composer 全链路是 `relative`/`static`（实测 8 层 chain），`:has()` 分支已足够。兜底只在上游把 composer 改成 fixed 布局时才有用，本轮**无法验证**它。 |
| 4 | 平板侧用的是空会话 | ⚠️ 沿用 E6 限制 | E6 §7.1 记录：1366 几何下打开 4000 行大会话会打死模拟器。本轮平板侧沿用 "New session" 空会话。**composer 是同一个组件**，但所在会话内容不同。 |
| 5 | `:has()` 依赖 WebView ≥105 | ⚠️ 声明 | 本机 124 支持。低于 105 的 WebView 上 `:has()` 规则会整条失效 ⇒ 退化回兜底路径，而兜底只在 fixed 布局下有效。`minSdk = 26` 但实际 WebView 由系统提供，**未测低版本**。 |
| 6 | 收起态未单独取证 | ⚠️ 部分 | 改前那一轮顺带读到 `imeVar=""` / `0.00px`，据此论证"收起时留白自动归零"，但**没有专门跑一次"IME 收起后 composer 回到原位"的完整对照**。 |
| 7 | 与 E6 ①③④ 的关系 | ✅ 已隔离 | 本轮只动 IME 遮挡；侧栏消失、触摸靶 48dp、横向溢出三条**未触碰**。抬升只改 `form` 的 `padding-bottom`，不引入横向溢出。 |

---

## 9. 设备纪律

**本轮下发过任何命令的 serial 只有一个：**

| serial | 归属 | 本轮做过什么 |
|---|---|---|
| **`emulator-5824`** | 自建（`OC_Narrow_Test`，E6 建的） | `install -r -d -g`（web 壳）、`reverse`、`wm size`/`wm density` 覆写（1080x2400@420 ⇄ 1366x768@160）、`am start`、`am force-stop`、`input tap`、`screencap`、`pull`、`rm`（/sdcard/e7.png）、CDP `adb forward`、logcat、`dumpsys`、`pidof`、`pkill` 0 次、`emu kill` **0 次** |

**零触碰清单**（未下发过任何命令）：

```
emulator-5636   （平板机，Lead 管）
emulator-5570 / emulator-5572 / emulator-5730 / emulator-5802   （用户的）
```

* **未起停后端**：全程只做 `adb reverse tcp:4095 tcp:4095`，`127.0.0.1:4095` 由 Lead 托管。
* **未碰 `app/**`、`_upstream/**`、`.github/**`**；改动全部在 `webapp/**` + `research/**`。
* **未做 `adb kill-server` / `reconnect` / `-a`** 等扰动全宿主机连接的操作。
* `system_server` pid 全程 **580**（轮次开始 = 轮次结束）⇒ 实例零崩溃。
* 收尾几何：**手机 1080x2400 @420**（复核输出见 §5.3）。

---

## 10. 本轮产出物

| 类别 | 路径 |
|---|---|
| 报告 | `research/web/IME_OCCLUSION_FIX.md`（本文件） |
| 设备侧可复现判据脚本 | `research/env/ime_repro_e7.ps1` |
| 在线核对探针 | `research/env/probe_ime_fix_e7.js` |
| 运行日志 | `research/env/logs/e7_before_phone.log` / `e7_after_phone.log` / `e7_after_tablet.log` / `e7_final_phone.log` |
| 截图 | `research/env/e7_before_phone.png`、`e7_after_phone.png`、`e7_after_tablet.png`、`e7_final_phone.png` |
| 修法 | `webapp/src/main/assets/www/oc-host.js`（注入层 IME 抬升） |
| 判据 | `webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/ImeOcclusion.kt` |
| 绊线 | `webapp/src/test/java/com/xiufeigo/opencodeshell/web/host/ImeOcclusionTest.kt` |
| 绊线接线 | `webapp/build.gradle.kts`（把注入层声明为测试输入，否则假绿） |
