<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# State Ownership and the Phase Graph

Status: design note / direction (2026-05-27)

Two of the worst structural problems in the codebase are related:

1. **Too many state machines, no formal ownership.** Task/FSM state, DAG
   frontier, budget counters, lock pools, git, and GitHub all hold pieces of
   "the truth," and there is no written rule for *who answers which question*
   or *who wins when they disagree*.
2. **The phase graph is data-driven and opaque.** Phase chains are EDN composed
   at load time with dynamic interceptor middleware. The most complex behavior
   emerges from the most data-driven configuration — powerful, but no static
   analysis can see the runtime graph, so ordering / termination / retry-bound
   errors surface only at runtime (the 2026-05-26 overnight retry runaway was
   one symptom).

This note fixes ownership on paper, defines the invariants that must hold, and
specifies the static guard (the phase transition graph validator) plus the
sequenced opacity fixes.

---

## 1. Canonical Sources

Every fact has exactly one owner and one resolution rule.

| Fact                     | Owner       | Resolution rule                |
|--------------------------|-------------|--------------------------------|
| Code content             | Git         | Git always wins                |
| PR status                | Git/GitHub  | External; poll, don't cache    |
| Task phase / FSM state   | Datalevin   | Datalevin always wins          |
| Dependency edges         | Datalevin   | Derived in-memory at startup   |
| Budget counters          | Datalevin   | Single writer per task         |
| Scheduling frontier      | In-memory   | Derived; rehydrate on crash    |
| Lock pools               | In-memory   | Derived; never authoritative   |

Rule of thumb: **persistent truth lives in Datalevin or Git; in-memory state is
always derived and rebuildable.** Anything in-memory that cannot be rebuilt from
Datalevin + Git is a latent data-loss bug.

### Conflict Resolution

- **In-memory diverges from Datalevin** → Datalevin wins; in-memory is rebuilt.
- **Datalevin diverges from Git** → surface as an invariant violation, **halt
  the task**, a human resolves. (These two should never disagree; if they do,
  something wrote state without doing the work, or vice versa.)

---

## 2. Crash Recovery Protocol

A crash must be recoverable purely from the canonical sources:

1. Rehydrate task records from Datalevin.
2. Rebuild the DAG frontier from task dependency edges.
3. Reacquire locks for tasks in `:running` state.
4. Git worktree state is ground truth for code; re-export if missing.

No step may depend on in-memory state that was not first rebuilt from Datalevin
or Git.

---

## 3. Invariant Checker

A per-task checker that asserts the canonical sources agree. Run it at phase
boundaries and on resume; a failing invariant halts the task loud.

```clojure
(defn check-task-invariants [task-id]
  {:state-coherent?  (= (git/branch-exists? task-id)
                        (task/state-running-or-later? task-id))
   :budget-not-over?  (<= (budget/actual-spend task-id)
                          (budget/allocated task-id))
   :retry-bounded?    (< (task/retry-count task-id)
                         (phase/max-retries (task/current-phase task-id)))
   :phase-not-stuck?  (< (task/time-in-current-phase task-id)
                         phase/stuck-threshold)
   :dag-coherent?     (every? task/exists? (dag/dependencies task-id))})
```

`:retry-bounded?` and `:phase-not-stuck?` are the runtime counterparts of the
static checks in §4 — they catch at runtime what the validator catches at load
time.

---

## 4. Phase Transition Graph Validator (most urgent)

A static analysis pass over the phase EDN configs. Treat every phase as a node
and every transition (`:next`, `:on-failure`, `:on-budget-exhausted`) as a
directed edge. Build the graph; check properties.

### What to validate

1. **Existence** — every referenced phase name resolves to a loaded impl.
2. **Termination** — every chain has at least one reachable terminal state
   (`:done`/`:completed`/`:failed`/`:escalated`).
3. **Cycle detection** — flag any cycle that lacks a bounded exit condition.
4. **Retry bounds** — any back-edge (`A → A`, or `A → B → A`) must have an
   explicit `max-retries` with a transition to a terminal when count exhausted.
