import sys; sys.path.insert(0,r'D:\XiaoZhuTouTou\opencode-android-shell\research\tmp\ux17p1'); import cdp
js = r"""(function(){
  var sel='a,button,[role="button"],[role="menuitem"],[aria-label]';
  var out=Array.prototype.slice.call(document.querySelectorAll(sel),0,60).map(function(e){
    var r=e.getBoundingClientRect();
    return {tag:e.tagName.toLowerCase(), al:(e.getAttribute('aria-label')||'').slice(0,30),
            txt:(e.innerText||'').replace(/\s+/g,' ').trim().slice(0,30),
            x:Math.round(r.x+r.width/2), y:Math.round(r.y+r.height/2), vis:r.width>0};
  }).filter(function(o){return o.vis && (o.txt||o.al);});
  return JSON.stringify(out);
})()"""
print(cdp.evaluate(js))
