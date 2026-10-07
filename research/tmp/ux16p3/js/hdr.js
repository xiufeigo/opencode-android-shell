var hdr = Array.prototype.slice.call(document.querySelectorAll('button,a,[role="button"]')).map(function(e){
  var r=e.getBoundingClientRect();
  if(!(r.width>0&&r.height>0)) return null;
  if(r.top > 130) return null;
  return {tag:e.tagName, txt:(e.innerText||e.getAttribute('aria-label')||e.title||'').replace(/\s+/g,' ').trim().slice(0,30),
          x:Math.round(r.x), y:Math.round(r.y), w:Math.round(r.width), h:Math.round(r.height)};
}).filter(Boolean);
// find any element whose text mentions 4173
var hits = Array.prototype.slice.call(document.querySelectorAll('*')).filter(function(e){
  return e.children.length===0 && /4173/.test(e.textContent||'');
}).slice(0,10).map(function(e){
  var r=e.getBoundingClientRect();
  return {tag:e.tagName, txt:(e.textContent||'').trim().slice(0,60), x:Math.round(r.x), y:Math.round(r.y)};
});
var de=document.scrollingElement;
return {hdr:hdr, hits4173:hits, scrollH:de?de.scrollHeight:null, clientH:de?de.clientHeight:null, scrollY:window.scrollY};