(() => JSON.stringify({
  colorSchemeDataset: document.documentElement.dataset.colorScheme,
  themeDataset: document.documentElement.dataset.theme,
  bg: getComputedStyle(document.documentElement).backgroundColor,
  lsColorScheme: localStorage.getItem('opencode-color-scheme'),
  lsServer: localStorage.getItem('opencode.global.dat:server'),
  url: location.href
}, null, 1))()
