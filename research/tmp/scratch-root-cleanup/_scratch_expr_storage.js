(function () {
  const dump = (s) => {
    const o = {}; const keys = [];
    try {
      for (let i = 0; i < s.length; i++) { const k = s.key(i); keys.push(k); o[k] = String(s.getItem(k)).slice(0, 300); }
    } catch (e) { return 'ERR:' + e; }
    return { len: s.length, keys, values: o };
  };
  return JSON.stringify({
    origin: location.origin,
    sessionStorage: dump(sessionStorage),
    localStorageKeys: (() => { const a = []; for (let i = 0; i < localStorage.length; i++) a.push(localStorage.key(i)); return a; })(),
    // 页面里能不能直接看到注册表状态（上游 solid store / context）
    ocGlobals: Object.keys(window).filter(k => /opencode|oc[A-Z]/i.test(k)).slice(0, 20),
    cookies: document.cookie,
  }, null, 1);
})()
