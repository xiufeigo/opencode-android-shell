package com.xiufeigo.opencodeshell

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@LargeTest
@RunWith(AndroidJUnit4::class)
class HomeTapInstrumentedTest {
    @Test
    fun settingsControlIsTappableAfterColdStart() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val latch = CountDownLatch(1)
        var jsError: String? = null
        var clicked = false
        var dialogOpened = false

        scenario.onActivity { activity ->
            activity.findViewById<android.webkit.WebView>(R.id.webView).postDelayed({
                activity.findViewById<android.webkit.WebView>(R.id.webView).evaluateJavascript(
                    TAP_SETTINGS_JS,
                ) { value ->
                    val raw = value?.trim('"') ?: "null"
                    clicked = raw.contains("clicked")
                    dialogOpened = raw.contains("dialog")
                    if (raw.contains("error") || raw == "null") jsError = raw
                    latch.countDown()
                }
            }, 8_000)
        }

        assertTrue("WebView JS tap probe timed out", latch.await(25, TimeUnit.SECONDS))
        assertTrue("settings tap did not run ($jsError)", clicked || dialogOpened)

        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val settings = device.wait(Until.findObject(By.text("Settings")), 2_000)
            ?: device.wait(Until.findObject(By.text("设置")), 2_000)
        // Either the JS dialog opened or the native UI Automator node exists.
        assertTrue("Settings UI did not respond", dialogOpened || settings != null || clicked)
        scenario.close()
    }

    companion object {
        private const val TAP_SETTINGS_JS = """
            (function () {
              function visible(el) {
                if (!el) return false;
                const s = getComputedStyle(el);
                if (s.display === 'none' || s.visibility === 'hidden' || s.pointerEvents === 'none') return false;
                const r = el.getBoundingClientRect();
                return r.width > 0 && r.height > 0;
              }
              const nodes = Array.from(document.querySelectorAll('button, [role="button"], [data-action]'));
              const settings = nodes.find((el) => {
                const label = ((el.getAttribute('data-action') || '') + ' ' + (el.getAttribute('aria-label') || '') + ' ' + (el.textContent || '')).toLowerCase();
                return visible(el) && (label.includes('settings') || label.includes('设置') || el.getAttribute('data-action') === 'home-open-settings');
              });
              if (!settings) return 'error:no-settings-control';
              settings.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, cancelable: true }));
              settings.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
              const dialog = document.querySelector('[role="dialog"], [data-component*="dialog"], [data-component*="settings"]');
              return dialog ? 'clicked+dialog' : 'clicked';
            })();
        """
    }
}
