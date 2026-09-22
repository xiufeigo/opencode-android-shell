// Extract per-locale i18n tables for the native Android shell.
//
// Two modes:
//
//   1. --src (default, v2): parse the upstream TypeScript dict sources
//      directly (--src-app, --src-ui, --out). Complete by
//      construction: every key present in the sources lands in the output,
//      including hyphenated keys (ssh.error.host-key,
//      settings.timeline.preset.text-only) and single-quoted values, both of
//      which the old dist-chunk regex missed. TS escape sequences in values
//      (\\n, \\', \\", \\\\, \\r, \\t) are decoded to their runtime form so
//      the extracted JSON matches what the web runtime actually renders.
//      Output: research/_i18n_v2/{en,zh,zht}.json (per-locale union of the
//      app + ui dicts; zh/zht hold only the keys translated upstream, the
//      rest falls back to English at runtime).
//
//   2. --dist <dir>: legacy v1 path. Scan vendored web-build JS chunks for
//      inlined "dotted.key":"value" tables (English inlined in the entry
//      bundle, zh/zht in dedicated chunks). Output: research/_i18n/*.json.
//      Kept for backward compatibility; the v1 bundle is stale.
//
// Usage:
//   node scripts/extract-i18n.mjs                        (v2 src extraction)
//   node scripts/extract-i18n.mjs --src                   (same, explicit)
//   node scripts/extract-i18n.mjs --dist app/src/main/assets/opencode-app/assets
import { readFileSync, writeFileSync, readdirSync, mkdirSync } from "node:fs"
import { join, isAbsolute } from "node:path"

const args = process.argv.slice(2)
const distIdx = args.indexOf("--dist")
const MODE = distIdx >= 0 ? "dist" : "src"

