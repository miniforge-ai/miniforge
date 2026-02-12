/* Miniforge Web Dashboard - Filter State Management
 * Hybrid filtering: global context + pane-local analysis
 * Supports localStorage persistence and URL encoding
 */

// Filter state structure
const filterState = {
  global: {
    op: 'and',
    clauses: []
  },
  panes: {
    'task-status': { op: 'and', clauses: [] },
    'fleet': { op: 'and', clauses: [] },
    'evidence': { op: 'and', clauses: [] },
    'workflows': { op: 'and', clauses: [] }
  },
  savedViews: {} // Named presets
};

// Current pane
let currentPane = 'task-status';

//------------------------------------------------------------------------------ Layer 0
// State management

function getFilterState() {
  return filterState;
}

function ensurePaneState(pane) {
  if (!filterState.panes[pane]) {
    filterState.panes[pane] = { op: 'and', clauses: [] };
  }
}

function syncPaneFromDOM() {
  const pane = document.body?.dataset?.currentPane;
  if (pane && filterState.panes[pane]) {
    currentPane = pane;
  }
}

function setCurrentPane(pane) {
  if (pane && filterState.panes[pane]) {
    currentPane = pane;
    persistState();
  }
}

function getCurrentPane() {
  return currentPane;
}

function isFilterActive(filterId, value, scope = 'global') {
  const clauses = scope === 'global'
    ? filterState.global.clauses
    : filterState.panes[currentPane].clauses;

  return clauses.some(c =>
    c['filter/id'] === filterId && String(c.value) === String(value)
  );
}

function toggleFilter(filterId, value, scope, checked) {
  if (checked) {
    addFilter(filterId, ':=', value, scope);
  } else {
    // Remove filter - for boolean filters, remove any value; for others, remove specific value
    const targetClauses = scope === 'global'
      ? filterState.global.clauses
      : filterState.panes[currentPane].clauses;

    const newClauses = targetClauses.filter(c => {
      // For boolean filters, remove entire filter by id.
      if (value === true || value === 'true') {
        return c['filter/id'] !== filterId;
      }
      // For other filters, remove only the specific value
      return !(c['filter/id'] === filterId && String(c.value) === String(value));
    });

    if (scope === 'global') {
      filterState.global.clauses = newClauses;
    } else {
      filterState.panes[currentPane].clauses = newClauses;
    }

    persistState();
    applyFilters();
  }
}

function setTextFilter(filterId, scope, value, op = ':contains') {
  const targetClauses = scope === 'global'
    ? filterState.global.clauses
    : filterState.panes[currentPane].clauses;

  const nextClauses = targetClauses.filter(c => c['filter/id'] !== filterId);
  const trimmed = value?.trim();

  if (trimmed) {
    nextClauses.push({
      'filter/id': filterId,
      op: op,
      value: trimmed
    });
  }

  if (scope === 'global') {
    filterState.global.clauses = nextClauses;
  } else {
    filterState.panes[currentPane].clauses = nextClauses;
  }

  persistState();
  applyFilters();
}

function toggleCloudFilter(button) {
  if (!button) {
    return;
  }

  const filterId = button.dataset.filterId;
  const filterValue = button.dataset.filterValue;
  const scope = button.dataset.scope || 'global';

  if (!filterId) {
    return;
  }

  const active = isFilterActive(filterId, filterValue, scope);
  toggleFilter(filterId, filterValue, scope, !active);
}

function syncCloudOptionButtons() {
  document.querySelectorAll('.filter-option-cloud-btn').forEach(button => {
    const filterId = button.dataset.filterId;
    const filterValue = button.dataset.filterValue;
    const scope = button.dataset.scope || 'global';
    const active = isFilterActive(filterId, filterValue, scope);
    button.classList.toggle('active', active);
  });
}

