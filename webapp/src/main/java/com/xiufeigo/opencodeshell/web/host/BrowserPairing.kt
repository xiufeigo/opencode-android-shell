package com.xiufeigo.opencodeshell.web.host

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONObject
import java.net.URI

/** Browser pairing has one encrypted source of truth, separate from legacy host preferences. */
class BrowserPairing(private val context: Context) : AutoCloseable {
    private var dialog: AlertDialog? = null
    private val key = "browserPairing"

    fun read(): JSONObject? = SecretStore.get(context).getString(key, null)?.let(::JSONObject)

    fun configure(reply: (Any?, Exception?) -> Unit) {
        check(dialog == null) { "Browser configuration is already open" }
        val saved = read()
        val url = EditText(context).apply {
            contentDescription = "浏览器服务地址"
            hint = "浏览器服务地址，如 http://192.168.1.10:4100"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(saved?.optString("url"))
        }
        val token = EditText(context).apply {
            contentDescription = "配对令牌"
            hint = "配对令牌（服务的 pairing-token 文件）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(saved?.optString("token"))
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(url)
            addView(token)
        }
        val editor = AlertDialog.Builder(context).setTitle("连接内置浏览器服务").setView(layout)
            .setPositiveButton("连接", null)
            .setNegativeButton("取消") { _, _ -> reply(false, null) }
            .setNeutralButton("断开") { _, _ ->
                try {
                    check(SecretStore.get(context).edit().remove(key).commit()) { "Cannot persist browser configuration" }
                    reply(null, null)
                } catch (error: Exception) { reply(null, error) }
            }.create()
        dialog = editor
        editor.setOnCancelListener { reply(false, null) }
        editor.setOnDismissListener { dialog = null }
        editor.setOnShowListener {
            editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val address = URI(url.text.toString().trim())
                    require(address.scheme in setOf("http", "https") && address.host != null &&
                        address.userInfo == null && address.query == null && address.fragment == null) { "服务地址必须是 HTTP/HTTPS URL" }
                    val credential = token.text.toString().trim()
                    require(credential.matches(Regex("[A-Za-z0-9_-]{32,256}"))) { "配对令牌格式不正确" }
                    val config = JSONObject().put("url", address.toString().trimEnd('/')).put("token", credential)
                    check(SecretStore.get(context).edit().putString(key, config.toString()).commit()) { "Cannot persist browser configuration" }
                    reply(config, null)
                    editor.dismiss()
                } catch (error: Exception) { token.error = error.message }
            }
        }
        editor.show()
    }

    override fun close() { dialog?.dismiss(); dialog = null }
}
