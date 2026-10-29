import { builtinCatalog } from "@opencode/plugin/gui/builtin"
import { Plugin } from "@opencode/plugin/gui"

export default Plugin.define({
  i18n: builtinCatalog({
    en: {
      title: "Browser", new: "New tab", open: "Open browser", address: "Search or enter a URL",
      back: "Back", forward: "Forward", reload: "Reload", stop: "Stop", close: "Close tab",
      home: "Open a page", description: "Preview your app, read docs, or browse the web.",
      retry: "Reconnect", unavailable: "Server browser unavailable",
      install: "Install the OpenCode server browser plugin on this server to use this panel.",
      keyboard: "Page keyboard input", accept: "OK", dismiss: "Cancel", options: "Browser options",
      copy: "Copy link", external: "Open in system browser", fit: "Fit page to panel", files: "Downloads and captures",
    },
    zh: {
      title: "浏览器", new: "新标签页", open: "打开浏览器", address: "搜索或输入网址",
      back: "后退", forward: "前进", reload: "重新加载", stop: "停止", close: "关闭标签页",
      home: "打开网页", description: "预览应用、阅读文档或浏览网页。",
      retry: "重新连接", unavailable: "服务端浏览器不可用",
      install: "在当前服务器安装 OpenCode 服务端浏览器插件后，即可使用这个面板。",
      keyboard: "网页键盘输入", accept: "确定", dismiss: "取消", options: "浏览器选项",
      copy: "复制链接", external: "在系统浏览器打开", fit: "将网页适配到面板", files: "下载与截图",
    },
  }),
})
