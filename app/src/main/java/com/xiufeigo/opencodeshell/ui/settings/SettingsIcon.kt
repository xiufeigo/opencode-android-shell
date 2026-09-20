package com.xiufeigo.opencodeshell.ui.settings

/**
 * Icon names from the official SVG sprite that the Settings left navigation and
 * the servers tab use (`dialog-settings-v2.tsx` passes
 * `<Icon name="sliders|keyboard|server|providers|models"/>`; `servers.tsx` uses
 * `plus` / `dots`). Kept here — next to [SettingsTab] — so the mapping is pure
 * and unit-testable; the drawing lives in `ui/components/OcIcons.kt`.
 */
enum class SettingsIcon { SLIDERS, KEYBOARD, SERVER, PROVIDERS, MODELS }
