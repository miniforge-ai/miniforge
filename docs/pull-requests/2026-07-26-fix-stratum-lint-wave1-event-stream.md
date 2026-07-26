<!--
  Title: stratum-lint autofix for components/event-stream (Wave 1)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: stratum-lint autofix for components/event-stream (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/event-stream` (`src` + `test`)
to replace decorative `Layer N` headings with real ones derived from
each file's actual same-file reference graph, and tag every top-level
`def`/`defn`/`deftest` with `^{:stratum n}`. One of the Wave 1 batches
from `work/stratum-lint-baseline-2026-07-24.md` — the largest single
component in the program to date (26 `src` files including one
`.cljc`, 29 `test` files; 53 of the 55 rewritten by the fixer, 2 left
untouched — see below).

Mechanical for almost the whole diff, but the mandated full-diff read
surfaced one real, pre-existing bug: a 13-line explanatory comment in
`event_type_registry.clj` documenting `naming-asymmetries` was left
stranded after a newly-inserted `Layer 3` heading, sitting directly
against an unrelated one-line comment for a different def
(`audit-summary`) — see Changes in Detail. Eight files also carried
stale decorative `Layer N` sub-banners (trailing text or non-integer
numbers) that `--fix`'s heading regex doesn't recognize and leaves
untouched; all hand-cleaned. No executable logic changed anywhere.

## Motivation

Fresh plain-lint run against the current pin (`bef8657a`, confirmed
matching `tasks/stratum.clj` on `main` before starting), zero `SL001`
across the whole component — no upward-reference/cycle risk, clearing
this component for a blind `--fix` run per the Wave 1 batch criteria:

- `approval.clj`: `SL002` ×2, `SL003` (4 decorative layers)
- `core.clj`: `SL002` ×15, `SL003` (9 decorative layers) — the worst
  offender in the component, not previously called out in this
  program's tracking
- `digest.clj`: `SL002` ×1
- `event_type_registry.clj`: `SL002` ×2
- `interface/events.clj`: `SL002` ×4, `SL003` (5 decorative layers)
- `listeners.clj`: `SL003` (4 decorative layers)
- `schema.clj`: `SL002` ×1, `SL003` (7 decorative layers — the worst
  per-file heading count)
- `snowflake.clj`: `SL002` ×4
- `timeline.clj`: `SL002` ×3, `SL003` (4 decorative layers)
- `test/core_test.clj`: `SL004` ×2, `SL002` ×6, `SL003` (7 decorative
  layers)
- `test/event_type_registry_test.clj`: `SL002` ×1
- `test/identity_propagation_test.clj`: `SL003` (5 decorative layers)
- `test/progress_integration_test.clj`: `SL003` (5 decorative layers)
- `test/reader_test.clj`: `SL004` ×2
- `test/sinks_test.clj`: `SL004` ×4, `SL002` ×4, `SL003` (5 decorative
  layers)
- `test/zettel_promoted_test.clj`: `SL003` (6 decorative layers)

62 findings total, 16 files — larger and shifted from the pointer
list this task started from (that list was explicitly a partial
snapshot; `core.clj`, `digest.clj`, and `event_type_registry.clj`
weren't in it at all).

`interface/manifest.cljc` carries a private `jvm-only!` helper plus
seven `defn`/`def` pairs entirely inside `#?(:bb ... :clj ...)`
reader-conditional branches — superficially the SL008 shape fixed
upstream in [stratum-lint#15](https://github.com/miniforge-ai/stratum-lint/pull/15)
(merged, pin bumped in #1526). Re-verified directly: a plain lint on
just that file reports zero findings, and `--fix` on just that file
produces zero diff. Safe — none of its callers are themselves
recognized top-level defs (they're all inside the same `#?()` branches),
so the tool's reference graph never connects them and SL008 doesn't
fire. Left untouched, as expected.

`test/operator_requests_test.clj` is the other file `--fix` left
untouched — not a lookalike case, just already fully compliant: it
already carried correct bare `Layer 0`/`1`/`2` headings and
`^{:stratum n}` metadata on every `deftest` before this PR, so the
fixer found nothing to change (zero diff on the very first `--fix`
run). Confirmed via `git status` after the fix — it's the only test
file with no changes at all.

## Changes in Detail

Ran, over the whole component, at the current `main` pin:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "bef8657a2efd3b1ba9e1a4f510693c9fbca45abd" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/event-stream
```

53 of the component's 55 `.clj`/`.cljc` files were rewritten (`--fix`
normalizes every already-annotated file, not just the 16 with
findings) — `interface/manifest.cljc` and `test/operator_requests_test.clj`
are the two files left untouched, per the pre-checks above. Diffs are
heading text, `^{:stratum n}` metadata, and def/deftest reordering only. Verified this with a
line-multiset differential (added vs. removed lines per file,
`^{:stratum n}` tags stripped) across all 53 changed files, not just a
manual read: every file's added/removed line sets balance exactly
except for the hand edits below and two harmless whitespace-only
realignments of a trailing same-line comment (`schema.clj`,
`test/snowflake_test.clj` — same comment, same line, same assertion,
just different padding before the `;`).

### Stale decorative `Layer N` banners hand-cleaned (8 files)

`--fix`'s heading regex (`^;+\s*-*\s*Layer\s+(\d+)\s*-*\s*$`) only
matches a bare integer with optional surrounding dashes/whitespace —
trailing text or a non-integer number fails the match, so `--fix`
leaves the old banner in place next to (or straddling) its own new,
correct heading. Found and hand-cleaned:

- `control.clj`: five old `Layer 1a`/`1b`/`1c`/`2a`/`2b` sub-banners,
  each immediately followed by an existing plain description comment
  — deleted the banner line, kept the description.
- `archive.clj`: four old `Layer N — <label>` banners, one of which
  (`Layer 3 — fleet-level scan + recover over Layer 2`) had been
  *dragged along* with `scan-incomplete-archives` when `--fix` moved
  it to its real stratum (1, not 3) — now sitting before the real
  `Layer 2` heading and actively lying about the def below it.
  Deleted all four (descriptions were already redundant with the
  file's own `STRATIFIED-DESIGN LAYERING` comment block, rewritten
  below to list the real 7-layer structure).
- `manifest.clj`: five old `Layer N — <label>` banners, three of them
  similarly dragged to sit before the wrong new heading (claiming
  `Layer 1`/`3`/`4` while now sitting before real `Layer 3`/`5`/`6`
  defs). Deleted all five; rewrote the file's `STRATIFIED-DESIGN
  LAYERING` quick-map from a stale 5-layer description to the real
  7-layer one `--fix` computed (see SL003 discussion below).
- `schema.clj`: five old `Layer 1.4`/`1.5`/`2.5`/`4.5`/`5.5` sub-layer
  banners (non-integer numbers), each already followed by its own
  description comment — deleted the banner, kept the description.
- `core.clj`: four old `Layer 5.5`/`6.1`/`6.2`/`6.5` sub-banners, same
  shape and same fix as `schema.clj`.
- `sinks.clj`: five old `Layer N: <label>` banners (double-semicolon,
  colon-suffixed) — three had wrong numbers relative to their new
  real stratum (`Layer 1`/`3`/`2`/`4` labels sitting before defs at
  real stratum 0/0/1/5). Dropped the false `Layer N:` claim, kept the
  label as a plain comment.
- `interface/events.clj`: one old `Layer 3.5` banner before
  `agent-stream-stalled` (real stratum 0, since this file is pure
  re-exports) — dropped the claim, kept the description.
- `test/sinks_test.clj`, `test/reliability_schema_test.clj`: one old
  `Layer 1b` / two old `Layer 5.5`/`6` banners respectively, same
  fix.

### One real bug: a documentation block stranded by a heading insertion

`event_type_registry.clj`'s 13-line `;; Asymmetries at a glance:`
comment — a worked-example enumeration documenting `naming-asymmetries`
(real stratum 2) — originally sat physically between `naming-asymmetries`
and `audit-summary` (real stratum 3) in the pre-fix file. `--fix`
inserted the new `Layer 3` heading at the start of that gap (comments
aren't part of the reference graph, so `--fix` doesn't reposition them,
only new headings), leaving the block sandwiched *under* `Layer 3` and
crammed directly against `audit-summary`'s own one-line `;; Audit
summary (machine-readable)` comment with no separation — a reader
hitting `Layer 3` would reasonably expect what follows to describe
`audit-summary`, not `naming-asymmetries`.

First attempt at fixing this (moving the block above the heading, with
a blank line before `Layer 3`) didn't survive: re-running `--fix`
relocated the heading back to the start of the whole gap and dragged
the comment block down again — the tool always inserts a heading at
the first line of the gap between two defs and does not honor an
internal blank line as a sub-boundary. Fixed properly by moving the
block to sit directly above `naming-asymmetries` itself (still real
stratum 2, no heading boundary crossed there), leaving `audit-summary`
with a clean, accurate one-line comment after `Layer 3`. Re-ran `--fix`
twice more after this — zero diff both times, confirming the placement
is stable under the tool. No behavior change; comment-position only.

### Stale namespace-level layer-count documentation updated (2 files)

`archive.clj` and `manifest.clj` each carry a `STRATIFIED-DESIGN
LAYERING` comment block summarizing which functions live at which
layer. Both were stale after the fix recomputed real strata:

- `archive.clj`: the map assigned `scan-incomplete-archives` to
  "Layer 3 fleet-level scan + recover" by naming convention, but the
  real reference graph places it at Layer 1 (no upward calls — it
  only touches `manifest/load-manifest` and `marker-path`, both
  Layer 0). Rewrote the map to list it under Layer 1 and narrowed
  Layer 3's description to the actual sole remaining function there
  (`recover-all-incomplete!`).
- `manifest.clj`: the map described a 5-layer structure
  (`defaults/data`, `single-concern primitives`, `compose primitives`,
  `manifest-bound IO`, `scheduled/orchestration`); the real reference
  graph is 7 layers deep (see SL003 below). Rewrote the map to list
  all 7 real layers and their actual member functions.

One self-inflicted near-miss during this edit: a wrapped continuation
line reading `;;            Layer 0` (describing "built on Layer 0")
sat alone on its own line and matched the heading regex verbatim,
so the next `--fix` pass silently deleted it as a spurious extra
`Layer 0` heading. Caught by the mandated re-run-after-hand-edits step
(diff showed an unexpected file rewrite); fixed by rewording so
"Layer 0" never appears alone on a line.

### Review-round fix: a misplaced `;; Public API` banner (`timeline.clj`)

GitHub Copilot's automated review on this PR flagged one real issue:
the plain `;; Public API` grouping comment (not a `Layer N` heading —
same class of pre-existing free-floating comment as
`event_type_registry.clj`'s "Asymmetries at a glance" block above) sat
directly above `index-tool-names`, a `defn-` (private). The file's
only actual public function is `render-timeline` at the bottom
(`defn`, real stratum 5) — every other function in the file is
`defn-`. Verified directly against the PR's GitHub API data (not just
the review summary) and cross-checked the same banner's correct usage
elsewhere in the component (`digest.clj:108`, sitting directly above
the actual public `digest-content`). Moved the banner from above
`index-tool-names` to directly above `render-timeline`. Re-ran `--fix`
on the file afterward — zero diff, confirms the new placement is
stable. No behavior change; comment-position only.

### Review-round fix: a stale docstring on `listeners.clj`'s privacy/capability map

GitHub Copilot's second review pass (after the `timeline.clj` fix
above) flagged `privacy->min-capability`'s docstring: it claims
`:internal events -> :advise or higher`, but the map itself sets
`:internal :observe` — the same minimum capability as `:public`.
Verified this is pre-existing (traces back to `components/event-stream`'s
original introduction, #175 — long before stratum-lint touched this
file) and that there's no test or spec to determine which side is
"correct": `work/n08-oci-governance.spec.edn` documents the whole
capability-gating layer as ~10% implemented and explicitly defers
privacy-tier policy to a spec that doesn't define this mapping.
Fixed the docstring to describe the map's actual current behavior
(no behavior change) rather than tightening the map to match the old
docstring's claim, which would be an unverifiable security-policy
decision — see Related Issues for the follow-up. Re-ran `--fix` (zero
diff), `clj-kondo` (0 errors/warnings), and `listeners_test.clj` (5
tests, 21 assertions, 0 failures/errors) after the change.

### Review-round fix: a stale namespace-docstring layer summary on `approval.clj`

GitHub Copilot's third review pass flagged `approval.clj`'s namespace
docstring: it claimed a 4-layer breakdown (`Layer 0: Approval request
creation`, `Layer 1: Approval signing and status checking`, `Layer 2:
Approval manager`, `Layer 3: Approval event constructors`) that no
longer matches the file's real `^{:stratum n}` tags — verified
directly by listing every top-level def's stratum: the file collapsed
to 3 real layers (0, 1, 2) when `--fix` first ran, with a completely
different grouping (response predicates, expiry/signing checks, the
manager, and the event constructors are ALL real stratum 0; request
creation/signing/status-checking is stratum 1; `list-approvals` alone
is stratum 2). The same bug class this program has hit repeatedly
(`adapter-claude-code`, `reliability`, `observer` — a stratum-lint
`--fix` pass changes the real layer count/grouping but never touches
prose describing the old one). Rewrote the docstring's layer summary
to match the verified real structure. Re-ran `--fix` (zero diff),
`clj-kondo` (0 errors/warnings), and `approval_test.clj` (9 tests, 45
assertions, 0 failures/errors) after the change.

## Testing Plan

1. Confirmed the stratum-lint pin in `tasks/stratum.clj`
   (`bef8657a2efd3b1ba9e1a4f510693c9fbca45abd`) matches current `main`
   before branching — this program's history has seen stale-pin
   staleness silently re-corrupt prior work.
2. Ran plain (non-`--fix`) `stratum-lint` before any change —
   reproduced the 62 findings above exactly, confirmed 0 `SL001`
   across the whole component.
3. Verified `interface/manifest.cljc`'s SL008 lookalike is a true
   negative: isolated plain lint (zero findings) and isolated `--fix`
   (zero diff) before touching anything else.
4. Ran `--fix` over the whole component — 53 files rewritten.
5. Ran `--fix` a second time immediately — zero diff, confirms
   idempotency.
6. Read the full diff for all 53 changed files. Verified the read with
   a line-multiset differential per file (see Changes in Detail) in
   addition to a manual pass over every `src` file's diff. Found and
   fixed the one real bug (`event_type_registry.clj` comment
   misattachment) and hand-cleaned 8 files' worth of stale decorative
   headings plus 2 files' worth of stale layer-count documentation.
7. Re-ran `--fix` after every hand edit (multiple passes, including
   two more rounds specifically for the `event_type_registry.clj` fix
   until the tool's heading-insertion behavior was understood and
   the placement held) — final state is zero diff on a fresh `--fix`
   pass.
8. `clj-kondo --lint components/event-stream`: 0 errors both before
   and after. 4 pre-existing warnings (`timeline.clj` unresolved
   `clojure.string` namespace — a real pre-existing gap, unrelated to
   this change; `operator_requests_test.clj` unused binding;
   `publish_helpers_test.clj` and `quiesce_drain_test.clj` unused
   `testing` referral) are unchanged in content and count before and
   after — verified directly against copies of the original files,
   only line numbers shifted from reordering.
9. Component test suite: `components/event-stream/deps.edn`'s
   standalone `:test` alias can't run `core_test.clj` on its own — it
   requires `ai.miniforge.phase.interface`, which isn't declared as a
   dep in this component's own `deps.edn` (a pre-existing gap on
   `main`, unrelated to stratum-lint — confirmed by checking `HEAD`'s
   version of `core_test.clj`, already requires it; matches the
   already-tracked "component `deps.edn` missing local deps its own
   src/test actually needs, masked by root flattening" issue class).
   Ran instead via the root `:dev:test` aliases, which already
   flatten every component's deps (including `phase`) — the same
   classpath `poly test`/CI already use: every test namespace in the
   component (28 namespaces; `test_helpers/http_mock.clj` is a helper
   with no `deftest` forms, not a namespace of its own), 349 tests,
   1747 assertions, 0 failures, 0 errors — 343 tests / 1724 assertions
   across the 27 namespaces `--fix` actually touched, plus 6 tests /
   23 assertions from `operator_requests_test.clj` (the one test file
   left untouched, run separately for completeness). Cross-checked
   `deftest` form counts per test file before vs. after the fix —
   identical count in every one of the 27 changed test files,
   confirming zero tests were dropped by the reordering.
10. Re-ran plain `stratum-lint` after all fixes: `SL001`/`SL002`/`SL004`
    clear across the component. `SL003` remains on 12 files, all
    real over-budget files the old decorative/absent headings hid —
    none of these are a regression this PR introduces, and all are
    genuine linear reference-graph depth rather than sprawl (see
    Deployment Plan for the deferral reasoning):

    | File | Real layers |
    |------|------------:|
    | `manifest.clj` | 7 |
    | `sinks.clj` | 7 |
    | `timeline.clj` | 6 |
    | `snowflake.clj` | 5 |
    | `archive.clj` | 4 |
    | `control.clj` | 4 |
    | `core.clj` | 4 |
    | `digest.clj` | 4 |
    | `event_type_registry.clj` | 4 |
    | `operator_requests.clj` | 4 |
    | `storage_layout.clj` | 4 |
    | `test/snowflake_test.clj` | 4 |

    Of the files in the original 62-finding list, `approval.clj`,
    `listeners.clj`, `schema.clj`, `interface/events.clj`,
    `test/core_test.clj`, `test/identity_propagation_test.clj`,
    `test/progress_integration_test.clj`, `test/sinks_test.clj`, and
    `test/zettel_promoted_test.clj` all collapsed to ≤3 real layers —
    the old decorative headings had overstated their depth. Four
    files (`archive.clj`, `control.clj`, `digest.clj`,
    `event_type_registry.clj`, `operator_requests.clj`,
    `storage_layout.clj`) newly surface `SL003` at exactly 4 — one
    over budget — purely because their old headings either didn't
    exist in a form the tool recognized (silently skipped, not a
    clean bill of health) or undercounted.
11. GitHub Copilot review (post-push) flagged the `timeline.clj`
    `;; Public API` misplacement above. Fixed, re-ran `--fix` (zero
    diff, stable), re-ran `clj-kondo` on the file (0 errors, same 1
    pre-existing `clojure.string` warning), and ran
    `timeline_test.clj` directly (15 tests, 66 assertions, 0
    failures/errors).
12. Second Copilot review pass (after the `timeline.clj` push) flagged
    `listeners.clj`'s stale `privacy->min-capability` docstring above.
    Fixed, re-ran `--fix` (zero diff), `clj-kondo` (0 errors, 0
    warnings), and `listeners_test.clj` directly (5 tests, 21
    assertions, 0 failures/errors).
13. Third Copilot review pass (after the `listeners.clj` push) flagged
    `approval.clj`'s stale namespace-docstring layer summary above.
    Fixed, re-ran `--fix` (zero diff), `clj-kondo` (0 errors, 0
    warnings), and `approval_test.clj` directly (9 tests, 45
    assertions, 0 failures/errors).

## Deployment Plan

Merges to `main` like any other component change. Almost entirely
comment/metadata/order-only; the two real fixes
(`event_type_registry.clj`'s and `timeline.clj`'s comment placement)
have zero behavior impact — no code, only comment position, changed.
Pre-commit's
`lint:stratum` autofixer keeps this component clean going forward.

**SL003 deferral, not resolution, for all 12 remaining files.** Every
one was examined for a genuinely separable split before deferring —
none qualified as "clean" within this PR's scope:

- The four-layer files (`archive.clj`, `control.clj`, `core.clj`,
  `digest.clj`, `event_type_registry.clj`, `operator_requests.clj`,
  `storage_layout.clj`) are each a single linear pipeline with
  exactly one function at the deepest layer (e.g. `storage_layout.clj`:
  raw config → loader → memoized atom → six accessor functions;
  `digest.clj`: byte conversion → hex conversion → sha256 → the one
  public `digest-content`). Splitting a 4-deep chain with one function
  per top layer into multiple namespaces would separate tightly
  coupled steps with no independent reuse value — busywork, not a
  real decomposition.
- `snowflake.clj` (5) and `timeline.clj` (6) are the same shape at
  greater depth: a Snowflake ID generator's lease-acquire → compose-id
  → emit-id → generator-handle → public-API pipeline, and an event
  timeline renderer's field-extraction → formatting → per-event-type
  dispatch → line-render pipeline. Real sequential depth, not sprawl.
- `manifest.clj` (7) and `sinks.clj` (7) are the deepest and the most
  plausible split candidates — `sinks.clj`'s path-resolution helpers
  (`default-events-dir` → `live-dir`/`archived-dir`/`operator-dir` →
  `live-workflow-dir`/`archived-workflow-dir` → `workflow-dir`/
  `event-file-path`) are conceptually separable from its sink
  implementations, and splitting them into their own namespace would
  likely drop both files' real depth below budget. Deferred anyway:
  both are foundational, imported throughout the workspace
  (`archive.clj`, the CLI, and others call into `sinks.clj`'s path
  functions directly), and a split needs its own dedicated,
  carefully-tested PR rather than being folded into a stratum-lint
  autofix pass.
- `core.clj` (4) is the highest-traffic file in the component: ~70
  independent event-constructor functions that are all real stratum 2
  siblings (each just calls `create-envelope` + `publish!`), one layer
  over budget only because of the trailing `phase-completed`/chain-event
  group at stratum 3. A real fix means extracting the event
  constructors into their own namespace (mirroring what
  `interface/events.clj` already re-exports) — Wave 2 scope, not a
  same-PR addition to this one given how central this file is.

This tracks the dominant pattern already established across ~30 prior
Wave 1 PRs in this program (e.g. `schema`, `self-healing`,
`release-executor` all deferred equivalent or smaller SL003 overages
for the same reason: genuine reference-graph depth, not decorative
inflation). `MINIFORGE_STRATUM_BUDGET_MODE=warn` is required at commit
time for these 12 files until Wave 2 splits them.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 namespace splits for the 12 files listed in
  Testing Plan item 10, `sinks.clj` and `manifest.clj` first (largest
  real depth, most concrete split candidates identified above)
- Pre-existing, unrelated gap noticed while running tests:
  `components/event-stream/deps.edn`'s `:test` alias is missing
  `ai.miniforge/phase` as a dependency, even though
  `test/ai/miniforge/event_stream/core_test.clj` requires
  `ai.miniforge.phase.interface` — masked by the root `:dev`/`:test`
  aliases' flattened classpath. Matches the already-tracked
  component-`deps.edn`-transitive-gaps issue class (partially fixed
  elsewhere, e.g. `gate`/`loop`, `workflow`/`pr-train`). Not fixed
  here — out of scope for a stratum-lint autofix PR — but worth a
  follow-up so `clojure -M:test` works standalone for this component.
- `interface/manifest.cljc`'s SL008 lookalike shape: background in
  [stratum-lint#15](https://github.com/miniforge-ai/stratum-lint/pull/15)
  and PR #1526 (pin bump). Re-confirmed safe for this component in
  Motivation above; no action needed.
- Pre-existing docstring/data mismatch, first noticed via a Copilot
  low-confidence suppressed note and confirmed as a real, separately
  posted review comment on the second pass: `listeners.clj`'s
  `privacy->min-capability` docstring said `:internal events -> :advise
  or higher`, but the map itself has `:internal :observe` — the same
  minimum capability as `:public`, meaning the `:internal` privacy tier
  currently adds no additional access restriction over `:public`.
  Confirmed pre-dating this PR back to `components/event-stream`'s
  original introduction (#175) — the stratum-lint fix only reorders,
  it never touched this docstring's text. No test covers
  `privacy->min-capability` or its caller either way, and
  `work/n08-oci-governance.spec.edn` documents the whole capability
  layer as "~10% implemented," explicitly deferring privacy-tier
  policy to a spec that doesn't define this mapping yet. Given that,
  fixed the docstring to describe current behavior accurately rather
  than changing the map — changing the map would be a security-policy
  decision (tightening `:internal` event access) that isn't
  verifiable as correct from the code alone, and doesn't belong in a
  stratum-lint autofix PR. Matches this program's precedent for
  exactly this situation (`observer`'s Wave 1 PR fixed a stale
  docstring claiming unsupported `:json` format support by correcting
  the doc to match the code, not the reverse, when the "right"
  behavior wasn't obvious from code alone). The underlying
  privacy-tiering policy question (should `:internal` require more
  than `:observe`?) is a genuine product decision, flagged separately
  for follow-up — not resolved here.

## Checklist

- [x] Confirmed stratum-lint pin matches `main` before starting
- [x] Zero `SL001` confirmed before running `--fix` (no cycle/upward-
      reference risk)
- [x] `interface/manifest.cljc` SL008-lookalike re-verified safe in
      isolation (zero findings, zero `--fix` diff) before touching the
      rest of the component
- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second `--fix` pass confirms idempotency (zero diff)
- [x] Diff read in full for all 53 changed files, cross-checked with a
      line-multiset differential per file
- [x] One real bug found and fixed: `event_type_registry.clj`'s
      `naming-asymmetries` documentation block, stranded by a heading
      insertion, moved back to its correct position (comment-only,
      verified stable under two more `--fix` re-runs)
- [x] Eight files' stale decorative `Layer N` banners hand-cleaned;
      two files' stale `STRATIFIED-DESIGN LAYERING` documentation
      blocks rewritten to match real post-fix layer counts
- [x] `clj-kondo` clean of new issues (0 errors before/after; 4
      pre-existing warnings unchanged in content/count, confirmed
      against the original files directly)
- [x] Component tests pass (349 tests, 1747 assertions, 0
      failures/errors) via the root `:dev:test` classpath; `deftest`
      counts confirmed identical per file before/after
- [x] Plain lint re-run post-fix: zero `SL001`/`SL002`/`SL004`;
      `SL003` remains on 12 files, all documented above with real
      layer counts and explicit split-feasibility reasoning, tracked
      as Wave 2
- [x] GitHub Copilot review comment (`timeline.clj`'s misplaced `;;
      Public API` banner) verified directly against the PR's GitHub
      API data and fixed; stability re-confirmed under `--fix`
- [x] Second Copilot review pass comment (`listeners.clj`'s stale
      `privacy->min-capability` docstring) verified directly, confirmed
      pre-existing (traces to #175) and unresolvable to a "correct"
      map from code/spec alone; fixed the docstring to match current
      behavior rather than changing runtime access-control behavior;
      underlying policy question flagged separately in Related Issues
- [x] Third Copilot review pass comment (`approval.clj`'s stale
      namespace-docstring layer summary) verified directly against the
      file's actual `^{:stratum n}` tags and fixed
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
