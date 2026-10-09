# Android 与 OpenCode Desktop 对齐

目标：Android 连接已运行的远程 OpenCode，使用与 Desktop 相同的共享 UI。
浏览器要求：提供官方内置浏览器操作和自动化。沿用已选择的远程客户端架构，官方 Electron/CDP 引擎运行在电脑浏览器服务，Android 提供面板、画面和输入。
不在 Android 上移植本地执行引擎、WSL 或电脑本地文件系统。

根因：发布 UI、源码标记和参考源码版本不一致；移动端改动依赖编译后的文件名和符号；Android 原生桥未接入官方 PlatformProvider。

选择结构性修复：固定官方 v2 commit，从源码应用少量 Android 适配并构建，而不是继续修改压缩 JS。保留经过验证的 xterm 触屏适配，通过官方平台接口接入系统文件保存、外链和剪贴板，使用官方持久化恢复会话。

影响范围：frontend/android、browser-companion、UI 构建脚本、webapp 宿主、内嵌资产、CI 与产品说明。

验证顺序：平台消息及触屏行为单元测试；官方 UI 类型检查；Android 单测与 APK 构建；手机和平板导航、会话、终端、文件保存和恢复冒烟测试。评审全部 diff，明确记录平台特有能力的差异。
