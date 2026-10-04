(function () {
  var d = document, de = d.documentElement, b = d.body;
  var mv = d.querySelector('meta[name=viewport]');
  var vv = d.visualViewport;
  return JSON.stringify({
    title: d.title,
    origin: location.origin,
    href: location.href.slice(0, 120),
    nodeCount: d.getElementsByTagName('*').length,
    metaViewport: mv ? mv.getAttribute('content') : null,
    devicePixelRatio: d.defaultView.devicePixelRatio,
    innerW: d.defaultView.innerWidth,
    innerH: d.defaultView.innerHeight,
    outerW: d.defaultView.outerWidth,
    outerH: d.defaultView.outerHeight,
    screenW: d.defaultView.screen.width,
    screenH: d.defaultView.screen.height,
    vvScale: vv ? vv.scale : null,
    vvW: vv ? Math.round(vv.width * 100) / 100 : null,
    vvH: vv ? Math.round(vv.height * 100) / 100 : null,
    deScrollW: de.scrollWidth, deClientW: de.clientWidth,
    deScrollH: de.scrollHeight, deClientH: de.clientHeight,
    bodyScrollW: b ? b.scrollWidth : null,
    bodyClientW: b ? b.clientWidth : null,
    deRectW: Math.round(de.getBoundingClientRect().width * 100) / 100,
    text200: (b ? b.innerText : '').slice(0, 200)
  });
})()
