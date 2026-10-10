import { Terminal as Xterm } from "@xterm/xterm"
import { FitAddon as XtermFit } from "@xterm/addon-fit"
import { SerializeAddon } from "@xterm/addon-serialize"
import css from "@xterm/xterm/css/xterm.css"

// xterm reserves 14 CSS px for the vertical scrollbar. Keep that layout and
// hit area stable for FitAddon and touch input; only narrow the mobile thumb's
// painted surface so a high-DPR phone does not show an oversized bar.
const XTERM_SCROLLBAR_WIDTH = 14
const MOBILE_SCROLLBAR_THUMB_WIDTH = 8

const scale = () => {
  // A stored preference wins on every screen; only a missing or invalid value
  // falls back to the narrow-screen default, so tablets keep full-size glyphs.
  const fallback = window.matchMedia("(max-width: 767px)").matches ? 0.65 : 1
  try {
    const value = Number.parseFloat(localStorage.getItem("oc.settings.terminalFontScale"))
    return Number.isFinite(value) && value > 0 ? Math.min(1, Math.max(0.5, value)) : fallback
  } catch {
    return fallback
  }
}

const installStyle = () => {
  if (document.getElementById("oc-xterm-style")) return
  const style = document.createElement("style")
  style.id = "oc-xterm-style"
  style.textContent = css + `
    [data-oc-terminal-engine=xterm]{display:flex;flex-direction:column;justify-content:flex-end;min-height:0;overflow:hidden;touch-action:none;}
    .oc-terminal-frame{position:relative;flex:none;width:100%;overflow:hidden;touch-action:none;}
    .oc-terminal-frame .xterm{padding:0;touch-action:none;}
    .oc-terminal-frame .xterm-screen{touch-action:none;}
    .oc-terminal-frame .xterm-helper-textarea{font-size:16px;}
    @media (max-width: 767px) {
      .oc-terminal-frame .xterm .xterm-scrollable-element > .scrollbar.vertical > .slider {
        width: ${MOBILE_SCROLLBAR_THUMB_WIDTH}px !important;
        left: ${XTERM_SCROLLBAR_WIDTH - MOBILE_SCROLLBAR_THUMB_WIDTH}px !important;
      }
    }
  `
  document.head.appendChild(style)
}

// A blank row is still *visible* when a program painted it: ANSI background
// colour, inverse video, underline and strikethrough all render on empty
// cells, so a text-only check crops colored blocks (a selected row, a progress
// bar's empty tail, a highlighted prompt) out of the frame. Read them through
// the public buffer cell API — no renderer internals — and reuse one cell
// object, since layoutFrame() runs on every parsed write.
const lineIsPainted = (line, columns, scratch) => {
  if (!line) return false
  if (line.translateToString(true).length) return true
  // `length` is not trimmed after a resize, so it can exceed the grid.
  for (let x = 0; x < Math.min(line.length, columns); x++) {
    const cell = line.getCell(x, scratch)
    if (!cell) continue
    // 0 === default background; palette and RGB backgrounds are both painted.
    if (cell.getBgColorMode() !== 0) return true
    if (cell.isInverse() || cell.isUnderline() || cell.isStrikethrough()) return true
  }
  return false
}

// Preserve the API consumed by the bundled application; VT parsing, rendering,
// IME composition and serialization all use the pinned xterm implementation.
export class Terminal extends Xterm {
  constructor(options = {}) {
    const { ghostty, colorScheme, ...rest } = options
    const defined = Object.fromEntries(Object.entries(rest).filter(([, value]) => value !== undefined))
    super({ ...defined, cols: defined.cols || 80, rows: defined.rows || 24, fontSize: (rest.fontSize || 14) * scale(), allowProposedApi: true })
    this.baseFontSize = rest.fontSize || 14
    window.__ocTerminalTest?.instances.push(this)
    this.mobileCleanups = []
    const bufferChange = this.buffer.onBufferChange(() => this.clearResizeAnchor())
    this.mobileCleanups.push(() => bufferChange.dispose())
    this.mobileDisposed = false
    this.fitFrame = 0
    this.flingFrame = 0
    this.gesture = undefined
    this.scrollRemainder = 0
    this.suppressClickUntil = 0
    this.wasmTerm = { getMode: (mode) => this.getMode(mode) }
  }

  get onKey() {
    return (listener) => super.onKey((event) => listener({ ...event, key: event.domEvent.key }))
  }

  attachCustomKeyEventHandler(handler) {
    super.attachCustomKeyEventHandler((event) => {
      if (event.isComposing || event.keyCode === 229) return true
      return event.type !== "keydown" || !handler(event)
    })
  }

