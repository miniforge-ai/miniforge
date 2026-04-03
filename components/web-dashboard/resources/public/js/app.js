/* Miniforge Web Dashboard - Client-side functionality
 * Theme switching, WebSocket management, and real-time updates
 */

// Theme management
(function initTheme() {
  // Load saved theme or default to dark
  const savedTheme = localStorage.getItem('miniforge-theme') || 'dark';
  document.documentElement.setAttribute('data-theme', savedTheme);
})();

function switchTheme(theme) {
  if (!['dark', 'light', 'high-contrast'].includes(theme)) {
    console.error('Invalid theme:', theme);
    return;
  }

  document.documentElement.setAttribute('data-theme', theme);
  localStorage.setItem('miniforge-theme', theme);

  // Emit custom event for other components to react
  window.dispatchEvent(new CustomEvent('theme-changed', { detail: { theme } }));
}

function cycleTheme() {
  const currentTheme = document.documentElement.getAttribute('data-theme') || 'dark';
  const themes = ['dark', 'light', 'high-contrast'];
  const currentIndex = themes.indexOf(currentTheme);
  const nextTheme = themes[(currentIndex + 1) % themes.length];
  switchTheme(nextTheme);
}

// Keyboard shortcut: Ctrl+Shift+T to cycle themes
document.addEventListener('keydown', (e) => {
  if (e.ctrlKey && e.shiftKey && e.key === 'T') {
    e.preventDefault();
    cycleTheme();
  }
});

// Control plane - send commands to workflows
function sendWorkflowCommand(command, params = {}) {
  if (!ws || ws.readyState !== WebSocket.OPEN) {
    console.error('WebSocket not connected');
    return;
  }

  const message = {
    command: command,
    params: params,
    timestamp: new Date().toISOString()
  };

  ws.send(JSON.stringify(message));
  console.log('Sent workflow command:', command);
}

// Filter management
const activeFilters = new Map();

function addFilterChip(label, type, value) {
  const filterKey = `${type}:${value}`;

  // Avoid duplicates
  if (activeFilters.has(filterKey)) {
    return;
  }

  activeFilters.set(filterKey, { label, type, value });

  // Create filter chip element
  const filterChipsContainer = document.getElementById('filter-chips');
  if (!filterChipsContainer) return;

  const chip = document.createElement('div');
  chip.className = 'filter-chip';
  chip.setAttribute('data-filter-key', filterKey);
  chip.innerHTML = `
    <span class="filter-label">${label}</span>
    <button class="filter-remove" onclick="window.miniforge.removeFilterChip('${filterKey}')" title="Remove filter">×</button>
  `;

  filterChipsContainer.appendChild(chip);

  // Close the filter modal after adding
  const modal = document.querySelector('.filter-modal');
  if (modal) {
    modal.remove();
  }

  // Trigger filter update
  applyFilters();
}

function removeFilterChip(filterKey) {
  activeFilters.delete(filterKey);

  const chip = document.querySelector(`[data-filter-key="${filterKey}"]`);
  if (chip) {
    chip.remove();
  }

  applyFilters();
}

function applyFilters() {
  // TODO: Implement actual filtering logic
  // For now, just log the active filters
  console.log('Active filters:', Array.from(activeFilters.entries()));

  // Trigger kanban board update
  dispatchRefresh();
}

// Post workflow command via HTTP (GraalVM-safe, no WebSocket needed)
function postWorkflowCommand(workflowId, command) {
  fetch('/api/workflow/' + workflowId + '/command', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ command: command })
  })
  .then(r => r.json())
  .then(data => {
    showToast('Command sent: ' + command, 'info', 3000);
    console.log('Command queued:', data);
  })
  .catch(err => {
    showToast('Failed to send command: ' + err.message, 'error');
    console.error('Error sending command:', err);
  });
}

