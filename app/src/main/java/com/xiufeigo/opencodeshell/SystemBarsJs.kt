package com.xiufeigo.opencodeshell

/**
 * Observes the official app color-scheme (`data-color-scheme`, theme-color meta,
 * `prefers-color-scheme`) and reports it to [OpenCodeAndroidBridge.setColorScheme]
 * so status/navigation bar icon appearance can match the WebView UI.
 */
object SystemBarsJs {
    const val INSTALL = """
            (function () {
              if (window.__ocSystemBars) return;
              window.__ocSystemBars = true;
              function scheme() {
                var el = document.documentElement;
                var ds = (el.dataset && (el.dataset.colorScheme || el.dataset.colorscheme)) ||
                  el.getAttribute('data-color-scheme') || '';
                ds = String(ds).toLowerCase();
                if (ds === 'dark' || ds === 'light') return ds;
                var meta = document.querySelector('meta[name="theme-color"]');
                var c = meta && meta.getAttribute('content');
                if (c) {
                  var low = String(c).toLowerCase();
                  if (low === '#080808' || low === '#000' || low === '#000000') return 'dark';
                  if (low === '#fafafa' || low === '#fff' || low === '#ffffff') return 'light';
                }
                try {
                  if (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) {
                    return 'dark';
                  }
                } catch (e) {}
                return 'light';
              }
              function report() {
                try {
                  if (window.OpenCodeAndroid && typeof window.OpenCodeAndroid.setColorScheme === 'function') {
                    window.OpenCodeAndroid.setColorScheme(scheme());
                  }
                } catch (e2) {}
              }
              report();
              try {
                var mo = new MutationObserver(report);
                mo.observe(document.documentElement, {
                  attributes: true,
                  attributeFilter: ['data-color-scheme', 'data-theme', 'style', 'class']
                });
                if (document.head) {
                  mo.observe(document.head, { subtree: true, childList: true, attributes: true, attributeFilter: ['content'] });
                }
              } catch (e3) {}
              try {
                var mq = window.matchMedia('(prefers-color-scheme: dark)');
                var onMq = function () { report(); };
                if (mq.addEventListener) mq.addEventListener('change', onMq);
                else if (mq.addListener) mq.addListener(onMq);
              } catch (e4) {}
            })();
        """
}
