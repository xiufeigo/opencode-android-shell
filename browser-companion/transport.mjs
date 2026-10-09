import { timingSafeEqual } from "node:crypto"

export const PROTOCOL = "opencode-browser-v1"
export function authenticate(header, token) {
  const protocols = (header ?? "").split(",").map(value => value.trim())
  if (protocols[0] !== PROTOCOL || protocols.length !== 2) return false
  const actual = Buffer.from(protocols[1])
  const expected = Buffer.from(token)
  return actual.length === expected.length && timingSafeEqual(actual, expected)
}
export function endpoint(value) {
  if (!value || typeof value.id !== "string" || !value.id || value.id.length > 16_384) throw new Error("Invalid server id")
  if (typeof value.url !== "string") throw new Error("Invalid OpenCode server URL")
  const url = new URL(value.url)
  if (!["http:", "https:"].includes(url.protocol) || url.username || url.password || url.hash || url.search)
    throw new Error("Invalid OpenCode server URL")
  const username = value.username ?? (value.password ? "opencode" : undefined)
  if (username !== undefined && (typeof username !== "string" || !username || /[:\r\n\0]/.test(username)))
    throw new Error("Invalid server username")
  if (value.password !== undefined && typeof value.password !== "string") throw new Error("Invalid server password")
  if (username && typeof value.password !== "string") throw new Error("Missing server password")
  return { id: value.id, url: url.href.replace(/\/$/, ""), username, password: value.password, local: false,
    headers: username ? { authorization: `Basic ${Buffer.from(`${username}:${value.password}`).toString("base64")}` } : {} }
}
export function frame(id, jpeg) {
  const header = Buffer.from(JSON.stringify({ id }))
  const size = Buffer.alloc(2)
  size.writeUInt16BE(header.length)
  return Buffer.concat([size, header, jpeg])
}
