(function(){
  var inputs=[]; var ins=document.querySelectorAll("input");
  for(var i=0;i<ins.length;i++) inputs.push(i+":"+ins[i].type+":"+ins[i].value+":"+ins[i].disabled);
  var dlg=document.querySelectorAll("[role='dialog']");
  var txt="";
  for(var i=0;i<dlg.length;i++){ if(dlg[i].getClientRects().length) txt=(dlg[i].innerText||"").trim(); }
  if(!txt) txt=(document.body.innerText||"").slice(-300);
  return JSON.stringify({inputs:inputs, dialog:txt.slice(0,400), innerW:window.innerWidth, innerH:window.innerHeight, dpr:window.devicePixelRatio});
})()
