var d = document;
var rows = Array.from(d.querySelectorAll('a,li,[role="row"],[role="option"]')).slice(0,40).map(function(e){
  var r = e.getBoundingClientRect();
  return (e.innerText||'').replace(/\n/g,' | ').slice(0,60) + ' @' + Math.round(r.x) + ',' + Math.round(r.y) + ' ' + Math.round(r.width) + 'x' + Math.round(r.height);
});
JSON.stringify({url: location.href, title: d.title, bodyStart: d.body.innerText.slice(0,300), rows: rows});
