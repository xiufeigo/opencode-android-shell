var t=(document.body.innerText||'').replace(/\s+/g,' ');
var n=(t.match(/reply with the single word OK/g)||[]).length;
return {count:n, tail:t.slice(-200)};
