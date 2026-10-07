var de = document.scrollingElement;
var scrollers = Array.prototype.slice.call(document.querySelectorAll('*')).filter(function(e){
  return e.scrollHeight > e.clientHeight + 8 && e.clientHeight > 100;
}).slice(0,12).map(function(e){
  var r = e.getBoundingClientRect();
  return {tag:e.tagName, id:e.id||null, cls:(e.className&&e.className.toString?e.className.toString():'').slice(0,50),
          sh:e.scrollHeight, ch:e.clientHeight, top:Math.round(r.top), h:Math.round(r.height)};
});
var tabs = Array.prototype.slice.call(document.querySelectorAll('button')).filter(function(b){
  return /^(Home|Settings|Help|New session)$/.test((b.innerText||'').trim());
}).map(function(b){
  var r=b.getBoundingClientRect();
  return {txt:(b.innerText||'').trim(), x:Math.round(r.x), y:Math.round(r.y), w:Math.round(r.width), h:Math.round(r.height),
          pos: getComputedStyle(b.parentElement ? b.parentElement : b).position};
});
return {
  innerH: window.innerHeight, innerW: window.innerWidth, dpr: window.devicePixelRatio,
  docScrollH: de ? de.scrollHeight : null, docClientH: de ? de.clientHeight : null,
  bodyH: document.body.scrollHeight, bodyClientH: document.body.clientHeight,
  scrollY: window.scrollY,
  scrollers: scrollers, tabs: tabs
};