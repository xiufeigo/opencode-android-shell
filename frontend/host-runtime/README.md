# Android host runtime sources

`index.html`、`oc-perf.js`、`oc-preflight.js` 以及 document-start facade 的权威源码在本目录。facade 按职责拆成 `android-facade.js`、`overlay-focus.js`、`terminal-settings.js`；`webapp/src/main/assets/www/oc-host.js` 由构建脚本按顺序拼接生成。`webapp/src/main/assets/www/` 中的同名文件都是打包输入，不要直接编辑产物。
