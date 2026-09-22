package com.xiufeigo.opencodeshell

/**
 * JS pushed into the WebView to mirror the server config into the native shell.
 * It also exposes a reverse direction hook used by native Settings edits.
 */
object ServerConfigSyncJs {
    val INSTALL = """
        (function () {
          if (window.__ocServerConfigSync) return;
          window.__ocServerConfigSync = true;
          var ATTEMPTS = 12, INTERVAL = 500;
          var tries = 0;
          var last = null;
          window.__ocSetServerConfig = function (json) {
            try {
              localStorage.setItem('opencode.global.dat:server', json);
              last = json;
              try { OpenCodeAndroid.syncServerConfig(json); } catch (e0) {}
              window.dispatchEvent(new CustomEvent('opencode-server-config-changed', { detail: json }));
            } catch (e0) {}
          };
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
            else setInterval(read, 5000);
          }
          document.addEventListener('visibilitychange', function () {
            if (document.visibilityState === 'visible') read();
          });
          tick();
        })();
    """.trimIndent()
}
