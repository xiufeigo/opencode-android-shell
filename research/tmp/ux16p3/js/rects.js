var ce=document.querySelector('[contenteditable="true"]');
var cr=ce?ce.getBoundingClientRect():null;
var sb=Array.prototype.slice.call(document.querySelectorAll('button')).filter(function(b){return (b.getAttribute('aria-label')||'')==='Send';})[0];
var sr=sb?sb.getBoundingClientRect():null;
return {composer: cr?{x:Math.round(cr.x+cr.width/2),y:Math.round(cr.y+cr.height/2),w:Math.round(cr.width),h:Math.round(cr.height)}:null,
        send: sr?{x:Math.round(sr.x+sr.width/2),y:Math.round(sr.y+sr.height/2)}:null, vv:window.innerHeight};
