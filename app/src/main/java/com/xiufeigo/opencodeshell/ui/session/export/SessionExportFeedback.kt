package com.xiufeigo.opencodeshell.ui.session.export

/**
 * What one finished export attempt must tell the user — the "never silent"
 * half of [ExportFinish].
 *
 * The P0 report was "tap Export session → no picker, no toast, no file": the
 * saver's dismissed-picker branch called nothing, so the caller's busy flag
 * stayed true and every later tap bounced off its re-entry guard in silence.
 * Routing the outcome through this enum before any UI is built makes the
 * silence structurally impossible in two ways:
 *
 * - [ExportFeedback.of] is a `when` over the sealed [ExportFinish] with no
 *   `else`, so a new outcome cannot compile until it has feedback;
 * - every member here is rendered by the caller (`SessionScreen`'s toast
 *   switch is exhaustive over this enum for the same reason), and each one is
 *   distinct: a picker save, a Download/ bypass save, a cancel and a failure
 *   never share copy, so a device report says *which* branch ran.
 *
 * The **stages before** the saver (the payload read `SessionScreen` and
 * `ExportSessionButton` do themselves) are not [ExportFinish] outcomes: a
 * timed-out read, a transport 400 and a missing session id all end as
 * [FAILED] with the stage's own message in the caller's `reason` slot — the
 * `oc_export_fetch_failed` copy — which is what keeps a v9 device report
 * ("the mask closed and said nothing") decidable.
 */
enum class ExportFeedback {
    /** Written through the SAF picker to the location the user chose. */
    SAVED,

    /**
     * Written to `Download/` because the picker could not be used — no handler
     * (`SessionExportFallback.hasSaveHandler` false), a throwing launch, a
     * picker that never answered past [SessionExportFallback.SAF_TIMEOUT_MS],
     * or a picker write that then failed.
     */
    SAVED_TO_DOWNLOADS,

    /** The picker was dismissed (or the attempt was cancelled): the user said no. */
    CANCELLED,

    /** Every path was exhausted: the picked uri write, the `Download/` bypass. */
    FAILED;

    companion object {
        /**
         * Exhaustive over [ExportFinish] on purpose: see the class docs. The
         * nullable-free mapping is what the JVM test pins — a `Cancelled` that
         * maps to nothing was the bug.
         */
        fun of(finish: ExportFinish): ExportFeedback = when (finish) {
            is ExportFinish.Saved ->
                if (finish.viaFallback) SAVED_TO_DOWNLOADS else SAVED
            ExportFinish.Cancelled -> CANCELLED
            ExportFinish.Failed -> FAILED
        }
    }
}

/**
 * Which stage of an attempt the progress overlay is waiting on. The overlay
 * used to say only "Export session" while the payload read could legitimately
 * take a minute (v9 measured 12–75 s): a long wait and a hang looked
 * identical. Naming the stage — and its own deadline, see
 * `withExportDeadline` — makes the difference visible.
 */
enum class ExportPhase {
    /** Reading `{info, messages}`; bounded by `EXPORT_FETCH_TIMEOUT_MS`. */
    FETCHING,

    /** Handing the serialized JSON to SAF; bounded by the saver's watchdog. */
    SAVING,
}

/**
 * The single-shot terminal report of **one** export attempt: the first
 * [settle] wins, every later one is dropped.
 *
 * This is the structural half of "no overlay exit without feedback". The
 * overlay is visible exactly while an attempt is in flight and the attempt's
 * funnel is the **only** writer of that flag (pinned by
 * `SessionExportFeedbackTest.everyOverlayExitReportsThroughTheSingleWriter`),
 * so the two ways v9 saw the mask close in silence both fail to compile-in
 * silence: an exit that reports a second, later outcome cannot re-toast (the
 * ledger refuses it), and an exit that reports nothing cannot clear the
 * overlay at all.
 *
 * The two reports a single attempt can legitimately race are the Cancel tap
 * and a late payload answer; and the saver's own `ExportFinish` arriving after
 * [begin] reset the ledger is dropped the same way, so one attempt is exactly
 * one message. Main-thread confined (Compose state + toasts), like the screens
 * that own it.
 */
class ExportAttemptLedger {

    /** The outcome that settled the current attempt; `null` while it is in flight. */
    var reported: ExportFeedback? = null
        private set

    /** True once the attempt has told the user how it ended. */
    val settled: Boolean get() = reported != null

    /** Called when a new attempt starts; clears the previous attempt's report. */
    fun begin() {
        reported = null
    }

    /**
     * Reports [feedback] as the attempt's one outcome. Returns `true` when this
     * call was the first — the caller then owns the toast and the overlay
     * release — and `false` when the attempt already reported, in which case
     * the caller must stay silent (a second toast for one tap is the same bug
     * seen from the other side).
     */
    fun settle(feedback: ExportFeedback): Boolean {
        if (reported != null) return false
        reported = feedback
        return true
    }
}
