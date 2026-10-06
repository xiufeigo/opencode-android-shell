(async function(){
  function sleep(ms){ return new Promise(r=>setTimeout(r,ms)); }
  function log(a){ steps.push(a); }
  var steps=[];
  function byText(txt){
    var els=document.querySelectorAll("button,[role='button'],a,li");
    for(var i=0;i<els.length;i++){
      if((els[i].innerText||"").trim()===txt && els[i].getClientRects().length) return els[i];
    }
    return null;
  }
  var s=byText("Settings");
  if(!s) return JSON.stringify({err:"no Settings btn", body:(document.body.innerText||"").slice(0,120)});
  s.click(); await sleep(3000); log("settings");
  var p=byText("Preferences");
  if(p){ p.click(); await sleep(1500); log("dropdown"); }
  var srv=null;
  var opts=document.querySelectorAll("button,[role='menuitem'],[role='tab'],li,a");
  for(var i=0;i<opts.length;i++){
    if(/4095/.test((opts[i].innerText||"")) && opts[i].getClientRects().length){ srv=opts[i]; break; }
  }
  if(!srv) return JSON.stringify({err:"no 4095 opt", steps:steps});
  srv.click(); await sleep(3000); log("4095 page");
  var rb=document.querySelectorAll(".settings-servers-row button");
  if(!rb.length) return JSON.stringify({err:"no row btn", steps:steps, body:(document.body.innerText||"").slice(0,160)});
  rb[0].click(); await sleep(1500); log("row menu");
  var sd=byText("Set as default");
  if(!sd) return JSON.stringify({err:"no Set as default", steps:steps});
  sd.click(); await sleep(3000); log("set default");
  return JSON.stringify({ok:true, steps:steps, body:(document.body.innerText||"").slice(0,200)});
})()
