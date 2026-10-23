# 适配 Web 客户端

与 Android 使用同一份固定 OpenCode 2.1.4 UI 和 `frontend/remote-browser` 扩展。使用浏览器标准平台接口；Android 则必须连接原生平台桥，不存在自动降级。

```sh
node frontend/android/build.mjs --web
node frontend/web/serve.mjs
```

打开 `http://127.0.0.1:4175`，在官方连接界面添加原有 OpenCode 服务。使用服务端浏览器需先安装 [插件](../../plugins/server-browser/README.md)。

Release 的 Web ZIP 包含 `serve.mjs` 和完整 `dist/`，解压后直接运行 `node serve.mjs`。该服务仅托管静态文件，不转发 API。部署到已有站点时，配置未知的前端路由回到 `index.html`，保留 `/_assets/` 和 `/oc-terminal.js`；HTTPS 界面应连接 HTTPS 后端，避免浏览器混合内容限制。
