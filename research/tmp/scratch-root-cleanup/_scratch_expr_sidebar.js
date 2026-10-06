(function () {
  const res = {};
  const sb = document.querySelector('.settings-sidebar');
  if (sb) {
    const cs = getComputedStyle(sb);
    const r = sb.getBoundingClientRect();
    res.sidebar = { display: cs.display, visibility: cs.visibility, width: cs.width, height: cs.height, position: cs.position, transform: cs.transform, classes: sb.className, rect: { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) } };
    // 侧栏里所有 nav 按钮文本（即使不可见）
    res.navButtons = [];
    sb.querySelectorAll('button').forEach(b => res.navButtons.push((b.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 40)));
  } else res.sidebar = 'NO .settings-sidebar';
  // 找能切换侧栏的按钮
  res.toggles = [];
  document.querySelectorAll('button,[role=button]').forEach(n => {
    const a = (n.getAttribute('aria-label') || '') + ' ' + (n.getAttribute('title') || '') + ' ' + (n.getAttribute('data-state') || '');
    if (/menu|nav|sidebar|toggle|panel|settings/i.test(a)) res.toggles.push({ aria: n.getAttribute('aria-label'), title: n.getAttribute('title'), state: n.getAttribute('data-state'), text: (n.innerText || '').trim().slice(0, 30) });
  });
  // 所有带 settings 前缀的类名，看布局
  res.settingsClasses = [];
  document.querySelectorAll('[class*=settings]').forEach(n => res.settingsClasses.push(n.tagName.toLowerCase() + '.' + n.className.toString().split(/\s+/).slice(0, 3).join('.')));
  res.settingsClasses = [...new Set(res.settingsClasses)].slice(0, 25);
  return JSON.stringify(res, null, 1);
})()
