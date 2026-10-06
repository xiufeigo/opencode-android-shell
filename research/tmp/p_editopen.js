(function(){
  var row=document.querySelector(".settings-servers-row");
  if(!row) return "NO_ROW";
  row.querySelectorAll("button")[0].click();
  var found=[];
  var all=document.querySelectorAll("*");
  for(var i=0;i<all.length;i++){
    var e=all[i];
    var t=(e.innerText||"").trim();
    if(!t || t.length>30) continue;
    var tag=e.tagName;
    if(tag!=="BUTTON" && tag!=="LI" && tag!=="A" && e.getAttribute("role")!=="menuitem" && tag!=="DIV" && tag!=="SPAN") continue;
    if(/^(edit|remove|set default|use|copy)/i.test(t)) found.push(tag+"|"+(e.getAttribute("role")||"")+"|"+t);
  }
  return JSON.stringify(found.slice(0,12));
})()
