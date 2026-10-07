(() => {
  const cancel = Array.from(document.querySelectorAll('button')).find(b => (b.innerText || '').trim() === 'Cancel');
  if (cancel) cancel.click();
  // Seed the exact server-list entry shape the app itself persists (P5 documented)
  const entry = {
    list: [
      { type: 'http', http: { url: 'http://127.0.0.1:4095', password: 'opencode' }, displayName: 'opencode' }
    ]
  };
  localStorage.setItem('opencode.global.dat:server', JSON.stringify(entry));
  const readback = localStorage.getItem('opencode.global.dat:server');
  setTimeout(() => location.reload(), 250);
  return JSON.stringify({ cancelled: !!cancel, seeded: readback });
})()
