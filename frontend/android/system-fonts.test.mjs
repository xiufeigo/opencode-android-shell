import assert from "node:assert/strict"
import { test } from "node:test"
import { createSystemFonts } from "./system-fonts.mjs"

const id = `ocsys-${"a".repeat(64)}`

test("registers each system font once and loads only the chosen face", async () => {
  const added = []
  let requests = 0
  class Face {
    constructor(...args) { this.args = args; this.loads = 0 }
    async load() { this.loads++; return this }
  }
  const fonts = createSystemFonts(async () => { requests++; return [{ id, name: "MiSansVF.ttf" }] }, {
    add: face => added.push(face),
  }, Face)
  assert.equal(requests, 0)
  assert.deepEqual(await fonts.list(), [{ id, name: "MiSansVF.ttf" }])
  await fonts.list()
  assert.equal(requests, 1)
  assert.equal(added.length, 1)
  assert.deepEqual(added[0].args, [id, `url("/oc-system-font/${id}")`, { weight: "100 900" }])
  assert.equal(added[0].loads, 0)
  await fonts.load(id)
  assert.equal(added[0].loads, 1)
  await assert.rejects(fonts.load(`ocsys-${"b".repeat(64)}`), /unavailable/)
})

test("rejects invalid or duplicate font identifiers", async () => {
  class Face { async load() {} }
  for (const entries of [[{ id: "../secret", name: "bad" }], [{ id, name: "one" }, { id, name: "two" }]]) {
    const added = []
    const fonts = createSystemFonts(async () => entries, { add: face => added.push(face) }, Face)
    await assert.rejects(fonts.list(), /Invalid system font entry/)
    assert.equal(added.length, 0)
  }
})

test("reports native enumeration and font loading failures", async () => {
  class FailedFace { async load() { throw new Error("font rejected") } }
  const fonts = createSystemFonts(async () => [{ id, name: "broken.ttf" }], { add() {} }, FailedFace)
  await assert.rejects(fonts.load(id), /font rejected/)
  const unavailable = createSystemFonts(async () => { throw new Error("system fonts unavailable") }, { add() {} }, FailedFace)
  await assert.rejects(unavailable.list(), /system fonts unavailable/)
})
