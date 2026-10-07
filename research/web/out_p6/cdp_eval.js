// CDP Runtime.evaluate helper: node cdp_eval.js <port> <exprFile>
const fs = require('fs');
const port = process.argv[2] || '9223';
const exprFile = process.argv[3];
const expr = fs.readFileSync(exprFile, 'utf8');

async function main() {
  const list = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
  const page = list.find(t => t.type === 'page' && t.webSocketDebuggerUrl);
  if (!page) { console.log('NO_PAGE'); process.exit(2); }
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  const done = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('timeout')), 30000);
    ws.onopen = () => ws.send(JSON.stringify({
      id: 1, method: 'Runtime.evaluate',
      params: { expression: expr, returnByValue: true, awaitPromise: true, allowUnsafeEvalBlockedByCSP: true }
    }));
    ws.onmessage = (ev) => {
      const m = JSON.parse(ev.data);
      if (m.id === 1) { clearTimeout(timer); resolve(m); }
    };
    ws.onerror = (e) => { clearTimeout(timer); reject(new Error('ws error')); };
  });
  try {
    const r = await done;
    const res = r.result || {};
    if (res.exceptionDetails) {
      console.log('EXCEPTION: ' + JSON.stringify(res.exceptionDetails).slice(0, 800));
    } else {
      const v = res.result && res.result.value;
      console.log(typeof v === 'string' ? v : JSON.stringify(v, null, 1));
    }
  } catch (e) {
    console.log('ERR: ' + e.message);
    process.exit(3);
  }
  process.exit(0);
}
main();
