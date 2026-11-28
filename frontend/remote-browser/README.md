# Remote browser panel

本目录是 Android/Web 浏览器面板的权威源码。`rpc.ts` 是生成文件，唯一权威契约是 `plugins/server-browser/src/rpc.ts`；运行 `node frontend/remote-browser/sync-rpc.mjs` 重新生成，架构检查会验证字节一致。

快速行为测试：

```sh
cd frontend/remote-browser
bun install
node test.mjs
```

测试要求 `frontend/android/upstream.json` pin 的 Bun 版本；PATH 不一致时设置 `BUN_BINARY`。这些测试覆盖输入队列和模型行为；本地快速测试使用与服务端插件相同的已发布 schema/effect 包。完整的 TypeScript 类型检查只能在包含上游 GUI SDK workspace 的构建上下文中运行，因此权威兼容验证仍是 `node frontend/android/build.mjs` 中的上游测试与 `packages/gui-plugins` typecheck。
