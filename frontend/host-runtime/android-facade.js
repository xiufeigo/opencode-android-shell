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

