var ins = Array.prototype.slice.call(document.querySelectorAll('input'));
function setVal(el, val){
  var proto = el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
  Object.getOwnPropertyDescriptor(proto,'value').set.call(el, val);
  el.dispatchEvent(new Event('input',{bubbles:true}));
  el.dispatchEvent(new Event('change',{bubbles:true}));
}
var byPh = {};
ins.forEach(function(i){ byPh[i.placeholder] = i; });
setVal(byPh['http://localhost:4096'] || ins[1], 'http://127.0.0.1:4095');
setVal(byPh['Localhost'] || ins[2], 'ux16p3');
setVal(byPh['password'] || ins[3], 'opencode');

// find submit: the button whose text is exactly 'Add server' AND has a 'Cancel' button nearby
var bs = Array.prototype.slice.call(document.querySelectorAll('button'));
var cancel = bs.filter(function(b){return (b.innerText||'').trim()==='Cancel' && b.getBoundingClientRect().width>0;})[0];
var submit = null;
if(cancel){
  var scope = cancel.closest('div');
  var cands = Array.prototype.slice.call((scope?scope.parentElement:document).querySelectorAll('button'))
    .filter(function(b){ return (b.innerText||'').trim()==='Add server' && b.getBoundingClientRect().width>0; });
  submit = cands[cands.length-1];
}
if(!submit){
  var all = bs.filter(function(b){return (b.innerText||'').trim()==='Add server' && b.getBoundingClientRect().width>0;});
  submit = all[all.length-1];
}
if(!submit) return {ok:false, why:'no submit'};
var r = submit.getBoundingClientRect();
submit.click();
return {ok:true, submitTxt:(submit.innerText||'').trim(), w:Math.round(r.width), h:Math.round(r.height),
        values: Array.prototype.slice.call(document.querySelectorAll('input')).map(function(i){return {v:i.value, ph:i.placeholder};})};