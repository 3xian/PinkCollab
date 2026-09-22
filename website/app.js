'use strict';

const demoGroups = {
  attention: { cards: [{ kind: 'attention-task', label: 'NEEDS YOUR INPUT', time: 'now', title: 'Review database migration', host: 'dev-server', path: 'projects/api', message: 'Ready to apply the migration.\nWould you like to proceed?' }], context: 'A decision from you keeps work moving.' },
  running: { cards: [{ kind: 'running-task', label: 'RUNNING', time: 'live', title: 'Fix the failing tests', host: 'home-pc', path: 'projects/web', message: 'Running the test suite…' }, { kind: 'running-task', label: 'RUNNING', time: 'live', title: 'Refactor the API routes', host: 'dev-server', path: 'projects/api' }], context: 'See what’s happening across your hosts.' },
  recent: { cards: [{ kind: 'recent-task', label: 'COMPLETED', time: '12m ago', title: 'Update project documentation', host: 'home-pc', path: 'projects/web' }, { kind: 'recent-task', label: 'COMPLETED', time: '1h ago', title: 'Investigate slow queries', host: 'dev-server', path: 'projects/api' }], context: 'Finished work stays in your recent tasks.' }
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
  panel.setAttribute('aria-labelledby', tab.id);
  panel.replaceChildren();
  for (const card of group.cards) {
    const article = element('article', `demo-task ${card.kind}`);
    const label = element('div', 'task-label');
    label.append(element('span', 'status-dot'), document.createTextNode(card.label), element('span', 'task-time', card.time));
    const path = element('p', 'task-path');
    path.append(document.createTextNode(card.host), element('span', '', '/'), document.createTextNode(card.path));
    article.append(label, element('h3', '', card.title), path);
    if (card.message) {
      const message = element('div', 'task-message');
      const arrow = element('span', '', '↳');
      arrow.setAttribute('aria-hidden', 'true');
      const text = element('p', '', card.message);
      text.style.whiteSpace = 'pre-line';
      message.append(arrow, text);
      article.append(message);
    }
    if (tab.dataset.group === 'attention') article.append(element('span', 'task-footnote', 'Continue the conversation in the app →'));
    panel.append(article);
  }
  panel.append(element('p', 'demo-context', group.context));
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
