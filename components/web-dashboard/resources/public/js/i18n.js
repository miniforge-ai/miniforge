/* Miniforge Web Dashboard — browser-side message translator.
 *
 * Mirrors ai.miniforge.messages.core/t:
 *   - Look up `key` (e.g. "ws/status-connected") in window.MINIFORGE_MESSAGES.
 *   - Substitute {param-name} tokens from the optional params object.
 *   - Fall back to the key name itself when missing, so untranslated keys
 *     surface visibly rather than rendering empty.
 *
 * The catalog is hydrated inline in page-head before this script loads;
 * see ai.miniforge.web-dashboard.views/messages-hydration-script.
 */

(function (global) {
  const PLACEHOLDER = /\{([^{}]+)\}/g;

  function lookupTemplate(key) {
    const catalog = global.MINIFORGE_MESSAGES;
    if (catalog && Object.prototype.hasOwnProperty.call(catalog, key)) {
      return catalog[key];
    }
    return key;
  }

  function substitute(template, params) {
    if (typeof template !== 'string' || !params) {
      return template;
    }
    return template.replace(PLACEHOLDER, function (match, name) {
      return Object.prototype.hasOwnProperty.call(params, name)
        ? String(params[name])
        : match;
    });
  }

  function t(key, params) {
    return substitute(lookupTemplate(key), params);
  }

  global.miniforge = global.miniforge || {};
  global.miniforge.t = t;
})(window);
