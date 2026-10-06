(async function(){
  function sleep(ms){ return new Promise(r=>setTimeout(r,ms)); }
  function byText(txt){
    var els=document.querySelectorAll("button,[role='button'],a,li");
    for(var i=0;i<els.length;i++){
      if((els[i].innerText||"").trim()===txt && els[i].getClientRects().length) return els[i];
    }
    return null;
  }
  var hdr=null;
  var b=document.querySelectorAll("button");
  for(var i=0;i<b.length;i++){ if(b[i].getClientRects().length && /Home/.test(b[i].innerText||"")){ hdr=b[i]; break; } }
  if(!hdr) return "NO_HDR";
  hdr.click(); await sleep(1500);
  var ns=byText("New session");
  if(!ns) return "NO_NEW_SESSION";
  ns.click();
  for(var k=0;k<10;k++){
    await sleep(1500);
    var ta=document.querySelector("textarea") || document.querySelector("[contenteditable='true']") || document.querySelector("input[type=text]");
    if(ta) return JSON.stringify({ok:true, after:k, tag:ta.tagName, path:location.pathname});
  }
  return JSON.stringify({ok:false, body:(document.body.innerText||"").slice(0,200), path:location.pathname});
})()
