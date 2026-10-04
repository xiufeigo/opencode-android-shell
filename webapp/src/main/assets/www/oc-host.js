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
        if (lastInsets) doApply(lastInsets);
        window.dispatchEvent(new CustomEvent("oc:ready", { detail: { version: 1 } }));
      },
      { once: true }
    );
  } else {
    window.dispatchEvent(new CustomEvent("oc:ready", { detail: { version: 1 } }));
  }
})();
