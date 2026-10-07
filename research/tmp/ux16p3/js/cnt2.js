var t=(document.body.innerText||'').replace(/\s+/g,' ');
return {count:(t.match(/reply with the single word OK/g)||[]).length, tail:t.slice(-170)};
