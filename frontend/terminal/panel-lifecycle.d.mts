export function createTerminalHandoffs(): {
  wait(key: string): Promise<unknown[]> | undefined
  begin(key: string): () => void
}
