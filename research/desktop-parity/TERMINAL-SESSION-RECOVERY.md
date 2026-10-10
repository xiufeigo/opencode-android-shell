# 会话终端恢复验收

2026-10-10，Android 2.0.15 / versionCode 43，官方 UI 基准仍为 2.0.26。

终端标签、顺序、选中 PTY 和恢复快照按服务器＋目录＋会话保存。这是用户要求的行为调整：官方 Desktop 同目录共享终端。一个目录仍使用一份官方持久化存储，内部按会话分组，避免运行进程的归属随布局缓存裁剪。旧共享标签由第一个打开终端的会话接收一次。

验证结果：

- 真实模型＋官方持久化器单测 4/4：同目录会话隔离、冷模型恢复原标签/选中 PTY、旧状态一次迁移、缓存淘汰与关闭操作不影响其他会话的进程。UI 构建每次必跑。
- app / GUI 扩展类型检查和源码构建通过；Android 单测 249/249，release/debug 构建通过。
- 完整终端 SPA 回归 51/51，包含触摸、IME、旋转和刷新恢复；刷新断言同时核对原 PTY ID。报告：`research/tmp/verify-android-terminal-release-ready/2026-10-10T03-52-50-449Z-3497f1/report.json`。该项使用 Chromium 与测试后端。
- 实际模拟器 APK＋普通 OpenCode 服务验收 5/5：同目录两个会话、三个真实 PTY；原 App PID 6391，强杀 6 秒后新 PID 6762；远端 PTY PID 始终为 47084；进度从 8 增长到 23。命令在服务器写入的启动记录只有一条，恢复后 PTY 列表没有新增进程；另一个会话仍恢复自己的输出。报告：`research/tmp/verify-android-terminal-session-recovery/2026-10-10T04-00-48-233Z/report.json`。未替换原生桥和 UI，设备为 Android 模拟器，未测实体手机。

服务必须持续运行。App 断开仅关闭客户端连接，明确关闭终端标签会终止对应 PTY；服务重启不能恢复旧进程。服务端保留最近 2 MiB 输出，不提供无限历史归档；客户端不会自动重新执行命令。

release APK 使用用户指定密钥，证书 SHA-256：`760dc05fb4d1a125ed82cf9bff8eee622c7244d44b6ddc82a4c34e0b37b820e6`。
