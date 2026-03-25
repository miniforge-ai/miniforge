# feat: human meta-loop checkpoint model

## Overview

Introduces the first implementation slice for the human meta-loop spec:
a canonical normalized decision model that both the control-plane queue and
inner-loop escalation path can emit.

This does not build the inbox, rule miner, or delegated-mode runtime yet.
It establishes the shared artifact those later features need: a versioned,
structured checkpoint and episode shape.

The branch also includes a small reader-fix in `components/algorithms` for
`graph.clj`. That parse error was already present on `origin/main` after the
rebase and blocked `bb pre-commit` from completing, so it is included here to
keep the branch reviewable and commit hooks green.

## Motivation

The current repo has two adjacent but separate concepts:

- control-plane decisions for external or supervised agents
- loop escalation for retry-budget exhaustion

Both represent judgment checkpoints, but they use incompatible shapes.
That makes it hard to build one inbox, one audit trail, and one eventual
delegation-acquisition pipeline.

This PR starts by normalizing the data before expanding UI or automation.

## Changes In Detail

### 1. New `decision` component

Adds a pure component for canonical decision artifacts:

- `DecisionCheckpoint`
- `DecisionEpisode`
- constructors and update helpers
- producer helpers for control-plane decisions and loop escalations

### 2. Control-plane queue enrichment

`components/control-plane` now keeps its existing `:decision/*` API while
attaching:

- `:decision/checkpoint`
- `:decision/episode`

That preserves current consumers while giving downstream features a shared
normalized representation.

### 3. Loop escalation bridge

`components/loop` now emits the same normalized checkpoint/episode model when
an inner loop escalates or aborts.

This makes loop escalation a first-class checkpoint producer instead of an
isolated prompt UX.

### 4. Reader fix for `algorithms/graph.clj`

Repairs a malformed DFS implementation in `components/algorithms` that broke
namespace loading under both Clojure and Babashka/GraalVM.

This is not part of the product feature itself, but it was required to satisfy
normal pre-commit discipline on the rebased branch.

## Testing Plan

Targeted namespace validation:

```bash
CLJ_CONFIG=/tmp/codex-clojure-config clojure -M:test -e "(require '[clojure.test :as t] '[ai.miniforge.decision.interface-test]) (let [r (t/run-tests 'ai.miniforge.decision.interface-test)] (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1)))"

CLJ_CONFIG=/tmp/codex-clojure-config clojure -M:test -e "(require '[clojure.test :as t] '[ai.miniforge.loop.escalation-test]) (let [r (t/run-tests 'ai.miniforge.loop.escalation-test)] (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1)))"

CLJ_CONFIG=/tmp/codex-clojure-config clojure -M:test -e "(require '[clojure.test :as t] '[ai.miniforge.control-plane.interface-test]) (let [r (t/run-tests 'ai.miniforge.control-plane.interface-test)] (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1)))"
```

Full changed-bricks and compatibility validation:

```bash
CLJ_CONFIG=/tmp/codex-clojure-config bb pre-commit
```

## Deployment Plan

Safe incremental change:

- legacy `:decision/*` fields remain intact
- new checkpoint/episode data is additive
- no UI migration is required yet

## Related Issues/PRs

- Human meta-loop spec provided for this branch: `miniforge-human-meta-loop-spec.md`
- Existing escalation UX foundation:
  `docs/pull-requests/2026-01-24-feat-human-escalation-ux.md`

## Checklist

- [x] Canonical checkpoint schema added
- [x] Canonical episode schema added
- [x] Control-plane decisions emit normalized data
- [x] Loop escalation emits normalized data
- [x] Targeted tests passing
- [ ] Unified inbox UI
- [ ] Episode persistence/warehouse
- [ ] Similar-decision retrieval
- [ ] Delegation rule mining
