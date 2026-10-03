package com.xiufeigo.opencodeshell.ui.session.composer

import com.xiufeigo.opencodeshell.ChatViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/**
 * W5-C B8: one draft per composer key (the session id), plus the "the send
 * failed, give the text back" slot.
 *
 * U-3: the draft is a handoff, not just text — upstream `session/handoff.ts`
 * carries the file selection (`setSessionHandoff`, `{files}`) and the in-flight
 * user message (`setSessionMessageHandoff`) across sessions, so a draft keeps
 * the attachments and the referenced message alongside the text. Every read and
 * write of the new fields stays inside the composer domain (this store plus
 * `Composer`'s restore effect); the view model only ever touches text.
 *
 * Why a process-wide store instead of `rememberSaveable` alone: the composer is
 * re-created whenever the screen switches sessions (`SessionScreen` rebuilds the
 * whole region on a session switch) and the app has several screens that can own
 * a composer. Upstream keeps the same state in `composer/persistence.tsx`
 * (draft + cursor per session key), and the `Screen`-switch survival requirement
 * is exactly why the value cannot live in the composition.
 *
 * v16: the editor's own `rememberSaveable` slots are scoped by the same key
 * ([saveableSlot]), because a saveable slot named after its call site alone hands
 * one session's editor to the next one whenever the nav entry is reused.
 *
 * The store is deliberately dumb and synchronous so it is JVM-testable:
 * [text] / [set] / [restore] / [takeRestore] plus the P1-C switch-path trio
 * ([stash] records the whole handoff on a switch, [restoreHandoff] hands a
 * failed payload back without wiping the stashed ref, [appendLine] appends a
 * review line without touching the halves, [takeMessageRef] consumes the ref).
 * The composer is the only writer of
 * [set]; [restore] is called by the view model when a prompt POST fails
 * (`composer/submit.ts:407-418` `failSubmission` → `restore()`), so the text the
 * user typed is never lost.
 */
object ComposerDrafts {

    /** Key used when the caller has no session id (pre-session draft / previews). */
    const val DEFAULT_KEY = "oc-draft-default"

    /** v16: [saveableSlot] name for the editor's `TextFieldValue`. */
    const val SLOT_EDITOR_VALUE = "editorValue"

    /** v16: [saveableSlot] name for the editor's shell mode (`!command`). */
    const val SLOT_SHELL_MODE = "shellMode"

    /**
     * [pendingRestore] is the one-shot flag that makes the composer adopt [text]
     * even though the user is not typing: it is set by [restore] and cleared by
     * [takeRestore].
     *
     * U-3: [attachments] is the handoff's file half (`handoff.ts`
     * `setSessionHandoff`, `{files}`) and [messageRef] its message half
     * (`setSessionMessageHandoff`, the in-flight user message) — both survive a
     * session switch or a failed send exactly like the text does.
     */
    data class Draft(
        val text: String = "",
        val pendingRestore: Boolean = false,
        val attachments: List<DraftAttachment> = emptyList(),
        val messageRef: MessageRef? = null,
        /**
         * v84 P2: this pending restore *grew* the editor's own text
         * ([appendLine]) instead of handing a payload back into a cleared one.
         * The one kind of restore that may write over text the user can see —
         * see [adoptRestore]. Transient like [pendingRestore]: never persisted.
         */
        val restoreAppend: Boolean = false,
    )

    /**
     * U-3: one draft attachment — the same three fields as
     * [ChatViewModel.Attachment] as pure data (no view-model dependency for the
     * store's readers). [toAttachment] / [ChatViewModel.Attachment.toDraft]
     * convert at the composer boundary.
     */
    data class DraftAttachment(
        val mime: String,
        val filename: String,
        val dataUrl: String,
    ) {
        fun toAttachment(): ChatViewModel.Attachment =
            ChatViewModel.Attachment(mime = mime, filename = filename, dataUrl = dataUrl)
    }

    /**
     * U-3: the referenced message half of the handoff — the id to scroll back
     * to (or to quote) plus a short excerpt so a not-yet-loaded timeline can
     * still label it.
     */
    data class MessageRef(val messageID: String, val excerpt: String = "")

    private val _state = MutableStateFlow<Map<String, Draft>>(emptyMap())

