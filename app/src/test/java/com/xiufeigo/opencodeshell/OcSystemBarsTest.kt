package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.theme.OcSystemBars
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v40 真机批：暗色主题下系统状态栏图标不可读 (`research/v40_verify/tD`
 * `tD_20_dark`，而浅色 `tD_76_light` 正常)。
 *
 * 现象：Color scheme = Dark 后应用内容全黑，但状态栏的时钟 / 信号 / 电量图标
 * 仍是深灰——深灰压深黑几乎不可读。
 *
 * 原因：`OcActivity` 是 edge-to-edge，状态栏画的是 Compose surface 本身；
 * platform 的 `isAppearanceLightStatusBars` 却停在主题声明的初始值（浅色
 * `true`＝深色图标），内容切暗后无人把它翻成浅色图标。
 *
 * 修法：activity 观察 `Settings.isDark(systemDark)` 的实时值（Color scheme
 * 行的 `system|light|dark` 语义，`system` 跟随平台），每变一次就写一次
 * `WindowInsetsControllerCompat.isAppearanceLightStatusBars`：暗内容 → 浅图标
 * (`false`)，浅内容 → 深图标 (`true`)。
 */
class OcSystemBarsTest {

    @Test
    fun darkContentTakesLightStatusBarIcons() {
        assertTrue(OcSystemBars.statusBarIconsLight(darkContent = true))
    }

    @Test
    fun lightContentTakesDarkStatusBarIcons() {
        assertFalse(OcSystemBars.statusBarIconsLight(darkContent = false))
    }

    @Test
    fun lightIconsMappingIsTheInverseOfDarkContent() {
        assertFalse(OcSystemBars.lightIconsForDarkContent(dark = true))
        assertTrue(OcSystemBars.lightIconsForDarkContent(dark = false))
    }

    @Test
    fun activityWiresTheResolvedSchemeToTheStatusBarController() {
        val activity = File("src/main/java/com/xiufeigo/opencodeshell/OcActivity.kt")
            .takeIf { it.isFile }
            ?: File("app/src/main/java/com/xiufeigo/opencodeshell/OcActivity.kt")
        assertTrue("missing source: OcActivity.kt", activity.isFile)
        val body = activity.readText()
        assertTrue(body.contains("WindowInsetsControllerCompat"))
        assertTrue(body.contains("isAppearanceLightStatusBars"))
        assertTrue(body.contains("OcSystemBars.lightIconsForDarkContent"))
        assertTrue(
            "the appearance must follow the Color scheme row, not the raw platform flag",
            body.contains("settings.isDark("),
        )
        assertTrue(body.contains("isSystemInDarkTheme()"))
    }
}