function dispatchRefresh() {
  if (window.htmx && typeof window.htmx.trigger === 'function') {
    window.htmx.trigger(document.body, 'refresh');
  }
  document.body.dispatchEvent(new CustomEvent('refresh'));
}

let refreshTimer = null;
const workflowRefreshTimers = new Map();

function scheduleRefresh(delay = 150) {
  if (refreshTimer) {
    clearTimeout(refreshTimer);
  }
  refreshTimer = setTimeout(() => {
    refreshTimer = null;
    dispatchRefresh();
  }, delay);
}

function normalizeWorkflowId(event) {
  return event && (
    event['workflow/id']
    || event.workflow_id
    || event.workflowId
    || event['workflow-id']
  );
}

function escapeSelectorValue(value) {
  if (window.CSS && typeof window.CSS.escape === 'function') {
    return window.CSS.escape(String(value));
  }

  return String(value).replace(/(["\\.#:[\]])/g, '\\$1');
}

function refreshTarget(url, selector) {
  if (!window.htmx || typeof window.htmx.ajax !== 'function') {
    return;
  }

  const target = document.querySelector(selector);
  if (!target) {
    return;
  }

  window.htmx.ajax('GET', url, { target: target, swap: 'innerHTML' });
}

function scheduleWorkflowRefresh(workflowId, delay = 200) {
  if (!workflowId) {
    scheduleRefresh(delay);
    return;
  }

  const key = String(workflowId);
  if (workflowRefreshTimers.has(key)) {
    clearTimeout(workflowRefreshTimers.get(key));
  }

  workflowRefreshTimers.set(key, setTimeout(() => {
    workflowRefreshTimers.delete(key);

    const panelSelector = `#wf-panel-${escapeSelectorValue(key)}`;
    const detailSelector = `#workflow-detail-panel[data-workflow-id="${key}"]`;
    const eventsSelector = `#wf-events-${escapeSelectorValue(key)}`;

    refreshTarget(`/api/workflow/${encodeURIComponent(key)}/panel`, panelSelector);
    refreshTarget(`/api/workflow/${encodeURIComponent(key)}/panel`, detailSelector);
    refreshTarget(`/api/workflow/${encodeURIComponent(key)}/events`, eventsSelector);
    scheduleRefresh(0);
  }, delay));
}

function postJson(url) {
  return fetch(url, { method: 'POST' }).then((r) => r.json());
}

function apiErrorMessage(res, fallback) {
  return (res && (res.error || res.message)) || fallback;
}

function fleetAddRepo() {
  const repo = prompt('Repository (owner/name):');
  if (!repo) return;

  postJson('/api/fleet/repos/add?repo=' + encodeURIComponent(repo))
    .then((res) => {
      if (res.success) {
        const prefix = res['added?'] ? 'Added: ' : 'Already configured: ';
        showToast(prefix + (res.repo || repo), 'success');
        dispatchRefresh();
        return;
      }
      showToast('Error: ' + apiErrorMessage(res, 'Unable to add repo'), 'error');
    })
    .catch((err) => showToast('Error: ' + err.message, 'error'));
}

function fleetDiscoverRepos() {
  const owner = prompt('Owner/org (leave blank for current user):', '') || '';
  const suffix = owner.trim() ? ('?owner=' + encodeURIComponent(owner.trim())) : '';

  postJson('/api/fleet/repos/discover' + suffix)
    .then((res) => {
      if (res.success) {
        showToast(
          'Discovered ' + (res.discovered || 0) + ' repos, added ' + (res.added || 0) + '.',
          'success'
        );
        dispatchRefresh();
        return;
      }
      showToast('Error: ' + apiErrorMessage(res, 'Repository discovery failed'), 'error');
    })
    .catch((err) => showToast('Error: ' + err.message, 'error'));
}

function fleetSyncPrs() {
  postJson('/api/fleet/prs/sync')
    .then((res) => {
      if (res.success) {
        const summary = res.summary || {};
        showToast(
          'Synced repos: ' + (res.synced || 0) + ', tracked PRs: ' + (summary['tracked-prs'] || 0),
          'success'
        );
      } else {
        showToast(
          'Error: ' + apiErrorMessage(res, 'Sync failed for ' + (res.failed || 0) + ' repo(s)'),
          'error'
        );
      }
      dispatchRefresh();
    })
    .catch((err) => showToast('Error: ' + err.message, 'error'));
}

function fleetDiscoverAndSync() {
  postJson('/api/fleet/repos/discover')
    .then((res) => {
      if (!res.success) {
        throw new Error(apiErrorMessage(res, 'Discovery failed'));
      }
      return postJson('/api/fleet/prs/sync');
    })
    .then((syncRes) => {
      if (syncRes.success) {
        showToast('Discovery and PR sync completed.', 'success');
      } else {
        showToast('Sync error: ' + apiErrorMessage(syncRes, 'Unable to synchronize PRs'), 'error');
      }
      dispatchRefresh();
    })
    .catch((err) => showToast('Error: ' + err.message, 'error'));
}

// Export functions for global use
window.miniforge = {
  switchTheme,
  cycleTheme,
  getCurrentTheme: () => document.documentElement.getAttribute('data-theme') || 'dark',
  sendWorkflowCommand,
  postWorkflowCommand,
  addFilterChip,
  removeFilterChip,
  getActiveFilters: () => Array.from(activeFilters.entries()),
  queryEvents,
  fleet: {
    addRepo: fleetAddRepo,
    discoverRepos: fleetDiscoverRepos,
    syncPrs: fleetSyncPrs,
    discoverAndSync: fleetDiscoverAndSync
  }
};

// WebSocket connection management
let ws = null;
let reconnectTimer = null;
let reconnectAttempts = 0;
// Exponential backoff with full jitter, no hard cap on attempts
const BASE_RECONNECT_DELAY = 1000;   // 1s initial
const MAX_RECONNECT_DELAY = 30000;   // 30s cap

function connectWebSocket() {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const wsUrl = `${protocol}//${window.location.host}/ws`;

  try {
    ws = new WebSocket(wsUrl);

    ws.onopen = () => {
      console.log('WebSocket connected');
      reconnectAttempts = 0;
      updateConnectionStatus('connected');
      // Request current state to catch up on events missed while disconnected
      try {
        ws.send(JSON.stringify({ action: 'refresh' }));
      } catch (e) {
        console.error('Error sending refresh request:', e);
      }
    };

    ws.onclose = () => {
      console.log('WebSocket disconnected');
      updateConnectionStatus('disconnected');
      scheduleReconnect();
    };

    ws.onerror = (error) => {
      console.error('WebSocket error:', error);
      updateConnectionStatus('error');
    };

    ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        handleWebSocketMessage(data);
      } catch (e) {
        console.error('Error parsing WebSocket message:', e);
      }
    };
  } catch (e) {
    console.error('Error creating WebSocket:', e);
    updateConnectionStatus('error');
    scheduleReconnect();
  }
}

function scheduleReconnect() {
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
  }

  reconnectAttempts++;
  updateConnectionStatus('reconnecting');
  // Exponential backoff capped at MAX_RECONNECT_DELAY, with full jitter
  const expDelay = BASE_RECONNECT_DELAY * Math.pow(2, Math.min(reconnectAttempts, 5));
  const cappedDelay = Math.min(MAX_RECONNECT_DELAY, expDelay);
  const delay = Math.random() * cappedDelay;
  console.log(`Reconnecting in ${Math.round(delay)}ms (attempt ${reconnectAttempts})`);
  reconnectTimer = setTimeout(connectWebSocket, delay);
}

