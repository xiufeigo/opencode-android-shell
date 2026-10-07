// Find the Add server submit button inside the dialog (has a Cancel sibling) and click it
var bs = Array.prototype.slice.call(document.querySelectorAll('button'));
var submit = bs.filter(function(b){
  return (b.innerText||'').trim()==='Add server' && b.getBoundingClientRect().width>0;
});
var target = submit[submit.length-1];
if(!target) return {ok:false, why:'no submit button'};
var r = target.getBoundingClientRect();
target.click();
return {ok:true, cx:Math.round(r.x+r.width/2), cy:Math.round(r.y+r.height/2), w:Math.round(r.width), h:Math.round(r.height),
        inputs: Array.prototype.slice.call(document.querySelectorAll('input')).map(function(i){return {v:i.value, ph:i.placeholder};})};