    /** All live drafts, observable so the composer can adopt a restored one. */
    val state: StateFlow<Map<String, Draft>> = _state.asStateFlow()

    /** Session id (or [DEFAULT_KEY]) → store key. Blank ids fall back too. */
    fun key(sessionID: String?): String = sessionID?.takeIf { it.isNotBlank() } ?: DEFAULT_KEY

    /**
     * v16: the `rememberSaveable` **slot key** one piece of composer editor state
     * ([slot], one of [SLOT_EDITOR_VALUE] / [SLOT_SHELL_MODE]) is stored under for
     * [draftKey].
     *
     * Passing the draft key as a `rememberSaveable` *input* is not enough to keep
     * two sessions apart. `rememberSaveable` restores as `restored ?: init()`
     * (`runtime-saveable` `RememberSaveable.kt`), and the restore is looked up by
     * the slot key alone: with the default key — the call site's composite hash —
     * a restored value beats the inputs' `init()` no matter which session the
     * inputs name. That slot then gets refilled with the *previous* session's
     * value, because this app's session route is reused across ids: `ui/App.kt`
     * navigates with `launchSingleTop`, and `NavController.launchSingleTopInternal`
     * rebuilds the entry through `NavBackStackEntry(oldEntry, args)`, which keeps
     * `entry.id` — so `SaveableStateHolder` (keyed by `BackStackEntryIdViewModel.id`)
     * hands the outgoing session's saveable map to the incoming one.
     *
     * That is the measured v16 leak (`research/v16_verify/misc/`): session B's
     * `DRAFT_V16 keepme` came back in a freshly created session C, while C's
     * attachment list stayed empty (attachments are screen state, not saveable) —
     * the text was restored from the *editor slot*, never read from C's draft.
     * Naming the slot after the draft key keeps the halves honest: C's slot is not
     * in the restored map, so `init()` runs and reads [text] for C, which is empty.
     * An intentional handoff still works, because it writes the *draft* ([stash] /
     * [restore] / [move]) rather than leaning on a stale editor slot.
     */
    fun saveableSlot(draftKey: String, slot: String): String = "$SAVEABLE_SLOT_PREFIX$slot:$draftKey"

    fun text(key: String): String = _state.value[key]?.text.orEmpty()

    fun pendingRestore(key: String): Boolean = _state.value[key]?.pendingRestore == true

    /** U-3: the draft's file half (empty when the draft carries text only). */
    fun attachments(key: String): List<DraftAttachment> = _state.value[key]?.attachments.orEmpty()

    /** U-3: the draft's referenced message, or null. */
    fun messageRef(key: String): MessageRef? = _state.value[key]?.messageRef

    /**
     * Records what the editor currently holds. No-op when nothing changed, so the
     * caller may call it on every keystroke without growing the map.
     *
     * U-3: a keystroke never carries the handoff — the stored attachments and
     * message ref survive it untouched.
     */
    fun set(key: String, text: String) {
        _state.update { current ->
            val existing = current[key]
            if (existing != null && existing.text == text && !existing.pendingRestore) {
                current
            } else {
                // A keystroke is the user's own text: it supersedes any pending
                // restore, appended line included (v84 P2).
                val next = (existing ?: Draft()).copy(
                    text = text,
                    pendingRestore = false,
                    restoreAppend = false,
                )
                current + (key to next)
            }
        }
    }

    /**
     * U-3: records the draft's file half (e.g. the composer hands its pending
     * attachments over before a session switch). An empty list clears the half.
     */
    fun setAttachments(key: String, attachments: List<DraftAttachment>) {
        _state.update { current ->
            val existing = current[key]
            // P1-C: a send-clear on an already-dropped key must not resurrect
            // map junk — the screen mirrors every list change, including the
            // post-send empty one.
            if (existing == null && attachments.isEmpty()) return@update current
            val base = existing ?: Draft()
            if (base.attachments == attachments) current
            else current + (key to base.copy(attachments = attachments))
        }
    }

    /**
     * U-3: records (or, with null, clears) the draft's referenced message.
     */
    fun setMessageRef(key: String, ref: MessageRef?) {
        _state.update { current ->
            val existing = current[key] ?: Draft()
            if (existing.messageRef == ref) current
            else current + (key to existing.copy(messageRef = ref))
        }
    }