  setOption(key, value) {
    if (key === "colorScheme" || key === "ghostty") return
    if (key === "fontSize") this.baseFontSize = value
    this.options[key] = key === "fontSize" ? value * scale() : value
    this.scheduleFit()
  }

  // Persisted Ghostty positions count backward from the newest output. Keep
  // that contract while the xterm buffer itself uses absolute line numbers.
  getViewportY() {
    const buffer = this.buffer.active
    return buffer.baseY - (this.resizeAnchor?.line ?? buffer.viewportY)
  }

  scrollToLine(offset) {
    this.clearResizeAnchor()
    this.scrollToBufferLine(this.buffer.active.baseY - offset)
  }

  scrollToBottom() {
    this.clearResizeAnchor()
    this.scrollToBufferLine(this.buffer.active.baseY)
  }

  scrollToBufferLine(line) {
    // xterm 6's scrollToLine converts an absolute row to a relative pixel
    // delta using ydisp, which can disagree with the viewport after resize.
    // Normalize at the top, then seek with public scrollLines. Both operations
    // are synchronous, so the intermediate top position is never painted.
    super.scrollLines(-Number.MAX_SAFE_INTEGER)
    super.scrollLines(Math.max(0, line))
  }

  scrollLines(lines) {
    this.clearResizeAnchor()
    super.scrollLines(lines)
  }

  clearResizeAnchor() {
    cancelAnimationFrame(this.viewportFrame)
    this.resizeAnchor?.dispose()
    this.resizeAnchor = undefined
  }

  resize(cols, rows) {
    const buffer = this.buffer.active
    const readingHistory = buffer.type === "normal" && buffer.viewportY < buffer.baseY
    // xterm adjusts ydisp when rows change. A public marker follows the same
    // content through width reflow, so the reading line stays at the top.
    const anchor = this.resizeAnchor || (readingHistory
      ? this.registerMarker(buffer.viewportY - buffer.baseY - buffer.cursorY)
      : undefined)
    this.resizeAnchor = anchor
    cancelAnimationFrame(this.viewportFrame)
    super.resize(cols, rows)
    if (!anchor) return
    // xterm 6 synchronizes its pixel viewport during the next render. Calling
    // scrollToLine before that sync computes a delta against stale pixels.
    this.viewportFrame = requestAnimationFrame(() => {
      if (!anchor.isDisposed) this.scrollToBufferLine(anchor.line)
      this.clearResizeAnchor()
    })
  }

  getMode(mode) {
    const modes = this.modes
    if (mode === 1) return modes.applicationCursorKeysMode
    if (mode === 66) return modes.applicationKeypadMode
    if (mode === 2004) return modes.bracketedPasteMode
    if (mode === 1004) return modes.sendFocusMode
    if (mode === 1047 || mode === 1049) return this.buffer.active.type === "alternate"
    if (mode === 1000) return modes.mouseTrackingMode !== "none"
    return false
  }

  loadAddon(addon) {
    if (typeof addon.serialize !== "function") return super.loadAddon(addon)
    const serializer = new SerializeAddon()
    super.loadAddon(serializer)
    addon.serialize = (options) => serializer.serialize(options)
    // The application owns disposal of its serializer; xterm owns its replacement.
    addon.activate(this)
  }

