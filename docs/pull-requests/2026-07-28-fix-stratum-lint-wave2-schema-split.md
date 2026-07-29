# fix(schema): split core.clj and logging.clj to clear the 3-layer stratum budget (SL003, Wave 2)

## Overview

Splits two over-budget files in `components/schema` into a vocabulary
namespace plus a slimmer composite-schema namespace each, so every file
in the component lands at or under the 3-layer `SL003` budget for real
(computed, not decorative) layers. No executable logic changed: def
bodies, docstrings, and validation semantics are copied verbatim to
their new location; only namespace headers, `:require` forms, `Layer N`
headings, and `^{:stratum n}` metadata change.

- `core.clj` (327 lines, 4 real layers) → `vocab.clj` (enums + severity
  pass-throughs + `registry` + `Severity`, 3 layers) + `core.clj`
  (composite entity schemas, 2 layers).
- `logging.clj` (228 lines, 4 real layers) → `logging_vocab.clj` (event
  taxonomy + `all-events` + `logging-registry`, 3 layers) +
  `logging.clj` (composite log/scenario schemas, 1 layer).

## Motivation

This is the Wave 2 follow-on flagged by the Wave 1 mechanical pass for
this component (`docs/pull-requests/2026-07-26-fix-stratum-lint-wave1-schema.md`):
that PR's `--fix` run corrected `core.clj` and `logging.clj` from
decorative (undercounted) 2-layer headings to their real 4-layer
structure, which is genuinely over the 3-layer budget rule 210 sets
(`.cursor/rules/languages/clojure.mdc`). Confirmed independently before
touching anything, via a fresh plain-lint run:

```text
components/schema/src/ai/miniforge/schema/core.clj:225:1: SL003 file uses 4 distinct layers (max 3); split the namespace or extract a component
components/schema/src/ai/miniforge/schema/logging.clj:111:1: SL003 file uses 4 distinct layers (max 3); split the namespace or extract a component
```

Zero `SL001`/`SL002`/`SL004` findings on the component going in — no
upward-reference/cycle risk to reason about, and no decorative-heading
cleanup needed first.

The task that seeded this PR named only `core.clj`, but `logging.clj`
carries the identical defect (same shape: a vocabulary/registry stratum
feeding a composite-schema stratum, one layer too many when kept in one
file) and is explicitly called out as a Wave 2 target in the Wave 1 PR
above. Leaving it unsplit would leave the component's `SL003` count
non-zero, so it's fixed in the same PR rather than deferred again.

## Changes in Detail

### `core.clj` → `vocab.clj` + `core.clj`

Real reference graph in the pre-split file: the base enum vectors
(`agent-roles`, `task-types`, ...) and the policy-clause severity
pass-throughs (`severities`, `normalize-severity`, `severity-order`,
`compare-severity`, `more-severe`) are independent leaves; `registry`
(a malli registry map) is built from those enums; `Severity` (a
constructed malli enum) pulls `:severity` out of `registry`; the eight
"standalone" composite `:map` schemas (`Agent`, `TaskConstraints`,
`TaskResult`, `ArtifactOrigin`, `WorkflowBudget`, `Metrics`,
`MetaAgentConfig`, `MetaAgentHealthCheck`) each reference `registry`
only; and the four top-level composites (`Task`, `Artifact`, `Workflow`,
`MetaCoordinatorState`) embed those standalone composites as sub-schemas
(`Task` embeds `TaskConstraints`/`TaskResult`, `Artifact` embeds
`ArtifactOrigin`, `Workflow` embeds `WorkflowBudget`,
`MetaCoordinatorState` embeds `MetaAgentHealthCheck`). That's 4 real
layers in one file.

New `vocab.clj` takes everything through `Severity` (3 layers: enums →
`registry` → `Severity`) — a self-contained vocabulary namespace with no
dependency on the composite schemas. `core.clj` keeps only the
`:map` composites, now requiring `vocab` for `registry`: the eight
standalone composites collapse to a single real layer (Layer 0 relative
to this file — they only reference `vocab/registry`, not each other),
and the four top-level composites are Layer 1 (they reference the
Layer-0 composites in the same file). 2 real layers, within budget.

### `logging.clj` → `logging_vocab.clj` + `logging.clj`

