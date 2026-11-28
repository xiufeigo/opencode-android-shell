#!/usr/bin/env node
// Shared mock OpenCode backend for Android verification runners. It implements
// the HTTP/SSE/WebSocket surface needed by the packaged UI without depending on
// any runner-specific report state.

import http from "node:http"
import crypto from "node:crypto"
import { listen } from "./verify-android-server-lifecycle.mjs"

/* ─────────────────────────── mock OpenCode V2 backend ─────────────────────────── */

/** Minimal RFC 6455 server-side framing (no dependencies). */
function wsAccept(key) {
  return crypto
    .createHash("sha1")
    .update(`${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11`)
    .digest("base64")
}

function wsFrame(payload, opcode = 0x1) {
  const data = Buffer.isBuffer(payload) ? payload : Buffer.from(String(payload), "utf8")
  const len = data.length
  let header
  if (len < 126) {
    header = Buffer.alloc(2)
    header[1] = len
  } else if (len < 65536) {
    header = Buffer.alloc(4)
    header[1] = 126
    header.writeUInt16BE(len, 2)
  } else {
    header = Buffer.alloc(10)
    header[1] = 127
    header.writeBigUInt64BE(BigInt(len), 2)
  }
  header[0] = 0x80 | opcode
  return Buffer.concat([header, data])
}

/**
 * The PTY's retained output, mirroring _upstream/packages/core/src/pty.ts:
 *
 *   BUFFER_LIMIT = 2MB, `buffer` holds the retained tail, `bufferCursor` is the
 *   ABSOLUTE cursor of buffer[0], `cursor` is the absolute end. `attach()`
 *   computes `from` as -1 -> end (tail only), a safe integer -> that offset,
 *   anything else -> 0, and replies with buffer.slice(from - bufferCursor).
 *
 * Without this the backend was a dumb sink: every reconnect -- and a full page
 * reload is a reconnect -- produced ZERO bytes, so "the scrollback did not come
 * back" was guaranteed by the fixture and proved nothing about the product.
 * The client advances its own resume cursor on every frame (terminal.tsx:625
 * `cursor += data.length; seek = cursor`), so this history is the only thing
 * that has to survive the reload.
 */
const PTY_BUFFER_LIMIT = 2 * 1024 * 1024

function newPtyLog() {
  return { history: "", bufferCursor: 0, cursor: 0 }
}

/**
 * A PTY connection. The test drives output in; whatever the UI sends back is
 * recorded verbatim so "input reached the WS exactly once" is a real observation.
 */
