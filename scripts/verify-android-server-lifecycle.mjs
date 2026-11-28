#!/usr/bin/env node
// Shared localhost server lifecycle helpers for Android verification runners.

function listen(server) {
  return new Promise((resolve, reject) => {
    server.on("error", reject)
    server.listen(0, "127.0.0.1", () => resolve(server))
  })
}

function closeServer(server) {
  return new Promise((resolve) => {
    server.closeAllConnections?.()
    server.close(() => resolve())
  })
}

export { listen, closeServer }
