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
      const yesNo = window.miniforge.t(value ? 'value/yes' : 'value/no');
      return window.miniforge.t('filter/chip-label-value', { field: id, value: yesNo });
    }

    if (Array.isArray(value)) {
      return window.miniforge.t('filter/chip-label-value', {
        field: id,
        value: value.map(normalizeDisplay).join(', ')
      });
    }

    return window.miniforge.t('filter/chip-label-value', {
      field: id,
      value: normalizeDisplay(value)
    });
  }

  function createFilterChip(clause, scope) {
    const chip = document.createElement('div');
    chip.className = `filter-chip filter-chip-${scope}`;
    chip.setAttribute('data-filter-id', clause['filter/id']);
    chip.setAttribute('data-scope', scope);

    const label = document.createElement('span');
    label.className = 'filter-label';
    label.textContent = formatFilterLabel(clause);

    const filterId = clause['filter/id'];
    const filterValue = clause.value;

    const menu = document.createElement('div');
    menu.className = 'filter-chip-menu';

    const items = document.createElement('div');
    items.className = 'filter-chip-menu-items';

    const toggleBtn = document.createElement('button');
    toggleBtn.className = 'filter-chip-menu-btn';
    toggleBtn.textContent = '⋮';
    toggleBtn.addEventListener('click', (event) => {
      event.stopPropagation();
      items.classList.toggle('show');
    });

    if (scope === 'local') {
      const pinBtn = document.createElement('button');
      pinBtn.textContent = window.miniforge.t('filter/menu-pin-global');
      pinBtn.addEventListener('click', () => window.miniforge.filters.promoteToGlobal(filterId));
      items.appendChild(pinBtn);
    }

    if (scope === 'global') {
      const makeLocalBtn = document.createElement('button');
      makeLocalBtn.textContent = window.miniforge.t('filter/menu-make-local');
      makeLocalBtn.addEventListener('click', () => window.miniforge.filters.demoteToLocal(filterId));
      items.appendChild(makeLocalBtn);
    }

    const copyBtn = document.createElement('button');
    copyBtn.textContent = window.miniforge.t('filter/menu-copy');
    copyBtn.addEventListener('click', () => window.miniforge.filters.showCopyMenu(filterId));
    items.appendChild(copyBtn);

    const removeMenuBtn = document.createElement('button');
    removeMenuBtn.textContent = window.miniforge.t('filter/menu-remove');
    removeMenuBtn.addEventListener('click', () => window.miniforge.filters.removeFilter(filterId, scope, filterValue));
    items.appendChild(removeMenuBtn);

    menu.appendChild(toggleBtn);
    menu.appendChild(items);

    const removeBtn = document.createElement('button');
    removeBtn.className = 'filter-remove';
    removeBtn.textContent = '×';
    const removeLabel = window.miniforge.t('filter/remove-title');
    removeBtn.title = removeLabel;
    removeBtn.setAttribute('aria-label', removeLabel);
    removeBtn.addEventListener('click', () => runtime.callApi('removeFilter', clause['filter/id'], scope, clause.value));

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

    const options = panes.map((pane, idx) => `${idx + 1}. ${pane}`).join('\n');
    const menu = prompt(window.miniforge.t('prompt/copy-which-pane', { options }), '1');

    if (!menu) {
      return;
    }

    const idx = parseInt(menu, 10) - 1;
    if (idx >= 0 && idx < panes.length) {
      runtime.callApi('copyToPane', filterId, panes[idx]);
      alert(window.miniforge.t('filter/copied-to-pane', { pane: panes[idx] }));
    }
  }

  Object.assign(runtime.api, {
    syncCloudOptionButtons,
    initializeFilterCheckboxes,
    renderFilterChips,
    showCopyMenu
  });
})(window);
