(function(){
  var t=document.querySelectorAll('[role=tablist]');
  for(var i=0;i<t.length;i++){if(!t[i].closest('header')){t[i].setAttribute('role','tablist-ux3probe');}}
  var b=document.querySelector('button[aria-label="New terminal"]');
  if(!b)return 'NO_BTN';
  var before=document.querySelectorAll('[role=tab]').length;
  b.click();
  return 'clicked, tabsBefore='+before;
})()
