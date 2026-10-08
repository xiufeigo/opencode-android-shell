// The Android host owns this loopback origin and serves no OpenCode API there.
// It cannot also be a backend: only its obsolete placeholder is removed.
export function prepareEmbeddedServerStorage(storage, origin) {
  const isOrigin = (url) => typeof url === "string" && url.replace(/\/+$/, "") === origin
  const updates = []
  for (const key of ["opencode.global.dat:server", "opencode.global.dat:server.v3"]) {
    const raw = storage.getItem(key)
    if (raw === null) continue
    const state = JSON.parse(raw)
    if (!state || !Array.isArray(state.list)) throw new Error("Invalid server registry: " + key)
    if (state.list.some(entry => typeof entry !== "string" && (!entry || typeof entry !== "object" || Array.isArray(entry)))) {
      throw new Error("Invalid server registry entry: " + key)
    }
    const list = state.list.filter((entry) => !isOrigin(typeof entry === "string" ? entry : entry.http?.url ?? entry.url))
    const originHidden = state.hidden && Object.hasOwn(state.hidden, origin)
    if (list.length === state.list.length && !originHidden) continue
    const next = { ...state, list }
    if (originHidden) next.hidden = Object.fromEntries(Object.entries(state.hidden).filter(([key]) => key !== origin))
    updates.push([key, JSON.stringify(next)])
  }
  const defaultKey = "opencode.settings.dat:defaultServerUrl"
  const clearDefault = isOrigin(storage.getItem(defaultKey))
  // Parse and validate both registry generations before changing either one.
  for (const [key, value] of updates) storage.setItem(key, value)
  if (clearDefault) storage.removeItem(defaultKey)
}
