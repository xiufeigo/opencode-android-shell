package com.xiufeigo.opencodeshell.ui.theme

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * `prefers-reduced-motion` for Android.
 *
 * There is no CSS media query here; the platform equivalent is the **system
 * animation scale** — `Settings.Global.ANIMATOR_DURATION_SCALE == 0` (the
 * "Remove animations" / Animator duration scale is off switch) or
 * [ValueAnimator.areAnimatorsEnabled] being `false`. `research/03` §5 lists the
 * 16 CSS rules that degrade; [OcMotion.ReducedMotionTarget] names them and
 * [OcMotion.durationMs] performs the degradation.
 *
 * The **decision** is a pure function ([isReduced]) so it is JVM-tested; the
 * Android read + the `ContentObserver` that keeps it live sit in
 * [ProvideOcReducedMotion].
 */
object ReducedMotion {

    /**
     * `true` when the user asked the platform for no animations.
     *
     * @param animatorDurationScale `Settings.Global.ANIMATOR_DURATION_SCALE`
     *   (`1` = normal, `0` = animations removed, values in between = speed).
     * @param animatorsEnabled `ValueAnimator.areAnimatorsEnabled()` — the
     *   developer-options "Animator duration scale" master switch, which also
     *   covers the "no animations at all" accessibility state.
     */
    fun isReduced(animatorDurationScale: Float, animatorsEnabled: Boolean): Boolean =
        !animatorsEnabled || animatorDurationScale == 0f
}

/** `true` while the system asks for reduced motion. Default: full motion. */
val LocalOcReducedMotion = staticCompositionLocalOf { false }

/**
 * Reads the system animation scale, keeps it in sync through a
 * `ContentObserver` on `ANIMATOR_DURATION_SCALE`, and publishes
 * [LocalOcReducedMotion].
 *
 * Mounted once, at the Compose root (`OcApp`) — every motion component in the
 * shell reads the local instead of touching `Settings` itself.
 */
@Composable
fun ProvideOcReducedMotion(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val resolver = context.contentResolver

    fun read(): Boolean {
        val scale = runCatching {
            Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        return ReducedMotion.isReduced(
            animatorDurationScale = scale,
            animatorsEnabled = ValueAnimator.areAnimatorsEnabled(),
        )
    }

    var reduced by remember(resolver) { mutableStateOf(read()) }

    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = read()
            }
        }
        runCatching {
            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false,
                observer,
            )
        }
        onDispose { runCatching { resolver.unregisterContentObserver(observer) } }
    }

    CompositionLocalProvider(LocalOcReducedMotion provides reduced) {
        content()
    }
}
