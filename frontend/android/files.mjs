// WebChromeClient supplies Android document URIs directly to this file input.
// Reading File objects avoids the legacy bridge's inline-file size limit.
export function openAttachmentPickerDialog(opts, onFile) {
  return new Promise((resolve, reject) => {
    const input = document.createElement("input")
    input.type = "file"
    input.hidden = true
    input.multiple = opts.multiple ?? false
    input.accept = [...(opts.accept ?? []), ...(opts.extensions ?? []).map(ext => `.${ext.replace(/^\./, "")}`)].join(",")
    const cleanup = () => input.remove()
    input.addEventListener("cancel", () => { cleanup(); resolve() }, { once: true })
    input.addEventListener("change", async () => {
      const files = Array.from(input.files ?? [])
      cleanup()
      try {
        for (const file of files) await onFile(file)
        resolve()
      } catch (error) { reject(error) }
    }, { once: true })
    document.body.append(input)
    try { input.click() } catch (error) { cleanup(); reject(error) }
  })
}
