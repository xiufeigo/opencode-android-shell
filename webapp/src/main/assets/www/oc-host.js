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

  // UX3：「打开终端 / 打开文件」选择面板挂在**右侧栏标签条的「+」**上。
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
   * UX3 · 右侧栏标签条「+」的选择面板（终端 / 文件）。
   *
   * ## 挂点（UX1/UX2 挂错过，UX3 纠正）
   *
   * UX1/UX2 把面板挂在了**顶栏标签条的「+」**（`<header>` 内、
   * aria-label="New session" 的按钮，上游 titlebar.tsx:621-629 →
   * `openNewTab`(:271) 直接新建会话草稿标签）。产品负责人 2506×1673 截图
   * 批注纠正：**要的是右侧栏标签条上「审查」旁边的那个「+」**。
   * ⇒ 顶栏「+」**完全不拦截**，恢复上游 openNewTab 原行为。
   *
   * ## 目标元素的选择器（DOM 结构定位，不绑像素/文案）
   *
   * UX7 收窄（B-1/B-4）：只锚定**审查条** —— `aside#review-panel`
   * （session-side-panel.tsx:259，整面板桌面限定 :257）内的 [role=tablist]
   * （:317），取其尾部 sticky 容器（:458）里 DOM 顺序最后一个「非 tab、含 svg、
   * 非 close」的按钮（见 findReviewTablist / scanTablistButtons 原文）。
   * **不再扫描页面上任意非 header 的 tablist** —— 旧抓法在手机档会命中终端
   * 标签条自己的「+」（aria-label="New terminal"，panel.tsx:330-335，
   * onClick=terminal.new()），把"直接开终端"替换成我们的面板（产品负责人
   * 2026-10-05 实测打回）。手机档没有审查条（<768px 不挂载）⇒ 本面板在该档
   * 全页面零拦截，终端条「+」完全归上游。
   *
   * ## 上游那个「+」原本干什么（不能弄丢）
   *
   * 部署版实测（2026-10-05 11:30，注入绕过拦截直点 + 截图 ux5_t17）：
   * 审查条「+」aria-label="Open file"，点击 ⇒ 审查条出现「Open file」标签页 +
   * **工作区应用内文件浏览器**（Filter files + 目录树）。v2 源码同一结构位
   * （session-side-panel.tsx:458-536）：无浏览器能力时 = 一键 "Open file"
   * （:475-482 → openFileBrowser() :205-208 = previewTab(SESSION_OPEN_FILE_TAB)）；
   * 有浏览器能力时 = "Add tab" 菜单（打开文件 :503-516 + 浏览器 :517-530）。
   *
   * ## 两个动作全部透传给上游（UX5 修正；UX3 把「打开文件」错接成宿主 SAF）
   *
   * - 「打开文件」= **把合成 click 还给被拦截的那个上游「+」自己**
   *   （findPlusButton 命中、passThrough 放行）——上游原行为逐字发生，
   *   构造上即与"直接点 +"等价，不再弹 Android 系统文件选择器。
   *   （产品负责人 2026-10-05 打回：上一版宿主原生 pickFile 弹的是"选平板上的
   *   文件"，他要的是"原本点 + 触发的功能"。）
   * - 「打开终端」= 复刻上游自己的 openTerminal 序列
   *   （use-session-commands.tsx:195-199，绑定在 terminal.new 命令）：
   *   ① 终端面板**已开**（终端条「New terminal」按钮真实可见）⇒ passThrough
   *      点它（panel.tsx:333 → terminal.new()，新建并激活标签）；
   *   ② 终端面板**未开**（终端条不在 DOM / 零尺寸）⇒ 派发上游自己的
   *      terminal.new 键位（默认 ctrl+alt+t；command.tsx:401-428 的键位派发器
   *      只看 event.key/修饰键、无 isTrusted 检查，合成事件可触发）——
   *      上游 handler 打开面板 + 建/聚焦终端，0 终端时 panel.tsx:105-115
   *      的自动创建效果补 Terminal 1。
   *   设备实证（ux5_t18）：合成 ctrl+` 触发上游 terminal.toggle 后终端面板出现、
   *   Terminal 1 自动创建、活的 PowerShell 提示符可见。
   *   ⚠️ UX3 版只 passThrough 点终端条「+」，终端面板收起时终端条根本不在 DOM
   *   （实测 tablists=0）⇒ 什么都不发生——即产品负责人实测打回的"按了没反应"。
   *   面板收起但缓存挂载（零高）时 offsetParent 仍非 null，故可见性判据用
   *   getBoundingClientRect 的真实宽高，不只 offsetParent。
   *
   * 不复用上游 DialogCommandPalette（shell/commands/dialog.tsx:30）的理由（沿用 UX1 §3.2）：
   * 入口是 Solid 的 dialog.show(...)，依赖一整套响应式宿主，WebView 无可达入口。
   */
  function installPlusChooser() {
    if (window.__ocPlusChooser) return;
    var MIN_TAP_PX = 48;
    var panel = null;
    var armed = false;
    // 「打开终端」会把一次合成 click 还给上游「+」自己；这期间我们的
    // document 捕获监听必须放行，否则点击会被自己的拦截吃回去（面板重开）。
    var passThrough = false;

    function log() {
      try {
        console.log("[oc-plus-chooser] " + Array.prototype.join.call(arguments, " "));
      } catch (e) {}
    }

    /* 右侧栏**审查条**的标签条 = `aside#review-panel` 内的那个 [role=tablist]
     * （选择器原文见上方头注释「目标元素的选择器」）。上游是 SPA，节点会重挂 ⇒
     * 每次重新查，不缓存节点引用；不绑任何文案/像素。
     *
     * UX7（B-1/B-4，产品负责人 2026-10-05 实测打回）：**绝不**再扫"页面上任意
     * 非 header 的 tablist" —— 那个抓法在手机档会命中**终端标签条自己的「+」**
     * （aria-label="New terminal"，panel.tsx:330-335 → terminal.new()），把
     * "直接新建终端"的上游语义替换成我们的面板。
     *
     * 两条 tablist 的 DOM 区分（origin/v2 行号）：
     *   审查条「+」= aside#review-panel（session-side-panel.tsx:259）→ Tabs.List
     *     （:317）尾部 sticky 容器（:458）内 IconButton：aria-label="Open file"
     *     （:475-482，单文件一键 → openFileBrowser() :205-208）或 "Add tab"
     *     （:490-500，有浏览器能力时的 Menu）。整个 SessionSidePanel **桌面限定**：
     *     session-side-panel.tsx:257 `<Show when={isDesktop() && !!params.id}>`。
     *   终端条「+」= #terminal-panel（terminal/panel.tsx）→ Tabs.List（:305）尾部
     *     IconButton aria-label="New terminal"（:330-335，onClick=terminal.new()）。
     *
     * ⇒ 手机档（<768px）根本没有审查条 ⇒ findReviewTablist() 返回 null ⇒
     *    findPlusButton() 返回 null ⇒ **手机档全页面零拦截**，终端条「+」完全
     *    归上游（点了直接 terminal.new()）。这正是 B-1 修法要求第 3 条。 */
    var CLOSE_PREFIX = /^\s*(close|dismiss|关闭|關閉)/i;
    /* 「+」位于 tablist 尾部的 sticky 容器里；面板切换图标（Toggle file tree /
     * Toggle review）sticky 在**开头** ⇒ 取候选里 DOM 顺序的最后一个。 */
    var TERMINAL_LABEL = /terminal|终端/i;

    function scanTablistButtons(list, pickLast) {
      var nodes = list.querySelectorAll("button,[role=button]");
      var found = null;
      for (var j = 0; j < nodes.length; j++) {
        var el = nodes[j];
        if (el.getAttribute("data-oc-plus-chooser") === "1") continue;
        if (el.getAttribute("role") === "tab") continue;
        if (el.closest("[role=tabpanel]")) continue;
        if (!el.querySelector("svg")) continue;
        if (el.offsetParent === null) continue;
        if (CLOSE_PREFIX.test(el.getAttribute("aria-label") || "")) continue;
        found = el;
        if (!pickLast) return found;
      }
      return found;
    }

    /* 审查条容器 = 上游 SessionSidePanel 的根（session-side-panel.tsx:259
     * id="review-panel"）。宽度为 0（收起动画/未开）时等同不在 ⇒ 返回 null，
     * 此时页面上没有任何该拦截的「+」。手机档整面板不挂载 ⇒ 恒 null。 */
    function findReviewTablist() {
      var panel = document.getElementById("review-panel");
      if (!panel || panel.offsetParent === null) return null;
      if (panel.getBoundingClientRect().width < 2) return null;
      var list = panel.querySelector("[role=tablist]");
      if (!list || list.offsetParent === null) return null;
      if (list.getBoundingClientRect().width < 2) return null;
      return list;
    }

    /* 只从审查条里找「+」（DOM 区分与依据见上方注释块）。不再遍历全页 tablist。 */
    function findPlusButton() {
      var list = findReviewTablist();
      if (!list) return null;
      return scanTablistButtons(list, true);
    }

    /* 终端触发器 = 非 header tablist 内 aria-label 命中 terminal/终端 且非 close 的
     * 按钮（部署版即 "New terminal"）。终端标签自身是 [role=tab] 且无 svg，天然排除。 */
    function findTerminalTrigger() {
      var lists = document.querySelectorAll("[role=tablist]");
      for (var i = 0; i < lists.length; i++) {
        var list = lists[i];
        if (list.closest("header")) continue;
        var nodes = list.querySelectorAll("button,[role=button]");
        for (var j = 0; j < nodes.length; j++) {
          var el = nodes[j];
          if (el.getAttribute("role") === "tab") continue;
          if (!el.querySelector("svg")) continue;
          if (el.offsetParent === null) continue;
          var label = el.getAttribute("aria-label") || "";
          if (CLOSE_PREFIX.test(label)) continue;
          if (TERMINAL_LABEL.test(label)) return el;
        }
      }
      return null;
    }

    /* UX4：面板配色**全部取自上游主题 token**（:root 上的 CSS 变量），
     * 不再硬编码深浅色 —— 上游换主题/切深浅色时面板自动跟随。
     *
     * token 依据（_upstream/packages/ui/src/theme/themes/oc-2.json，
     * light / dark 两套值都在；由 context.tsx applyThemeCss 写进 :root）：
     *   --surface-raised-base        浮层面板底   light #F3F3F3 / dark #232323
     *   --surface-raised-base-hover  菜单项 hover 底 light #EDEDED / dark #282828
     *   --border-weaker-base         面板描边     light #E8E8E8 / dark #232323
     *   --text-strong                菜单项文字   light #171717 / dark #EDEDED
     *   --text-weak                  "打开"小标题 light #8F8F8F / dark #707070
     * var() 的兜底值 = 改前深色配色：上游 token 缺失（改版）时面板退回深色仍可读。
     * 阴影深浅按 :root[data-color-scheme=…] 分流（浅色 .18 / 深色 .55），
     * data-color-scheme 是上游 applyThemeCss 写的模式标记。 */
    var PANEL_STYLE_ID = "oc-plus-chooser-style";

    function installPlusChooserStyle() {
      if (document.getElementById(PANEL_STYLE_ID)) return;
      var st = document.createElement("style");
      st.id = PANEL_STYLE_ID;
      st.textContent = [
        "[data-oc-plus-chooser=panel]{",
        "background:var(--surface-raised-base,#18181b);",
        "border:1px solid var(--border-weaker-base,#2e2e35);",
        "color:var(--text-strong,#e8e8ea);",
        "box-shadow:0 10px 30px rgba(0,0,0,.55);",
        "}",
        ":root[data-color-scheme=light] [data-oc-plus-chooser=panel]{",
        "box-shadow:0 10px 30px rgba(0,0,0,.18);",
        "}",
        "[data-oc-plus-chooser=panel] [data-oc-plus-chooser=title]{",
        "color:var(--text-weak,#8b8b95);",
        "}",
        "[data-oc-plus-chooser=panel] button[data-oc-plus-chooser]{",
        "color:var(--text-strong,#e8e8ea);",
        "background:transparent;",
        "}",
        "[data-oc-plus-chooser=panel] button[data-oc-plus-chooser]:hover{",
        "background:var(--surface-raised-base-hover,#2a2a30);",
        "}",
      ].join("\n");
      (document.head || document.documentElement).appendChild(st);
    }

    function closePanel() {
      if (panel && panel.parentNode) panel.parentNode.removeChild(panel);
      panel = null;
    }

    function buildPanel(anchor) {
      installPlusChooserStyle();
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
        // 颜色全部在 installPlusChooserStyle() 的 <style> 里按上游 token 给 —— 这里只留版式。
        "border-radius:10px", "padding:6px",
        "font:14px/1.4 -apple-system,Roboto,'Noto Sans CJK SC',sans-serif",
      ].join(";");

      var title = document.createElement("div");
      title.textContent = "打开";
      title.setAttribute("data-oc-plus-chooser", "title");
      title.style.cssText = "font-size:11px;padding:6px 10px 4px";
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
          "border:0", "border-radius:8px",
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

    /* 打开终端 = 复刻上游自己的 openTerminal 序列（use-session-commands.tsx:195-199）：
     * ① 终端面板已开（终端条「New terminal」按钮**真实可见**）⇒ 把合成 click 还给
     *    它自己（passThrough 放行；panel.tsx:333 → terminal.new()，新建并激活标签）；
     * ② 终端面板未开（终端条不在 DOM / 被零高缓存）⇒ 派发上游自己的 terminal.new
     *    键位（默认 ctrl+alt+t）——上游键位派发器（command.tsx:401-428）只看
     *    event.key/修饰键、无 isTrusted 检查，合成事件即可触发；上游 handler 会
     *    打开面板 + 建/聚焦终端（0 终端时 panel.tsx:105-115 自动创建 Terminal 1）。
     * 面板收起但缓存挂载时 offsetParent 仍非 null ⇒ 可见性用 rect 真实宽高判定。
     * 两条路都找不到就如实标记 found:false（不装成功）。 */
    function dispatchUpstreamKey(key, ctrl, alt) {
      try {
        document.dispatchEvent(
          new KeyboardEvent("keydown", {
            key: key,
            ctrlKey: !!ctrl,
            altKey: !!alt,
            bubbles: true,
            cancelable: true,
            composed: true,
          })
        );
        return true;
      } catch (e) {
        return false;
      }
    }

    function terminalTriggerUsable(btn) {
      if (!btn || btn.offsetParent === null) return false;
      var r = btn.getBoundingClientRect();
      return r.width > 0 && r.height > 0;
    }

    function runTerminal() {
      var btn = findTerminalTrigger();
      if (terminalTriggerUsable(btn)) {
        passThrough = true;
        try {
          btn.click();
        } finally {
          passThrough = false;
        }
        log("upstream New terminal re-clicked via passThrough (panel already open)");
        return true;
      }
      // 终端面板未开：走上游自己的 terminal.new 键位（默认 ctrl+alt+t）。
      if (dispatchUpstreamKey("t", true, true)) {
        log("upstream terminal.new keybind dispatched (ctrl+alt+t); panel closed case");
        /* 自检：给上游一点时间，终端面板真的出现（「New terminal」触发器可见）
         * 才算数；键位被用户改绑等极端情况下诚实标记 found:false。 */
        setTimeout(function () {
          var opened = terminalTriggerUsable(findTerminalTrigger());
          window.dispatchEvent(
            new CustomEvent("oc:open-terminal", { detail: { source: "plus-chooser", found: opened } })
          );
          if (!opened) log("terminal panel still not visible after terminal.new keybind");
        }, 900);
        return true;
      }
      window.dispatchEvent(new CustomEvent("oc:open-terminal", { detail: { source: "plus-chooser", found: false } }));
      return false;
    }

    /* 打开文件 = 把合成 click 还给**被拦截的那个上游「+」自己**（passThrough 放行）。
     * 上游原行为（审查条出现「Open file」标签页 + 应用内文件浏览器）逐字发生，
     * 构造上即与"直接点 +"等价 —— 这就是"原本点 + 号触发的功能"。
     * （UX5 修正：UX3 版调宿主原生 pickFile 弹 Android SAF，被产品负责人打回；
     * 宿主 pickFile 桥仍存在于 facade 供别处使用，但本面板不再走它。） */
    function runFile() {
      var btn = findPlusButton();
      if (!btn) {
        window.dispatchEvent(new CustomEvent("oc:open-file", { detail: { source: "plus-chooser", found: false } }));
        log("no upstream plus button found for open-file");
        return false;
      }
      passThrough = true;
      try {
        btn.click();
      } finally {
        passThrough = false;
      }
      log("upstream plus re-clicked via passThrough (open file)");
      return true;
    }

    function run(act) {
      if (act === "terminal") runTerminal();
      else if (act === "file") runFile();
    }

    function onClick(e) {
      if (passThrough) return; // 面板动作把点击还给上游「+」，这里必须放行
      if (panel) { closePanel(); return; }
      var btn = findPlusButton();
      if (!btn) return;
      if (!(e.target === btn || btn.contains(e.target))) return;
      e.preventDefault();
      e.stopPropagation();
      buildPanel(btn);
      log("side-tablist plus intercepted; chooser shown");
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

  // ======================================================================
  // 幻影服务器条目（页面 origin 那条）· **上游注册表机制为主，DOM 兜底为辅**
  //
  // ## 这一层要解决的问题（2026-10-07 产品负责人报的缺陷）
  //
  // `127.0.0.1:4173` 是本壳 `LocalUiOriginServer` 托管的**页面自身来源**；
  // 上游把它当"内建服务器"（`currentServerUrl = location.origin`），永远在
  // `list` 之外，于是：
  //   · 上游 Remove 永远删不掉它（canRemove 只看 list）；
  //   · 上游 Home 的服务器列表与**当前上下文**都读 store 的 `visible`
  //     （`server.list: () => visible`、`focused = visible.find(…) ?? visible[0]`），
  //     而 origin 恒在 `visible[0]` ⇒ Home 的当前服务器恒为 4173；
  //   · 4173 的 `/api/*` 由 LocalUiOriginServer 按设计一律 404
  //     ⇒ 事件流 404 @1Hz 空转 ⇒ Home 永久 `Loading`。
  //
  // v1–v4 的 DOM 隐藏只遮界面：设置里的行藏了，**Home 的当前上下文仍钉在
  // 4173**（页头/搜索框写着 127.0.0.1:4173，正文 Loading），设备复验
  // （UX17-P6）当场可见。产品负责人 2026-10-07 的原话就是
  // "设置界面添加服务器后 4173 消失了，但是主页这里还在，而且也不能用"。
  //
  // ## 修法：写上游自己的 `hidden` 映射
  //
  // store 的 `visible = list.filter(s => !hidden[Key(s)])`。把 origin 写进
  // `hidden` ⇒ 它**从所有读 visible 的表面消失**（Home 列表、Home 切换器、
  // 设置里的服务器行/分段菜单），**并且 Home 的当前服务器自动落到用户的
  // 服务器上**（`focused = visible[0]`）⇒ 不点切换器也能用。
  //
  // 关键在**写入时机**：本文件由 `addDocumentStartJavaScript` 注入，
  // 顶层代码早于页面脚本 ⇒ 这里写下的 `hidden` 会在应用 hydration 时被读进
  // 内存，**当次启动即生效**，不需要刷新。应用运行期间用户 Add/Remove 时，
  // 应用自己会写这个键 —— 我们挂一个只认这一个键的观察器补写一次并 reload
  // 一次（应用内存里的 hidden 只有重新 hydration 才跟得上）。
  //
  // ## 产品语义（产品负责人拍板）
  //
  // · 用户**还没有自己的服务器**（注册表键不存在）⇒ 一个字节都不动：
  //   origin 那条是首启唯一的 onboarding 入口（Edit 它即可改成自己的服务器）。
  // · 用户**已有自己的服务器** ⇒ `hidden[origin] = true`：它从全界面消失，
  //   Home 落到用户的服务器上。
  // · 用户把服务器**全部删掉** ⇒ 撤掉 `hidden[origin]`：placeholder 回来，
  //   应用仍然可用（不留一个"没有任何服务器"的死界面）。
  //
  // ## 已放弃的选项（留档，别再走一遍）
  //
  // · 预置 `defaultServerUrl`（4095/4096）：UX18-P1 设备实测证明它是**死指针**
  //   —— 列表/切换器/事件流三方都不读它。写了也没用。
  // · 纯 DOM 隐藏（v1–v4）：见上，遮不住上下文。
  // · 往注册表 list 里塞占位服务器：会**抢走 focused**（list[0] 顺序），
  //   等于用一个新的坏上下文换掉旧的坏上下文。
  // ======================================================================
  var PHANTOM_ATTR = "data-oc-phantom";
  var PHANTOM_STYLE_ID = "oc-phantom-hide";
  var PHANTOM_THROTTLE_MS = 250;
  var PHANTOM_ROW_SEL =
    'a,li,button,[role="button"],[role="menuitem"],[role="menuitemradio"],[data-slot]';
  // 服务器注册表键（P5 实证"首次 Add 成功才惰性创建"⇒ 存在性 = 有无用户服务器）
  var PHANTOM_REGISTRY_KEY = "opencode.global.dat:server";
  // reload 防环计数（sessionStorage：同一次会话内跨 reload 存活）
  var PHANTOM_RELOAD_KEY = "oc.originHideReloads";
  var PHANTOM_RELOAD_MAX = 3;
  var phantomWritingRegistry = false; // 我们自己在写 ⇒ 观察器别再递归
  var phantomReloadPending = false; // 一次只排一次 reload

  /** 页面 origin（去掉结尾斜杠）。host:port 由它派生 —— 不写死端口字面量。 */
  function phantomOrigin() {
    try {
      var o = window.location && window.location.origin;
      return o ? String(o).replace(/\/+$/, "") : "";
    } catch (e) {
      return "";
    }
  }

  /** 注册表里一条服务器条目的 url（兼容上游三种形态：字符串 / {url} / {http:{url}}）。 */
  function phantomServerUrl(entry) {
    if (!entry) return "";
    if (typeof entry === "string") return entry;
    if (entry.http && typeof entry.http.url === "string") return entry.http.url;
    if (typeof entry.url === "string") return entry.url;
    return "";
  }

  /**
   * 惰性读注册表。键不存在 / 坏 JSON / localStorage 不可用（隐私模式）一律 null。
   * **绝不缓存**：每次调用都重读，否则首启判定之后加服务器就再也放行不了。
   */
  function phantomReadRegistry() {
    try {
      var raw = window.localStorage && window.localStorage.getItem(PHANTOM_REGISTRY_KEY);
      if (!raw) return null;
      var reg = JSON.parse(raw);
      return reg && typeof reg === "object" ? reg : null;
    } catch (e) {
      return null;
    }
  }

  /** 整表写回（唯一写入口径：读-改-写，不改 list/不删条目）。 */
  function phantomWriteRegistry(reg) {
    phantomWritingRegistry = true;
    try {
      window.localStorage.setItem(PHANTOM_REGISTRY_KEY, JSON.stringify(reg));
      return true;
    } catch (e) {
      return false;
    } finally {
      phantomWritingRegistry = false;
    }
  }

  /** 用户**自己的**服务器 = list 里 url ≠ 页面 origin 的那些。 */
  function phantomUserServers(reg) {
    var out = [];
    var list = reg && Array.isArray(reg.list) ? reg.list : [];
    var origin = phantomOrigin();
    for (var i = 0; i < list.length; i++) {
      var u = phantomServerUrl(list[i]).replace(/\/+$/, "");
      if (u && u !== origin) out.push(u);
    }
    return out;
  }

  /**
   * 让注册表的 `hidden[origin]` 与产品语义对齐：
   *   用户有自己的服务器 ⇒ 置 true（origin 让位）；没有 ⇒ 删掉（placeholder 回来）。
   * 返回 true = **这次改了 localStorage**（调用方若在 hydration 之后，需要 reload 才生效）。
   *
   * 只动 `hidden` 这一个容器：`list` 一字不改、条目一个不删 —— 上游数据模型
   * 保持原样，避免破坏它的焦点/排序逻辑。
   */
  function syncOriginHidden() {
    var origin = phantomOrigin();
    if (!origin) return false;
    var reg = phantomReadRegistry();
    // 键不存在 = 用户还没有任何服务器 ⇒ 原样不动（首启 onboarding 路径）。
    if (!reg) return false;
    var want = phantomUserServers(reg).length > 0;
    var hidden = reg.hidden && typeof reg.hidden === "object" ? reg.hidden : null;
    var has = !!(hidden && hidden[origin]);
    if (want === has) return false; // 已经对齐，不写、不 reload
    if (want) {
      if (!hidden) {
        hidden = {};
        reg.hidden = hidden;
      }
      hidden[origin] = true;
    } else {
      delete hidden[origin];
    }
    return phantomWriteRegistry(reg);
  }

  /**
   * DOM 兜底层的闸门：**直接读注册表的 `hidden[origin]`**（单一事实来源）。
   * 上游渲染层没消费 hidden 的表面由 DOM 层补齐 —— 两侧同进同退，
   * 不会出现"设置里藏了、主页还在"这种半隐藏状态。
   */
  function isOriginHiddenInRegistry() {
    var origin = phantomOrigin();
    if (!origin) return false;
    var reg = phantomReadRegistry();
    if (!reg || !reg.hidden || typeof reg.hidden !== "object") return false;
    return !!reg.hidden[origin];
  }

  /**
   * 应用自己写注册表之后的补写路径。
   * 只有当**状态真的需要变**时才 reload —— 已经对齐的写入（打开项目、
   * 记 lastProject 等）不打扰用户。
   */
  function afterRegistryWrite() {
    if (!syncOriginHidden()) return; // 已对齐
    if (phantomReloadPending) return; // 一次只排一次
    // 还没挂载完（boot 路径已处理）⇒ 不 reload，避免启动期空转。
    if (document.readyState !== "complete") return;
    if (!document.querySelector("[data-opencode-mounted]")) return;
    var n = 0;
    try {
      n = parseInt(window.sessionStorage.getItem(PHANTOM_RELOAD_KEY) || "0", 10) || 0;
    } catch (e) {}
    if (n >= PHANTOM_RELOAD_MAX) return; // 结构性防环：同一次会话最多 3 次
    try {
      window.sessionStorage.setItem(PHANTOM_RELOAD_KEY, String(n + 1));
    } catch (e) {}
    phantomReloadPending = true;
    window.setTimeout(function () {
      try {
        window.location.reload();
      } catch (e) {}
    }, 200);
  }

  /**
   * 观察器：只认注册表这一个键。
   * （不挂 MutationObserver 去猜"用户刚加了服务器"—— 应用写注册表本身就是
   *   最准确的信号，而 DOM 变更在流式输出期间遍地都是。）
   */
  function watchRegistryWrites() {
    try {
      var proto = window.Storage && window.Storage.prototype;
      if (!proto || proto.__ocRegistryWatcher) return;
      var orig = proto.setItem;
      if (typeof orig !== "function") return;
      proto.__ocRegistryWatcher = true;
      proto.setItem = function (key, value) {
        var out = orig.apply(this, arguments);
        if (key === PHANTOM_REGISTRY_KEY && !phantomWritingRegistry) {
          try {
            afterRegistryWrite();
          } catch (e) {}
        }
        return out;
      };
    } catch (e) {}
  }

  /** 目标标识：location.origin 的 "host:port"（动态取，不写死端口字面量）。 */
  function phantomOriginToken() {
    try {
      var o = window.location && window.location.origin;
      if (!o) return "";
      return String(o).replace(/^https?:\/\//, "").replace(/\/+$/, "");
    } catch (e) {
      return "";
    }
  }

  /** 注入隐藏 CSS。 */
  function installPhantomServerHideStyle() {
    if (document.getElementById(PHANTOM_STYLE_ID)) return;
    try {
      var st = document.createElement("style");
      st.id = PHANTOM_STYLE_ID;
      st.textContent = "[" + PHANTOM_ATTR + '="1"]{display:none!important}';
      (document.head || document.documentElement).appendChild(st);
    } catch (e) {}
  }

  /**
   * 从命中元素上爬，取**要打标记的行/块容器**。
   * 幂等（已标记即跳过）；**不删除任何节点**。
   *
   * 🔴 血泪坑（2026-10-06 设备复验当场踩到）：**不能无脑 closest()**。
   *   `closest('…, [data-slot]')` 会一路爬到页面外壳；而 <html>/<body> 自身
   *   也含该 URL 文本（它们在 querySelectorAll('*') 里），没有匹配祖先时
   *   回落成"标记自身" ⇒ 实测把 <html> 打了标记，**整页连 <style> 一起
   *   消失，屏幕全黑**。
   *   第二次收紧：上爬到的祖先若"文本量超过紧凑上限"，说明它是分区/页面
   *   容器（连 "Add server" 按钮一起包住），**不能**当行容器。
   *   ⇒ 找不到紧凑的行祖先时回落标记命中元素自身（宁可少隐藏，不可多隐藏）。
   *
   * 🔴 UX15-P1 血泪坑之二（同轮设备复验踩到，**改 XPath 引入**）：
   *   改用 XPath 直接文本匹配后，候选只剩"**直接**文本节点含目标串"的元素。
   *   而真实 DOM（设置 → Server 页）长这样：
   *     bdi.settings-servers-name   ← XPath 命中（直接文本 = 页面 origin）
   *       div.settings-servers-copy
   *         div.settings-servers-lead
   *           div.settings-servers-row
   *             div
   *               section.settings-section.settings-server-connection  ← 旧扫描在这里收口
   *   这条链上**没有一个祖先匹配 PHANTOM_ROW_SEL**（全是 div/section），
   *   于是"回落标记命中元素自身"就只把那个 <bdi> 藏了 ⇒ **行的红点与 "…" 还在**，
   *   设备上表现为"服务器行只剩一个红点和三个点，名字没了"。
   *   旧的全量扫描之所以能整块隐藏，是因为**祖先元素自己的 textContent 同样含
   *   目标串**，所以 section 本身也是候选、并在"父级文本超上限"处收口被标记。
   *   ⇒ 现在保留同样的收口语义：沿链记住**最外层仍然紧凑**的那个祖先，
   *     找不到行选择器匹配时用它兜底（而不是用命中的 <bdi>）。
   */
  function phantomRowFor(el, cap) {
    var node = el.parentElement;
    var lastCompact = null;
    while (node && node !== document.body && node !== document.documentElement) {
      if (node === document.head) break;
      if ((node.textContent || "").trim().length > cap) break;
      lastCompact = node; // 仍然紧凑 ⇒ 记为当前最外层的合法收口点
      if (node.matches && node.matches(PHANTOM_ROW_SEL)) return node;
      node = node.parentElement;
    }
    return lastCompact || el;
  }

  function hidePhantomRows() {
    // 闸门 = 注册表的 `hidden[origin]`（单一事实来源）。它由 syncOriginHidden()
    // 按"用户是否已有自己的服务器"维护，DOM 层只负责把上游漏渲染的表面补齐
    // ⇒ 两侧同进同退，不会再出现 v1–v4 那种"设置里藏了、主页还在"的半隐藏。
    if (!isOriginHiddenInRegistry()) return 0;
    var token = phantomOriginToken();
    if (!token) return 0;
    // 🔴 UX15-P1 · 候选取元素改用 **XPath 直接文本匹配**。
    //   原实现是 `querySelectorAll("*")` + 逐个 `el.textContent.includes(token)`：
    //   textContent 是 O(子树) 的串接读取，把全页元素轮一遍就是 O(整页文本量)；
    //   而流式输出期间 DOM 高频变动，250ms 节流窗口内反复全量扫描 ⇒
    //   中低端机掉帧风险（本轮自我回归审查定的性能红线）。
    //   `//*[text()[contains(., "…")]]` 只命中"**直接**文本节点含目标串"的元素：
    //   浏览器原生实现只走文本节点通道，且**天然免疫 P6 踩到的祖先污染** ——
    //   祖先的 textContent 含该串、但它自己的直接文本并不含，根本不会进入上爬。
    //   目标串是 host:port（. : - 数字），与双引号无冲突；仍做一次实体转义兜底。
    var hits;
    try {
      var lit = token.replace(/"/g, "&quot;");
      hits = document.evaluate(
        "//*[text()[contains(., \"" + lit + "\")]]",
        document,
        null,
        XPathResult.ORDERED_NODE_SNAPSHOT_TYPE,
        null,
      );
    } catch (e) {
      return 0;
    }
    var m = hits.snapshotLength;
    var candMax = token.length * 6 + 40;
    var n = 0;
    for (var i = 0; i < m; i++) {
      var el = hits.snapshotItem(i);
      if (!el || el.nodeType !== 1) continue;
      var tag = (el.tagName || "").toUpperCase();
      // <style>/<script> 的"直接文本"就是其内容本身，XPath 会直接命中它们 ⇒ 必跳。
      if (tag === "SCRIPT" || tag === "STYLE") continue;
      // 🔴 永不标记根节点 —— 标记 <html> 会让整页（含 <style>）一起消失。
      if (el === document.documentElement || el === document.body) continue;
      if (el === document.head) continue; // 同理：<head> 一藏，页面所有样式都没了
      if (el.getAttribute(PHANTOM_ATTR) === "1") continue; // 幂等
      // 不碰输入框：Add server 对话框里可能正输入着同一个地址
      //（XPath 命中的是文本节点，而 input 的 value 并不是文本节点，这里是双保险；
      //  真正兜底的是下面"容器里带输入框就整体跳过"那条。）
      if (tag === "INPUT" || tag === "TEXTAREA") continue;
      // 紧凑上界：命中的元素自己可能是个大容器（直接文本里带地址、子树却超大）。
      var own = "";
      try {
        own = el.textContent || "";
      } catch (e) {
        continue;
      }
      if (own.trim().length > candMax) continue;
      // 容器里带着输入框的一律不动（保护 Add server 对话框）
      if (el.querySelector("input,textarea")) continue;
      var row = phantomRowFor(el, candMax);
      if (!row || row === document.documentElement || row === document.body) continue;
      // UX17-v4 · **绝不动 Home 服务器切换器**（[data-component=home-projects-dropdown]）：
      // 切换器文本="当前服务器名"。首加服务器后当前仍是 4173 ⇒ 藏了它=永远无法切走（自锁死，UX17-P3 实证）。
      // 只藏列表里的 4173 行；切换器本体保持可见可点，用户切到自己服务器后其标签自然不再含 4173。
      if (row.closest && row.closest('[data-component="home-projects-dropdown"]')) continue;
      if (row.getAttribute(PHANTOM_ATTR) === "1") continue;
      row.setAttribute(PHANTOM_ATTR, "1");
      n++;
    }
    return n;
  }

  /** 启动：立即跑一次 + MutationObserver 节流跟住 SPA 重渲染。 */
  function startPhantomServerHide() {
    if (window.__ocPhantomHideStarted) return;
    window.__ocPhantomHideStarted = true;
    installPhantomServerHideStyle();
    try {
      hidePhantomRows();
      var pending = false;
      var mo = new MutationObserver(function () {
        if (pending) return;
        pending = true;
        window.setTimeout(function () {
          pending = false;
          hidePhantomRows();
        }, PHANTOM_THROTTLE_MS);
      });
      mo.observe(document.body, { subtree: true, childList: true });
      window.__ocPhantomHideObserver = mo;
    } catch (e) {}
  }

  // 只在首屏加载完之后启动，避免阻断首屏渲染
  if (document.readyState === "complete") {
    startPhantomServerHide();
  } else {
    window.addEventListener("load", startPhantomServerHide, { once: true });
  }

  // ---- 注册表机制 · ① boot 路径（必须在文档开始跑，早于页面脚本）----------
  // 本文件由 addDocumentStartJavaScript 注入 ⇒ 顶层代码在**文档开始**执行。
  // 这里写下的 `hidden[origin]` 会在应用 hydration 时被读进内存 ⇒ **当次启动
  // 即生效**（用户手机上"已经有自己的服务器"的状态，装上这版一开就好）。
  try {
    syncOriginHidden();
  } catch (e) {}

  // ---- 注册表机制 · ② 运行期路径 ------------------------------------------
  // 应用运行期间 Add/Edit/Remove 都会自己写注册表键；观察器在那里补写一次，
  // 必要时 reload 一次让应用重新 hydration（hidden 才会进它的内存）。
  watchRegistryWrites();

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
  // 只在**窄屏**生效（`matchMedia('(max-width: 767px)')`，与上游窄屏断点一致）；
  // 桌面/平板不受影响。比例值由**设置页那一行**决定（见文件末尾 OC-SETTINGS 段）：
  // 默认 0.65 ⇒ 41 → ≈63 列、字形 9.1px；可选 100% / 80% / 65% / 55%。
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
      if (!v || !isFinite(v)) return OC_TERM_SCALE_DEFAULT;
      return Math.min(1, Math.max(0.5, v));
    } catch (e) {
      return OC_TERM_SCALE_DEFAULT;
    }
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
              if (ocCtxInTerminal(this)) v = ocScaleFontPx(String(value), ocTerminalScale());
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
            if (!window.matchMedia || !window.matchMedia("(max-width: 767px)").matches) return m;
            var tail = ocTerminalFontTail();
            if (!tail) return m;
            var font = String(this.font || "");
            if (!font || font.indexOf(tail) < 0) return m;
            var z = ocTerminalScale();
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
  /** 原始（未缩放）measureText —— "估算列数"要用真实格子宽，不能被自己的补丁缩放。 */
  var ocRawMeasureText = null;

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
