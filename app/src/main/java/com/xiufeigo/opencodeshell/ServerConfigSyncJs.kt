package com.xiufeigo.opencodeshell

/**
 * JS pushed into the WebView to mirror the server config into the native shell.
 * Reads localStorage `opencode.global.dat:server` ({url, username?, password?})
 * and calls the Android bridge; retried briefly because the SDK hydrates the
 * entry asynchronously after page load.
 */
object ServerConfigSyncJs {
    val INSTALL = """
        (function () {
          if (window.__ocServerConfigSync) return;
          window.__ocServerConfigSync = true;
          var ATTEMPTS = 12, INTERVAL = 500;
          var tries = 0;
          var last = null;
          function read() {
            try {
              var raw = localStorage.getItem('opencode.global.dat:server');
              if (raw && raw !== last) {
                last = raw;
                try { OpenCodeAndroid.syncServerConfig(raw); } catch (e1) {}
              }
            } catch (e2) {}
          }
          function tick() {
            read();
            tries += 1;
            if (tries < ATTEMPTS) setTimeout(tick, INTERVAL);
            else setInterval(read, 5000); // server edits can land long after load
          }
          document.addEventListener('visibilitychange', function () {
            if (document.visibilityState === 'visible') read();
          });
          tick();
        })();
    """.trimIndent()
}