function updateConnectionStatus(status) {
  const statusDot = document.getElementById('ws-indicator');
  if (statusDot) {
    statusDot.className = 'status-dot';
    statusDot.classList.add(status);
  }

  const statusText = document.getElementById('ws-text');
  if (statusText) {
    const statusLabels = {
      connected: 'Connected',
      disconnected: 'Disconnected',
      reconnecting: 'Reconnecting...',
      error: 'Error'
    };
    statusText.textContent = statusLabels[status] || status;
  }
}

function handleWebSocketMessage(data) {
  if (!data) return;

  // Raw event (no envelope wrapper) — forward directly
  if (!data.type && (data['event/type'] || data.event_type)) {
    handleWorkflowEvent(data);
    return;
  }

  const type = data.type;
  switch (type) {
    case 'init':
      console.log('WebSocket connected, SSR data is current');
      // Light refresh after short delay to pick up events missed during page load
      scheduleRefresh(500);
      break;

    case 'state':
      console.log('State update:', data.data);
      scheduleRefresh();
      break;

    case 'event': {
      // Unwrap envelope — prefer the inner event, fall back to envelope itself.
      // Overlay envelope's pre-resolved metadata since Cheshire strips namespaces
      // from inner keyword map keys (e.g. :event/type → "type" not "event/type").
      var innerEvent = data.data || data.event || data;
      if (data.event_type) {
        innerEvent.event_type = innerEvent.event_type || data.event_type;
      }
      if (data['event-type']) {
        innerEvent['event-type'] = innerEvent['event-type'] || data['event-type'];
      }
      if (data.workflow_id) {
        innerEvent.workflow_id = innerEvent.workflow_id || data.workflow_id;
      }
      if (data['workflow-id']) {
        innerEvent['workflow-id'] = innerEvent['workflow-id'] || data['workflow-id'];
      }
      handleWorkflowEvent(innerEvent);
      break;
    }

    default:
      console.log('Unknown message type:', type, data);
  }
}

