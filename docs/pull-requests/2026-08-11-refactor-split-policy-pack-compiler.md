<!--
  Title: Split policy-pack/compiler.clj (rule 210)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(policy-pack): split compiler.clj (rule 210)

## Overview

Splits `ai.miniforge.policy-pack.compiler` (317 lines, SL003: 8 real
layers, max 3) into a parent plus three sibling namespaces under
`compiler/`, each holding one stage of the same detector-resolution →
check-building → pack-compilation pipeline. Pure code motion — no
detection or compilation logic changed.

## Motivation

Part of the stratum-lint rule-210 remediation program, policy-pack
Wave 2 batch 2 (task #9). `compiler.clj` compiles a policy pack's
rules into executable N4 check entries; the whole pipeline — resolve
a rule's detector, normalize artifacts, build a result, wrap it in an
executable check-fn, compile a whole pack — lived in one namespace and
had grown to 8 real dependency layers.

## Changes in Detail

- New file `compiler/artifacts.clj`
  (`ai.miniforge.policy-pack.compiler.artifacts`): `code-files`,
  `code-files-present?`, `file-entry->artifact` (layer 0),
  `normalize-artifacts` (layer 1, private), `artifact-inputs` (layer
  2) — N4 check-input normalization. 3 layers.
- New file `compiler/check.clj`
  (`ai.miniforge.policy-pack.compiler.check`): `by-type-detectors`,
  `rule-enabled?`, `detector-class`, `semantic-context-ready?`,
  `violation-with-rule`, `exception-violation`,
  `missing-semantic-wiring-violation` (layer 0); `resolve-detector`,
  `rule-metadata`, `detect-rule-violations` (layer 1); `check-result`
  (layer 2). Requires `compiler.artifacts` for `detect-rule-violations`.
  3 layers.
- New file `compiler/rule.clj` (`ai.miniforge.policy-pack.compiler.rule`):
  `compile-check-fn` (layer 0, private), `compile-rule-check` (layer
  1). Requires `compiler.check`. 2 layers.
- `compiler.clj`: now requires `compiler.check` and `compiler.rule`.
  `resolve-detector`, `rule-enabled?`, and `compile-rule-check` become
  thin re-export `def`s pointing at the sibling implementations, so
  every existing external caller of
  `ai.miniforge.policy-pack.compiler/...` keeps working unchanged.
  `anomaly-rule-id`, `compiled-entry-detector`, `compile-pack-checks`,
  and `compile-pack` keep their real implementations here — pack-level
  compilation is this namespace's own reason to exist, following the
  `mdc_compiler.clj` split's convention of keeping the outermost
  orchestration in the parent rather than re-exporting it too. 3
  layers (down from 8).

### Visibility changes (the only non-motion changes)

Five defs go from `defn-`/private to `defn`/public because a sibling
namespace now calls them cross-namespace; no signature, name, or
logic changed:

- `compiler.check/semantic-context-ready?`
- `compiler.check/exception-violation`
- `compiler.check/missing-semantic-wiring-violation`
- `compiler.check/detect-rule-violations`
- `compiler.artifacts/artifact-inputs`

`compiler.check/rule-enabled?`, `compiler.check/resolve-detector`, and
`compiler.check/check-result` were already public in the original file
— unchanged. `compiler.artifacts/normalize-artifacts`,
`compiler.check/detector-class`, `compiler.check/violation-with-rule`,
`compiler.check/rule-metadata`, and `compiler.rule/compile-check-fn`
stay private — each is used only within the file it now lives in.

One local parameter rename, no observable effect: `detect-rule-violations`'s
`artifacts` parameter is renamed to `artifacts-input` in
`compiler.check` because that file now also requires the sibling
namespace `ai.miniforge.policy-pack.compiler.artifacts` aliased as
`artifacts` — Clojure resolves `artifacts/artifact-inputs` via the
alias table regardless of a local binding of the same name, so this
wasn't strictly required, but keeping the two apart avoids a confusing
read.

## Fan-in Check

Grepped the fully-qualified namespace (not a guessed alias prefix)
across `components`, `bases`, and `projects`:

```bash
grep -rlE "ai\.miniforge\.policy-pack\.compiler\b" --include='*.clj' components bases projects
```

Four files, all already accounted for by the re-export design:

- `components/policy-pack/src/ai/miniforge/policy_pack/interface/checking.clj`
  — re-exports `resolve-detector`, `rule-enabled?`, `compile-rule-check`,
  `compile-pack-checks`, `compile-pack` as `compiler/...`. Unaffected —
  those five names still resolve from `ai.miniforge.policy-pack.compiler`
  with identical semantics.
- `components/policy-pack/test/ai/miniforge/policy_pack/compiler_test.clj`
  — exercises the same five public fns via `sut/...`. Unaffected.
- `components/policy-pack/test/ai/miniforge/policy_pack/builtin_detectors_test.clj`
  — calls `compiler/resolve-detector`. Unaffected.
- `components/policy-pack/src/ai/miniforge/policy_pack/compiler.clj`
  — the file being split.

No project-level caller found; `projects/miniforge/test/` has no file
referencing this namespace. Zero call sites required a change.

## Testing Plan

- `stratum-lint` (with `--fix`) clean on all four touched files (exit
  0 on the target and every new sibling; was SL003 exit 1 on the
  original).
- `bb lint:clj` (clj-kondo) clean on all four files.
- `bb pre-commit` (commit-budget, `poly check`, lint, stratum-lint,
  pre-commit smoke tests, GraalVM/Babashka compatibility) green on
  both commits.
- `bb test` (change-scope, the poly test runner) did not finish in a
  useful time in this session — the backing JVM process sat mostly
  idle rather than progressing (17 minutes wall clock, <3 minutes CPU
  time), so it was killed rather than trusted. Verified directly
  instead: `cd components/policy-pack && clojure -M:test -e
  "(require 'ai.miniforge.policy-pack.compiler-test
  'ai.miniforge.policy-pack.builtin-detectors-test)
  (clojure.test/run-tests
  'ai.miniforge.policy-pack.compiler-test
  'ai.miniforge.policy-pack.builtin-detectors-test)"` — 20 tests, 77
  assertions, 0 failures. Broadened to the other namespaces this split
  touches transitively (`detection-test`, `capability-test`,
  `knowledge-safety-test`, `interface-test`, `mdc-compiler-test`) the
  same way — 86 tests, 460 assertions, 0 failures — and re-ran after
  rebasing onto `main` to confirm the rebase didn't change the result.
- No project-level integration test references this namespace (fan-in
  check above), so no separate `clojure -M -e` verification against
  `projects/miniforge/test/` was needed for this file, unlike the
  `knowledge_safety.clj` split.

## Deployment Plan

Merged to `main` immediately (miniforge#1766, squash commit
`22e04a58f8d4f5c900f6bf26bc92e2fe54729d0c`); no follow-up needed for
the split itself. This doc file is added retroactively in a doc-only
follow-up — it was written before the split PR but never `git add`ed
to its commits, an oversight caught in the post-merge sweep. No code
changes are part of this follow-up.

## Related Issues/PRs

- miniforge#1766 — this split.
- Part of the stratum-lint rule-210 remediation program, policy-pack
  Wave 2 batch 2 (task #9). Follows the established splitting
  convention from `workflow_runner.clj` (miniforge#1662-#1667),
  `knowledge_safety.clj` (miniforge#1731), and the `mdc_compiler.clj`
  train (miniforge#1729-#1743) — extract cohesive layer-groups into
  sibling files under a subdirectory named after the original file,
  keep the outermost public orchestration in the parent, re-export
  any lower-level public API the parent used to implement directly.

## Checklist

- [x] stratum-lint clean on all resulting files
- [x] `bb lint:clj` clean
- [x] `bb pre-commit` green on both commits
- [x] `bb test` (poly runner) hung and was killed; verified directly
      via `clojure -M:test` instead (86 tests, 460 assertions, 0
      failures across the affected namespaces) — see Testing Plan
- [x] Adversarial self-review: def set unchanged (relocated + 3
      re-export defs added: `resolve-detector`, `rule-enabled?`,
      `compile-rule-check`), 5 visibility flips documented above, one
      cosmetic local-parameter rename, no logic changes
- [x] Zero fan-in confirmed repo-wide before starting; all four
      matches accounted for, zero required a code change
