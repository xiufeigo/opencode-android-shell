import type { Bridge, BridgeMessage } from "@opencode/gui-extensions/sdk/bridge"
type Server = Parameters<Bridge["configure"]>[0][number]
type Native = <T = unknown>(method: string, payload?: Record<string, unknown>) => Promise<T>
export function browserTicket(server: Server, fetcher?: typeof fetch, signal?: AbortSignal): Promise<{ url: string; token: string }>
export function createBrowserTransport(input: {
  native: Native; publish(value: BridgeMessage): void; surface(id: string, bytes: Uint8Array | string): void;
  keyboard(id: string): void; status(value: string): void;
}): {
  call: Bridge["call"]; configure: Bridge["configure"]; configureLegacy(): Promise<boolean>;
  connect(serverID: string): Promise<void>;
  surface<T = unknown>(id: string, method: string, input: unknown): Promise<T>;
  stop(): void; resume(): void;
}
