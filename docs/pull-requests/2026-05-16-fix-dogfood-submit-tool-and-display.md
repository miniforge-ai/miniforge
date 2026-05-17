# Fix: Submit MCP tool schema (Anthropic 400) + honest phase outcome display

## Overview

Two narrow fixes pulled out of the 2026-05-16 dogfood of
`work/event-log-tool-visibility.spec.edn`, plus a regression test that
pins the underlying invariant so the bug class cannot recur. A third
blocker — silent `bb miniforge resume` fast-fail — is captured as a
follow-up spec rather than fixed here because it needs design
discussion on the FSM/snapshot resume contract.

## Motivation

Dogfooding hit three blockers in quick succession. They all converge
on the same theme: the system was lying to the operator.

1. **Submit MCP tool returned 400 on every call.** PR #872 reintroduced
   the `submit` tool with property keys `"code/summary"`,
   `"code/tests-needed?"`, and `"code/dependencies-added"`. Anthropic's
   tool-schema validator rejects any property key outside
   `^[a-zA-Z0-9_.-]{1,64}$`, so every implementer invocation that
   loaded the tool produced:

   ```
   API Error: 400 tools.12.custom.input_schema.properties:
   Property keys should match pattern '^[a-zA-Z0-9_.-]{1,64}$'
   ```

   The implementer agent then exited without calling `submit`, the
   runtime fell back to file-artifact-fallback, and the cycle repeated
   every phase. Net effect — the structured submission channel we just
   exposed was unreachable from Claude Code.

2. **Phase outcome display reported failures as success.** Even after
   file-artifact-fallback recovered and every gate passed, the implement
   phase was tagged `:failure` (because the underlying LLM call had
   errored) and rendered as `✓ Phase :implement failure` in green. The
   green checkmark in front of the word "failure" makes failures look
   like successes in scrollback and in dogfood post-mortems.

3. **`bb miniforge resume` fast-fails silently.** Two consecutive resumes
   of a 4178-event workflow returned status `:running` with no actual
   phase execution and exited 0. Captured as
   `work/workflow-resume-status-handling.spec.edn` — the fix touches
   the FSM/snapshot resume contract and is too entangled for this PR.

## Changes in Detail

### Submit MCP tool — wire-safe property keys + alias mapping

- Rename `submit` input-schema properties to `code_summary`,
  `code_tests_needed`, `code_dependencies_added` (underscores; safe
  against Anthropic's validator).
- Add a `:param-aliases` map to the tool registry entry that maps each
  wire key back to the namespaced EDN keyword the runtime persists
  (`:code/summary`, `:code/tests-needed?`, `:code/dependencies-added`).
- Apply aliases in `tools/handle-tool-call` before dispatching to the
  handler, so handlers see ready-to-spit EDN maps without per-handler
  keywordize ceremony.
- Delete the now-unused `string-key->keyword` and
  `keywordize-artifact-keys` helpers in `context_cache.clj`, plus the
  `clojure.walk` import.
- Update the implementer prompt so agents are told the wire form
  (`code_summary`) with a one-line note explaining the underscore
  convention and the EDN mapping the runtime performs.

### Phase outcome display — color and glyph match the outcome

- `format-event-line` for `:workflow/phase-completed` now switches on
  `:phase/outcome`: `:success` → green `✓`, `:failure` → red `✗`,
  `:skipped` → yellow `○`, anything else → green `✓` (backward
  compatible).
- Template `:workflow-runner/phase-completed` gains a `{symbol}`
  placeholder so the glyph follows the color.

### Regression guard — Anthropic property-key pattern

- New test namespace `ai.miniforge.mcp-context-server.tools-test`
  walks every registered tool's `:inputSchema` and asserts every
  property key satisfies `^[a-zA-Z0-9_.-]{1,64}$`. Future tools that
  reach for a namespaced key fail at unit-test time, not at the first
  agent call.
- Same namespace covers `apply-param-aliases` (renames listed keys,
  passes others through, nil/empty aliases are identity) and asserts
  the `submit` tool's `:param-aliases` map covers every declared
  property.

### Follow-up spec

- `work/workflow-resume-status-handling.spec.edn` captures the silent
  resume fast-fail: CLI must exit non-zero when `run-pipeline` returns
  a non-terminal status, and `Resuming from phase` must reflect the
  FSM snapshot rather than the first pipeline phase.

## Testing Plan

- `bases/mcp-context-server` focused tests:
  ```
  clojure -A:test:dev -M -e "(require 'clojure.test
    '[ai.miniforge.mcp-context-server.tools-test]
    '[ai.miniforge.mcp-context-server.context-cache-test])
    (clojure.test/run-tests
      'ai.miniforge.mcp-context-server.tools-test
      'ai.miniforge.mcp-context-server.context-cache-test)"
  ```
  → 27 tests, 69 assertions, 0 failures.
- `bases/cli` display tests:
  ```
  clojure -A:test:dev -M -e "(require 'clojure.test
    '[ai.miniforge.cli.workflow-runner.display-output-test]
    '[ai.miniforge.cli.main-test])
    (clojure.test/run-tests
      'ai.miniforge.cli.workflow-runner.display-output-test
      'ai.miniforge.cli.main-test)"
  ```
  → 51 tests, 130 assertions, 0 failures.
- `bb pre-commit` — to be run on the committed branch before merge.

## Deployment Plan

Merge normally. The submit tool change is a wire-format break for any
external caller already using the old slash-keyed payload — but the
only caller is the implementer agent, whose prompt is updated in this
PR. The display change is purely cosmetic.

## Related Issues/PRs

- Regresses: PR #872 (exposed the submit tool with namespaced property keys).
- Dogfood checkpoint: `3927baf8-c9db-44d3-b5fb-5a1552dbe554` (5/16 run).
- Spec: `work/event-log-tool-visibility.spec.edn`.
- Follow-up: `work/workflow-resume-status-handling.spec.edn` (new in
  this PR).

## Checklist

- [x] Submit tool property keys pass Anthropic's validator.
- [x] Alias plumbing covers every declared property of the submit tool.
- [x] Regression test guards every registered tool against the pattern.
- [x] Phase display color and glyph match outcome.
- [x] Implementer prompt teaches the wire form, not the EDN form.
- [x] Follow-up resume spec filed.
- [ ] `bb pre-commit` green on the pushed branch.
