package com.xiufeigo.opencodeshell.web.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6 · 省电绊线（第一道）：**纯逻辑** tripwire。
 *
 * 被守护的判据（题面要求）：
 * > "离开前台 / 回合结束后不应仍持有 `FLAG_KEEP_SCREEN_ON`"
 *
 * 之所以能直接测 [PowerPolicy] 而不用 Robolectric：`webapp` 模块**没开**
 * `unitTests.isReturnDefaultValues`（`app` 开了，见 `app/build.gradle.kts:64-65`），
 * 所以本轮（**无权**改 `webapp/build.gradle.kts`）把决策抽进无 android 依赖的
 * [PowerPolicy] 才有真行为断言。接线由 `PowerWiringTest` 另行把关。
 *
 * ⚠️ 本类**不**断言任何耗电。它只断言"宿主在该省电时不省"。
 */
class PowerPolicyTest {

    // ---- 绊线本体：离开前台 / 回合结束 ⇒ 不得持有 KEEP_SCREEN_ON ----------

    @Test
    fun `leaving foreground must not keep screen on even while a round is active`() {
        // 前置：回合进行中且 flag 已置位（"改前"的最坏情况）
        val flagsWithKeepOn = PowerPolicy.FLAG_KEEP_SCREEN_ON_BIT

        assertTrue("前置：回合进行中时确实持有常亮", PowerPolicy.hasKeepScreenOn(flagsWithKeepOn))
        assertTrue("前置：判据要求清", PowerPolicy.mustClearKeepScreenOn(foreground = false, roundActive = true))

        val after = PowerPolicy.applyKeepScreenOn(flagsWithKeepOn, foreground = false, roundActive = true)

        assertFalse(
            "绊线：离开前台后不得仍持有 FLAG_KEEP_SCREEN_ON（flags=$after）",
            PowerPolicy.hasKeepScreenOn(after),
        )
    }

    @Test
    fun `round end must not keep screen on even while in foreground`() {
        val flagsWithKeepOn = PowerPolicy.FLAG_KEEP_SCREEN_ON_BIT

        assertTrue(PowerPolicy.mustClearKeepScreenOn(foreground = true, roundActive = false))

        val after = PowerPolicy.applyKeepScreenOn(flagsWithKeepOn, foreground = true, roundActive = false)

        assertFalse(
            "绊线：回合结束后不得仍持有 FLAG_KEEP_SCREEN_ON（flags=$after）",
            PowerPolicy.hasKeepScreenOn(after),
        )
    }

    @Test
    fun `leaving foreground while round already ended must not keep screen on`() {
        val after = PowerPolicy.applyKeepScreenOn(
            PowerPolicy.FLAG_KEEP_SCREEN_ON_BIT,
            foreground = false,
            roundActive = false,
        )
        assertFalse(PowerPolicy.hasKeepScreenOn(after))
    }

    // ---- 不许把常亮砍过头：前台 + 回合进行中 = 唯一允许置位的情形 ----------

    @Test
    fun `foreground plus active round is the only case allowed to keep screen on`() {
        assertTrue(PowerPolicy.shouldKeepScreenOn(foreground = true, roundActive = true))

        val after = PowerPolicy.applyKeepScreenOn(0, foreground = true, roundActive = true)

        assertTrue(
            "前台 + 回合进行中 应当允许常亮（否则是把功能砍了，不是省电）",
            PowerPolicy.hasKeepScreenOn(after),
        )
    }

    // ---- 其余 flag 不得被误伤 ---------------------------------------------

    @Test
    fun `clearing keep screen on must not touch other flags`() {
        // 随手挑几个真实用到的窗口 flag 位：LAYOUT_IN_SCREEN(0x40) / FULLSCREEN(0x400)
        // / KEEP_SCREEN_ON(0x80) / LAYOUT_NO_LIMITS(0x200) / HARDWARE_ACCELERATED(0x10000000)
        val flags = 0x40 or 0x400 or PowerPolicy.FLAG_KEEP_SCREEN_ON_BIT or 0x200 or 0x10000000

        val after = PowerPolicy.applyKeepScreenOn(flags, foreground = false, roundActive = false)

        assertFalse(PowerPolicy.hasKeepScreenOn(after))
        assertEquals("其余 flag 位必须原样保留", 0x40 or 0x400 or 0x200 or 0x10000000, after)
    }

    @Test
    fun `clearing keep screen on is idempotent`() {
        val once = PowerPolicy.applyKeepScreenOn(PowerPolicy.FLAG_KEEP_SCREEN_ON_BIT, false, true)
        val twice = PowerPolicy.applyKeepScreenOn(once, false, true)

        assertEquals("clearFlags 语义：重复清不应改变结果", once, twice)
        assertFalse(PowerPolicy.hasKeepScreenOn(twice))
    }

    // ---- 常量必须与 android 框架一致（防手抄错） ---------------------------

    @Test
    fun `flag constant matches android framework value`() {
        // WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON = 0x00000080（自 API 1 未变）
        assertEquals(0x00000080, PowerPolicy.FLAG_KEEP_SCREEN_ON_BIT)
    }
}
