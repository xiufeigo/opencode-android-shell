var d = document;
var sel = 'a,li,button,[role="row"],[role="option"],[data-testid]';
var rows = Array.prototype.slice.call(d.querySelectorAll(sel), 0, 45).map(function(e){
  var r = e.getBoundingClientRect();
  var t = (e.innerText || e.textContent || '').replace(/\s+/g,' ').trim().slice(0,50);
  if (!t) return null;
  return t + ' @' + Math.round(r.x) + ',' + Math.round(r.y) + ' ' + Math.round(r.width) + 'x' + Math.round(r.height);
}).filter(Boolean);
return {
  url: location.href,
  title: d.title,
  colorScheme: d.documentElement.dataset.colorScheme || null,
  viewport: {w: window.innerWidth, h: window.innerHeight, vvH: window.visualViewport ? Math.round(window.visualViewport.height) : null, vvOff: window.visualViewport ? Math.round(window.visualViewport.offsetTop) : null},
  bodyStart: (d.body.innerText || '').replace(/\s+/g,' ').trim().slice(0,400),
  rows: rows
};