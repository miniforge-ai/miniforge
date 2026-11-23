<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(llm): split llm_client.clj into thirteen namespaces (rule 210)

## Overview

Mechanical rule 210 split of
`components/llm/src/ai/miniforge/llm/protocols/impl/llm_client.clj` into the core
namespace and twelve `llm-client.*` namespaces, each within the 3-layer budget. No
behavior change.

## Motivation

The file holds 150 definitions over 11 call-graph layers against a budget of 3. Any
edit to it arms stratum-lint, which then fails SL003. Two follow-up changes to the LLM
client need to edit it, and those changes should pass stratum-lint without an opt-out.

## Changes in Detail

1. `llm-client.responses`: the success and failure builders, the legacy-category to
   canonical-anomaly map, the category for a provider HTTP status, and the canonical
   stop reason.
2. `llm-client.settings`: the `llm/client-defaults.edn` and `llm/backends.edn`
   resources, `getenv-value`, and the on/off environment switches.
3. `llm-client.prompts`: building the prompt, its delivery mode (argv or stdin), the
   pre-flight size gauge, and the context-overflow check.
4. `llm-client.cli-args`: the agent CLI argument builders.
5. `llm-client.cli-output`: the Claude and Codex stream parsers, the stream
   accumulator, and the classification of CLI output into responses.
6. `llm-client.backend-registry`: `backends` and the probe endpoint each backend
   resolves to.
7. `llm-client.http-wire`: request bodies, response extractors and response parsing
   for the direct HTTP providers.
8. `llm-client.http-backend`: the POST transport, key and endpoint resolution,
   headers, the provider registry, and `http-complete`.
9. `llm-client.subprocess`: process environment, stdin, stdout and stderr readers,
   the process-tree kill, and the capture process start.
10. `llm-client.stream-reader`: the stdout reader daemon, the line queue and its
    signals.
11. `llm-client.stream-exec`: `stream-exec-fn`, the stream loop, the stream-timing
    settings, the keepalive interval and the default progress monitor.
12. `llm-client.completion`: `complete-impl`, its logging, and the exec-fn adapters.
13. `llm-client`: `complete-stream-impl` with the handlers it dispatches to,
    `get-config-impl`, `default-exec-fn` and `stop-capture-process!`, plus 50
    one-line aliases that keep each name a caller uses.

143 definitions move unchanged and 7 stay in core. The edits are cross-namespace
qualification, public visibility for 39 private definitions another namespace now
reaches, the aliases, and the core docstring. Core calls every aliased definition
through its alias, as main called those vars, so a redefinition of
`llm-client/stream-exec-fn`, `llm-client/complete-impl` and the other aliased names
still steers `complete-stream-impl` and its handlers. `stop-capture-process!` stays in
core so the test that redefines `llm-client/wait-for-stream-process` and
`llm-client/post-kill-join-timeout-ms` still steers it. Moved definitions that nothing
outside the split uses get no alias (no-dead-code).

Two comments changed: the block describing the response builders now sits above
`llm-success` instead of `load-client-defaults`, and the comment above
`load-backend-data` names `backend-registry` as the home of the function-valued
backend fields. The error message in `->canonical-anomaly` still names
`ai.miniforge.llm.protocols.impl.llm-client` as the home of `category->type`; it is
left unchanged because it is runtime output.

Limits of the aliases: they do not carry `:arglists`, and reloading only a
sub-namespace at a REPL leaves an alias holding the old function until `llm-client`
reloads. Redefining an `llm-client/<name>` alias reaches only the forms left in core:
calls made inside the sub-namespaces go to the sub-namespace var (`http-backend/http-post-request`,
`backend-registry/backends`, `stream-exec/...`), so a new test must redefine that var.
No current test relies on the old behaviour; the five that steered moved code now
target the new vars.

Two other edits outside the moved text: `getenv-value`'s docstring names the var to
redefine, and the unmapped-category error message names
`llm-client.responses`, where `category->type` now lives. The `::message-id`
keyword inside `fold-message-usage` now carries the `cli-output` namespace; it is
written and read only inside that function, in metadata that is never serialized.

Four redefinition targets cannot stay steerable through core: the callers of
`getenv-value`, `http-post-request`, `start-stream-process!` and
`process-join-timeout-ms` sit too deep in the call graph to fit in core's three
layers. The tests that redefine them now point at the new homes:
`settings/getenv-value` (http-providers, network-health and network-probe-proxy
tests), `http-backend/http-post-request` (http-providers test),
`stream-exec/start-stream-process!` (interface test) and
`stream-exec/process-join-timeout-ms` (stream-process-cleanup test). Each of those test
files stays within 3 layers.

## Testing Plan

1. Form-by-form comparison of every definition against main, after normalizing
   stratum numbers, `defn-`/`^:private` and namespace prefixes: 150 checked, 0
   differences, 50 aliases, no other added definition.
2. stratum-lint `--fix` then check on the thirteen namespaces and the five edited
   tests: pass. clj-kondo: no errors or warnings.
3. A scan of every `with-redefs`, `with-redefs-fn`, `binding`, `alter-var-root`,
   `#'` and `ns-resolve` use of this namespace in the repo; only `components/llm/test`
   reaches it.
4. All 17 `components/llm/test` namespaces under the shared JVM lock: 232 tests, 1336
   assertions, 0 failures, 0 errors.

## Deployment Plan

Ships with the next release; no configuration or data change.

## Related Issues/PRs

1. Precedent: #2260 (artifact-session split).
2. Open PRs #2081 and #2089 edit `llm_client.clj` and will need their hunks moved to
   the split namespaces. #2081 touches `claude-args` and adds a definition beside
   the Claude input-format flags (now `cli-args`), adds a helper beside
   `resolve-prompt-via` (now `prompts`), and edits `complete-impl` (now
   `completion`) and `handle-streaming` and `complete-stream-impl` (still in
   `llm-client`). #2089 rewrites `rate-limited?` (now `cli-output`).

## Checklist

- [x] Every definition moved unchanged (normalized comparison)
- [x] Each namespace within 3 layers
- [x] LLM test namespaces pass
- [ ] Independent review settled
- [ ] CI green, Copilot comments resolved
