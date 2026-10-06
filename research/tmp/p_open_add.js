(async function(){
  function sleep(ms){ return new Promise(r=>setTimeout(r,ms)); }
  function clickText(txt, exact){
    var els = document.querySelectorAll("button,[role='button'],[data-slot],a");
    for(var i=0;i<els.length;i++){
      var t=(els[i].innerText||"").trim();
      if(txt==="Server" && t.indexOf("Server")===0 && t.length<10){ els[i].click(); return true; }
      if(exact && t===txt){ els[i].click(); return true; }
    }
    return false;
  }
  var a = clickText("Settings", true);
  await sleep(2000);
  var b = clickText("Server", false);
  await sleep(2000);
  var c = clickText("Add server", true);
  await sleep(2000);
  var inputs=[]; var ins=document.querySelectorAll("input");
  for(var i=0;i<ins.length;i++) inputs.push(i+":"+ins[i].type+":"+(ins[i].placeholder||"")+":"+(ins[i].value||""));
  return JSON.stringify({settings:a, server:b, addserver:c, inputs:inputs});
})()