class MockPtySocket {
  constructor(id, log) {
    this.id = id
    this.log = log
    this.socket = null
    this.received = []
    // Everything the backend pushed DOWN to the UI, live frames and replay alike.
    this.sent = []
    this.closed = false
    this.replayBytes = 0
  }
  /** Appends to the PTY's retained output AND pushes it live, in that order. */
  append(text) {
    const log = this.log
    log.history += text
    log.cursor += text.length
    if (log.history.length > PTY_BUFFER_LIMIT) {
      const excess = log.history.length - PTY_BUFFER_LIMIT
      log.history = log.history.slice(excess)
      log.bufferCursor += excess
    }
  }
  write(text) {
    if (!this.socket || this.socket.destroyed) return false
    this.socket.write(wsFrame(text, 0x1))
    return true
  }
  attach(socket) {
    this.socket = socket
    socket.on("data", (buf) => this.onData(buf))
    socket.on("close", () => {
      this.closed = true
    })
    socket.on("error", () => {
      this.closed = true
    })
  }
  onData(buf) {
    let offset = 0
    while (offset + 2 <= buf.length) {
      const opcode = buf[offset] & 0x0f
      const masked = (buf[offset + 1] & 0x80) !== 0
      let len = buf[offset + 1] & 0x7f
      let cursor = offset + 2
      if (len === 126) {
        if (cursor + 2 > buf.length) return
        len = buf.readUInt16BE(cursor)
        cursor += 2
      } else if (len === 127) {
        if (cursor + 8 > buf.length) return
        len = Number(buf.readBigUInt64BE(cursor))
        cursor += 8
      }
      let mask = null
      if (masked) {
        mask = buf.subarray(cursor, cursor + 4)
        cursor += 4
      }
      const payload = Buffer.from(buf.subarray(cursor, cursor + len))
      if (mask) for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4]
      offset = cursor + len
      if (opcode === 0x9) this.socket?.write(wsFrame(payload, 0xa))
      else if (opcode === 0x8) {
        this.socket?.end()
        return
      } else this.handleFrame(opcode, payload)
    }
  }
  /** The legacy pty protocol only sends text frames; the persistent one sends framed binary. */
  handleFrame(opcode, payload) {
    if (opcode === 0x1) this.received.push(payload.toString("utf8"))
  }
  send(text) {
    if (!this.socket || this.socket.destroyed) return false
    this.append(text)
    this.sent.push(text)
    return this.write(text)
  }
  /**
   * The control frame production terminal.tsx parses for the resume cursor:
   * 0x00 + JSON {cursor}, websocket opcode 0x2 (core/src/pty/protocol.ts
   * metaFrame). Sent by the real backend right after replay with the absolute
   * end cursor, so it always reports THIS pty's absolute position.
   */
  sendCursor(cursor) {
    const value = Number.isSafeInteger(cursor) ? cursor : this.log.cursor
    const json = JSON.stringify({ cursor: value })
    const bytes = Buffer.concat([Buffer.from([0]), Buffer.from(json, "utf8")])
    this.sent.push(`\u0000${json}`)
    if (!this.socket || this.socket.destroyed) return false
    this.socket.write(wsFrame(bytes, 0x2))
    return true
  }
  /** Replays retained output from an absolute cursor (the -1 / n / 0 rules). */
  replayFrom(requested) {
    const log = this.log
    const from =
      requested === -1
        ? log.cursor
        : typeof requested === "number" && Number.isSafeInteger(requested)
          ? Math.max(0, requested)
          : 0
    let data = ""
    if (log.history && from < log.cursor) {
      const offset = Math.max(0, from - log.bufferCursor)
      if (offset < log.history.length) data = log.history.slice(offset)
    }
    this.replayBytes = data.length
    // PtyProtocol.chunks(): bounded frames, never one giant blob.
    const CHUNK = 64 * 1024
    for (let i = 0; i < data.length; i += CHUNK) {
      const piece = data.slice(i, i + CHUNK)
      this.sent.push(piece)
      this.write(piece)
    }
    return { requested, from, bytes: data.length, data }
  }
  /** How many times this backend socket pushed the given marker down to the UI. */
  markerSends(marker) {
    return this.sent.filter((t) => String(t).includes(marker)).length
  }
}

/**
 * A persistent-PTY connection (the v2.2.7 terminal protocol, read from
 * packages/client/src/solid/pty.ts and packages/gui-plugins/src/terminal/
 * connection-machine.ts):
 *   server -> client output: raw binary websocket frames,
 *   server -> client control: JSON text frames ({type:"attached"|"replay_complete"|...}),
 *   client -> server: binary frames [op:u8][cols:u16][rows:u16][data] where op 1
 *   carries input bytes and op 0 is a size/takeover frame.
 * The client fails the attach with "outdated" unless `inputProtocol` is 1.
 */
