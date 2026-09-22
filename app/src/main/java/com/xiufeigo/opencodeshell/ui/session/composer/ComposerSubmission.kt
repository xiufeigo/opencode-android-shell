package com.xiufeigo.opencodeshell.ui.session.composer

import com.xiufeigo.opencodeshell.ChatViewModel

/**
 * WAVE 2.4b (P0-8 + P0-7): the composer's submit payload as a pure model.
 *
 * Upstream a submission is `ComposerSubmission` (`composer/submit.ts:17-26`):
 * `{id, mode, prompt, context, text, images, selection, delivery}` — the
 * typed prompt parts (`submit.ts:351-402` `sendPrompt`/`buildSubmissionRequest`
 * → `request.ts:buildPromptRequest`) decide the structured `files/agents/skills`
 * fields, and `delivery` (`adapter.ts:42` `ComposerDelivery = "steer"|"queue"`)
 * decides whether the prompt steers the running turn or queues behind it.
 *
 * The native composer owns the text, the attachments, the shell/command
 * routing ([ComposerSubmit.Payload]) and — new in this wave — the structured
 * mention tokens ([MentionTokens]) plus the delivery choice
 * ([ComposerDelivery]). This value is what the composer hands to the screen,
 * which forwards it to `ChatViewModel`. The transport call itself stays VM-side
 * (a parallel agent owns `ChatViewModel`); the exact VM mapping is written in
 * `research/v2_audit/wave2.4b-notes.md` §VM so the two halves cannot drift.
 *
 * Pure Kotlin on purpose: every constructor below is JVM-testable without a
 * device, Compose, or the network.
 */
object ComposerSubmission {

    /**
     * Follow-up delivery (`composer/adapter.ts:42` + `queue.ts:217,218-222`):
     * - [STEER] — steer the running turn (upstream `"steer"`; interrupts nothing,
     *   reconfigures the session first — `submit.ts:363` applies the agent/model
     *   selection only for steer).
     * - [QUEUE] — enqueue behind the running turn (upstream `"queue"`; becomes
     *   an inbox item — `session.inbox.enqueued` — and must NOT reconfigure the
     *   running turn). v1 has no inbox API, so the composer never offers QUEUE
     *   on v1 (see [ComposerQueue]).
     */
    enum class Delivery(val wire: String) {
        STEER("steer"),
        QUEUE("queue"),
        ;

        companion object {
            /** Parses the stored `followUpBehavior` / wire value; unknown → [STEER]. */
            fun of(value: String?): Delivery =
                entries.firstOrNull { it.wire == value?.trim()?.lowercase() } ?: STEER
        }
    }

    /**
     * One structured file entry for the prompt payload: a `@file` mention that
     * resolved to a workspace path ([MentionTokens.FileRef]) or an attached
     * image/file ([ChatViewModel.Attachment] bytes as a `data:` URL).
     */
    data class FileEntry(
        /** `file://…` for mentions, `data:<mime>;base64,…` for attachments. */
        val uri: String,
        val mime: String? = null,
        val name: String? = null,
    )

    /**
     * What the composer hands over on submit: the display text plus the
     * structured fields the transports need.
     *
     * - [text] — the trimmed editor text (`displayText`; the model-visible
     *   text gains the `Attached file: \`path\`` lines upstream server-side via
     *   `formatAttachmentReference` — the VM keeps building it as today).
     * - [files] — mention `file://` refs first (token order), then attachment
     *   `data:` URLs (chip order), mirroring `request.ts:133,193`
     *   (`[...files, ...context, ...inline]`).
     * - [agents]/[skills] — mention names in token order (`request.ts`
     *   `agents`/`skills`).
     * - [delivery] — the queue/steer choice (v2 only; the VM ignores it on v1).
     * - [shell]/[command]/[arguments] — the existing [ComposerSubmit.Payload]
     *   routing triple, carried through unchanged.
     */
    data class Model(
        val text: String,
        val files: List<FileEntry> = emptyList(),
        val agents: List<String> = emptyList(),
        val skills: List<ComposerSkill> = emptyList(),
        val delivery: Delivery = Delivery.STEER,
        val shell: Boolean = false,
        val command: String? = null,
        val arguments: String = "",
    )

    /** One `@skill` mention: `{id, name}` (`SkillPart{id,name}`, `request.ts:68-72`). */
    data class ComposerSkill(val id: String, val name: String)

    /**
     * Builds the [Model] for one submit from the composer's live state.
     *
     * @param attachments the SAF/paste chips ([ChatViewModel.Attachment]).
     * @param tokens the accepted `@` mentions ([MentionTokens]).
     * @param sessionDirectory resolves relative mention paths to absolute
     * `file://` URIs (`request.ts:42-47` `absolute()`); null leaves them
     * relative (the server resolves against the session location).
     */
    fun build(
        text: String,
        attachments: List<ChatViewModel.Attachment> = emptyList(),
        tokens: List<MentionTokens.Token> = emptyList(),
        delivery: Delivery = Delivery.STEER,
        shell: Boolean = false,
        command: String? = null,
        arguments: String = "",
        sessionDirectory: String? = null,
    ): Model {
        val structured = MentionTokens.structure(tokens, sessionDirectory)
        val files = buildList {
            for (file in structured.files) {
                add(FileEntry(uri = file.uri, mime = file.mime, name = file.name))
            }
            for (attachment in attachments) {
                add(
                    FileEntry(
                        uri = attachment.dataUrl,
                        mime = attachment.mime,
                        name = attachment.filename,
                    ),
                )
            }
        }
        return Model(
            text = text,
            files = files,
            agents = structured.agents.toList(),
            skills = structured.skills.map { ComposerSkill(it.id, it.name) },
            delivery = delivery,
            shell = shell,
            command = command,
            arguments = arguments,
        )
    }

    /**
     * Upgrades the existing [ComposerSubmit.Payload] (text + attachments +
     * routing) with the wave's structured fields. The composer calls this at
     * the single submit site so the M1.5/`onSend` fallback keeps working:
     * without tokens and on idle, the model degrades to today's payload.
     */
    fun ComposerSubmit.Payload.structured(
        tokens: List<MentionTokens.Token> = emptyList(),
        delivery: Delivery = Delivery.STEER,
        sessionDirectory: String? = null,
    ): Model = build(
        text = this.text,
        attachments = this.attachments,
        tokens = tokens,
        delivery = delivery,
        shell = this.shell,
        command = this.command,
        arguments = this.arguments,
        sessionDirectory = sessionDirectory,
    )
}
