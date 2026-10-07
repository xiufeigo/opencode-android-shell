var all=Array.prototype.slice.call(document.querySelectorAll('div,pre,span')).filter(function(e){var r=e.getBoundingClientRect();return r.width>50&&r.height>10&&/ux16p3gateok/.test(e.innerText||'');}).map(function(e){return (e.innerText||'').replace(/\s+/g,' ').slice(-160);});
return {hits:all.slice(0,4)};
