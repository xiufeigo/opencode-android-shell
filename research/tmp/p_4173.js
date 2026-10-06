(function(){
  var out=[];
  var all=document.querySelectorAll("*");
  for(var i=0;i<all.length;i++){
    var e=all[i];
    if(e.nodeType!==1) continue;
    var direct="";
    for(var j=0;j<e.childNodes.length;j++){
      if(e.childNodes[j].nodeType===3) direct+=e.childNodes[j].nodeValue;
    }
    if(direct.indexOf("4173")<0) continue;
    var cs=getComputedStyle(e);
    var marked=false, p=e;
    while(p && p!==document.documentElement){
      if(p.getAttribute && p.getAttribute("data-oc-phantom")==="1"){ marked=true; break; }
      p=p.parentElement;
    }
    out.push({
      tag:e.tagName, cls:(e.className||"").toString().slice(0,50),
      text:direct.trim().slice(0,40), display:cs.display,
      rects:e.getClientRects().length, insideMarked:marked
    });
  }
  return JSON.stringify({count:out.length, items:out.slice(0,8), registryPresent:!!localStorage.getItem("opencode.global.dat:server")});
})()
