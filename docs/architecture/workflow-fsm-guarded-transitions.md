<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# RFC: Guarded FSM redesign for workflow phase transitions

| | |
|---|---|
| **Status** | Proposed |
| **Date** | 2026-06-02 |
| **Authors** | Chris Lester (christopher@miniforge.ai) |
| **Discussion** | PR #TBD |
| **Implementation tracking** | task #32 |

## Summary

The workflow FSM is a passive `(state, event) → next-state` lookup table.
It dispatches transitions but does not decide them. Every routing decision
lives outside the machine — in phase code, in the runner's
`determine-phase-event`, in `apply-phase-transition`'s budget check, and in
`build-phase-state`'s compile-time `:on-fail` resolution. Two distinct
channels can produce identical edges, and the accounting is wired to only
one of them.

The 2026-05-28 dogfood demonstrated this experimentally — a 3h40min,
$8-15 run that should have terminated after 5 redirects burned through to
forced kill. Three workaround PRs (#1010, #1011, #1013) patched symptoms
without fixing the design.

This RFC proposes adopting `clj-statecharts` end-to-end for workflow phase
transitions, with a named-guard registry layered on top for closed-set
compile-time validation. `clj-statecharts` is already a runtime dependency,
already wrapped in the `fsm` component, and already proven Babashka-compatible
by other in-process FSMs. The redesign moves every transition decision
into the FSM as a typed, enumerable guard — the bug class becomes
structurally impossible to express. Migration is five independently
mergeable phases. Net negative LOC.

## Problem

### The bug class

`request-redirect` (phase mutates result → runner emits a
`workflow.event/redirect-to-X` keyword) is one mechanism for "go to phase
X on failure." `:on-fail :X` in pipeline config (FSM-compile-time table
bake of `:phase/fail` → on-fail-target) is a second mechanism. Both
mechanisms produce identical FSM trajectories — execution lands at the
same target state. The accounting in
`apply-phase-transition` predicates on `redirect-event?` — a check on the
event keyword's namespace. It catches the first mechanism. It misses the
second.

In the 2026-05-28 dogfood, the workflow ran ~5 verify→implement
transitions via the second mechanism. `:execution/redirect-count` peaked
at 2, never close to the cap of 5. The convergence-cap signal from #1011
fired correctly inside the phase but had no FSM-level place to take
effect; the workflow followed `:on-fail :implement` regardless of phase
intent. The eventual workarounds (#1013's `:phase/terminal-fail`,
`:stagnated?`/`:needs-decomposition?` flag bits) papered over the
symptom by adding a fourth decision site sniffing for flag values the
FSM doesn't model.

### The diagnosis

Zero entry/exit guards. Every routing decision lives in one of five
places, none of which see the others:

| Concern | Owner | Phase |
|---|---|---|
| Per-phase agent-iteration budget | `phase/interface.clj` `handle-error` | runtime |
| Per-phase `:on-fail` target | pipeline config → `build-phase-state` | compile-time |
| Redirect-count budget | `execution.clj` `apply-phase-transition` | runtime |
| Stagnation, convergence cap, exhausted-budget | `review.clj` `compute-decision` | runtime |
| Phase result → FSM event | `execution.clj` `determine-phase-event` | runtime |

Five concern-owners, four flag conventions, two redirect channels, one
bug class. Adding a new invariant means a new flag, a new check site,
and a new precedence rule in `determine-phase-event`. Every new invariant
expands the surface where the next bug of this shape will hide.

The FSM is the place where this consolidation belongs. The phase produces
a SIGNAL ("approved" / "rejected" / "stagnated"); the FSM owns the
DECISION (whether to redirect, retry, terminate, or progress); the
runner EXECUTES the FSM-chosen edge plus any FSM-owned hooks.

## Investigation

### Current shape, file by file

| File | Role | Key lines |
|---|---|---|
| `components/workflow/src/.../fsm.clj` | FSM compiler | `build-phase-state:212`, `redirect-event?:177`, `redirect-transition:170`, `transition-execution:187` |
| `components/workflow/src/.../execution.clj` | Runner / event determination | `determine-phase-event:294`, `terminal-failure?:278`, `apply-phase-transition:326`, `max-redirects:322` |
| `components/workflow/src/.../checkpoint_store.clj` | Persistence | `persisted-execution-keys:64` |
| `components/phase/src/.../interface.clj` | Phase contract | `handle-error:242` |
| `components/phase/src/.../phase_result.clj` | Phase-result accessors | `request-redirect:160` |
| `components/phase-software-factory/src/.../review.clj` | Worst-offender decision site | `compute-decision:317`, `apply-decision:329`, `compute-stagnated?:286`, `compute-needs-decomposition?:294` |
| `components/phase-software-factory/src/.../verify.clj` | Same shape, simpler | `leave-verify:357` |
| `components/phase-software-factory/src/.../release.clj` | Same shape | `leave-release:463` |
| `components/fsm/src/.../core.clj` | `clj-statecharts` wrapper (already exposes `guard`, `assign`, `entry`, `exit`) | `assign:200`, `guard:237` |
| `components/dag-executor/src/.../state.clj` | INDEPENDENT FSM, no cross-cutting | `transition-task!:200` |