Same shape. The event/level/category vocabularies (`log-levels`,
`agent-events`, `scenario-tags`, ...) are leaves; `all-events` (Layer 1)
aggregates the event vocabularies; `logging-registry` (Layer 2) merges
`vocab/registry` with logging-specific registry entries built from the
vocabularies and `all-events`. The six composite schemas (`LogContext`,
`ScenarioContext`, `TraceContext`, `PerfMetrics`, `LogEntry`,
`Scenario`) each reference only `logging-registry` — none of them
reference each other (`LogEntry` re-lists the same keys `LogContext`
etc. declare rather than embedding them as sub-schemas; that redundancy
predates this PR and is out of scope here — no behavior change).

New `logging_vocab.clj` takes the vocabularies through `logging-registry`
(3 layers, requires `vocab` for the base `registry` merge). `logging.clj`
keeps only the six composite schemas, now requiring `logging-vocab`:
since none of the six reference each other, they all land at a single
real layer (Layer 0) — a full layer under budget, room to spare.

### `interface.clj`

`interface.clj` re-exports individual symbols by qualified reference
(`core/agent-roles`, `logging/log-levels`, ...), not through `*ns*`
inspection, so nothing here changes shape — only which required
namespace each pass-through def points at changes:

- `agent-roles`, `task-types`, `task-statuses`, `artifact-types`,
  `workflow-phases`, `workflow-statuses`, `severities`, `Severity`,
  `severity-order`, `normalize-severity`, `compare-severity`,
  `more-severe`: `core/*` → `vocab/*`.
- `log-levels`, `log-categories`, `all-events`, `scenario-tags`:
  `logging/*` → `logging-vocab/*`.
