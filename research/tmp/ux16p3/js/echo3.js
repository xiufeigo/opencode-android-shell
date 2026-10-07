var t=document.body.innerText||'';
var i=t.indexOf('ux16p3gateok');
return {found:i>=0, occurrences:(t.match(/ux16p3gateok/g)||[]).length, tail:t.replace(/\s+/g,' ').slice(0,260)};