// Toast notification system
function showToast(message, type = 'info', duration = 5000) {
  let container = document.getElementById('toast-container');
  if (!container) {
    container = document.createElement('div');
    container.id = 'toast-container';
    container.style.cssText = 'position:fixed;top:60px;right:16px;z-index:9999;display:flex;flex-direction:column;gap:8px;';
    document.body.appendChild(container);
  }

  const toast = document.createElement('div');
  toast.className = `toast toast-${type}`;
  toast.style.cssText = 'padding:10px 16px;border-radius:6px;font-size:13px;color:#fff;opacity:0;transition:opacity 0.3s;max-width:360px;box-shadow:0 2px 8px rgba(0,0,0,0.3);';

  const colors = { info: '#2196F3', success: '#4CAF50', error: '#f44336', warning: '#ff9800' };
  toast.style.background = colors[type] || colors.info;
  toast.textContent = message;

  container.appendChild(toast);
  requestAnimationFrame(() => { toast.style.opacity = '1'; });

  setTimeout(() => {
    toast.style.opacity = '0';
    setTimeout(() => toast.remove(), 300);
  }, duration);
}

// Event type normalization — handles all envelope key variants and strips
// leading colons from Clojure keyword serialization
function normalizeEventType(event) {
  var raw = event['event/type'] || event.event_type || event['event-type'] || event.eventType || '';
  raw = String(raw);
  return raw.charAt(0) === ':' ? raw.substring(1) : raw;
}

// Stream buffers for accumulating agent chunks per workflow
var streamBuffers = {};