  open(container) {
    installStyle()
    this.mobileContainer = container
    container.dataset.ocTerminalEngine = "xterm"
    container._ocTerminal = this
    const frame = document.createElement("div")
    frame.className = "oc-terminal-frame"
    frame.style.height = "100%"
    container.appendChild(frame)
    this.mobileFrame = frame
    super.open(frame)
    this.textarea.setAttribute("autocapitalize", "off")
    this.textarea.setAttribute("autocomplete", "off")
    this.textarea.spellcheck = false
    const listen = (target, type, callback, options) => {
      target.addEventListener(type, callback, options)
      this.mobileCleanups.push(() => target.removeEventListener(type, callback, options))
    }
    const stop = (event) => {
      event.preventDefault()
      event.stopImmediatePropagation()
    }
    const end = (event, canceled) => {
      const gesture = this.gesture
      if (!gesture || gesture.id !== event.pointerId) return
      stop(event)
      this.gesture = undefined
      if (container.hasPointerCapture(event.pointerId)) container.releasePointerCapture(event.pointerId)
      if (gesture.dragging) {
        this.suppressClickUntil = performance.now() + 500
        gesture.samples.push({ time: event.timeStamp, y: event.clientY })
        const recent = gesture.samples.filter((sample) => event.timeStamp - sample.time <= 100)
        const first = recent[0]
        if (!canceled && first && event.timeStamp > first.time) {
          this.fling((first.y - event.clientY) / (event.timeStamp - first.time))
        }
        return
      }
      if (!canceled) {
        // This executes during the trusted release gesture, allowing Android IME.
        this.textarea.focus({ preventScroll: true })
      }
    }
    listen(container, "pointerdown", (event) => {
      if (event.pointerType !== "touch" && event.pointerType !== "pen") return
      stop(event)
      this.stopFling()
      if (this.gesture || !event.isPrimary) {
        this.gesture = undefined
        return
      }
      this.scrollRemainder = 0
      this.gesture = { id: event.pointerId, startX: event.clientX, startY: event.clientY, lastY: event.clientY, dragging: false, samples: [{ time: event.timeStamp, y: event.clientY }] }
      container.setPointerCapture(event.pointerId)
    }, { capture: true, passive: false })
    listen(container, "pointermove", (event) => {
      const gesture = this.gesture
      if (!gesture || gesture.id !== event.pointerId) return
      stop(event)
      if (!gesture.dragging && Math.hypot(event.clientX - gesture.startX, event.clientY - gesture.startY) < 6) return
      gesture.dragging = true
      this.scrollPixels(gesture.lastY - event.clientY)
      gesture.lastY = event.clientY
      gesture.samples.push({ time: event.timeStamp, y: event.clientY })
      gesture.samples = gesture.samples.filter((sample) => event.timeStamp - sample.time <= 120)
    }, { capture: true, passive: false })
    listen(container, "pointerup", (event) => end(event, false), { capture: true, passive: false })
    listen(container, "pointercancel", (event) => end(event, true), { capture: true, passive: false })
    listen(container, "lostpointercapture", () => { this.gesture = undefined }, true)
    listen(container, "click", (event) => {
      if (performance.now() < this.suppressClickUntil) stop(event)
    }, true)
    listen(container, "touchstart", (event) => {
      if (event.touches.length > 1) {
        this.gesture = undefined
        this.stopFling()
      }
      // Prevent xterm's built-in touch handler and browser page panning.
      event.preventDefault()
      event.stopImmediatePropagation()
    }, { capture: true, passive: false })
    listen(container, "touchmove", stop, { capture: true, passive: false })
    listen(container, "touchend", stop, { capture: true, passive: false })
    listen(window, "resize", () => this.scheduleFit())
    listen(window, "oc:insets", () => this.scheduleFit())
    if (window.visualViewport) {
      listen(window.visualViewport, "resize", () => this.scheduleFit())
      listen(window.visualViewport, "scroll", () => this.scheduleFit())
    }
    // The grid is measured in pixels, so a webfont that lands after the first
    // fit — or a page restored from bfcache, or a tab shown again — leaves
    // stale cell metrics and a PTY that is the wrong size until something
    // else resizes. Re-measure on every signal that layout may have changed.
    const fonts = document.fonts
    if (fonts) {
      const fitAfterFonts = () => {
        if (this.mobileDisposed) return
        // fit() reads cached cell metrics. xterm does not watch FontFaceSet,
        // so trigger its public font-size option observer before fitting.
        // Both updates are synchronous; no intermediate size is painted.
        const fontSize = this.options.fontSize
        this.options.fontSize = fontSize + 0.01
        this.options.fontSize = fontSize
        this.scheduleFit()
      }
      fonts.ready?.then(fitAfterFonts, () => {})
      listen(fonts, "loadingdone", fitAfterFonts)
    }
    listen(document, "visibilitychange", () => {
      // Only when the terminal can actually be seen again; an unknown state
      // (older WebView) is treated as visible rather than ignored.
      if (document.visibilityState && document.visibilityState !== "visible") return
      this.scheduleFit()
    })
    listen(window, "pageshow", () => this.scheduleFit())
    listen(this.textarea, "focus", () => {
      // Deliberately no scrollToBottom(): tapping to open the keyboard must
      // not yank a user who is reading scrollback down to the newest line.
      // xterm keeps the viewport, and fit() only follows output when the
      // viewport was already pinned to the bottom.
      window.scrollTo(0, 0)
      this.scheduleFit()
    })
    listen(this.textarea, "blur", () => this.stopFling())
    this.mobileCleanups.push(() => this.onWriteParsedSubscription?.dispose())
    this.onWriteParsedSubscription = this.onWriteParsed(() => this.layoutFrame())
    this.layoutFrame()
  }

  cellHeight() {
    const screen = this.element?.querySelector(".xterm-screen")
    return screen?.getBoundingClientRect().height / this.rows || this.options.fontSize * this.options.lineHeight
  }

