import assert from "node:assert/strict"
import test from "node:test"
import { prepareEmbeddedServerStorage } from "./server-state.mjs"

const origin = "http://127.0.0.1:4173"
const key = "opencode.global.dat:server"
function storage(entries = []) {
  const values = new Map(entries)
  const writes = []
  return { values, writes, getItem: key => values.get(key) ?? null, setItem: (key, value) => { writes.push(key); values.set(key, value) }, removeItem: key => { writes.push(key); values.delete(key) } }
}

test("first launch and an already migrated registry require no writes", () => {
  const empty = storage()
  prepareEmbeddedServerStorage(empty, origin)
  assert.deepEqual(empty.writes, [])
  const saved = storage([[key, JSON.stringify({ list: [{ type: "http", http: { url: "http://server:4096" } }], hidden: {} })]])
  prepareEmbeddedServerStorage(saved, origin)
  assert.deepEqual(saved.writes, [])
})

test("upgrade removes only the asset placeholder and keeps two servers, authentication and projects", () => {
  const servers = [{ type: "http", http: { url: "http://remote:4173", username: "test", password: "fixture" }, displayName: "First" }, { type: "http", http: { url: "https://two" }, displayName: "Second" }]
  const state = { list: [origin, { url: origin + "/" }, { type: "http", http: { url: origin } }, ...servers], hidden: { [origin]: true, "https://two": true }, projects: { "http://remote:4173": [{ worktree: "/project" }] }, lastProject: { "http://remote:4173": "/project" } }
  const saved = storage([[key, JSON.stringify(state)], ["opencode.settings.dat:defaultServerUrl", origin]])
  prepareEmbeddedServerStorage(saved, origin)
  assert.deepEqual(JSON.parse(saved.getItem(key)), { ...state, list: servers, hidden: { "https://two": true } })
  assert.equal(saved.getItem("opencode.settings.dat:defaultServerUrl"), null)
  const writes = saved.writes.length
  prepareEmbeddedServerStorage(saved, origin)
  assert.equal(saved.writes.length, writes)
})

test("legacy state is migrated and real defaults are retained; malformed state fails explicitly", () => {
  const saved = storage([["opencode.global.dat:server.v3", JSON.stringify({ list: [], hidden: { [origin]: true } })], ["opencode.settings.dat:defaultServerUrl", "http://server:4096"]])
  prepareEmbeddedServerStorage(saved, origin)
  assert.deepEqual(JSON.parse(saved.getItem("opencode.global.dat:server.v3")), { list: [], hidden: {} })
  assert.equal(saved.getItem("opencode.settings.dat:defaultServerUrl"), "http://server:4096")
  assert.throws(() => prepareEmbeddedServerStorage(storage([[key, "{"]]), origin), SyntaxError)
  assert.throws(() => prepareEmbeddedServerStorage(storage([[key, "{}"]]), origin), /Invalid server registry/)
})


test("a malformed second registry fails before either registry or its default is changed", () => {
  const state = JSON.stringify({ list: [origin], hidden: { [origin]: true } })
  for (const malformed of ["{", "null", JSON.stringify({ list: [null] })]) {
    const saved = storage([[key, state], ["opencode.global.dat:server.v3", malformed], ["opencode.settings.dat:defaultServerUrl", origin]])
    assert.throws(() => prepareEmbeddedServerStorage(saved, origin))
    assert.deepEqual(saved.writes, [])
    assert.equal(saved.getItem(key), state)
    assert.equal(saved.getItem("opencode.settings.dat:defaultServerUrl"), origin)
  }
})
