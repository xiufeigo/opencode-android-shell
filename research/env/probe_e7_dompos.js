// E7: where in the DOM does the narrow-screen view switcher sit, and is the composer/dock also there?
(function () {
  var vh = window.innerHeight;
  var nav = document.querySelector('[data-slot="session-mobile-view-navigation"]');
  var chain = [];
  var e = nav;
  while (e && e !== document.body) {
    var s = getComputedStyle(e),
      r = e.getBoundingClientRect();
    chain.push({
      tag: e.tagName.toLowerCase(),
      slot: e.getAttribute("data-slot"),
      cls: (typeof e.className === "string" ? e.className : "").slice(0, 90),
      box: [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)],
      display: s.display,
      flexDir: s.flexDirection,
      order: s.order,
      pos: s.position
    });
    e = e.parentElement;
  }
  // index of the nav among its parent's children + summary of siblings
  var parent = nav && nav.parentElement;
  var sibs = [];
  if (parent) {
    for (var i = 0; i < parent.children.length; i++) {
      var c = parent.children[i],
        r = c.getBoundingClientRect(),
        s = getComputedStyle(c);
      sibs.push({
        i: i,
        isNav: c === nav,
        tag: c.tagName.toLowerCase(),
        slot: c.getAttribute("data-slot"),
        aria: c.getAttribute("aria-label"),
        text: (c.innerText || "").replace(/\s+/g, " ").trim().slice(0, 30),
        box: [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)],
        display: s.display
      });
    }
  }
  // every element that looks like a bottom dock / bar, anywhere
  var dockish = [];
  var all = document.querySelectorAll("body *");
  for (var j = 0; j < all.length; j++) {
    var el = all[j],
      r = el.getBoundingClientRect();
    var slot = el.getAttribute("data-slot") || "";
    if (/dock|bottom|nav|tab/i.test(slot) && r.width > 100 && r.height > 20) {
      dockish.push({
        tag: el.tagName.toLowerCase(),
        slot: slot,
        box: [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)]
      });
    }
  }
  return JSON.stringify(
    {
      innerH: vh,
      innerW: window.innerWidth,
      navFound: !!nav,
      ancestorChain: chain,
      parentChildCount: parent ? parent.children.length : null,
      siblings: sibs,
      dockLikeSlots: dockish
    },
    null,
    1
  );
})();
