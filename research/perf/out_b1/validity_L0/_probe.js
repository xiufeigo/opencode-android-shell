(function(){
 try{
  var t=document.title||'';
  var ta=document.querySelector('textarea');
  var res={title:t, docScrollTop:document.scrollingElement?document.scrollingElement.scrollTop:-1,
           winScrollY:window.scrollY||0, bodyScrollTop:document.body?document.body.scrollTop:-1};
  if(ta) res.textareaScrollTop=ta.scrollTop, res.textareaScrollHeight=ta.scrollHeight, res.textareaClientHeight=ta.clientHeight;
  // 官方 UI 的滚动容器：找 scrollHeight>clientHeight 的最大可滚动节点
  var best=null;
  var all=document.querySelectorAll('div,main,section,ul');
  for(var i=0;i<all.length;i++){var e=all[i];
    if(e.scrollHeight-e.clientHeight>50){ if(!best||e.scrollHeight>best.sh) best={sh:e.scrollHeight,ch:e.clientHeight,st:e.scrollTop,cls:(e.className||'').toString().slice(0,40)};}}
  res.biggestScroller=best;
  res.nodes=document.getElementsByTagName('*').length;
  return JSON.stringify(res);
 }catch(e){return JSON.stringify({err:String(e)});}
})()
