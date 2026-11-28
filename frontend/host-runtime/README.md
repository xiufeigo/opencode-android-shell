# Android host runtime sources

`index.html`、`oc-perf.js`、`oc-preflight.js` 以及 document-start facade 的权威源码在本目录。复制文件与 facade 拼接顺序的唯一权威清单是 `assembly.json`；`build.mjs` 与架构检查都读取它。`webapp/src/main/assets/www/` 中的同名文件都是打包输入，不要直接编辑产物。
