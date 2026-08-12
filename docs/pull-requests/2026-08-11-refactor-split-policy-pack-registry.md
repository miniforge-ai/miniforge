<!--
  Title: Split policy-pack/registry.clj (rule 210)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(policy-pack): split registry.clj (rule 210)

## Overview

Splits `ai.miniforge.policy-pack.registry`'s pure version-comparison,
glob-matching, rule-applicability, dedup, and signature-decoding
helpers out into a new sibling namespace,
`ai.miniforge.policy-pack.registry.support`, resolving a stratum-lint
SL003 finding (the combined namespace measured 5 real layers, over
the rule 210 budget of 3).

## Motivation

Part of the stratum-lint rule-210 remediation program's policy-pack
Wave 2, batch 2. `registry.clj` (452 lines) is the CRUD/composition
protocol for policy packs plus its in-memory implementation — six
files repo-wide reference the namespace (not a zero-fan-in file), so
every caller needed checking, not just the definitions.

## Changes in Detail

- New file `registry/support.clj`: `parse-datever`, `glob-matches?`,
  `decode-signature`, `dedupe-by-id` (Layer 0), `compare-versions`,
  `rule-applies?` (Layer 1, over `parse-datever`/`glob-matches?`),
  `latest-version` (Layer 2, over `compare-versions`) — 3 layers.
  Committed as a new-file-only commit ahead of the wire-up, same
  pattern as `knowledge_safety.clj` (#1731 refers to it as
  `detectors.clj`) and the `workflow_runner.clj` train
  (miniforge#1662-#1667).
- `registry.clj`: keeps the `PolicyPackRegistry` protocol and the `t`
  translator (Layer 0), the `InMemoryPackRegistry` defrecord (Layer 1,
  now calling `support/latest-version`, `support/rule-applies?`,
  `support/dedupe-by-id`, `support/decode-signature` via the qualified
  require instead of same-file symbols), and `create-registry`
  (Layer 2) — down from 5 real layers to 3.
- `interface/registry.clj`: the component's Polylith interface
  re-exports `glob-matches?` and `compare-versions` as public defs;
  both now point at `registry-support/*` instead of `registry/*`. The
  umbrella `ai.miniforge.policy-pack.interface` re-exports these
  transitively through `interface.registry` and needed no direct
  change.
- `applicability.clj` and `external.clj`: both called
  `registry/glob-matches?` directly; both now require
  `ai.miniforge.policy-pack.registry.support` instead of
  `ai.miniforge.policy-pack.registry` (that was their only use of the
  `registry` namespace) and call `registry-support/glob-matches?`.
- No other caller needed changes. `registry_signature_test.clj` and
  `anomaly/registry_anomaly_test.clj` only call symbols that stayed in
  `registry.clj` (`create-registry`, `verify-signature`,
  `->InMemoryPackRegistry`, `register-pack`, `import-pack`,
  `export-pack`). `rules/pack_version_constraint_test.clj`'s
  `#'sut/compare-versions` looked like a possible fourth caller from a
  symbol-only grep, but `sut` there aliases
  `pack-dependency-validation`, an unrelated namespace with its own
  `compare-versions` — confirmed by reading the file's `ns` form, not
  assumed.

This is pure code motion — no logic changes. The def set is unchanged;
every moved function's body, docstring, and `:stratum` metadata is
byte-for-byte identical to what it was in `registry.clj` (the Layer
0/1/2 grouping in `support.clj` mirrors the original file's own
Layer 0/1/2, before its Layer 3/4 `InMemoryPackRegistry`/
`create-registry` collapse to Layer 1/2 in the parent).

`external.clj` carries its own pre-existing, unrelated SL003 finding
(5 real layers, already documented in its own namespace docstring as
part of this same sweep's queue) — only its `:require` and one call
site changed here, not its layer structure, so the wire-up commit used
`MINIFORGE_STRATUM_BUDGET_MODE=warn` to avoid blocking on a violation
this PR doesn't touch.

## Testing Plan

- `stratum-lint` clean (exit 0) on `registry.clj`,
  `registry/support.clj`, `applicability.clj`, and
  `interface/registry.clj` — the four in-scope files. `external.clj`
  still reports its pre-existing SL003 (documented above, tracked
  separately).
- `clj-kondo` clean (0 errors, 0 warnings) across all five touched/new
  files.
- Repo-wide fan-in grep for the fully-qualified namespace
  (`ai\.miniforge\.policy-pack\.registry\b`) across `components`,
  `bases`, and `projects` before starting: six callers found, all
  accounted for above. `projects/miniforge/test/` specifically checked
  and clean — no project-level integration test references this
  namespace.
- Full `policy-pack` component test suite run directly (`clojure -M
  -e ...`) rather than only through `bb test`, since this session's
  `bb test` was contending with a large number of concurrent sibling
  rule-210 split PRs running in parallel worktrees on the same
  machine: 294 tests, 2689 assertions, 0 failures, 0 errors. The four
  tests directly touching this change
  (`registry-signature-test`, `anomaly.registry-anomaly-test`,
  `interface-test`, `external-test`) also verified in isolation: 33
  tests, 144 assertions, 0 failures.
- Both commits' pre-commit hooks (`clojure -M:poly check`, clj-kondo,
  stratum-lint, and the pre-commit smoke suite: 345 tests / 1301
  assertions component-smoke + 8 tests / 623 assertions GraalVM
  compatibility) passed clean.

## Deployment Plan

Merges to `main` immediately; no follow-up needed for this file. This
was task #15 of the policy-pack Wave 2 batch 2 sweep; the sweep
continues with other files in the queue (`external.clj`'s own SL003
finding among them).

## Related Issues/PRs

- Part of the stratum-lint rule-210 Wave 2 continuation — see
  `knowledge_safety.clj` (miniforge#1731) for the sibling-namespace +
  new-file-then-wire-up convention this follows, and
  `workflow_runner.clj` (miniforge#1662-#1667) for the original
  two-commit-PR pattern.

## Checklist

- [x] stratum-lint clean on all four in-scope resulting files
- [x] Full policy-pack component test suite green (294 tests / 2689
      assertions), plus the four directly-affected test namespaces in
      isolation
- [x] Adversarial self-review: def set unchanged (relocated only), no
      logic changes
- [x] Fan-in confirmed via fully-qualified-namespace grep across
      components, bases, and projects; every caller updated or
      confirmed unaffected
- [x] PR budget: 228 reportable lines total, split 98/130 across two
      commits (both under the 200-line commit ceiling), well under the
      600-line PR ceiling
