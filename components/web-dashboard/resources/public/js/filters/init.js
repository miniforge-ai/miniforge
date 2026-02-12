/* Miniforge Web Dashboard - Filter Initialization */

(function registerFilterInit(global) {
  const runtime = global.miniforgeFilterRuntime;
  if (!runtime) {
    return;
  }

  function init() {
    runtime.callApi('loadState');
    runtime.callApi('syncPaneFromDOM');
    runtime.ensurePaneState(runtime.currentPane);

    const hasURLState = runtime.callApi('decodeFiltersFromURL');
    const hasLocalState = runtime.filterState.global.clauses.length > 0
      || ((runtime.filterState.panes[runtime.currentPane] || { clauses: [] }).clauses.length > 0);

    runtime.callApi('persistState');

    if (hasURLState || hasLocalState) {
      runtime.callApi('applyFilters');
    } else {
      runtime.callApi('renderFilterChips');
    }
  }

  const modalObserver = new MutationObserver((mutations) => {
    mutations.forEach((mutation) => {
      if (!mutation.addedNodes.length) {
        return;
      }

      mutation.addedNodes.forEach((node) => {
        if (node.classList && node.classList.contains('filter-modal')) {
          runtime.callApi('initializeFilterCheckboxes');
        }
      });
    });
  });

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }

  document.addEventListener('DOMContentLoaded', () => {
    const modalContainer = document.getElementById('filter-modal-container');
    if (modalContainer) {
      modalObserver.observe(modalContainer, {
        childList: true,
        subtree: true
      });
    }
  });

  global.miniforge = global.miniforge || {};
  global.miniforge.filters = runtime.api;
})(window);
