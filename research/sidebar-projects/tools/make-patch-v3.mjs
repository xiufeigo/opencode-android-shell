#!/usr/bin/env node
/**
 * Build the reviewable patch for the project sidebar (top toolbar, project actions,
 * long-press menus, collapsible sidebar) on top of the Android-patched baseline.
 *
 * `frontend/android/build.mjs` applies `upstream.patch` and then `sidebar.patch`, so this
 * patch must be diffed against the Android-patched source, not against the dirty build
 * checkout. The script reconstructs that baseline, layers the current checkout files, and
 * verifies that a fresh baseline accepts the patch and reproduces every file byte-for-byte.
 */
import fs from "node:fs"
import path from "node:path"
import os from "node:os"
import { execFileSync } from "node:child_process"
import { fileURLToPath } from "node:url"

const HERE = path.dirname(fileURLToPath(import.meta.url))
const REPO = path.resolve(HERE, "../../..")
const CHECKOUT = path.join(REPO, "_scratch_root/desktop-parity/checkout")
const UPSTREAM_PATCH = path.join(REPO, "frontend/android/upstream.patch")
const OUT = path.join(REPO, "frontend/android/sidebar.patch")

const MODIFIED = [
  "packages/app/src/home/projects/controller.tsx",
  "packages/app/src/home/sessions/controller.tsx",
  "packages/app/src/runtime/i18n/en.ts",
  "packages/app/src/session/timeline/message-timeline.tsx",
  "packages/app/src/shell/routes/routes.tsx",
  "packages/app/src/shell/shell.tsx",
  "packages/app/src/shell/state/layout.tsx",
  "packages/app/src/shell/titlebar/titlebar.tsx",
]
const ADDED = [
  "packages/app/src/home/projects/import-session.ts",
  "packages/app/src/home/sessions/actions.ts",
  "packages/app/src/session/delete-session-dialog.tsx",
  "packages/app/src/shell/layout/vertical.ts",
  "packages/app/src/shell/titlebar/project-session-sidebar.tsx",
  "packages/app/src/shell/titlebar/vertical-sidebar-toolbar.tsx",
]
const ALL = [...MODIFIED, ...ADDED]

const git = (cwd, args, options = {}) => execFileSync("git", args, { cwd, encoding: "utf8", ...options })

function pristine(rel) {
  return git(CHECKOUT, ["show", `HEAD:${rel}`])
}

function writeTree(root) {
  for (const rel of MODIFIED) {
    const file = path.join(root, rel)
    fs.mkdirSync(path.dirname(file), { recursive: true })
    fs.writeFileSync(file, pristine(rel))
  }
}

function initRepo(root) {
  fs.mkdirSync(root, { recursive: true })
  git(root, ["init", "--quiet"])
  git(root, ["config", "--local", "core.autocrlf", "false"])
}

function buildBaseline(root) {
  initRepo(root)
  writeTree(root)
  const includes = MODIFIED.flatMap((rel) => ["--include", rel])
  try {
    git(root, ["apply", "--whitespace=nowarn", ...includes, UPSTREAM_PATCH])
  } catch (error) {
    throw new Error(`Failed to reconstruct Android baseline: ${error.stderr || error.message}`)
  }
}

const work = fs.mkdtempSync(path.join(os.tmpdir(), "sidebar-v3-work-"))
const baseDir = path.join(work, "baseline")
buildBaseline(baseDir)

const repo = path.join(work, "repo")
fs.cpSync(baseDir, repo, { recursive: true })
initRepo(repo)
git(repo, ["add", "-A"])
git(repo, ["-c", "user.name=preview", "-c", "user.email=preview@local", "commit", "-q", "-m", "baseline"])

for (const rel of MODIFIED) {
  fs.copyFileSync(path.join(CHECKOUT, rel), path.join(repo, rel))
}
for (const rel of ADDED) {
  const file = path.join(repo, rel)
  fs.mkdirSync(path.dirname(file), { recursive: true })
  fs.copyFileSync(path.join(CHECKOUT, rel), file)
}
git(repo, ["add", "-A"])

const patch = git(repo, ["diff", "--cached", "--no-color", "--no-ext-diff"])
fs.writeFileSync(OUT, patch)

const applyRoot = path.join(work, "apply")
fs.cpSync(baseDir, applyRoot, { recursive: true })
initRepo(applyRoot)
git(applyRoot, ["apply", "--check", OUT])
git(applyRoot, ["apply", OUT])
for (const rel of ALL) {
  const expected = fs.readFileSync(path.join(CHECKOUT, rel), "utf8")
  const actual = fs.readFileSync(path.join(applyRoot, rel), "utf8")
  if (expected !== actual) throw new Error(`Applied ${rel} differs from the checkout`)
}

const stat = git(repo, ["diff", "--cached", "--numstat"]).trim()
fs.rmSync(work, { recursive: true, force: true })
console.log(JSON.stringify({ patch: OUT, bytes: patch.length, files: ALL, stat, applied: true }, null, 2))
