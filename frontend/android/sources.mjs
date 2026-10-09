import fs from "node:fs/promises"
import path from "node:path"
import { execFileSync } from "node:child_process"

export async function applyAndroidSources(checkout, here) {
  execFileSync("git", ["apply", "--check", path.join(here, "upstream.patch")], { cwd: checkout, stdio: "inherit" })
  execFileSync("git", ["apply", path.join(here, "upstream.patch")], { cwd: checkout, stdio: "inherit" })
  const files = [
    ["platform.ts", "packages/app/src/runtime/platform/android.ts"],
    ["files.mjs", "packages/app/src/runtime/platform/android-files.mjs"],
    ["files.d.mts", "packages/app/src/runtime/platform/android-files.d.mts"],
    ["browser.tsx", "packages/app/src/runtime/platform/android-browser.tsx"],
    ["browser-settings.tsx", "packages/app/src/settings/general/android-browser.tsx"],
    ["mobile-browser.tsx", "packages/gui-extensions/src/browser/android-mobile.tsx"],
    ["rpc.mjs", "packages/app/src/runtime/platform/android-rpc.mjs"],
    ["rpc.d.mts", "packages/app/src/runtime/platform/android-rpc.d.mts"],
    ["../embedded-ui/server-state.mjs", "packages/app/src/runtime/platform/android-server-state.mjs"],
    ["../embedded-ui/resize-pointer.mjs", "packages/ui/src/components/android-resize-pointer.mjs"],
    ["../embedded-ui/resize-pointer.d.mts", "packages/ui/src/components/android-resize-pointer.d.mts"],
    ["../terminal/panel-lifecycle.mjs", "packages/gui-extensions/src/terminal/android-handoffs.mjs"],
    ["../terminal/panel-lifecycle.d.mts", "packages/gui-extensions/src/terminal/android-handoffs.d.mts"],
  ]
  await Promise.all(files.map(([from, to]) => fs.copyFile(path.join(here, from), path.join(checkout, to))))
  for (const name of ["app", "gui-extensions"]) {
    await fs.writeFile(path.join(checkout, `packages/${name}/tsconfig.android.json`), JSON.stringify({
      extends: "./tsconfig.json", include: ["src", "package.json"], exclude: ["src/**/*.test.ts", "src/**/*.stories.tsx"],
    }, null, 2))
  }
  // Windows Git can check symlinks out as plain text. Resolve the tracked link
  // graph explicitly so type declarations and public images remain real files.
  if (process.platform !== "win32") return
  const links = new Map(execFileSync("git", ["ls-files", "--stage", "-z"], { cwd: checkout, encoding: "utf8" })
    .split("\0").filter(row => row.startsWith("120000 ")).map(row => [row.split("\t")[1], row.split(" ")[1]]))
  const bytes = async name => {
    const hash = links.get(name)
    if (!hash) return fs.readFile(path.join(checkout, name))
    const target = execFileSync("git", ["cat-file", "blob", hash], { cwd: checkout, encoding: "utf8" }).trim()
    return bytes(path.posix.normalize(path.posix.join(path.posix.dirname(name), target)))
  }
  for (const name of links.keys()) {
    if (!name.startsWith("packages/app/")) continue
    await fs.writeFile(path.join(checkout, name), await bytes(name))
  }
}
