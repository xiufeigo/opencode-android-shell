var sb=Array.prototype.slice.call(document.querySelectorAll('button')).filter(function(b){return (b.getAttribute('aria-label')||'')==='Send';})[0];
var r=sb?sb.getBoundingClientRect():null;
return {send: r?{x:Math.round(r.x+r.width/2),y:Math.round(r.y+r.height/2)}:null};
