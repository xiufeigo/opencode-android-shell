(function(){
  function r(el){var b=el.getBoundingClientRect();return [Math.round(b.left),Math.round(b.top),Math.round(b.width),Math.round(b.height)].join(',');}
  function desc(el){var s='<'+el.tagName.toLowerCase();if(el.id)s+='#'+el.id;var c=el.getAttribute('class');if(c)s+='.'+String(c).split(' ').slice(0,3).join('.');var rl=el.getAttribute('role');if(rl)s+='[role='+rl+']';return s+'>';}
  var out={dpr:window.devicePixelRatio, vw:window.innerWidth, vh:window.innerHeight, tablists:[], buttons:[]};
  var tls=document.querySelectorAll('[role=tablist]');
  for(var i=0;i<tls.length;i++){var t=tls[i];var kids=[];var tabs=t.querySelectorAll('[role=tab]');
    for(var j=0;j<tabs.length;j++){kids.push((tabs[j].textContent||'').trim().slice(0,20)+'@'+r(tabs[j]));}
    out.tablists.push({desc:desc(t),rect:r(t),inHeader:!!t.closest('header'),label:t.getAttribute('aria-label')||'',tabs:kids});}
  var btns=document.querySelectorAll('button,[role=button]');
  for(var k=0;k<btns.length;k++){var b=btns[k];
    if(!b.querySelector('svg'))continue;
    if(b.offsetParent===null)continue;
    var lbl=(b.getAttribute('aria-label')||'').trim();
    var chain=[];var p=b.parentElement;
    while(p&&chain.length<6){chain.push(desc(p));p=p.parentElement;}
    out.buttons.push({label:lbl,text:(b.textContent||'').trim().slice(0,24),rect:r(b),inHeader:!!b.closest('header'),inTablist:!!b.closest('[role=tablist]'),role:b.getAttribute('role')||'',chain:chain.join(' < ')});}
  return JSON.stringify(out);
})()