5. **Failure paths** — every non-terminal phase has an `:on-failure`
   transition; none dangling.
6. **Budget paths** — every non-terminal phase has an `:on-budget-exhausted`
   transition; none dangling.
7. **Interceptors** — every interceptor hook references a fn that exists at
   load time.

```clojure
;; bb phases:validate   or   mf phases:validate
(defn build-transition-graph [phase-configs]
  ;; => {:nodes #{phase-name} :edges #{[from to label]}}
  )

(defn validate-graph [graph]
  {:unresolved-refs   (find-dangling-refs graph)
   :unbounded-cycles  (find-cycles-without-exit-condition graph)
   :missing-failure   (phases-without-failure-path graph)
   :missing-budget    (phases-without-budget-path graph)
   :unreachable       (find-unreachable-terminals graph)})
```

Run at two points:

- **Load time** — fail fast on startup if the active phase pack's graph is
  invalid.
- **`bb phases:validate`** — standalone, runs in CI and before deploying a new
  phase pack.

This is the static analog of the `#988` runaway guard: an unbounded back-edge
is a config-time error, not a 17-hour runtime discovery.

> Spec: `work/phase-transition-graph-validator.spec.edn`.

---

## 5. Dynamic Phase Composition Opacity — three problems, three fixes

Phase plugins load at runtime from EDN; chains are configured in data;
interceptor middleware is applied dynamically. The result is three distinct
opacity problems wearing one coat.

### Problem 1 — Can't see the graph

The phase graph exists at load time but nobody renders it; it lives in memory,
invisible.

**Fix 1 — make the graph visible (1–2 days).** The validator (§4) already
*builds* the graph; rendering it is trivial additional work.

```text
bb phases:graph                    # full graph, all chains
bb phases:graph --chain software   # one chain
bb phases:graph --task abc123      # the path this task took (from event stream)
```

Output Mermaid or DOT (renders in GitHub PRs natively, zero tooling cost). Each
node shows name, attached interceptors, and `max-retries` if it has a back-edge;
each edge shows its transition label. Additionally, **log the resolved chain at
load time**:

```text
[phases] Loaded chain: software-factory
  :explore   interceptors: [audit knowledge-inject budget-gate]
  :plan      interceptors: [audit knowledge-inject conflict-check budget-gate]
  :implement interceptors: [audit knowledge-inject secrets-scan budget-gate]
  ...
```

"Opaque" becomes "one command away from a diagram."

### Problem 2 — Contracts aren't declared

Phases don't formally state the input shape they need or the output shape they
guarantee, so chain compatibility is implicit and wrong shapes flow silently
until something crashes downstream.

**Fix 2 — declare contracts, check chain compatibility (3–4 days).**

```edn
{:phase/name   :implement
 :phase/input  :schema/explore-output    ;; what I need from upstream
 :phase/output :schema/implement-output  ;; what I guarantee downstream
 :on-failure   {:next :implement :max-retries 3 :on-exhausted :escalate}}
```

At chain load time, walk the edges: for each `A → B`, does `A`'s `:phase/output`
satisfy `B`'s `:phase/input`? A Malli compatibility check, failing **at load
time** with a clear message:

```text
ERROR: Chain 'software-factory' transition :explore -> :plan is invalid.
  :explore produces :schema/explore-output
  :plan expects :schema/plan-input
  Missing keys: [:plan/task-decomposition :plan/file-candidates]
```

A type system for the pipeline — Malli does this today. Also register the
schemas for clj-kondo hooks / Calva so the EDN config gets autocomplete and
inline validation in the editor.

#### Boundary enforcement — the Ixi two-level pattern, reimplemented

Declaring `:phase/input` / `:phase/output` schemas is half of it; the other
half is *enforcing* them at the phase boundary with the right cost profile in
dev vs production. The Ixi `custom-flow-control` pattern maps cleanly and is
worth reimplementing as a new miniforge `boundary-validation` component (note:
the existing `boundary` component is exception→anomaly→chain handling, *not*
schema validation — this is additive, not a duplicate).

Two levels, used together:

