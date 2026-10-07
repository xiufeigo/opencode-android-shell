var els=Array.prototype.slice.call(document.querySelectorAll('textarea,[contenteditable="true"],input[type="text"]')).map(function(e){var r=e.getBoundingClientRect();return {tag:e.tagName,ph:e.placeholder||e.getAttribute('data-placeholder')||'',x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height),vis:r.width>0&&r.height>0};});
return {els:els.slice(0,10)};
