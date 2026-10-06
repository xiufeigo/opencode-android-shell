(function(){
  var ins=document.querySelectorAll("input");
  var setter=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,"value").set;
  function setVal(el,v){
    setter.call(el,v);
    el.dispatchEvent(new Event("input",{bubbles:true}));
    el.dispatchEvent(new Event("change",{bubbles:true}));
  }
  setVal(ins[1], "http://localhost:4095");
  setVal(ins[3], "opencode");
  var els=document.querySelectorAll("button,[role='button']");
  var target=null;
  for(var i=0;i<els.length;i++){
    var t=(els[i].innerText||"").trim();
    if(t==="Add server" && els[i].getClientRects().length) target=els[i];
  }
  if(!target) return JSON.stringify({err:"no btn", addr:ins[1].value});
  return JSON.stringify({addr:ins[1].value, disabled:target.disabled||false});
})()
