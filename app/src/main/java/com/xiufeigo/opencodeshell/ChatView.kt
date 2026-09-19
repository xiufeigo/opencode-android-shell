package com.xiufeigo.opencodeshell

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.util.Base64
import android.util.LruCache
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.xiufeigo.opencodeshell.databinding.ItemMessageAssistantBinding
import com.xiufeigo.opencodeshell.databinding.ItemMessageUserBinding
import com.xiufeigo.opencodeshell.databinding.ItemSessionBinding
import com.xiufeigo.opencodeshell.databinding.ViewChatBinding
import com.xiufeigo.opencodeshell.databinding.ViewToolCardBinding
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.syntax.SyntaxHighlightPlugin
import io.noties.prism4j.Prism4j
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

/**
 * Native chat screen (M1 + v2 UI fidelity): message list + composer with SAF
 * attachments and @-mention, session drawer, and per-part rendering (markdown
 * text, reasoning/tool collapsible cards, file chips). Streaming part updates
 * are coalesced (80ms) before rebinding so fast token streams do not re-render
 * every event.
 */
class ChatView(
    context: Context,
    private val viewModel: ChatViewModel,
) : ConstraintLayout(context) {

    private val binding = ViewChatBinding.inflate(LayoutInflater.from(context), this, true)
    // <include> with an id generates a nested binding field directly; bind()
    // on top of it would double-inflate and mismatch types.
    private val drawerBinding = binding.sessionDrawer
    private val adapter = MessagesAdapter()
    private val sessionsAdapter = SessionsAdapter()
    private val markwon: Markwon = buildMarkwon()
    private val pendingAttachments = AtomicReference<List<ChatViewModel.Attachment>>(emptyList())
    private val thumbCache = object : LruCache<String, Bitmap>(THUMB_CACHE_ENTRIES) {}
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
    private var renderJob: Job? = null
    private var collectJob: Job? = null
    private var mentionSearchJob: Job? = null
    private var mentionPopup: PopupWindow? = null
    private var drawerBackCallback: OnBackPressedCallback? = null
    private var started = false
    private var latestState = ChatStore.ChatState()

    private val attachLauncher = (context as? ActivityResultRegistryOwner)?.let { owner ->
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
        // Part views are rebuilt on every stream rebind; the default change
        // animator would cross-fade them and fight the expanded-card toggles.
        binding.messageList.itemAnimator = null
        binding.messageList.adapter = adapter
        drawerBinding.sessionList.layoutManager = LinearLayoutManager(context)
        drawerBinding.sessionList.adapter = sessionsAdapter
        // Drawer width: 78% of the screen, capped at 320dp.
        binding.chatDrawerRoot.addOnLayoutChangeListener { v, l, t, r, b, oldL, oldT, oldR, oldB ->
            val width = min((v.width * DRAWER_WIDTH_RATIO).toInt(), dp(DRAWER_WIDTH_MAX_DP))
            val lp = binding.sessionDrawer.root.layoutParams
            if (r - l != oldR - oldL || lp.width != width) {
                lp.width = width
                binding.sessionDrawer.root.layoutParams = lp
            }
        }
        binding.chatDrawerRoot.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) = Unit

            override fun onDrawerOpened(drawerView: View) {
                drawerBackCallback?.isEnabled = true
                viewModel.listSessions { sessionsAdapter.submit(it) }
            }

            override fun onDrawerClosed(drawerView: View) {
                drawerBackCallback?.isEnabled = false
            }

            override fun onDrawerStateChanged(newState: Int) = Unit
        })
        binding.btnMenu.setOnClickListener { binding.chatDrawerRoot.openDrawer(Gravity.START) }
        drawerBinding.btnNewSession.setOnClickListener {
            viewModel.newSession()
            sessionsAdapter.notifyDataSetChanged()
            closeDrawer()
        }
        binding.btnAttach.setOnClickListener {
            attachLauncher?.launch(arrayOf("*/*"))
                ?: Toast.makeText(context, R.string.chat_need_config, Toast.LENGTH_SHORT).show()
        }
        binding.modelChip.setOnClickListener {
            Toast.makeText(context, R.string.oc_model_picker_soon, Toast.LENGTH_SHORT).show()
        }
        binding.btnSend.setOnClickListener { onSendClicked() }
        binding.composerInput.addTextChangedListener(SimpleTextWatcher {
            applyMentionSpans(binding.composerInput.text)
            onInputChanged()
            updateSendEnabled()
        })
        binding.chatError.setOnClickListener {
            viewModel.clearError()
            binding.chatError.visibility = View.GONE
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        registerDrawerBackCallback()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun registerDrawerBackCallback() {
        if (drawerBackCallback != null) return
        val dispatcherOwner = context as? androidx.activity.OnBackPressedDispatcherOwner ?: return
        val lifecycleOwner = findViewTreeLifecycleOwner() ?: return
        drawerBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (binding.chatDrawerRoot.isDrawerOpen(Gravity.START)) {
                    closeDrawer()
                } else {
                    // Drawer closed between the enable and the dispatch: pass
                    // the event to the next callback (activity back policy).
                    isEnabled = false
                    dispatcherOwner.onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }.also { dispatcherOwner.onBackPressedDispatcher.addCallback(lifecycleOwner, it) }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // The drawer's back interception must not steal events while this tab
        // is hidden (e.g. terminal tab open): only enable on-screen.
        drawerBackCallback?.isEnabled =
            visibility == View.VISIBLE && binding.chatDrawerRoot.isDrawerOpen(Gravity.START)
    }

    fun start() {
        // One collector for the view's lifetime: start()/stop() pairs (every
        // backgrounding) must not stack duplicate collectors on the lifecycle
        // scope, or submitList runs N times per state change.
        if (collectJob?.isActive != true) {
            collectJob = findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                launch {
                    viewModel.state.collect { onState(it) }
                }
                launch {
                    viewModel.currentTitle.collect { renderTitle(it) }
                }
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

    private fun closeDrawer() {
        binding.chatDrawerRoot.closeDrawer(Gravity.START)
    }

    private fun renderTitle(title: String?) {
        binding.chatTitle.text = title ?: context.getString(R.string.oc_new_session)
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
        val isStreaming = state.runStatus == ChatStore.RunStatus.RUNNING
        val wasStreaming = adapter.streaming
        adapter.streaming = isStreaming
        // Follow the stream only when the user is already pinned to the
        // bottom; manual scroll-up must survive incoming deltas.
        val followBottom = isAtBottom()
        adapter.submitList(state.messages) {
            if (followBottom) binding.messageList.scrollToPosition(adapter.itemCount - 1)
            // The typing cursor lives in the last row's view tree; a flip
            // between streaming and idle with unchanged content would
            // otherwise leave the blink animation running (diff sees no
            // change, so force one rebind after the diff has been applied).
            if (wasStreaming != isStreaming && adapter.itemCount > 0) {
                adapter.notifyItemChanged(adapter.itemCount - 1)
            }
        }
        when (state.runStatus) {
            ChatStore.RunStatus.RUNNING -> {
                binding.btnSend.setImageResource(R.drawable.ic_oc_stop)
                binding.btnSend.imageTintList = ColorStateList.valueOf(context.getColor(R.color.oc_danger_fg))
                binding.btnSend.contentDescription = context.getString(R.string.chat_abort)
                binding.btnSend.setOnClickListener { viewModel.abort() }
                binding.btnSend.alpha = 1f
                binding.chatError.visibility = View.GONE
            }

            else -> {
                binding.btnSend.setImageResource(R.drawable.ic_oc_send)
                binding.btnSend.imageTintList = ColorStateList.valueOf(context.getColor(R.color.oc_text_base))
                binding.btnSend.contentDescription = context.getString(R.string.chat_send)
                binding.btnSend.setOnClickListener { onSendClicked() }
                if (state.error != null) {
                    binding.chatErrorText.text = state.error
                    binding.chatError.visibility = View.VISIBLE
                } else {
                    binding.chatError.visibility = View.GONE
                }
                updateSendEnabled()
            }
        }
    }

    private fun updateSendEnabled() {
        val hasContent = !binding.composerInput.text.isNullOrEmpty() ||
            pendingAttachments.get().isNotEmpty()
        val running = latestState.runStatus == ChatStore.RunStatus.RUNNING
        binding.btnSend.alpha = if (running || hasContent) 1f else 0.5f
    }

    /** True when the list is already showing the latest row (minus tolerance). */
    private fun isAtBottom(): Boolean {
        val lm = binding.messageList.layoutManager as? LinearLayoutManager ?: return true
        val last = lm.findLastCompletelyVisibleItemPosition()
        return last == RecyclerView.NO_POSITION || last >= adapter.itemCount - 1
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
            setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.bg_composer_panel))
            mentionPopup = this
        }
        val list = ListView(context).apply {
            divider = null
            dividerHeight = 0
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        list.adapter = object : ArrayAdapter<String>(
            context,
            android.R.layout.simple_list_item_1,
            paths.take(MENTION_LIMIT),
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.setTextColor(context.getColor(R.color.oc_text_base))
                view.textSize = 14f
                view.setPadding(dp(12), dp(10), dp(12), dp(10))
                return view
            }
        }
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

    // ---------------------------------------------------------------- mentions

    /** `@file` tokens color property-pink, `@agent` tokens type-yellow-green. */
    private class MentionSpan(color: Int) : ForegroundColorSpan(color)

    private fun applyMentionSpans(spannable: Spannable?) {
        if (spannable == null) return
        for (span in spannable.getSpans(0, spannable.length, MentionSpan::class.java)) {
            spannable.removeSpan(span)
        }
        for (match in MENTION_REGEX.findAll(spannable)) {
            val token = match.value.substring(1)
            val isFile = token.contains('/') || token.contains('.')
            val color = context.getColor(if (isFile) R.color.oc_syntax_property else R.color.oc_syntax_type)
            spannable.setSpan(
                MentionSpan(color),
                match.range.first,
                match.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private fun mentionColored(text: String): SpannableString {
        val spannable = SpannableString(text)
        applyMentionSpans(spannable)
        return spannable
    }

    // ------------------------------------------------------------ attachments

    private data class AttachSpec(
        val mime: String,
        val filename: String,
        val url: String,
    )

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
            val source = android.graphics.ImageDecoder.createSource(appContext.contentResolver, uri)
            val bitmap = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
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

    private fun removeAttachment(attachment: ChatViewModel.Attachment) {
        while (true) {
            val current = pendingAttachments.get()
            val next = current - attachment
            if (pendingAttachments.compareAndSet(current, next)) break
        }
        updateAttachmentStrip()
    }

    private fun updateAttachmentStrip() {
        val attachments = pendingAttachments.get()
        val content = binding.attachmentStripContent
        content.removeAllViews()
        if (attachments.isEmpty()) {
            binding.attachmentStrip.visibility = View.GONE
            updateSendEnabled()
            return
        }
        binding.attachmentStrip.visibility = View.VISIBLE
        for (attachment in attachments) {
            content.addView(
                makeAttachmentView(
                    AttachSpec(attachment.mime, attachment.filename, attachment.dataUrl),
                    removable = true,
                ) { removeAttachment(attachment) },
            )
        }
        updateSendEnabled()
    }

    /** 58x46 image thumbnail or a file chip, with an optional remove badge. */
    private fun makeAttachmentView(
        spec: AttachSpec,
        removable: Boolean,
        onRemove: (() -> Unit)? = null,
    ): View {
        val holder = FrameLayout(context)
        val isImage = spec.mime.startsWith("image/") && spec.url.isNotEmpty()
        if (isImage) {
            val image = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(dp(58), dp(46))
                background = ContextCompat.getDrawable(context, R.drawable.bg_attachment_thumb)
                clipToOutline = true
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            bindThumb(image, spec.url)
            holder.addView(image)
        } else {
            val chip = LinearLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(46),
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(context, R.drawable.bg_attachment_chip)
                setPadding(dp(8), 0, dp(10), 0)
                addView(
                    ImageView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(14), dp(14))
                        setImageResource(R.drawable.ic_oc_file)
                        imageTintList = ColorStateList.valueOf(context.getColor(R.color.oc_icon_muted))
                        importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                )
                addView(
                    TextView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { marginStart = dp(6) }
                        text = spec.filename
                        setTextColor(context.getColor(R.color.oc_text_base))
                        textSize = 12f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        maxWidth = dp(120)
                    },
                )
            }
            holder.addView(chip)
        }
        if (removable && onRemove != null) {
            val badge = FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(dp(16), dp(16)).apply {
                    gravity = android.view.Gravity.END or android.view.Gravity.TOP
                }
                background = ContextCompat.getDrawable(context, R.drawable.bg_icon_badge)
                contentDescription = context.getString(R.string.oc_remove_attachment)
                isClickable = true
                isFocusable = true
                addView(
                    TextView(context).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { gravity = android.view.Gravity.CENTER }
                        text = "×"
                        setTextColor(context.getColor(R.color.oc_bg_base))
                        textSize = 10f
                        includeFontPadding = false
                    },
                )
                setOnClickListener { onRemove() }
            }
            holder.addView(badge)
        }
        val outer = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        outer.marginEnd = dp(8)
        holder.layoutParams = outer
        return holder
    }

    private fun bindThumb(image: ImageView, url: String) {
        val cached: Bitmap? = synchronized(thumbCache) { thumbCache[url] }
        if (cached != null) {
            image.setImageBitmap(cached)
            return
        }
        image.setImageDrawable(null)
        findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            val bitmap = withContext(Dispatchers.IO) { decodeThumb(url) } ?: return@launch
            synchronized(thumbCache) { thumbCache[url] = bitmap }
            image.setImageBitmap(bitmap)
        }
    }

    /** data: URL → Bitmap, downsampled to thumbnail scale (~3x of 58x46). */
    private fun decodeThumb(dataUrl: String): Bitmap? = try {
        val comma = dataUrl.indexOf(',')
        val base64 = if (comma >= 0) dataUrl.substring(comma + 1) else dataUrl
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMB_TARGET_PX) {
            sample *= 2
        }
        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    } catch (e: Exception) {
        Log.w(TAG, "thumbnail decode failed: ${e.message}")
        null
    }

    // ---------------------------------------------------------------- markdown

    private fun buildMarkwon(): Markwon {
        val density = resources.displayMetrics.density
        fun px(dp: Int) = (dp * density + 0.5f).toInt()
        return Markwon.builder(context)
            .usePlugin(CorePlugin.create())
            .usePlugin(SyntaxHighlightPlugin.create(Prism4j(OcGrammarLocator()), OcPrismTheme(context)))
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder
                        .linkColor(context.getColor(R.color.oc_accent))
                        .isLinkUnderlined(true)
                        .blockMargin(px(32))
                        .blockQuoteWidth(1)
                        .blockQuoteColor(context.getColor(R.color.oc_border_strong))
                        .codeTextColor(context.getColor(R.color.oc_code_inline_fg))
                        .codeBackgroundColor(context.getColor(R.color.oc_code_inline_bg))
                        .codeBlockTextColor(context.getColor(R.color.oc_text_base))
                        .codeBlockBackgroundColor(context.getColor(R.color.oc_layer_02))
                        .codeBlockMargin(px(8))
                        .codeTypeface(Typeface.MONOSPACE)
                        .codeBlockTypeface(Typeface.MONOSPACE)
                        .codeTextSize(px(12))
                        .codeBlockTextSize(px(12))
                        .headingBreakHeight(0)
                        .headingTextSizeMultipliers(
                            floatArrayOf(17f / 14f, 15f / 14f, 13f / 14f, 1f, 1f, 1f),
                        )
                }
            })
            .build()
    }

    // ------------------------------------------------------------ tool cards

    private fun titleForTool(toolName: String?): String = when (toolName) {
        "bash", "shell" -> context.getString(R.string.oc_tool_shell)
        "edit" -> context.getString(R.string.oc_tool_edit)
        "write" -> context.getString(R.string.oc_tool_write)
        "read" -> context.getString(R.string.oc_tool_read)
        "glob" -> context.getString(R.string.oc_tool_find)
        "grep" -> context.getString(R.string.oc_tool_search)
        "task" -> context.getString(R.string.oc_tool_task)
        "webfetch" -> context.getString(R.string.oc_tool_fetch)
        "todowrite" -> context.getString(R.string.oc_tool_todos)
        else -> toolName?.replaceFirstChar { it.uppercase(Locale.US) }
            ?: context.getString(R.string.oc_tool_generic)
    }

    private fun iconForTool(toolName: String?): Int = when (toolName) {
        "bash", "shell" -> R.drawable.ic_oc_console
        "edit", "write", "patch", "apply_patch" -> R.drawable.ic_oc_code
        "read" -> R.drawable.ic_oc_glasses
        "glob", "grep" -> R.drawable.ic_oc_search
        "webfetch", "websearch" -> R.drawable.ic_oc_globe
        "task" -> R.drawable.ic_oc_task
        "todowrite" -> R.drawable.ic_oc_check
        else -> R.drawable.ic_oc_file
    }

    /** Subtitle = first meaningful input key, or the error summary on failure. */
    private fun subtitleForTool(part: ChatStore.UiPart): String? {
        if (part.toolStatus == "error") {
            val summary = (part.toolError ?: part.toolOutput).orEmpty()
                .lineSequence()
                .firstOrNull { it.isNotBlank() }
            return (summary ?: context.getString(R.string.oc_tool_error)).take(TOOL_SUBTITLE_MAX)
        }
        val input = part.toolInput?.let { parseJsonObject(it) } ?: return null
        for (key in TOOL_INPUT_KEYS) {
            val value = input.optString(key, "")
            if (value.isNotEmpty()) return value.take(TOOL_SUBTITLE_MAX)
        }
        return null
    }

    private fun parseJsonObject(raw: String): JSONObject? = try {
        JSONObject(raw)
    } catch (_: Exception) {
        null
    }

    /** Pretty JSON (keys muted, strings green) or shell/diff text for the body. */
    private fun toolBodyText(part: ChatStore.UiPart): CharSequence {
        val name = part.toolName.orEmpty()
        val input = part.toolInput?.let { parseJsonObject(it) }
        if (name == "bash" || name == "shell") {
            val command = input?.optString("command", "").orEmpty()
            val output = part.toolOutput.orEmpty().replace("\r\n", "\n").replace("\r", "\n")
            return plainSpannable("$ $command" + if (output.isNotEmpty()) "\n\n$output" else "")
        }
        if (name == "edit" || name == "write") {
            val oldText = input?.optString("oldString", "").orEmpty()
            val newText = input?.optString("newString", "").orEmpty()
            if (oldText.isNotEmpty() || newText.isNotEmpty()) {
                return diffSpannable(oldText, newText)
            }
        }
        val builder = SpannableStringBuilder()
        part.toolInput?.let { raw ->
            builder.append(jsonSpannable(raw))
        }
        part.toolOutput?.let { output ->
            if (builder.isNotEmpty()) builder.append("\n\n")
            builder.append(plainSpannable(output))
        }
        return builder
    }

    private fun plainSpannable(text: String): SpannableStringBuilder =
        SpannableStringBuilder(text)

    private fun diffSpannable(oldText: String, newText: String): SpannableStringBuilder {
        val builder = SpannableStringBuilder()
        val addLines = { prefix: String, text: String, color: Int ->
            text.lineSequence().forEach { line ->
                val start = builder.length
                builder.append(prefix)
                builder.append(line)
                builder.setSpan(
                    ForegroundColorSpan(context.getColor(color)),
                    start,
                    builder.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                builder.append("\n")
            }
        }
        addLines("- ", oldText, R.color.oc_diff_del_fg)
        addLines("+ ", newText, R.color.oc_diff_add_fg)
        return builder
    }

    private fun jsonSpannable(raw: String): SpannableStringBuilder {
        val pretty = try {
            parseJsonObject(raw)?.toString(2) ?: JSONArray(raw).toString(2)
        } catch (_: Exception) {
            raw
        }
        val builder = SpannableStringBuilder(pretty)
        for (match in JSON_STRING_REGEX.findAll(pretty)) {
            // Key vs string value: skip whitespace after the closing quote and
            // treat only a directly-following ':' as a key marker.
            var cursor = match.range.last + 1
            while (cursor < pretty.length && pretty[cursor].isWhitespace()) cursor++
            val isKey = cursor < pretty.length && pretty[cursor] == ':'
            val color = if (isKey) R.color.oc_text_muted else R.color.oc_syntax_string
            builder.setSpan(
                ForegroundColorSpan(context.getColor(color)),
                match.range.first,
                match.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return builder
    }

    private fun setToolBody(card: ViewToolCardBinding, part: ChatStore.UiPart) {
        val body = card.toolBodyText
        body.text = toolBodyText(part)
        val listWidth = binding.messageList.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        // Approximate the card's inner text width: list side paddings (2x16),
        // accent bar + margins (~12) and card paddings (8 + 12).
        val available = listWidth - dp(16) * 2 - dp(12) - dp(8) - dp(12)
        body.measure(
            View.MeasureSpec.makeMeasureSpec(available.coerceAtLeast(1), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        card.toolBodyScroll.layoutParams.height = body.measuredHeight
            .coerceAtMost(dp(TOOL_BODY_MAX_HEIGHT_DP))
        card.toolBodyScroll.requestLayout()
    }

    // ---------------------------------------------------------------- adapter

    private class MessageDiff : DiffUtil.ItemCallback<ChatStore.UiMessage>() {
        override fun areItemsTheSame(old: ChatStore.UiMessage, new: ChatStore.UiMessage) =
            old.id == new.id

        override fun areContentsTheSame(old: ChatStore.UiMessage, new: ChatStore.UiMessage) =
            old == new
    }

    private inner class MessagesAdapter :
        ListAdapter<ChatStore.UiMessage, RecyclerView.ViewHolder>(MessageDiff()) {

        /** Mirrors runStatus == RUNNING; gates the cursor + shimmer animations. */
        var streaming: Boolean = false

        val expanded = HashMap<String, Boolean>()

        override fun getItemViewType(position: Int): Int =
            if (getItem(position).role == "user") TYPE_USER else TYPE_ASSISTANT

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_USER) {
                UserHolder(ItemMessageUserBinding.inflate(inflater, parent, false))
            } else {
                AssistantHolder(ItemMessageAssistantBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val message = getItem(position)
            when (holder) {
                is UserHolder -> holder.bind(message)
                is AssistantHolder -> holder.bind(message, position)
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is AssistantHolder) holder.stopAnimations()
            super.onViewRecycled(holder)
        }

        inner class UserHolder(val item: ItemMessageUserBinding) :
            RecyclerView.ViewHolder(item.root) {

            fun bind(message: ChatStore.UiMessage) {
                item.userBubble.maxWidth = (resources.displayMetrics.widthPixels * BUBBLE_MAX_RATIO).toInt()
                val text = message.parts
                    .filter { it.type == "text" }
                    .joinToString("\n\n") { it.text }
                if (text.isEmpty()) {
                    item.userBubble.visibility = View.GONE
                } else {
                    item.userBubble.visibility = View.VISIBLE
                    item.userBubble.text = mentionColored(text)
                }
                val files = message.parts
                    .filter { it.type == "file" }
                    .map { AttachSpec(it.mime.orEmpty(), it.filename.orEmpty(), it.url.orEmpty()) }
                item.userAttachments.removeAllViews()
                if (files.isEmpty()) {
                    item.userAttachments.visibility = View.GONE
                } else {
                    item.userAttachments.visibility = View.VISIBLE
                    for (spec in files) {
                        item.userAttachments.addView(makeAttachmentView(spec, removable = false))
                    }
                }
                if (message.timeCreated > 0) {
                    item.userMeta.text = timeFormat.format(message.timeCreated)
                    item.userMeta.visibility = View.VISIBLE
                } else {
                    item.userMeta.visibility = View.GONE
                }
            }
        }

        inner class AssistantHolder(val item: ItemMessageAssistantBinding) :
            RecyclerView.ViewHolder(item.root) {

            val animators = mutableListOf<ObjectAnimator>()

            fun stopAnimations() {
                for (animator in animators) animator.cancel()
                animators.clear()
            }

            fun bind(message: ChatStore.UiMessage, position: Int) {
                stopAnimations()
                item.partsContainer.removeAllViews()
                val isLastMessage = position == itemCount - 1
                val parts = message.parts
                var first = true
                for ((index, part) in parts.withIndex()) {
                    val view = when (part.type) {
                        "text" -> part.text.takeIf { it.isNotBlank() }?.let { textViewFor(it) }
                        "reasoning" -> part.text.takeIf { it.isNotBlank() }?.let {
                            reasoningCard(part, this, index == parts.lastIndex && isLastMessage)
                        }
                        "tool" -> part.toolName?.takeIf { it != "todowrite" }?.let { toolCard(part, this) }
                        "file" -> fileChip(part)
                        else -> null
                    }
                    if (view != null) {
                        addItemView(view, first)
                        first = false
                    }
                }
                // Streaming typing cursor on the trailing text part.
                if (streaming && isLastMessage && parts.lastOrNull()?.type == "text") {
                    addItemView(makeCursor(), first)
                }
            }

            private fun addItemView(view: View, isFirst: Boolean) {
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                lp.topMargin = if (isFirst) 0 else dp(PART_GAP_DP)
                item.partsContainer.addView(view, lp)
            }

            private fun textViewFor(text: String): TextView = TextView(context).apply {
                setTextColor(context.getColor(R.color.oc_text_base))
                textSize = 14f
                setLineSpacing(0f, 1.5f)
                setTextIsSelectable(true)
                markwon.setMarkdown(this, text)
            }

            private fun makeCursor(): TextView {
                val cursor = TextView(context).apply {
                    text = context.getString(R.string.oc_cursor)
                    setTextColor(context.getColor(R.color.oc_text_base))
                    textSize = 14f
                }
                val animator = ObjectAnimator.ofFloat(cursor, View.ALPHA, 1f, 0f).apply {
                    duration = CURSOR_BLINK_MS
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    start()
                }
                animators.add(animator)
                return cursor
            }
        }

        private fun startShimmer(titleView: TextView, holder: AssistantHolder) {
            val animator = ObjectAnimator.ofFloat(titleView, View.ALPHA, 1f, SHIMMER_MIN_ALPHA).apply {
                duration = SHIMMER_DURATION_MS
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
            holder.animators.add(animator)
        }

        private fun toolCard(part: ChatStore.UiPart, holder: AssistantHolder): View {
            val card = ViewToolCardBinding.inflate(LayoutInflater.from(context))
            val running = part.toolStatus == "pending" || part.toolStatus == "running"
            val isError = part.toolStatus == "error"
            val accentRes = when {
                isError -> R.color.oc_danger_fg
                running -> R.color.oc_accent
                else -> R.color.oc_layer_03
            }
            card.toolAccentBar.backgroundTintList = ColorStateList.valueOf(context.getColor(accentRes))
            card.toolIcon.setImageResource(iconForTool(part.toolName))
            card.toolTitle.text = titleForTool(part.toolName)
            card.toolTitle.alpha = 1f
            if (running) startShimmer(card.toolTitle, holder)
            val subtitle = subtitleForTool(part)
            if (subtitle.isNullOrEmpty()) {
                card.toolSubtitle.visibility = View.GONE
            } else {
                card.toolSubtitle.visibility = View.VISIBLE
                card.toolSubtitle.text = subtitle
            }
            val open = expanded[part.id] == true
            card.toolBodyScroll.visibility = if (open) View.VISIBLE else View.GONE
            card.toolChevron.rotation = if (open) 180f else 0f
            if (open) setToolBody(card, part)
            card.toolTriggerRow.setOnClickListener {
                val newOpen = !(expanded[part.id] == true)
                expanded[part.id] = newOpen
                card.toolBodyScroll.visibility = if (newOpen) View.VISIBLE else View.GONE
                card.toolChevron.animate()
                    .rotation(if (newOpen) 180f else 0f)
                    .setDuration(CHEVRON_ROTATE_MS)
                    .start()
                if (newOpen) setToolBody(card, part)
            }
            return card.root
        }

        private fun reasoningCard(
            part: ChatStore.UiPart,
            holder: AssistantHolder,
            shimmer: Boolean,
        ): View {
            val card = ViewToolCardBinding.inflate(LayoutInflater.from(context))
            card.toolAccentBar.visibility = View.GONE
            card.toolIcon.visibility = View.GONE
            card.toolTitle.text = context.getString(R.string.oc_thought)
            card.toolTitle.alpha = 1f
            if (shimmer) startShimmer(card.toolTitle, holder)
            card.toolSubtitle.visibility = View.GONE
            val open = expanded[part.id] == true
            card.toolBodyScroll.visibility = if (open) View.VISIBLE else View.GONE
            card.toolChevron.rotation = if (open) 180f else 0f
            if (open) renderReasoningBody(card, part)
            card.toolTriggerRow.setOnClickListener {
                val newOpen = !(expanded[part.id] == true)
                expanded[part.id] = newOpen
                card.toolBodyScroll.visibility = if (newOpen) View.VISIBLE else View.GONE
                card.toolChevron.animate()
                    .rotation(if (newOpen) 180f else 0f)
                    .setDuration(CHEVRON_ROTATE_MS)
                    .start()
                if (newOpen) renderReasoningBody(card, part)
            }
            return card.root
        }

        private fun renderReasoningBody(card: ViewToolCardBinding, part: ChatStore.UiPart) {
            val body = card.toolBodyText
            body.typeface = Typeface.SANS_SERIF
            body.textSize = 13f
            body.setTextColor(context.getColor(R.color.oc_text_muted))
            markwon.setMarkdown(body, part.text)
            capReasoningBody(card)
        }

        private fun capReasoningBody(card: ViewToolCardBinding) {
            val body = card.toolBodyText
            val listWidth = binding.messageList.width.takeIf { it > 0 }
                ?: resources.displayMetrics.widthPixels
            val available = listWidth - dp(16) * 2 - dp(8) - dp(12)
            body.measure(
                View.MeasureSpec.makeMeasureSpec(available.coerceAtLeast(1), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            card.toolBodyScroll.layoutParams.height = body.measuredHeight
                .coerceAtMost(dp(TOOL_BODY_MAX_HEIGHT_DP))
            card.toolBodyScroll.requestLayout()
        }

        private fun fileChip(part: ChatStore.UiPart): View {
            // File parts may carry only a (possibly huge) data URL; never use
            // that as the displayed name.
            val name = part.filename
                ?: part.url?.takeIf { !it.startsWith("data:") }
                ?: context.getString(R.string.oc_attachment_file)
            return makeAttachmentView(
                AttachSpec(part.mime.orEmpty(), name, part.url.orEmpty()),
                removable = false,
            )
        }
    }

    // --------------------------------------------------------------- sessions

    private inner class SessionsAdapter :
        RecyclerView.Adapter<SessionsAdapter.SessionHolder>() {

        private val sessions = mutableListOf<OpenCodeClient.Session>()

        @SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<OpenCodeClient.Session>) {
            sessions.clear()
            sessions.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionHolder =
            SessionHolder(
                ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            )

        override fun getItemCount(): Int = sessions.size

        override fun onBindViewHolder(holder: SessionHolder, position: Int) {
            val session = sessions[position]
            val selected = session.id == viewModel.sessionID
            holder.item.root.setBackgroundColor(
                if (selected) context.getColor(R.color.oc_layer_01) else context.getColor(android.R.color.transparent),
            )
            holder.item.sessionTitle.text = session.title
            if (session.updatedAt > 0) {
                holder.item.sessionTime.text = DateUtils.getRelativeTimeSpanString(session.updatedAt)
                holder.item.sessionTime.visibility = View.VISIBLE
            } else {
                holder.item.sessionTime.visibility = View.GONE
            }
            holder.item.root.setOnClickListener {
                viewModel.observeSession(session.id, session.title)
                closeDrawer()
            }
        }

        inner class SessionHolder(val item: ItemSessionBinding) :
            RecyclerView.ViewHolder(item.root)
    }

    // ----------------------------------------------------------------- helpers

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val TAG = "ChatView"
        private const val TYPE_USER = 1
        private const val TYPE_ASSISTANT = 2
        private const val MENTION_LIMIT = 20
        private const val MENTION_DEBOUNCE_MS = 200L
        private const val MAX_ATTACHMENTS = 6
        private const val MAX_ATTACHMENT_BYTES = 8 * 1024 * 1024
        private const val MAX_IMAGE_EDGE = 2048
        private const val THUMB_CACHE_ENTRIES = 24
        private const val RENDER_COALESCE_MS = 80L
        private const val PART_GAP_DP = 12
        private const val CURSOR_BLINK_MS = 800L
        private const val SHIMMER_DURATION_MS = 1000L
        private const val SHIMMER_MIN_ALPHA = 0.55f
        private const val CHEVRON_ROTATE_MS = 150L
        private const val TOOL_BODY_MAX_HEIGHT_DP = 220
        private const val TOOL_SUBTITLE_MAX = 120
        private const val THUMB_TARGET_PX = 174
        private const val BUBBLE_MAX_RATIO = 0.82f
        private const val DRAWER_WIDTH_RATIO = 0.78f
        private const val DRAWER_WIDTH_MAX_DP = 320

        private val MENTION_REGEX = Regex("@[A-Za-z0-9_][A-Za-z0-9_.\\-/]*")

        private val JSON_STRING_REGEX = Regex("\"(?:\\\\.|[^\"\\\\])*\"")

        private val TOOL_INPUT_KEYS = listOf(
            "command", "filePath", "pattern", "query", "url", "path", "description", "name",
        )
    }
}
