package com.xiufeigo.opencodeshell

import android.text.Editable
import android.text.TextWatcher

/** Minimal [TextWatcher] for change-only listeners (composer, search boxes). */
class SimpleTextWatcher(private val onChanged: () -> Unit) : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

    override fun afterTextChanged(s: Editable?) {
        onChanged()
    }
}
