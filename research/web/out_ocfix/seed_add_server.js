(() => {
  // 模拟"用户在 Settings 里 Add 成功"那一刻：应用自己把注册表写成下面这份
  // （形状取自 UX18-P1 设备实测的落盘样例），hidden 里还没有 origin。
  const KEY = "opencode.global.dat:server";
  const reg = {
    list: [{ type: "http", http: { url: "http://8.138.19.15:4094", password: "opencode" }, displayName: "8.138.19.15:4094" }],
    hidden: {},
    projects: {},
    lastProject: {},
    recentlyClosed: {},
  };
  localStorage.setItem(KEY, JSON.stringify(reg));
  return JSON.stringify({ wrote: localStorage.getItem(KEY) });
})()
