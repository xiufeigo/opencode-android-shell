(() => {
  const inputs = Array.from(document.querySelectorAll('input, textarea'));
  const setVal = (el, v) => {
    const proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    const setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
    setter.call(el, v);
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  };
  const byPh = (re) => inputs.find(el => re.test(el.getAttribute('placeholder') || ''));
  const urlEl = byPh(/localhost:4096/);
  const nameEl = byPh(/^Localhost$/i);
  const pwdEl = inputs.find(el => el.type === 'password');
  if (nameEl) setVal(nameEl, 'opencode');
  if (pwdEl) setVal(pwdEl, 'opencode');
  return JSON.stringify({
    url: urlEl ? urlEl.value : null,
    name: nameEl ? nameEl.value : null,
    pwdLen: pwdEl ? String(pwdEl.value).length : null
  });
})()
