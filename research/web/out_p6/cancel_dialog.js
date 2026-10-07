(() => {
  // Cancel the failed dialog first
  const cancel = Array.from(document.querySelectorAll('button')).find(b => (b.innerText || '').trim() === 'Cancel');
  if (cancel) cancel.click();
  return JSON.stringify({ cancelled: !!cancel });
})()
