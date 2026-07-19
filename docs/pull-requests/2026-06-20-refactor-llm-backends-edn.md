<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# refactor(llm): externalize backend endpoint/model data to EDN

## Overview

Moves the pure-data fields of the `backends` map in the `llm` component
out of `llm_client.clj` and into a new EDN resource, loaded from the
classpath as `llm/backends.edn` (on disk at
`components/llm/resources/llm/backends.edn`). The function-valued fields
stay in code.
The runtime value of the `backends` var is unchanged.

## Motivation

`components/llm/src/ai/miniforge/llm/protocols/impl/llm_client.clj`
defined `(def backends {...})` as a single literal that mixed
configuration data (command names, probe/API endpoint URLs, default
model, model list) with code (stream-parser and arg-builder function
references). The data portion is config-as-data (Dewey 007) and belongs
in a resource alongside the component's existing EDN configs
(`model-catalog.edn`, `cost-table.edn`, `client-defaults.edn`).

## Changes

- Added `components/llm/resources/llm/backends.edn` holding the
  pure-data fields of each backend, keyed by backend keyword:
  `:cmd`, `:streaming?`, `:description`, `:provider`, `:requires-cli?`,
  `:prompt-via`, `:probe-endpoint`, and (for `:ollama`)
  `:api-endpoint`, `:default-model`, `:models`.
- In `llm_client.clj`, replaced the `backends` literal with:
  - `load-backend-data` / `backend-data` — reads the EDN resource (same
    `io/resource` + `edn/read-string` pattern the component already
    uses for `client-defaults.edn`; throws `:anomalies/not-found` if the
    resource is missing).
  - `backend-fns` — the per-backend function-valued fields kept in code.
  - `backends` — `(merge-with merge backend-data backend-fns)`.
- The in-code comments explaining prompt delivery, OpenCode credential
  handling, and the `:echo` probe-endpoint were moved/retained next to
  the fields they describe.

## What stayed in code, and why

The function-valued fields cannot be expressed as data in EDN; they are
references to fns defined in this namespace and must stay in Clojure:

- `:claude` and `:codex`: `:stream-parser` (`parse-claude-stream-line` /
  `parse-codex-stream-line`) and `:args-fn` (`claude-args` /
  `codex-args`).
- `:cursor`, `:opencode`, `:echo`: `:args-fn` (`cursor-args` /
  `opencode-args` / `echo-args`).
- `:ollama` has no function-valued fields; its entry is fully data.

The `:echo` backend's `:probe-endpoint` was previously the var
`generic-connectivity-probe-url`, whose value is the string
`"https://1.1.1.1/"`. The EDN uses that literal string, which equals the
var's value. `generic-connectivity-probe-url` remains defined in code
because `probe-endpoint-for` still references it as the fallback for
backends without an explicit endpoint.

## Verification

- Equivalence smoke against the original literals: for each of the six
  backends, the data fields (`:cmd`, endpoints, `:default-model`,
  `:models`, and the other scalars) equal the pre-refactor values; the
  per-backend key set is unchanged; `:stream-parser` / `:args-fn` are
  present where they were before and are `fn?`. Result: pass.
- `llm` component tests via the cognitect test-runner over
  `components/llm/test`: 164 tests, 942 assertions, 0 failures, 0
  errors. This includes `args-fn-test`, which exercises the retained
  `:args-fn` references.
- `bb poly:check`: OK.
