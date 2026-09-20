package com.xiufeigo.opencodeshell.ui.session.composer

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.xiufeigo.opencodeshell.ChatViewModel
import com.xiufeigo.opencodeshell.R
import kotlinx.coroutines.launch

/**
 * The composer's 「+」 SAF picker.
 *
 * Registration has to happen in composition, but the *state* belongs to the
 * screen (see [com.xiufeigo.opencodeshell.ui.session.SessionScreen]), so this is
 * a small controller: the caller keeps the attachment list, the controller owns
 * the `rememberLauncherForActivityResult` registration, the IO read and the
 * limit/size toasts.
 *
 * Mirrors `ChatView.attachLauncher` (L101-109) + `ingestAttachments` (L468): the
 * same `OpenMultipleDocuments` contract with the wildcard mime filter, and the
 * same "merge with the current chips, then cap at 6" order, but the cap/dedupe
 * rule itself is the unit-tested [AttachmentPolicy.merge].
 */
internal class AttachmentPicker(private val launch: () -> Unit) {

    /** Opens the system document picker; no-op result when the user cancels. */
    fun pick() = launch()
}

@Composable
internal fun rememberAttachmentPicker(
    attachments: List<ChatViewModel.Attachment>,
    onAttachmentsChange: (List<ChatViewModel.Attachment>) -> Unit,
): AttachmentPicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The result callback runs outside the composition that registered it, so it
    // must read the *current* chips and not a stale capture.
    val currentAttachments by rememberUpdatedState(attachments)
    val update by rememberUpdatedState(onAttachmentsChange)

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val result = AttachmentReader.readAll(context, uris)
            for (name in result.tooLarge) {
                Toast.makeText(
                    context,
                    context.getString(R.string.chat_attach_too_large, name),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            val merged = AttachmentPolicy.merge(currentAttachments, result.attachments)
            if (merged.dropped > 0) {
                Toast.makeText(
                    context,
                    context.getString(R.string.chat_attach_limit, AttachmentPolicy.MAX_ATTACHMENTS),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            if (merged.attachments != currentAttachments) update(merged.attachments)
        }
    }

    return remember(launcher) {
        AttachmentPicker { launcher.launch(arrayOf("*/*")) }
    }
}
