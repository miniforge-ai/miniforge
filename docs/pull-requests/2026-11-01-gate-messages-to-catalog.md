<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# refactor: Move every gate message into the gate catalog

## Overview

Every user-visible string in the gate component now lives in its message
catalog (`config/gate/messages/en-US.edn`, or `system.edn` for log text),
with the text unchanged. A new test fails when a gate uses a key the
catalog does not have.

## Motivation

The gate component already has `config/gate/messages/en-US.edn` and
`system.edn`. The stale-references, no-secrets, behavioral, opsv,
policy-pack and tests-pass gates read their text from those catalogs. The
rest did not. Their messages, registry descriptions and repair texts were
English literals. These included the Codex consultation verdicts and description.
They also covered syntax, lint, format, pre-verify lint, plan, review, quality,
release, policy, coverage and pre-commit discipline. Mechanical capability
descriptions also used literals. Registry unknown-gate and noop entries did too,
as did the interface's missing-repair failure. There were 45 strings in all,
counting the six added in review.

## Changes in Detail

- Each literal moves to a catalog key with the same text. Two were built
  with `str`, `:registry/unknown-gate {gate}` and
  `:coverage/below-threshold {coverage} {threshold}`. The translator
  substitutes `(str v)`, so the output matches the old `str` exactly,
  including the leading colon on a keyword gate name.
- User-facing text goes to `en-US.edn`. The one log line, lint's
  "Policy-pack check threw; lint gate fails closed", goes to `system.edn`
  through `system-t`, the same split `decide` uses.
- The one text change: pre-commit discipline's repair message was a
  multi-line literal, and the source indentation was part of the string.
  The catalog entry has the same lines without that indentation.
- `syntax`, `lint`, `format`, `pre-verify-lint`, `capabilities`,
  `codex-consultation`, `precommit-discipline`, `registry` and `interface`
  now require `ai.miniforge.gate.messages`. That namespace depends only on
  the messages component, so this adds no require cycle. `registry` gains
  its first require.
- New `messages_test.clj` checks that every key a gate can render has a
  catalog entry. A missing key does not throw. It renders as the bare key
  name, so a gate result saying "skipped" reads as a word rather than a
  defect. Keys reach the translator in two ways, and the test collects
  both:
  - Directly in the call, including keys chosen inline, as in
    `(t (if (= 1 n) :tests-pass/failed-one :tests-pass/failed))`. A
    reader walk collects every namespace-qualified keyword literal in
    the first argument of each `t` or `system-t` call. Unqualified
    keywords are skipped, because there they are lookup paths, such as
    `(get-in gate-copy [gate-id :description])`.
  - Through opsv's `gate-copy` table. The test reads the namespace's own
    table, so it checks the inventory the gate renders: six gates, three
    fields each.

  Minimum-count guards keep an empty scan from passing vacuously.

Literals still left in gate sources: the rich-comment example blocks,
machine strings (git argv, URIs, glob prefixes), and one programmer-error
`ex-info` in `pre_verify_lint/execution.clj`. Dewey 050 exempts all three
classes. Regex sweeps missed six prose literals, all of which Copilot
caught and which are now catalogued:

- the capability failure line and its fallback
- the missing format-check-contract message
- the formattable-file count
- the "Gate validation failed" anomaly in the response chain
- the "<gate> gate check threw: <exception>" diagnostic

The final inventory came from a reader walk that collected every string
literal outside a docstring in the gate sources. The only prose left is
the one programmer-error `ex-info`. The
anomaly text goes to `system.edn`, the default when the audience is
unclear (050).

## Testing Plan

Run here with Clojure 1.12 from Maven Central. Clojars is 403 through
this environment's proxy, so only namespaces without Clojars
dependencies could load:

- `messages-test`: 1 test, 110 assertions, passing on main after merge. It was also run against
  a copy of the catalog with `:tests-pass/failed-one` and
  `:opsv/abort-remediation` deleted. Both deletions were reported, so the
  test catches the indirect keys.
- `codex-consultation-test`: 6 tests, 13 assertions, pass.
- `precommit-discipline-test`: 12 tests, 43 assertions, pass. It asserts
  on the moved message text.
- Every edited source file and both catalogs read cleanly.

Not run here: `registry`, `policy`, `lint`, `format`, `tests-pass`,
`capabilities` and `interface` tests. They need malli, babashka.fs or
slingshot from Clojars. CI runs them.

## Deployment Plan

No configuration or data migration. Rendered text is byte-identical except
for the pre-commit repair message, which loses the source indentation that
had leaked into the literal.

## Related Issues/PRs

- Follow-up to the Codex §7.4.3 consultation gate.
- Three Copilot review rounds found literals the earlier sweeps had
  missed: indirect keys the first test version did not see, and six prose
  literals. All were fixed before merge.
- Merged as `8e016d1`.

## Checklist

- [x] Catalog keys and their uses are covered by `messages-test`
- [x] Rendered text checked byte-for-byte against the replaced literals
- [x] CI green on a head containing current main
- [x] Copilot findings fixed and resolved
