var bs=Array.prototype.slice.call(document.querySelectorAll('button')).filter(function(b){var r=b.getBoundingClientRect();return r.width>0&&r.height>0&&r.y>380;}).map(function(b){var r=b.getBoundingClientRect();return {t:(b.getAttribute('aria-label')||b.innerText||'').replace(/\s+/g,' ').trim().slice(0,22),x:Math.round(r.x+r.width/2),y:Math.round(r.y+r.height/2)};});
return {bs:bs.slice(0,8)};