function initializeFilterCheckboxes() {
  // Set checked state for all filter checkboxes based on current filter state
  document.querySelectorAll('.filter-checkbox').forEach(checkbox => {
    const filterId = checkbox.dataset.filterId;
    const scope = checkbox.dataset.scope;
    const hasExplicitValue = checkbox.hasAttribute('value');
    const checkboxValue = checkbox.value;

    if (filterId && scope) {
      const clauses = scope === 'global'
        ? filterState.global.clauses
        : filterState.panes[currentPane].clauses;

      const hasFilter = hasExplicitValue
        ? clauses.some(c =>
          c['filter/id'] === filterId && String(c.value) === String(checkboxValue)
        )
        : clauses.some(c => c['filter/id'] === filterId);
      checkbox.checked = hasFilter;
    }
  });

  // Also initialize text inputs
  document.querySelectorAll('.filter-text-input').forEach(input => {
    const filterId = input.dataset.filterId;
    const scope = input.dataset.scope;

    if (filterId && scope) {
      const clauses = scope === 'global'
        ? filterState.global.clauses
        : filterState.panes[currentPane].clauses;

      const existingFilter = clauses.find(c => c['filter/id'] === filterId);
      if (existingFilter) {
        input.value = existingFilter.value;
      }
    }
  });

  syncCloudOptionButtons();
}

//------------------------------------------------------------------------------ Layer 1
// Filter operations

function addFilter(filterId, op, value, scope = 'global') {
  const clause = {
    'filter/id': filterId,
    op: op,
    value: value
  };

  // Check if filter already exists to avoid duplicates
  const targetClauses = scope === 'global'
    ? filterState.global.clauses
    : filterState.panes[currentPane].clauses;

  const exists = targetClauses.some(c =>
    c['filter/id'] === filterId && c.value === value
  );

  if (!exists) {
    if (scope === 'global') {
      filterState.global.clauses.push(clause);
    } else {
      filterState.panes[currentPane].clauses.push(clause);
    }
  }

  persistState();
  applyFilters();
}

function removeFilter(filterId, scope = 'global', value = null) {
  const shouldKeepClause = (clause) => {
    if (clause['filter/id'] !== filterId) {
      return true;
    }
    if (value === null || typeof value === 'undefined') {
      return false;
    }
    return String(clause.value) !== String(value);
  };

  if (scope === 'global') {
    filterState.global.clauses = filterState.global.clauses.filter(shouldKeepClause);
  } else {
    filterState.panes[currentPane].clauses = filterState.panes[currentPane].clauses.filter(shouldKeepClause);
  }

  persistState();
  applyFilters();
}

function promoteToGlobal(filterId) {
  // Find clause in current pane
  const paneFilters = filterState.panes[currentPane];
  const clause = paneFilters.clauses.find(c => c['filter/id'] === filterId);

  if (clause) {
    // Add to global
    filterState.global.clauses.push(clause);
    // Remove from pane
    paneFilters.clauses = paneFilters.clauses.filter(c => c['filter/id'] !== filterId);

    persistState();
    applyFilters();
  }
}

function demoteToLocal(filterId) {
  // Find clause in global
  const clause = filterState.global.clauses.find(c => c['filter/id'] === filterId);

  if (clause) {
    // Add to current pane
    filterState.panes[currentPane].clauses.push(clause);
    // Remove from global
    filterState.global.clauses = filterState.global.clauses.filter(
      c => c['filter/id'] !== filterId
    );

    persistState();
    applyFilters();
  }
}

function copyToPane(filterId, targetPane) {
  // Find clause in global or current pane
  let clause = filterState.global.clauses.find(c => c['filter/id'] === filterId);
  if (!clause) {
    clause = filterState.panes[currentPane].clauses.find(c => c['filter/id'] === filterId);
  }

  if (clause && targetPane !== currentPane) {
    // Add to target pane (avoid duplicates)
    const targetFilters = filterState.panes[targetPane];
    if (!targetFilters.clauses.find(c => c['filter/id'] === filterId)) {
      targetFilters.clauses.push({ ...clause });
    }

    persistState();
  }
}

