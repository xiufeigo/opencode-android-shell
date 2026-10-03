package com.xiufeigo.opencodeshell.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v46 tD 探针：`Move to…` 选目录后「Select this directory」看起来永久卡死。
 * 按环排查提交链 `pendingConfirm → onSelect → move 请求`：
 *
 * - 两段确认状态机（[confirmStep]）是纯函数、无 await：首 tap 必定 arm，
 *   次 tap 必定提交 **arm 时的路径**。这里 pin 住它——任何把次 tap 变成
 *   重 arm（或把提交路径换成漂移后的当前路径）的改动都会回到这张卡死的
 *   截图（`research/v46_verify/tD/tD_25_cleanrepro_stuck.png`）。
 * - `onSelect → confirmMove → moveSessionTo` 之间同样无 await；实测挂死的
 *   环是 `moveSessionTo` 在 probe 窗口内的静默返回（无请求、无回调），已在
 *   `ChatViewModel` 内修为 promise 必达 + 乐观行即时反馈。
 */
class MoveConfirmStepTest {

    @Test
    fun firstTapAlwaysArmsTheCurrentPath() {
        val step = confirmStep(null, "/home/xiufeigo/v36projB")
        assertTrue(step is DirectoryConfirm.Armed)
        assertEquals("/home/xiufeigo/v36projB", (step as DirectoryConfirm.Armed).path)
    }

    @Test
    fun secondTapSubmitsTheArmedPathNotTheDriftedOne() {
        // The path may drift between the two taps (probe adoption relists and
        // renames `currentPath`); the submit must carry what the user armed.
        val step = confirmStep("/home/xiufeigo/v36projB", "/home/xiufeigo/v35projB")
        assertTrue(step is DirectoryConfirm.Submit)
        assertEquals("/home/xiufeigo/v36projB", (step as DirectoryConfirm.Submit).path)
    }

    @Test
    fun armingIsIdempotentAcrossRecompositions() {
        // `pendingConfirm` survives recomposition, so rendering the armed card
        // again must still submit — never silently re-arm.
        val armed = confirmStep(null, "/home/xiufeigo/v36projB") as DirectoryConfirm.Armed
        val submit = confirmStep(armed.path, armed.path)
        assertTrue(submit is DirectoryConfirm.Submit)
        assertEquals(armed.path, (submit as DirectoryConfirm.Submit).path)
    }
}
