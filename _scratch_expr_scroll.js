(function(){
  const cands=[];
  document.querySelectorAll('*').forEach(n=>{
    if(n.scrollHeight>n.clientHeight+40 && n.clientHeight>300) cands.push(n);
  });
  const info=cands.map(n=>({tag:n.tagName.toLowerCase(),aria:n.getAttribute('aria')||'',cls:(n.className||'').toString().slice(0,60),sh:n.scrollHeight,ch:n.clientHeight,st:n.scrollTop}));
  // 滚到最底，找 Server 分区
  cands.forEach(n=>{n.scrollTop=n.scrollHeight});
  return JSON.stringify({containers:info, text:(document.body.innerText||'').replace(/\n{2,}/g,'\n').trim().slice(-1200)},null,1);
})()