function clearFilters(scope = 'all') {
  if (scope === 'global' || scope === 'all') {
    filterState.global.clauses = [];
  }
  if (scope === 'local' || scope === 'all') {
    filterState.panes[currentPane].clauses = [];
  }
  if (scope === 'all') {
    Object.keys(filterState.panes).forEach(pane => {
      filterState.panes[pane].clauses = [];
    });
  }

  persistState();
  applyFilters();
}

//------------------------------------------------------------------------------ Layer 2
// Saved views

function saveView(name) {
  filterState.savedViews[name] = {
    global: JSON.parse(JSON.stringify(filterState.global)),
    panes: JSON.parse(JSON.stringify(filterState.panes)),
    createdAt: new Date().toISOString()
  };

  persistState();
  return true;
}

function loadView(name) {
  const view = filterState.savedViews[name];
  if (view) {
    filterState.global = JSON.parse(JSON.stringify(view.global));
    filterState.panes = JSON.parse(JSON.stringify(view.panes));

    persistState();
    applyFilters();
    return true;
  }
  return false;
}

function deleteView(name) {
  delete filterState.savedViews[name];
  persistState();
}

function listViews() {
  return Object.keys(filterState.savedViews).map(name => ({
    name: name,
    createdAt: filterState.savedViews[name].createdAt
  }));
}

//------------------------------------------------------------------------------ Layer 3
// Persistence: localStorage

function persistState() {
  try {
    localStorage.setItem('miniforge-filters', JSON.stringify(filterState));
    localStorage.setItem('miniforge-current-pane', currentPane);
  } catch (e) {
    console.error('Failed to persist filter state:', e);
  }
}

function loadState() {
  try {
    const stored = localStorage.getItem('miniforge-filters');
    if (stored) {
      const parsed = JSON.parse(stored);
      Object.assign(filterState, parsed);
    }

    const storedPane = localStorage.getItem('miniforge-current-pane');
    if (storedPane) {
      currentPane = storedPane;
    }

    ['task-status', 'fleet', 'evidence', 'workflows'].forEach(ensurePaneState);
  } catch (e) {
    console.error('Failed to load filter state:', e);
  }
}

//------------------------------------------------------------------------------ Layer 4
// Persistence: URL encoding

function encodeFiltersToURL() {
  const params = new URLSearchParams();

  // Encode global filters
  if (filterState.global.clauses.length > 0) {
    params.set('gf', btoa(JSON.stringify(filterState.global)));
  }

  // Encode current pane filters
  const paneFilters = filterState.panes[currentPane];
  if (paneFilters.clauses.length > 0) {
    params.set('pf', btoa(JSON.stringify(paneFilters)));
  }

  // Encode current pane
  if (currentPane) {
    params.set('pane', currentPane);
  }

  return params.toString();
}

function decodeFiltersFromURL() {
  const params = new URLSearchParams(window.location.search);
  const gf = params.get('gf');
  const pf = params.get('pf');
  const pane = params.get('pane');

  try {
    // Decode global filters
    if (gf) {
      filterState.global = JSON.parse(atob(gf));
    }

    // Decode pane filters
    const nextPane = pane || currentPane;
    if (pf && filterState.panes[nextPane]) {
      filterState.panes[nextPane] = JSON.parse(atob(pf));
    }

    // Set current pane
    if (nextPane && filterState.panes[nextPane]) {
      currentPane = nextPane;
    }

    return Boolean(gf || pf || pane);
  } catch (e) {
    console.error('Failed to decode filters from URL:', e);
    return false;
  }
}

function updateURL() {
  const encoded = encodeFiltersToURL();
  const newURL = encoded
    ? `${window.location.pathname}?${encoded}`
    : window.location.pathname;

  window.history.replaceState({}, '', newURL);
}

