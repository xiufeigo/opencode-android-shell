// E7 compact: is the narrow-screen bottom view bar present inside a SESSION?
(function () {
  var vh = window.innerHeight,
    vw = window.innerWidth;
  function cs(el) {
    try {
      return getComputedStyle(el);
    } catch (e) {
      return {};
    }
  }
  function box(el) {
    var r = el.getBoundingClientRect();
    return [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)];
  }
  function vis(el) {
    var s = cs(el),
      r = el.getBoundingClientRect();
    return (
      s.display !== "none" &&
      s.visibility !== "hidden" &&
      parseFloat(s.opacity) !== 0 &&
      r.width > 0 &&
      r.height > 0 &&
      r.bottom > 0 &&
      r.top < vh
    );
  }
  function one(sel) {
    var e = document.querySelector(sel);
    if (!e) return null;
    var s = cs(e);
    return {
      box: box(e),
      display: s.display,
      visibility: s.visibility,
      opacity: s.opacity,
      position: s.position,
      zIndex: s.zIndex,
      text: (e.innerText || "").replace(/\s+/g, " ").trim().slice(0, 140)
    };
  }
  function cnt(sel) {
    return document.querySelectorAll(sel).length;
  }

  // Any element whose top edge lives in the bottom 140px band
  var band = [];
  var all = document.querySelectorAll("body *");
  for (var i = 0; i < all.length; i++) {
    var e = all[i],
      r = e.getBoundingClientRect();
    if (vis(e) && r.top >= vh - 140) {
      var s = cs(e);
      band.push({
        tag: e.tagName.toLowerCase(),
        slot: e.getAttribute("data-slot"),
        aria: e.getAttribute("aria-label"),
        role: e.getAttribute("role"),
        sel: e.getAttribute("aria-selected"),
        text: (e.innerText || "").replace(/\s+/g, " ").trim().slice(0, 40),
        box: box(e),
        pos: s.position,
        z: s.zIndex
      });
    }
  }
  band.sort(function (a, b) {
    return b.box[2] * b.box[3] - a.box[2] * a.box[3];
  });

  // widest elements in the bottom band (candidates for a full-width bar)
  var wide = band.filter(function (d) {
    return d.box[2] >= vw * 0.8;
  });

  // The nav container's full subtree summary
  var nav = document.querySelector('[data-slot="session-mobile-view-navigation"]');
  var navSub = null;
  if (nav) {
    var nr = nav.getBoundingClientRect();
    navSub = {
      box: box(nav),
      display: cs(nav).display,
      children: Array.prototype.slice.call(nav.querySelectorAll("button,[role=tab],button")).map(function (b) {
        return {
          tag: b.tagName.toLowerCase(),
          slot: b.getAttribute("data-slot"),
          aria: b.getAttribute("aria-label"),
          sel: b.getAttribute("aria-selected"),
          text: (b.innerText || "").replace(/\s+/g, " ").trim().slice(0, 30),
          box: box(b)
        };
      }),
      topEdge: Math.round(nr.top),
      bottomEdge: Math.round(nr.bottom),
      distanceToViewportBottom: Math.round(vh - nr.bottom)
    };
  }

  return JSON.stringify(
    {
      url: location.href,
      innerW: vw,
      innerH: vh,
      dpr: window.devicePixelRatio,
      visualViewportH: window.visualViewport ? Math.round(window.visualViewport.height) : null,
      mq_desktop_768: window.matchMedia("(min-width: 768px)").matches,
      mq_mobile_767: window.matchMedia("(max-width: 767px)").matches,
      domTotal: all.length,
      COUNTS: {
        "session-mobile-view-navigation": cnt('[data-slot="session-mobile-view-navigation"]'),
        "session-mobile-view-tabs": cnt('[data-slot="session-mobile-view-tabs"]'),
        "session-review-toggle": cnt('[data-slot="session-review-toggle"]'),
        "mobile-panel": cnt('[data-slot="mobile-panel"]'),
        "session-timeline-scroll": cnt('[data-slot="session-timeline-scroll"]'),
        aside: cnt("aside"),
        "aside#review-panel": cnt("aside#review-panel"),
        nav: cnt("nav"),
        textarea: cnt("textarea"),
        "[contenteditable]": cnt("[contenteditable]")
      },
      MOBILE_NAV: one('[data-slot="session-mobile-view-navigation"]'),
      MOBILE_NAV_SUBTREE: navSub,
      SIDE_ASIDE: one("aside"),
      REVIEW_TOGGLE: one('[data-slot="session-review-toggle"]'),
      BOTTOM_BAND_140: band.slice(0, 12),
      bottomBandCount: band.length,
      BOTTOM_BAND_WIDE: wide.slice(0, 12)
    },
    null,
    1
  );
})();
