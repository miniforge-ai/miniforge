<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Split implementer output into a curator step

## Context

In the environment-promotion model the implementer writes files directly into
the executor environment (capsule or worktree); the environment itself is the
artifact. The existing implementer, however, still tried to produce a
structured `:code/...` Clojure map out-of-band via three fallback paths: an
MCP `submit_code_artifact` tool, a pre/post file snapshot synthesizer, and a
text-EDN parse. That three-path design was observed to fail in a dogfood run
on 2026-04-16 where the implementer spent 56 minutes and 6 retries producing
`"LLM response could not be parsed as code artifact"` with no files ever
written to the environment. The workflow terminated only because of a
pipeline-level `max-redirects-exceeded` safety valve — not because of any
task-level budget or diagnosis.

This change introduces a dedicated **curator** that runs after the
implementer. The curator reads the env diff, produces the structured metadata
downstream phases consume, and **fast-fails in seconds** when the implementer
wrote nothing — replacing the multi-retry silent-failure mode with a clear
`Curator: implementer wrote no files to the environment` error.

The two-stage (generator + curator) pattern is attribution-tagged to
`obra/superpowers` (MIT, Jesse Vincent), specifically the
`subagent-driven-development` skill. This is M0a of the Superpowers
methodology-integration plan; M0b (stripping the now-vestigial MCP /
text-parse paths from the implementer) is a separate follow-up PR.

## What changed

| File | Kind | Purpose |
|---|---|---|
| `components/agent/src/ai/miniforge/agent/curator.clj` | new | Curator implementation: deterministic skeleton + optional LLM enrichment. |
| `components/agent/resources/prompts/curator.edn` | new | System prompt for the LLM-enrichment branch. Strict EDN output, 3-turn budget. |
| `components/agent/test/ai/miniforge/agent/curator_test.clj` | new | 11 tests, 40 assertions, covers happy path, scope-deviation detection, test-file heuristics, summary formatting, language inference, metrics, and empty-diff fast-fail. |
| `components/agent/src/ai/miniforge/agent/interface/specialized.clj` | modify | Require the curator namespace; export `curate-implement-output`. |
| `components/agent/src/ai/miniforge/agent/interface.clj` | modify | Re-export `curate-implement-output` at the top-level agent interface. |
| `components/phase-software-factory/src/ai/miniforge/phase/implement.clj` | modify | In `enter-implement`, invoke the curator after the implementer agent succeeds; curator output replaces the implementer's `:output` while preserving implementer metrics. |

Curator behavior (see docstring in `curator.clj` for detail):

1. **Resolve the diff.** Priority: (a) `:code/files` already present on the
   implementer result, (b) fresh collection via `file-artifacts/collect-written-files-via-executor`
   when running in a capsule, (c) local `snapshot-working-dir` based collection.
2. **Fast-fail on empty diff.** Returns `response/error` with `:data` carrying
   `:worktree-path`, `:env-id`, and `:intent-scope` for diagnostics.
3. **Deterministic enrichment.** Computes `:code/summary`, `:code/tests-added?`
   (test-path heuristic), `:code/scope-deviations` (set-difference against
   `:spec/intent :scope`), and `:code/language` (most-common file extension).
4. **Optional LLM enrichment.** When a pre-resolved `:llm-client` is passed,
   invokes Sonnet with a strict EDN-output prompt for a better summary plus
   `:breaking-change?` and `:rationale`. On any LLM failure, silently falls
   back to the deterministic values — the curator is never a
   single-point-of-failure.
5. **Metric passthrough.** The phase preserves the implementer's token /
   duration metrics and layers on curator metrics: `:files-total`,
   `:files-created`, `:files-modified`, `:files-deleted`,
   `:scope-deviations`, `:tests-added?`, `:curator-source`
   (`:deterministic` | `:hybrid`).

## Verification

```
clojure -M:test -e '(require (quote [clojure.test :as t])
                             (quote ai.miniforge.agent.curator-test))
                    (t/run-tests (quote ai.miniforge.agent.curator-test))'
# → 11 tests, 40 assertions, 0 failures, 0 errors

clojure -M:test -e '(require (quote [clojure.test :as t])
                             (quote ai.miniforge.agent.implementer-test))
                    (t/run-tests (quote ai.miniforge.agent.implementer-test))'
# → 11 tests, 58 assertions, 0 failures, 0 errors  (regression check)
```

Compile-check: `clojure -M:test -e "(require 'ai.miniforge.phase.implement) (require 'ai.miniforge.agent.interface)
(println :compile-ok)"` → `:compile-ok`.

Lint: no new warnings in files touched by this change. Pre-existing repo-wide
lint issues are unrelated.

## Follow-up work

- **M0b** (separate PR): strip `parse-code-response`, `extract-code-blocks`,
  `code-from-blocks`, and the `submit_code_artifact` MCP tool registration
  from `implementer.clj` / `artifact_session.clj`. Update the implementer
  prompt to remove "output a structured code artifact" instructions; keep
  only "write files to disk."
- **Observability**: emit a structured `:curator/curated` event carrying
  `:scope-deviations` and `:curator-source` so the TUI can surface them
  live. Today they only appear in the response metrics map.
- **M1**: retry-budget + debugger subagent (see
  `/private/tmp/mf-retry-budget/specs/active/retry-budget-debugger.spec.edn`).
  With the curator's fast-fail in place, the debugger gets real signal to
  work with instead of the opaque "could not be parsed" failure.
- **Attribution bundle**: `NOTICE` + `LICENSES/superpowers-MIT.txt` land
  with M6 of the methodology-integration plan.
