// The vendored SPA captures its restore state before the previous responsive
// panel finishes cleanup. Keep a single handoff barrier per PTY, not a second
// history cache. Resolve only after its existing store has the flushed snapshot.
export function createTerminalHandoffs() {
  const pending = new Map()
  return {
    wait(key) { return pending.get(key) },
    begin(key) {
      let complete
      const current = Promise.all([pending.get(key), new Promise((resolve) => { complete = resolve })])
      pending.set(key, current)
      return () => {
        complete()
        current.then(() => {
          if (pending.get(key) === current) pending.delete(key)
        })
      }
    },
  }
}
