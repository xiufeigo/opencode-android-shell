(async function(){
  function sleep(ms){ return new Promise(r=>setTimeout(r,ms)); }
  document.body.dispatchEvent(new KeyboardEvent("keydown",{key:"Escape",bubbles:true}));
  var back = Array.prototype.filter.call(document.querySelectorAll("button,[role='button'],a"), function(x){
    return (x.innerText||"").trim()==="Back to app";
  })[0];
  if(back) { back.click(); await sleep(2000); }
  var st = Array.prototype.filter.call(document.querySelectorAll("button,[role='button'],a"), function(x){
    return (x.innerText||"").trim()==="Settings";
  })[0];
  if(st){ st.click(); await sleep(2500); }
  var sv = Array.prototype.filter.call(document.querySelectorAll("button,[role='button'],a"), function(x){
    var t=(x.innerText||"").trim();
    return t==="Server" && x.getClientRects().length;
  })[0];
  if(sv){ sv.click(); await sleep(2500); return "SERVER_PAGE"; }
  return "NO_SERVER_NAV";
})()
