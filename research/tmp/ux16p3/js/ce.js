var ce=document.querySelector('[contenteditable="true"]');
return {txt:(ce?ce.innerText:'').slice(0,120), vv: window.visualViewport?{h:Math.round(window.visualViewport.height),off:Math.round(window.visualViewport.offsetTop)}:null};
