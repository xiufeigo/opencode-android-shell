(function(){
  var els=document.querySelectorAll("button,[role='button']");
  var target=null;
  for(var i=0;i<els.length;i++){
    var t=(els[i].innerText||"").trim();
    if(t==="Add server" && els[i].getClientRects().length) target=els[i];
  }
  if(!target) return "NO_BTN";
  var r=target.getBoundingClientRect();
  target.click();
  return JSON.stringify({clicked:true, x:Math.round(r.x), y:Math.round(r.y), disabled:target.disabled||false});
})()