if (MODE === "dist") {
  // ---------------------------------------------------------------- legacy --
  // Scan vendored web-build JS chunks for inlined locale tables.
  const ASSETS =
    distIdx + 1 < args.length && !args[distIdx + 1].startsWith("--")
      ? args[distIdx + 1]
      : "app/src/main/assets/opencode-app/assets"
  const OUT = "research/_i18n"
  mkdirSync(OUT, { recursive: true })

  const KV = /"([a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)+)":"((?:[^"\\]|\\.)*)"/g

  // Scan every JS chunk: locale tables are spread across many imported chunks, not
  // only the entry bundle. Skip the tree-sitter grammar / wasm hunks, which are huge
  // and contain no i18n keys, to keep this fast.
  const SKIP = /^(cpp|emacs-lisp|wasm|ghostty-web|markdown\.worker)-/
  const files = readdirSync(ASSETS)
    .filter((f) => f.endsWith(".js"))
    .filter((f) => !SKIP.test(f))

  const summary = {}
  /** en is spread over the entry bundle only; zh/zht each have a dedicated chunk. */
  const buckets = { en: {}, zh: {}, zht: {} }

  for (const f of files) {
    let src
    try {
      src = readFileSync(`${ASSETS}/${f}`, "utf8")
    } catch {
      continue
    }
    const bucket = f.startsWith("zht-") ? "zht" : f.startsWith("zh-") ? "zh" : f.startsWith("index-") ? "en" : null
    if (!bucket) continue
    KV.lastIndex = 0
    let m
    let n = 0
    while ((m = KV.exec(src)) !== null) {
      buckets[bucket][m[1]] = m[2]
      n++
    }
    if (n) summary[f] = { bucket, keys: n }
  }

  for (const [loc, dict] of Object.entries(buckets)) {
    const keys = Object.keys(dict).sort()
    writeFileSync(`${OUT}/${loc}.json`, JSON.stringify(dict, null, 2))
    console.log(`${loc}: ${keys.length} keys -> ${OUT}/${loc}.json`)
  }
  console.log(JSON.stringify(summary, null, 2))
} else {
  // ---------------------------------------------------------- v2 sources --
  // Parse upstream TS dicts (app + desktop-native + ui) per locale and merge
  // to the same per-locale unions the web runtime builds in language.tsx:
  //   base = flatten({ ...appEn, ...uiEn })
  //   zh   = { ...base, ...flatten({ ...appZh, ...uiZh }) }
  const flag = (name, def) => {
    const i = args.indexOf(name)
    return i >= 0 && i + 1 < args.length && !args[i + 1].startsWith("--") ? args[i + 1] : def
  }
  const SRC_APP = flag("--src-app", "_upstream/packages/app/src/runtime/i18n")
  const SRC_UI = flag("--src-ui", "_upstream/packages/ui/src/i18n")
  const OUT = flag("--out", "research/_i18n_v2")
  mkdirSync(OUT, { recursive: true })

  // Dotted keys may contain hyphens (ssh.error.host-key,
  // settings.timeline.preset.text-only); the old dist regex excluded them.
  const KEY = String.raw`"([a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_-]+)+)"`
  // Values are double- or single-quoted TS strings; backtick values do not
  // occur in the dict sources (only in unrelated type-level template code).
  const VAL = String.raw`(?:"((?:[^"\\]|\\.)*)"|'((?:[^'\\]|\\.)*)')`
  const KV = new RegExp(`${KEY}\\s*:\\s*${VAL}`, "g")

  // Decode the TS escape sequences that occur in dict values to runtime text.
  // Only \\n and \\' occur in today's dicts; the rest is forward coverage.
  const TS_ESCAPES = { n: "\n", r: "\r", t: "\t", b: "\b", f: "\f", v: "\v", 0: "\0" }
  function decodeTS(s) {
    return s.replace(/\\(u\{[0-9a-fA-F]+\}|u[0-9a-fA-F]{4}|x[0-9a-fA-F]{2}|.)/g, (m, e) => {
      if (e.length === 1) {
        if (e === "\\") return "\\"
        return TS_ESCAPES[e] ?? e
      }
      if (e.startsWith("u{")) return String.fromCodePoint(parseInt(e.slice(2, -1), 16))
      if (e.startsWith("u")) return String.fromCharCode(parseInt(e.slice(1), 16))
      return String.fromCharCode(parseInt(e.slice(1), 16)) // \x..
    })
  }

  function parseDict(path) {
    // CLI paths are repo-relative (or absolute); keep CWD-relative resolution
    // so the script works from a repo checkout on any platform.
    const file = isAbsolute(path) ? path : join(process.cwd(), path)
    const src = readFileSync(file, "utf8")
    KV.lastIndex = 0
    const d = {}
    let m
    while ((m = KV.exec(src)) !== null) d[m[1]] = decodeTS(m[2] !== undefined ? m[2] : m[3])
    return d
  }

  // desktop-native.ts exports DESKTOP_NATIVE_ENGLISH (spread into app en);
  // zh/zht carry desktop.* keys inline, so only en needs the extra file.
  const appEn = { ...parseDict(join(SRC_APP, "desktop-native.ts")), ...parseDict(join(SRC_APP, "en.ts")) }
  const uiEn = parseDict(join(SRC_UI, "en.ts"))
  const buckets = { en: { ...appEn, ...uiEn } }
  for (const loc of ["zh", "zht"]) {
    const app = parseDict(join(SRC_APP, `${loc}.ts`))
    const ui = parseDict(join(SRC_UI, `${loc}.ts`))
    buckets[loc] = { ...app, ...ui }
  }

  for (const [loc, dict] of Object.entries(buckets)) {
    const keys = Object.keys(dict).sort()
    const sorted = {}
    for (const k of keys) sorted[k] = dict[k]
    writeFileSync(join(OUT, `${loc}.json`), JSON.stringify(sorted, null, 2) + "\n")
    console.log(`${loc}: ${keys.length} keys -> ${OUT}/${loc}.json`)
  }
}
