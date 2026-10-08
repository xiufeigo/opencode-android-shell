import fs from "node:fs/promises"
import { fileURLToPath } from "node:url"
import { prepareEmbeddedServerStorage } from "./server-state.mjs"

const entry = fileURLToPath(new URL("../../webapp/src/main/assets/www/ui/oa/index-D2r3WEwY.js", import.meta.url))
const before = "function ZI(){return location.origin}"
// Equivalent to the upstream VITE_OPENCODE_SERVER_MODE=none build option.
// This origin serves APK assets only; it does not expose an OpenCode backend.
const after = "function ZI(){return void 0}"
const prefix = `(${prepareEmbeddedServerStorage.toString()})(localStorage,location.origin);\n`

export function patchWebPlatform(source) {
  if (source.startsWith(prefix)) {
    if (source.includes(before) || source.split(after).length !== 2) throw new Error("Incomplete embedded web platform patch")
    return source
  }
  if (source.split(before).length !== 2) throw new Error("Embedded web platform anchor changed; review the upstream entry")
  return prefix + source.replace(before, after)
}

export async function patchEmbeddedUi() {
  const source = await fs.readFile(entry, "utf8")
  const patched = patchWebPlatform(source)
  if (patched !== source) await fs.writeFile(entry, patched)
}
