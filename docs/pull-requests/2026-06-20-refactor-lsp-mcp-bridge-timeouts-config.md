# Externalize lsp-mcp-bridge LSP client timeouts to EDN

## Overview

Moves the LSP client request, initialize, and shutdown timeouts in the
`lsp-mcp-bridge` base out of inline source constants and into an EDN resource.
Behavior-preserving: the loaded values match the originals
(`30000`/`60000`/`10000`).

This is the follow-up named in
`docs/pull-requests/2026-06-20-refactor-lsp-client-timeouts-config.md`
(PR 1253), which externalized the same three timeouts for the
`tool-registry` component. The base carried a duplicate of those magic
numbers.

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
  structure mirror `components/tool-registry/resources/config/tool-registry/lsp.edn`.
- `bases/lsp-mcp-bridge/src/ai/miniforge/lsp_mcp_bridge/lsp/client.clj`:
  - requires `clojure.edn` and `clojure.java.io`
  - adds a private `load-config` helper that reads the EDN via
    `(io/resource ...) slurp edn/read-string`, failing fast with a clear
    `ex-info` when the resource is missing, malformed, not a map, or missing a
    required key (mirrors the `tool-registry` client)
  - `default-timeout-ms` now sources `:request-timeout-ms`; new `init-timeout-ms`
    and `shutdown-timeout-ms` defs source their keys
  - the `60000` in `initialize` and the `10000` in `shutdown` now reference the
    new defs
- Path wiring so the new resource is on every classpath that already lists the
  base `src`:
  - `bases/lsp-mcp-bridge/deps.edn` `:paths` → `["src" "resources"]` (the three
    projects reference the base via `:local/root`, so they inherit this — no
    project `deps.edn` edits needed)
  - workspace `deps.edn` `:dev` and `:test` path lists
  - the four `bb.edn` LSP tasks (`lsp-mcp-bridge`, `lsp:status`, `lsp:install`,
    `lsp:setup`) and the `miniforge` dev task, each of which loads the base

## Verification

- JVM load smoke (`:dev` classpath): `default-timeout-ms`/`init-timeout-ms`/`shutdown-timeout-ms`
  equal `30000`/`60000`/`10000`, matching the originals.
- bb resource resolution: `io/resource` + `edn/read-string` of
  `config/lsp-mcp-bridge/lsp.edn` returns the expected map under Babashka.
- Base isolated tests (client, config, protocol, installer): 31 tests, 111
  assertions, 0 failures, 0 errors.
- `bb poly:check`: OK.

## Notes

- The path `config/lsp-mcp-bridge/lsp.edn` is base-namespaced so it does not
  collide with the `tool-registry` resource `config/tool-registry/lsp.edn` when
  both are on the same project classpath.
- `bb lsp:status` fails to load on `origin/main` due to a pre-existing
  `slingshot` gap in that task's classpath (unrelated to this change); the base
  namespace load was verified on the JVM instead.
