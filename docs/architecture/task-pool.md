# 架构优化任务池

优先级按“复杂性下降 / 行为风险”排序。每项必须独立完成闭环后才能标记完成。

## P0 删除死实现与虚假兼容

- [x] 删除 `oc-host.js` 中永久不可达的 Ghostty 终端字体缩放和触摸滚动实现；删除只为它们存在的历史源码绊线，保留或迁移真正覆盖当前 xterm 行为的验证。
- [x] 清理验证脚本和 README 对这些死实现的现行语义描述；历史描述只允许留在 `research/`。
- [x] 检查 `databaseEnabled` 等 Deprecated WebView 设置是否仍有真实调用方；没有则删除并验证存储行为。

## P1 建立权威源码边界

- [x] 把 `oc-host.js`、`oc-perf.js`、`oc-preflight.js`、诊断页 `index.html` 的权威源码迁出 `webapp/src/main/assets/www/`，产物目录只保留生成结果，并用脚本验证同步。
- [ ] 拆分 `oc-host.js` 单体为按职责命名的运行时模块，再打包成单个 document-start 脚本。优先拆分无副作用的纯逻辑，最后处理安装顺序敏感的模块。
- [ ] 将终端字号存储键、insets 事件名、桥接口名等跨 Kotlin/JS 契约集中到显式契约文档或共享生成常量，并加契约测试。

## P1 降低 Android 宿主耦合

- [x] 断开 `WebHostBridge` 对具体 `WebHostActivity` 的反向依赖，改为窄接口或能力注入。
- [x] 断开 `NotifierHost` 对具体 Activity 类的依赖，通知目标由入口层传入。
- [ ] 从 `WebHostActivity` 提取 WebView 实例装配、加载看门狗、相机权限、后台生命周期/电源策略等深模块；每次只提取一个职责并运行现有行为测试。
- [x] 建立 Kotlin 内部依赖扫描，禁止 `host/` 能力包反向引用入口 Activity。

## P2 测试体系归一

- [ ] 把历史事故型字符串绊线分批替换为行为契约测试；保留确实无法行为化的资产约束，但集中到一个资产契约测试工具，避免每个事故一套文件读取代码。
- [ ] 为 `frontend/remote-browser` 建立仓库内可运行的类型检查/测试入口，明确 `rpc.ts` 的生成关系。
- [ ] 收敛 4000 行级验证脚本中的重复 harness，只保留能驱动真实打包 SPA 的共享基础设施。

## P2 文档与版本 SSOT

- [x] README 删除可变版本号副本，指向 `upstream.json` 与 `webapp/build.gradle.kts`。
- [ ] 把当前有效工程约束迁入 `docs/architecture/`，`research/` 明确标记为历史证据。
- [x] 建立文档漂移检查，防止 README 再次硬编码过期版本。

## P3 防回归架构约束

- [x] 新增初版 `scripts/check-architecture.mjs`：防止 Ghostty 终端死实现复活。
- [x] 把架构检查接入 CI package workflow。
- [x] 扩展 `scripts/check-architecture.mjs`：检查 Kotlin `host/` 反向依赖。
- [x] 扩展 `scripts/check-architecture.mjs`：扫描宿主运行时生成产物边界并验证字节同步。
- [ ] 扩展 `scripts/check-architecture.mjs`：扫描上游 UI 生成产物边界，提供比“禁止手改”更强的机器约束。
- [ ] 在每次治理循环后把新确立规则加入检查，而不是只写文档。
