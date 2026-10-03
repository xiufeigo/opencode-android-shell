package com.xiufeigo.opencodeshell.v2

import com.xiufeigo.opencodeshell.ChatStore
import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.ui.home.FsBrowse
import com.xiufeigo.opencodeshell.ui.session.export.SessionExport
import com.xiufeigo.opencodeshell.ui.session.timeline.RawParts
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/**
 * Wave 1 v2 transport: [V2Api] plus the v2 streams, converging on the same
 * [ChatStore] representations the UI already renders (`UiMessage`/`UiPart`/
 * `RunStatus`), so the UI layer needs zero changes.
 *
 * REST mapping (A5 §2, verified live on 2.0.11 + openapi.json, 113 paths):
 * catalogs unwrap `{location, data}`, session/message unwrap `{data}`, project
 * and config are bare arrays, message pages carry an opaque cursor. Query
 * params: catalogs use `location[directory]`, session list uses bare
 * `directory`, session-scoped reads take no location.
 *
 * Prompt semantics (A5 §2): v2 has no `prompt_async` parts array. Sends are
 * flat `{text, files?, agents?, skills?, metadata?, delivery?, resume?}`;
 * model/agent/variant are switched via their own routes *before* the send
 * (steer only — queue must not reconfigure the running turn), matching
 * upstream `composer/submit.ts`.
 *
 * Blocking calls (like both clients): call from an IO dispatcher.
 */
