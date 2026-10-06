(function(){
  var out=[];
  var els=document.querySelectorAll("[role='menuitem'],button,li,div");
  for(var i=0;i<els.length;i++){
    var t=(els[i].innerText||"").trim();
    if(t && t.length<26 && /^(edit|rename|set default|use|open|change)$/i.test(t)) out.push(els[i].tagName+":"+t);
  }
  return JSON.stringify(out.slice(0,12));
})()