class MockPersistentPtySocket extends MockPtySocket {
  constructor(id, log, info) {
    super(id, log)
    this.info = info
    // Size-only frames carry no input text, so keep them separately from
    // `received`: without this the test cannot tell a missing client resize
    // from a missing server canonical-resize reply.
    this.sizeFrames = []
  }
  /** Terminal output goes out as a binary frame; the client routes ArrayBuffers to the screen. */
  write(text) {
    if (!this.socket || this.socket.destroyed) return false
    this.socket.write(wsFrame(Buffer.from(String(text), "utf8"), 0x2))
    return true
  }
  /** JSON control message (attached / replay_complete / resized / ...). */
  sendControl(message) {
    const text = JSON.stringify(message)
    this.sent.push(text)
    if (!this.socket || this.socket.destroyed) return false
    this.socket.write(wsFrame(text, 0x1))
    return true
  }
  handleFrame(opcode, payload) {
    if (opcode === 0x1) {
      this.received.push(payload.toString("utf8"))
      return
    }
    if (opcode !== 0x2 || payload.length < 5) return
    const cols = payload.readUInt16BE(1)
    const rows = payload.readUInt16BE(3)
    if (cols <= 0 || rows <= 0) return
    this.sizeFrames.push({ cols, rows, input: payload[0] === 1 })
    if (payload[0] === 1) this.received.push(payload.subarray(5).toString("utf8"))
    if (this.info.size.cols === cols && this.info.size.rows === rows) return
    this.info.size = { cols, rows }
    // The daemon answers a controlling attachment's resize with the canonical
    // size on every socket. The client deliberately never fits its emulator
    // locally, so omitting this control frame leaves it at the creation size
    // forever; the fixture then reports a product resize defect that the real
    // server never causes. Its checkpoint is the retained output, matching the
    // app e2e persistent-PTY mock.
    this.sendControl({
      type: "resized",
      cols,
      rows,
      checkpoint: Buffer.from(this.log.history, "utf8").toString("base64"),
    })
  }
}

function corsHeaders(req) {
  const origin = req.headers.origin || "*"
  return {
    "access-control-allow-origin": origin,
    "access-control-allow-credentials": origin === "*" ? undefined : "true",
    "access-control-allow-headers":
      req.headers["access-control-request-headers"] ||
      "Authorization, Content-Type, Accept, Last-Event-ID, x-opencode-directory, x-opencode-workspace, x-opencode-ticket",
    "access-control-allow-methods": "GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS",
    "access-control-allow-private-network": "true",
    vary: "Origin",
  }
}

