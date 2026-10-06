(async function(){
  function sleep(ms){ return new Promise(r=>setTimeout(r,ms)); }
  var ns=null;
  var b=document.querySelectorAll("button,[role='button']");
  for(var i=0;i<b.length;i++){ if((b[i].innerText||"").trim()==="New session" && b[i].getClientRects().length){ ns=b[i]; break; } }
  if(!ns) return "NO_NEW_SESSION";
  ns.click();
  await sleep(3000);
  var ta=document.querySelector("textarea");
  var ce=document.querySelector("[contenteditable='true']");
  var el = ta || ce;
  if(!el) return JSON.stringify({err:"no composer", path:location.pathname, body:(document.body.innerText||"").slice(0,160)});
  el.focus();
  var text="Count from 1 to 200 comma separated";
  if(ta){
    var setter=Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,"value").set;
    setter.call(el,text);
    el.dispatchEvent(new Event("input",{bubbles:true}));
  } else {
    el.textContent=text;
    el.dispatchEvent(new InputEvent("input",{bubbles:true}));
  }
  await sleep(600);
  return JSON.stringify({ok:true, tag:el.tagName, val:(el.value||el.textContent||"").slice(0,50)});
})()
