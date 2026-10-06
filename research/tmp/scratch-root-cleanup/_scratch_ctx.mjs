// UX14-P4 临时静态取证脚本（跑完删）：在压缩 bundle 里按关键词取上下文窗口。
import { readFileSync } from 'node:fs';
const file = process.argv[2];
const terms = process.argv.slice(3);
const s = readFileSync(file, 'utf8');
for (const t of terms) {
  let i = -1, n = 0;
  console.log('\n########## TERM: ' + t + ' ##########');
  while ((i = s.indexOf(t, i + 1)) !== -1 && n < 4) {
    const a = Math.max(0, i - 380), b = Math.min(s.length, i + t.length + 380);
    console.log(`--- @offset ${i} ---`);
    console.log(s.slice(a, b).replace(/\s+/g, ' '));
    n++;
  }
  if (n === 0) console.log('(0 hits)');
}
