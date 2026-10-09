// One request channel for native operations. Native replies include either a
// value or an explicit error; cancelling a system picker is a normal value.
export function createNativeRpc(native, events) {
  const pending = new Map()
  let sequence = 0
  events.addEventListener("message", event => {
    const reply = JSON.parse(event.data)
    const request = pending.get(reply.id)
    if (!request) throw new Error("Unexpected Android platform reply: " + reply.id)
    pending.delete(reply.id)
    if (reply.error) request.reject(new Error(reply.error))
    else request.resolve(reply.value)
  })
  return (method, payload = {}) => new Promise((resolve, reject) => {
    const id = String(++sequence)
    pending.set(id, { resolve, reject })
    try {
      native.postMessage(JSON.stringify({ id, method, payload }))
    } catch (error) {
      pending.delete(id)
      reject(error)
    }
  })
}