### Two-channel mechanism, expressed in five lines

```clojure
;; fsm.clj transition-execution
(if (and transition-defined? (redirect-event? event-key))
  (fsm/update-context next-state update :redirect-count increment-redirect-count)
  next-state)
```

The accounting is here. `redirect-event?` checks for the `workflow.event`
keyword namespace. Channel A (`request-redirect` → `workflow.event/...`)
passes; channel B (`:phase/fail` → `:phase` namespace → on-fail target)
fails. Same edge, different events, one of them counted.

### What's persisted

`checkpoint_store.clj` `persisted-execution-keys` includes
`:execution/redirect-count` and `:execution/fsm-state`. The persisted
machine state is just a Clojure map: `{:_state :phase-1-implement,
:redirect-count 2}`. **Any redesign that keeps the same state-id keywords
and the same context-key set preserves checkpoint compatibility.**

### What lives independently

The DAG-executor FSM at `dag-executor/state.clj` is a separate state
machine — a profile-driven status-enum lifecycle. It does not share code
with `workflow.fsm`. The only interaction seam is
`apply-dag-success`/`apply-dag-failure` in
`execution.clj:556-608`, which call `apply-phase-transition` on the
workflow FSM with `:phase/succeed`. **The workflow-FSM redesign does not
touch the DAG-executor FSM and vice versa.**

### Test surface

- `phase_transitions_test.clj` (243 LOC) tests `determine-phase-event`
  and `apply-phase-transition` directly. Four of eight test forms pin
  workaround behavior. **Rewrite end-to-end, not augment.**
- `fsm_test.clj` (330 LOC) is mostly legacy-API tests on the top-level
  workflow lifecycle FSM (`:pending/:running/:completed/:failed/:paused/
  :cancelled`). **Unchanged.**
- `runner_*_test.clj`, per-phase tests. **Untouched** — phase contract
  stays "phase result → workflow advances correctly."

## Prior art survey

| Model | Catches bug at compile? | First-class guards? | FSM-owned entry/exit? | BB-compat | Migration from us |
|---|---|---|---|---|---|
| **XState v5** (TS) | Yes — guarded transition arrays | Yes, typed | Yes | n/a | conceptual borrow |
| **SCXML** (W3C) | Yes | Yes | Yes | n/a | conceptual borrow |
| **gen_statem** (Erlang/OTP) | Convention prevents | Inline in callback | Yes | n/a | data-shape borrow |
| **Akka/Pekko FSM** (JVM) | Yes | Yes | Yes | n/a | none |
| **Spring State Machine** (Java) | Yes | Yes | Yes | n/a | persistence-interface idea |
| **clj-statecharts** (Clojure) | **Yes** | **Yes** | **Yes** | **Proven (vendored)** | **direct adoption** |
| automat (Clojure) | No | Limited | Limited | likely | poor fit |
| Temporal | No (orthogonal) | n/a | n/a | no | event-history idea (long-term) |
| Event sourcing | Yes by construction | Yes (validators) | Yes | yes | long-term direction |

Only `clj-statecharts` hits all four columns with zero ecosystem-buy-in
cost. The other top candidates (XState v5, SCXML) inspired it — same
Harel-statecharts lineage.

### Why this matters for `clj-statecharts` specifically

Already a runtime dependency in `bb.edn`. Already wrapped at
`components/fsm/src/.../core.clj` exposing `guard`, `all-guards`,
`any-guard`, `assign`, `entry`, `exit`, and the `:guard`/`:actions`
transition keys. Already in production via other in-process FSMs. The
existing `components/workflow/src/.../fsm.clj` builds plain
`:event → :target` maps and uses zero of the guarded-transition surface.
**The redesign is not "adopt statecharts." It is "use the statechart
engine we already paid for."**

Library's guarded-transition syntax:

```clojure
{:on {:some-event [{:target :s2 :guard fn1 :actions [act1]}
                   {:target :s3 :guard fn2}
                   {:target :s4}]}}
```

