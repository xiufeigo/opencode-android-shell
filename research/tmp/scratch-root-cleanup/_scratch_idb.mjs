// UX14-P4 临时 CDP 客户端（跑完删）。Node 24 自带 WebSocket，无需依赖。
// 用法: node _scratch_idb.mjs <wsUrl> <file-with-js-expression>
import { readFileSync } from 'node:fs';

const wsUrl = process.argv[2];
const expr = readFileSync(process.argv[3], 'utf8');

const ws = new WebSocket(wsUrl);
const timer = setTimeout(() => { console.error('TIMEOUT'); process.exit(2); }, 30000);

ws.onopen = () => {
  ws.send(JSON.stringify({
    id: 1,
    method: 'Runtime.evaluate',
    params: {
      expression: expr,
      awaitPromise: true,      // 异步表达式直接等结果（IndexedDB/缓存都是 promise）
      returnByValue: true,
      userGesture: true,
      allowUnsafeEvalBlockedByCSP: true,
    },
  }));
};

ws.onmessage = (ev) => {
  const m = JSON.parse(ev.data);
  if (m.id !== 1) return;
  clearTimeout(timer);
  if (m.result?.exceptionDetails) {
    console.log('EXCEPTION: ' + JSON.stringify(m.result.exceptionDetails).slice(0, 900));
  } else {
    const v = m.result?.result?.value;
    console.log(typeof v === 'string' ? v : JSON.stringify(v, null, 1));
  }
  ws.close();
  process.exit(0);
};

ws.onerror = (e) => { clearTimeout(timer); console.error('WS_ERR ' + e.message); process.exit(1); };