class V2Transport(
    private val api: V2Api,
    /**
     * Pause between two confirmation re-reads of a rename
     * ([RenameConfirmation.RETRY_DELAY_MS]).
     *
     * A seam, not a preference: the bounded window is what a JVM test has to
     * drive (`renameSession` blocks between attempts), and a test that asserts
     * the window must not spend it sleeping.
     */
    private val renameConfirmDelayMs: Long = RenameConfirmation.RETRY_DELAY_MS,
) : SessionTransport {

    override val generation: ServerGeneration = ServerGeneration.V2

    /**
     * Export drains currently running, keyed by session + directory.
     *
     * The ⋮ Export menu, the context/usage tab and Home's row action all read
     * the same `{info, messages}` frame, and they can fire in the same frame —
     * each one used to run its own paged drain of the whole transcript. One
     * entry shares a single drain across concurrent callers; the entry is
     * dropped as soon as the leader finishes, so nothing is cached across
     * calls and a later read still sees fresh rows.
     */
    private val exportInFlight = ConcurrentHashMap<String, FutureTask<SessionExport.Frame>>()

    override fun close() {
        api.close()
    }

    // ------------------------------------------------------------ snapshot

    override fun snapshotMessages(sessionID: String, limit: Int): List<ChatStore.UiMessage> {
        // `order = "desc"` (newest first), while the turn markers are
        // order-based — "every step since the previous marker belongs to one
        // turn" (`schema/src/session-message.ts:284-286`) — so the fold runs
        // oldest → newest, the order the store keeps its own list in.
        var out: List<ChatStore.UiMessage> = emptyList()
        for (row in api.messages(sessionID, limit = limit, order = "desc").data.asReversed()) {
            if (row.type == V2Api.Message.TYPE_IDLE) {
                // The server's own end-of-turn record. Never a drawn row
                // (`session-ui/src/timeline/projection.ts:766` excludes it from
                // its notices); it decides whether the turn it closes was a
                // Stop, which is the only record a pre-stream abort has.
                out = ChatStore.applyTurnMarker(
                    out,
                    ChatStore.TurnMarker(
                        outcome = optStringOrNull(row.raw, "outcome").orEmpty(),
                        time = row.timeCreated,
                    ),
                )
                continue
            }
            val ui = row.toUi() ?: continue
            out = out + ui
        }
        return out
    }

    override fun fetchSession(sessionID: String): SessionRow? = try {
        api.session(sessionID).toRow()
    } catch (_: Exception) {
        null
    }

    override fun fetchPermissions(sessionID: String): List<OpenCodeClient.PermissionRequest> =
        // Two views exist (`/permission/request` location-wide + session list);
        // the merge is keyed by session, so the session view is authoritative.
        api.sessionPermissions(sessionID).map { it.toV1() }

    override fun activeRunStatus(sessionID: String): String? = try {
        api.activeSessions()[sessionID]?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    // --------------------------------------------------------------- writes

    override fun sendPrompt(
        sessionID: String,
        text: String,
        files: List<V2Api.PromptFile>,
        messageID: String?,
        delivery: String?,
        resume: Boolean?,
    ): String? = sendStructuredPrompt(
        sessionID = sessionID,
        text = text,
        files = files,
        messageID = messageID,
        delivery = delivery,
        resume = resume,
    )

    /**
     * WAVE 2.4b: the full flat prompt body (`request.ts`
     * `buildPromptRequest`) — `files` mentions-first, `agents[]`/`skills[]`,
     * `metadata.displayText`, `delivery`. Returns the admitted user-inbox id
     * (`msg_…`) or null when blank (never measured, but tolerated).
     *
     * W2 A-7: `files` entries may carry `mention{start,end,text}`
     * (`V2Api.PromptFile.mention`) and `agents`/`skills` are built with
     * `V2Api.PromptAgent`/`V2Api.PromptSkill` — the parameter types are
     * unchanged (raw `List<JSONObject>`), so a caller passing a bare `{name}`
     * still sends exactly what it sent before.
     */
    fun sendStructuredPrompt(
        sessionID: String,
        text: String,
        files: List<V2Api.PromptFile>,
        messageID: String?,
        delivery: String?,
        resume: Boolean?,
        agents: List<JSONObject>? = null,
        skills: List<JSONObject>? = null,
        metadata: JSONObject? = null,
    ): String? {
        val admitted = api.prompt(
            sessionID = sessionID,
            text = text,
            files = files,
            agents = agents,
            skills = skills,
            metadata = metadata,
            delivery = delivery,
            resume = resume,
            messageID = messageID,
        )
        // WAVE 2.4b: the admitted user-inbox id (`msg_…`) keys the optimistic
        // queue row; blank (never measured, but tolerated) means "no key".
        return admitted.id.takeIf { it.isNotBlank() }
    }

    override fun switchAgent(sessionID: String, agent: String) {
        api.switchAgent(sessionID, agent)
    }

    /**
     * `POST /api/session/{id}/model` `{model:{id,providerID,variant?}}`
     * (`V2Api.ModelRef` → `api.switchModel`). `modelID` is the **ref id** — the
     * picker's entry-level id ([OpenCodeClient.Providers.Model.entryID]): the
     * server resolves `session.model.id` against `Model.Info.id`
     * (`_upstream/packages/core/src/session/runner/model.ts`), which is the only
     * half that tells a base entry from its `-fast`/`-pro` siblings. `variant`
     * is the reasoning-effort label; `null`/blank omits the key so the server
     * keeps its own default (`V2Api.ModelRef.toJson`).
     */
    override fun switchModel(sessionID: String, providerID: String, modelID: String, variant: String?) {
        api.switchModel(sessionID, V2Api.ModelRef(modelID, providerID, variant))
    }

    override fun sendShell(sessionID: String, command: String, messageID: String?) {
        api.shell(sessionID, command, messageID)
    }

    override fun sendCommand(
        sessionID: String,
        name: String,
        text: String,
        files: List<V2Api.PromptFile>,
        delivery: String?,
    ) {
        api.command(sessionID, name, text, files, delivery = delivery)
    }

    override fun interrupt(sessionID: String, resume: Boolean?): Boolean =
        api.interrupt(sessionID, resume)

    override fun replyPermission(sessionID: String, requestID: String, decision: String, message: String?) {
        api.replyPermission(sessionID, requestID, decision, message)
    }

    // ------------------------------------------ v2 session control (P1 seams)

    override fun backgroundSession(sessionID: String) {
        api.background(sessionID)
    }

    override fun setSessionEnvironment(sessionID: String, variables: Map<String, String>) {
        api.environment(sessionID, variables)
    }

    /**
     * The generate route carries `{prompt}` alone, so a model has to be **on
     * the session** before the call: pin it first (`POST /model`, the same
     * shape [switchModel] sends), then generate. Without a pin, a session whose
     * `model` is null answers 503 `Missing API key` (device v14).
     */
    override fun generateFromContext(
        sessionID: String,
        prompt: String,
        model: SessionModelSync.Pin?,
    ): String {
        if (model != null) {
            api.switchModel(sessionID, V2Api.ModelRef(model.modelID, model.providerID, model.variant))
        }
        return api.generate(sessionID, prompt)
    }

    override fun moveSession(sessionID: String, directory: String, delivery: String?) {
        api.moveSession(sessionID, directory, delivery)
    }

    override fun sendSynthetic(
        sessionID: String,
        text: String,
        description: String?,
        id: String?,
        delivery: String?,
        resume: Boolean?,
        metadata: JSONObject?,
    ): String? {
        val row = api.synthetic(sessionID, text, description, id, delivery, resume, metadata)
        return row.optString("id", "").takeIf { it.isNotEmpty() }
    }

    override fun markSessionViewed(sessionID: String, idle: Long) {
        api.view(sessionID, idle)
    }

    // ------------------------------------------------- forms (v2 replaces question REST)

    /**
     * WAVE 2.4b: `GET /api/session/{id}/inbox` items (raw JSON; the VM maps
     * them to [ComposerQueue.Row][com.xiufeigo.opencodeshell.ui.session.composer.ComposerQueue.Row]).
     */
    fun fetchInbox(sessionID: String): List<JSONObject> = api.sessionInbox(sessionID)

    /**
     * WAVE 2.4b: promote a queued follow-up to the running turn
     * (`PATCH …/inbox/{inboxID}{delivery:"steer"}`, `queue.ts` steer).
     */
    fun updateInboxDelivery(sessionID: String, inboxID: String, delivery: String) {
        api.updateInboxDelivery(sessionID, inboxID, delivery)
    }

    /**
     * WAVE 2.4b: drop a queued follow-up (`DELETE …/inbox/{inboxID}`,
     * `queue.ts` remove).
     */
    fun cancelInbox(sessionID: String, inboxID: String) {
        api.cancelInbox(sessionID, inboxID)
    }

    /** `GET /api/session/{id}/form` → pending forms for the session. */
    fun fetchForms(sessionID: String): List<V2Api.FormInfo> = api.sessionForms(sessionID)

    /** `POST …/form/{formID}/reply{answer}` → 204. */
    fun replyForm(sessionID: String, formID: String, answer: Map<String, Any?>) {
        api.replyForm(sessionID, formID, answer)
    }

    // -------------------------------------------------------------- sessions

    override fun createSession(directory: String?, title: String?): String =
        api.createSession(agent = null, model = null, id = null, title = title, directory = directory).id

    /**
     * v81 P1: `PATCH` then a **bounded** re-read, with every answer kept apart.
     *
     * `PATCH` answers `204` with no body, so the write is confirmed by re-reading
     * the session — and only a title the server holds **exactly** counts
     * ([RenameConfirmation.confirmed]): a mismatch (server-side
     * regenerate/normalize, or a read racing another writer) means the rename was
     * NOT applied as requested, and returning the foreign title would let the
     * caller mirror a title the user never typed. Upstream
     * (`server/src/handlers/session.ts`, `session.update`) never deletes: unknown
     * id → 404 `SessionNotFoundError`, empty title → AI regenerate.
     *
     * Two things changed in v81, both from the measured 5556 failure (the server
     * log's `PATCH … http.status=500` on a session whose project directory
     * `/mnt/c/x` does not exist, reported to the user as 「check the connection」):
     *
     * - the non-2xx code is **kept**, not swallowed into the same `null` a socket
     *   error produces — [RenameResult.Refused] vs [RenameResult.Unreachable], and
     *   `404` becomes [RenameResult.Gone] so the caller can drop the entry;
     * - the re-read repeats within [RenameConfirmation.ATTEMPTS] (the write path
     *   answers before the projector is guaranteed to have relayed the row), so a
     *   write the server accepted is not reported as unconfirmed because the read
     *   was a few milliseconds early. Acceptance itself is unchanged: exact match
     *   only, never a title the user did not type.
     */
    override fun renameSession(sessionID: String, title: String): RenameResult {
        try {
            api.renameSession(sessionID, title)
        } catch (e: V2Api.ApiException) {
            return RenameConfirmation.fromStatus(e.code)
        } catch (e: Exception) {
            return RenameResult.Unreachable(e.message)
        }
        var attempt = 0
        while (true) {
            val read = try {
                api.session(sessionID).title
            } catch (e: V2Api.ApiException) {
                // A read that 404s is the session being gone — including the
                // window between our accepted PATCH and this read (deleted by
                // another client): the entry is dead either way.
                return RenameConfirmation.fromStatus(e.code)
            } catch (e: Exception) {
                return RenameResult.Unreachable(e.message)
            }
            if (RenameConfirmation.confirmed(title, read)) return RenameResult.Renamed(title)
            attempt += 1
            if (!RenameConfirmation.shouldRetry(attempt)) return RenameResult.Unconfirmed
            if (renameConfirmDelayMs > 0L) Thread.sleep(renameConfirmDelayMs)
        }
    }

    /**
     * v81 P2: `true` means the session is gone afterwards — and a `404` says it
     * already was, which is exactly what this write is for.
     *
     * The measured chain that forced the split: a session whose project directory
     * does not exist on the server host answers `404` on every route once the
     * server has dropped it (`07:07:54` onward for `ses_f0edfb53ffe…14Sr`), so a
     * delete aimed at such a row used to answer `false` — the card stayed open
     * with 「Not deleted — the server did not confirm it」 and the row stayed in
     * the list: a second dead end for the one action the user asked for. A
     * *refusal* (any other non-2xx, or no answer at all) still reports `false`:
     * that delete did not happen, and a live session must not be tombstoned.
     *
     * The session half is why only `404` is folded in: the route is
     * `DELETE /api/session/{id}` with no location scope (`v2/v1` sessions are
     * self-scoped), so the only other 404 shape — the unprefixed SPA catch-all on
     * a 2.x server — is a `200 text/html`, not this.
     */
    override fun deleteSession(sessionID: String): Boolean = try {
        api.deleteSession(sessionID)
        true
    } catch (e: V2Api.ApiException) {
        e.code == 404
    } catch (_: Exception) {
        false
    }

    // v99 runtime trace (keep): the ⋮ `Undo` no-op was invisible from logcat
    // because `stageRevert` swallowed every failure into a null return — the
    // same value a 200-without-`revert` produces, so "request never left" and
    // "server answered no boundary" were indistinguishable. Capture with
    // `adb logcat -s V2Transport:D`.
    override fun stageRevert(sessionID: String, messageID: String): String? = try {
        val staged = api.revertStage(sessionID, messageID).messageID
        android.util.Log.d(
            "V2Transport",
            "revert: POST session/$sessionID/revert/stage {messageID=$messageID} -> messageID=$staged",
        )
        staged
    } catch (e: Exception) {
        android.util.Log.w("V2Transport", "revert: stage FAILED for $messageID: ${e.message}")
        null
    }

    override fun clearRevert(sessionID: String) {
        api.revertClear(sessionID)
    }

    override fun commitRevert(sessionID: String) {
        android.util.Log.d("V2Transport", "revert: POST session/$sessionID/revert/commit")
        api.revertCommit(sessionID)
        android.util.Log.d("V2Transport", "revert: commit 2xx (rows deleted, revert cleared server-side)")
    }

    override fun forkSession(sessionID: String, before: String?): String? = try {
        api.fork(sessionID, before).id
    } catch (_: Exception) {
        null
    }

    // ---------------------------------------------------------------- browse

    // Anchor-relative: every in-anchor browse rides ONE stable location (the
    // filesystem root below) with `path=` relative to it, so browsing N
    // directories boots no location and `GET /api/project` gains no rows.
    // Upstream (`protocol/src/groups/fs.ts`, `fs.list` description): "Entry
    // paths remain relative to the requested location; listing does not
    // switch locations" — and measured 2026-09-25 on 4095 that a stable
    // location plus `path=` (relative, `.`, `..`, even absolute) lists 200
    // while each *new* `location[directory]` value persists one project row
    // (`core/src/location.ts` → `Project.resolve` upsert; the protocol has
    // NO project-delete route, so a booted row is forever). The previous
    // path-driven shape (location = the browsed dir, no `path=`) therefore
    // left one garbage `canonical` record per browsed folder (VM: 7 rows for
    // one browsing session; `/api/project` also holds probe garbage like
    // `/nonexistent-dir-xyz`). Entries arrive prefixed with the requested
    // path (`?path=home/x` → `home/x/child/`), so they join onto the anchor,
    // not onto [absolute].
    //
    // Measured live on 4095 (2026-09-26, re-verified for this contract):
    // `location[directory]=/&path=home/xiufeigo` → 200 (42 dirs) while the
    // relative `path` is resolved *against the location*, so a `path` that is
    // relative to the browsed directory instead of the anchor 500s with an
    // empty body (`location=/home/xiufeigo&path=home/xiufeigo` → 500; the
    // server stats `/home/xiufeigo/home/xiufeigo`). The anchor is therefore
    // the only shape that may carry a non-empty `path=`. The Windows `C:/`
    // anchor half stays UNVERIFIED (no Windows v2 server reachable).
    //
    // Only a *trusted* anchor — one derived from a real `GET /api/location`
    // answer — is cached for the transport's lifetime: the location's own root
    // is a prefix of every path on that volume, so it cannot go stale under a
    // live connection. A *guessed* anchor (`/`, taken while the location read
    // answered nothing — the picker opens in the same frame as `ensureClient`)
    // is re-derived on the next call, so a transient probe-window failure can
    // never pin the browser to the wrong volume root for the whole session.
    private data class BrowseAnchor(val path: String, val trusted: Boolean)

    @Volatile
    private var browseAnchorCache: BrowseAnchor? = null

    private fun browseAnchorState(): BrowseAnchor {
        browseAnchorCache?.takeIf { it.trusted }?.let { return it }
        val home = browseHome?.takeIf { it.isNotBlank() }
        val derived = home?.let { FsBrowse.root(it).ifEmpty { null } }
        val state = BrowseAnchor(derived ?: "/", derived != null)
        synchronized(this) {
            browseAnchorCache?.takeIf { it.trusted }?.let { return it }
            browseAnchorCache = state
            return state
        }
    }

    /**
     * [absolute] as a `path=` value relative to [anchor]: `""` when it IS the
     * anchor, `null` when it escapes it (another drive / UNC share) so the
     * caller falls back to the location-booting shape.
     */
    internal fun anchorRelative(anchor: String, absolute: String): String? {
        val a = FsBrowse.canonical(anchor)
        val t = FsBrowse.canonical(absolute)
        if (a == t) return ""
        if (!FsBrowse.isWithin(a, t)) return null
        val prefix = if (a.endsWith("/")) a else "$a/"
        return t.substring(prefix.length)
    }

    override fun listDirectory(absolute: String): List<DirEntry> {
        val anchor = browseAnchorState()
        val rel = anchorRelative(anchor.path, absolute)
        if (rel != null) {
            try {
                return api.fsList(path = rel.ifEmpty { null }, locationDirectory = anchor.path).map {
                    val resolved = FsBrowse.joinRelative(anchor.path, it.path)
                    DirEntry(
                        name = it.name.ifEmpty { FsBrowse.name(resolved) },
                        absolute = resolved,
                        isDirectory = it.directory,
                    )
                }
            } catch (e: Exception) {
                // A trusted anchor is a prefix of [absolute] by construction, so
                // a failure is the target's own (missing / unreadable — the
                // server answers an empty-bodied 500) and must surface as such:
                // falling through here would boot a project row for a directory
                // that does not exist. A guessed anchor may simply be the wrong
                // volume root, so that one does fall through to the
                // location-booting shape below and still lists.
                if (anchor.trusted) throw e
            }
        }
        // Outside the anchor (another drive / UNC share), or under a guessed
        // anchor that could not list: the legacy per-directory location. Boots
        // one project row server-side (no delete route exists); unreachable
        // from the picker, which starts at the server's home/cwd under the
        // anchor.
        return api.fsList(path = null, locationDirectory = absolute).map {
            val resolved = FsBrowse.absolute(it.path, absolute)
            DirEntry(
                name = it.name.ifEmpty { FsBrowse.name(resolved) },
                absolute = resolved,
                isDirectory = it.directory,
            )
        }
    }

    override fun searchDirectories(query: String, limit: Int): List<DirEntry> {
        val base = browseRoot
        return api.fsFind(query, type = "directory", limit = limit, locationDirectory = base).map {
            DirEntry(
                name = it.name,
                absolute = FsBrowse.joinRelative(base, it.path),
                isDirectory = true,
            )
        }
    }

    override fun searchFiles(query: String, limit: Int, directory: String?): List<String> {
        val base = directory ?: browseRoot
        return api.fsFind(query, type = null, limit = limit, locationDirectory = base)
            .map { FsBrowse.joinRelative(base, it.path) }
    }

    // The root follows the server's own filesystem: derived from a sample
    // path (`FsBrowse.root` of the location) so a Windows v2 host browses
    // `C:/` instead of the POSIX `/` the fs routes reject there; an
    // unreachable location falls back to `/`.
    override val browseRoot: String
        get() = FsBrowse.root(browseHome.orEmpty()).ifEmpty { "/" }

    // v2 has no home field: `/api/location` → `directory` is the project
    // location (`{"directory":"/home/xiufeigo","project":{…}}`, measured), an
    // approximation of `$HOME`.
    override val browseHome: String?
        get() = try {
            api.location().directory.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }

    // Same route as [browseHome]: v2 has one location, no separate home/cwd.
    override val browseCwd: String?
        get() = try {
            api.location().directory.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }

    // Measured: any absolute `location[directory]` lists (`/`, `/home`, …).
    override val browseEscapeAllowed: Boolean = true

    // ------------------------------------------- export / review / home reads

    override fun fetchExportFrame(sessionID: String, directory: String?): SessionExport.Frame {
        // Concurrent callers for the same session join one drain instead of
        // each issuing their own paged read (leader/follower on `FutureTask`;
        // the entry lives only as long as the drain does).
        val key = sessionID + "\u0000" + directory.orEmpty()
        while (true) {
            exportInFlight[key]?.let { return awaitExportFrame(it) }
            val task = FutureTask<SessionExport.Frame> { readExportFrame(sessionID) }
            if (exportInFlight.putIfAbsent(key, task) != null) continue
            try {
                task.run()
            } finally {
                exportInFlight.remove(key, task)
            }
            return awaitExportFrame(task)
        }
    }

    /**
     * The one drain behind [fetchExportFrame].
     *
     * Session routes take no location on v2 — [directory] is v1-only. The drain
     * reports its own page-cap truncation ([V2Api.MessageDrain]), and the frame
     * carries it into the export payload so a short transcript is never
     * presented as a complete one. Nothing here retries: an
     * [V2Api.ApiException.isInvalidCursor] rejection is a client-side cursor
     * bug, so re-issuing the same request only repeats it.
     */
    private fun readExportFrame(sessionID: String): SessionExport.Frame {
        val info = api.sessionRaw(sessionID)
        val drain = api.messagesRawPage(sessionID)
        return SessionExport.Frame(
            info = info,
            messages = drain.messages,
            truncated = drain.truncated,
            truncatedPages = drain.pages,
        )
    }

    /** A follower's view of the leader's drain: the frame, or its own throwable unwrapped. */
    private fun awaitExportFrame(task: FutureTask<SessionExport.Frame>): SessionExport.Frame =
        try {
            task.get()
        } catch (e: ExecutionException) {
            when (val cause = e.cause) {
                null -> throw IOException("export fetch failed", e)
                // An [V2Api.ApiException] is an IOException: rethrow it as-is so
                // a follower keeps the server's `code`/`isInvalidCursor` and not
                // just the sentence.
                is IOException -> throw cause
                is RuntimeException -> throw cause
                is Error -> throw cause
                else -> throw IOException(cause.message ?: "export fetch failed", cause)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("export fetch interrupted", e)
        }

    override fun listProjects(): List<OpenCodeClient.Project> =
        api.projects().mapNotNull(::v2ProjectRow)

    override fun sessionDiff(sessionID: String, directory: String?): List<OpenCodeClient.FileDiff> =
        api.sessionDiff(sessionID)

    override fun vcsInfo(directory: String?): OpenCodeClient.VcsInfo =
        v2VcsInfoRow(api.vcs(locationDirectory = directory))

    override fun vcsDiff(mode: String, directory: String?): List<OpenCodeClient.FileDiff> =
        api.vcsDiff(v2VcsDiffMode(mode), locationDirectory = directory)

    override fun fileContent(path: String, directory: String?): OpenCodeClient.FileContent {
        val location = directory?.takeIf { it.isNotBlank() } ?: browseCwd
        val relative = FsBrowse.fsReadRelative(location, path)
            ?: throw IOException("outside the v2 location, no fs/read target: $path")
        return fileContentFromBytes(api.fsRead(relative, locationDirectory = location))
    }

    // ---------------------------------------------------------------- streams

    override fun streamRequests(sessionID: String?, afterSeq: Long?): List<Request> = buildList {
        add(api.globalEventRequest())
        if (sessionID != null) add(api.sessionLogRequest(sessionID, afterSeq, follow = true))
    }

    override fun parseFrame(type: String, data: JSONObject): List<ChatStore.Event> =
        V2EventAdapter.adapt(type, data)

    override fun frameSessionID(data: JSONObject): String? = V2EventAdapter.sessionID(data)

    // ---------------------------------------------------------------- mapping

    /**
     * One durable `GET /api/session/{id}/message` row → the store row the UI
     * renders.
     *
     * `internal` so the mapping is pinned by unit test without a server
     * (`CompactionMessageRowTest`): it is pure JSON → [ChatStore] work.
     */
    internal fun V2Api.Message.toUi(): ChatStore.UiMessage? {
        if (id.isEmpty()) return null
        return when (type) {
            // The turn marker ([V2Api.Message.TYPE_IDLE]) is not a message: it
            // carries neither parts nor a body, and projecting it through the
            // notice branch below produced an assistant-role row with an empty
            // part — an invisible row that said nothing. Its one fact (the turn
            // it closes was a Stop) is folded by the snapshot walk
            // ([snapshotMessages] → [ChatStore.applyTurnMarker]).
            V2Api.Message.TYPE_IDLE -> null
            V2Api.Message.TYPE_USER -> ChatStore.UiMessage(
                id = id,
                role = "user",
                timeCreated = timeCreated,
                parts = listOf(ChatStore.UiPart(id = "$id:text", type = "text", text = text.orEmpty())),
                tokens = null,
                agent = null,
                model = null,
                timeCompleted = timeCompleted,
                interrupted = false,
            )
            V2Api.Message.TYPE_ASSISTANT -> ChatStore.UiMessage(
                id = id,
                role = "assistant",
                timeCreated = timeCreated,
                parts = stableParts(id, content),
                tokens = OpenCodeClient.tokenTotal(
                    JSONObject()
                        .put("input", tokensInput)
                        .put("output", tokensOutput),
                ),
                agent = agent,
                model = model?.let { "${it.providerID}/${it.id}" },
                timeCompleted = timeCompleted,
                interrupted = ChatStore.isInterrupted(raw.optJSONObject("error")),
                // The failure the server recorded on this turn (`finish:"error"`
                // plus `error:{type,message,status?}`, e.g. the measured
                // `provider.auth` 403 body). `ChatStore` rebuilds the failure
                // card from it, so closing and reopening a session keeps the
                // card on its own turn instead of dropping it — upstream
                // projects the same field (`projection.ts:357-359`). An
                // interrupted turn carries `aborted` and stays a separator.
                error = ChatStore.messageErrorOf(raw.opt("error")),
            )
            // A `type=compaction` row carries its outcome on the **row**
            // (`status: running|completed|failed` + `error` on failure —
            // `_upstream/packages/schema/src/session-message.ts:238-281`), not in
            // `summary`, which is empty on a rejected compaction. Projecting the
            // row through `ChatStore.compactionRow` (the one builder for the
            // durable row) carries that outcome into the part the timeline reads
            // — `toolStatus` in the store's own vocabulary, the readable failure
            // in `text`, the untouched row in `rawJson` — so the snapshot and
            // live `session.compaction.*` paths merge onto one row and agree.
            V2Api.Message.TYPE_COMPACTION -> ChatStore.compactionRow(raw)
            // Notice rows (shell/synthetic/system/skill/agent-switched/
            // model-switched/location-switched/idle): one text part carrying the
            // human line; types the timeline does not know stay renderable as
            // plain rows instead of vanishing.
            //
            // A durable `model-switched` with no `previous` is the session's
            // first model, not a switch — the same row the live frame path drops
            // (`isInitialModelSelection`). The server persists one for every
            // `ModelSelected`, so without this a reopened new session grew the
            // 「Model changed」 row back from its own history.
            "model-switched" -> if (isInitialModelSelection(raw.opt("previous"))) {
                null
            } else {
                ChatStore.UiMessage(
                    id = id,
                    role = "assistant",
                    timeCreated = timeCreated,
                    parts = listOf(noticePart()),
                    tokens = null,
                    agent = agent,
                    model = model?.let { "${it.providerID}/${it.id}" },
                    timeCompleted = timeCompleted,
                    interrupted = false,
                )
            }
            else -> ChatStore.UiMessage(
                id = id,
                role = "assistant",
                timeCreated = timeCreated,
                parts = listOf(noticePart()),
                tokens = null,
                agent = agent,
                model = model?.let { "${it.providerID}/${it.id}" },
                timeCompleted = timeCompleted,
                interrupted = false,
            )
        }
    }

    /**
     * The snapshot copy of a notice row's single part, in the shape the live
     * frames build ([V2EventAdapter.notice] / [V2EventAdapter.shellNotice]):
     * the same `"$id:notice"` key **and** the same `toolName` / `toolStatus`
     * for the two types the timeline prints a name on. `PartRenderer` renders
     * `part.toolName` as the row's value for `shell` and `skill`
     * (`ui/session/timeline/PartRenderer.kt:200-210`), and after a reopen the
     * snapshot is the only source for rows the live stream no longer replays —
     * so leaving `toolName` null here blanked the command / skill name.
     */
    private fun V2Api.Message.noticePart(): ChatStore.UiPart = ChatStore.UiPart(
        id = "$id:notice",
        type = type,
        text = noticeText(),
        // Live `session.shell.started` reads `shell.command` / `shell.status`
        // off the nested shell object; the durable row carries the same two
        // keys at the top level (`Session.Message.Shell`, `session-message.ts:111-123`).
        // `session.skill.activated` names the skill on `name` and carries no
        // status (`V2EventAdapter.kt:326-337`).
        toolName = when (type) {
            V2Api.Message.TYPE_SHELL -> command?.takeIf { it.isNotEmpty() }
            "skill" -> optStringOrNull(raw, "name")
            else -> null
        },
        toolStatus = if (type == V2Api.Message.TYPE_SHELL) optStringOrNull(raw, "status") else null,
    )

    /**
     * The visible line of a notice row, resolved **here** for the two types whose
     * durable row carries two strings.
     *
     * `system` / `synthetic` rows hold the model-facing `text` **and** an optional
     * human `description` (`_upstream/packages/schema/src/session-message.ts:71-85`).
     * The live part keeps the payload as `rawJson` and the timeline renders
     * `RawParts.description(rawJson) ?? text` (`PartRenderer.kt:196-199`), so live
     * rows show the description; a snapshot part carries no `rawJson`
     * ([ChatStore.UiPart.RAW_JSON_TYPES]) and used to fall back to the
     * model-facing text — the same row rendered differently after a reopen.
     * Resolving the description into [text] at projection time gives the snapshot
     * the exact string the live renderer displays.
     */
    private fun V2Api.Message.noticeText(): String = when (type) {
        V2Api.Message.TYPE_SHELL -> listOfNotNull(command, shellOutputText()).joinToString("\n")
        "system", "synthetic" -> RawParts.description(raw.toString()) ?: text.orEmpty()
        // `location-switched` rows carry no `text` (the directory lives on the
        // nested `location`, `session-message.ts:62-67`) and no `rawJson`
        // survives projection — so every snapshot-sourced row rendered a bare
        // 「Location changed」 with no project name (v46 tD). Resolve the
        // display name into `text` here, the same projection-time fix as
        // `system` / `synthetic` above; `PartRenderer` prefers `rawJson` and
        // falls back to this. Blank-tolerant: a nameless value (no directory
        // on the payload at all) falls through to the row's own `text`
        // instead of pinning an empty string, so live and snapshot agree.
        "location-switched" -> RawParts.switch(raw.toString()).value?.takeIf { it.isNotBlank() }
            ?: text.orEmpty()
        else -> text.orEmpty()
    }

    /**
     * The durable `shell` row stores the captured page as `Shell.Output`
     * (`{output,cursor,size,truncated}`,
     * `_upstream/packages/schema/src/shell.ts:73-81`) — the same struct the
     * live `shell.started`/`ended` frames nest under `output`. [V2Api.Message.output]
     * is read with `optString`, which stringifies an object, so the page is
     * taken out of `output.output` (matching [V2EventAdapter.shellText]) with a
     * plain-string fallback for a payload that carries the page directly.
     */
    private fun V2Api.Message.shellOutputText(): String? =
        (raw.opt("output") as? JSONObject)
            ?.let { optStringOrNull(it, "output") }
            ?: output?.takeIf { it.isNotEmpty() && it != "null" }

    /**
     * Snapshot part ids must agree with the live-stream ids the
     * [V2EventAdapter] synthesizes, or `ChatStore.mergeParts` keeps BOTH
     * copies of every part: the server's `content[]` entries carry no `id`
     * for text/reasoning (measured 2026-09-22 on 2.0.11), so a content-hash
     * fallback never matches the stream's `msg_…:text:0` keys — and worse,
     * it changes whenever the part's raw JSON gains a field, stacking a new
     * duplicate on every snapshot. The adapter keys text/reasoning parts by
     * per-kind ordinal and tool parts by their `call_` id (which the server
     * DOES carry), so the same scheme is reproduced here positionally.
     */
    private fun stableParts(messageID: String, content: List<V2Api.Part>): List<ChatStore.UiPart> {
        val ordinals = HashMap<String, Int>()
        return content.map { part ->
            val stableId = when {
                part.type == "text" || part.type == "reasoning" -> {
                    val ordinal = ordinals.getOrDefault(part.type, 0)
                    ordinals[part.type] = ordinal + 1
                    "$messageID:${part.type}:$ordinal"
                }
                part.id.isNotEmpty() -> part.id
                else -> {
                    val ordinal = ordinals.getOrDefault(part.type, 0)
                    ordinals[part.type] = ordinal + 1
                    "$messageID:${part.type}:$ordinal"
                }
            }
            part.toUiPart(stableId)
        }
    }

    private fun V2Api.Part.toUiPart(stableId: String): ChatStore.UiPart = when (type) {
        "reasoning" -> ChatStore.UiPart(id = stableId, type = type, text = text)
        "tool" -> toolPart(stableId)
        else -> ChatStore.UiPart(
            id = stableId,
            type = type.ifEmpty { "text" },
            text = text,
            rawJson = raw.takeIf { type in ChatStore.UiPart.RAW_JSON_TYPES }?.toString(),
        )
    }

    private fun V2Api.Part.toolPart(stableId: String): ChatStore.UiPart {
        val state = raw.optJSONObject("state")
        val status = state?.optString("status", "")?.takeIf { it.isNotEmpty() }
        val input = state?.opt("input")?.let {
            when (it) {
                is JSONObject -> it.toString()
                is String -> it
                else -> it.toString()
            }
        }
        val content = state?.optJSONArray("content")
        val output = content?.let { V2EventAdapter.toolContentText(it) }
            ?: state?.opt("output")?.let {
                when (it) {
                    is String -> it
                    JSONObject.NULL -> null
                    else -> it.toString()
                }
            }
        val error = state?.optJSONObject("error")?.optString("message", "")?.takeIf { it.isNotEmpty() }
            ?: state?.optString("error", "")?.takeIf { it.isNotEmpty() }
        return ChatStore.UiPart(
            id = stableId,
            type = "tool",
            text = "",
            toolName = raw.optString("name", "").takeIf { it.isNotEmpty() },
            toolStatus = status,
            toolInput = input,
            toolOutput = output,
            toolError = error,
        )
    }

    /**
     * `internal` so the pure projection is pinned by unit test without a server
     * (`SessionRevertRestoreTest`), like [toUi].
     */
    internal fun V2Api.SessionInfo.toRow(): SessionRow = SessionRow(
        id = id,
        title = title,
        directory = directory,
        cost = cost,
        // `Session.Info.revert.messageID` is the authoritative boundary
        // (`_upstream/packages/schema/src/session-revert.ts:8-14`); an absent
        // `revert` means nothing is staged. Read off `raw` because the promoted
        // [V2Api.SessionInfo] fields stop at `subpath`, and guarded against a
        // JSON `null` (optString would answer the literal `"null"`).
        revertMessageID = raw.optJSONObject("revert")?.let { optStringOrNull(it, "messageID") },
        agent = agent,
        providerID = model?.providerID,
        modelID = model?.id,
        variant = model?.variant?.takeIf { it.isNotBlank() && it != "default" },
        hasTitle = !optStringOrNull(raw, "title").isNullOrBlank(),
    )

    private fun V2Api.PermissionRequest.toV1(): OpenCodeClient.PermissionRequest =
        OpenCodeClient.PermissionRequest(
            id = id,
            sessionID = sessionID,
            permission = action,
            patterns = resources,
            always = save,
            tool = if (messageID != null && callID != null) {
                OpenCodeClient.PermissionRequest.Tool(messageID, callID)
            } else {
                null
            },
            metadata = raw.optJSONObject("metadata"),
        )
}

// ------------------------------------------------------------- pure mappings
//
// Top-level (public) so the JVM tests can pin them without a socket, next to
// the transport that uses them.

/**
 * v2 `GET /api/project` entry → [OpenCodeClient.Project] for Home's projects
 * column. Shape gap measured 2026-09-22 on 2.0.11: v2 answers
 * `{id,canonical,time,sandboxes,vcs?}` — **no `worktree`** (v1's path key), so
 * [V2Api.V2Project.canonical] (the project root) maps into the `worktree` slot.
 *
 * `icon` is optional and was absent from that fixture only because no project
 * had one set: `Project.Icon` `{url,override,color}` is on the wire
 * (`schema/src/project.ts:20-25`) and `core/src/project.ts:70-75` builds it
 * whenever the row has an icon url, override or color. Both halves the shell
 * draws are mapped — `color` (the avatar tint) and `override` (the custom
 * image, which the avatar prefers). Entries with a blank canonical are dropped,
 * mirroring v1's no-worktree rule.
 */
fun v2ProjectRow(project: V2Api.V2Project): OpenCodeClient.Project? {
    val worktree = project.canonical.trim()
    if (worktree.isEmpty()) return null
    val icon = project.raw.optJSONObject("icon")
    return OpenCodeClient.Project(
        id = project.id,
        worktree = worktree,
        vcs = project.raw.optString("vcs", "").takeIf { it.isNotEmpty() && it != "null" },
        iconColor = optStringOrNull(icon, "color"),
        sandboxes = project.sandboxes,
        // `name` is optional; guard JSON-null so optString's literal "null"
        // never reaches the Home display chain.
        name = if (project.raw.isNull("name")) "" else project.raw.optString("name", "").trim().takeIf { it != "null" } ?: "",
        iconOverride = optStringOrNull(icon, "override"),
    )
}

/**
 * v2 `GET /api/vcs` → [OpenCodeClient.VcsInfo]. Shape gap: v1 is the flat
 * `{branch,default_branch}`, v2 is `{branch:{current,default}}` inside the
 * `{location,data}` envelope ([V2Api.vcs] already unwraps). Measured
 * `{"branch":{}}` with no repo — both halves null, same as v1's JSON nulls.
 */
fun v2VcsInfoRow(vcs: V2Api.VcsInfo): OpenCodeClient.VcsInfo =
    OpenCodeClient.VcsInfo(branch = vcs.current, defaultBranch = vcs.default)

/**
 * Review scope mode → the generation's `vcs/diff` `mode` enum. The UI sends
 * upstream's v1 names (`git`/`branch`); measured 2026-09-22: v1 accepts
 * `git|branch` and 400s `working`, v2 accepts `working|branch|committed` and
 * **400s `git`** — `git` and `working` mean the same scope (uncommitted
 * working-tree changes), so only that one name is translated. `branch` and
 * anything else pass through untouched.
 */
fun v2VcsDiffMode(mode: String): String = if (mode == "git") "working" else mode

/**
 * `GET /api/fs/read/<rel>` bytes → the v1 `GET /file/content?path=` JSON
 * shape ([OpenCodeClient.FileContent]) the preview already renders.
 *
 * v1 decides `type` server-side: measured text answers `{type,content}`
 * (UTF-8 string) and binary answers
 * `{type:"binary",content:<base64>,encoding:"base64",mimeType:<ext-derived>}`.
 * v2 ships the bytes alone, so the client approximates the same split: any
 * NUL byte ⇒ binary (content base64, `encoding="base64"` like v1), otherwise
 * UTF-8 text (malformed sequences decode to U+FFFD, as any UTF-8 client
 * would). `mimeType` stays null — v1 derives it from the extension
 * server-side and the preview's binary branch never reads it.
 */
fun fileContentFromBytes(bytes: ByteArray): OpenCodeClient.FileContent {
    val binary = bytes.any { it == 0.toByte() }
    return OpenCodeClient.FileContent(
        binary = binary,
        content = if (binary) {
            Base64.getEncoder().encodeToString(bytes)
        } else {
            bytes.toString(Charsets.UTF_8)
        },
        diff = null,
        patch = null,
        encoding = if (binary) "base64" else null,
        mimeType = null,
    )
}
