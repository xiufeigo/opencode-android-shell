import fs from "node:fs/promises"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { execFileSync, spawn } from "node:child_process"
import { applyAndroidSources } from "./sources.mjs"

const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.resolve(here, "../..")
const pin = JSON.parse(await fs.readFile(path.join(here, "upstream.json"), "utf8"))
const bun = process.env.BUN_BINARY ?? "bun"
const bunVersion = execFileSync(bun, ["--version"], { encoding: "utf8" }).trim()
if (bunVersion !== pin.bun) throw new Error(`Bun ${pin.bun} is required; found ${bunVersion}. Set BUN_BINARY to the pinned executable.`)
const checkout = path.join(root, "_scratch_root/desktop-parity/checkout")
const target = process.argv.includes("--web") ? "web" : "android"
const assets = path.join(root, target === "android" ? "webapp/src/main/assets/www/ui" : "frontend/web/dist")
const workspaces = ["app", "gui-extensions", "ui", "client", "schema", "util", "session-ui", "plugin-browser", "protocol", "plugin", "script", "httpapi-codegen", "theme", "ai", "http-recorder"]

async function run(command, args, cwd = checkout, env = {}) {
  console.log(`[android-ui] ${command} ${args.join(" ")}`)
  await new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd, stdio: "inherit", env: { ...process.env, ...env } })
    child.on("error", reject)
    child.on("exit", code => code === 0 ? resolve() : reject(new Error(`${command} exited with ${code}`)))
  })
}

await fs.mkdir(checkout, { recursive: true })
try { await fs.access(path.join(checkout, ".git")) } catch {
  await run("git", ["init"])
  await run("git", ["remote", "add", "origin", pin.repository])
  await run("git", ["config", "remote.origin.promisor", "true"])
  await run("git", ["config", "remote.origin.partialclonefilter", "blob:none"])
  await run("git", ["sparse-checkout", "init", "--cone"])
}
// This directory is a disposable build checkout, never the developer's reference
// repository. Restoring tracked source prevents patches accumulating on rebuild.
// Public files participate in asset hashes; Windows CRLF conversion would make
// the checked-in UI differ from the Linux CI build of the same commit.
await run("git", ["config", "--local", "core.autocrlf", "false"])
// Tailwind scans available source, including packages added during an audit.
// Fix the input scope on every build so cached and fresh checkouts agree.
await run("git", ["sparse-checkout", "set", "patches", ...workspaces.map(name => `packages/${name}`), "packages/desktop/icons"])
await run("git", ["fetch", "--filter=blob:none", "--depth", "1", "origin", pin.commit])
await run("git", ["checkout", "--force", "--detach", pin.commit])
// An older checkout can retain CRLF files in Git's stat cache. Remove only
// files whose LF blobs were converted, then restore their original bytes.
// --eol reads blobs: scanning excluded workspaces would defeat partial clone.
const sourcePaths = [":(top,glob)*", "patches", ...workspaces.map(name => `packages/${name}`), "packages/desktop/icons"]
console.log("[android-ui] Verify checked-out source line endings")
const converted = execFileSync("git", ["ls-files", "--eol", "-z", "--", ...sourcePaths], { cwd: checkout, encoding: "utf8" })
  .split("\0").filter(row => /^i\/lf\s+w\/(crlf|mixed)\s/.test(row)).map(row => row.split("\t")[1])
for (const name of converted) {
  const filename = path.resolve(checkout, name)
  if (!filename.startsWith(checkout + path.sep)) throw new Error("Source path escapes build checkout")
  await fs.unlink(filename)
}
await run("git", ["checkout-index", "--force", "--all"])
const pkg = JSON.parse(await fs.readFile(path.join(checkout, "packages/app/package.json"), "utf8"))
if (pkg.version !== pin.version) throw new Error("Pinned OpenCode version does not match source")
await applyAndroidSources(checkout, here)
await run(bun, ["install", "--frozen-lockfile", "--ignore-scripts", "--filter", "@opencode/app"])
await run(bun, ["test", "src/settings/route.test.ts", "src/runtime/polyfills.test.ts"], path.join(checkout, "packages/app"))
await run(bun, ["test", "--conditions=browser", "src/terminal/session-model.test.ts"], path.join(checkout, "packages/gui-extensions"))
await run(bun, ["run", "typecheck", "tsconfig.android.json"], path.join(checkout, "packages/app"))
await run(bun, ["run", "typecheck", "tsconfig.android.json"], path.join(checkout, "packages/gui-extensions"))
await run(bun, ["run", "build", "--base", "/", "--sourcemap", "false", "--logLevel", "warn"], path.join(checkout, "packages/app"), {
  VITE_OPENCODE_SERVER_MODE: "none",
  VITE_OPENCODE_PLATFORM: target,
  OPENCODE_CHANNEL: "prod",
})
const dist = path.join(checkout, "packages/app/dist")
// Only replace packaged assets after the complete source build succeeds.
await fs.rm(assets, { recursive: true, force: true })
await fs.cp(dist, assets, { recursive: true })
if (target === "android") await fs.rename(path.join(assets, "_assets"), path.join(assets, "oa"))
else await fs.copyFile(path.join(root, "webapp/src/main/assets/www/oc-terminal.js"), path.join(assets, "oc-terminal.js"))
await fs.writeFile(path.join(assets, "opencode-build.json"), JSON.stringify({ ...pin, platform: target }, null, 2) + "\n")
console.log(`${target} UI built from OpenCode ${pin.version} (${pin.commit})`)
