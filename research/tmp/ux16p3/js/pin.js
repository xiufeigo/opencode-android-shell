// Point the app at the real backend: hide the builtin 4173 phantom and pin 4095 as active.
var k = 'opencode.global.dat:server';
var cfg = JSON.parse(localStorage.getItem(k) || '{}');
cfg.list = cfg.list || [];
cfg.hidden = cfg.hidden || {};
// hide builtin origin entry so the UI stops resolving "current server" to 4173
cfg.hidden['http://127.0.0.1:4173'] = true;
localStorage.setItem(k, JSON.stringify(cfg));
// also record an explicit active-server hint the upstream may read
localStorage.setItem('opencode.activeServer', 'http://127.0.0.1:4095');
return {cfg: cfg, stored: localStorage.getItem(k)};