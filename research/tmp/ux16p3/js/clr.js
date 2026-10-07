var ce=document.querySelector('[contenteditable="true"]');
ce.focus();
var sel=window.getSelection(), r=document.createRange();
r.selectNodeContents(ce); sel.removeAllRanges(); sel.addRange(r);
document.execCommand('delete');
return {ok:true, txt:(ce.innerText||'')};
