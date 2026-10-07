var paths = ['/api/info','/api/session','/api/event','/api/config','/api/app'];
var out = {};
var i = 0;
function next(){
  if(i >= paths.length) return out;
  var p = paths[i++];
  return fetch(p).then(function(r){
    return r.text().then(function(t){ out[p] = {status:r.status, body:t.slice(0,180)}; });
  }).catch(function(e){ out[p] = {err:String(e)}; })
    .then(next);
}
return next();