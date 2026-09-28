'use strict';

const demoGroups = {
  running: { prompt: 'Fix the failing tests and explain what changed.', reply: 'I’ll reproduce the failure, check the affected code, and run the tests after the fix.', tool: 'npm test', result: 'Running…', placeholder: 'Steer OMP…' },
  recent: { prompt: 'Update the API setup instructions.', reply: 'Updated the setup instructions with the required environment variables and a working request example.', tool: 'git diff --stat', result: 'README.md | 18 ++++++++++++------', placeholder: 'Send another prompt…' }
};
const tabs = [...document.querySelectorAll('.task-tab')];
const panel = document.querySelector('#demo-tasks');
function element(tag, className, text) {
  const node = document.createElement(tag);
  node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}
function activateTab(tab) {
  const group = demoGroups[tab.dataset.group];
  if (!group) return;
  for (const item of tabs) {
    const active = item === tab;
    item.classList.toggle('active', active);
    item.setAttribute('aria-selected', String(active));
    item.tabIndex = active ? 0 : -1;
  }
  tab.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'instant' });
  panel.setAttribute('aria-labelledby', tab.id);
  const tool = element('div', 'demo-tool-call');
  tool.append(element('strong', '', 'bash'), element('code', '', group.tool), element('span', '', group.result));
  panel.replaceChildren(element('p', 'demo-user-message', group.prompt), element('p', 'demo-assistant-message', group.reply), tool);
  document.querySelector('#demo-placeholder').firstChild.textContent = group.placeholder + ' ';
}
for (const tab of tabs) {
  tab.addEventListener('click', () => activateTab(tab));
  tab.addEventListener('keydown', event => {
    const index = tabs.indexOf(tab);
    let target;
    if (event.key === 'ArrowRight') target = tabs[(index + 1) % tabs.length];
    if (event.key === 'ArrowLeft') target = tabs[(index + tabs.length - 1) % tabs.length];
    if (event.key === 'Home') target = tabs[0];
    if (event.key === 'End') target = tabs[tabs.length - 1];
    if (target) { event.preventDefault(); activateTab(target); target.focus(); }
  });
}

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
    copyStatus.textContent = copied
      ? `${label} copied.`
      : `Press Ctrl/Cmd+C to copy the highlighted ${label.toLowerCase()}.`;
  });
}
