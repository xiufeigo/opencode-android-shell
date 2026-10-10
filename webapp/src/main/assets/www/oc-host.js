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
 *
 * 另附两个上游已约定的兼容入口（见 frontend/patches/android-webview-z-native-proxy.patch
 * 里的 packages/app/src/utils/android-proxy.ts）：window.OpenCodeAndroid.proxyOrigin/proxyPrefix
 * 与 window.__ocAndroidProxy。原生对象上确实实现了这两个方法，所以能对上。
 */
(function () {
  var N = window.__ocAndroidNative;
  if (!N) return;
  window.__ocTerminalEngine = "xterm";

  var backHandler = null;
  var insetHandler = null;
  var fileHandler = null;
  var saveHandler = null;
  var sessionHandler = null;

  // insets 可能在 documentElement 就绪前到达，先存住，DOMContentLoaded 再补一次。
  var lastInsets = null;

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
  // ======================================================================
  var TOP_STYLE_ID = "oc-top-inset";

  function installTopInsetStyle() {
    if (document.getElementById(TOP_STYLE_ID)) return;
    var css = [
      // 边到边下 env() 恒为 0；取两者较大值，与底部 --oc-safe-bottom-eff 同一口径。
      ":root{--oc-safe-top-eff:max(var(--oc-safe-top,0px),env(safe-area-inset-top,0px));}",
      // 应用根容器：#root 是上游挂载点，body>div 是它在 DOM 上的语义形态。
      // 两者同指一个元素；写成两条是为了上游改 id 时不至于整条修法失效。
      "#root,body>div:first-of-type{",
      "padding-top:var(--oc-safe-top-eff,0px);",
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
    s.setProperty("--oc-safe-left", d.safeLeft + "px");
    s.setProperty("--oc-safe-right", d.safeRight + "px");
    s.setProperty("--oc-ime-bottom", d.imeBottom + "px");
    s.setProperty("--oc-window-height", d.windowHeight + "px");
    s.setProperty("--oc-ime-visible", d.imeVisible ? "1" : "0");
    s.setProperty("--oc-scheme", d.scheme);
    // 让浏览器自带的表单控件/滚动条也跟着深浅色走
    s.colorScheme = d.scheme;
    // 宿主是"压矮视口"还是"只发变量"——决定页面侧还要不要自己垫 IME（见 IME 抬升段）：
    // resize 模式下视口已经变矮，再垫一次 = 双倍留白（composer 会飘到半空）。
    el.setAttribute("data-oc-ime-mode", d.imeMode || "vars");
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

    // ---- 3. 文件 -------------------------------------------------------
    // 选完会 dispatch 'oc:filePicked'；要字节再调 readPickedFile()。
    pickFile: function (accept, multiple) {
      try {
        N.pickFile(String(accept || ""), !!multiple);
      } catch (e) {}
    },
    readPickedFile: function () {
      try {
        return N.readPickedFile();
      } catch (e) {
        return '{"error":"readPickedFile failed"}';
      }
    },
    // base64 直存：宿主弹 CREATE_DOCUMENT，用户选完写入。
    saveContent: function (name, mime, base64) {
      try {
        N.saveContent(String(name || "download"), String(mime || "application/octet-stream"), String(base64 || ""));
      } catch (e) {}
    },
    clearPickedFile: function () {
      try {
        N.clearPickedFile();
      } catch (e) {}
    },

    // ---- 4. 分享 -------------------------------------------------------
    share: function (text, title) {
      try {
        return N.share(String(text || ""), String(title || ""));
      } catch (e) {
        return false;
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
    onOpenSession: function (sessionId) {
      if (typeof sessionHandler === "function") {
        try {
          sessionHandler(sessionId);
        } catch (e) {}
      }
      window.dispatchEvent(
        new CustomEvent("oc:openSession", { detail: { sessionId: sessionId } })
      );
    },
    setOpenSessionHandler: function (fn) {
      sessionHandler = typeof fn === "function" ? fn : null;
    },

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
     * 页面侧只负责执行；事件仍然照发，不破坏任何已有监听者。
     */
    __openSessionByRoute: function (route, sessionId) {
      try {
        window.__lastOpenSessionRoute = route;
        window.__lastOpenSessionId = sessionId;
        if (route && typeof route === "string" && route.charAt(0) === "/") {
          window.location.assign(route);
        }
      } catch (e) {}
      return oc.onOpenSession(sessionId);
    },

    onFilePicked: function (fn) {
      fileHandler = typeof fn === "function" ? fn : null;
    },
    _emitFilePicked: function (payload) {
      if (typeof fileHandler === "function") {
        try {
          fileHandler(payload);
        } catch (e) {}
      }
      window.dispatchEvent(new CustomEvent("oc:filePicked", { detail: payload }));
    },
    onFileSaved: function (fn) {
      saveHandler = typeof fn === "function" ? fn : null;
    },
    _emitFileSaved: function (payload) {
      if (typeof saveHandler === "function") {
        try {
          saveHandler(payload);
        } catch (e) {}
      }
      window.dispatchEvent(new CustomEvent("oc:fileSaved", { detail: payload }));
    },
  };

  window.__ocAndroid = oc;

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

  // 上游兼容入口（android-proxy.ts 会读这两个）
  window.OpenCodeAndroid = {
    proxyOrigin: function () {
      try {
        return N.proxyOrigin();
      } catch (e) {
        return "";
      }
    },
    proxyPrefix: function () {
      try {
        return N.proxyPrefix();
      } catch (e) {
        return "";
      }
    },
  };
  try {
    window.__ocAndroidProxy = {
      origin: window.OpenCodeAndroid.proxyOrigin(),
      prefix: window.OpenCodeAndroid.proxyPrefix(),
    };
  } catch (e) {}

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

  // ======================================================================
  // UX8 · 回合电源传感器（D1 常亮的**驱动** + P6 后台断流取证）
  //
  // 为什么需要它：UX6 删回环代理后宿主看不见流量，D1 只剩"页面桥"一条路，
  // 而上游官方 UI 从不调 setKeepScreenOn ⇒ 常亮从未置起（FIX_D1 的教训：
  // "观测到 0 命中"不等于"策略在位"）。上游全仓也没有 wakeLock/keepScreenOn
  // 机制可复用（_upstream/packages/app/src 零命中）⇒ 按规格在注入层自建。
  //
  // 传感器只干两件事，全部是**观测 + 调既有桥**，不碰凭据、不改上游行为：
  //  1) 回合开始：fetch 拦截到 POST /api/session/{id}/prompt|message|message_async
  //     ⇒ 乐观置位（与上游 composer/submit.ts:100-103 的乐观 busy 同一思路）。
  //  2) 回合进行/结束：给 /api/event 的 SSE 响应体套一根透明 TransformStream
  //     tee，逐行解析 "data: {json}" 帧，按**线上实测验证过**的
  //     session.execution.started / .succeeded / .failed / .interrupted
  //     （上游自己判 running/idle 也是这四个：packages/client/src/solid/data.ts
  //     :1009-1011 与 :1025-1028）维护 busy 会话集合 ⇒ 驱动 oc.setKeepScreenOn。
  //     ⚠ 不用 "session.status"+"type":"idle" 作主判据：真后端 4095 实测整轮
  //     无该帧（research/web/ux8/wire_event.txt）——旧标记只留作兜底。
  //
  // 本文件由 addDocumentStartJavaScript 注入，早于页面脚本 ⇒ 上游随后
  // 拿到的 window.fetch 就是我们的包装（含 platform.fetch 引用同一全局）。
  //
  // 多会话：busy 按键 sessionID 计数，任一会话在跑 ⇒ 常亮；全部收口 ⇒ 释放。
  // 已知边界（与旧代理时代同取舍）：后台期间回合结束时 SSE 已被掐断，事件丢
  // 失 ⇒ 靠宿主 RoundActivityTracker 的 90s 空闲兜底 + 30min 硬上限收口。
  // ======================================================================
  function installRoundPowerSensor() {
    if (window.__ocRoundPowerSensor) return;
    window.__ocRoundPowerSensor = { installed: true };

    var NativeFetch = window.fetch;
    if (typeof NativeFetch !== "function") return;

    var busy = {};       // sessionID -> true；"__post__" = 发送瞬间的乐观占位
    var keepOn = null;   // 上次推给宿主的值（去抖，避免每帧刷桥）
    var streamSeq = 0;   // SSE 流编号（取证日志用）

    function log(msg) {
      try {
        console.log("[oc-pow] " + msg);
      } catch (e) {}
    }

    function pushKeep(on) {
      if (keepOn === on) return;
      keepOn = on;
      try {
        oc.setKeepScreenOn(on); // 既有桥：前台校验/超时兜底/落 flag 全在宿主侧
      } catch (e) {}
      var keys = [];
      for (var k in busy) keys.push(k);
      log("keepScreenOn -> " + on + " busy=[" + keys.join(",") + "]");
    }

    function setBusy(id, on) {
      if (!id) id = "__unknown__";
      var had = !!busy[id];
      if (on === had) return;
      if (on) busy[id] = true;
      else delete busy[id];
      var any = false;
      for (var k2 in busy) {
        any = true;
        break;
      }
      pushKeep(any);
    }

    var ROUND_POST_RE = /\/api\/session\/[^/?#]+\/(?:prompt|message|message_async)\/?$/;
    var EVENT_PATH_RE = /\/api\/event\/?$/;

    // 按行解析 SSE 帧（线上格式已验证："data: {json}"，心跳为 ": heartbeat"）。
    function scanEventLines(lines) {
      for (var i = 0; i < lines.length; i++) {
        var line = lines[i];
        if (line.indexOf("data:") !== 0) continue;
        var payload = line.substring(5).replace(/^\s+/, "");
        if (
          payload.indexOf("session.execution") < 0 &&
          payload.indexOf("session.status") < 0 &&
          payload.indexOf("session.idle") < 0
        ) continue;
        var evt = null;
        try {
          evt = JSON.parse(payload);
        } catch (e) {
          continue; // 帧被读切两半时 JSON 不完整，等下一行（leftover 机制保证行完整）
        }
        if (!evt || !evt.type) continue;
        var sid = evt.data && evt.data.sessionID;
        var t = evt.type;
        if (t === "session.execution.started") {
          log("round start " + t);
          setBusy(sid, true);
          setBusy("__post__", false); // 真信号接管，撤乐观占位
        } else if (
          t === "session.execution.succeeded" ||
          t === "session.execution.failed" ||
          t === "session.execution.interrupted"
        ) {
          log("round end " + t);
          setBusy(sid, false);
        } else if (t === "session.status") {
          // 兜底：部分后端发 session.status（schema session-status-event.ts:9-32）
          var st = evt.data && evt.data.status && evt.data.status.type;
          if (st === "busy") {
            setBusy(sid, true);
            setBusy("__post__", false);
          } else if (st === "idle") {
            setBusy(sid, false);
          }
        } else if (t === "session.idle") {
          setBusy(sid, false); // 已废弃事件，旧后端兜底
        }
      }
    }

    // 取绝对 URL 的 origin（纯诊断用；失败时退化成 "?"，绝不抛）。
    function originOf(url) {
      try {
        return new URL(url, window.location.href).origin;
      } catch (e) {
        return "?";
      }
    }

    // 给 /api/event 响应体套透明 tee：字节原样透传，顺便扫帧 + 记录开合。
    function teeEventStream(resp, path, absUrl) {
      var body = resp.body;
      if (!body || body.__ocPowed) return;
      var id = ++streamSeq;
      var origin = originOf(absUrl || path);
      var status = resp.status;
      // P7：日志补 origin 与 status。
      // 动机（P6 §B.4 原文）：EVENT_PATH_RE 是**纯 pathname 匹配、不查 status**，
      // 所以页面 origin(4173) 被 LocalUiOriginServer 按红线 3 打成 404 + Connection: close
      // 的空转流，也一样会打出 `event stream OPEN`。P5 据此误判成"4095 真流被重连风暴
      // 截断"，P6 也差点跟着走偏 —— 缺的就是 origin 和 status 这两个字段。
      log("event stream OPEN #" + id + " " + origin + path + " status=" + status);
      if (typeof status === "number" && (status < 200 || status >= 300)) {
        // 单独再打一行非 2xx：一条 grep 即可区分「4173-404 空转」与「4095 真实流」。
        log("event stream NON2XX #" + id + " " + origin + " status=" + status);
      }
      var decoder = new TextDecoder();
      var leftover = "";
      var closed = false;
      function markClosed(reason) {
        if (closed) return;
        closed = true;
        log("event stream CLOSE #" + id + " " + origin + " " + reason);
      }
      var ts = new TransformStream({
        transform: function (chunk, ctl) {
          try {
            var text = decoder.decode(chunk, { stream: true });
            if (text) {
              var buf = leftover + text;
              var lines = buf.split(/\r?\n/);
              leftover = lines.pop(); // 行可能被读切两半，留到下一块拼回
              scanEventLines(lines);
            }
          } catch (e) {}
          ctl.enqueue(chunk);
        },
        flush: function () {
          markClosed("eof");
        },
      });
      var piped;
      try {
        piped = body.pipeThrough(ts);
      } catch (e) {
        return;
      }
      piped.__ocPowed = true;
      // 上游 abort() 走 reader.cancel，flush 不一定跑到 ⇒ 包一层 getReader 记 CLOSE。
      try {
        var origGetReader = piped.getReader.bind(piped);
        piped.getReader = function () {
          var r = origGetReader();
          if (r && typeof r.cancel === "function") {
            var origCancel = r.cancel.bind(r);
            r.cancel = function (reason) {
              markClosed("cancel");
              return origCancel(reason);
            };
          }
          return r;
        };
        Object.defineProperty(resp, "body", { value: piped, configurable: true });
      } catch (e) {}
    }

    window.fetch = function (input, init) {
      var url = "";
      var method = "GET";
      try {
        url = typeof input === "string" ? input : ((input && input.url) || "");
        method = (init && init.method) || (input && input.method) || "GET";
      } catch (e) {}
      var path = "";
      try {
        path = new URL(url, window.location.href).pathname;
      } catch (e) {
        path = url;
      }

      if (/^POST$/i.test(method) && ROUND_POST_RE.test(path)) {
        setBusy("__post__", true); // 乐观：点发送的瞬间 flag 就置起，不等首个事件
        log("round POST " + path);
      }

      return NativeFetch.apply(window, arguments).then(function (resp) {
        try {
          if (resp && EVENT_PATH_RE.test(path)) teeEventStream(resp, path, url);
        } catch (e) {}
        return resp;
      });
    };

    log("installed");
  }
  installRoundPowerSensor();

})();

  // ======================================================================
  // OC-KEYBOARD · 弹层里的"自动聚焦"不许再弹软键盘
  //
  // ## 现象（2026-10-07 产品负责人报单，本机同机型复现）
  //
  // 点作曲家的模型芯片：菜单打开的同时**软键盘自己弹起来**。菜单本身是 popper
  // 锚在芯片上的 `[data-component=menu-v2-content]`（宽 284），键盘一弹，可视高度
  // 被压掉一半 ⇒ popper 只能把菜单顶到屏幕中上部 ⇒ 看起来像"从底部往上弹的大面板"，
  // 而不是贴着芯片的下拉。**两件抱怨其实是同一个根因：键盘。**
  //
  // 设备实测（411×914 CSS，dpr 2.625；菜单里的激活元素 = `input[placeholder=Search models]`）：
  //   · 修前：`mInputShown=true`，菜单 rect {x:61, y:230, w:284, h:286}（顶到半屏）
  //   · 修后：`mInputShown=false`，菜单 rect {x:61, y:566, w:284, h:286}（紧贴芯片 y=858 上方）
  //
  // ## 机制
  //
  // 上游给菜单/对话框里的搜索框加了 `autofocus`，弹层打开时由 `onOpenAutoFocus`
  // 主动 `el.focus()`；Android WebView 只要**可编辑元素**获得焦点就拉 IME。
  //
  // ## 修法：只拦"程序化 + 在浮层里 + 不是用户点出来的"这三种条件同时成立的那一次 focus
  //
  //   ① 目标可编辑（input / textarea / contenteditable）；
  //   ② 目标位于浮层内（menu / dialog / popper / 移动抽屉）；
  //   ③ 这次 focus 不是用户刚点出来的（1s 内、且点的就是它自己或它的祖先）。
  // ⇒ 不聚焦 ⇒ 不弹键盘；菜单于是拿回整屏空间，popper 自然贴回芯片。
  // ⇒ 用户真去点输入框时走的是**浏览器原生聚焦**，我们完全不介入 ⇒ 键盘照常弹。
  //    设备实测：点搜索框 `mInputShown=true`，输入 "fl" 能过滤出 MiMo-V2.6-Flash。
  // ⇒ 主界面（作曲家、新建会话）的自动聚焦**不在范围内**，行为一字不改
  //    （设备实测：点进会话本来就不弹键盘）。
  //
  // ## 为什么不用其它写法（都试过，逐条留档）
  //
  // · `inputmode="none"`：静音期间用户点进来还要补一次 blur+focus 才能唤起 IME，
  //   于是多一套"谁在静音"的状态要维护，漏一处就变成"点了也不弹"。
  // · focus 后立刻 blur：会先弹一下再收，设备上肉眼可见闪一下。
  // · 宿主侧关 IME：WebView 只有**整页**开关，关了会把作曲家的键盘一起废掉。
  // · 监听 keydown 之类做"用户意图"识别：`InputPathHotspotTest` 明令禁止逐键监听
  //   （每次按键都在主线程上多跑一遍我们的代码）；pointerdown 是**非逐键**事件，
  //   一次交互只触发一次，所以这里只挂它。
  // ======================================================================
  var OC_OVERLAY_FOCUS_SELECTOR =
    '[role="menu"],[role="dialog"],[data-component="menu-v2-content"],' +
    '[data-slot="dialog-container"],[data-popper-positioner],[data-corvu-drawer-content]';
  var OC_FOCUS_GESTURE_MS = 1000;
  var ocLastPointer = { at: 0, target: null };

  /** 可编辑元素判定（与上游的输入控件对齐，含 contenteditable）。 */
  function ocEditable(el) {
    if (!el) return false;
    var tag = (el.tagName || "").toUpperCase();
    return tag === "INPUT" || tag === "TEXTAREA" || el.isContentEditable === true;
  }

  /** 是否位于"临时浮层"内（这些浮层的自动聚焦正是键盘自己弹起来的来源）。 */
  function ocInOverlay(el) {
    try {
      return !!(el.closest && el.closest(OC_OVERLAY_FOCUS_SELECTOR));
    } catch (e) {
      return false;
    }
  }

  /** 这次 focus 是不是用户刚点出来的（点的是它自己 / 它的祖先）。 */
  function ocFocusFromGesture(el) {
    var g = ocLastPointer;
    if (!g.target) return false;
    if (Date.now() - g.at > OC_FOCUS_GESTURE_MS) return false;
    return g.target === el || el.contains(g.target);
  }

  /**
   * 装 focus 守卫。只拦"程序化 + 浮层内 + 非用户点击"的那一次；
   * 其余一律 `orig.apply` 原样放行（含参数透传，别把 preventScroll 吃掉）。
   */
  function installOverlayFocusGuard() {
    try {
      var proto = window.HTMLElement && window.HTMLElement.prototype;
      if (!proto || proto.__ocFocusGuard) return;
      var orig = proto.focus;
      if (typeof orig !== "function") return;
      proto.__ocFocusGuard = true;
      proto.focus = function () {
        if (ocEditable(this) && ocInOverlay(this) && !ocFocusFromGesture(this)) {
          // 调试计数（设备上用 CDP 读 window.__ocFocusGuard 即可确认这条生不生效）
          try {
            window.__ocFocusGuard = window.__ocFocusGuard || { blocked: 0, last: "" };
            window.__ocFocusGuard.blocked++;
            window.__ocFocusGuard.last = String(this.getAttribute("placeholder") || this.tagName);
          } catch (e) {}
          return; // 不聚焦 ⇒ 不拉 IME
        }
        return orig.apply(this, arguments);
      };
    } catch (e) {}
  }

  // 手势记录：必须**先于**任何自动聚焦注册（文档开始），否则第一次弹层会被误判成
  // "没有手势"而静音 —— 那正是我们要的，但用户点输入框那一下必须先能被记到。
  try {
    document.addEventListener(
      "pointerdown",
      function (e) {
        ocLastPointer = { at: Date.now(), target: e.target };
      },
      true
    );
  } catch (e) {}
  installOverlayFocusGuard();

  // ======================================================================
  // OC-TERMINAL · 手机竖屏的终端列数太少（"显示太诡异"）—— 用**度量缩放**换列数
  //
  // ## 现象（2026-10-07 产品负责人报单 + 本机同机型实测）
  //
  // 竖屏终端里 PowerShell 的表格被压成"一列一个字符"（`L/e/n/g/t/h`），`PS …>ls`
  // 也从中间折断。设备实测（411×914 CSS，dpr 2.625）：
  //   · 终端面板 `[data-component=terminal]` clientWidth 394、左右各 14px padding ⇒ 内容 366
  //   · 画布 CSS 宽 343.6px；字体 IBM Plex Mono 在 **14px** 下单元格 = **8.4px**
  //   · ⇒ `cols = floor(343.6 / 8.4) = 41` **列**（PowerShell 于是把最后一列挤成 1 字符宽）
  //
  // 根因在上游：终端控件把字号**写死**成 `fontSize: 14`
  //   （`panel-*.js`: `new Terminal({… fontSize:14, fontFamily: r.font('mono') …})`），
  // 手机上 400px 宽就是 41 列。上游没有"终端字号"设置项（设置里只有字体族）。
  //
  // ## 为什么不用 CSS `zoom`（试过，会坏坐标）
  //
  // `zoom:0.75` 面板：列数确实 41→57，但控件内部所有**指针数学**都还按未缩放的
  //   布局像素算（它用 `clientX - rect.left` 除以 `metrics.width`，而 `clientX/offsetX`
  //   在缩放后仍是视觉像素）。设备实测：点在视觉 x=299.8 处，控件算出的格子是 33.4，
  //   而真实格子是 44.6 —— 正好差 0.75 倍。链接点击/拖选/滚动条命中都会偏 25%。
  //
  // ## 修法：让控件**自己**量出更小的单元格（列数变多），并把字形等比缩小
  //
  // 两条都在 CSS 像素空间里，因此指针数学**天然自洽**：
  //   ① 量字体：终端在**离屏**画布上 `measureText` 量 M/Mg 得到单元格宽高。
  //      我们把"字体族 = 终端面板的字体栈"的量字结果按比例缩小 ⇒ 控件算出更小单元格
  //      ⇒ `cols = floor(内容宽 / 单元格宽)` 自然变大，画布 CSS 尺寸仍铺满面板。
  //   ② 画字形：终端面板内的 `ctx.font` 一律把 px 乘同一个比例 ⇒ 字形与单元格同步变小，
  //      不会重叠；`metrics` 与 `getBoundingClientRect`/`offsetX` 全在同一坐标系。
  //
  // 默认窄屏 0.65（约 63 列、字形 9.1px），宽屏 1；已保存的字号对手机/平板都生效。
  // 绘制与度量共享首次创建终端时的比例，旋转和键盘 resize 只调整行列数。
  // 可选 100% / 80% / 65% / 55%，见文件末尾 OC-SETTINGS 段。
  //
  // 退化安全：上游若把 `measureText`/`ctx.font` 的用法换掉，这两处补丁**自然失效**，
  // 结果就是今天的 41 列（不会画歪、不会崩）——由 `TerminalFontScaleTest` 钉住这条性质。
  // ======================================================================
  var OC_TERM_SCALE_KEY = "oc.settings.terminalFontScale";
  var OC_TERM_SCALE_DEFAULT = 0.65;
  var OC_TERM_SCALE_CHOICES = [1, 0.8, 0.65, 0.55];

  /** 当前生效的终端字号比例（设置页可改；越界一律夹回 [0.5, 1]）。 */
  function ocTerminalScale() {
    try {
      var v = parseFloat(window.localStorage.getItem(OC_TERM_SCALE_KEY) || "");
      if (!v || !isFinite(v)) {
        return window.matchMedia && window.matchMedia("(max-width: 767px)").matches ? OC_TERM_SCALE_DEFAULT : 1;
      }
      return Math.min(1, Math.max(0.5, v));
    } catch (e) {
      return window.matchMedia && window.matchMedia("(max-width: 767px)").matches ? OC_TERM_SCALE_DEFAULT : 1;
    }
  }

  // 控件会缓存字符格子的度量；旋转/键盘 resize 只能改变行列数，不能单独改变绘制字号。
  // 设置变更会重载页面，因此度量与绘制在本页使用同一个固定比例。
  var ocAppliedTerminalScale = null;
  var ocRawMeasureText = null;
  function ocTerminalRenderScale() {
    if (ocAppliedTerminalScale === null) ocAppliedTerminalScale = ocTerminalScale();
    return ocAppliedTerminalScale;
  }

  /** 终端面板当前用的字体栈（= 上游"代码字体"设置；控件也用它）。 */
  function ocTerminalFontFamily() {
    try {
      var panel = document.querySelector('[data-component="terminal"]');
      if (!panel) return null;
      var fam = window.getComputedStyle(panel).fontFamily;
      return fam ? String(fam).trim() : null;
    } catch (e) {
      return null;
    }
  }

  /**
   * 字体栈的"通用尾巴"（去掉首选项，留 `ui-monospace, SFMono-Regular, … monospace`）。
   *
   * ⚠️ 为什么不能用整串比对（2026-10-07 设备实测踩到）：终端控件用的字体族来自
   * **上游设置里的"代码字体"**（`appearance.mono`，默认 `JetBrainsMono Nerd Font Mono`），
   * 而面板的 CSS `font-mono` 解析出来是 `"IBM Plex Mono", …` —— 首选项不同、**尾巴相同**。
   * 拿整串比对 ⇒ 量字那半永远不命中的同时画字那半照缩 ⇒ 字形比格子小（字距拉开的坏样子）。
   */
  function ocTerminalFontTail() {
    var fam = ocTerminalFontFamily();
    if (!fam) return null;
    var i = fam.indexOf(",");
    return (i >= 0 ? fam.slice(i + 1) : fam).trim() || null;
  }

  /** 该 ctx 是不是"画在终端面板里"的那支笔（画字形用）。 */
  function ocCtxInTerminal(ctx) {
    try {
      var c = ctx && ctx.canvas;
      return !!(c && c.closest && c.closest('[data-component="terminal"]'));
    } catch (e) {
      return false;
    }
  }

  /** 把字体串里的 px 乘缩放（保留 italic/bold 前缀与字体栈原文）。 */
  function ocScaleFontPx(font, scale) {
    var m = /(\d+(?:\.\d+)?)px/.exec(font);
    if (!m) return font;
    var scaled = parseFloat(m[1]) * scale;
    return font.slice(0, m.index) + scaled + "px" + font.slice(m.index + m[0].length);
  }

  function installTerminalFontScale() {
    if (window.__ocTerminalEngine === "xterm") return;
    try {
      var proto = window.CanvasRenderingContext2D && window.CanvasRenderingContext2D.prototype;
      if (!proto || proto.__ocTermScale) return;
      proto.__ocTermScale = true;

      // ① 画字形：终端面板内的 ctx.font 缩 px
      var desc = Object.getOwnPropertyDescriptor(proto, "font");
      if (desc && desc.get && desc.set) {
        Object.defineProperty(proto, "font", {
          configurable: true,
          get: function () {
            return desc.get.call(this);
          },
          set: function (value) {
            var v = value;
            try {
              if (ocCtxInTerminal(this)) v = ocScaleFontPx(String(value), ocTerminalRenderScale());
            } catch (e) {}
            desc.set.call(this, v);
          },
        });
      }

      // ② 量字体：只认"字体栈尾巴与终端面板一致"的离屏量字（终端量单元格宽高就走这里）
      var origMeasure = proto.measureText;
      if (typeof origMeasure === "function") {
        ocRawMeasureText = origMeasure;
        proto.measureText = function (text) {
          var m = origMeasure.call(this, text);
          try {
            // 面板内的 font setter 已缩放；只有离屏量字需要补乘，避免二次缩放。
            if (ocCtxInTerminal(this)) return m;
            var tail = ocTerminalFontTail();
            if (!tail) return m;
            var font = String(this.font || "");
            if (!font || font.indexOf(tail) < 0) return m;
            var z = ocTerminalRenderScale();
            if (z === 1) return m;
            return {
              width: m.width * z,
              actualBoundingBoxAscent: (m.actualBoundingBoxAscent || 0) * z,
              actualBoundingBoxDescent: (m.actualBoundingBoxDescent || 0) * z,
              fontBoundingBoxAscent: (m.fontBoundingBoxAscent || 0) * z,
              fontBoundingBoxDescent: (m.fontBoundingBoxDescent || 0) * z,
            };
          } catch (e) {
            return m;
          }
        };
      }
    } catch (e) {}
  }

  // 文档开始就装（终端控件是懒加载的，装早了才有机会拦住它的第一次量字）。
  installTerminalFontScale();

  // ======================================================================
  // OC-SETTINGS · 把"终端字号"做成设置项（设置 → 外观）
  //
  // 为什么是"注入"而不是改上游：`assets/www/ui/**` 是上游预构建产物（红线，不许动），
  // 而手机端上游**没有**终端字号设置（只有字体族；字号在控件里写死 14px）。
  //
  // 做法（尽量少碰上游）：
  //   · 锚点用上游自己的 `data-action="settings-terminal-font"`（"终端字体"那一行），
  //     **克隆整行**后改文案 + 换控件 ⇒ 排版、配色、分隔线天然一致，也不怕上游改类名；
  //   · 值存 `localStorage[oc.settings.terminalFontScale]`，由上面的度量补丁在下次
  //     创建终端时读取（终端一打开就按新字号重算列数）；
  //   · 选完**整页重载**：上游 URL 自带 `?tab=appearance`，重载后仍停在同一个面板，
  //     确定性最好（否则已挂载的终端要等重新创建才会变）。
  //   · 只在 `/settings` 路径上做检查（会话流式输出时那次 DOM 抖动不参与）。
  // ======================================================================
  var OC_TERM_SIZE_ATTR = "data-oc-term-size-row";

  /**
   * 上游的界面语言：`opencode.global.dat:language` 存的是**带 locale 字段的编码对象**
   * （见上游 `language-*.js`：`decode(localStorage[…]).locale`），没存过就跟随系统。
   */
  function ocUiLang() {
    try {
      var raw = String(window.localStorage.getItem("opencode.global.dat:language") || "");
      if (raw) {
        var m = /"locale"\s*:\s*"([^"]+)"/.exec(raw);
        if (m) return m[1].toLowerCase();
        try {
          var o = JSON.parse(raw);
          if (o && typeof o === "object" && o.locale) return String(o.locale).toLowerCase();
        } catch (e) {}
      }
      return String(window.navigator.language || "").toLowerCase();
    } catch (e) {
      return "";
    }
  }

  function ocTermTexts() {
    var zh = ocUiLang().indexOf("zh") === 0;
    return {
      title: zh ? "终端字号" : "Terminal font size",
      desc: zh
        ? "只影响手机窄屏的终端（列数随字号变化）。当前 %V%，竖屏约 %C% 列。"
        : "Narrow screens only - fewer pixels per character means more columns. Now %V%, about %C% columns here.",
      apply: zh ? "已保存，正在重新载入…" : "Saved, reloading...",
    };
  }

  /**
   * 当前字号大致能出多少列（只是给用户看的估算）。
   *
   * 设备实测口径（411×914 CSS / dpr 2.625，0.65 档 = 实测 63 列）：
   *   · 面板 clientWidth 394，去掉左右 14px padding = 366 的"内容宽"
   *   · 但控件真正排格子的是 **347**（还留了约 19px 给滚动条/内边距）
   *   · 单元格 = 8.4px × 比例 ⇒ cols = floor(347 / 5.46) = 63 ✅
   * 终端面板不在当前页（正在设置页）时，用视口宽按同样的口径推。
   */
  function ocEstimateCols(scale) {
    try {
      var panel = document.querySelector('[data-component="terminal"]');
      var grid = panel ? panel.clientWidth - 47 : window.innerWidth - 65;
      if (!grid || grid < 40) return null;
      var fam = panel
        ? String(window.getComputedStyle(panel).fontFamily || "")
        : "ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace";
      var ctx = document.createElement("canvas").getContext("2d");
      ctx.font = "14px " + fam;
      var m = ocRawMeasureText ? ocRawMeasureText.call(ctx, "M") : ctx.measureText("M");
      var baseCell = m && m.width ? m.width : 0;
      if (!baseCell || !isFinite(baseCell)) return null;
      return Math.max(1, Math.floor(grid / (baseCell * scale)));
    } catch (e) {
      return null;
    }
  }

  function ocTermSizeRowStyle() {
    var id = "oc-term-size-style";
    if (document.getElementById(id)) return;
    var st = document.createElement("style");
    st.id = id;
    st.textContent = [
      "[data-oc-term-size-row] [data-oc-term-size] { display:flex; gap:6px; flex-wrap:wrap; justify-content:flex-end; }",
      "[data-oc-term-size-row] [data-oc-term-size] > button { min-height:32px; min-width:56px; padding:0 10px; cursor:pointer;",
      "  border-radius:8px; border:1px solid var(--v2-border-border-base, rgba(128,128,128,.35));",
      "  background:var(--v2-background-bg-layer-02, rgba(128,128,128,.08)); color:var(--v2-text-text-muted, inherit);",
      "  font-size:12px; line-height:1; }",
      "[data-oc-term-size-row] [data-oc-term-size] > button[data-oc-active] {",
      "  background:var(--v2-background-bg-base, rgba(128,128,128,.2)); color:var(--v2-text-text-base, inherit);",
      "  border-color:var(--v2-border-border-strong, currentColor); font-weight:600; }",
      "[data-oc-term-size-row] [data-oc-term-size] > button:active { opacity:.7; }",
    ].join("\n");
    document.head.appendChild(st);
  }

  function ocSyncTermSizeRow(row) {
    var cur = ocTerminalScale();
    var c0 = ocEstimateCols(cur);
    var t = ocTermTexts();
    var title = row.querySelector('[data-slot="settings-row-title"]');
    var desc = row.querySelector('[data-slot="settings-row-description"]');
    if (title) title.textContent = t.title;
    if (desc) {
      desc.textContent = t.desc
        .replace("%V%", String(Math.round(cur * 100)))
        .replace("%C%", c0 === null ? "?" : String(c0));
    }
    var cur0 = row.querySelector("[data-oc-term-size]");
    if (!cur0) return;
    for (var i = 0; i < cur0.children.length; i++) {
      var b = cur0.children[i];
      var v = parseFloat(b.getAttribute("data-oc-size") || "");
      if (Math.abs(v - cur) < 0.001) b.setAttribute("data-oc-active", "");
      else b.removeAttribute("data-oc-active");
    }
  }

  function ocInjectTermSizeRow() {
    // 只在设置页做（会话页每秒都在动 DOM，别跟着凑热闹）
    if (String(window.location.pathname || "").indexOf("/settings") !== 0) return;
    var anchor = document.querySelector('[data-action="settings-terminal-font"]');
    if (!anchor) return;
    var row = anchor.closest('[data-component="settings-row"]');
    if (!row || !row.parentElement) return;

    var row_ = row.parentElement.querySelector("[" + OC_TERM_SIZE_ATTR + "]");
    if (!row_) {
      ocTermSizeRowStyle();
      row_ = row.cloneNode(true);
      row_.setAttribute(OC_TERM_SIZE_ATTR, "");
      // 克隆体里不能留上游的 data-action / 输入框（否则等于给上游塞了第二个同名字段）
      var stale = row_.querySelectorAll("[data-action]");
      for (var i = 0; i < stale.length; i++) stale[i].removeAttribute("data-action");
      var control = row_.querySelector('[data-slot="settings-row-control"]');
      if (!control) return;
      control.textContent = "";
      var wrap = document.createElement("div");
      wrap.setAttribute("data-oc-term-size", "");
      for (var j = 0; j < OC_TERM_SCALE_CHOICES.length; j++) {
        var v = OC_TERM_SCALE_CHOICES[j];
        var b = document.createElement("button");
        b.type = "button";
        b.setAttribute("data-oc-size", String(v));
        b.textContent = Math.round(v * 100) + "%";
        b.addEventListener("click", function (ev) {
          ev.preventDefault();
          ev.stopPropagation();
          var val = parseFloat(this.getAttribute("data-oc-size") || "");
          try {
            window.localStorage.setItem(OC_TERM_SCALE_KEY, String(val));
          } catch (e) {}
          var d = row_.querySelector('[data-slot="settings-row-description"]');
          if (d) d.textContent = ocTermTexts().apply;
          // 让点击反馈先落地，再整页重载（URL 带 ?tab=appearance ⇒ 回来还在这一屏）
          window.setTimeout(function () {
            try { window.location.reload(); } catch (e) {}
          }, 120);
        });
        wrap.appendChild(b);
      }
      control.appendChild(wrap);
      row.parentElement.insertBefore(row_, row.nextSibling);
    }
    ocSyncTermSizeRow(row_);
  }

  function startTermSizeRowWatch() {
    if (window.__ocTermSizeWatch) return;
    window.__ocTermSizeWatch = true;
    var queued = false;
    var tick = function () {
      queued = false;
      try {
        ocInjectTermSizeRow();
      } catch (e) {}
    };
    try {
      new MutationObserver(function () {
        if (queued) return;
        queued = true;
        window.setTimeout(tick, 300);
      }).observe(document.body, { subtree: true, childList: true });
    } catch (e) {}
    tick();
  }

  // 设置页是懒挂载的，常驻观察（内部先判路径，非设置页立刻返回）。
  if (document.body) startTermSizeRowWatch();
  else document.addEventListener("DOMContentLoaded", startTermSizeRowWatch, { once: true });

  // ======================================================================
  // OC-TERM-TOUCH · 手机上"拖动终端看历史"（产品负责人 21:21 报单）
  //
  // ### 现象与根因
  // 终端里拖不动、看不到上面的内容。根因在控件：ghostty-web 只监听 **wheel / mousedown /
  // mousemove / click**，**一个 touch 监听都没有**（`touchstart`/`touchmove` 在它的包里
  // 出现 0 次）——桌面靠滚轮、靠右侧滚动条拖动；手机既没有滚轮，手指拖动又落不到
  // 滚动条那条 8px 窄带上 ⇒ 屏幕中间怎么拖都不动。
  //
  // ### 修法：把"手指竖直拖动"翻译成控件认得的 **wheel**
  // 控件的 `handleWheel` 在 `deltaMode === DOM_DELTA_PIXEL` 时按
  // `lines = deltaY / 单元格高` 换算（实测单元格高 10.9 CSS px）⇒ 我们发 1px 的
  // deltaY 就是 1px 的滚动，**跟手**；它内部再 `smoothScrollTo()` 平滑过去。
  //
  // 方向（与控件内部语义对齐，别写反）：`viewportY = 0` 表示"贴着最新输出"
  // （控件画滚动条时 `c = viewportY/t`、`thumbY = 4 + (n-s)*(1-c)` ⇒ 0 时拇指在最下），
  // `viewportY - deltaY/行高` ⇒ **deltaY > 0 = 往最新走**。手指上滑（内容上移）应当看
  // *更新* 的内容 ⇒ `deltaY = 手指位移(向上为正)` ✅。
  //
  // ### 边界（不许踩）
  // · 只认**起点在 `[data-component="terminal"]` 内**的触摸；
  // · 位移小于 `OC_TERM_TOUCH_SLOP` 一律当轻点 ⇒ 不动 `preventDefault`，
  //   控件自己的 tap/聚焦/选择照旧（否则点一下终端就弹不出键盘了）；
  // · 只加 `touch-action:none` 到终端面板（它本来就没有原生滚动），页面别处不受影响；
  // · 逐键事件一个都不碰（`InputPathHotspotTest` 的红线）。
  // ======================================================================
  var OC_TERM_TOUCH_SLOP = 6;
  var OC_TERM_TOUCH_STYLE_ID = "oc-term-touch";

  // ---- 松手惯性（fling）参数（单位：CSS px / ms，与拖动同一套坐标）----------
  /** 起甩的最低速度：约 150 px/s。低于它当普通拖动，不继续滑。 */
  var OC_TERM_FLING_MIN = 0.15;
  /** 每 16.7ms 保留的速度比例（0.94 ⇒ 约 700ms 衰减到停下）。 */
  var OC_TERM_FLING_DECAY = 0.94;
  /** 低于这个速度就停（约 15 px/s）。 */
  var OC_TERM_FLING_STOP = 0.015;
  /** 速度采样窗口：只取最后 120ms 的位移算速度（更长会把减速段算进去）。 */
  var OC_TERM_FLING_WINDOW_MS = 120;
  /** 单帧最大时长，掉帧时别一次滑过头。 */
  var OC_TERM_FLING_MAX_FRAME_MS = 48;
  /** 一次甩动最长持续（保险丝，防止异常时一直滚）。 */
  var OC_TERM_FLING_MAX_MS = 900;

  function installTerminalTouchStyle() {
    if (document.getElementById(OC_TERM_TOUCH_STYLE_ID)) return;
    var st = document.createElement("style");
    st.id = OC_TERM_TOUCH_STYLE_ID;
    // touch-action:none 让浏览器别把这串触摸当滚动/缩放去处理（否则我们会收到 pointercancel）
    st.textContent = '[data-component="terminal"],[data-component="terminal"] canvas{touch-action:none;}';
    (document.head || document.documentElement).appendChild(st);
  }

  /**
   * 派发一个合成 wheel。
   *
   * ⚠️ 必须从**触发点（画布）**派发，不能派在面板上：控件是把监听挂在
   * `open(element)` 传来的那个节点上，而事件只向上冒泡 —— 派给它的祖先它收不到
   * （2026-10-07 第一版就踩了这个：计数器显示 wheel 到了面板、控件却毫无反应）。
   */
  function ocDispatchTerminalWheel(target, deltaY, ev) {
    try {
      var node = target;
      if (!node || !node.closest || !node.closest('[data-component="terminal"]')) return false;
      var wheel = new WheelEvent("wheel", {
        deltaY: deltaY,
        deltaMode: WheelEvent.DOM_DELTA_PIXEL,
        bubbles: true,
        cancelable: true,
        // Shift-wheel 是控件原有的本地滚动通道；触摸查看历史不伪装成 TUI 鼠标输入。
        shiftKey: true,
        clientX: ev ? ev.clientX : 0,
        clientY: ev ? ev.clientY : 0,
      });
      node.dispatchEvent(wheel);
      return true;
    } catch (e) {
      return false;
    }
  }

  function installTerminalTouchScroll() {
    if (window.__ocTerminalEngine === "xterm") return;
    if (window.__ocTermTouch) return;
    window.__ocTermTouch = true;
    installTerminalTouchStyle();

    var track = null; // {id, lastY, moved, target, samples:[{t,y}]}

    // 一次拖动会来几十个 pointermove；逐个发 wheel 会让控件的 smoothScrollTo 反复重启，
    // 实测会出现"画布只重绘了一部分"的残留（截图上 13..22 与 50..65 叠在一起）。
    // 所以**每帧只发一个** wheel（把这一帧的位移合起来），控件一次动画滚到位。
    var pendingDy = 0;
    var rafId = 0;
    var lastTarget = null;
    var lastEv = null;

    function flushWheel() {
      rafId = 0;
      var dy = pendingDy;
      pendingDy = 0;
      if (!dy || !lastTarget) return;
      ocDispatchTerminalWheel(lastTarget, dy, lastEv);
    }

    function cancelWheel() {
      if (rafId) window.cancelAnimationFrame(rafId);
      rafId = 0;
      pendingDy = 0;
      lastTarget = null;
      lastEv = null;
    }

    function queueWheel(target, dy, ev) {
      pendingDy += dy;
      lastTarget = target;
      lastEv = ev;
      if (!rafId) rafId = window.requestAnimationFrame(flushWheel);
    }

    // ---- 松手惯性（fling）------------------------------------------------
    // 手机上看长输出时，一次快速上/下滑后还应该继续滑一段并减速停下 —— 与系统列表一致。
    // 速度取"最后 120ms 窗口"内的样本（只取最后两点会被抖动放大），逐帧衰减；
    // 每帧发一个 wheel（与拖动同一条通道），所以不会和控件自己的平滑滚动打架。
    function flingVelocity(samples) {
      if (!samples || samples.length < 2) return 0;
      var last = samples[samples.length - 1];
      var first = samples[0];
      for (var i = samples.length - 1; i >= 0; i--) {
        if (last.t - samples[i].t > OC_TERM_FLING_WINDOW_MS) break;
        first = samples[i];
      }
      var dt = last.t - first.t;
      if (dt <= 0) return 0;
      // 与拖动同一套方向：手指上滑（y 变小）为正 = 往最新走
      return (first.y - last.y) / dt;
    }

    var flingRaf = 0;

    function stopFling() {
      if (flingRaf) window.cancelAnimationFrame(flingRaf);
      flingRaf = 0;
    }

    function stillInTerminal(target) {
      try {
        return !!(target && target.closest && target.closest('[data-component="terminal"]') && document.contains(target));
      } catch (e) {
        return false;
      }
    }

    function startFling(velocity, target, ev) {
      stopFling();
      if (!stillInTerminal(target)) return;
      var v = velocity;
      var startedAt = 0;
      var lastAt = 0;
      var pos = ev ? { clientX: ev.clientX, clientY: ev.clientY } : { clientX: 0, clientY: 0 };

      function step(now) {
        flingRaf = 0;
        if (!startedAt) {
          startedAt = now;
          lastAt = now;
        }
        var dt = Math.min(OC_TERM_FLING_MAX_FRAME_MS, now - lastAt); // 掉帧保护
        lastAt = now;
        // 逐帧衰减（按真实帧时长折算，掉帧时不会"多滑"）
        v *= Math.pow(OC_TERM_FLING_DECAY, dt / 16.7);
        if (Math.abs(v) < OC_TERM_FLING_STOP || now - startedAt > OC_TERM_FLING_MAX_MS) return;
        if (!stillInTerminal(target)) return;
        ocDispatchTerminalWheel(target, v * dt, pos);
        flingRaf = window.requestAnimationFrame(step);
      }

      flingRaf = window.requestAnimationFrame(step);
    }

    var touchPanel = null;
    var touchBlockUntil = 0;
    var clickBlockPanel = null;
    var clickBlockUntil = 0;

    document.addEventListener(
      "pointerdown",
      function (e) {
        stopFling(); // 手一碰就停，符合列表直觉
        cancelWheel();
        if (e.pointerType === "mouse") return;
        var t = e.target;
        var panel = t && t.closest && t.closest('[data-component="terminal"]');
        if (!panel || t.closest('button,a,input,[data-component="resize-handle"]')) return;
        var canvas = panel.querySelector("canvas");
        if (!canvas) return;
        // 上游 pointerdown 会立即 focus 并唤起 IME；延迟到确认是轻点的 pointerup。
        e.stopPropagation();
        if (e.cancelable) e.preventDefault(); // 也屏蔽触摸合成的 mousedown/文本选择
        touchPanel = panel;
        touchBlockUntil = Date.now() + 800;
        if (e.isPrimary === false) {
          track = null;
          clickBlockPanel = panel;
          clickBlockUntil = Date.now() + 800;
          return;
        }
        clickBlockPanel = null;
        track = { id: e.pointerId, lastY: e.clientY, startY: e.clientY, moved: 0, target: canvas, samples: [{ t: e.timeStamp, y: e.clientY }] };
        try { canvas.setPointerCapture(e.pointerId); } catch (err) {}
      },
      { capture: true, passive: false },
    );

    document.addEventListener(
      "pointermove",
      function (e) {
        if (!track || e.pointerId !== track.id) return;
        e.stopPropagation();
        var dy = track.lastY - e.clientY; // 手指上滑 = 正
        track.lastY = e.clientY;
        track.samples.push({ t: e.timeStamp, y: e.clientY });
        while (track.samples.length > 2 && e.timeStamp - track.samples[0].t > OC_TERM_FLING_WINDOW_MS) {
          track.samples.shift();
        }
        if (track.moved < OC_TERM_TOUCH_SLOP) {
          track.moved = Math.abs(track.startY - e.clientY);
          if (track.moved < OC_TERM_TOUCH_SLOP) return;
          dy = track.startY - e.clientY;
        }
        // 从起手的画布派发，离开画布或经过面板留白也不会丢滚动。
        queueWheel(track.target, dy, e);
        if (e.cancelable) e.preventDefault();
      },
      { capture: true, passive: false },
    );

    var stop = function (e) {
      if (!track || (e && e.pointerId !== track.id)) return;
      var t = track;
      track = null;
      touchBlockUntil = Date.now() + 800;
      e.stopPropagation();
      if (e.cancelable) e.preventDefault();
      try { if (t.target.hasPointerCapture(t.id)) t.target.releasePointerCapture(t.id); } catch (err) {}
      if (!e || e.type !== "pointerup") {
        cancelWheel();
        clickBlockPanel = touchPanel;
        clickBlockUntil = Date.now() + 800;
        return;
      }
      // 结束帧先交付拖动位移，惯性随后开始；不会遗留上一手势的 rAF。
      if (rafId) window.cancelAnimationFrame(rafId);
      flushWheel();
      if (t.moved < OC_TERM_TOUCH_SLOP) {
        var input = touchPanel && touchPanel.querySelector("textarea");
        if (input) input.focus({ preventScroll: true });
        return;
      }
      clickBlockPanel = touchPanel;
      clickBlockUntil = Date.now() + 800;
      // 松手也必须采样：拖完停住再放手不会拿旧速度误起甩。
      t.samples.push({ t: e.timeStamp, y: e.clientY });
      var v = flingVelocity(t.samples);
      if (Math.abs(v) >= OC_TERM_FLING_MIN) startFling(v, t.target, e);
    };
    document.addEventListener("pointerup", stop, true);
    document.addEventListener("pointercancel", stop, true);
    document.addEventListener("lostpointercapture", stop, true);

    // 控件自身的 touchend 也会 focus；轻点已在 pointerup 聚焦，拖动不应弹键盘。
    document.addEventListener("touchend", function (e) {
      if (!touchPanel || Date.now() > touchBlockUntil || !touchPanel.contains(e.target)) return;
      if (e.cancelable) e.preventDefault();
      e.stopImmediatePropagation();
    }, { capture: true, passive: false });
    document.addEventListener("click", function (e) {
      if (!clickBlockPanel || Date.now() > clickBlockUntil || !clickBlockPanel.contains(e.target)) return;
      e.preventDefault();
      e.stopImmediatePropagation();
    }, true);
  }

  installTerminalTouchScroll();