- `Agent`, `Task`, `Artifact`, `Workflow`, `TaskConstraints`,
  `TaskResult`, `ArtifactOrigin`, `WorkflowBudget`, `Metrics`,
  `LogEntry`, `Scenario`: unchanged (`core/*` / `logging/*` — these
  symbols didn't move). `registry` itself was never re-exported through
  `interface.clj`, so no change needed there.

`interface.clj`'s own real-layer count is unaffected: its layers are
computed from same-file references between its `def`s, and this PR
doesn't add or remove any same-file dependency there, only repoints
which external var a handful of pass-throughs source from.

### Same-component internal callers

`logging.clj` and `supervisory.clj` both required `ai.miniforge.schema.core`
directly for `core/registry` (and `supervisory.clj` also for
`core/severities`) — legal per rule 210 (same-component files may
reference `.core`/subnamespaces directly; only cross-component calls
must go through `.interface`). Both now require `ai.miniforge.schema.vocab`
instead and reference `vocab/registry` / `vocab/severities`, since that's
where those symbols live now. `logging.clj` additionally now requires the
new `logging-vocab` for its own composite schemas' registry.

A repo-wide grep for `ai.miniforge.schema.core` outside this component
turned up only two comments (in `automation-edge-correlator/schema.clj`
and `-interface.clj`) documenting the cross-component-avoidance pattern,
and one in `supervisory-state/schema.clj` — none are actual requires, so
no cross-component fix was needed. `components/schema/test/` doesn't
reference `.core`/`.logging`/`.supervisory` directly anywhere (only
`.interface`), so no test-file updates were needed either.

## Testing Plan

1. Read `core.clj`, `logging.clj`, `supervisory.clj`, and `interface.clj`
   in full before making any change, and grepped the whole repo for
   `ai.miniforge.schema.core` to confirm no cross-component `.core`
   requires existed to fix.
2. Plain `stratum-lint` before any change reproduced the 2 `SL003`
   findings above exactly (`core.clj` and `logging.clj`, 4 layers each).
3. Designed and applied the split (this PR's diff).
4. Ran `--fix` over the whole component after the split: zero diff (the
   `Layer N` headings and `^{:stratum n}` metadata written by hand
   already match what the tool derives). Ran it a second time
   immediately after: zero diff again, confirms idempotency.
5. Read the full diff for every changed/new file. `core.clj` and
   `logging.clj`'s `(comment ...)` rich-comment blocks were checked
   symbol-by-symbol against what moved — `logging.clj`'s comment
   referenced bare `all-events`, which no longer resolves there, so it
   was rewritten to `logging-vocab/all-events` (the file already
   requires `logging-vocab`, and the comment needs valid refs even
   though it isn't compiled at load time). No docstring was dropped or
   truncated in the moves.
6. Plain `stratum-lint` re-run over `components/schema`:

   ```bash
   bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "80699e378cb8ebbb6daeb928431aa4a6b373c07e" :deps/root "clojure"}}}' -m stratum-lint.interface components/schema
   ```

   Exit `0`, no output — zero `SL001`/`SL002`/`SL003`/`SL004` findings
   across the whole component, including the 2 new files.
7. `clj-kondo --lint components/schema`: 0 errors, 8 pre-existing
   warnings (deprecated `schema/validate`, exercised by its own
   backward-compat test coverage — present before this PR, unrelated to
   the split).
8. Ran the 6 schema-component test namespaces directly
   (`clojure -M:test`, via `clojure.test/run-tests`): 36 tests, 139
   assertions, 0 failures, 0 errors.
9. `clojure -M:poly check`: passes; only two pre-existing warnings
   unrelated to this component (`config` non-top namespace in test
   fixtures, an unnecessary `decision-envelope` component reference in
   `data-foundry`).
10. Tried `bb test` (stable-derived changed-and-affected) for full
    coverage. This branch's stable tag predates a large amount of merged
    work, so in practice it exercises most of the workspace, not just
    schema's dependents, and it got through hundreds of tests across
    many components with 0 failures/0 errors — but it does not complete
    in this sandbox: it stalls in `dag-executor.executor-test`, which
    retries against a Docker daemon this environment doesn't have
    (`SKIPPED: Docker not available` after each ~120s retry) and never
    returns. Waited it out past 9 minutes of retries before it was
    terminated (`EXIT:143`/`SIGTERM`) — a pre-existing environment
    limitation unrelated to this change, not a test failure.
11. Fell back to targeted direct test runs (`clojure -M:test` +
    `clojure.test/run-tests`, same technique as step 8) for the
    dependent components most exposed to this split: the two components
    whose comments reference `schema.core` directly
    (`automation-edge-correlator` + `supervisory-state`: 137 tests, 396
    assertions, 0 failures/0 errors) plus `task` (38 tests, 128
    assertions) and `gate` (42 tests, 122 assertions), both 0
    failures/0 errors — central, heavy consumers of `schema.interface`'s
    `Task`/`Workflow`/severity surface.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — namespace/require/heading/metadata-only. Pre-commit's
`lint:stratum` autofixer keeps this component clean going forward; both
of the component's `SL003` findings are now resolved (not deferred to
`warn` mode).

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Predecessor: `docs/pull-requests/2026-07-26-fix-stratum-lint-wave1-schema.md`
  (mechanical relabeling; flagged both `core.clj` and `logging.clj` as
  Wave 2 targets)

## Checklist

- [x] Full-file reads of `core.clj`/`interface.clj` and a repo-wide grep
      for `ai.miniforge.schema.core` before making any change
- [x] Split designed around the real same-file reference graph, not a
      mechanical line-count cut
- [x] `core.clj` → `vocab.clj` (3 layers) + `core.clj` (2 layers)
- [x] `logging.clj` → `logging_vocab.clj` (3 layers) + `logging.clj`
      (1 layer)
- [x] `interface.clj` re-exports repointed to whichever namespace now
      owns each moved symbol; no implementation logic added to it
- [x] Same-component internal requires (`logging.clj`, `supervisory.clj`)
      repointed from `.core` to `.vocab`
- [x] `--fix` run after the split: zero diff; second `--fix` pass
      confirms idempotency
- [x] Full diff read for every changed/new file; one stale bare-symbol
      reference in a `(comment ...)` block found and fixed
- [x] Plain `stratum-lint` over the whole component: exit 0, no findings
- [x] `clj-kondo` clean of new issues (0 errors; 8 pre-existing warnings
      unchanged)
- [x] Schema component tests: 36 tests, 139 assertions, 0 failures/errors
- [x] `clojure -M:poly check`: passes (2 pre-existing, unrelated warnings)
- [x] `bb test`: does not complete in this sandbox (stalls on the
      Docker-gated `dag-executor.executor-test`, killed after 9+ minutes
      of retries, `EXIT:143`) — everything it did run before that point
      was 0 failures/0 errors; not this change's fault
- [x] Fallback direct dependent-component tests in place of full `bb
      test`: `automation-edge-correlator` + `supervisory-state` (137
      tests/396 assertions), `task` (38/128), `gate` (42/122) — all 0
      failures/0 errors
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
