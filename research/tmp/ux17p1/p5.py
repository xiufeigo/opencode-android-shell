import sys; sys.path.insert(0,r'D:\XiaoZhuTouTou\opencode-android-shell\research\tmp\ux17p1'); import cdp
js = r"""(function(){
  var all=Array.prototype.slice.call(document.querySelectorAll('*'),0,4000);
  var hits=[];
  all.forEach(function(e){
    var t=(e.innerText||'').trim();
    if(!t||t.length>24) return;
    if(!/server|setting|add|connect|new/i.test(t)) return;
    var r=e.getBoundingClientRect();
    if(r.width<1||r.height<1) return;
    hits.push({t:t,tag:e.tagName.toLowerCase(),x:Math.round(r.x+r.width/2),y:Math.round(r.y+r.height/2)});
  });
  var uniq=[]; var seen={};
  hits.forEach(function(h){ if(!seen[h.t+h.x+h.y]){seen[h.t+h.x+h.y]=1; uniq.push(h);} });
  return JSON.stringify(uniq.slice(0,25));
})()"""
print(cdp.evaluate(js))
