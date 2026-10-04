(function(){
  try{
    var t=document.title||'';
    var body=(document.body&&document.body.innerText||'').slice(0,300);
    var n=document.getElementsByTagName('*').length;
    var pass=document.querySelectorAll('input[type=password]').length;
    // 真正的原生口令对话框签名：input[type=password] 存在，或正按钮文案"保存并连接"作为独立按钮存在。
    var dialogBtn=Array.prototype.some.call(document.querySelectorAll('button'),function(b){
       return (b.innerText||'').trim()==='保存并连接';});
    // 宽松关键词命中（仅作诊断，**不作判据**：用户自己的会话标题里就含"连接"等词）
    var loose=null, m=body+t.match(/[^\n]{0,20}(保存并连接|密码|password|sign in|连接)[^\n]{0,20}/i);
    if(m) loose=m[0];
    return JSON.stringify({title:t,body:body,nodes:n,passwordInputs:pass,
       dialogSaveBtn:dialogBtn, looseKeywordHit:loose,
       looksLikeLoginDialog:(pass>0||dialogBtn)});
  }catch(e){return JSON.stringify({err:String(e)});}
})()
