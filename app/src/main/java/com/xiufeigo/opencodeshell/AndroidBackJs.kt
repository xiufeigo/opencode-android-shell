package com.xiufeigo.opencodeshell

/**
 * Forwards Android Back into the embedded OpenCode UI the same way Desktop uses Escape:
 * close one dialog/popover/menu, or pop in-app navigation. The Activity finishes only at
 * true root (home, nothing to dismiss). ESC never finishes the Activity.
 */
object AndroidBackJs {
    const val OVERLAY = "overlay"
    const val NAV = "nav"
    const val ROOT = "root"

    /** Installs `window.__ocAndroidHandleBack` without invoking it. */
    const val INSTALL = """
            (function () {
              window.__ocAndroidHandleBack = function () {
                var visible = function (el) {
                  if (!el) return false;
                  var s = getComputedStyle(el);
                  if (s.display === 'none' || s.visibility === 'hidden' || s.pointerEvents === 'none') return false;
                  var r = el.getBoundingClientRect();
                  return r.width > 0 && r.height > 0;
                };
                var overlayOpen = function () {
                  var sels = [
                    '[data-component="dialog-overlay"]',
                    '[data-component="dialog-v2"]',
                    '[data-slot="dialog-content"]',
                    '[role="dialog"]',
                    '[data-component="popover-content"]',
                    '[role="menu"]',
                    '[role="listbox"]'
                  ];
                  for (var i = 0; i < sels.length; i++) {
                    var nodes = document.querySelectorAll(sels[i]);
                    for (var j = 0; j < nodes.length; j++) {
                      if (visible(nodes[j])) return true;
                    }
                  }
                  return false;
                };
                var fireEscape = function () {
                  var opts = { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true };
                  try { window.dispatchEvent(new KeyboardEvent('keydown', opts)); } catch (e1) {}
                  try { document.dispatchEvent(new KeyboardEvent('keydown', opts)); } catch (e2) {}
                };
                var click = function (el) {
                  if (!el) return false;
                  try {
                    el.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, cancelable: true }));
                    el.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, cancelable: true }));
                    el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
                    return true;
                  } catch (e) {
                    try { el.click(); return true; } catch (e2) { return false; }
                  }
                };
                var labelOf = function (el) {
                  return ((el.getAttribute('aria-label') || '') + ' ' + (el.textContent || '')).trim().toLowerCase();
                };
                if (overlayOpen()) {
                  fireEscape();
                  return 'overlay';
                }
                var buttons = document.querySelectorAll('button, [role="button"]');
                for (var b = 0; b < buttons.length; b++) {
                  var el = buttons[b];
                  if (el.disabled || el.getAttribute('aria-disabled') === 'true' || !visible(el)) continue;
                  var lab = labelOf(el);
                  if (lab === 'navigate back' || lab === 'go back' || lab === '返回' || lab === '上一页') {
                    click(el);
                    return 'nav';
                  }
                }
                for (var h = 0; h < buttons.length; h++) {
                  var home = buttons[h];
                  if (!visible(home)) continue;
                  var pressed = home.getAttribute('aria-pressed');
                  if (pressed !== 'false') continue;
                  var hl = labelOf(home);
                  if (hl === 'home' || hl === '主页' || hl.indexOf('home') === 0) {
                    click(home);
                    return 'nav';
                  }
                }
                return 'root';
              };
            })();
        """

    const val INVOKE = """
            (function () {
              try {
                if (typeof window.__ocAndroidHandleBack !== 'function') { $INSTALL }
                return window.__ocAndroidHandleBack();
              } catch (e) {
                return 'root';
              }
            })();
        """

    fun parse(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ROOT
        val trimmed = raw.trim().removeSurrounding("\"").removeSurrounding("'")
        return when (trimmed) {
            OVERLAY, NAV, ROOT -> trimmed
            else -> ROOT
        }
    }

    /** Android Back may leave the app only at root with no WebView history. ESC never uses this. */
    fun shouldLeaveApp(jsResult: String, webViewCanGoBack: Boolean): Boolean {
        if (jsResult == OVERLAY || jsResult == NAV) return false
        if (webViewCanGoBack) return false
        return true
    }

    fun shouldFinishOnEscape(): Boolean = false
}
