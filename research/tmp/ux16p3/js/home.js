var de=document.scrollingElement;
var items=Array.prototype.slice.call(document.querySelectorAll('a,[role="row"],[role="option"],li,button,div'))
 .filter(function(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0&&e.children.length<=2;})
 .map(function(e){var r=e.getBoundingClientRect();var t=(e.innerText||'').replace(/\s+/g,' ').trim();if(!t)return null;return {t:t.slice(0,44),x:Math.round(r.x+r.width/2),y:Math.round(r.y+r.height/2)};}).filter(Boolean);
return {url:location.href, body:(document.body.innerText||'').replace(/\s+/g,' ').slice(0,500), items:items.slice(0,30)};