function shareCurrentView() {
  updateURL();
  const url = window.location.href;
  const isLocalhost = window.location.hostname === 'localhost' ||
                     window.location.hostname === '127.0.0.1' ||
                     window.location.hostname === '';

  if (isLocalhost) {
    // For localhost, just notify that URL is updated for bookmarking
    alert('✓ Filter state saved to URL\n\nBookmark this page to save your current view.\nThe URL has been updated with your filter settings.');
  } else {
    // For remote servers, copy shareable link to clipboard
    if (navigator.clipboard) {
      navigator.clipboard.writeText(url).then(() => {
        alert('✓ Shareable link copied to clipboard!\n\nShare this URL with your team to show them your current filter view.');
      }).catch(err => {
        console.error('Failed to copy link:', err);
        prompt('Copy this shareable link:', url);
      });
    } else {
      prompt('Copy this shareable link:', url);
    }
  }
}

//------------------------------------------------------------------------------ Layer 5
// Filter application (triggers server-side filtering or client-side)

function applyFilters() {
  ensurePaneState(currentPane);

  // Merge global and pane-local filters
  const combined = {
    op: 'and',
    clauses: [
      ...filterState.global.clauses,
      ...filterState.panes[currentPane].clauses
    ]
  };

  // Trigger htmx refresh with filter params
  const params = new URLSearchParams();
  params.set('filters', JSON.stringify(combined));
  params.set('pane', currentPane);

  // Update htmx elements that declare they participate in filtering.
  const refreshTargets = document.querySelectorAll('[data-filter-refresh="true"][hx-get]');
  refreshTargets.forEach(el => {
    const url = new URL(el.getAttribute('hx-get'), window.location.origin);
    url.search = params.toString();
    el.setAttribute('hx-get', url.pathname + '?' + url.search);
  });

  // DAG page currently renders server-side only, so refresh via URL when needed.
  if (refreshTargets.length === 0 && window.location.pathname === '/dag') {
    const currentQuery = window.location.search.replace(/^\?/, '');
    const nextQuery = params.toString();
    if (currentQuery !== nextQuery) {
      window.location.replace(`${window.location.pathname}?${nextQuery}`);
      return;
    }
  }

  // Trigger refresh
  document.body.dispatchEvent(new CustomEvent('refresh'));

  // Update URL
  updateURL();

  // Update UI
  renderFilterChips();
}

//------------------------------------------------------------------------------ Layer 6
// UI rendering

function renderFilterChips() {
  const globalContainer = document.getElementById('global-filter-chips');
  const localContainer = document.getElementById('filter-chips'); // Pane-local container

  if (globalContainer) {
    globalContainer.innerHTML = '';
    filterState.global.clauses.forEach(clause => {
      const chip = createFilterChip(clause, 'global');
      globalContainer.appendChild(chip);
    });
  }

  if (localContainer) {
    localContainer.innerHTML = '';
    filterState.panes[currentPane].clauses.forEach(clause => {
      const chip = createFilterChip(clause, 'local');
      localContainer.appendChild(chip);
    });
  }

  syncCloudOptionButtons();
}

