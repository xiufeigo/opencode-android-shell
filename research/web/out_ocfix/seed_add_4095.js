(() => {
  // 模拟"用户在 Settings 里 Add 成功"那一刻（真实可达的后端：设备 127.0.0.1:4095
  // 经 adb reverse 打到宿主机 4095 的 v2 opencode 2.0.24）。
  const KEY = "opencode.global.dat:server";
  const reg = {
    list: [{ type: "http", http: { url: "http://127.0.0.1:4095", password: "opencode" }, displayName: "opencode" }],
    hidden: {},
    projects: {},
    lastProject: {},
    recentlyClosed: {},
  };
  localStorage.setItem(KEY, JSON.stringify(reg));
  return JSON.stringify({ wrote: localStorage.getItem(KEY) });
})()
