/* Miniforge Web Dashboard - Filters State + Operations */

(function registerFilterState(global) {
  const runtime = global.miniforgeFilterRuntime;
  if (!runtime) {
    return;
  }

  const filterState = runtime.filterState;

  function getFilterState() {
    return filterState;
  }

  function syncPaneFromDOM() {
    const pane = document.body && document.body.dataset
      ? document.body.dataset.currentPane
      : null;

    if (pane && filterState.panes[pane]) {
      runtime.currentPane = pane;
    }
  }

  function setCurrentPane(pane) {
    if (pane && filterState.panes[pane]) {
      runtime.currentPane = pane;
      runtime.callApi('persistState');
    }
  }

  function getCurrentPane() {
    return runtime.currentPane;
  }

  function isFilterActive(filterId, value, scope) {
    const effectiveScope = scope || 'global';
    const clauses = runtime.getClauses(effectiveScope);

    return clauses.some((clause) =>
      clause['filter/id'] === filterId && String(clause.value) === String(value)
    );
  }

  function addFilter(filterId, op, value, scope) {
    const effectiveScope = scope || 'global';
    const clauses = runtime.getClauses(effectiveScope);

    const exists = clauses.some((clause) =>
      clause['filter/id'] === filterId && String(clause.value) === String(value)
    );

    if (!exists) {
      clauses.push({
        'filter/id': filterId,
        op: op,
        value: value
      });
      runtime.setClauses(effectiveScope, clauses);
    }

    runtime.callApi('persistState');
    runtime.callApi('applyFilters');
  }

  function removeFilter(filterId, scope, value) {
    const effectiveScope = scope || 'global';
    const hasSpecificValue = !(value === null || typeof value === 'undefined');

    const nextClauses = runtime.getClauses(effectiveScope).filter((clause) => {
      if (clause['filter/id'] !== filterId) {
        return true;
      }

      if (!hasSpecificValue) {
        return false;
      }

      return String(clause.value) !== String(value);
    });

    runtime.setClauses(effectiveScope, nextClauses);
    runtime.callApi('persistState');
    runtime.callApi('applyFilters');
  }

  function toggleFilter(filterId, value, scope, checked) {
    const effectiveScope = scope || 'global';

    if (checked) {
      addFilter(filterId, ':=', value, effectiveScope);
      return;
    }

    const nextClauses = runtime.getClauses(effectiveScope).filter((clause) => {
      if (value === true || value === 'true') {
        return clause['filter/id'] !== filterId;
      }

      return !(clause['filter/id'] === filterId && String(clause.value) === String(value));
    });

    runtime.setClauses(effectiveScope, nextClauses);
    runtime.callApi('persistState');
    runtime.callApi('applyFilters');
  }

  function setTextFilter(filterId, scope, value, op) {
    const effectiveScope = scope || 'global';
    const effectiveOp = op || ':contains';

    const nextClauses = runtime.getClauses(effectiveScope)
      .filter((clause) => clause['filter/id'] !== filterId);

    const trimmed = value ? String(value).trim() : '';
    if (trimmed) {
      nextClauses.push({
        'filter/id': filterId,
        op: effectiveOp,
        value: trimmed
      });
    }

    runtime.setClauses(effectiveScope, nextClauses);
    runtime.callApi('persistState');
    runtime.callApi('applyFilters');
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

  function promoteToGlobal(filterId) {
    runtime.ensurePaneState(runtime.currentPane);
    const paneState = filterState.panes[runtime.currentPane];

    const clause = paneState.clauses.find((entry) => entry['filter/id'] === filterId);
    if (!clause) {
      return;
    }

    filterState.global.clauses.push(clause);
    paneState.clauses = paneState.clauses.filter((entry) => entry['filter/id'] !== filterId);

    runtime.callApi('persistState');
    runtime.callApi('applyFilters');
  }

  function demoteToLocal(filterId) {
    const clause = filterState.global.clauses.find((entry) => entry['filter/id'] === filterId);
    if (!clause) {
      return;
    }

    runtime.ensurePaneState(runtime.currentPane);
    filterState.panes[runtime.currentPane].clauses.push(clause);
    filterState.global.clauses = filterState.global.clauses
      .filter((entry) => entry['filter/id'] !== filterId);

    runtime.callApi('persistState');
    runtime.callApi('applyFilters');
  }

  function copyToPane(filterId, targetPane) {
    runtime.ensurePaneState(runtime.currentPane);
    runtime.ensurePaneState(targetPane);

    let clause = filterState.global.clauses.find((entry) => entry['filter/id'] === filterId);
    if (!clause) {
      clause = filterState.panes[runtime.currentPane].clauses
        .find((entry) => entry['filter/id'] === filterId);
    }

    if (!clause || targetPane === runtime.currentPane) {
      return;
    }

    const targetState = filterState.panes[targetPane];
    const alreadyExists = targetState.clauses
      .some((entry) => entry['filter/id'] === filterId && String(entry.value) === String(clause.value));

    if (!alreadyExists) {
      targetState.clauses.push(runtime.clone(clause));
      runtime.callApi('persistState');
    }
  }

  function clearFilters(scope) {
    const effectiveScope = scope || 'all';

    if (effectiveScope === 'global' || effectiveScope === 'all') {
      filterState.global.clauses = [];
    }

    if (effectiveScope === 'local' || effectiveScope === 'all') {
      runtime.ensurePaneState(runtime.currentPane);
      filterState.panes[runtime.currentPane].clauses = [];
    }

    if (effectiveScope === 'all') {
      Object.keys(filterState.panes).forEach((pane) => {
        runtime.ensurePaneState(pane);
        filterState.panes[pane].clauses = [];
      });
    }

    runtime.callApi('persistState');
    runtime.callApi('applyFilters');
  }

  function saveView(name) {
    filterState.savedViews[name] = {
      global: runtime.clone(filterState.global),
      panes: runtime.clone(filterState.panes),
      createdAt: new Date().toISOString()
    };

    runtime.callApi('persistState');
    return true;
  }

  function loadView(name) {
    const view = filterState.savedViews[name];
    if (!view) {
      return false;
    }

    filterState.global = runtime.clone(view.global);
    filterState.panes = runtime.clone(view.panes);

    runtime.callApi('persistState');
    runtime.callApi('applyFilters');
    return true;
  }

  function deleteView(name) {
    delete filterState.savedViews[name];
    runtime.callApi('persistState');
  }

  function listViews() {
    return Object.keys(filterState.savedViews).map((name) => ({
      name: name,
      createdAt: filterState.savedViews[name].createdAt
    }));
  }

  Object.assign(runtime.api, {
    getFilterState,
    syncPaneFromDOM,
    setCurrentPane,
    getCurrentPane,
    isFilterActive,
    addFilter,
    removeFilter,
    toggleFilter,
    setTextFilter,
    toggleCloudFilter,
    promoteToGlobal,
    demoteToLocal,
    copyToPane,
    clearFilters,
    saveView,
    loadView,
    deleteView,
    listViews
  });
})(window);
