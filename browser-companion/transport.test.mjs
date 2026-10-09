import { test } from "node:test"
import assert from "node:assert/strict"
import { authenticate, endpoint, frame, PROTOCOL } from "./transport.mjs"

test("pairing requires exactly one authenticated protocol", () => {
  assert.equal(authenticate(`${PROTOCOL}, secret`, "secret"), true)
  for (const input of [undefined, PROTOCOL, `${PROTOCOL}, wrong`, `${PROTOCOL}, secret, other`])
    assert.equal(authenticate(input, "secret"), false)
})
test("remote endpoints never grant local file access, and credentials become Basic headers", () => {
  const value = endpoint({ id: "server", url: "http://127.0.0.1:4096", password: "password" })
  assert.equal(value.local, false)
  assert.equal(value.headers.authorization, `Basic ${Buffer.from("opencode:password").toString("base64")}`)
  for (const url of ["file:///etc/passwd", "http://user:pass@localhost", "http://localhost/?token=secret", "http://localhost/#secret"])
    assert.throws(() => endpoint({ id: "server", url }))
  for (const credentials of [{ username: 123, password: "password" }, { username: "bad:user", password: "password" }, { password: 123 }])
    assert.throws(() => endpoint({ id: "server", url: "http://localhost", ...credentials }))
})
test("frame header preserves embed ownership and JPEG bytes", () => {
  const jpeg = Buffer.from([255, 216, 1, 255, 217])
  const bytes = frame("embed-one", jpeg)
  const length = bytes.readUInt16BE()
  assert.deepEqual(JSON.parse(bytes.subarray(2, length + 2)), { id: "embed-one" })
  assert.deepEqual(bytes.subarray(length + 2), jpeg)
})
