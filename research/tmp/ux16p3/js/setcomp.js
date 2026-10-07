var ce = document.querySelector('[contenteditable="true"]');
if(!ce) return {ok:false, why:'no contenteditable'};
ce.focus();
// select all existing content then replace
var sel = window.getSelection();
var range = document.createRange();
range.selectNodeContents(ce);
sel.removeAllRanges();
sel.addRange(range);
document.execCommand('delete');
document.execCommand('insertText', false, 'reply with the single word OK');
ce.dispatchEvent(new InputEvent('input', {bubbles:true, inputType:'insertText', data:'reply with the single word OK'}));
return {ok:true, txt:(ce.innerText||'').slice(0,120)};