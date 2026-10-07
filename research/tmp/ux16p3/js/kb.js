var ce=document.querySelector('[contenteditable="true"]');
var r=ce?ce.getBoundingClientRect():null;
var panel=ce?ce.closest('form')||ce.parentElement:null;
var pr=panel?panel.getBoundingClientRect():null;
return {ceBottom: r?Math.round(r.bottom):null, panelBottom: pr?Math.round(pr.bottom):null,
        vvH: window.visualViewport?Math.round(window.visualViewport.height):null,
        innerH: window.innerHeight, ok: r? (r.bottom <= (window.visualViewport?window.visualViewport.height:window.innerHeight)) : null};
