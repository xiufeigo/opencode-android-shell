(async function(){
  function sleep(ms){ return new Promise(r=>setTimeout(r,ms)); }
  var trig = Array.prototype.filter.call(document.querySelectorAll("button"), function(x){
    return (x.innerText||"").trim()==="Preferences";
  })[0];
  if(!trig) return "NO_TRIGGER";
  trig.click();
  await sleep(800);
  var opts = Array.prototype.filter.call(document.querySelectorAll("button,[role='menuitem'],[role='tab'],li,a"), function(x){
    var t=(x.innerText||"").trim();
    return t==="Server" && x.getClientRects().length;
  });
  if(!opts.length) return "NO_SERVER_OPT";
  opts[0].click();
  await sleep(1500);
  return "navigated";
})()
