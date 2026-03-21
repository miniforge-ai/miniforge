/*
 * Legacy compatibility entrypoint.
 *
 * Filter code now lives in /js/filters/*.js and should be loaded explicitly
 * in order: runtime, state, persistence, apply, ui, init.
 */
window.miniforge = window.miniforge || {};
window.miniforge.filters = window.miniforge.filters || {};
