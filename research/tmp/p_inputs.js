(function(){
  var inputs = document.querySelectorAll("input");
  var info=[];
  for(var i=0;i<inputs.length;i++){
    info.push({i:i, type:inputs[i].type, ph:inputs[i].placeholder||"", val:inputs[i].value||""});
  }
  return JSON.stringify(info);
})()
