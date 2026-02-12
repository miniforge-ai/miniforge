/* Miniforge Web Dashboard - Filter UI */

(function registerFilterUI(global) {
  const runtime = global.miniforgeFilterRuntime;
  if (!runtime) {
    return;
  }

  const filterState = runtime.filterState;

  function syncCloudOptionButtons() {
    document.querySelectorAll('.filter-option-cloud-btn').forEach((button) => {
      const filterId = button.dataset.filterId;
      const filterValue = button.dataset.filterValue;
      const scope = button.dataset.scope || 'global';
      const active = runtime.callApi('isFilterActive', filterId, filterValue, scope);
      button.classList.toggle('active', active);
    });
  }

  function initializeFilterCheckboxes() {
    document.querySelectorAll('.filter-checkbox').forEach((checkbox) => {
      const filterId = checkbox.dataset.filterId;
      const scope = checkbox.dataset.scope;
      const hasExplicitValue = checkbox.hasAttribute('value');
      const checkboxValue = checkbox.value;

      if (!filterId || !scope) {
        return;
      }

      const clauses = scope === 'global'
        ? filterState.global.clauses
        : (filterState.panes[runtime.currentPane] || { clauses: [] }).clauses;

      const hasFilter = hasExplicitValue
        ? clauses.some((clause) =>
          clause['filter/id'] === filterId && String(clause.value) === String(checkboxValue)
        )
        : clauses.some((clause) => clause['filter/id'] === filterId);

      checkbox.checked = hasFilter;
    });

    document.querySelectorAll('.filter-text-input').forEach((input) => {
      const filterId = input.dataset.filterId;
      const scope = input.dataset.scope;

      if (!filterId || !scope) {
        return;
      }

      const clauses = scope === 'global'
        ? filterState.global.clauses
        : (filterState.panes[runtime.currentPane] || { clauses: [] }).clauses;

      const existing = clauses.find((clause) => clause['filter/id'] === filterId);
      if (existing) {
        input.value = existing.value;
      }
    });

    syncCloudOptionButtons();
  }

  function formatFilterLabel(clause) {
    const id = clause['filter/id'];
    const value = clause.value;

    const normalizeDisplay = (item) => {
      if (typeof item === 'string' && item.startsWith(':')) {
        return item.slice(1);
      }
      return item;
    };

    if (typeof value === 'boolean') {
      return `${id}: ${value ? 'Yes' : 'No'}`;
    }

    if (Array.isArray(value)) {
      return `${id}: ${value.map(normalizeDisplay).join(', ')}`;
    }

    return `${id}: ${normalizeDisplay(value)}`;
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

    const encodedValue = runtime.escapeJSString(clause.value);
    menu.innerHTML = `
      <button class="filter-chip-menu-btn" onclick="event.stopPropagation(); this.nextElementSibling.classList.toggle('show')">⋮</button>
      <div class="filter-chip-menu-items">
        ${scope === 'local' ? '<button onclick="window.miniforge.filters.promoteToGlobal(\'' + clause['filter/id'] + '\')">📌 Pin to Global</button>' : ''}
        ${scope === 'global' ? '<button onclick="window.miniforge.filters.demoteToLocal(\'' + clause['filter/id'] + '\')">📍 Make Pane-Local</button>' : ''}
        <button onclick="window.miniforge.filters.showCopyMenu(\'' + clause['filter/id'] + '\')">📋 Copy to Pane</button>
        <button onclick="window.miniforge.filters.removeFilter(\'' + clause['filter/id'] + '\', '\'' + scope + '\', '\'' + encodedValue + '\')">🗑️ Remove</button>
      </div>
    `;

    const removeBtn = document.createElement('button');
    removeBtn.className = 'filter-remove';
    removeBtn.textContent = '×';
    removeBtn.title = 'Remove filter';
    removeBtn.onclick = () => runtime.callApi('removeFilter', clause['filter/id'], scope, clause.value);

    chip.appendChild(label);
    chip.appendChild(menu);
    chip.appendChild(removeBtn);

    return chip;
  }

  function renderFilterChips() {
    const globalContainer = document.getElementById('global-filter-chips');
    const localContainer = document.getElementById('filter-chips');

    if (globalContainer) {
      globalContainer.innerHTML = '';
      filterState.global.clauses.forEach((clause) => {
        globalContainer.appendChild(createFilterChip(clause, 'global'));
      });
    }

    if (localContainer) {
      runtime.ensurePaneState(runtime.currentPane);
      localContainer.innerHTML = '';
      filterState.panes[runtime.currentPane].clauses.forEach((clause) => {
        localContainer.appendChild(createFilterChip(clause, 'local'));
      });
    }

    syncCloudOptionButtons();
  }

  function showCopyMenu(filterId) {
    const panes = runtime.DEFAULT_PANES.filter((pane) => pane !== runtime.currentPane);

    const menu = prompt(
      'Copy to which pane?\n' + panes.map((pane, idx) => `${idx + 1}. ${pane}`).join('\n'),
      '1'
    );

    if (!menu) {
      return;
    }

    const idx = parseInt(menu, 10) - 1;
    if (idx >= 0 && idx < panes.length) {
      runtime.callApi('copyToPane', filterId, panes[idx]);
      alert(`Filter copied to ${panes[idx]}`);
    }
  }

  Object.assign(runtime.api, {
    syncCloudOptionButtons,
    initializeFilterCheckboxes,
    renderFilterChips,
    showCopyMenu
  });
})(window);
