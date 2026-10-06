(function(){
  var rows=document.querySelectorAll(".settings-servers-row");
  var vis=[];
  for(var i=0;i<rows.length;i++){
    var r=rows[i];
    if(r.getClientRects().length) vis.push((r.innerText||"").trim().replace(/\n/g," / ").slice(0,70));
  }
  var dots=document.querySelectorAll(".settings-servers-row span, .settings-servers-row div");
  return JSON.stringify({
    registryKeyPresent: !!localStorage.getItem("opencode.global.dat:server"),
    registryLen: (localStorage.getItem("opencode.global.dat:server")||"").length,
    markedCount: document.querySelectorAll("[data-oc-phantom='1']").length,
    visibleRows: vis,
    bodyText: (document.body.innerText||"").slice(0,240)
  });
})()
