import fs from "node:fs/promises"
import path from "node:path"

const root = path.resolve(import.meta.dirname, "..")
const output = path.join(root, "dist/stage")
const pkg = JSON.parse(await fs.readFile(path.join(root, "plugins/server-browser/package.json"), "utf8"))
const gradle = await fs.readFile(path.join(root, "webapp/build.gradle.kts"), "utf8")
const version = gradle.match(/versionName = "([^"]+)"/)[1]
const plugin = path.join(output, `opencode-server-browser-${pkg.version}`)
const web = path.join(output, `opencode-web-${version}`)
// Only replace this script's generated staging trees, never source directories.
for (const target of [plugin, web]) {
  if (path.dirname(target) !== output) throw new Error("Distribution target escaped its staging directory")
  await fs.rm(target, { recursive: true, force: true })
  await fs.mkdir(target, { recursive: true })
}
for (const name of ["dist", "package.json", "package-lock.json", "server.js", "install.mjs", "README.md"]) {
  await fs.cp(path.join(root, "plugins/server-browser", name), path.join(plugin, name), { recursive: true })
}
for (const name of ["dist", "serve.mjs", "README.md"]) {
  await fs.cp(path.join(root, "frontend/web", name), path.join(web, name), { recursive: true })
}
await fs.copyFile(path.join(root, "LICENSE"), path.join(plugin, "LICENSE"))
await fs.copyFile(path.join(root, "LICENSE"), path.join(web, "LICENSE"))
console.log(`Staged ${path.basename(plugin)} and ${path.basename(web)}`)
