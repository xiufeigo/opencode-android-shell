var out=[];
Array.prototype.slice.call(document.querySelectorAll('span,div')).forEach(function(e){
  var r=e.getBoundingClientRect();
  if(r.width>0&&r.width<=14&&r.height>0&&r.height<=14){
    var bg=getComputedStyle(e).backgroundColor;
    if(bg&&bg!=='rgba(0, 0, 0, 0)') out.push({bg:bg,x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width)});
  }
});
return {dots:out.slice(0,10)};
