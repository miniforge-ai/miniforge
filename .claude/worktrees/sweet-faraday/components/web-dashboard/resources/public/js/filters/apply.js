/* Miniforge Web Dashboard - Filter Application */

(function registerFilterApply(global) {
  const runtime = global.miniforgeFilterRuntime;
  if (!runtime) {
    return;
  }

  const filterState = runtime.filterState;

  function applyFilters() {
    runtime.ensurePaneState(runtime.currentPane);

    const combined = {
      op: 'and',
      clauses: [
        ...filterState.global.clauses,
        ...filterState.panes[runtime.currentPane].clauses
      ]
    };

    const params = new URLSearchParams();
    params.set('filters', JSON.stringify(combined));
    params.set('pane', runtime.currentPane);

    const refreshTargets = document.querySelectorAll('[data-filter-refresh="true"][hx-get]');
    refreshTargets.forEach((el) => {
      const url = new URL(el.getAttribute('hx-get'), window.location.origin);
      url.search = params.toString();
      el.setAttribute('hx-get', url.pathname + '?' + url.search);
    });

    if (refreshTargets.length === 0 && window.location.pathname === '/dag') {
      const currentQuery = window.location.search.replace(/^\?/, '');
      const nextQuery = params.toString();
      if (currentQuery !== nextQuery) {
        window.location.replace(`${window.location.pathname}?${nextQuery}`);
        return;
      }
    }

    document.body.dispatchEvent(new CustomEvent('refresh'));
    runtime.callApi('updateURL');
    runtime.callApi('renderFilterChips');
  }

  runtime.api.applyFilters = applyFilters;
})(window);