// Update the live event banner (top-of-page activity ticker)
function updateEventBanner(eventType, event) {
  var banner = document.getElementById('event-banner');
  if (!banner) return;

  var icons = {
    'workflow/started': '\u{1F680}', 'workflow/phase-started': '\u25B6',
    'workflow/phase-completed': '\u2713', 'workflow/completed': '\u2705',
    'workflow/failed': '\u274C', 'agent/started': '\u{1F916}',
    'agent/completed': '\u{1F916}', 'agent/chunk': '\u{1F4AC}',
    'gate/passed': '\u{1F513}', 'gate/failed': '\u{1F512}'
  };

  var icon = icons[eventType] || '\u{1F4E1}';
  var phase = event['workflow/phase'] || event['phase'] || '';
  if (typeof phase === 'string' && phase.charAt(0) === ':') {
    phase = phase.substring(1);
  }
  var message = eventType + (phase ? ' \u00B7 ' + phase : '');

  // Build DOM node safely (no innerHTML)
  var item = document.createElement('div');
  item.className = 'event-banner-item';

  var iconSpan = document.createElement('span');
  iconSpan.className = 'event-icon';
  iconSpan.textContent = icon;

  var textSpan = document.createElement('span');
  textSpan.className = 'event-text';
  textSpan.textContent = message;

  item.appendChild(iconSpan);
  item.appendChild(textSpan);

  // Keep last 5 items
  while (banner.children.length >= 5) {
    banner.removeChild(banner.firstChild);
  }
  banner.appendChild(item);
}

// Update streaming preview panel with latest agent chunk
function updateStreamingPreview(workflowId, chunk) {
  if (!workflowId || !chunk) return;

  var key = String(workflowId);
  if (!streamBuffers[key]) {
    streamBuffers[key] = '';
  }
  // Accept both namespaced (from serialize-for-json) and legacy key forms
  var text = chunk['chunk/delta'] || chunk['chunk/text'] || chunk.delta || chunk.text || chunk.content || '';
  streamBuffers[key] = (streamBuffers[key] + text).slice(-500);

  var selector = '#wf-streaming-' + escapeSelectorValue(key);
  var el = document.querySelector(selector);
  if (el) {
    el.textContent = streamBuffers[key];
  }
}

// Query historical events from the /api/events endpoint
function queryEvents(params) {
  var qs = Object.keys(params || {}).map(function(k) {
    return encodeURIComponent(k) + '=' + encodeURIComponent(params[k]);
  }).join('&');
  return fetch('/api/events' + (qs ? '?' + qs : ''))
    .then(function(r) { return r.json(); });
}

// Handle individual workflow events
function handleWorkflowEvent(event) {
  if (!event) return;

  var eventType = normalizeEventType(event);
  var workflowId = normalizeWorkflowId(event);

  // Update the live event banner for all event types
  if (eventType) {
    updateEventBanner(eventType, event);
  }

  switch (eventType) {
    case 'workflow/started':
      showToast('Workflow started: ' + (event['workflow/spec']?.name || 'unknown'), 'info');
      break;

    case 'workflow/phase-started':
      showToast('Phase started: ' + (event['workflow/phase'] || 'unknown'), 'info');
      break;

    case 'workflow/phase-completed': {
      var outcome = event['phase/outcome'] || 'completed';
      var toastType = outcome === 'success' ? 'success' : 'warning';
      showToast('Phase completed: ' + (event['workflow/phase'] || 'unknown'), toastType);
      break;
    }

    case 'workflow/completed':
      showToast('Workflow completed', 'success');
      break;

    case 'workflow/failed':
      showToast('Workflow failed: ' + (event['workflow/failure-reason'] || 'unknown error'), 'error', 8000);
      break;

    case 'agent/started':
    case 'agent/completed':
    case 'agent/status':
      // Agent lifecycle — refresh panels to show activity
      break;

    // agent/chunk events are high-frequency — don't toast, just stream + debounced refresh
    case 'agent/chunk':
      updateStreamingPreview(workflowId, event);
      scheduleWorkflowRefresh(workflowId, 150);
      return;

    case 'gate/passed':
    case 'gate/failed':
      // Gate events — refresh to update gate status indicators
      break;

    default:
      break;
  }

  scheduleWorkflowRefresh(workflowId, 75);
}

// Initialize WebSocket on page load
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', connectWebSocket);
} else {
  connectWebSocket();
}

// Clean up on page unload
window.addEventListener('beforeunload', () => {
  if (ws) {
    ws.close();
  }
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
  }
});
