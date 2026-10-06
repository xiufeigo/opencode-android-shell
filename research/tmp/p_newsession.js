(async function(){
  function sleep(ms){ return new Promise(r=>setTimeout(r,ms)); }
  function clickExact(txt){
    var els=document.querySelectorAll("button,[role='button'],a");
    for(var i=0;i<els.length;i++){
      if((els[i].innerText||"").trim()===txt && els[i].getClientRects().length){ els[i].click(); return true; }
    }
    return false;
  }
  clickExact("Back to app"); await sleep(2500);
  var ns = clickExact("New session"); await sleep(3000);
  var t = document.querySelector("textarea") || document.querySelector("input[type=text]");
  return JSON.stringify({newSession:ns, path:location.pathname, hasComposer:!!t, tag:t?t.tagName+":"+t.type:""});
})()
