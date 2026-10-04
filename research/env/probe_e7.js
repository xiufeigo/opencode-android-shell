// E7: narrow-screen bottom view bar / top sidebar-entry enumeration via CDP Runtime.evaluate
// Returns a JSON string (cdp_run_e6.ps1 prints string values verbatim).
(function () {
  var vh = window.innerHeight,
    vw = window.innerWidth,
    BAND = 120; // bottom band height in CSS px

  function computed(el) {
    try {
      return getComputedStyle(el);
    } catch (e) {
      return null;
    }
  }
  function isVisible(el) {
    var s = computed(el);
    if (!s) return false;
    if (s.display === "none" || s.visibility === "hidden") return false;
    if (parseFloat(s.opacity) === 0) return false;
    var r = el.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) return false;
    if (r.bottom <= 0 || r.top >= vh) return false; // fully outside the visual viewport
    return true;
  }
  function desc(el) {
    var r = el.getBoundingClientRect(),
      s = computed(el) || {};
    return {
      tag: el.tagName.toLowerCase(),
      slot: el.getAttribute("data-slot") || null,
      role: el.getAttribute("role") || null,
      aria: el.getAttribute("aria-label") || null,
      testid: el.getAttribute("data-testid") || null,
      cls: (typeof el.className === "string" ? el.className : "").slice(0, 110),
      text: (el.innerText || el.textContent || "").replace(/\s+/g, " ").trim().slice(0, 70),
      box: [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)],
      pos: s.position || null,
      z: s.zIndex || null
    };
  }
  function area(d) {
    return d.box[2] * d.box[3];
  }

  var all = Array.prototype.slice.call(document.querySelectorAll("body *"));
  var bottom = [],
    top = [],
    visTotal = 0;
  for (var i = 0; i < all.length; i++) {
    var el = all[i];
    if (!isVisible(el)) continue;
    visTotal++;
    var r = el.getBoundingClientRect();
    if (r.top >= vh - BAND) bottom.push(desc(el));
    if (r.bottom <= BAND) top.push(desc(el));
  }
  bottom.sort(function (a, b) {
    return area(b) - area(a);
  });
  top.sort(function (a, b) {
    return area(b) - area(a);
  });

  // Direct probes for the upstream narrow-screen nav slot + desktop side region
  function probe(sel) {
    var n = document.querySelectorAll(sel).length;
    var out = [];
    for (var k = 0; k < document.querySelectorAll(sel).length && k < 4; k++) {
      var e = document.querySelectorAll(sel)[k];
      var s = computed(e) || {},
        r = e.getBoundingClientRect();
      out.push({
        box: [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)],
        display: s.display,
        visibility: s.visibility,
        opacity: s.opacity,
        position: s.position,
        zIndex: s.zIndex,
        overflow: s.overflow,
        text: (e.innerText || "").replace(/\s+/g, " ").trim().slice(0, 120)
      });
    }
    return { count: n, nodes: out };
  }

  var out = {
    url: location.href,
    origin: location.origin,
    title: document.title,
    innerW: vw,
    innerH: vh,
    dpr: window.devicePixelRatio,
    visualViewportH: window.visualViewport ? Math.round(window.visualViewport.height) : null,
    scrollH: document.documentElement.scrollHeight,
    bodyScrollH: document.body ? document.body.scrollHeight : null,
    mq_desktop_768: window.matchMedia("(min-width: 768px)").matches,
    mq_mobile_767: window.matchMedia("(max-width: 767px)").matches,
    domTotal: document.querySelectorAll("body *").length,
    visibleTotal: visTotal,
    band: BAND,
    bandRangeCss: [vh - BAND, vh],
    // --- upstream narrow-screen view switcher ---
    "slot_session-mobile-view-navigation": probe('[data-slot="session-mobile-view-navigation"]'),
    "slot_session-mobile-view-tabs": probe('[data-slot="session-mobile-view-tabs"]'),
    "slot_session-review-toggle": probe('[data-slot="session-review-toggle"]'),
    "slot_mobile-panel": probe('[data-slot="mobile-panel"]'),
    "slot_session-timeline-scroll": probe('[data-slot="session-timeline-scroll"]'),
    // --- desktop side region ---
    aside: probe("aside"),
    aside_review_panel: probe("aside#review-panel"),
    nav: probe("nav"),
    // --- top region candidate sidebar/menu entry ---
    headerButtonsTop120: top.filter(function (d) {
      return d.tag === "button" || d.role === "button" || d.tag === "a" || d.slot;
    }),
    // --- generic interactive affordances in the bottom band (any band, not only visible-subtree) ---
    bottomBand: bottom.slice(0, 40),
    bottomBandCount: bottom.length,
    topBand: top.slice(0, 40),
    topBandCount: top.length
  };
  return JSON.stringify(out, null, 1);
})();
