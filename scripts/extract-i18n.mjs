// Extract per-locale i18n tables from the vendored opencode web build.
// English is inlined in the entry bundle (packages/app/src/i18n/en.ts + packages/ui/src/i18n/en.ts);
// zh / zht live in dedicated chunks. Output is split per file so locales never mix.
// Usage: node scripts/extract-i18n.mjs
import { readFileSync, writeFileSync, readdirSync, mkdirSync } from "node:fs"

const ASSETS = "app/src/main/assets/opencode-app/assets"
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