  layoutFrame() {
    if (!this.mobileFrame || !this.element) return
    const buffer = this.buffer.active
    // Anchor short shell output at the bottom without altering cursor addresses
    // or PTY dimensions. Full screens and scrollback retain the entire grid.
    let used = this.rows
    if (buffer.type === "normal" && buffer.baseY === 0) {
      used = buffer.cursorY + 1
      // One reusable cell object keeps the decoration scan allocation-free
      // (public buffer API only — no renderer internals).
      const scratch = buffer.getNullCell?.()
      for (let row = this.rows - 1; row >= used; row--) {
        if (lineIsPainted(buffer.getLine(row), this.cols, scratch)) {
          used = row + 1
          break
        }
      }
    }
    this.mobileFrame.style.height = Math.ceil(Math.min(this.rows, used) * this.cellHeight()) + "px"
  }

  scheduleFit() {
    // The SPA's first explicit fit follows snapshot write + viewport restore.
    // Font/resize observers must not change the saved grid before that point.
    if (this.mobileDisposed || !this.mobileLayoutReady || this.fitFrame) return
    this.fitFrame = requestAnimationFrame(() => {
      this.fitFrame = 0
      if (this.mobileDisposed) return
      this.mobileFit?.fit()
    })
  }

  scrollPixels(pixels) {
    this.scrollRemainder += pixels / this.cellHeight()
    const lines = Math.trunc(this.scrollRemainder)
    if (!lines) return
    const before = this.buffer.active.viewportY
    // Public buffer scrolling bypasses terminal mouse reporting entirely.
    this.scrollLines(lines)
    this.scrollRemainder -= lines
    if (before === this.buffer.active.viewportY) {
      this.scrollRemainder = 0
      this.stopFling()
    }
  }

  fling(velocity) {
    if (Math.abs(velocity) < 0.04) return
    let previous = performance.now()
    const start = previous
    const tick = (now) => {
      this.flingFrame = 0
      const elapsed = Math.min(48, now - previous)
      previous = now
      const before = this.buffer.active.viewportY
      this.scrollPixels(velocity * elapsed)
      velocity *= Math.pow(0.94, elapsed / 16)
      if (before === this.buffer.active.viewportY && Math.abs(velocity * elapsed) >= this.cellHeight()) return
      if (Math.abs(velocity) < 0.015 || now - start >= 900 || this.gesture) return
      this.flingFrame = requestAnimationFrame(tick)
    }
    this.flingFrame = requestAnimationFrame(tick)
  }

  stopFling() {
    cancelAnimationFrame(this.flingFrame)
    this.flingFrame = 0
  }

  dispose() {
    this.mobileDisposed = true
    this.clearResizeAnchor()
    this.stopFling()
    cancelAnimationFrame(this.fitFrame)
    for (const cleanup of this.mobileCleanups) cleanup()
    this.mobileCleanups = []
    if (this.mobileContainer?._ocTerminal === this) delete this.mobileContainer._ocTerminal
    super.dispose()
    this.mobileFrame?.remove()
  }
}

export class FitAddon extends XtermFit {
  activate(terminal) {
    super.activate(terminal)
    this.mobileTerminal = terminal
    terminal.mobileFit = this
  }

  proposeDimensions() {
    const frame = this.mobileTerminal?.mobileFrame
    if (!frame) return super.proposeDimensions()
    const previous = frame.style.height
    frame.style.height = "100%"
    const dimensions = super.proposeDimensions()
    frame.style.height = previous
    return dimensions
  }

  fit() {
    const terminal = this.mobileTerminal
    if (!terminal?.element || !terminal.mobileContainer?.isConnected) return
    terminal.mobileLayoutReady = true
    // A hidden / not-yet-laid-out container measures 0x0. Fitting it would ask
    // the PTY to adopt a 1x1 grid and rewrite the child shell's window size,
    // so wait until the terminal is genuinely visible again.
    const rect = terminal.mobileContainer.getBoundingClientRect?.()
    if (!rect || rect.width <= 0 || rect.height <= 0) return
    const fontSize = terminal.baseFontSize * scale()
    if (terminal.options.fontSize !== fontSize) terminal.options.fontSize = fontSize
    const buffer = terminal.buffer.active
    const follow = !terminal.resizeAnchor && buffer.viewportY >= buffer.baseY
    super.fit()
    if (follow) terminal.scrollToBottom()
    terminal.layoutFrame()
  }

  observeResize() {
    if (this.observer) return
    this.observer = new ResizeObserver(() => this.mobileTerminal?.scheduleFit())
    this.observer.observe(this.mobileTerminal.mobileContainer)
  }

  dispose() {
    this.observer?.disconnect()
    super.dispose()
  }
}

export const Ghostty = { load: async () => ({}) }
export const init = async () => ({})
