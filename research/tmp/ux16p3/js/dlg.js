var bs = Array.prototype.slice.call(document.querySelectorAll('button')).filter(function(b){
  var r=b.getBoundingClientRect(); return r.width>0&&r.height>0;
}).map(function(b){
  var r=b.getBoundingClientRect();
  return {txt:(b.innerText||'').replace(/\s+/g,' ').trim().slice(0,30),
          x:Math.round(r.x+r.width/2), y:Math.round(r.y+r.height/2)};
});
return {buttons:bs,
        inputs: Array.prototype.slice.call(document.querySelectorAll('input')).map(function(i){return {v:i.value, ph:i.placeholder};})};