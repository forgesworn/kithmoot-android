// Apply the saved appearance before styles load. No signer or board data is read.
(() => {
  let preference = 'system';
  try {
    const saved = localStorage.getItem('girnel-theme');
    if (saved === 'light' || saved === 'dark') preference = saved;
  } catch { /* Follow the system when storage is unavailable. */ }
  document.documentElement.dataset.themePreference = preference;
  document.documentElement.dataset.theme = preference === 'system'
    ? (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
    : preference;
})();