First matching guard wins. Default branch (no `:guard`) is the
fall-through. Guards are pure `(state, event) → boolean`. Actions
wrapped in `assign` mutate context; bare actions are side effects.

### Why not the others

- **gen_statem-style state functions:** prevents the bug by convention,
  not by structure. Convention is enforced only by code review.
  Migration cost is high — every phase rewritten.
- **Reimplement XState v5 in Clojure:** writes an interpreter we don't
  need. `clj-statecharts` already does this.
- **Event sourcing with command validators:** the correct long-term
  destination but a 3-month rewrite. Out of scope.
- **Temporal:** orthogonal. The bug would still occur if the workflow
  function had two channels for "go to implement." Temporal solves
  durability, not decision-vs-dispatch.

## Candidate designs

### A — Full `clj-statecharts` adoption, guarded transitions

Replace `build-phase-state`'s flat event map with an ordered guarded
array. Move every decision (redirect budget, on-fail redirect,
stagnation, convergence cap) into named guard predicates. Delete the
parallel `redirect-event` machinery, `:phase/terminal-fail`,
`:stagnated?`/`:needs-decomposition?` flag-sniffs.

Phases emit a single `:phase/fail` event with a payload describing why.
FSM guards decide where to go. One event channel, one accounting site.

**Prevents the bug:** YES at compile time. There is exactly one
`:phase/fail` event; the redirect-count increment is exactly one
`(assign inc-redirect-count)` site; verdict-terminal evaluation precedes
the redirect branch in document order. The structural impossibility is
enforced by the ordered-array semantics of the engine.

### B — gen_statem-style state functions

Each phase is `(ctx event) → {:next-state :ctx-update :side-effects}`.
No transition table; runtime dispatch IS the table. Migration cost is
high (every phase + test rewritten). Compile-time guarantees are weaker
(state functions are opaque to compile-time validation). **Rejected.**

### C — Typed transition table, custom interpreter

Build a transition-entry data shape (`:kind :guard :next :on-deny
:on-fire`) and a small runtime interpreter. Strongest compile-time
guarantees (closed-set keywords) but reimplements
what `clj-statecharts` already provides. **Rejected on duplication.**

### D — Event-sourced with command validator

Phase emits commands; FSM is the validator + projection. The right
long-term destination but a quarter-scale rewrite. Out of scope for
this RFC. **Rejected on scope.**

### E — Candidate A + named-guard registry (RECOMMENDED)

Adopt `clj-statecharts` per Candidate A. Add a named-guard registry so
guards and actions are referenced by keyword (`:guard
:budget/redirects-spent?`) and resolved from a registry at machine-compile
time. A validator pass asserts every referenced keyword resolves.

Same runtime behavior as A. Stronger compile-time validation. Every
state's "what can go wrong from here" is enumerable as a list of guard
names per state — direct support for visualization and documentation.

**Cost over A:** ~150 LOC for `workflow/guards.clj` and a validator pass.
**Risk delta over A:** zero.

## Recommendation

**Candidate E.**

Against A: closed-set keyword refs catch typos and renames at machine-compile
time. Audit story is significantly better — every state's failure modes
are enumerable as data.

Against B: B prevents the bug by convention; E prevents it by structure.
B's migration is far more expensive.

Against C: C reimplements `clj-statecharts`. E reuses what we paid for.

Against D: D is the right long-term destination; E doesn't preclude D
and lands in 2-3 weeks instead of 3 months.

## Migration plan

Five phases (plus an optional Phase 6), each independently mergeable. The workflow runs at every
phase boundary, including all in-flight checkpoints.

### Phase 1 — Named guard registry, no behavior change

**Deliverable.** `components/workflow/src/ai/miniforge/workflow/guards.clj`
holds a guard registry, lookup, and per-machine validator. `fsm.clj`
`compile-execution-machine` accepts a guard registry and validates that
every keyword-form guard reference resolves.

**Files touched.**

- `components/workflow/src/.../guards.clj` (new, ~120 LOC)
- `components/workflow/src/.../fsm.clj` (~30 LOC delta)

**Invariants enforced.** Every guard reference is a keyword. Every
referenced guard exists in the registry. Compile fails when a reference
is dangling. Test: dangling reference fails the validator.

**Behavior change.** None. No transitions yet use guards.

### Phase 2 — Guarded `:phase/fail` array for review phase