function escapeJSString(value) {
  return String(value)
    .replace(/\\/g, '\\\\')
    .replace(/'/g, "\\'");
}

function createFilterChip(clause, scope) {
  const chip = document.createElement('div');
  chip.className = `filter-chip filter-chip-${scope}`;
  chip.setAttribute('data-filter-id', clause['filter/id']);
  chip.setAttribute('data-scope', scope);

  const label = document.createElement('span');
  label.className = 'filter-label';
  label.textContent = formatFilterLabel(clause);

  const menu = document.createElement('div');
  menu.className = 'filter-chip-menu';
  const encodedValue = escapeJSString(clause.value);
  menu.innerHTML = `
    <button class="filter-chip-menu-btn" onclick="event.stopPropagation(); this.nextElementSibling.classList.toggle('show')">⋮</button>
    <div class="filter-chip-menu-items">
      ${scope === 'local' ? '<button onclick="window.miniforge.filters.promoteToGlobal(\'' + clause['filter/id'] + '\')">📌 Pin to Global</button>' : ''}
      ${scope === 'global' ? '<button onclick="window.miniforge.filters.demoteToLocal(\'' + clause['filter/id'] + '\')">📍 Make Pane-Local</button>' : ''}
      <button onclick="window.miniforge.filters.showCopyMenu(\'' + clause['filter/id'] + '\')">📋 Copy to Pane</button>
      <button onclick="window.miniforge.filters.removeFilter(\'' + clause['filter/id'] + '\', \'' + scope + '\', \'' + encodedValue + '\')">🗑️ Remove</button>
    </div>
  `;

  const removeBtn = document.createElement('button');
  removeBtn.className = 'filter-remove';
  removeBtn.textContent = '×';
  removeBtn.title = 'Remove filter';
  removeBtn.onclick = () => removeFilter(clause['filter/id'], scope, clause.value);

  chip.appendChild(label);
  chip.appendChild(menu);
  chip.appendChild(removeBtn);

  return chip;
}

function formatFilterLabel(clause) {
  const id = clause['filter/id'];
  const value = clause.value;
  const normalizeDisplay = (v) => {
    if (typeof v === 'string' && v.startsWith(':')) {
      return v.slice(1);
    }
    return v;
  };

  // Format based on filter type
  if (typeof value === 'boolean') {
    return `${id}: ${value ? 'Yes' : 'No'}`;
  } else if (Array.isArray(value)) {
    return `${id}: ${value.map(normalizeDisplay).join(', ')}`;
  } else {
    return `${id}: ${normalizeDisplay(value)}`;
  }
}

function showCopyMenu(filterId) {
  const panes = ['task-status', 'fleet', 'evidence', 'workflows']
    .filter(p => p !== currentPane);

  const menu = prompt(
    'Copy to which pane?\n' + panes.map((p, i) => `${i + 1}. ${p}`).join('\n'),
    '1'
  );

  if (menu) {
    const idx = parseInt(menu) - 1;
    if (idx >= 0 && idx < panes.length) {
      copyToPane(filterId, panes[idx]);
      alert(`Filter copied to ${panes[idx]}`);
    }
  }
}

//------------------------------------------------------------------------------ Layer 7
// Initialization

function init() {
  loadState();
  syncPaneFromDOM();
  ensurePaneState(currentPane);

  const hasURLState = decodeFiltersFromURL();
  const hasLocalState = filterState.global.clauses.length > 0 ||
    (filterState.panes[currentPane]?.clauses?.length || 0) > 0;

  persistState();
  if (hasURLState || hasLocalState) {
    applyFilters();
  } else {
    renderFilterChips();
  }
}

// Initialize on page load
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init);
} else {
  init();
}

// Watch for filter modal content changes and initialize checkboxes
const modalObserver = new MutationObserver((mutations) => {
  mutations.forEach((mutation) => {
    if (mutation.addedNodes.length) {
      mutation.addedNodes.forEach((node) => {
        if (node.classList && node.classList.contains('filter-modal')) {
          initializeFilterCheckboxes();
        }
      });
    }
  });
});

// Start observing the filter modal container
document.addEventListener('DOMContentLoaded', () => {
  const modalContainer = document.getElementById('filter-modal-container');
  if (modalContainer) {
    modalObserver.observe(modalContainer, {
      childList: true,
      subtree: true
    });
  }
});

// Export API
window.miniforge = window.miniforge || {};
window.miniforge.filters = {
  getFilterState,
  setCurrentPane,
  getCurrentPane,
  isFilterActive,
  toggleFilter,
  setTextFilter,
  initializeFilterCheckboxes,
  toggleCloudFilter,
  addFilter,
  removeFilter,
  promoteToGlobal,
  demoteToLocal,
  copyToPane,
  clearFilters,
  saveView,
  loadView,
  deleteView,
  listViews,
  shareCurrentView,
  applyFilters,
  renderFilterChips,
  showCopyMenu
};
