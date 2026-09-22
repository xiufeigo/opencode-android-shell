package com.xiufeigo.opencodeshell.ui.session.composer

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.xiufeigo.opencodeshell.R

/**
 * WAVE 2.4b (P0-7): the Steer/Queue chip label.
 *
 * The labels are the generated v2 i18n keys (`strings_oc_i18n.xml`, from
 * upstream `session.queue.*` / `ui.promptInput.*`): they exist in the default
 * `values` bundle, so `stringResource` resolves on every locale (missing zh
 * translations fall back to the default bundle at runtime — Android's standard
 * resource fallback, no crash). The pure [label] overload pins the
 * delivery→key mapping for JVM tests.
 */
internal object DeliveryChipText {

    /** `delivery` (`steer` | `queue`) → generated string key. */
    fun key(delivery: String?): Int = when (delivery?.trim()?.lowercase()) {
        ComposerDelivery.QUEUE -> R.string.ui_promptInput_queue
        else -> R.string.ui_promptInput_steer
    }

    /** JVM-testable key selection (no resources needed). */
    fun label(delivery: String?): String = when (delivery?.trim()?.lowercase()) {
        ComposerDelivery.QUEUE -> FALLBACK_QUEUE
        else -> FALLBACK_STEER
    }

    internal const val FALLBACK_STEER = "Steer"
    internal const val FALLBACK_QUEUE = "Queue"
}

/** The chip label, resolved from resources (default-bundle fallback covers zh). */
@Composable
internal fun deliveryChipLabel(delivery: String?): String =
    stringResource(DeliveryChipText.key(delivery))
