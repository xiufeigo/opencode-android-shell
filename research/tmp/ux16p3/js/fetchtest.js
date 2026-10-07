return fetch('http://127.0.0.1:4095/api/info', {credentials:'include'})
  .then(function(r){ return r.text().then(function(t){ return {ok:true, status:r.status, body:t.slice(0,200)}; }); })
  .catch(function(e){ return {ok:false, err:String(e)}; });