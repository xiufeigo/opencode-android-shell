(() => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  return (async () => {
    // 直接整页导航到 /settings，避免 pushState 同 URL 不触发路由
    location.href = "/settings";
    return "NAVIGATING";
  })();
})()
