(function(){
  var cands=[];
  var els=document.querySelectorAll("button,[role='button']");
  for(var i=0;i<els.length;i++){
    var t=(els[i].innerText||"").trim();
    if(t==="Add server" && els[i].getClientRects().length){
      var r=els[i].getBoundingClientRect();
      cands.push({i:i, x:Math.round(r.x+r.width/2), y:Math.round(r.y+r.height/2), w:Math.round(r.width), h:Math.round(r.height)});
    }
  }
  if(!cands.length) return JSON.stringify({err:"no Add server button", all:[].length});
  var pick=cands[cands.length-1];
  return JSON.stringify({cands:cands, pick:pick});
})()
