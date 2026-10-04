JSON.stringify({
  documentTitle: document.title,
  locationOrigin: location.origin,
  locationHref: location.href,
  domNodeCount: document.getElementsByTagName('*').length,
  bodyChildCount: document.body ? document.body.children.length : -1,
  readyState: document.readyState,
  userAgent: navigator.userAgent,
  ocFacadePresent: typeof window.oc,
  visibleTextFirst200: (document.body ? (document.body.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 200) : ''),
  paintEntries: performance.getEntriesByType('paint').map(function (e) { return e.name + '=' + Math.round(e.startTime); }),
  dcl_ms: Math.round(performance.timing.domContentLoadedEventEnd - performance.timing.navigationStart),
  loadEvent_ms: Math.round(performance.timing.loadEventEnd - performance.timing.navigationStart),
  navType: (performance.getEntriesByType('navigation')[0] || {}).type || 'n/a'
}, null, 1);
