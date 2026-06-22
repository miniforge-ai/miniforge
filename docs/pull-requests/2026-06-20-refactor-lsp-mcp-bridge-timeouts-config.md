# Externalize lsp-mcp-bridge LSP client timeouts to EDN

## Overview

Moves the LSP client request, initialize, and shutdown timeouts in the
`lsp-mcp-bridge` base out of inline source constants and into an EDN resource.
Behavior-preserving: the loaded values match the originals
(`30000`/`60000`/`10000`).

This is the follow-up to PR #1253, which externalized the same three
timeouts for the `tool-registry` component (adding
`components/tool-registry/resources/config/tool-registry/lsp.edn` and the
matching client changes). That PR is still open at the time of writing, so
the paths it introduces are not yet on `main`. The base carried a
duplicate of the same three magic numbers.

## Motivation

Config-as-data (Dewey 007) requires operator-tunable values to live in data,
not source. The base LSP client held three timeouts as code:

- `(def default-timeout-ms 30000)`
- a bare `60000` for the initialize handshake
- a bare `10000` for the graceful shutdown request

PR #1253 deferred this base because, unlike `tool-registry`, the base had no
`resources` directory and no `"resources"` on its `deps.edn` `:paths`, and it is
wired into three projects (`miniforge`, `miniforge-core`, `miniforge-tui`) plus
the `bb.edn` LSP tasks. This PR does that path wiring.

## Changes

- New resource
  `bases/lsp-mcp-bridge/resources/config/lsp-mcp-bridge/lsp.edn` holding
  `{:request-timeout-ms 30000 :init-timeout-ms 60000 :shutdown-timeout-ms 10000}`,
  with the Apache-2.0 header and a comment describing each key. Values and
  structure match the `tool-registry` resource introduced by PR #1253.
- New message catalog
  `bases/lsp-mcp-bridge/resources/config/lsp-mcp-bridge/messages/system.edn`
  holding the config-load diagnostic strings, so the fail-fast `ex-info`
  messages are catalog-data (operator/system-locale, auditable and overridable)
  rather than raw English string literals in source. Follows the established
  system-locale catalog layout with section key `:lsp-mcp-bridge/system`.
- `bases/lsp-mcp-bridge/src/ai/miniforge/lsp_mcp_bridge/config.clj` (the base's
  existing config reader) gains the resource loading, so `lsp/client.clj` does
  not introduce a second `load-config`:
  - `read-config-resource` — strict counterpart of the existing lenient
    `read-edn-file`: reads a required EDN map from the classpath and fails fast
    with a catalog-routed `ex-info` when the resource is missing, malformed, not
    a map, or missing a required key
  - `read-timeout-resource` / `load-lsp-timeouts` — add the positive-integer
    millisecond value check (so a string/nil/negative value fails at load, not
    later at `deref`) and read the shipped resource
- `bases/lsp-mcp-bridge/src/ai/miniforge/lsp_mcp_bridge/lsp/client.clj`:
  - requires the base `config` namespace (and no longer `clojure.edn`,
    `clojure.java.io`, or `messages.interface` directly)
  - `timeouts` is now `(config/load-lsp-timeouts)`; `default-timeout-ms` sources
    `:request-timeout-ms`, with `init-timeout-ms`/`shutdown-timeout-ms` defs for
    the other two keys
  - the `60000` in `initialize` and the `10000` in `shutdown` reference the
    named defs
- Path wiring so the new resources are on every classpath that already lists the
  base `src`:
  - `bases/lsp-mcp-bridge/deps.edn` `:paths` → `["src" "resources"]` (the three
    projects reference the base via `:local/root`, so they inherit this — no
    project `deps.edn` edits needed; `messages`, like `response`, is already
    supplied by each project and the workspace, so it follows the Polylith
    base convention of not being listed in the base `deps.edn`)
  - workspace `deps.edn` `:dev` and `:test` path lists (already carry
    `components/messages/*`)
  - the four `bb.edn` LSP tasks (`lsp-mcp-bridge`, `lsp:status`, `lsp:install`,
    `lsp:setup`) gain `components/messages/{src,resources}` plus the base
    `resources`; the `miniforge` dev task already carried `messages`

## Verification

- JVM load smoke (`:dev` classpath): `default-timeout-ms`/`init-timeout-ms`/`shutdown-timeout-ms`
  equal `30000`/`60000`/`10000`, matching the originals.
- bb resource resolution: `io/resource` + `edn/read-string` of
  `config/lsp-mcp-bridge/lsp.edn` returns the expected map under Babashka.
- `read-config-resource`/`read-timeout-resource` negative paths exercised
  (in `config-test`): missing-resource, not-a-map, missing-key, and
  string/negative value each throw the catalog-routed `ex-info` (not a later
  low-signal `deref` failure), and the resolved message comes from the catalog.
- Base isolated tests (client, config, protocol, installer): 33 tests, 124
  assertions, 0 failures, 0 errors.
- `bb poly:check`: OK.

## Notes

- The path `config/lsp-mcp-bridge/lsp.edn` is base-namespaced so it does not
  collide with the `tool-registry` resource `config/tool-registry/lsp.edn` when
  both are on the same project classpath.
- `bb lsp:status` fails to load on `origin/main` due to a pre-existing
  `slingshot` gap in that task's classpath (unrelated to this change); the base
  namespace load was verified on the JVM instead.
