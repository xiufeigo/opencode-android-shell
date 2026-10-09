# Android 共享 UI 适配

APK 使用与 Desktop 相同的官方 UI。`frontend/android/build.mjs` 从固定提交构建源码，应用 `upstream.patch`，并接入本目录的纯逻辑；不再按生成 chunk 文件名或压缩符号修改产物。

`server-state.mjs` 在初始化前移除旧本地资产 origin 的服务器记录、默认服务器和可见性标记，保留真正的远程服务器、名称、凭据和工作区信息。两个历史 registry 都先完成验证再更新，迁移幂等。构建使用官方 `VITE_OPENCODE_SERVER_MODE=none`，避免将只提供静态资源的本地端点注册成 OpenCode 服务。

`resize-pointer.mjs` 管理官方 ResizeHandle 的 Pointer Events 生命周期，支持鼠标、触摸和笔，忽略第二指针，在释放、取消、丢失捕获、窗口失焦和组件卸载时清理。尺寸约束、RTL、折叠和持久化仍由官方组件负责。源码 CSS 将触屏目标扩大到 24px。

运行 `frontend/terminal` 的 `npm test` 验证纯逻辑，再执行 `node frontend/android/build.mjs`。整页验证分别使用 `scripts/verify-android-navigation.mjs` 和 `scripts/verify-android-resize.mjs`；它们使用完整打包 UI、模拟原生桥和测试服务，不能冒充实际 Android 设备验证。

`scripts/verify-android-resize-device.mjs` 在实际 APK WebView 中通过 ADB 滑动验证分隔线，结束后恢复模拟器显示设置。内置浏览器通过 `browser-companion` 复用官方 Electron 引擎；普通工作区文件入口采用官方 Web 行为。
