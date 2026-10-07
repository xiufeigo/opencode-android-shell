(function(){
  var b = Array.prototype.slice.call(document.querySelectorAll('button')).filter(function(e){
    return String(e.innerText || '').indexOf('All projects') >= 0;
  })[0];
  if (!b) return 'trigger-not-in-DOM';
  b.removeAttribute('data-oc-phantom');
  var cs = getComputedStyle(b);
  var r = b.getBoundingClientRect();
  return JSON.stringify({
    txt: b.innerText,
    display: cs.display,
    w: Math.round(r.width),
    h: Math.round(r.height),
    x: Math.round(r.x),
    y: Math.round(r.y)
  });
})()
