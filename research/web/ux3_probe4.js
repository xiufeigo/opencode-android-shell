(function(){
  function r(el){var b=el.getBoundingClientRect();return [Math.round(b.left),Math.round(b.top),Math.round(b.width),Math.round(b.height)].join(',');}
  var out=[];
  var tls=document.querySelectorAll('[role=tablist]');
  for(var i=0;i<tls.length;i++){var t=tls[i];
    var btns=t.querySelectorAll('button,[role=button]');
    var list=[];
    for(var j=0;j<btns.length;j++){var b=btns[j];
      list.push(((b.getAttribute('aria-label')||'').trim()||(b.textContent||'').trim().slice(0,16))+'@'+r(b)+' role='+(b.getAttribute('role')||'-')+' svg='+!!b.querySelector('svg'));}
    out.push({i:i,inHeader:!!t.closest('header'),rect:r(t),tabCount:t.querySelectorAll('[role=tab]').length,buttons:list});}
  return JSON.stringify(out);
})()
