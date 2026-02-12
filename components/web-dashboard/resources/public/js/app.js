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
  document.body.dispatchEvent(new CustomEvent('refresh'));
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

// Export functions for global use
window.miniforge = {
  switchTheme,
  cycleTheme,
  getCurrentTheme: () => document.documentElement.getAttribute('data-theme') || 'dark',
  sendWorkflowCommand,
  postWorkflowCommand,
  addFilterChip,
  removeFilterChip,
  getActiveFilters: () => Array.from(activeFilters.entries())
};

// WebSocket connection management
let ws = null;
let reconnectTimer = null;
let reconnectAttempts = 0;
const MAX_RECONNECT_ATTEMPTS = 10;
const RECONNECT_DELAY = 3000;

function connectWebSocket() {
  const wsUrl = `ws://${window.location.host}/ws`;

  try {
    ws = new WebSocket(wsUrl);

    ws.onopen = () => {
      console.log('WebSocket connected');
      reconnectAttempts = 0;
      updateConnectionStatus('connected');
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

  if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
    reconnectAttempts++;
    console.log(`Reconnecting in ${RECONNECT_DELAY}ms (attempt ${reconnectAttempts}/${MAX_RECONNECT_ATTEMPTS})`);
    reconnectTimer = setTimeout(connectWebSocket, RECONNECT_DELAY);
  } else {
    console.error('Max reconnection attempts reached');
  }
}

function updateConnectionStatus(status) {
  const statusDot = document.querySelector('.status-dot');
  if (statusDot) {
    statusDot.className = 'status-dot';
    statusDot.classList.add(status);
  }

  const statusText = document.getElementById('ws-text');
  if (statusText) {
    const statusLabels = {
      connected: 'Connected',
      disconnected: 'Disconnected',
      error: 'Error'
    };
    statusText.textContent = statusLabels[status] || status;
  }
}

function handleWebSocketMessage(data) {
  switch (data.type) {
    case 'init':
      console.log('WebSocket connected, SSR data is current');
      // No refresh needed — page already has server-rendered data
      break;

    case 'state':
      console.log('State update:', data.data);
      document.body.dispatchEvent(new CustomEvent('refresh'));
      break;

    case 'event':
      handleWorkflowEvent(data.data);
      break;

    default:
      console.log('Unknown message type:', data.type);
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

// Handle individual workflow events
function handleWorkflowEvent(event) {
  if (!event) return;

  const eventType = event['event/type'] || event.event_type;

  switch (eventType) {
    case 'workflow/started':
      showToast('Workflow started: ' + (event['workflow/spec']?.name || 'unknown'), 'info');
      break;

    case 'workflow/phase-started':
      showToast('Phase started: ' + (event['workflow/phase'] || 'unknown'), 'info');
      break;

    case 'workflow/phase-completed': {
      const outcome = event['phase/outcome'] || 'completed';
      const type = outcome === 'success' ? 'success' : 'warning';
      showToast('Phase completed: ' + (event['workflow/phase'] || 'unknown'), type);
      break;
    }

    case 'workflow/completed':
      showToast('Workflow completed', 'success');
      break;

    case 'workflow/failed':
      showToast('Workflow failed: ' + (event['workflow/failure-reason'] || 'unknown error'), 'error', 8000);
      break;

    // agent/chunk events are high-frequency — don't toast, just refresh
    case 'agent/chunk':
      break;

    default:
      break;
  }

  // Trigger htmx refresh for all data-bound sections
  document.body.dispatchEvent(new CustomEvent('refresh'));
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
