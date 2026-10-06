(function(){
  var out=[];
  var els=document.querySelectorAll("button,[role='button'],a,textarea");
  for(var i=0;i<els.length;i++){
    var e=els[i];
    if(!e.getClientRects().length) continue;
    var t=(e.innerText||e.placeholder||e.getAttribute("aria-label")||"").trim().slice(0,32);
    out.push(e.tagName+"|"+t);
  }
  return JSON.stringify({path:location.pathname, n:out.length, items:out.slice(0,20), body:(document.body.innerText||"").slice(0,200)});
})()