    /**
     * A send failed: hand [text] back to the editor for that key.
     *
     * U-3: hands the full handoff back — the failed payload's attachments and
     * referenced message ride along, so the restore effect can re-seat them.
     */
    fun restore(
        key: String,
        text: String,
        attachments: List<DraftAttachment> = emptyList(),
        messageRef: MessageRef? = null,
    ) {
        if (text.isEmpty() && attachments.isEmpty() && messageRef == null) return
        _state.update { it + (key to Draft(text, pendingRestore = true, attachments, messageRef)) }
    }

    /**
     * v58: the leave-flush ([Composer] disposes its editor effect when the
     * composer leaves the composition or re-keys onto another session) may only
     * write while the editor is still bound to [boundKey] — the key the effect
     * was created for. Returns whether the write happened.
     *
     * A session switch is the reason this guard exists. The composer's instance
     * survives a switch (the session route is reused across ids), so the outgoing
     * effect is disposed *after* the new key is bound; without the guard the
     * outgoing effect wrote the incoming session's editor text under the
     * **outgoing** key (and an editor event that arrived late — a queued IME
     * commit, which `setText` cannot attribute — was accepted into whichever key
     * was live when it landed). Measured on emulator-5556 (`research/v58_verify/
     * 5556/55_disc.png`): session A's unsent 「say hello」 landed inside session
     * B's editor, i.e. a mis-send surface, not just a visual glitch.
     *
     * Dropping the flush on a switch loses nothing: every editor mutation goes
     * through [set] first, so the text this flush would save was already stored
     * under the key it belonged to.
     */
    fun flush(boundKey: String, currentKey: String, text: String): Boolean {
        if (boundKey != currentKey || text.isEmpty()) return false
        set(boundKey, text)
        return true
    }

    /**
     * OBS-1 (v78, emulator-5558): whether the composer newly bound to [key]
     * must drop what its editor shows instead of keeping it. True only when
     * every half agrees the text is foreign: the editor is non-empty, yet the
     * key holds no draft of its own and no failed-send/revert restore is armed
     * for it — so the text came from another session's slot, never from this
     * one, and a new session opens empty.
     *
     * The last clause is the cold-start line: drafts live in this process, so
     * after a process death the store is empty while the editor slot still
     * restores the session's own text — that restore is kept. A live switch
     * always leaves the outgoing session's draft behind, so a non-empty store
     * with nothing under [key] can only mean the editor is showing text that
     * is not this session's.
     *
     * Pure (reads only) so the rule is pinned by unit test; the composer calls
     * it on every draft-key switch. A `true` never disturbs an intentional
     * path: a failed send arms [pendingRestore], a returning session finds its
     * own text, and an attachment-only stash leaves the (already empty) editor
     * alone while its halves still adopt through their own bridges.
     */
    fun dropForeignSeed(key: String, editorText: String): Boolean =
        editorText.isNotEmpty() && text(key).isEmpty() && !pendingRestore(key) && state.value.isNotEmpty()

    /**
     * P1-C: stashes the outgoing editor state on a switch path (top-tab leave,
     * drawer session switch, home round-trip, session re-entry). Unlike [set]
     * (a keystroke, text-only) this records the whole handoff at once; unlike
     * [restore] it does not arm a one-shot — the composer adopts a stash on
     * every mount, so a stashed draft survives an arbitrary number of switches.
     * Stashing an empty handoff drops the key instead of growing the map.
     */
    fun stash(
        key: String,
        text: String,
        attachments: List<DraftAttachment> = emptyList(),
        messageRef: MessageRef? = null,
    ) {
        if (text.isEmpty() && attachments.isEmpty() && messageRef == null) {
            clear(key)
            return
        }
        _state.update { it + (key to Draft(text = text, attachments = attachments, messageRef = messageRef)) }
    }

    /**
     * P1-C: a failed send hands the payload back through the same handoff shape
     * ([restore]), but the view model never knew the stashed message ref — a
     * plain [restore] would wipe it. This keeps whatever ref the key holds and
     * arms the one-shot, so text + attachments + ref all come back together.
     */
    fun restoreHandoff(
        key: String,
        text: String,
        attachments: List<DraftAttachment> = emptyList(),
    ) {
        if (text.isEmpty() && attachments.isEmpty()) return
        _state.update { current ->
            val ref = current[key]?.messageRef
            current + (key to Draft(text, pendingRestore = true, attachments, ref))
        }
    }

