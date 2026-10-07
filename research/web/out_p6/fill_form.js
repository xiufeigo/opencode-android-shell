(() => {
  const out = [];
  const inputs = Array.from(document.querySelectorAll('input, textarea'));
  const setVal = (el, v) => {
    const proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    const setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
    setter.call(el, v);
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  };
  inputs.forEach((el, i) => {
    const ph = el.getAttribute('placeholder') || '';
    const type = el.getAttribute('type') || '';
    const name = el.getAttribute('name') || '';
    out.push({ i, ph, type, name, before: el.type === 'password' ? '(pwd)' : el.value });
    if (type === 'password') { setVal(el, 'opencode'); return; }
    if (/url|address|localhost/i.test(ph)) setVal(el, 'http://127.0.0.1:4095');
    else if (/name|display/i.test(ph)) setVal(el, 'opencode');
  });
  const after = inputs.map((el, i) => ({ i, val: el.type === 'password' ? '(pwd)[' + String(el.value).length + ']' : el.value }));
  const btns = Array.from(document.querySelectorAll('[data-component=dialog-v2] button')).map(b => (b.innerText || '').trim());
  return JSON.stringify({ inputsFound: out, after, dialogButtons: btns }, null, 1);
})()
