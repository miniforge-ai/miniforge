# Externalize lsp-mcp-bridge LSP client timeouts to EDN

## Overview

Moves the LSP client request / initialize / shutdown timeouts in the
`lsp-mcp-bridge` base out of inline source constants and into an EDN resource,
loaded through the shared canonical loader `config/load-config-resource` —
exactly as the `tool-registry` LSP client does (PR #1253). Behaviour-preserving:
the loaded values stay `30000` / `60000` / `10000`.

## Motivation

Config-as-data (Dewey 007) requires operator-tunable values to live in data, not
source. The base LSP client held three timeouts as code:

- `(def default-timeout-ms 30000)`
- a bare `60000` for the initialize handshake
- a bare `10000` for the graceful shutdown request

These mirror the `tool-registry` timeouts externalized in PR #1253. The base was
deferred there because, unlike `tool-registry`, it had no `resources` directory
and no `"resources"` on its `deps.edn` `:paths`, and is wired into three projects
plus the `bb.edn` LSP tasks.

## Changes

- New resource
  `bases/lsp-mcp-bridge/resources/config/lsp-mcp-bridge/lsp.edn` holding
  `{:request-timeout-ms 30000 :init-timeout-ms 60000 :shutdown-timeout-ms 10000}`,
  base-namespaced so it does not collide with `config/tool-registry/lsp.edn` on a
  shared classpath.
- `lsp/client.clj` requires `ai.miniforge.config.interface` and loads the
  resource via `(config/load-config-resource "config/lsp-mcp-bridge/lsp.edn"
  [:request-timeout-ms :init-timeout-ms :shutdown-timeout-ms])`; `default-timeout-ms`
  sources `:request-timeout-ms`, with `init-timeout-ms` / `shutdown-timeout-ms`
  defs for the other two. The `60000` / `10000` literals in `initialize` /
  `shutdown` reference the named defs. The two LSP clients (`tool-registry`,
  `lsp-mcp-bridge`) now load identically — no second hand-rolled loader.
- Path wiring so the resource and the shared loader are on every classpath that
  lists the base `src`:
  - `bases/lsp-mcp-bridge/deps.edn` `:paths` → `["src" "resources"]`. The three
    projects reference the base via `:local/root`, and all already supply
    `ai.miniforge/config`, so they inherit this with no project `deps.edn` edits.
  - workspace `deps.edn` `:dev` and `:test` path lists (already carry `config`).
  - the four `bb.edn` LSP tasks gain the base `resources` and
    `components/config/{src,resources}` (plus `components/messages/{src,resources}`,
    which the shared loader uses once PR #1265 lands); the `miniforge` dev task
    already carried them.

## Verification

- JVM load smoke (`:dev`): `default-timeout-ms` / `init-timeout-ms` /
  `shutdown-timeout-ms` equal `30000` / `60000` / `10000`.
- Base isolated tests (client, config, protocol, installer): 32 tests, 114
  assertions, 0 failures. `client-test/shipped-timeouts-test` asserts the shipped
  resource loads through the shared loader.
- `bb poly:check`: OK (pre-existing Warning 205 about config test-fixture files
  is unrelated).
