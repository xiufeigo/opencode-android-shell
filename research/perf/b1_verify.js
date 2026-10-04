// b1_verify.js — 身份探针：证明当前页面**确实**是 L0 极简页（不是官方 SPA，也没被弹回）
(function(){
  try{
    var t = document.title || '';
    var n = document.getElementsByTagName('*').length;
    var ta = document.querySelectorAll('textarea').length;
    var inp = document.querySelectorAll('input').length;
    var extScript = document.querySelectorAll('script[src]').length;
    var linkCss = document.querySelectorAll('link[rel=stylesheet]').length;
    var bodyTxt = (document.body && document.body.innerText || '').slice(0,120);
    return JSON.stringify({
      title: t, domNodes: n, textareas: ta, inputs: inp,
      externalScripts: extScript, stylesheets: linkCss,
      hasReactRootAttrs: document.querySelectorAll('[data-reactroot],[id=root],[id=app]').length,
      bodySample: bodyTxt
    });
  }catch(e){ return JSON.stringify({err:String(e)}); }
})()
