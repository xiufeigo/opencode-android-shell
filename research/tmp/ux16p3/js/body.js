return {url:location.href, body:(document.body.innerText||'').replace(/\s+/g,' ').slice(0,300)};