    /**
     * P1-C: appends [text] below the live draft (the review line-comment path,
     * `ChatViewModel.appendComposerText`) without touching the handoff halves —
     * a plain [restore] here would drop staged attachments. Blank input is a
     * no-op; the write arms the one-shot so a mounted composer adopts the line
     * and an unmounted one picks it up on mount.
     */
    fun appendLine(key: String, text: String) {
        if (text.isBlank()) return
        _state.update { current ->
            val existing = current[key]
            val merged = if (existing?.text.isNullOrBlank()) text
            else existing!!.text.trimEnd() + "\n" + text
            // v84 P2: an append *grows* the editor's own text, so it is the one
            // restore allowed to write over what the user can see
            // ([adoptRestore]).
            current + (
                key to (existing ?: Draft()).copy(
                    text = merged,
                    pendingRestore = true,
                    restoreAppend = true,
                )
            )
        }
    }

    /**
     * U-3: moves a draft across sessions — the native shape of upstream's
     * cross-session handoff (`handoff.ts` §`getSession*Handoff`: a new session
     * inherits the files + message). The source key is dropped; a missing
     * source is a no-op. The target is expected to be fresh — a source draft
     * replaces whatever sits there.
     */
    fun move(fromKey: String, toKey: String) {
        if (fromKey == toKey) return
        _state.update { current ->
            val source = current[fromKey] ?: return@update current
            (current - fromKey) + (toKey to source)
        }
    }

    /**
     * Consumes the restore request and answers the text the editor must show —
     * or `null` when the editor must be left exactly as it is.
     *
     * v84 P2 「后台中断后草稿粘连脏消息」 (emulator-5554
     * `research/v84_verify/5554/49_bg2_state.png`, `55_deeplink_landing.png`): a
     * handback is a race with the user. The failed send that arms it is answered
     * by a coroutine — a timeout can resolve seconds after the composer is back
     * on screen — and the effect that adopted it wrote the payload straight over
     * whatever the field held by then: `E2ECHK5554 bg notify` + the new line came
     * out as one glued message (`…notifyE2ECHK5554 bg2 retry`), and a landing
     * mid-typing spliced the payload into the middle of the new text
     * (`E2ECHK5554 deeplink cE2ECHK5554 notify deep linkheck`).
     *
     * [editorText] is what the field holds now, so the rule can tell the two
     * moments apart ([adoptRestore]): a payload only lands in an editor the user
     * has not written into. A send clears the editor before it is dispatched
     * (`Composer.submit`), so the ordinary failed-send handback still lands —
     * only a *late* one, arriving after the user started the next message, is
     * refused. Consumed either way: the answer is this one shot.
     *
     * Comparison is on the folded text ([HalfWidthText.normalize]): a draft
     * written by an older build (or before the W5-F fold) may hold `／`, which
     * the editor never shows. The returned text is the stored one — the composer
     * folds it on the way in, as it always did.
     */
    fun takeRestore(key: String, editorText: String): String? {
        val pending = _state.value[key] ?: return null
        if (!pending.pendingRestore) return null
        _state.update {
            it + (key to pending.copy(pendingRestore = false, restoreAppend = false))
        }
        return adoptRestore(
            editorText = HalfWidthText.normalize(editorText),
            restored = HalfWidthText.normalize(pending.text),
            append = pending.restoreAppend,
        )?.let { pending.text }
    }

    /**
     * v84 P2: the rule behind [takeRestore] — the text the editor must adopt, or
     * `null` when it must be left alone.
     *
     * * an empty editor takes the payload (the failed-send handback: the send
     *   cleared the field before dispatching);
     * * an editor that already holds it takes nothing — re-writing the same
     *   value would be a needless `restartInput()` for the IME (the v66
     *   mechanism);
     * * [append] (the review line comment, [appendLine]) grew this editor's own
     *   text, so writing the stored text back is the intent — the field must show
     *   the appended line;
     * * **anything else is the user's own text, and it wins.** Never spliced,
     *   never overwritten: the payload that lost the race is dropped from the
     *   editor (its key still holds it until the next keystroke).
     *
     * Pure, so the rule is pinned by unit test.
     */
    internal fun adoptRestore(editorText: String, restored: String, append: Boolean): String? {
        if (restored.isEmpty()) return null
        if (editorText.isBlank()) return restored
        if (editorText == restored) return null
        if (append && restored.startsWith(editorText.trimEnd())) return restored
        return null
    }

