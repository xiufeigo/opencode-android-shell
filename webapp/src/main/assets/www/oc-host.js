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
  // BACK：面板打开时先关面板（缺陷 B）
  //
  // 根因（F18 实测，WebHostActivity.kt:768 改前）：宿主是
  //     if (webView.canGoBack()) { webView.goBack(); return }
  //     ... else 问页面 onBack()
  // 即 **canGoBack 优先**。而上游 SPA 用 History API 做站内导航 ⇒ 打开会话时
  // 已经压了一条 history ⇒ 面板打开态按 BACK 时 canGoBack()==true，
  // 于是直接 goBack() 把 SPA 路由退回首屏，**页面自己的 onBack 根本没被问到**
  // ⇒ popover 没关、还退了两级。已改为"先问页面、再退历史"。
  //
  // 页面侧策略：**不自己实现关闭逻辑**，而是派发一个真实的 Escape。
  // 依据：桌面浏览器里这个 popover 就是靠 Esc 关闭的（V13 §缺陷 D4 的对照），
  // Radix 的 dismissable layer 原生监听 Escape ⇒ 走的是上游自己的关闭路径，
  // 行为与桌面端逐字一致，也不会与上游的状态机打架。
  // ======================================================================
  // 打开中的浮层选择器。
  // ⚠️ **故意不含 [role='dialog']**（F18 真机实测踩到的坑）：上游常驻预挂载一个
  // role="dialog" 的底部 sheet（实测 top=780 / h=205，而视口高 780 ⇒ 恰在视口外）。
  // 它不属于 BACK 该消费的"临时浮层"，把它算进来会让 count 在面板关闭后仍 ≥0，
  // 于是**第二次** BACK 也被吃掉 ⇒ 永远退不回会话列表（实测复现）。
  // 面板本体是 Radix 的 **menu** popper（触发器 aria-haspopup="true"、实测
  // "More options" 按钮 aria-expanded 随开合 true/false），下面的选择器足以覆盖。
  var OVERLAY_OPEN_SELECTORS = [
    "[data-radix-popper-content-wrapper]",
    "[data-state='open']",
    "[role='menu']",
    "[role='listbox']",
    ":popover-open",
  ].join(",");

  /**
   * 触发器自报"我开着"—— Radix 打开 popover 时把触发器的 aria-expanded 置 true、
   * 关闭时置回 false。这是**上游自己给的权威信号**，比任何选择器猜测都可靠。
   */
  function hasExpandedTrigger() {
    try {
      return (
        document.querySelectorAll("[aria-haspopup][aria-expanded='true']").length > 0
      );
    } catch (e) {
      return false;
    }
  }

  function overlayCount() {
    try {
      var vh = window.innerHeight || 0;
      var nodes = document.querySelectorAll(OVERLAY_OPEN_SELECTORS);
      var n = 0;
      for (var i = 0; i < nodes.length; i++) {
        var el = nodes[i];
        var r = el.getBoundingClientRect();
        var w = r.width || el.offsetWidth || 0;
        var h = r.height || el.offsetHeight || 0;
        // **只看视口内真正可见的**（零尺寸/离屏的挂载节点不算"打开"）。
        if (w > 0 && h > 0 && r.top < vh && r.bottom > 0) n++;
      }
      return n;
    } catch (e) {
      return 0;
    }
  }

  /** 面板/popover 打开态按 BACK：派发 Escape 让上游自己关。返回是否消费了这次 BACK。 */
  function closeTopOverlayOnBack() {
    // 判"面板开着"用**触发器自报**（aria-haspopup + aria-expanded=true）为准，
    // 可见浮层数为辅。触发器信号是上游自己的契约，面板一关就立刻回到 false，
    // 不会像"选择器命中数"那样被常驻挂载节点长期钉住。
    if (!hasExpandedTrigger() && overlayCount() === 0) return false;
    try {
      var target = document.activeElement || document.body;
      ["keydown", "keyup"].forEach(function (type) {
        target.dispatchEvent(
          new KeyboardEvent(type, {
            key: "Escape",
            code: "Escape",
            keyCode: 27,
            which: 27,
            bubbles: true,
            cancelable: true,
            composed: true,
          })
        );
      });
      return true;
    } catch (e) {
      return false;
    }
  }

  /** 注册默认 BACK 处理器；页面自己注册过 setBackHandler 时以页面的为准。 */
  function installDefaultBackHandler() {
    oc.setBackHandler(function () {
      return closeTopOverlayOnBack();
    });
  }


  // ======================================================================
  // IME 抬升（composer 被键盘完全遮挡的修法）
  //
  // 前提事实（不要改坏）：
  // - AndroidManifest 已声明 windowSoftInputMode="adjustResize"；
  // - 但 InsetsHost.init 调了 WindowCompat.setDecorFitsSystemWindows(window, false)
  //   ⇒ 边到边。API 30+ 上边到边窗口不再因 IME 压矮，adjustResize 的效果被抵消
  //   （InsetsHost.applyResize 又被 "lp.height != -1" 守卫挡住，对 MATCH_PARENT
  //     的 WebView 永远不生效）⇒ **布局视口不随键盘变矮**。
  // - 宿主仍然把**实测正确**的 IME 高度交到 --oc-ime-bottom（IME1 实测
  //   336.38 CSS px × dpr 2.625 = 883px = type=ime frame 高度，完全一致）。
  // - 上游 SPA 不认识 --oc-ime-*，也不消费 env(safe-area-inset-bottom)
  //   （WebView 边到边 ⇒ env() 恒为 0）⇒ composer 停在视口底部、被键盘盖住。
  //
  // 所以修法落在**页面消费侧**：由宿主注入层给 composer 容器垫 --oc-ime-bottom。
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
      "form:has(textarea),form:has([contenteditable=\"true\"]){",
      "padding-bottom:var(--oc-ime-bottom,0px);",
      "box-sizing:border-box;",
      "transition:padding-bottom .12s ease;",
      "}",
      // 兜底：若 composer 不是 <form>，退到"最近 fixed/sticky 祖先"上抬。
      "[data-oc-ime-lift]{padding-bottom:calc(var(--oc-ime-bottom,0px) + var(--oc-safe-bottom-eff,0px));",
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
      if (typeof backHandler === "function") {
        try {
          return !!backHandler();
        } catch (e) {
          return false;
        }
      }
      return false;
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
  }

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
        installDefaultBackHandler();
        if (lastInsets) doApply(lastInsets);
        window.dispatchEvent(new CustomEvent("oc:ready", { detail: { version: 1 } }));
      },
      { once: true }
    );
  } else {
    installImeLiftStyle();
    installTopInsetStyle();
    installDefaultBackHandler();
    window.dispatchEvent(new CustomEvent("oc:ready", { detail: { version: 1 } }));
  }
  // focusin 委托：composer 是 SPA 动态挂载的，不能只绑一次。
  document.addEventListener("focusin", onImeFocusIn, true);
  // insets 每变一次都刷新一次（IME 收起时 --oc-ime-bottom 归 0，留白自动撤掉）。
  if (lastInsets) doApply(lastInsets);

  // UX1 任务3：右侧栏「+」→ 弹出「打开终端 / 打开文件」选择面板。
  // ⚠ 为什么**内联**在这里、而不是 fetch 外部文件再 eval：
  //   实测上游官方 UI 的 CSP 是
  //     script-src 'self' 'wasm-unsafe-eval' 'sha256-...'
  //   ——**没有 'unsafe-eval'**，所以 fetch 得到 200（1809 B）后 eval 仍被拒：
  //     "Refused to evaluate a string as JavaScript because 'unsafe-eval' is not
  //      an allowed source of script"（logcat 原文，2026-10-05 07:43:49）。
  //   而本文件是**宿主用 addDocumentStartJavaScript 注入的文档开始脚本**，
  //   不受页面 CSP 约束 ⇒ 内联是唯一可靠的路。
  installPlusChooser();
})();

  /**
   * UX1 任务3 · 右侧栏「+」的选择面板（终端 / 文件）。
   *
   * 上游那个「+」在 shell/titlebar/titlebar.tsx:621-629：`icon={<Icon name="plus" />}
   * onClick={openNewTab} aria-label={language.t("command.session.new")}`，
   * `openNewTab`（:271）**直接新建会话**，没有选择层。
   *
   * 不复用上游 `DialogCommandPalette`（shell/commands/dialog.tsx:30）的理由：
   * 它的入口是 Solid 的 `dialog.show(...)`（home/sessions/controller.tsx:119-121、
   * new-session/commands.tsx:27-29），依赖 useCommand/useGlobal/useTabs 等一整套
   * 响应式宿主（shell/commands/palette.ts:82-92），WebView 从外部没有任何可达入口。
   *
   * 一致性原则：**不替换上游任何行为**，只在点击**之前**加一层选择；
   * 选完再走上游/宿主原路径。
   */
  function installPlusChooser() {
    if (window.__ocPlusChooser) return;
    var MIN_TAP_PX = 48;
    var panel = null;
    var armed = false;

    function log() {
      try {
        console.log("[oc-plus-chooser] " + Array.prototype.join.call(arguments, " "));
      } catch (e) {}
    }

    /* 上游是 SPA，节点会重挂 ⇒ 每次重新查，不缓存节点引用。
     * 判据：内含 svg（上游按钮都是 Icon），且 aria-label/文本命中"新建会话"语义。 */
    function findPlusButton() {
      var nodes = document.querySelectorAll("button,[role=button]");
      for (var i = 0; i < nodes.length; i++) {
        var el = nodes[i];
        if (el.getAttribute("data-oc-plus-chooser") === "1") continue;
        var label = (el.getAttribute("aria-label") || el.textContent || "").trim();
        if (!el.querySelector("svg")) continue;
        if (!/新建会话|新会话|new session/i.test(label)) continue;
        if (el.offsetParent === null) continue;
        return el;
      }
      return null;
    }

    function closePanel() {
      if (panel && panel.parentNode) panel.parentNode.removeChild(panel);
      panel = null;
    }

    function buildPanel(anchor) {
      var r = anchor.getBoundingClientRect();
      var vw = window.innerWidth || 0;
      var vh = window.innerHeight || 0;
      var w = Math.min(300, Math.max(220, vw - 24));
      var left = Math.max(12, Math.min(r.left, vw - w - 12));
      var h = 2 * MIN_TAP_PX + 46;
      var top = r.bottom + 8;
      if (top + h > vh - 12) top = Math.max(12, r.top - h - 8);

      var p = document.createElement("div");
      p.setAttribute("data-oc-plus-chooser", "panel");
      p.setAttribute("role", "menu");
      p.setAttribute("aria-label", "选择要打开的内容");
      p.style.cssText = [
        "position:fixed", "z-index:2147483000",
        "left:" + left + "px", "top:" + top + "px", "width:" + w + "px",
        "background:#18181b", "border:1px solid #2e2e35", "border-radius:10px",
        "box-shadow:0 10px 30px rgba(0,0,0,.55)", "padding:6px",
        "font:14px/1.4 -apple-system,Roboto,'Noto Sans CJK SC',sans-serif", "color:#e8e8ea",
      ].join(";");

      var title = document.createElement("div");
      title.textContent = "打开";
      title.style.cssText = "font-size:11px;color:#8b8b95;padding:6px 10px 4px";
      p.appendChild(title);

      [
        { label: "打开终端", act: "terminal" },
        { label: "打开文件", act: "file" },
      ].forEach(function (item) {
        var b = document.createElement("button");
        b.type = "button";
        b.setAttribute("role", "menuitem");
        b.setAttribute("data-oc-plus-chooser", item.act);
        b.textContent = item.label;
        b.style.cssText = [
          "display:flex", "align-items:center", "width:100%",
          "min-height:" + MIN_TAP_PX + "px",
          "border:0", "border-radius:8px", "background:transparent", "color:#e8e8ea",
          "font-size:15px", "text-align:left", "padding:0 10px", "cursor:pointer",
        ].join(";");
        b.addEventListener("click", function (e) {
          e.preventDefault();
          e.stopPropagation();
          closePanel();
          run(item.act);
        });
        p.appendChild(b);
      });

      document.body.appendChild(p);
      panel = p;
      return p;
    }

    /* 打开终端：优先点**上游自己的**终端触发器（保持上游行为，不另造一条路）。
     * 找不到就派发事件并如实标记 found:false —— 不假装成功。 */
    function runTerminal() {
      var cands = document.querySelectorAll(
        "[data-slot*=terminal], [aria-label*=erminal], button[title*=erminal], [data-oc-terminal]"
      );
      for (var i = 0; i < cands.length; i++) {
        if (cands[i].offsetParent !== null) {
          cands[i].click();
          log("terminal trigger clicked");
          return true;
        }
      }
      window.dispatchEvent(new CustomEvent("oc:open-terminal", { detail: { source: "plus-chooser", found: false } }));
      log("no upstream terminal trigger found; dispatched oc:open-terminal");
      return false;
    }

    /* 打开文件：走宿主**原生**文件选择器（WebHostBridge.pickFile），可验证的真动作。 */
    function runFile() {
      try {
        var b = window.__ocAndroid;
        if (b && b.pickFile) {
          b.pickFile("*/*", false);
          log("file picker requested via native bridge");
          return true;
        }
      } catch (e) {
        log("pickFile failed: " + (e && e.message));
      }
      window.dispatchEvent(new CustomEvent("oc:open-file", { detail: { source: "plus-chooser", found: false } }));
      return false;
    }

    function run(act) {
      if (act === "terminal") runTerminal();
      else if (act === "file") runFile();
    }

    function onClick(e) {
      if (panel) { closePanel(); return; }
      var btn = findPlusButton();
      if (!btn) return;
      if (!(e.target === btn || btn.contains(e.target))) return;
      e.preventDefault();
      e.stopPropagation();
      armed = true;
      buildPanel(btn);
      log("plus intercepted; chooser shown");
    }

    function onKey(e) {
      // ⚠️ **刻意不注册** Esc 关闭：`InputPathHotspotTest.host injection has no
      // per-keystroke listeners` 会把任何 keydown 监听判为"每次按键都在主线程上多跑一遍
      // 我们的代码"。面板点空白处即可关闭（onClick 里已处理），不值得为 Esc 付这个代价。
      // ⇒ 这里**故意留空**，并由该测试守着不被后人加回来。
    }

    window.__ocPlusChooser = {
      installed: true,
      findPlusButton: findPlusButton,
      open: function () { var b = findPlusButton(); if (b) buildPanel(b); },
    };

    function arm() {
      if (armed) return;
      armed = true;
      document.addEventListener("click", onClick, true);
      // ⚠ 故意**不**加 keydown（理由见 onKey 的注释；点空白处即可关闭）。
      log("armed");
    }

    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", arm, { once: true });
    } else {
      arm();
    }
  }
