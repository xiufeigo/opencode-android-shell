package com.xiufeigo.opencodeshell.web.host

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import com.xiufeigo.opencodeshell.web.BuildConfig

/** WebView plus the padding-owning viewport container that must host it. */
data class WebViewViewport(
    val webView: WebView,
    val root: FrameLayout,
)

/**
 * Creates the instance-bound visual/focus shell around a WebView.
 *
 * This factory deliberately owns only configuration that is intrinsic to each
 * WebView instance: viewport containment, colors, scrolling, native focus/IME
 * plumbing, and first-draw observability. Bridge, client, insets, and loading
 * orchestration remain composition-root concerns.
 */
class WebViewViewportFactory(
    private val context: Context,
) {
    @SuppressLint("ClickableViewAccessibility")
    fun create(): WebViewViewport {
        val webView = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(0xFF080808.toInt())
            isVerticalScrollBarEnabled = false
            overScrollMode = WebView.OVER_SCROLL_NEVER

            // DOM focus and native focus are separate truths. Android only creates an
            // InputConnection when the served view really owns native focus; in touch
            // mode a tap does not reliably move that focus into WebView by itself.
            isFocusable = true
            isFocusableInTouchMode = true

            // ACTION_DOWN happens before WebView's internal DOM focus dispatch. Request
            // native focus there without consuming the event, so selection, scrolling,
            // clicking, and DOM-controlled keyboard behavior remain unchanged.
            setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN && !view.isFocused) {
                    val got = view.requestFocus()
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            TAG,
                            "focus: ACTION_DOWN requestFocus=$got focusable=${view.isFocusable} " +
                                "inTouchMode=${view.isFocusableInTouchMode}",
                        )
                    }
                }
                false
            }
        }

        // AppCompat's content frame can rewrite system-bar padding. The padding belongs
        // to this dedicated wrapper so InsetsHost's viewport shrinking survives.
        val root = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(0xFF080808.toInt())
        }
        root.addView(webView)

        // WebView's first real paint is the end of the startup blank-screen interval.
        webView.viewTreeObserver.addOnDrawListener(
            object : android.view.ViewTreeObserver.OnDrawListener {
                private var first = true
                override fun onDraw() {
                    if (first) {
                        first = false
                        PerfMarks.mark("Z.firstDraw")
                    }
                }
            },
        )

        return WebViewViewport(webView = webView, root = root)
    }

    private companion object {
        const val TAG = "OcWebHost"
    }
}
