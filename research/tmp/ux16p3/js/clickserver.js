var btns = Array.prototype.slice.call(document.querySelectorAll('button'));
var b = btns.filter(function(e){ return (e.textContent||'').trim()==='Server'; })[0];
if(!b) return {ok:false, why:'no Server button'};
b.scrollIntoView({block:'center'});
// force-open even if 0x0 (hidden tab panel) by dispatching click
b.click();
var r=b.getBoundingClientRect();
return {ok:true, w:Math.round(r.width), h:Math.round(r.height), x:Math.round(r.x), y:Math.round(r.y),
        cls:(b.className||'').toString().slice(0,80), aria:b.getAttribute('aria-selected')};