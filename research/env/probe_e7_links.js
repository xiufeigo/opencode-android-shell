(function () {
  var as = Array.prototype.slice.call(document.querySelectorAll('a[href*="/session"]'));
  var out = {
    url: location.href,
    innerW: window.innerWidth,
    innerH: window.innerHeight,
    mq_desktop_768: window.matchMedia("(min-width: 768px)").matches,
    linkCount: as.length,
    links: as.slice(0, 14).map(function (a) {
      var r = a.getBoundingClientRect();
      return {
        href: a.getAttribute("href"),
        text: (a.innerText || "").replace(/\s+/g, " ").trim().slice(0, 50),
        box: [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)]
      };
    })
  };
  return JSON.stringify(out, null, 1);
})();
