(function(){
  var els=document.querySelectorAll("button,[role='button'],[data-slot]");
  var found=[];
  for(var i=0;i<els.length;i++){
    var t=(els[i].innerText||"").trim();
    if(t==="Preferences" || t==="Server") found.push(els[i].tagName+"|"+t+"|"+(els[i].getAttribute("role")||""));
  }
  return JSON.stringify(found.slice(0,10));
})()