**Deliverable.** Replace `{:phase/fail failure-target}` in
`build-phase-state` with the ordered guarded form FOR REVIEW PHASES
ONLY. Three named guards (`:verdict/terminal?`, `:budget/redirects-spent?`,
`build-phase-state` with the ordered guarded form FOR REVIEW PHASES
ONLY. Three named guards (`:verdict/terminal?`, `:budget/redirects-spent?`,
`:config/on-fail-set?`) and one named action (`:redirect/inc-count`).
`leave-review` adds `:phase/verdict` to the phase result and drops the `:stagnated?` /
`:needs-decomposition?` flag bag.

**Files touched.**

- `components/workflow/src/.../fsm.clj` (`build-phase-state` accepts
  per-phase config flag; ~50 LOC delta)
- `components/workflow/src/.../guards.clj` (3 guards + 1 action; ~60 LOC)
- `components/phase-software-factory/src/.../review.clj`
  (`leave-review` collapses `apply-decision` ladder into verdict
  emission; remove `compute-decision`, `terminate-stagnated`,
  `terminate-needs-decomposition`; ~100 LOC net reduction)
- `components/workflow/src/.../execution.clj`
  (`determine-phase-event` extracts verdict; `terminal-failure?`
  becomes a guard input; ~30 LOC delta)

**Invariants.** Review-phase `:phase/fail` array has at most one
`:redirect/inc-count` action. Budget guard precedes redirect branch.
Verdict-terminal precedes everything.

**Behavior change.** Review failures route through the guarded array.
Channel-A redirects ARE now metered. **The dogfood-class bug is fixed
for review at end of Phase 2.**

**Checkpoint compat.** Same state-id keywords. Same context-key set.
`:redirect-count` increment goes through the same `assign` mechanism.

### Phase 3 — Roll guarded form to verify, release, implement

**Deliverable.** Same migration applied to verify, release, implement.
Each phase emits a verdict. `build-phase-state` drops the per-phase
flag; all phases use guarded form.

**Files touched.**

- `phase-software-factory/verify.clj` (timeout / rate-limit become
  verdicts; ~40 LOC delta)
- `phase-software-factory/release.clj` (~25 LOC delta)
- `phase-software-factory/implement.clj` (~25 LOC delta)
- `workflow/fsm.clj` (~10 LOC simplification)
- `workflow/execution.clj` (`determine-phase-event` collapses to
  "extract `:phase/verdict`"; ~40 LOC delta)

**Invariants.** All four work-loop phases use guarded form. Exactly one
redirect-counting action site in the entire compiled machine.

### Phase 4 — Delete the workaround

**Deliverable.** Remove dead code.

**Symbols removed.**

| Symbol | File | Why |
|---|---|---|
| `:phase/terminal-fail` event | `fsm.clj` | replaced by verdict + guard |
| `:stagnated?` flag-sniff | `review.clj`, `execution.clj` | replaced by verdict tag |
| `:needs-decomposition?` flag-sniff | `review.clj`, `execution.clj` | replaced by verdict tag |
| `redirect-event?` | `fsm.clj` | accounting via action, not namespace sniff |
| `redirect-event-namespace` constant | `fsm.clj` | dead |
| `redirect-event` fn | `fsm.clj` | no per-target event keyword |
| `redirect-transition` | `fsm.clj` | replaced by single guarded `:phase/fail` |
| `increment-redirect-count` inline | `fsm.clj` | replaced by `:redirect/inc-count` action |
| `terminal-failure?` | `execution.clj` | replaced by verdict guard |
| `compute-decision` | `review.clj` | replaced by verdict emission |
| `apply-decision` | `review.clj` | replaced by FSM dispatch |
| `terminate-stagnated` | `review.clj` | replaced by verdict tag |
| `terminate-needs-decomposition` | `review.clj` | replaced by verdict tag |
| `compute-phase-status` | `review.clj` | logic absorbed into verdict |
| `compute-needs-decomposition?` | `review.clj` | becomes guard input |
| `compute-stagnated?` | `review.clj` | becomes guard input |
| `:phase/terminal-fail` branch in `determine-phase-event` | `execution.clj` | gone |
| `is-redirect?` branch in `apply-phase-transition` | `execution.clj` | FSM action meters; runner just propagates |
| Workaround tests in `phase_transitions_test.clj` | tests | replaced by guard-coverage tests |

**Estimated total delete:** ~350-450 LOC. **Added:** ~250 LOC
(guards.clj + validator + new tests). **Net negative LOC, with stronger
guarantees.**

### Phase 5 — Strengthen compile-time validation

**Deliverable.** Extend `validate-execution-machine` with guard-coverage
rules:

- Every state's `:phase/fail` transition is a vector with at least one
  default branch.
- Every guarded branch references a registered guard.
- At most one action across the vector increments `redirect-count`.
- The single budget-check guard precedes the redirect branch in document
  order.

**Files touched.**

- `workflow/fsm.clj` `validate-execution-machine` (~80 LOC added)
- `workflow/test/fsm_test.clj` (~150 LOC of new validator tests)

**Invariants.** Future regressions where a contributor adds a second
redirect-counting path fail at compile time — at runner startup, not at
the next dogfood.

### Phase 6 (optional, independent) — Mermaid export

**Deliverable.** Export the compiled machine as Mermaid. CLI surface:
`mf workflow inspect <workflow.edn>` prints the state graph.

**Files touched.** `workflow/visualize.clj` (~80 LOC), CLI plumbing
(~30 LOC).

Independent — can ship any time after Phase 5.

## What gets deleted

Consolidated against the deliverables above. By end of Phase 4:

- `:phase/terminal-fail` event and its FSM table entries
- `:stagnated?` and `:needs-decomposition?` boolean flag sniff sites
- `redirect-event?`, `redirect-event-namespace`, `redirect-event`,
  `redirect-transition`
- `compute-decision`, `apply-decision`, `compute-stagnated?`,
  `compute-needs-decomposition?`, `compute-phase-status`,
  `terminate-stagnated`, `terminate-needs-decomposition`
- `terminal-failure?`, the `:phase/terminal-fail` branch in
  `determine-phase-event`, the `is-redirect?` branch in
  `apply-phase-transition`
- The four workaround test forms in `phase_transitions_test.clj`

Net delete ~350-450 LOC against ~250 LOC added. Stronger guarantees, less
code.

## Open questions and risks

### Compatibility

- **Checkpoint state-id keywords stay identical.** Verified — `:phase-N-X`
  format is the only consumer.
- **FSM context still holds `:redirect-count`.** Action increments it via
  the same `assign` mechanism. Old checkpoints continue to load.
- **Phase results add `:phase/verdict`.** Old results without verdict
  fall through to the default branch of the guarded transition. No
  in-flight workflow breaks.
- **`request-redirect` stays in the phase API.** It now sets verdict +
  target internally; externally the API is unchanged. Phases don't need
  to learn the verdict vocabulary on day one.

### Babashka

- `clj-statecharts` 0.1.5 runs in BB today via other components.
- Adding `:guard` and `:actions` to existing machines is a data change,
  not a code change — same SCI path.
- No protocol introduction. No `eval`. No new dynamic vars.

### Cross-FSM boundary

- DAG-executor FSM is independent. Single seam at
  `apply-dag-success`/`apply-dag-failure` calling
  `apply-phase-transition` with `:phase/succeed`. Unchanged.

### Test surface

- `phase_transitions_test.clj` rewritten (small file, ~250 LOC).
- `fsm_test.clj` legacy-API tests untouched; new validator tests added.
- Per-phase tests: phases emit simpler verdict shapes — most existing
  assertions remain.

### Things that could go wrong

- **Guard-evaluation order surprises.** `clj-statecharts` matches first
  guard; document-order matters. Mitigation: Phase 5's validator
  enforces "budget guard precedes redirect branch."
- **Action ordering vs context propagation.** `assign` actions mutate
  context; bare actions side-effect. Mixing them needs care. Mitigation:
  use `assign` exclusively for redirect-count and verdict-history.
- **Edge cases in DAG sub-workflow interaction.** A DAG leaf-task failing
  with a stagnation verdict — does the failure propagate to the parent
  workflow as a redirect or as a terminal? Mitigation: Phase 2 surfaces
  this through the integration tests; if behavior must change, do it
  explicitly in Phase 3.

## References

- [Guarded Transitions | clj-statecharts](https://lucywang000.github.io/clj-statecharts/docs/guards/)
- [Actions & Context | clj-statecharts](https://lucywang000.github.io/clj-statecharts/docs/actions/)
- [lucywang000/clj-statecharts on GitHub](https://github.com/lucywang000/clj-statecharts)
- [XState v5 announcement](https://stately.ai/blog/2023-12-01-xstate-v5)
- [Guards | Stately/XState](https://stately.ai/docs/guards)
- [gen_statem behaviour, stdlib v7.3](https://www.erlang.org/doc/apps/stdlib/gen_statem.html)
- [gen_statem Behaviour — Erlang System Documentation](https://www.erlang.org/doc/system/statem.html)
- Internal: `docs/architecture/state-ownership-and-phase-graph.md`,
  `docs/architecture/workflow-component.md`
- Bug origin PRs: #1010, #1011, #1013 (workarounds that motivated this RFC)
- Memory: `project_terminal_fail_event_gap.md`
