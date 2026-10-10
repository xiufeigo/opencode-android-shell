import type { Action } from "./rpc"

type Job = { action: Action; waiters: { resolve(value: unknown): void; reject(error: unknown): void }[] }

// Serialize input on the client as well as the server: concurrent HTTP calls
// need not arrive in pointer/key order. Only unsent motion may be coalesced.
export function createInputQueue(send: (action: Action) => Promise<unknown>) {
  const pending: Job[] = []
  let running = false
  const drain = async () => {
    if (running) return
    running = true
    while (pending.length) {
      const job = pending.shift()!
      try { const value = await send(job.action); for (const waiter of job.waiters) waiter.resolve(value) }
      catch (error) { for (const waiter of job.waiters) waiter.reject(error) }
    }
    running = false
  }
  return (action: Action): Promise<unknown> => new Promise((resolve, reject) => {
    const previous = pending.at(-1)
    const before = previous?.action
    const sameTab = before && "tabID" in before && "tabID" in action && before.tabID === action.tabID
    if (previous && sameTab && action.type === "pointer" && action.phase === "move" && before?.type === "pointer" && before.phase === "move") {
      previous.action = action
      previous.waiters.push({ resolve, reject })
    } else if (previous && sameTab && action.type === "scroll" && before?.type === "scroll") {
      previous.action = { ...action, deltaX: (before.deltaX ?? 0) + (action.deltaX ?? 0), deltaY: before.deltaY + action.deltaY }
      previous.waiters.push({ resolve, reject })
    } else pending.push({ action, waiters: [{ resolve, reject }] })
    void drain()
  })
}
