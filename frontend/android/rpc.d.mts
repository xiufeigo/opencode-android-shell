export function createNativeRpc(native: { postMessage(message: string): void }, events: EventTarget):
  <T = unknown>(method: string, payload?: Record<string, unknown>) => Promise<T>
