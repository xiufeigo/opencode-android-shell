export class DevicePage {
  constructor(socket, waitFor) {
    this.waitFor = waitFor
    this.socket = socket
    this.pending = new Map()
    this.events = new Map()
    this.nextId = 0
    this.socket.addEventListener('message', event => {
      const message = JSON.parse(event.data)
      if (message.id) {
        const pending = this.pending.get(message.id)
        if (!pending) return
        clearTimeout(pending.timer)
        this.pending.delete(message.id)
        if (message.error) pending.reject(new Error(`${pending.method}: ${message.error.message}`))
        else pending.resolve(message.result)
        return
      }
      for (const handler of this.events.get(message.method) || []) handler(message.params)
    })
    this.socket.addEventListener('close', () => {
      for (const pending of this.pending.values()) { clearTimeout(pending.timer); pending.reject(new Error('Actual WebView CDP socket closed')) }
      this.pending.clear()
    })
  }
  send(method, params = {}) {
    const id = ++this.nextId
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error(`CDP timeout: ${method}`)) }, 20000)
      this.pending.set(id, { resolve, reject, timer, method })
      this.socket.send(JSON.stringify({ id, method, params }))
    })
  }
  on(name, handler) {
    const method = name === 'pageerror' ? 'Runtime.exceptionThrown' : name === 'console' ? 'Runtime.consoleAPICalled' : name
    const fn = name === 'pageerror' ? params => handler(new Error(JSON.stringify(params.exceptionDetails))) : name === 'console' ? params => handler({ type: () => params.type, text: () => (params.args || []).map(v => v.value ?? v.description ?? '').join(' ') }) : handler
    const handlers = this.events.get(method) || []
    handlers.push(fn)
    this.events.set(method, handlers)
  }
  async evaluate(expression) {
    const value = typeof expression === 'function' ? `(${expression.toString()})()` : expression
    const result = await this.send('Runtime.evaluate', { expression: value, awaitPromise: true, returnByValue: true })
    if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails))
    return result.result?.value
  }
  async goto(url) {
    const result = await this.send('Page.navigate', { url })
    if (result.errorText) throw new Error(result.errorText)
    await this.waitFor(async () => {
      try { return await this.evaluate(`location.href === ${JSON.stringify(url)} && document.readyState !== 'loading'`) }
      catch { return false }
    }, 30000)
  }
  locator() { return { innerText: () => this.evaluate(() => document.body.innerText) } }
  async detach() { this.socket.close() }
}
