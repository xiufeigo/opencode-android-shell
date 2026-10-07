var out=Array.prototype.slice.call(document.querySelectorAll('button,[role="button"],[role="combobox"]')).map(function(e){
 var r=e.getBoundingClientRect(); if(!(r.width>0&&r.height>0)) return null;
 var t=(e.innerText||e.getAttribute('aria-label')||'').replace(/\s+/g,' ').trim();
 return {t:t.slice(0,36),x:Math.round(r.x+r.width/2),y:Math.round(r.y+r.height/2),w:Math.round(r.width),h:Math.round(r.height),y0:Math.round(r.y)};
}).filter(Boolean);
return {btns:out.slice(0,20), body:(document.body.innerText||'').replace(/\s+/g,' ').slice(0,200)};
