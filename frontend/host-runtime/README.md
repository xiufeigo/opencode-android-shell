# Android host runtime sources

`index.html`、`oc-host.js`、`oc-perf.js` 和 `oc-preflight.js` 的权威源码在本目录。`webapp/src/main/assets/www/` 中的同名文件是打包输入，由 `node frontend/host-runtime/build.mjs` 逐字节复制生成；不要直接编辑产物。

当前阶段先做权威源码边界与同步约束，不改变运行时内容。后续拆分 `oc-host.js` 时也应在本目录按职责建立模块，再由构建脚本生成单个 document-start 产物。
