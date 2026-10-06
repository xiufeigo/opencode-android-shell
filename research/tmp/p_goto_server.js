(function(){
  var btns = Array.prototype.filter.call(document.querySelectorAll("button"), function(x){
    return (x.innerText||"").trim()==="Server";
  });
  if(!btns.length) return "NO_SERVER_BTN";
  btns[0].click();
  return "clicked";
})()
