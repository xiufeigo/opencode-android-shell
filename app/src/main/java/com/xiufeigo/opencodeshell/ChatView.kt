package com.xiufeigo.opencodeshell

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.xiufeigo.opencodeshell.databinding.ViewChatBinding
import io.noties.markwon.Markwon
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Native chat screen (M1): message list + composer with SAF attachments and
 * @-mention. Streaming part updates are coalesced (80ms) before rebinding so
 * fast token streams do not re-render every event.
 */
class ChatView(
    context: Context,
    private val viewModel: ChatViewModel,
) : ConstraintLayout(context) {

    private val binding = ViewChatBinding.inflate(LayoutInflater.from(context), this, true)
    private val adapter = MessagesAdapter()
    private val markwon: Markwon = Markwon.create(context)
    private val pendingAttachments = AtomicReference<List<ChatViewModel.Attachment>>(emptyList())
    private var renderJob: Job? = null
    private var collectJob: Job? = null
    private var mentionSearchJob: Job? = null
    private var mentionPopup: PopupWindow? = null
    private var started = false
    private var latestState = ChatStore.ChatState()

    private val attachLauncher = (context as? androidx.activity.result.ActivityResultRegistryOwner)?.let { owner ->
        (context as? androidx.lifecycle.LifecycleOwner)?.let { lifecycleOwner ->
            owner.activityResultRegistry.register(
                "chat-attach",
                lifecycleOwner,
                ActivityResultContracts.OpenMultipleDocuments(),
            ) { uris -> if (uris != null) ingestAttachments(uris) }
        }
    }

    init {
        binding.messageList.layoutManager = LinearLayoutManager(context).apply {
            stackFromEnd = true
        }
        binding.messageList.adapter = adapter
        binding.btnAttach.setOnClickListener {
            attachLauncher?.launch(arrayOf("*/*"))
                ?: Toast.makeText(context, R.string.chat_need_config, Toast.LENGTH_SHORT).show()
        }
        binding.btnSend.setOnClickListener { onSendClicked() }
        binding.composerInput.addTextChangedListener(SimpleTextWatcher { onInputChanged() })
        binding.chatError.setOnClickListener {
            viewModel.clearError()
            binding.chatError.visibility = View.GONE
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    fun start() {
        // One collector for the view's lifetime: start()/stop() pairs (every
        // backgrounding) must not stack duplicate collectors on the lifecycle
        // scope, or submitList runs N times per state change.
        if (collectJob?.isActive != true) {
            collectJob = findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                viewModel.state.collect { onState(it) }
            }
        }
        started = true
        viewModel.start()
    }

    fun stop() {
        if (!started) return
        started = false
        collectJob?.cancel()
        collectJob = null
        viewModel.stop()
    }

    /** Back press: true when a mention popup was showing and got collapsed. */
    fun dismissMentionIfShowing(): Boolean {
        val wasShowing = mentionPopup?.isShowing == true
        dismissMention()
        return wasShowing
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun onState(state: ChatStore.ChatState) {
        latestState = state
        if (renderJob == null) {
            renderJob = findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                // Coalesce the token-stream burst into one list rebind.
                delay(RENDER_COALESCE_MS)
                renderJob = null
                renderNow(latestState)
            }
        }
    }

    private fun renderNow(state: ChatStore.ChatState) {
        adapter.submitList(state.messages) {
            binding.messageList.scrollToPosition(adapter.itemCount - 1)
        }
        when (state.runStatus) {
            ChatStore.RunStatus.RUNNING -> {
                binding.btnSend.setImageResource(android.R.drawable.ic_media_pause)
                binding.btnSend.contentDescription = context.getString(R.string.chat_abort)
                binding.btnSend.setOnClickListener { viewModel.abort() }
                binding.chatError.visibility = View.GONE
            }

            else -> {
                binding.btnSend.setImageResource(android.R.drawable.ic_menu_send)
                binding.btnSend.contentDescription = context.getString(R.string.chat_send)
                binding.btnSend.setOnClickListener { onSendClicked() }
                if (state.error != null) {
                    binding.chatError.text = state.error
                    binding.chatError.visibility = View.VISIBLE
                } else {
                    binding.chatError.visibility = View.GONE
                }
            }
        }
    }

    private fun onSendClicked() {
        val text = binding.composerInput.text?.toString().orEmpty().trim()
        val attachments = pendingAttachments.getAndSet(emptyList())
        if (text.isEmpty() && attachments.isEmpty()) return
        binding.composerInput.setText("")
        updateAttachmentStrip()
        if (viewModel.sessionID == null) {
            viewModel.createSession { viewModel.send(text, attachments) }
        } else {
            viewModel.send(text, attachments)
        }
    }

    private fun onInputChanged() {
        val input = binding.composerInput.text?.toString().orEmpty()
        val caret = binding.composerInput.selectionStart
        if (caret <= 0 || caret > input.length) {
            dismissMention()
            return
        }
        val at = input.lastIndexOf('@', caret - 1)
        if (at < 0 || input.substring(at + 1, caret).contains(' ')) {
            dismissMention()
            return
        }
        val query = input.substring(at + 1, caret)
        // Debounce /find/file (one request per 200ms pause) and drop results
        // whose @ anchor is stale by the time they arrive.
        mentionSearchJob?.cancel()
        mentionSearchJob = findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            delay(MENTION_DEBOUNCE_MS)
            viewModel.searchFiles(query) { results ->
                if (at == binding.composerInput.selectionStart - 1 - query.length ||
                    binding.composerInput.text?.toString()?.getOrNull(at) == '@'
                ) {
                    showMention(results, at)
                }
            }
        }
    }

    private fun showMention(paths: List<String>, at: Int) {
        if (paths.isEmpty()) {
            dismissMention()
            return
        }
        val popup = mentionPopup ?: PopupWindow(context).apply {
            isOutsideTouchable = true
            isFocusable = false
            mentionPopup = this
        }
        val list = ListView(context)
        list.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_list_item_1,
            paths.take(MENTION_LIMIT),
        )
        list.setOnItemClickListener { _, _, position, _ ->
            val path = paths[position]
            val editable = binding.composerInput.text ?: return@setOnItemClickListener
            val caret = binding.composerInput.selectionStart.coerceIn(0, editable.length)
            // Guard stale results: the @ anchor must still precede the caret
            // and the span between them must not have grown a space.
            if (caret < at || editable.substring(at, caret).contains(' ')) return@setOnItemClickListener
            editable.replace(at, caret, "@$path ")
            dismissMention()
        }
        popup.contentView = list
        popup.width = ViewGroup.LayoutParams.MATCH_PARENT
        popup.height = (resources.displayMetrics.heightPixels * 0.35f).toInt()
        // showAtLocation uses window coordinates, so anchor from the composer
        // bar's actual position: y = window height - composer top. This is
        // exact with/without the IME (the IME padding shrinks the content
        // view, moving the composer up) and never hides behind the keyboard
        // the way a fixed Gravity.BOTTOM offset would (rootView is the
        // DecorView, whose padding is always zero under edge-to-edge).
        val composerLoc = IntArray(2)
        binding.composerBar.getLocationInWindow(composerLoc)
        popup.showAtLocation(this, android.view.Gravity.BOTTOM, 0, rootView.height - composerLoc[1])
    }

    private fun dismissMention() {
        mentionPopup?.dismiss()
        mentionPopup = null
    }

    // ------------------------------------------------------------ attachments

    private fun ingestAttachments(uris: List<Uri>) {
        val appContext = context.applicationContext
        Thread({
            val ingested = mutableListOf<ChatViewModel.Attachment>()
            for (uri in uris) {
                readAttachment(appContext, uri)?.let { ingested.add(it) }
            }
            // CAS loop: concurrent chip removals must not clobber the ingest.
            while (true) {
                val current = pendingAttachments.get()
                val merged = (current + ingested).distinctBy {
                    it.filename to it.dataUrl
                }
                if (merged.size > MAX_ATTACHMENTS) {
                    val capped = merged.take(MAX_ATTACHMENTS)
                    if (pendingAttachments.compareAndSet(current, capped)) {
                        post {
                            Toast.makeText(
                                context,
                                context.getString(
                                    R.string.chat_attach_limit,
                                    MAX_ATTACHMENTS,
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        break
                    }
                    continue
                }
                if (pendingAttachments.compareAndSet(current, merged)) break
            }
            post { updateAttachmentStrip() }
        }, "oc-attach").apply { isDaemon = true }.start()
    }

    private fun readAttachment(appContext: Context, uri: Uri): ChatViewModel.Attachment? = try {
        val resolver = appContext.contentResolver
        val name = queryDisplayName(appContext, uri) ?: "attachment-${System.currentTimeMillis()}"
        val mime = resolveMime(appContext, uri, name) ?: "application/octet-stream"
        val bytes = downscaleOrRaw(appContext, uri, mime)
        if (bytes.size > MAX_ATTACHMENT_BYTES) {
            post {
                Toast.makeText(
                    context,
                    context.getString(R.string.chat_attach_too_large, name),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            null
        } else {
            ChatViewModel.Attachment(
                mime,
                name,
                "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP),
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "attachment read failed: ${e.message}")
        null
    }

    /** Images are downscaled (long edge ≤2048, JPEG 85) before base64. */
    private fun downscaleOrRaw(appContext: Context, uri: Uri, mime: String): ByteArray {
        if (!mime.startsWith("image/")) {
            return appContext.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        }
        // ImageDecoder needs API 28; on 26/27 fall back to raw bytes (the
        // NoSuchMethodError would not be caught by catch(Exception)).
        if (android.os.Build.VERSION.SDK_INT < 28) {
            return appContext.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        }
        return try {
            val source = ImageDecoder.createSource(appContext.contentResolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val maxSide = maxOf(info.size.width, info.size.height)
                if (maxSide > MAX_IMAGE_EDGE) {
                    val scale = MAX_IMAGE_EDGE.toFloat() / maxSide
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1),
                    )
                }
            }
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            bitmap.recycle()
            out.toByteArray()
        } catch (_: Exception) {
            appContext.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        }
    }

    /** contentResolver mime, with an extension-name fallback for SAF blanks. */
    private fun resolveMime(appContext: Context, uri: Uri, name: String?): String? {
        val direct = appContext.contentResolver.getType(uri)
        if (!direct.isNullOrEmpty() && direct != "application/octet-stream") return direct
        val extension = name?.substringAfterLast('.', "")?.lowercase() ?: return null
        if (extension.isEmpty()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }

    private fun queryDisplayName(appContext: Context, uri: Uri): String? = try {
        appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun updateAttachmentStrip() {
        val attachments = pendingAttachments.get()
        binding.attachmentStrip.removeAllViews()
        if (attachments.isEmpty()) {
            binding.attachmentStrip.visibility = View.GONE
            return
        }
        binding.attachmentStrip.visibility = View.VISIBLE
        for (attachment in attachments) {
            val chip = TextView(context).apply {
                text = "${attachment.filename} ×"
                setTextAppearance(android.R.style.TextAppearance_Material_Small)
                setPadding(12, 6, 12, 6)
                setOnClickListener {
                    while (true) {
                        val current = pendingAttachments.get()
                        val next = current - attachment
                        if (pendingAttachments.compareAndSet(current, next)) break
                    }
                    updateAttachmentStrip()
                }
            }
            binding.attachmentStrip.addView(chip)
        }
    }

    // ---------------------------------------------------------------- adapter

    private class MessageDiff : DiffUtil.ItemCallback<ChatStore.UiMessage>() {
        override fun areItemsTheSame(old: ChatStore.UiMessage, new: ChatStore.UiMessage) =
            old.id == new.id

        override fun areContentsTheSame(old: ChatStore.UiMessage, new: ChatStore.UiMessage) =
            old == new
    }

    private inner class MessagesAdapter :
        ListAdapter<ChatStore.UiMessage, MessagesAdapter.Holder>(MessageDiff()) {

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val role: TextView = view.findViewById(R.id.message_role)
            val body: TextView = view.findViewById(R.id.message_body)
            val files: TextView = view.findViewById(R.id.message_files)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false),
        )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val message = getItem(position)
            holder.role.text = if (message.role == "user") {
                context.getString(R.string.chat_role_user)
            } else {
                context.getString(R.string.chat_role_assistant)
            }
            val text = message.parts
                .filter { it.type == "text" }
                .joinToString("\n\n") { it.text }
            if (text.isEmpty()) {
                holder.body.text = message.parts
                    .filter { it.type == "tool" }
                    .joinToString("\n") { part ->
                        "${part.toolName ?: "tool"} · ${part.toolStatus ?: "…"}"
                    }
            } else {
                markwon.setMarkdown(holder.body, text)
            }
            val fileBits = message.parts
                .filter { it.type == "file" }
                .joinToString("\n") { "📎 ${it.filename ?: it.url ?: "file"}" }
            if (fileBits.isEmpty()) {
                holder.files.visibility = View.GONE
            } else {
                holder.files.text = fileBits
                holder.files.visibility = View.VISIBLE
            }
        }
    }

    companion object {
        private const val TAG = "ChatView"
        private const val MENTION_LIMIT = 20
        private const val MENTION_DEBOUNCE_MS = 200L
        private const val MAX_ATTACHMENTS = 6
        private const val MAX_ATTACHMENT_BYTES = 8 * 1024 * 1024
        private const val MAX_IMAGE_EDGE = 2048
        private const val RENDER_COALESCE_MS = 80L
    }
}
