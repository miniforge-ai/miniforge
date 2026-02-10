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

// Export functions for global use
window.miniforge = {
  switchTheme,
  cycleTheme,
  getCurrentTheme: () => document.documentElement.getAttribute('data-theme') || 'dark',
  sendWorkflowCommand
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
      console.log('Received initial state:', data.data);
      // htmx will handle state updates
      break;

    case 'state':
      console.log('State update:', data.data);
      // Trigger htmx refresh for affected sections
      document.body.dispatchEvent(new CustomEvent('refresh'));
      break;

    case 'event':
      console.log('Event received:', data.data);
      // Trigger htmx refresh for relevant sections
      document.body.dispatchEvent(new CustomEvent('refresh'));
      break;

    default:
      console.log('Unknown message type:', data.type);
  }
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
