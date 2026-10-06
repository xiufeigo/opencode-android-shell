(async () => {
  const dbs = await indexedDB.databases();
  const out = [];
  for (const d of dbs) {
    const stores = await new Promise((res) => {
      try {
        const req = indexedDB.open(d.name);
        req.onsuccess = (e) => {
          const db = e.target.result;
          const names = Array.from(db.objectStoreNames);
          const counts = {};
          // 每个 store 读 count，确认真有数据还是空壳
          let pending = names.length;
          if (!pending) return res({ names, counts });
          for (const n of names) {
            try {
              const c = db.transaction(n, 'readonly').objectStore(n).count();
              c.onsuccess = () => { counts[n] = c.result; if (--pending === 0) res({ names, counts }); };
              c.onerror = () => { counts[n] = 'ERR'; if (--pending === 0) res({ names, counts }); };
            } catch (err) { counts[n] = 'ERR:' + err; if (--pending === 0) res({ names, counts }); }
          }
        };
        req.onerror = () => res({ names: ['OPEN_ERR'] });
      } catch (err) { res({ names: ['THROW:' + err] }); }
    });
    out.push({ db: d.name, version: d.version, stores });
  }
  return JSON.stringify({ databases: out }, null, 1);
})()