    /**
     * P1-C: consumes the stashed message ref (mount-adopt scrolls back to it
     * once). Attachments need no consume — the screen mirrors them on every
     * change, so re-adopting is a no-op merge — but a ref re-fired on every
     * mount would scroll the timeline on every tab return.
     */
    fun takeMessageRef(key: String): MessageRef? {
        val ref = _state.value[key]?.messageRef ?: return null
        _state.update { current ->
            val existing = current[key] ?: return@update current
            current + (key to existing.copy(messageRef = null))
        }
        return ref
    }

    /** Drops one key's draft (a sent prompt, a closed session). */
    fun clear(key: String) {
        _state.update { it - key }
    }

    /** Test seam: forget every draft. */
    fun reset() {
        _state.value = emptyMap()
    }

    /**
     * U-3: serializes a draft. `pendingRestore` is transient editor state (a
     * live one-shot flag), so it is never persisted — a decoded draft always
     * reads clean.
     */
    fun Draft.toJson(): String {
        val root = JSONObject().put(KEY_TEXT, text)
        if (attachments.isNotEmpty()) {
            val files = JSONArray()
            for (attachment in attachments) {
                files.put(
                    JSONObject()
                        .put(KEY_MIME, attachment.mime)
                        .put(KEY_FILENAME, attachment.filename)
                        .put(KEY_DATA_URL, attachment.dataUrl),
                )
            }
            root.put(KEY_ATTACHMENTS, files)
        }
        val ref = messageRef
        if (ref != null) {
            root.put(
                KEY_MESSAGE_REF,
                JSONObject()
                    .put(KEY_MESSAGE_ID, ref.messageID)
                    .put(KEY_EXCERPT, ref.excerpt),
            )
        }
        return root.toString()
    }

    /**
     * U-3: decodes a draft, tolerating old text-only payloads (missing
     * `attachments` / `messageRef` keys simply read empty) — and returning null
     * for blank or malformed input instead of throwing, so a corrupt entry can
     * never wedge the composer.
     */
    fun draftFromJson(raw: String?): Draft? {
        if (raw.isNullOrBlank()) return null
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val files = root.optJSONArray(KEY_ATTACHMENTS)
        val attachments = (0 until (files?.length() ?: 0)).mapNotNull { index ->
            val file = files?.optJSONObject(index) ?: return@mapNotNull null
            DraftAttachment(
                mime = file.optString(KEY_MIME),
                filename = file.optString(KEY_FILENAME),
                dataUrl = file.optString(KEY_DATA_URL),
            )
        }
        val refNode = root.optJSONObject(KEY_MESSAGE_REF)
        val ref = refNode?.optString(KEY_MESSAGE_ID).takeIf { !it.isNullOrEmpty() }?.let {
            MessageRef(it, refNode.optString(KEY_EXCERPT))
        }
        return Draft(
            text = root.optString(KEY_TEXT),
            attachments = attachments,
            messageRef = ref,
        )
    }

    private const val KEY_TEXT = "text"
    private const val KEY_ATTACHMENTS = "attachments"
    private const val KEY_MIME = "mime"
    private const val KEY_FILENAME = "filename"
    private const val KEY_DATA_URL = "dataUrl"
    private const val KEY_MESSAGE_REF = "messageRef"
    private const val KEY_MESSAGE_ID = "messageID"
    private const val KEY_EXCERPT = "excerpt"

    /** v16: namespaces [saveableSlot] away from every other saveable in the app. */
    private const val SAVEABLE_SLOT_PREFIX = "oc-composer-"
}

/**
 * U-3: composer-boundary converter — screen attachments into the draft store.
 * (The reverse is [ComposerDrafts.DraftAttachment.toAttachment].)
 */
fun ChatViewModel.Attachment.toDraft(): ComposerDrafts.DraftAttachment =
    ComposerDrafts.DraftAttachment(mime = mime, filename = filename, dataUrl = dataUrl)
