package com.xiufeigo.opencodeshell.ui.terminal.share

/**
 * What a session-terminal create must do once the blocking
 * `createSessionTerminal` call returns. Pure so the attach-then-refetch order
 * is unit-testable; the panel performs the effects on the main thread (the
 * create itself runs on IO).
 *
 * Success attaches the new terminal as a tab **and** refetches the list, so
 * the `LazyColumn` shows it without leaving and re-entering. Failure reports
 * the create error and still refetches (the server may have created it).
 */
object SessionTerminalCreateOutcome {

    sealed interface Effect {
        /** Adopt the created terminal as a tab; carries its id. */
        data class Attach(val id: String) : Effect

        /** Re-run the list fetch (`reload` generation bump). */
        data object Refetch : Effect

        /** Show the create-failed error line. */
        data object ShowCreateError : Effect
    }

    fun effects(createdId: String?): List<Effect> =
        if (!createdId.isNullOrBlank()) {
            listOf(Effect.Attach(createdId), Effect.Refetch)
        } else {
            listOf(Effect.ShowCreateError, Effect.Refetch)
        }
}
