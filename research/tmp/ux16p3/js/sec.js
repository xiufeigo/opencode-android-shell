var all = Array.prototype.slice.call(document.querySelectorAll('*'));
// find scrollable ancestors
var sc = all.filter(function(e){ return e.scrollHeight > e.clientHeight + 20 && e.clientHeight > 200; });
var info = sc.slice(0,6).map(function(e){
  var r=e.getBoundingClientRect();
  return {tag:e.tagName, cls:(e.className&&e.className.toString?e.className.toString():'').slice(0,60),
          sh:e.scrollHeight, ch:e.clientHeight, st:e.scrollTop};
});
// scroll the first big scroller to bottom-ish then look for Server section text
var target=null;
if(sc.length){ var s=sc[0]; s.scrollTop = s.scrollHeight; target=true; }
var secs = Array.prototype.slice.call(document.querySelectorAll('h1,h2,h3,h4,button,div')).filter(function(e){
  var t=(e.textContent||'').trim();
  return e.children.length<=1 && /^(Server|Providers|Models|Extensions|About|Advanced|Preferences|General|Appearance|Notifications)$/.test(t);
}).slice(0,20).map(function(e){
  var r=e.getBoundingClientRect();
  return {tag:e.tagName, txt:(e.textContent||'').trim().slice(0,24), x:Math.round(r.x+r.width/2), y:Math.round(r.y+r.height/2), w:Math.round(r.width), h:Math.round(r.height)};
});
return {scrolled:!!target, scrollers:info, sections:secs};