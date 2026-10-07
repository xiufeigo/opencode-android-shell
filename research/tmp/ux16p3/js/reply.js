var t=(document.body.innerText||'').replace(/\s+/g,' ');
var i=t.indexOf('single word OK');
return {sentAt:i, tail:t.slice(Math.max(0,i-40), i+220)};
