(async function(){
  function sleep(ms){ return new Promise(r=>setTimeout(r,ms)); }
  var hdr=document.querySelectorAll("button");
  var trig=null;
  for(var i=0;i<hdr.length;i++){
    if(hdr[i].getClientRects().length && /Home/.test(hdr[i].innerText||"")) { trig=hdr[i]; break; }
  }
  if(!trig) return "NO_TRIG";
  trig.click();
  await sleep(1200);
  var opts=[];
  var all=document.querySelectorAll("button,[role='menuitem'],[role='tab'],li,a");
  for(var i=0;i<all.length;i++){
    var t=(all[i].innerText||"").trim();
    if(t && t.length<30 && all[i].getClientRects().length) opts.push(all[i].tagName+"|"+t);
  }
  return JSON.stringify(opts.slice(0,16));
})()