function startBackend({ record = () => {} } = {}) {
  const state = {
    origin: null,
    workspace: null,
    sessionId: null,
    ptys: new Map(),
    // Persistent PTYs (v2.2.7 terminal protocol), keyed by id.
    persistentPtys: new Map(),
    sockets: new Map(),
    // Every socket ever opened per PTY: a reconnect must not erase what the
    // previous connection pushed, or "how many times did the backend send the
    // marker" silently goes DOWN across a reload and reports nonsense.
    socketLog: new Map(),
    // The PTY's retained output (buffer / bufferCursor / cursor), i.e. the real
    // server's history, per _upstream/packages/core/src/pty.ts.
    ptyLogs: new Map(),
    // One record per websocket attach: which resume cursor the client asked for
    // and how many bytes the backend replayed in reply.
    replays: [],
    // The line the history-restore check stamps into the scrollback, so a replay
    // frame can be classified as "the server re-sent it" without guessing.
    marker: null,
    sse: new Set(),
    hits: [],
    sessionImports: [],
    ptyPatches: [],
  }

  const json = (res, status, body) => {
    const text = JSON.stringify(body)
    res.writeHead(status, { "content-type": "application/json", "content-length": Buffer.byteLength(text) })
    res.end(text)
  }

  const server = http.createServer((req, res) => {
    const cors = Object.fromEntries(Object.entries(corsHeaders(req)).filter(([, v]) => v != null))
    const url = new URL(req.url || "/", "http://127.0.0.1")
    const p = url.pathname
    const method = req.method || "GET"
    state.hits.push(`${method} ${p}`)
    record({ path: p, status: 200, via: `backend${method === "GET" ? "" : ` ${method}`}` })

    if (method === "OPTIONS") {
      res.writeHead(204, cors)
      res.end()
      return
    }

    const send = (body, status = 200) => {
      const text = JSON.stringify(body)
      res.writeHead(status, { ...cors, "content-type": "application/json", "content-length": Buffer.byteLength(text) })
      res.end(text)
    }

    // --- health / info -------------------------------------------------
    if (p.startsWith("/api/android-browser/")) { send({ error: "Browser gateway is not installed in this test backend" }, 404); return }
    if (p === "/api/health" || p === "/global/health" || p === "/health") {
      send({ healthy: true, version: "verify-android-terminal", time: Date.now() })
      return
    }
    if (p === "/api/info" || p === "/global/info" || p === "/info") {
      // Schema-exact ServerInfo: version, pid, urls, paths.tmp.
      send({
        version: "0.0.0-verify",
        pid: 1,
        urls: [state.origin || "http://127.0.0.1"],
        paths: { tmp: state.workspace || "." },
      })
      return
    }

    // --- SSE event stream ----------------------------------------------
    if (p === "/event" || p === "/global/event" || p === "/api/event") {
      res.writeHead(200, {
        ...cors,
        "content-type": "text/event-stream",
        "cache-control": "no-store",
        connection: "keep-alive",
      })
      res.write(`data: ${JSON.stringify({ id: `evt_${crypto.randomUUID()}`, type: "server.connected", data: {} })}\n\n`)
      state.sse.add(res)
      req.on("close", () => state.sse.delete(res))
      return
    }

    // --- config / provider / model -------------------------------------
    if (p === "/api/rpc/xiufeigo.server-browser/state" && method === "POST") {
      send({ output: { version: 1, tabs: [], focusedTabID: null } })
      return
    }
    // config.list() feeds a store that the app calls .findLast() on, so this MUST
    // be an array. Returning an object here crashes the whole session route with
    // "o(...)?.findLast is not a function".
    if (p === "/config" || p === "/global/config" || p === "/api/config") {
      send([])
      return
    }
    if (p === "/api/config/shell") {
      send([{ path: "/bin/bash", name: "bash", acceptable: true }])
      return
    }
    if (p === "/api/provider" || p === "/provider") {
      send({ location: { directory: state.workspace }, data: [] })
      return
    }
    if (p === "/api/model" || p === "/model") {
      send({ location: { directory: state.workspace }, data: [] })
      return
    }
    if (p === "/api/agent" || p === "/agent") {
      send({ location: { directory: state.workspace }, data: [] })
      return
    }
    if (["/api/command", "/command", "/api/skill", "/skill", "/api/mcp", "/mcp", "/api/plugin", "/plugin",
      "/api/integration", "/api/shell", "/api/reference", "/api/form"].includes(p)) {
      send({ location: { directory: state.workspace }, data: [] })
    } else if (p === "/api/mcp/resource") {
      send({ location: { directory: state.workspace }, data: { resources: [], templates: [] } })
    } else if (p === "/api/vcs") {
      send({ location: { directory: state.workspace }, data: { branch: {} } })
    } else if (p === "/api/worktree" || p === "/worktree") {
      send([])
    } else if (p === "/api/worktree/refresh" && method === "POST") {
      res.writeHead(204, cors).end()
    } else if (p === "/api/permission/request" || p === "/permission/request") send([])
    else if (p === "/api/question/request" || p === "/question/request") send([])
    else if (p === "/api/vcs/status" || p === "/vcs/status") send({})
    else if (p === "/api/vcs/diff" || p === "/vcs/diff") send({ files: [] })
    else if (p === "/api/project" || p === "/project") {
      send([{ id: "mock-project", worktree: state.workspace, vcs: false, time: { created: Date.now(), initialized: Date.now() } }])
    } else if (p === "/api/project/current" || p === "/project/current") {
      send({ id: "mock-project", worktree: state.workspace, vcs: false })
    } else if (p === "/path" || p === "/api/location" || p === "/location") {
      send(locationPublicInfo(state.workspace))
    } else if (p === "/file" || p === "/api/fs/list") {
      send([])
    } else if (p === "/file/status") {
      send([])
    } else if (p.startsWith("/file/content")) {
      send({ content: "" })
    }

    // --- sessions --------------------------------------------------------
    // SessionsResponse is { data: SessionInfo[], cursor: {previous?, next?} }.
    else if (p === "/api/experimental/session/import" && method === "POST") {
      const chunks = []
      req.on("data", chunk => chunks.push(chunk))
      req.on("end", () => {
        const data = JSON.parse(Buffer.concat(chunks).toString("utf8"))
        state.sessionImports.push(data)
        send({ data: sessionInfo(state.sessionId, data.location.directory) })
      })
    } else if (p === "/api/session/active" || p === "/session/active") {
      send({ data: {} })
    } else if (p === "/api/session" || p === "/session") {
      if (method === "POST") {
        const id = state.sessionId || (state.sessionId = `ses_${crypto.randomUUID().slice(0, 12)}`)
        send({ data: sessionInfo(id, state.workspace), cursor: {} })
        return
      }
      send({ data: [sessionInfo(state.sessionId, state.workspace)], cursor: {} })
    } else if (/^\/api\/session\/[^/]+$/.test(p) || /^\/session\/[^/]+$/.test(p)) {
      const id = decodeURIComponent(p.split("/").pop())
      send({ data: sessionInfo(id, state.workspace) })
    }
    // Sub-resources under /api/session/{id}. Output envelopes are
    // { data: Array<T> } for message/inbox/context, { data: Array<FileDiffInfo> } for diff.
    // message is paged: the app reads `cursor.next`, so the envelope must carry a
    // cursor object even when there are no messages.
    else if (/message$/.test(p)) {
      send({ data: [], cursor: {} })
    } else if (/\/(inbox|context)$/.test(p)) {
      send({ data: [] })
    } else if (/todo$/.test(p)) {
      send({ data: { todos: [] } })
    } else if (/diff$/.test(p)) {
      send({ data: [] })
    } else if (/\/shell$/.test(p)) {
      send({ data: [] })
    }

    // --- PTY -------------------------------------------------------------
    // PtyCreateOutput / PtyGetOutput / PtyUpdateOutput are { location, data: Pty }.
    else if (p === "/api/pty" || p === "/pty") {
      if (method === "POST") {
        const id = `pty_${crypto.randomUUID().slice(0, 10)}`
        state.ptys.set(id, ptyInfo(id, { cwd: state.workspace }))
        send({ location: { directory: state.workspace }, data: state.ptys.get(id) })
        return
      }
      send({ data: [...state.ptys.values()] })
    } else if (/^\/api\/pty\/[^/]+$/.test(p) || /^\/pty\/[^/]+$/.test(p)) {
      const id = decodeURIComponent(p.split("/").pop())
      const existing = state.ptys.get(id)
      const pty = existing || ptyInfo(id, { cwd: state.workspace })
      state.ptys.set(id, pty)
      if (method === "PATCH" || method === "PUT") {
        const chunks = []
        req.on("data", (c) => chunks.push(c))
        req.on("end", () => {
          const raw = Buffer.concat(chunks).toString("utf8")
          let body = {}
          try {
            body = JSON.parse(raw || "{}")
          } catch {}
          /* FINAL: record what the client ACTUALLY persists for a PTY.
           * The packaged terminal hands its serialized scrollback to
           * onCleanup({id, buffer, cursor, rows, cols, scrollY}) -> model.update(),
           * and the terminal reads `pty.buffer` back at mount. Whether that
           * payload ever reaches the server is exactly the question the previous
           * run guessed wrong, so every PATCH body is captured verbatim and the
           * report states what was sent rather than assuming.
           */
          state.ptyPatches.push({
            ptyId: id,
            bytes: raw.length,
            keys: Object.keys(body || {}),
            hasBuffer: typeof body?.buffer === "string" && body.buffer.length > 0,
            bufferBytes: typeof body?.buffer === "string" ? body.buffer.length : 0,
            raw,
          })
          // If the client really does push a serialized buffer up, honour the
          // real save/read contract: keep it on the pty so a later GET (i.e. a
          // real page refresh) hands it back instead of pretending it never
          // existed.
          if (typeof body?.buffer === "string") pty.buffer = body.buffer
          if (Number.isSafeInteger(body?.cursor)) pty.cursor = body.cursor
          if (Number.isSafeInteger(body?.scrollY)) pty.scrollY = body.scrollY
          const size = body.size || body.title?.size
          if (size?.cols && size?.rows) {
            pty.cols = size.cols
            pty.rows = size.rows
          }
          const title = body.title
          if (typeof title === "string") pty.title = title
          send({ location: { directory: state.workspace }, data: pty })
        })
        return
      }
      if (method === "DELETE") {
        state.ptys.delete(id)
        res.writeHead(204, cors)
        res.end()
        return
      }
      send({ location: { directory: state.workspace }, data: pty })
    }

    // --- persistent PTY (the v2.2.7 terminal protocol) -----------------------
    // Paths and envelopes mirror packages/client/src/promise/generated/client.ts
    // persistentPty.*: list/create return { data }, remove/shutdown are 204-empty,
    // handoff is the raw { handoff } body. Without these the panel's create call
    // falls into the permissive tail, decodes [] instead of an info object, and
    // the terminal strip never opens.
    else if (/^\/api\/experimental\/session\/[^/]+\/terminal\/read$/.test(p)) {
      send({ data: null })
    } else if (/^\/api\/experimental\/session\/[^/]+\/terminal$/.test(p)) {
      const sessionID = decodeURIComponent(p.split("/")[4])
      if (method === "POST") {
        bodyJson(req, (body) => {
          const id = `pty_${crypto.randomUUID().slice(0, 10)}`
          state.ptyLogs.set(id, newPtyLog())
          const patch = { cwd: typeof body.cwd === "string" ? body.cwd : state.workspace }
          if (typeof body.title === "string") patch.title = body.title
          if (typeof body.command === "string") patch.command = body.command
          if (Array.isArray(body.args)) patch.args = body.args
          if (body.size?.cols > 0 && body.size?.rows > 0) patch.size = { cols: body.size.cols, rows: body.size.rows }
          const info = persistentPtyInfo(id, sessionID, patch)
          state.persistentPtys.set(id, info)
          sseEmit(state, { type: "persistent-pty.added", data: { sessionID, terminal: info } })
          send({ data: info })
        })
        return
      }
      send({ data: [...state.persistentPtys.values()].filter((pty) => pty.sessionID === sessionID) })
    } else if (p === "/api/experimental/persistent-pty/shutdown" && method === "POST") {
      res.writeHead(204, cors)
      res.end()
    } else if (p === "/api/experimental/persistent-pty/handoff" && method === "POST") {
      send({ handoff: null })
    } else if (/^\/api\/experimental\/persistent-pty\/[^/]+\/connect-token$/.test(p) && method === "POST") {
      send({ data: { ticket: crypto.randomUUID(), expires_in: 60 } })
    } else if (/^\/api\/experimental\/persistent-pty\/[^/]+\/snapshot$/.test(p)) {
      const id = decodeURIComponent(p.split("/")[4])
      const log = state.ptyLogs.get(id) || newPtyLog()
      state.ptyLogs.set(id, log)
      const info = state.persistentPtys.get(id) || persistentPtyInfo(id, state.sessionId, { cwd: state.workspace })
      info.output = { head: log.bufferCursor, tail: log.cursor }
      state.persistentPtys.set(id, info)
      send({ data: { info, text: log.history, checkpoint: "", cursor: { x: 0, y: 0 } } })
    } else if (/^\/api\/experimental\/persistent-pty\/[^/]+$/.test(p)) {
      const id = decodeURIComponent(p.split("/").pop())
      const info = state.persistentPtys.get(id)
      if (!info) {
        send({ error: `persistent pty ${id} not found` }, 404)
        return
      }
      if (method === "DELETE") {
        state.persistentPtys.delete(id)
        sseEmit(state, { type: "persistent-pty.removed", data: { sessionID: info.sessionID, ptyID: id } })
        res.writeHead(204, cors)
        res.end()
        return
      }
      if (method === "PUT" || method === "PATCH") {
        bodyJson(req, (body) => {
          if (typeof body.title === "string") info.title = body.title
          if (body.size?.cols > 0 && body.size?.rows > 0) info.size = { cols: body.size.cols, rows: body.size.rows }
          sseEmit(state, { type: "persistent-pty.updated", data: { sessionID: info.sessionID, terminal: info } })
          send({ data: info })
        })
        return
      }
      send({ data: info })
    }

    // Permissive tail: the UI treats most sync endpoints as optional, so an
    // empty-but-valid { data: [] } envelope keeps the shell alive instead of
    // hard-failing. A bare {} trips the generated schema decoder and drops the
    // whole session route into its error boundary.
    else send({ data: [] })
  })

  server.on("upgrade", (req, socket, head) => {
    const url = new URL(req.url || "/", "http://127.0.0.1")
    const experimental = /^\/api\/experimental\/persistent-pty\/([^/]+)\/connect$/.exec(url.pathname)
    const match = experimental || /^\/(?:api\/)?pty\/([^/]+)\/connect$/.exec(url.pathname)
    if (!match) {
      socket.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n")
      socket.destroy()
      return
    }
    const key = req.headers["sec-websocket-key"]
    if (!key) {
      socket.write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n")
      socket.destroy()
      return
    }
    socket.write(
      [
        "HTTP/1.1 101 Switching Protocols",
        "Upgrade: websocket",
        "Connection: Upgrade",
        `Sec-WebSocket-Accept: ${wsAccept(key)}`,
        "",
        "",
      ].join("\r\n"),
    )
    const id = match[1]
    if (experimental) {
      const info = state.persistentPtys.get(id) || persistentPtyInfo(id, state.sessionId, { cwd: state.workspace })
      state.persistentPtys.set(id, info)
      const log = state.ptyLogs.get(id) || newPtyLog()
      state.ptyLogs.set(id, log)
      const sock = new MockPersistentPtySocket(id, log, info)
      state.sockets.set(id, sock)
      if (!state.socketLog.has(id)) state.socketLog.set(id, [])
      state.socketLog.get(id).push(sock)
      record({ path: url.pathname, status: 101, via: "backend:persistent-pty-ws" })
      sock.attach(socket)
      // attached must arrive before output so the state machine leaves "restoring";
      // inputProtocol 1 is the only version the client accepts.
      sock.sendControl({ type: "attached", inputProtocol: 1, info: { size: info.size }, role: "controller" })
      const requested = Number(url.searchParams.get("cursor"))
      const replay = sock.replayFrom(Number.isSafeInteger(requested) ? requested : 0)
      sock.sendControl({ type: "replay_complete" })
      state.replays.push({
        ptyId: id,
        protocol: "persistent",
        requestedCursor: Number.isSafeInteger(requested) ? requested : null,
        from: replay.from,
        replayBytes: replay.bytes,
        endCursor: log.cursor,
      })
      if (head && head.length) socket.unshift(head)
      return
    }
    /* Cursor protocol, read from the real client and server:
     *   client  packages/app/src/session/terminal/terminal.tsx:225
     *            `seek = start !== undefined ? start : restore ? -1 : 0`
     *   server  packages/server/src/handlers/pty.ts:160-165 parses ?cursor and
     *            accepts any safe integer >= -1, then hands it to Pty.attach.
     * The old fixture ignored the parameter entirely and pushed nothing, so a
     * reconnect could never restore anything -- a fixture artefact dressed up
     * as a product defect.
     */
    const rawCursor = url.searchParams.get("cursor")
    const parsed = rawCursor === null ? undefined : Number(rawCursor)
    const requested =
      parsed !== undefined && Number.isSafeInteger(parsed) && parsed >= -1 ? parsed : undefined

    const log = state.ptyLogs.get(id) || newPtyLog()
    state.ptyLogs.set(id, log)
    // Keep whatever metadata the panel PATCHed (title/size); never clobber it
    // with a fresh object just because a socket reconnected.
    const existing = state.ptys.get(id)
    const pty = existing || ptyInfo(id, { cwd: state.workspace })
    state.ptys.set(id, pty)

    const sock = new MockPtySocket(id, log)
    state.sockets.set(id, sock)
    if (!state.socketLog.has(id)) state.socketLog.set(id, [])
    state.socketLog.get(id).push(sock)
    record({ path: url.pathname, status: 101, via: "backend:pty-ws" })
    sock.attach(socket)

    const replay = sock.replayFrom(requested)
    // metaFrame(attachment.cursor) -- the absolute end cursor, sent right
    // after the replay exactly like handlers/pty.ts:198-199.
    sock.sendCursor(log.cursor)
    state.replays.push({
      ptyId: id,
      requestedCursor: requested ?? null,
      from: replay.from,
      replayBytes: replay.bytes,
      endCursor: log.cursor,
      hasMarkerInReplay: replay.data.includes(state.marker || "\u0000no-marker-set\u0000"),
    })
    if (head && head.length) socket.unshift(head)
  })

  return listen(server).then(() => {
    state.server = server
    state.origin = `http://127.0.0.1:${server.address().port}`
    state.sessionId = `ses_${crypto.randomUUID().slice(0, 12)}`
    return state
  })
}

