  // ======================================================================
  // IME 抬升（composer 被键盘完全遮挡的修法）
  //
  // 前提事实（不要改坏）：
  // - AndroidManifest 已声明 windowSoftInputMode="adjustResize"；
  // - 但 InsetsHost.init 调了 WindowCompat.setDecorFitsSystemWindows(window, false)
  //   ⇒ 边到边。API 30+ 上边到边窗口不再因 IME 压矮，adjustResize 的效果被抵消
  //   ⇒ **布局视口不随键盘变矮**（设备实测：IME 336.38px 时 innerHeight 仍 915）。
  // - 宿主仍然把**实测正确**的 IME 高度交到 --oc-ime-bottom（IME1 实测
  //   336.38 CSS px × dpr 2.625 = 883px = type=ime frame 高度，完全一致）。
  // - 上游 SPA 不认识 --oc-ime-*，也不消费 env(safe-area-inset-bottom)
  //   （WebView 边到边 ⇒ env() 恒为 0）。
  //
  // ### 两条路，按宿主的 imeMode 二选一（**别同时上，会双倍留白**）
  //
  // 1. `resize`（**宿主默认**，见 InsetsHost.ImeMode / ImeResize）：
  //    宿主把窗口 content 的**底部内边距**压成 max(ime, nav) ⇒ 布局视口真的变矮
  //    ⇒ 任何靠视口高度的界面（终端画布、对话框、composer）**自动**跟着收。
  //    这条是"兜底"：不再依赖"每个界面都记得自己让位"。
  // 2. `vars`（老路子，保留）：视口不变，页面**自己**垫 --oc-ime-bottom：
  //    composer 用 :has() 锚定；实在锚不到就退到 [data-oc-ime-lift]。
  //
  // 线上事故背景（TERM1，2026-10-07 产品负责人报单"键盘遮住终端"）：
  // 只有 composer 一条让位路径时，**终端画布被键盘遮住 310px**（实测），
  // 因为画布高度来自视口、而视口没变。研究里早写明了这条脆弱点：
  // `research/env/FORMFACTORS_F4.md` §6 问题 3 ——"一旦 composer 让位逻辑在某个宽度上
  // 失效，不会退化成 resize 兜底，而是直接被遮挡"。本轮补上兜底。
  //
  // 所以下面前两条 CSS 只在 `vars` 模式生效（`html:not([data-oc-ime-mode="resize"])`）。
  // ======================================================================
  var IME_STYLE_ID = "oc-ime-lift";

  function installImeLiftStyle() {
    if (document.getElementById(IME_STYLE_ID)) return;
    var css = [
      // 边到边下 env() 恒为 0；用宿主实测的 nav/cutout 补出一个可用的"有效安全区"。
      ":root{--oc-safe-bottom-eff:max(var(--oc-safe-bottom,0px),env(safe-area-inset-bottom,0px));}",
      // composer 容器是 <form class="group/composer ...">（上游结构）。
      // 用 :has() 按**语义**锚定，不绑上游类名/标签名。
      // WebView 124 = Chrome 124，:has() 已支持。
      "html:not([data-oc-ime-mode=\"resize\"]) form:has(textarea),",
      "html:not([data-oc-ime-mode=\"resize\"]) form:has([contenteditable=\"true\"]){",
      "padding-bottom:var(--oc-ime-bottom,0px);",
      "box-sizing:border-box;",
      "transition:padding-bottom .12s ease;",
      "}",
      // 兜底：若 composer 不是 <form>，退到"最近 fixed/sticky 祖先"上抬。
      "html:not([data-oc-ime-mode=\"resize\"]) [data-oc-ime-lift]{",
      "padding-bottom:calc(var(--oc-ime-bottom,0px) + var(--oc-safe-bottom-eff,0px));",
      "box-sizing:border-box;transition:padding-bottom .12s ease;}",
    ].join("\n");
    var st = document.createElement("style");
    st.id = IME_STYLE_ID;
    st.textContent = css;
    (document.head || document.documentElement).appendChild(st);
  }

  /** 兜底路径：给"聚焦可编辑元素最近的 fixed/sticky 祖先"打标。 */
  function tagFixedAncestor(ed) {
    var c = ed && ed.parentElement;
    while (c && c !== document.body) {
      var pos = window.getComputedStyle(c).position;
      if (pos === "fixed" || pos === "sticky") {
        c.setAttribute("data-oc-ime-lift", "1");
        return true;
      }
      c = c.parentElement;
    }
    return false;
  }

  function onImeFocusIn(e) {
    var t = e && e.target;
    if (!t || !t.closest) return;
    if (!t.matches("textarea, [contenteditable=\"true\"]")) return;
    // :has() 规则不生效（composer 不是 <form>）时才需要兜底，代价极小。
    tagFixedAncestor(t);
  }


  function doApply(d) {
    var el = document.documentElement;
    if (!el) return;
    var s = el.style;
    s.setProperty("--oc-safe-top", d.safeTop + "px");
    s.setProperty("--oc-safe-bottom", d.safeBottom + "px");
    if ((d.imeMode || "vars") === "resize") {
      s.setProperty("--safe-area-inset-bottom", "0px");
      s.setProperty("--shell-safe-area-bottom", "0px");
    } else {
      s.setProperty("--safe-area-inset-bottom", d.safeBottom + "px");
      s.setProperty("--shell-safe-area-bottom", d.safeBottom + "px");
    }
    s.setProperty("--oc-safe-left", d.safeLeft + "px");
    s.setProperty("--oc-safe-right", d.safeRight + "px");
    s.setProperty("--oc-ime-bottom", d.imeBottom + "px");
    s.setProperty("--oc-window-height", d.windowHeight + "px");
    s.setProperty("--oc-ime-visible", d.imeVisible ? "1" : "0");
    if (d.viewHeight != null && d.viewHeight !== "") {
      var nextH = d.viewHeight + "px";
      var prevH = s.getPropertyValue("--oc-view-height");
      s.setProperty("--oc-view-height", nextH);
      // 内联高度压过后续才注入的官方样式，避免仍走要点击才更新的 100dvh。
      var rootNode = document.getElementById("root") || document.querySelector("body > div");
      if (rootNode) rootNode.style.setProperty("height", nextH);
      // 官方会话虚拟列表听 window.resize；dvh 不更新时靠这条把 flex 子项重排。
      if (nextH !== prevH && typeof requestAnimationFrame === "function") {
        requestAnimationFrame(function () {
          try { window.dispatchEvent(new Event("resize")); } catch (e) {}
        });
      }
    }
    s.setProperty("--oc-scheme", d.scheme);
    // 让浏览器自带的表单控件/滚动条也跟着深浅色走
    s.colorScheme = d.scheme;
    // 上游主题只认 `prefers-color-scheme: dark` 的 change 事件（见文件头注释：
    // Android WebView 的该媒体查询不可靠），这里把宿主读数转交给它。
    applyHostScheme(d.scheme);
    // 宿主是"压矮视口"还是"只发变量"——决定页面侧还要不要自己垫 IME（见 IME 抬升段）：
    // resize 模式下视口已经变矮，再垫一次 = 双倍留白（composer 会飘到半空）。
    el.setAttribute("data-oc-ime-mode", d.imeMode || "vars");
    pushSurfaceColor();
  }

