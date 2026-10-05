(function(){
  function r(el){var b=el.getBoundingClientRect();return [Math.round(b.left),Math.round(b.top),Math.round(b.width),Math.round(b.height)].join(',');}
  var out={points:{},tablist:null};
  [[980,93],[1245,94],[295,46]].forEach(function(pt){
    var el=document.elementFromPoint(pt[0],pt[1]);
    var chain=[];var p=el;
    while(p&&chain.length<7){var s='<'+p.tagName.toLowerCase();if(p.id)s+='#'+p.id;var rl=p.getAttribute&&p.getAttribute('role');if(rl)s+='[role='+rl+']';var al=p.getAttribute&&p.getAttribute('aria-label');if(al)s+='[al='+al+']';chain.push(s+'>');p=p.parentElement;}
    out.points[pt.join(',')]=chain.join(' < ');
  });
  var tls=document.querySelectorAll('[role=tablist]');
  for(var i=0;i<tls.length;i++){
    var t=tls[i]; if(t.closest('header'))continue;
    var btns=t.querySelectorAll('button,[role=button]');
    var list=[];
    for(var j=0;j<btns.length;j++){var b=btns[j];
      list.push({label:(b.getAttribute('aria-label')||'').trim(),text:(b.textContent||'').trim().slice(0,18),rect:r(b),role:b.getAttribute('role')||'',svg:!!b.querySelector('svg'),vis:b.offsetParent!==null});}
    out.tablist={rect:r(t),cls:t.getAttribute('class'),btnCount:btns.length,buttons:list};
  }
  return JSON.stringify(out);
})()
