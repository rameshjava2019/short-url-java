// Copy-to-clipboard for the created short link. Everything else is server-rendered.
(function () {
  const copyButton = document.getElementById('copy');
  if (!copyButton) return;

  copyButton.addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText(copyButton.dataset.url);
      copyButton.textContent = 'Copied!';
      setTimeout(() => { copyButton.textContent = 'Copy'; }, 1500);
    } catch {
      copyButton.textContent = 'Copy failed';
    }
  });
})();
