/* Miniforge Web Dashboard - Filters Persistence */

(function registerFilterPersistence(global) {
  const runtime = global.miniforgeFilterRuntime;
  if (!runtime) {
    return;
  }

  const filterState = runtime.filterState;

  function persistState() {
    try {
      localStorage.setItem('miniforge-filters', JSON.stringify(filterState));
      localStorage.setItem('miniforge-current-pane', runtime.currentPane);
    } catch (error) {
      console.error('Failed to persist filter state:', error);
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
        runtime.currentPane = storedPane;
      }

      runtime.DEFAULT_PANES.forEach(runtime.ensurePaneState);
      if (!filterState.panes[runtime.currentPane]) {
        runtime.currentPane = 'task-status';
      }
    } catch (error) {
      console.error('Failed to load filter state:', error);
    }
  }

  function encodeFiltersToURL() {
    const params = new URLSearchParams();

    if (filterState.global.clauses.length > 0) {
      params.set('gf', btoa(JSON.stringify(filterState.global)));
    }

    runtime.ensurePaneState(runtime.currentPane);
    const paneFilters = filterState.panes[runtime.currentPane];
    if (paneFilters.clauses.length > 0) {
      params.set('pf', btoa(JSON.stringify(paneFilters)));
    }

    if (runtime.currentPane) {
      params.set('pane', runtime.currentPane);
    }

    return params.toString();
  }

  function decodeFiltersFromURL() {
    const params = new URLSearchParams(window.location.search);
    const encodedGlobal = params.get('gf');
    const encodedPane = params.get('pf');
    const pane = params.get('pane');

    try {
      if (encodedGlobal) {
        filterState.global = JSON.parse(atob(encodedGlobal));
      }

      const nextPane = pane || runtime.currentPane;
      runtime.ensurePaneState(nextPane);
      if (encodedPane && filterState.panes[nextPane]) {
        filterState.panes[nextPane] = JSON.parse(atob(encodedPane));
      }

      if (nextPane && filterState.panes[nextPane]) {
        runtime.currentPane = nextPane;
      }

      return Boolean(encodedGlobal || encodedPane || pane);
    } catch (error) {
      console.error('Failed to decode filters from URL:', error);
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
    const isLocalhost = window.location.hostname === 'localhost'
      || window.location.hostname === '127.0.0.1'
      || window.location.hostname === '';

    if (isLocalhost) {
      alert('✓ Filter state saved to URL\n\nBookmark this page to save your current view.\nThe URL has been updated with your filter settings.');
      return;
    }

    if (navigator.clipboard) {
      navigator.clipboard.writeText(url).then(() => {
        alert('✓ Shareable link copied to clipboard!\n\nShare this URL with your team to show them your current filter view.');
      }).catch((error) => {
        console.error('Failed to copy link:', error);
        prompt('Copy this shareable link:', url);
      });
      return;
    }

    prompt('Copy this shareable link:', url);
  }

  Object.assign(runtime.api, {
    persistState,
    loadState,
    encodeFiltersToURL,
    decodeFiltersFromURL,
    updateURL,
    shareCurrentView
  });
})(window);
