(function(){
  var out=[];
  var els=document.querySelectorAll("[role='menuitem'],[data-slot],button,li,a");
  for(var i=0;i<els.length;i++){
    var e=els[i];
    var t=(e.innerText||"").trim();
    var vis = !!(e.offsetParent || e.getClientRects().length);
    if(vis && t && t.length<40) out.push(e.tagName+"|"+e.getAttribute("role")+"|"+t);
  }
  return JSON.stringify(out.slice(0,30));
})()
