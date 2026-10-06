(async function(){
  function sleep(ms){ return new Promise(r=>setTimeout(r,ms)); }
  function byText(sel, txt){
    var els=document.querySelectorAll(sel);
    for(var i=0;i<els.length;i++){
      if((els[i].innerText||"").trim()===txt && els[i].getClientRects().length) return els[i];
    }
    return null;
  }
  byText("button,[role='button']","Settings").click(); await sleep(2500);
  var dd=byText("button,[role='button']","Preferences");
  if(dd){ dd.click(); await sleep(1500); }
  var srv=null;
  var opts=document.querySelectorAll("button,[role='menuitem'],[role='tab'],li,a");
  for(var i=0;i<opts.length;i++){
    if(/4095/.test((opts[i].innerText||"")) && opts[i].getClientRects().length){ srv=opts[i]; break; }
  }
  if(!srv) return "NO_4095_OPT";
  srv.click(); await sleep(3000);
  var more=null;
  var rowBtns=document.querySelectorAll(".settings-servers-row button");
  if(rowBtns.length) more=rowBtns[0];
  if(!more) return "NO_ROW_MENU";
  more.click(); await sleep(1200);
  var setd=byText("button,[role='menuitem'],[role='tab'],li,a","Set as default");
  if(!setd) return "NO_SETDEFAULT";
  setd.click(); await sleep(3000);
  return JSON.stringify({ok:true, body:(document.body.innerText||"").slice(0,200)});
})()
