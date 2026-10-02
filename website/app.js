'use strict';

const copyStatus = document.querySelector('#copy-status');
function copyLabel(button) {
  const heading = button.closest('.install-route')?.querySelector('h3');
  return heading ? `${heading.textContent} commands` : 'Commands';
}
function selectCommand(command) {
  const range = document.createRange();
  range.selectNodeContents(command);
  const selection = window.getSelection();
  selection.removeAllRanges();
  selection.addRange(range);
}
for (const button of document.querySelectorAll('.copy-button')) {
  button.addEventListener('click', async () => {
    const command = document.getElementById(button.dataset.copy);
    if (!command) return;
    const label = copyLabel(button);
    selectCommand(command);
    let copied = false;
    try {
      await navigator.clipboard.writeText(command.textContent);
      copied = true;
    } catch {
      // Older or permission-blocked browsers: the highlighted selection above is
      // the fallback, and execCommand copies it when it is still available.
      try { copied = document.execCommand('copy'); } catch { copied = false; }
    }
    button.querySelector('.copy-label').textContent = copied ? 'Copied' : 'Copy';
    button.querySelector('[aria-hidden]').textContent = copied ? '✓' : '⧉';
    copyStatus.textContent = copied
      ? `${label} copied.`
      : `Press Ctrl/Cmd+C to copy the highlighted ${label.toLowerCase()}.`;
  });
}
