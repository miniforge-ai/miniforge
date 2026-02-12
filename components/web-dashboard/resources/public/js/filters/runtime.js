/* Miniforge Web Dashboard - Filters Runtime */

(function initFilterRuntime(global) {
  const DEFAULT_PANES = ['task-status', 'fleet', 'evidence', 'workflows'];

  function createPaneState() {
    return { op: 'and', clauses: [] };
  }

  const runtime = global.miniforgeFilterRuntime || {};

  runtime.DEFAULT_PANES = DEFAULT_PANES;
  runtime.filterState = runtime.filterState || {
    global: createPaneState(),
    panes: {},
    savedViews: {}
  };

  if (!runtime.filterState.global) {
    runtime.filterState.global = createPaneState();
  }
  if (!Array.isArray(runtime.filterState.global.clauses)) {
    runtime.filterState.global.clauses = [];
  }

  if (!runtime.filterState.panes || typeof runtime.filterState.panes !== 'object') {
    runtime.filterState.panes = {};
  }

  DEFAULT_PANES.forEach((pane) => {
    if (!runtime.filterState.panes[pane]) {
      runtime.filterState.panes[pane] = createPaneState();
    }
    if (!Array.isArray(runtime.filterState.panes[pane].clauses)) {
      runtime.filterState.panes[pane].clauses = [];
    }
  });

  if (!runtime.filterState.savedViews || typeof runtime.filterState.savedViews !== 'object') {
    runtime.filterState.savedViews = {};
  }

  runtime.currentPane = runtime.currentPane || 'task-status';
  if (!runtime.filterState.panes[runtime.currentPane]) {
    runtime.currentPane = 'task-status';
  }

  runtime.api = runtime.api || {};

  runtime.ensurePaneState = function ensurePaneState(pane) {
    if (!runtime.filterState.panes[pane]) {
      runtime.filterState.panes[pane] = createPaneState();
    }
    if (!Array.isArray(runtime.filterState.panes[pane].clauses)) {
      runtime.filterState.panes[pane].clauses = [];
    }
  };

  runtime.getClauses = function getClauses(scope) {
    if (scope === 'global') {
      return runtime.filterState.global.clauses;
    }

    runtime.ensurePaneState(runtime.currentPane);
    return runtime.filterState.panes[runtime.currentPane].clauses;
  };

  runtime.setClauses = function setClauses(scope, clauses) {
    if (scope === 'global') {
      runtime.filterState.global.clauses = clauses;
      return;
    }

    runtime.ensurePaneState(runtime.currentPane);
    runtime.filterState.panes[runtime.currentPane].clauses = clauses;
  };

  runtime.clone = function clone(value) {
    return JSON.parse(JSON.stringify(value));
  };

  runtime.escapeJSString = function escapeJSString(value) {
    return String(value)
      .replace(/\\/g, '\\\\')
      .replace(/'/g, "\\'");
  };

  runtime.callApi = function callApi(name) {
    const fn = runtime.api[name];
    if (typeof fn !== 'function') {
      return undefined;
    }

    const args = Array.prototype.slice.call(arguments, 1);
    return fn.apply(null, args);
  };

  global.miniforgeFilterRuntime = runtime;
})(window);
