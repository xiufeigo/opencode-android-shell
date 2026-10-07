var rows = Array.prototype.slice.call(document.querySelectorAll('*')).filter(function(e){
  if(e.children.length>2) return false;
  var t=(e.textContent||'').replace(/\s+/g,' ').trim();
  return /127\.0\.0\.1:(4173|4095)/.test(t);
}).slice(0,12).map(function(e){
  var r=e.getBoundingClientRect();
  // look for a status dot: small colored span
  var dot=null;
  Array.prototype.slice.call(e.querySelectorAll('span,div')).some(function(s){
    var sr=s.getBoundingClientRect();
    if(sr.width>0&&sr.width<=14&&sr.height>0&&sr.height<=14){
      var bg=getComputedStyle(s).backgroundColor;
      if(bg && bg!=='rgba(0, 0, 0, 0)'){ dot=bg; return true; }
    }
    return false;
  });
  return {txt:(e.textContent||'').replace(/\s+/g,' ').trim().slice(0,50), dot:dot, y:Math.round(r.y)};
});
// whole-page text check
var body=(document.body.innerText||'');
return {rows:rows,
        has4173:/4173/.test(body), has4095:/4095/.test(body),
        bodyHasUx16p3:/ux16p3/.test(body),
        snippet: body.replace(/\s+/g,' ').slice(0,300)};