function sessionInfo(id, directory) {
  // Schema-exact against packages/client/src/promise/generated/types.ts SessionInfo:
  // id, projectID, cost, tokens, time{created,updated}, location{directory} are required.
  const now = Date.now()
  return {
    id,
    projectID: "mock-project",
    cost: 0,
    tokens: { input: 0, output: 0, reasoning: 0, cache: { read: 0, write: 0 } },
    time: { created: now, updated: now },
    title: "verify-android-terminal",
    location: { directory },
  }
}

function locationPublicInfo(directory) {
  return { directory, project: { id: "mock-project", directory, canonical: directory } }
}

function ptyInfo(id, patch = {}) {
  return {
    id,
    title: "mock",
    command: "/bin/sh",
    args: [],
    cwd: "",
    status: "running",
    pid: 4242,
    ...patch,
  }
}

// Schema-exact against generated PersistentPtyInfo: adds sessionID, userTitle,
// foregroundProcess, size and output cursors to the Pty.Info fields.
function persistentPtyInfo(id, sessionID, patch = {}) {
  return {
    id,
    title: "Terminal 1",
    command: "/bin/sh",
    args: [],
    cwd: "",
    status: "running",
    pid: 4242,
    sessionID,
    userTitle: null,
    foregroundProcess: null,
    size: { cols: 80, rows: 24 },
    output: { head: 0, tail: 0 },
    ...patch,
  }
}

/** Broadcasts a {id, created, type, data} event envelope to every SSE subscriber. */
function sseEmit(state, event) {
  const line = `data: ${JSON.stringify({ id: `evt_${crypto.randomUUID()}`, created: Date.now(), ...event })}\n\n`
  for (const res of state.sse) res.write(line)
}

/** Reads a JSON request body once; always calls back with an object. */
function bodyJson(req, cb) {
  const chunks = []
  req.on("data", (c) => chunks.push(c))
  req.on("end", () => {
    let body = {}
    try {
      body = JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}")
    } catch {}
    cb(body && typeof body === "object" ? body : {})
  })
}

export { startBackend }
