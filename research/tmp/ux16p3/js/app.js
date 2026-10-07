var bs=Array.prototype.slice.call(document.querySelectorAll('button')).filter(function(b){return (b.textContent||'').trim()==='Appearance' && b.getBoundingClientRect().width>0;});
if(bs.length){bs[0].click(); return {clicked:true};}
return {clicked:false};