- **Level 1 — `asserting-passthru` (dev/test, compiles out).** Wraps the call
  in an elide-able hard assertion of the input/output schema. Under
  `:elide-asserts true` (release builds) it is *literally gone from bytecode* —
  zero cost. Use it where a schema violation is a programmer error that should
  throw loudly in dev.
- **Level 2 — `conforming-passthru` (runtime, dynamic-var gated).** A
  `^:dynamic *validate-boundaries*` toggle: when `false` (production fast path)
  the validation is skipped entirely; when `true` it runs the Malli check.

```clojure
(def ^:dynamic *validate-boundaries* true)

;; dev / test — full validation, throws on violation
(asserting-passthru execute-phase [[PhaseInputSchema input-data]])

;; production — *validate-boundaries* false → zero overhead
(conforming-passthru execute-phase [[PhaseInputSchema input-data]])
```

A `defphase` macro encodes both: phase authors declare `:input`/`:output`
schemas and the boundary wrappers are generated.

**Two deliberate departures from the ixi original:**

1. **Reimplement; do not copy.** Per the IP boundary (miniforge stays
   documented-clean; no ixi source moves in before a written mutual release),
   build this fresh. The *pattern* is uncopyrightable and reimplementable
   freely — the code is not imported.
2. **Malli-only.** Ixi's `conforming-passthru` is a mixed spec+malli DSL;
   miniforge is malli-single-source (the standing architectural verdict was to
   leave the spec+malli DSL behind). Drop the spec dispatch in `process-tuple`;
   keep the Malli path.

**One addition ixi lacks (the production third level):** on a Level-2
production validation failure, don't merely log — emit a structured
`:phase/schema-violation` event to the N3 event stream. Telemetry without
crashing, observable in the observe loop, and overlayable on the phase graph
(Fix 3). So the strategy is three-tier: dev throws (Level 1), production-with-
validation emits an event and continues degraded (Level 2 + N3), production
fast-path skips (Level 2 off).

### Problem 3 — Can't see what happened at runtime

Even given the static graph, you can't observe which path a specific task took,
which interceptors fired, or which guard chose `:on-failure` over `:next`. This
is the N3 event-stream gap.

**Fix 3 — runtime trace (N3 completion).** Every transition emits:

```clojure
{:event/type      :phase/transition
 :task/id         task-id
 :phase/from      :explore
 :phase/to        :plan
 :transition/kind :next             ;; or :on-failure, :on-budget-exhausted
 :interceptors    [:audit :knowledge-inject :budget-gate]
 :duration-ms     4821
 :retry-count     0}
```

`bb phases:graph --task abc123` then overlays the actual execution path on the
static graph: green edges taken, grey not taken, red failed. Map + route.

---

## 6. The Deeper Tension

Data-driven configuration is flexible because it defers decisions to runtime —
which is exactly why it's opaque: the shape of the system isn't knowable until
it runs.

**Resolution: make common paths typed and visible; make extension points
data-driven but bounded.**

- The core chain (`Explore → Plan → Implement → Verify → Review → Release →
  Observe`) becomes a **first-class typed construct** — a declared structure
  with a known shape, not just EDN.
- Custom phases and interceptors extend it through data, but extension points
  are **well-typed slots**, not arbitrary composition.
- Adding an interceptor requires satisfying a declared interface, not just
  dropping in a function reference.

This narrows the surface where opacity can hide. Less flexible than fully
arbitrary composition — but the rigid parts are the parts that bite when they go
wrong. A bigger architectural conversation (RFC first), not a sprint.

---

## 7. Sequencing

| When        | Work                                                          |
|-------------|---------------------------------------------------------------|
| This week   | Graph validator + interceptor load-time logging + graph render |
| Next sprint | Malli input/output contracts + chain compatibility check      |
| Ongoing     | Phase transition events as part of N3 completion              |
| Later (RFC) | Typed core chain + bounded extension points                  |

The validator makes misconfiguration a load-time error; the renderer makes the
system debuggable; contracts make wrong-shape flow a load-time error; runtime
events make production incidents diagnosable; the typed core chain is the
long-term play.
