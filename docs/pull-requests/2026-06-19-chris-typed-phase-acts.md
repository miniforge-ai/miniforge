# feat: typed handoff acts for phase outputs, meta-loop halts, and PR-monitor decisions

## Overview

Makes three inter-agent handoff points explicit, typed acts instead of signals
reconstructed from status flags or FSM transitions:

1. **Phase outputs** — `:phase/outcome` gains `:blocked` (a refusal with a
   machine-readable cause) and `:redirected` (a request to the pipeline).
2. **Meta-loop halts** — a new `:meta-loop/halt-requested` event puts a
   meta-agent's halt on the event stream as a typed refusal with a reason code.
3. **PR-monitor decisions** — a new `:pr-monitor/decision-recorded` bus event
   records the classify→act choice (`:fix`/`:answer`/`:approve`/`:skip`) as one
   act with provenance, at the single routing point.

A single closed `RefusalReason` vocabulary is shared by phase `:blocked` and
meta-loop halts. It also closes a pre-existing gap: the `:agent/message-sent` /
`:agent/message-received` constructors had no schema; both are now defined.

## Motivation

A review of miniforge against the Symbol Grounding Framework ontology found that
the SDLC handoffs are implicit speech acts. The one borrowed idea was to name
them — not by importing a generic performative vocabulary (FIPA/AFP) as field
values, but by extending miniforge's own closed, domain-tuned enums. The review
phase already emitted a typed `:phase/review-decision`; the gap was uneven
coverage of the *refusal* and *decision* acts, which were the least legible:

- A meta-agent halt lived only in the coordinator's return value and the
  runner's error map — never on the stream, so absent from observability and
  evidence.
- The PR-monitor's chosen action was spread across fine-grained bus events
  (`fix-started`, `question-answered`, …) with no single decision act.
- A phase that cannot proceed had no first-class outcome distinct from a crash.

## Changes in Detail

### Event-stream contract (`components/event-stream`)

- `RefusalReason` — closed enum shared by refusal acts; consumers tolerate
  unknown values (forward-compat).
- `PhaseCompleted` — `:phase/outcome` enum widened with `:blocked` / `:redirected`;
  added optional `:phase/blocked-reason` (a `RefusalReason`).
- `AgentMessageSent` / `AgentMessageReceived` — new schemas for the existing
  constructors (gap close); fields match the code (`:message/type`,
  `:message/content`, optional `:from-agent/instance-id`).
- `MetaLoopHaltRequested` — new `:meta-loop/halt-requested` schema, constructor,
  interface exports, and privacy registration (`:internal`).

### Phase outputs (`components/phase`, `components/workflow`)

- `phase-result/blocked` factory (reuses the error halt path, tags the cause) +
  `blocked?` / `blocked-reason` predicates, exported through the phase interface.
- `runner-events` derives `:phase/outcome` via a new `phase-outcome` helper
  (refusal and failure dominate; a successful redirect is `:redirected`) and
  carries `:phase/blocked-reason` onto the event.

### Meta-loop halt (`components/workflow`)

- `monitoring/handle-supervision-halt` emits `:meta-loop/halt-requested` before
  transitioning. Reason code resolves from the halting agent's explicit
  `:halt/reason-code`, else a per-agent default, else `:no-progress`. Emission
  is best-effort — a telemetry failure cannot mask the halt.

### PR-monitor decision (`components/pr-lifecycle`)

- `monitor-events/decision-recorded` constructor + `:pr-monitor/decision-recorded`
  event type. `monitor-handlers/route-comment` emits it once at the routing
  point, with a pure `category->action` map.

### Outcome consumers (`bases/cli`, `components/tui-views`)

- `workflow-runner/display` renders `:blocked` (red ⊘) and `:redirected`
  (yellow ↻) distinctly, instead of falling through to the green-check default.
- `tui-views/persistence/phase-status` maps `:blocked` → `:failed` and
  `:redirected` → `:success` (nearest existing TUI status), instead of the
  `:running` default.

### Specs

- N3 §3.1 phase-completed outcome list; §3.7 field-name correction to match code;
  new §3.7b documenting `:meta-loop/halt-requested` and the `RefusalReason` set.
- N6 §2.3 note: a phase's typed outcome (incl. `:blocked` + reason) is recoverable
  from the linked phase-completed event via `:phase/event-stream-range`.

## Architecture Changes

- **`:phase/outcome` is the typed-act surface; `:status` is unchanged.** The
  internal phase-result `:status` stays a two-valued control flag for the FSM
  (read by many leave-handlers); the full act vocabulary lives on the observed
  layer only. This avoids destabilizing control flow.
- **PR-monitor decision is a bus event, not a stream event.** The PR-monitor
  runs on its own in-process bus (`monitor-state/emit!`), decoupled from the
  observability event-stream by design. The decision act lives where the
  monitor's other typed events live. Surfacing it into evidence bundles would
  require a pr-lifecycle→event-stream bridge — out of scope here, noted as the
  follow-on.

## Testing Plan

- New: `phase-result-test`, `typed-acts-schema-test`, `monitor-handlers-test`;
  added cases to `runner-events-test` and `monitoring-test`.
- Coverage: `blocked` factory shape; `:blocked`/`:redirected` outcomes through
  the public `publish-phase-completed!` path; all new events validate against
  their Malli schemas; `RefusalReason` rejects unknowns; halt emission +
  reason-code resolution (default + override); `route-comment` records one
  decision with provenance; `decision-action` category mapping.
- Verified: `clj-kondo` clean; affected tests green (107 assertions);
  `clojure -M:poly check` OK.

## Deployment Plan

Product is unreleased — no compatibility shims. The widened enum and new event
types are additive; existing producers and consumers are unaffected. No data
migration.

## PR Layering

This spans the event-stream contract plus three emitters. The pieces are not
independently meaningful (an enum value with no producer is dead; a producer
referencing an undefined value fails validation), so it lands as one cohesive
change rather than the four-PR DAG the <400-line guideline would otherwise
suggest. If a split is preferred: PR1 = event-stream contract + specs; PR2 =
phase outputs; PR3 = meta-loop halt; PR4 = PR-monitor decision.

## Related

- Specs: N3 (event stream), N6 (evidence & provenance)
- Source: SGF/MiniForge ontology review (typed-act recommendation)

## Checklist

- [x] `RefusalReason` + widened `PhaseCompleted` schema
- [x] Inter-agent message schemas (gap close)
- [x] `MetaLoopHaltRequested` schema + constructor + interface exports
- [x] `phase-result/blocked` + predicates + runner outcome wiring
- [x] Meta-loop halt emission with reason-code resolution
- [x] PR-monitor `decision-recorded` bus event + routing emit
- [x] N3 / N6 spec updates
- [x] Tests for all five areas
- [x] clj-kondo clean, affected tests green, `poly check` OK
- [ ] Push and open PR (awaiting approval)
