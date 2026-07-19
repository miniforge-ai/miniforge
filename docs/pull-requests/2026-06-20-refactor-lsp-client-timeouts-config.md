<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# Externalize LSP client timeouts to EDN

## Overview

Moves the LSP client request, initialize, and shutdown timeouts in the
`tool-registry` component out of inline source constants and into an EDN
resource. Behavior-preserving: the loaded values match the originals.

## Motivation

Config-as-data (Dewey 007) requires operator-tunable values to live in data,
not source. The `tool-registry` LSP client held three timeouts as code:

- `(def default-timeout-ms 30000)`
- a bare `60000` for the initialize handshake
- a bare `10000` for the graceful shutdown request

These are request/shutdown deadlines an operator may want to tune per
environment. The component already loads EDN resources (`lsp-registry.edn`,
`tools/lsp/*.edn`) and already has `"resources"` on its `deps.edn` `:paths`, so
no path wiring was needed.

## Changes

- New resource `components/tool-registry/resources/config/tool-registry/lsp.edn`
  holding `{:request-timeout-ms 30000 :init-timeout-ms 60000
  :shutdown-timeout-ms 10000}`, with the Apache-2.0 header and a comment
  describing each key. Mirrors `components/reliability/resources/config/reliability/defaults.edn`.
- `components/tool-registry/src/ai/miniforge/tool_registry/lsp/client.clj`:
  - requires `clojure.edn` and `clojure.java.io`
  - loads the EDN via `(io/resource ...) slurp edn/read-string` into a private
    `timeouts` map
  - `default-timeout-ms` now sources `:request-timeout-ms`; new `init-timeout-ms`
    and `shutdown-timeout-ms` defs source their keys
  - the `60000` in `initialize` and the `10000` in `shutdown` now reference the
    new defs

## Follow-up left for a separate PR

`bases/lsp-mcp-bridge/src/ai/miniforge/lsp_mcp_bridge/lsp/client.clj` carries a
duplicate of the same three magic numbers (`default-timeout-ms 30000`, bare
`60000`, bare `10000`). It was left out of this PR on the conservatism rule: the
base has no `resources` directory and no `"resources"` on its `deps.edn`
`:paths`, and the base is wired into three projects (`miniforge`,
`miniforge-core`, `miniforge-tui`). Adding a resources dir there means editing
the base `deps.edn` plus the workspace dev/test path lists and the affected
project path lists — heavier and higher-risk than this change. Tracked as
follow-up.

## Verification

- Load smoke test: loaded `default-timeout-ms`/`init-timeout-ms`/`shutdown-timeout-ms`
  equal `30000`/`60000`/`10000`, matching the originals.
- `tool-registry` isolated tests: 31 tests, 122 assertions, 0 failures, 0 errors.
- `bb poly:check`: OK.
