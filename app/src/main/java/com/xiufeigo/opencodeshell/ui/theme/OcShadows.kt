package com.xiufeigo.opencodeshell.ui.theme

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * `--v2-elevation-*` → Compose (`research/02` §0.5, `research/03`).
 *
 * The tokens are multi-layer CSS box-shadows; the first two layers carry the
 * blur/offset and the third is the `0.5px` ring (`--v2-alpha-dark-12` light,
 * `--v2-alpha-light-16` dark). Compose's `Modifier.shadow` is a single
 * elevation shadow, so the port uses the token's **blur** as the elevation and
 * its **colour** as both the ambient and spot colour — the same technique
 * `MentionPopover` already uses for `elevation-raised`. [OcShadow.ring] carries
 * the ring colour: consumers draw it as a `0.5 dp` hairline border on top of
 * the shadow. Consumers therefore never spell a dp value or a colour
 * themselves.
 */
fun Modifier.ocShadow(shadow: OcShadow, shape: Shape, clip: Boolean = false): Modifier = shadow(
    elevation = shadow.blur.dp,
    shape = shape,
    clip = clip,
    ambientColor = shadow.color,
    spotColor = shadow.color,
)

/** `elevation-raised` — the Home page card (and the review panel later). */
@Composable
fun Modifier.ocRaisedShadow(shape: Shape, clip: Boolean = false): Modifier =
    ocShadow(OcTheme.elevation.raised, shape, clip)

/** `elevation-floating` — popovers (search panel, menus). */
@Composable
fun Modifier.ocFloatingShadow(shape: Shape, clip: Boolean = false): Modifier =
    ocShadow(OcTheme.elevation.floating, shape, clip)

/** `elevation-overlay` — drawers, sheets, large dialogs. */
@Composable
fun Modifier.ocOverlayShadow(shape: Shape, clip: Boolean = false): Modifier =
    ocShadow(OcTheme.elevation.overlay, shape, clip)

/** Draws the `0.5px` ring layer of an [OcShadow] as a hairline border. */
fun Modifier.ocRing(shadow: OcShadow, shape: Shape): Modifier =
    border(0.5.dp, shadow.ring, shape)
