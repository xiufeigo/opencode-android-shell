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

