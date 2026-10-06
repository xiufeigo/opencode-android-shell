(function(){
  var row=document.querySelector(".settings-servers-row");
  if(!row) return "NO_ROW";
  var bs=row.querySelectorAll("button");
  var info=[]; for(var i=0;i<bs.length;i++) info.push(i+":"+((bs[i].innerText||bs[i].getAttribute("aria-label")||bs[i].title||"").trim()));
  bs[bs.length-1].click();
  return JSON.stringify({btns:info});
})()
