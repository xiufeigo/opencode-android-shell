package com.xiufeigo.opencodeshell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v57 (`E2ECHK5556-43…45`, emulator-5556): switching Language in the settings
 * dialog closed the dialog and threw the app back to Home — twice, both times
 * with the new language correctly applied and persisted.
 *
 * The language row writes the per-app locale through `LocaleManager`
 * (`SettingsStore.applyLanguage`, API 33+), which is a **configuration** change.
 * Unhandled, the framework recreates the Activity, and a fresh `setContent`
 * rebuilds everything from scratch: the nav graph restarts at Home and the
 * `DialogController` stack (which holds the settings dialog and the tab it was
 * on) starts empty. Declaring `locale|layoutDirection` in `configChanges` makes
 * the framework deliver the change instead of recreating — the composition, its
 * remembered state and the pushed dialog all survive, while `Resources` still
 * re-resolve so every `stringResource()` reads the new locale.
 *
 * Source-level pin (like the other manifest pins): the manifest is not
 * JVM-reachable, and an Activity recreation is not unit-testable here.
 */
class SettingsLanguageNoRecreateTest {

    private fun manifest(): String =
        File("src/main/AndroidManifest.xml").takeIf { it.isFile }
            ?.readText()
            ?: File("app/src/main/AndroidManifest.xml").readText()

    private fun ocActivityConfigChanges(): List<String> {
        val manifest = manifest()
        val start = manifest.indexOf("""android:name=".OcActivity"""")
        assertTrue("the launcher activity is declared", start > 0)
        val end = manifest.indexOf(">", start)
        val tag = manifest.substring(start, end)
        val at = tag.indexOf("android:configChanges=")
        assertTrue("OcActivity declares its handled configuration changes", at > 0)
        val value = tag.substring(at + "android:configChanges=".length)
        return value.substring(1, value.indexOf('"', 1)).split("|").map { it.trim() }
    }

    @Test
    fun theLauncherActivityHandlesLocaleChangesItself() {
        val handled = ocActivityConfigChanges()
        assertTrue(
            "a locale change must not recreate the launcher Activity " +
                "(v57: settings dialog closed and the app fell back to Home)",
            "locale" in handled,
        )
        assertTrue("layoutDirection rides the same notification", "layoutDirection" in handled)
    }

    @Test
    fun theExistingHandledChangesAreStillDeclared() {
        val handled = ocActivityConfigChanges()
        // `uiMode` is the theme-follow contract (`SystemBarIconAppearance`); the
        // rest are the standard desktop/freeform/IME set this file always had.
        for (flag in listOf(
            "orientation",
            "screenSize",
            "screenLayout",
            "keyboardHidden",
            "smallestScreenSize",
            "uiMode",
        )) {
            assertTrue("$flag is still handled", flag in handled)
        }
    }

    @Test
    fun theLanguageRowStillWritesTheAppLocaleRatherThanOnlyThePreference() {
        // The recreate was only ever the *side effect*; the language itself must
        // keep going through the platform locale API (`SettingsStore`).
        val store = File("src/main/java/com/xiufeigo/opencodeshell/SettingsStore.kt")
            .takeIf { it.isFile }
            ?.readText()
            ?: File("app/src/main/java/com/xiufeigo/opencodeshell/SettingsStore.kt").readText()
        assertTrue(store.contains("getSystemService(LocaleManager::class.java)"))
        assertTrue(store.contains("manager.applicationLocales = LocaleList.forLanguageTags(tag)"))
    }
}
