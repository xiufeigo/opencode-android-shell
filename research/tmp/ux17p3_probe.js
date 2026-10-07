(function(){
  var n = document.querySelectorAll('[data-oc-phantom="1"]');
  var out = [];
  for (var i = 0; i < n.length; i++) {
    out.push({
      tag: n[i].tagName,
      cls: String(n[i].className || '').slice(0, 40),
      txt: String(n[i].innerText || n[i].textContent || '').trim().slice(0, 40)
    });
  }
  return JSON.stringify({ count: n.length, els: out.slice(0, 8) });
})()
