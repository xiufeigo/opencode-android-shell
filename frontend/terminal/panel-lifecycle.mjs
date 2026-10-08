import fs from "node:fs/promises"
import { fileURLToPath } from "node:url"

// The vendored SPA captures its restore state before the previous responsive
// panel finishes cleanup. Keep a single handoff barrier per PTY, not a second
// history cache. Resolve only after its existing store has the flushed snapshot.
// These exact anchors deliberately fail on upstream changes that need review.
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

const panel = fileURLToPath(new URL("../../webapp/src/main/assets/www/ui/oa/panel-3c8jLlNq.js", import.meta.url))
const restore = "x=d.pty.buffer??``,C={cols:d.pty.cols,rows:d.pty.rows},w=x&&C.cols!==void 0&&Number.isSafeInteger(C.cols)&&C.cols>0&&C.rows!==void 0&&Number.isSafeInteger(C.rows)&&C.rows>0?{cols:C.cols,rows:C.rows}:void 0,T=d.pty.scrollY"
const cursor = "V=d.pty.cursor!==void 0&&Number.isSafeInteger(d.pty.cursor)?d.pty.cursor:void 0,H=V??0,U=V===void 0?x?-1:0:V"
const capture = `${restore.replace(",C=", ";C=").replace(",w=", ";w=").replace(",T=", ";T=")};${cursor.replace(",H=", ";H=").replace(",U=", ";U=")};`
const edits = [
  [restore, `x,C,w,T,ocCapture=()=>{${capture}}`],
  [cursor, "V,H,U"],
  ["let n=await d.ghostty();if(R)return;", `let n=await d.ghostty();await ocTerminalHandoffs.wait(g.url+"\\0"+v+"\\0"+b);if(R)return;ocCapture();`],
  ["get pty(){return t.pty}", "get pty(){return t.ops.all().find(e=>e.id===t.pty.id)??t.pty}"],
  ["x&&w?(await D(x),Y(),u.fit(),Q(s.cols,s.rows),T!==void 0&&s.scrollToLine(T),C())", "x&&w?(await D(x),Y(),T!==void 0&&s.scrollToLine(T),u.fit(),Q(s.cols,s.rows),C())"],
  ["typeof document<`u`&&document.fonts&&document.fonts.ready.then(Z);let m=s.onResize", "let m=s.onResize"],
  ["let C=()=>{u.observeResize()", "let C=()=>{typeof document<`u`&&document.fonts&&document.fonts.ready.then(Z);u.observeResize()"],
  ["u(()=>{R=!0,P!==void 0", "u(()=>{const ocComplete=ocTerminalHandoffs.begin(g.url+\"\\0\"+v+\"\\0\"+b);R=!0,P!==void 0"],
  ["Ae({term:k,addon:j,cursor:H,id:b,onCleanup:e.onCleanup}),ie()", "Ae({term:k,addon:j,cursor:H,id:b,onCleanup:e.onCleanup}),ie(),ocComplete()"],
]
const prefix = `const ocTerminalHandoffs=(${createTerminalHandoffs.toString()})();`

export async function patchPanelLifecycle() {
  let source = await fs.readFile(panel, "utf8")
  if (source.startsWith(prefix)) {
    for (const [, after] of edits) {
      if (!source.includes(after)) throw new Error("Incomplete terminal lifecycle patch: " + after.slice(0, 80))
    }
    return
  }
  for (const [before, after] of edits) {
    if (source.split(before).length !== 2) throw new Error("Terminal lifecycle anchor changed: " + before.slice(0, 80))
    source = source.replace(before, after)
  }
  await fs.writeFile(panel, prefix + source)
}
