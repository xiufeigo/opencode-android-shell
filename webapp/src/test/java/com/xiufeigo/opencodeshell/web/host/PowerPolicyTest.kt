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
        assertTrue(PowerPolicy.mustClearKeepScreenOn(foreground = false, roundActive = true))
        assertFalse(PowerPolicy.shouldKeepScreenOn(foreground = false, roundActive = true))
    }

    @Test
    fun `round end must not keep screen on even while in foreground`() {
        assertTrue(PowerPolicy.mustClearKeepScreenOn(foreground = true, roundActive = false))
        assertFalse(PowerPolicy.shouldKeepScreenOn(foreground = true, roundActive = false))
    }

    @Test
    fun `leaving foreground while round already ended must not keep screen on`() {
        assertTrue(PowerPolicy.mustClearKeepScreenOn(foreground = false, roundActive = false))
        assertFalse(PowerPolicy.shouldKeepScreenOn(foreground = false, roundActive = false))
    }

    // ---- 不许把常亮砍过头：前台 + 回合进行中 = 唯一允许置位的情形 ----------

    @Test
    fun `foreground plus active round is the only case allowed to keep screen on`() {
        assertTrue(
            "前台 + 回合进行中 应当允许常亮（否则是把功能砍了，不是省电）",
            PowerPolicy.shouldKeepScreenOn(foreground = true, roundActive = true),
        )
        assertFalse(PowerPolicy.mustClearKeepScreenOn(foreground = true, roundActive = true))
    }

    // ---- 常量必须与 android 框架一致（防手抄错） ---------------------------

    @Test
    fun `flag constant matches android framework value`() {
        // WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON = 0x00000080（自 API 1 未变）
        assertEquals(0x00000080, PowerPolicy.FLAG_KEEP_SCREEN_ON_BIT)
    }
}
