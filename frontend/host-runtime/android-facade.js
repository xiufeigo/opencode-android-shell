/*
 * W1 宿主注入层（facade）。由 WebHostActivity 用
 * WebViewCompat.addDocumentStartJavaScript(..., setOf("*")) 在**文档开始**注册，
 * 因此它早于页面任何脚本跑 —— 页面可以直接用 window.__ocAndroid。
 *
 * 为什么要一层 facade 而不是把 @JavascriptInterface 直接命名成 __ocAndroid：
 * 因为 `onBack()` 必须是**页面注册、宿主调用**的回调（宿主问"你处理了吗"，
 * 返回 true 表示已处理）。如果直接把原生对象叫 __ocAndroid，宿主问回原生的 onBack
 * 就成了自问自答。所以：
 *   - 原生对象注入名  = __ocAndroidNative（Kotlin: WebHostBridge）
 *   - 页面侧 facade   = window.__ocAndroid（本文件）
 */
(function () {
  var N = window.__ocAndroidNative;
  if (!N) return;
  window.__ocTerminalEngine = "xterm";
  // 宿主默认 ImeMode.RESIZE。官方 <main> 在属性落到 doApply 之前就会读
  // env(safe-area-inset-bottom)；Android WebView 给的是整条导航带（32 CSS），
  // 不是 0。先钉成 resize，让下面的 CSS 在首帧就把官方变量盖成 0。
  try { document.documentElement.setAttribute("data-oc-ime-mode", "resize"); } catch (e) {}

  // ======================================================================
  // 内置 UI 的 service worker 清理（APK 升级路径）
  //
  // 官方 Web 产物在 load 后注册 /sw.js，把整套 chunk 预缓存起来。Android 的 UI
  // 就在 APK 里、由本机 127.0.0.1 提供，预缓存没有收益；而 `skipWaiting: false`
  // 会让**旧包**的 worker 在升级后继续接管导航，直到所有旧页面关闭 —— 期间页面
  // 可能拿到上一版的 index.html 与已删除的 chunk（实测出现过整页报错白屏）。
  //
  // 这里在文档开始（早于页面脚本）做一次性清理：
  //   1. 反注册所有 service worker（含旧版本遗留的）；
  //   2. 删除 Cache Storage 里的预缓存，释放磁盘；
  //   3. 若**当前文档**正被旧 worker 接管，重载一次，让 APK 里的新页面重新出图。
  // 用 sessionStorage 标记避免重载循环；页面自身不再注册 SW（见
  // frontend/android/upstream.patch 的 entry.tsx：`!android && ...`）。
  // ======================================================================
  (function purgeEmbeddedUiCache() {
    var marker = "oc.android.embeddedUiCache";
    try {
      if (sessionStorage.getItem(marker) === "purged") return;
    } catch (e) {}
    var controlled = false;
    try {
      controlled = !!(navigator.serviceWorker && navigator.serviceWorker.controller);
    } catch (e) {}
    var work = [];
    try {
      if (navigator.serviceWorker) {
        work.push(navigator.serviceWorker.getRegistrations().then(function (list) {
          return Promise.all(list.map(function (registration) {
            try {
              return registration.unregister();
            } catch (e) {
              return false;
            }
          }));
        }));
      }
    } catch (e) {}
    try {
      if (window.caches) {
        work.push(caches.keys().then(function (keys) {
          return Promise.all(keys.map(function (key) {
            return caches.delete(key);
          }));
        }));
      }
    } catch (e) {}
    if (!work.length) return;
    Promise.all(work).then(function () {
      try {
        sessionStorage.setItem(marker, "purged");
      } catch (e) {}
      if (controlled) location.reload();
    }, function () {});
  })();

  var backHandler = null;
  var insetHandler = null;

  // insets 可能在 documentElement 就绪前到达，先存住，DOMContentLoaded 再补一次。
  var lastInsets = null;

  // ======================================================================
  // 系统深浅色桥（宿主 → matchMedia）
  //
  // 上游 UI 判断"跟随系统"只走 `matchMedia("(prefers-color-scheme: dark)")`
  // （`packages/ui/src/theme/context.tsx` 的 `getSystemMode()` 与 `onMount` 里给
  // 该媒体查询挂的 change 监听）。Android WebView **不保证**这个媒体查询在
  // `uiMode` 翻转后更新：Chromium 只有 embedder 允许 algorithmic darkening 时
  // 才把它当深色，默认可以一直停在 light —— 真机表现就是系统切深色后页面不变。
  // 宿主本来就知道真值：`InsetsHost.publish` 每帧带 `scheme`，
  // `onConfigurationChanged` 又强制重推一次 payload。于是这里把那个媒体查询换成
  // 宿主驱动的实现：`matches` 跟随 payload，翻转时派发 change。上游一行不用改。
  //
  // 只接管 `(prefers-color-scheme: dark)`；`prefers-reduced-motion` /
  // `display-mode` 等其它查询继续走浏览器原生实现。
  var hostDark = (function () {
    // 宿主在注入本文件前会先放 `window.__ocHostSchemeInitial`（创建时的 uiMode
    // 真值）。它比 WebView 的媒体查询可靠，也让首帧底色不再闪。
    var initial = window.__ocHostSchemeInitial;
    if (initial === "dark" || initial === "light") return initial === "dark";
    try {
      return !!(window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches);
    } catch (e) {
      return false;
    }
  })();
  var schemeListeners = [];
  var schemeQuery = null;

  function emitSchemeChange() {
    var ev = {
      type: "change",
      media: "(prefers-color-scheme: dark)",
      matches: hostDark,
      target: schemeQuery,
      currentTarget: schemeQuery,
    };
    var fns = schemeListeners.slice();
    for (var i = 0; i < fns.length; i++) {
      try {
        fns[i].call(schemeQuery, ev);
      } catch (e) {}
    }
    if (typeof schemeQuery.onchange === "function") {
      try {
        schemeQuery.onchange.call(schemeQuery, ev);
      } catch (e) {}
    }
  }

  function installSchemeQuery() {
    if (schemeQuery || !window.matchMedia) return;
    var real = window.matchMedia.bind(window);
    schemeQuery = {
      media: "(prefers-color-scheme: dark)",
      get matches() {
        return hostDark;
      },
      onchange: null,
      addEventListener: function (type, fn) {
        if (type === "change" && typeof fn === "function" && schemeListeners.indexOf(fn) < 0) schemeListeners.push(fn);
      },
      removeEventListener: function (type, fn) {
        var i = schemeListeners.indexOf(fn);
        if (type === "change" && i >= 0) schemeListeners.splice(i, 1);
      },
      addListener: function (fn) {
        schemeQuery.addEventListener("change", fn);
      },
      removeListener: function (fn) {
        schemeQuery.removeEventListener("change", fn);
      },
      dispatchEvent: function () {
        emitSchemeChange();
        return true;
      },
    };
    window.matchMedia = function (query) {
      if (String(query).indexOf("prefers-color-scheme: dark") >= 0) return schemeQuery;
      return real(query);
    };
  }

  function applyHostScheme(scheme) {
    var dark = String(scheme).toLowerCase() === "dark";
    if (dark === hostDark) return;
    hostDark = dark;
    emitSchemeChange();
  }

  installSchemeQuery();

  // ======================================================================
  // 顶部安全区（缺陷 A：标题与状态栏/挖孔重叠）
  //
  // 根因（F18 实测）：**不是**宿主没给 inset，而是两条各断一半：
  //  1) 宿主侧：InsetsHost 的首次 publish 发生在 onCreate 的 requestApplyInsets()，
  //     那时页面还不存在 ⇒ payload 静默丢失；顶部 inset 是静态的（不像 IME 会抖动），
  //     去重逻辑于是永不重发 ⇒ 页面侧 --oc-safe-top 恒为空。
  //     已由宿主侧 InsetsHost.replay() 在 onPageFinished 补发修好。
  //  2) 页面侧（就是这一段）：即使变量到了，上游 SPA **也不消费 --oc-safe-top**
  //     （与 IME1 同构：宿主交出了正确读数，页面没接）。
  //     WebView 边到边 ⇒ env(safe-area-inset-top) 恒为 0，也不能指望。
  //
  // 所以这里用宿主实测的 --oc-safe-top 给**应用根容器**垫顶部内边距。
  // 选根容器而不是 header 自身：根容器是 h-dvh + box-sizing:border-box，
  // 垫 padding-top 后**总高仍是视口高**（内容区等量收缩、不溢出、不出滚动条）；
  // 若垫在 header 上则会把它顶下去而下方内容不动，形态更差。
  //
  // 高度不能继续靠官方的 `h-dvh`（100dvh）：Android WebView 在父层 padding
  // 把控件压矮之后，dvh 经常仍是压矮前的值，要点击页面才会重算。
  // 会话面板是 #root 里的 flex-1，根节点偏矮 ⇒ 白色底框悬空。
  // 改用宿主量到的 WebView 实高 `--oc-view-height`（没有读数时退回 100%）。
  // ======================================================================
  var TOP_STYLE_ID = "oc-top-inset";

  function installTopInsetStyle() {
    if (document.getElementById(TOP_STYLE_ID)) return;
    var css = [
      // 边到边下 env() 恒为 0；取两者较大值，与底部 --oc-safe-bottom-eff 同一口径。
      ":root{--oc-safe-top-eff:max(var(--oc-safe-top,0px),env(safe-area-inset-top,0px));}",
      // 官方 shell 读 --shell-safe-area-bottom / --safe-area-inset-bottom / env()。
      // WebView 的 env(safe-area-inset-bottom) 是整条导航带；宿主已经用父层 padding
      // 让位，这里必须钉 0，否则 <main> 再垫 32px，白底框悬空，要点击才把 env 收掉。
      "html[data-oc-ime-mode=\"resize\"]{",
      "--safe-area-inset-bottom:0px !important;",
      "--shell-safe-area-bottom:0px !important;",
      "}",
      // 官方 <main> 内联 padding-bottom 走 env(safe-area-inset-bottom)。
      // WebView 给的是整条导航带 32 CSS；变量钉 0 后仍可能算出 32（max(10px, env())）。
      "html[data-oc-ime-mode=\"resize\"] main{",
      "padding-bottom:0px !important;",
      "}",
      "html,body{height:100%;}",
      // 应用根容器：#root 是上游挂载点，body>div 是它在 DOM 上的语义形态。
      // 两者同指一个元素；写成两条是为了上游改 id 时不至于整条修法失效。
      "#root,body>div:first-of-type{",
      "padding-top:var(--oc-safe-top-eff,0px);",
      "height:var(--oc-view-height,100%);",
      "box-sizing:border-box;",
      "}",
    ].join("\n");
    var st = document.createElement("style");
    st.id = TOP_STYLE_ID;
    st.textContent = css;
    (document.head || document.documentElement).appendChild(st);
  }

  // ======================================================================
  // BACK：只关闭真正显示在视口内的浮层；常驻展开区域不是返回层级。
  // 手机抽屉预挂载在视口外，关闭或退出动画中的节点不应吞掉返回键。
  // ======================================================================
  var OVERLAY_OPEN_SELECTORS = [
    "[role='menu']",
    "[role='listbox']",
    "[role='dialog']:not([data-closed]):not([data-closing])",
  ].join(",");

  function visibleBackLayer(el) {
    var r = el.getBoundingClientRect();
    var style = getComputedStyle(el);
    return r.width > 0 && r.height > 0 && r.top < window.innerHeight && r.bottom > 0 &&
      r.left < window.innerWidth && r.right > 0 &&
      style.visibility !== "hidden" && style.display !== "none" &&
      !el.closest("[aria-hidden='true'],[inert],[data-closed]");
  }

  function closeTopOverlayOnBack() {
    // The trigger becomes expanded before a drawer's entrance animation reaches
    // the viewport. It still owns Back during that animation.
    var layers = document.querySelectorAll(OVERLAY_OPEN_SELECTORS + ",[aria-haspopup][aria-expanded='true']");
    if (!Array.prototype.some.call(layers, visibleBackLayer)) return false;
    var target = document.activeElement || document.body;
    ["keydown", "keyup"].forEach(function (type) {
      target.dispatchEvent(new KeyboardEvent(type, {
        key: "Escape",
        code: "Escape",
        keyCode: 27,
        which: 27,
        bubbles: true,
        cancelable: true,
        composed: true,
      }));
    });
    return true;
  }

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

  // ======================================================================
  // 视口底带表面色
  //
  // resize 模式把 WebView 压矮后，露出的那条带子显示的是宿主窗口底色
  // （DayNight 资源，跟**系统**模式走）。页面主题与系统不一致时
  // （系统深色 + 页面浅色）带子就是一整块黑色——"小白条所在位置直接全黑"
  // （MIUI 平板浅色主题实测）。
  //
  // 把页面解析后的表面底色报给宿主刷那条带子：条带跟页面主题走。
  // 主题切换 / 深浅切换都不改 insets，单靠 doApply 推不到 → 另挂
  // MutationObserver 盯 html/body 的主题属性，再补一次首帧推送。
  // ======================================================================
  var lastSurfaceColor = "";
  function surfaceColor() {
    var els = [document.getElementById("root"), document.body, document.documentElement];
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (!el) continue;
      try {
        var bg = getComputedStyle(el).backgroundColor;
        if (bg && bg !== "transparent" && bg !== "rgba(0, 0, 0, 0)") return bg;
      } catch (e) {}
    }
    return "";
  }
  function pushSurfaceColor() {
    var c = surfaceColor();
    if (!c || c === lastSurfaceColor) return;
    lastSurfaceColor = c;
    try {
      N.reportSurfaceColor(c);
    } catch (e) {}
  }

  var oc = {
    // ---- 元信息 -------------------------------------------------------
    version: 1,
    hostInfo: function () {
      try {
        return N.hostInfo();
      } catch (e) {
        return "{}";
      }
    },

    // ---- 1. 通知 -------------------------------------------------------
    hasNotificationPermission: function () {
      try {
        return N.hasNotificationPermission();
      } catch (e) {
        return -1;
      }
    },
    requestNotificationPermission: function () {
      try {
        N.requestNotificationPermission();
      } catch (e) {}
    },
    postMessage: function (title, body, sessionId) {
      try {
        return N.postMessage(String(title || ""), String(body || ""), String(sessionId || ""));
      } catch (e) {
        return -2;
      }
    },

    // ---- 5. 凭据 -------------------------------------------------------
    getSecret: function (key) {
      try {
        var v = N.getSecret(String(key || ""));
        return v == null ? null : v;
      } catch (e) {
        return null;
      }
    },
    setSecret: function (key, value) {
      try {
        return N.setSecret(String(key || ""), value == null ? null : String(value));
      } catch (e) {
        return false;
      }
    },

    // ---- 7. 常亮 -------------------------------------------------------
    setKeepScreenOn: function (on) {
      try {
        N.setKeepScreenOn(!!on);
      } catch (e) {}
    },
    // ime 策略：'vars'（只发 CSS 变量，默认）| 'resize'（额外把 WebView 压矮）
    setImeMode: function (mode) {
      try {
        N.setImeMode(String(mode || "vars"));
      } catch (e) {}
    },
    getImeMode: function () {
      try {
        return N.getImeMode();
      } catch (e) {
        return "vars";
      }
    },

    // ---- 9. 诊断页重试（G1）-----------------------------------------------
    // 透传原生 retry()：宿主会重新做 WebView 版本前置检查并重走启动链，
    // 所以用户在诊断页上更新完 "Android System WebView" 后点它就能真的进官方 UI。
    retry: function () {
      try {
        N.retry();
        return true;
      } catch (e) {
        return false;
      }
    },

    // ---- 8. 主题 -------------------------------------------------------
    // 页面把自己的 data-color-scheme 报上来，宿主据此切系统栏图标明暗。
    reportTheme: function (dark) {
      try {
        N.reportTheme(!!dark);
      } catch (e) {}
      pushSurfaceColor();
    },

    // ---- 2. 返回（宿主调用页面） ---------------------------------------
    // 页面注册：window.__ocAndroid.setBackHandler(() => { ...; return true })
    //          返回 true = 已处理，宿主就不再往下走、**绝不** finish()。
    onBack: function () {
      if (closeTopOverlayOnBack()) return true;
      return typeof backHandler === "function" && backHandler();
    },
    setBackHandler: function (fn) {
      backHandler = typeof fn === "function" ? fn : null;
    },

    // ---- 6. insets（宿主调用页面） -------------------------------------
    applyInsets: function (json) {
      var d;
      try {
        d = typeof json === "string" ? JSON.parse(json) : json;
      } catch (e) {
        return;
      }
      if (!d) return;
      lastInsets = d;
      doApply(d);
      if (typeof insetHandler === "function") {
        try {
          insetHandler(d);
        } catch (e) {}
      }
      window.dispatchEvent(new CustomEvent("oc:insets", { detail: d }));
    },
    onInsets: function (fn) {
      insetHandler = typeof fn === "function" ? fn : null;
      if (typeof fn === "function" && lastInsets) {
        try {
          fn(lastInsets);
        } catch (e) {}
      }
    },
    getInsets: function () {
      return lastInsets;
    },

    // ---- 通知点击回会话 ------------------------------------------------
    /**
     * N2：真的把页面切到那个会话 —— 导航，不是派发事件。
     *
     * 根因：上游 SPA **没有任何一处**监听 `oc:openSession`（也不认识 `__ocAndroid`），
     * 所以只派发事件 = 把 sessionId 派发进虚空，点了通知页面永远不切会话。
     * 上游唯一支持的入口是路由
     *   `/server/<base64url(serverKey)>/session/<sessionId>`
     * （_upstream/packages/app/src/shell/routes/routes.tsx:39-40 与 routes/session.ts:5-7），
     * 上游自己的 openNotificationSession() 做的也是 navigate(tabHref(tab))。
     *
     * 路径由宿主算好传进来（NotifierHost.sessionRoutePath，纯函数、单测可断言），
     * 页面侧只负责执行。
     */
    __openSessionByRoute: function (route, sessionId) {
      try {
        window.__lastOpenSessionRoute = route;
        window.__lastOpenSessionId = sessionId;
        if (route && typeof route === "string" && route.charAt(0) === "/") {
          window.location.assign(route);
        }
      } catch (e) {}
    },
  };

  window.__ocAndroid = oc;

  // ======================================================================
  // 物理 ESC 透传标记。
  //
  // 宿主（WebHostActivity.dispatchKeyEvent）保证物理 ESC **不会**被当成
  // Android Back：ESC 原样进 WebView，被 OEM 报成 KEYCODE_BACK 的物理 ESC
  // 会被还原成 ESCAPE 再进来。页面侧据此知道"ESC 归我"，xterm / Vim 可以
  // 正常退出插入模式，而不会触发宿主的返回逻辑。
  //
  // 与 :app 的 MainActivity 保持同名同义，便于两边行为一致。
  // ======================================================================
  window.__ocAndroidShell = true;
  window.__ocAndroidEscapePassThrough = true;

  // ======================================================================
  // N5 / upstream notification policy bridge.
  //
  // Upstream delivers system notifications through the browser Notification
  // API. Android WebView does not implement it, so upstream's web platform
  // takes the early return at
  //   origin/v2 packages/app/src/runtime/platform/web.ts:22
  //     if (!("Notification" in window)) return
  // and NO turn-complete / error notification is ever raised in this host.
  //
  // This shim defines window.Notification so that upstream's own policy
  // executes verbatim in the page -- we do NOT re-implement it here:
  //   - web.ts:24-28      permission gate   -> hasNotificationPermission()
  //   - web.ts:29         visible+focused   -> not raised (in-page)
  //   - coordinator.ts:62 hasFocus()        -> not raised (in-page)
  //   - coordinator.ts:73-93 claim()        -> per-eventID dedup (in-page)
  //   - notification.tsx:227,240 hasOpenTab  -> only sessions with an open
  //                          tab notify (in-page)
  //   - notification.tsx:228,229,256,257     sound/notification user switches
  //
  // The host therefore only DELIVERS. Its own NotifierHost.postTurnFinished
  // stays a pure delivery function with no dedup / mute / focus policy of
  // its own -- the policy lives in one place, upstream's.
  //
  // sessionId: upstream's notify(title, description, onClick) carries no
  // session id, but the notification is only raised while that session has an
  // open tab (notification.tsx:240), so the current route is the right target.
  // Route shape is upstream's: /server/<serverKey>/session/<id>
  //   (shell/routes/routes.tsx:39-40, shell/routes/session.ts:5-7)
  // ======================================================================
  function currentSessionId() {
    try {
      var m = /\/server\/[^/]+\/session\/([^/?#]+)/.exec(window.location.pathname || "");
      return m ? decodeURIComponent(m[1]) : "";
    } catch (e) {
      return "";
    }
  }

  if (!("Notification" in window)) {
    var ShimNotification = function (title, options) {
      var opts = options || {};
      var sid = currentSessionId();
      var closed = false;
      try {
        oc.postMessage(String(title == null ? "" : title), String(opts.body == null ? "" : opts.body), sid);
      } catch (e) {}
      this.onclick = null;
      this.close = function () {
        closed = true;
      };
      this.__isClosed = function () {
        return closed;
      };
    };
    ShimNotification.permission = "default";
    ShimNotification.requestPermission = function () {
      var has = oc.hasNotificationPermission();
      // P9 root-cause fix: write the host truth back into the static property.
      // Previously only the resolved promise value changed; ShimNotification.permission
      // stayed "default" forever. If upstream reads Notification.permission first
      // (calling requestPermission only when it reads "default"), then after the
      // permission dialog was accepted the property is still "default" => never notify.
      ShimNotification.permission = has === 1 ? "granted" : (has < 0 ? "denied" : "default");
      if (has === 1) return Promise.resolve("granted");
      if (has < 0) return Promise.resolve("denied");
      oc.requestNotificationPermission();
      // Web-side cannot observe the async Android grant round-trip; report
      // "default" so upstream asks again on the next turn instead of
      // assuming either outcome.
      return Promise.resolve("default");
    };
    try {
      window.Notification = ShimNotification;
    } catch (e) {}

    // P9 · Host push entry for the permission result.
    // The Android side cannot observe the async grant round-trip from JS, so
    // WebHostActivity's POST_NOTIFICATIONS launcher callback dispatches this
    // custom event via evaluateJavascript:
    //   window.dispatchEvent(new CustomEvent('oc:notifyPerm', {detail:'granted'|'denied'}))
    // Listen for it and refresh the static property, so a gate that reads
    // Notification.permission (instead of calling requestPermission) stops
    // seeing a stale "default" after the user accepted the system dialog.
    try {
      window.addEventListener("oc:notifyPerm", function (ev) {
        var detail = ev && ev.detail;
        if (detail === "granted" || detail === "denied") {
          ShimNotification.permission = detail;
        }
      });
    } catch (e) {}
  }

  // ======================================================================
  // P10 · 后台"未聚焦"页面状态 shim —— 通知闸门的最后一道墙。
  //
  // 上游 notify 闸门原文（bundle `ui/oa/index-D2r3WEwY.js`）：
  //   if ((…Notification.requestPermission().catch(()=>'denied')
  //        : Notification.permission) !== 'granted'
  //       || document.visibilityState === 'visible' && document.hasFocus()) return;
  //   let r = new Notification(...)
  //
  // 即：授权 granted（P9 已修）之外，**还要求"页面不是 可见且聚焦"** 才通知。
  // 而保活路径下 WebView 切后台只 pause 渲染/JS 时钟，**不改这两个属性/方法**的取值：
  //   document.visibilityState 恒 "visible"、document.hasFocus() 恒 true
  // （P7 合成的 blur 事件是"事件"，不是这两个读数；WebView 后台不发原生
  //   visibilitychange，也不让 hasFocus() 变 false —— 这正是 P9 设备实测
  //   `ctor=0`（上游从未构造 Notification）的最后一堵墙）。
  //
  // 所以宿主在 onPause/onResume 显式改写这两个**读数**。
  //
  // 🔴 **只覆盖属性/方法，绝不派发 visibilitychange / blur / focus 事件。**
  //   P7 已证：上游 pageLifecycle 监听 visibilitychange，且在 visible 时会
  //   触发 resync → abort，把保活路径下的 4095 SSE 掐死 ⇒ "终帧必丢"的
  //   P6 死锁原样复活（P6/P7 报告 §死锁链）。blur/focus 派发则由
  //   WebHostActivity 的 dispatchPageLifecycleEvent 负责，本 shim 不碰事件面。
  //   任何"顺手补个事件让它更真"的修改都属于**回退**，见 NotificationShimTest。
  //
  // 取值语义：ocBackgrounded 由宿主在**进入后台时**置 true、回前台置 false，
  // 两条路径（回合保活 / 空闲断流）都设，不作区分 —— 闸门只读这两个值，
  // 与 SSE 是否仍活着无关。
  // ======================================================================
  // 🔴 UX15-P1 · hasFocus 覆盖改为**委托原生**：原实现 `return !ocBackgrounded`
  //   把"前台"硬编码成 true，等于替 WebView 撒了一个谎 —— 只要宿主认为在前台，
  //   系统弹窗（权限/选择器）遮挡、多窗口/分屏、系统面板拉起这些**页面确实没有
  //   焦点**的时刻，上游闸门仍会读到 hasFocus()===true，与原生 WebView 语义偏差。
  //   现在只叠加宿主**确知**的那一个事实（"我进了后台"），其余一律委托原生。
  //   visibilityState 的覆盖维持原样：原生在前台本就恒为 "visible"，WebView 后台
  //   又不发 visibilitychange（见上），所以覆盖前后取值完全一致，不引入偏差。
  var ocBackgrounded = false;
  try {
    Object.defineProperty(document, "visibilityState", {
      configurable: true,
      get: function () {
        return ocBackgrounded ? "hidden" : "visible";
      },
    });
    var ocOrigHasFocus = document.hasFocus.bind(document);
    document.hasFocus = function () {
      return ocBackgrounded ? false : ocOrigHasFocus();
    };
  } catch (e) {}
  window.__ocSetBackground = function (v) {
    ocBackgrounded = !!v;
  };

  if (document.readyState === "loading") {
    document.addEventListener(
      "DOMContentLoaded",
      function () {
        installImeLiftStyle();
        installTopInsetStyle();
        if (lastInsets) doApply(lastInsets);
        window.dispatchEvent(new CustomEvent("oc:ready", { detail: { version: 1 } }));
      },
      { once: true }
    );
  } else {
    installImeLiftStyle();
    installTopInsetStyle();
    window.dispatchEvent(new CustomEvent("oc:ready", { detail: { version: 1 } }));
  }
  // focusin 委托：composer 是 SPA 动态挂载的，不能只绑一次。
  document.addEventListener("focusin", onImeFocusIn, true);
  // insets 每变一次都刷新一次（IME 收起时 --oc-ime-bottom 归 0，留白自动撤掉）。
  if (lastInsets) doApply(lastInsets);

  // ======================================================================
  // UX6 · 输入框内方向键"焦点不越界"（缺陷：按 → 到行尾后焦点被 WebView
  // 空间导航抢到边栏/工具按钮）。
  //
  // 根因（设备实测，logcat 逐键读数）：composer 里光标移到行尾后再按
  // →（KEYCODE_DPAD_RIGHT）时，编辑器不消费该键 ⇒ 按键落给 WebView 的
  // 空间导航（focus search）⇒ DOM focus 跳到右侧可聚焦元素并保持在那里。
  // 上游键位派发器对 editable 目标里的无修饰键直接 return
  // （_upstream packages/app/src/shell/commands/command.tsx:410），与此无关。
  //
  // 修法：文档捕获阶段拦下"光标已无处可移"的方向键，只 preventDefault
  // （**不** stopPropagation）——
  //  · preventDefault 抑制 WebView 的默认动作（= 空间导航），焦点留在输入框；
  //  · 不 stopPropagation，让上游自己的 keydown 消费者（设置搜索的
  //    combobox 导航、时间线搜索等）照常收到事件；它们多数自己也会
  //    preventDefault，行为与桌面端一致。
  //
  // 范围（刻意收窄，宁缺勿错）：
  //  · 只管 ArrowLeft / ArrowRight 的光标边界；
  //  · 单行 input / textarea 用 selectionStart/End 判定（选区未折叠时放行，
  //    默认行为是收拢选区，那是"移动"）；
  //  · contenteditable（composer 的另一种形态）用 Selection 的折叠位置 +
  //    光标前字符数判定首尾；实测该形态下行尾按 → 同样会被空间导航抢走焦点；
  //  · 不碰 ArrowUp/ArrowDown（多行/软折行无法可靠判定首末行；
  //    上游搜索框用它们做下拉导航）；
  //  · 跳过 .xterm 内的 textarea：终端把空 textarea 当按键汇，
  //    "边界"判定对它全部命中、会把终端方向键整个吞掉；
  //  · 跳过 IME 组合中（isComposing / keyCode 229）与一切带修饰键的组合。
  //
  // ⚠ P5 绊线豁免：`InputPathHotspotTest` 禁止注入层注册逐键监听，
  // 本守卫是唯一被显式豁免的 keydown 注册点（豁免条件写在该测试里）。
  //  handler 体内只做属性读与少量 Range 文本度量（composer 文本量小），
  //  不许加存储读/字符串重活。
  // ======================================================================
  function onEditableArrowGuard(e) {
    var k = e.key;
    if (k !== "ArrowLeft" && k !== "ArrowRight") return;
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    if (e.isComposing || e.keyCode === 229) return;
    var t = e.target;
    if (!t || t.nodeType !== 1) return;
    if (t.closest && t.closest(".xterm")) return; // 终端按键汇，边界判定不适用
    var tag = t.tagName;
    var isTextarea = tag === "TEXTAREA";
    var isTextInput = tag === "INPUT" && !t.readOnly &&
      /^(?:text|search|url|password|tel|email|number)$/i.test(t.type || "text");
    if (isTextarea || isTextInput) {
      var start = t.selectionStart;
      var end = t.selectionEnd;
      if (start === undefined || start === null) return; // number 等无选区 API
      if (start !== end) return; // 选区折叠优先，交给默认行为
      var len = (t.value || "").length;
      var boundary = k === "ArrowLeft" ? start === 0 : end === len;
      if (!boundary) return; // 光标还能动，放行
      e.preventDefault(); // 抑制 WebView 空间导航；焦点留在输入框
      return;
    }
    // contenteditable：判定选区是否折叠在内容首/尾
    var root = t.isContentEditable ? t
      : (t.closest ? t.closest("[contenteditable=\"true\"]") : null);
    if (!root) return;
    var sel = window.getSelection();
    if (!sel || sel.rangeCount === 0 || !sel.isCollapsed) return;
    var range = sel.getRangeAt(0);
    if (!root.contains(range.startContainer)) return; // 光标不在目标 editable 内
    var pre = range.cloneRange();
    pre.selectNodeContents(root);
    pre.setEnd(range.startContainer, range.startOffset);
    var before = pre.toString().length; // 光标前的字符数
    var total = (root.textContent || "").length;
    var atEdge = k === "ArrowLeft" ? before === 0 : before === total;
    if (!atEdge) return; // 光标还能动，放行
    e.preventDefault(); // 抑制 WebView 空间导航；焦点留在输入框
  }
  document.addEventListener("keydown", onEditableArrowGuard, true);

