package com.xiufeigo.opencodeshell.ui.terminal.share

/**
 * What a background-shell create must do once the blocking `shellCreate`
 * (`POST /api/shell`) call returns. Pure so the refetch/error order is
 * unit-testable; the panel performs the effects on the main thread (the
 * create itself runs on IO).
 *
 * Success closes the inline form and refetches the list, so the `LazyColumn`
 * shows the new row without leaving and re-entering. Failure reports the
 * create error and still refetches (the server may have created it) — same
 * rule as [SessionTerminalCreateOutcome].
 */
object ShellCreateOutcome {

    sealed interface Effect {
        /** Close the inline command form. */
        data object CloseForm : Effect

        /** Re-run the list fetch (`reload` generation bump). */
        data object Refetch : Effect

        /** Show the create-failed error line. */
        data object ShowCreateError : Effect
    }

    fun effects(createdId: String?): List<Effect> =
        if (!createdId.isNullOrBlank()) {
            listOf(Effect.CloseForm, Effect.Refetch)
        } else {
            listOf(Effect.ShowCreateError, Effect.Refetch)
        }

    /**
     * Trims the inline form's input; `null` means "nothing to send" (blank),
     * so the panel shows the hint instead of posting an empty command (the
     * server requires `command`).
     */
    fun cleanCommand(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() }
}
