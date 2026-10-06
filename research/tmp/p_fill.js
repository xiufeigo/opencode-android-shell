(function(){
  var inputs = document.querySelectorAll("input");
  var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;
  function setVal(el, v){
    setter.call(el, v);
    el.dispatchEvent(new Event("input", {bubbles:true}));
    el.dispatchEvent(new Event("change", {bubbles:true}));
  }
  setVal(inputs[1], "http://127.0.0.1:4095");
  setVal(inputs[3], "opencode");
  return JSON.stringify({addr: inputs[1].value, pwd: inputs[3].value.length});
})()
