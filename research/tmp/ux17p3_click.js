(function(){
  var b = Array.prototype.slice.call(document.querySelectorAll('button')).filter(function(e){
    return String(e.innerText || '').indexOf('All projects') >= 0;
  })[0];
  if (!b) return 'trigger-not-in-DOM';
  b.removeAttribute('data-oc-phantom');
  var r = b.getBoundingClientRect();
  b.click();
  return JSON.stringify({
    clicked: true,
    deviceX: Math.round((r.x + r.width / 2) * (innerWidth / 411) * (1080 / innerWidth)),
    deviceY: Math.round((r.y + r.height / 2) * (2400 / innerHeight))
  });
})()
