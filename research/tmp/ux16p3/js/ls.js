var keys=[]; for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i); keys.push(k+' = '+String(localStorage.getItem(k)).slice(0,160));}
return {keys:keys, ss: Object.keys(sessionStorage)};
