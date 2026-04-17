<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Supervisory state component (WIP) + dogfood diagnosis from failed miniforge runs

## Context

This PR captures in-flight work from the `mf/supervisory-state` branch before
it gets blocked by unrelated polylith remediation. Two strands:

1. The `supervisory-state` component — pure event → entity-table projection
   implementing N5-delta §3.4 (already committed on this branch as spec).
2. Housekeeping from two failed miniforge dogfood runs earlier today:
   restore specs, gitignore Claude Code local state, document the failure
   mode for the next iteration.

## What changed

### `components/supervisory-state/` — new component (WIP)

Three namespaces, 765 LoC total:

- `accumulator.clj` (352 LoC) — pure reducer `(table, event) → table'` with a
  dispatch table keyed on `:event/type`. Handlers cover workflow lifecycle,
  control-plane agent state, PR fleet, gates, and the five `:supervisory/*`
  snapshot events defined in the paired spec commit.
- `schema.clj` (211 LoC) — malli registry for `WorkflowRun`, `AgentSession`,
  `PrFleetEntry`, `PolicyViolation`, `PolicyEvaluation`, `AttentionItem`,
  `EntityTable`; status enums; `empty-table` constructor.
- `attention.clj` (202 LoC) — rule-based derivation of
  `:supervisory/attention-derived` events (workflow-failed, workflow-stale,
  workflow-completed) with UUID v5 stable IDs.

Paired with the already-committed spec on this branch
(`spec: add :supervisory/* snapshot event family + §3.4 materialization`).

**Explicit WIP:** no `interface.clj` yet. The component's public API surface
needs a deliberate design pass before any consumers wire in. Follow-up spec
will: (a) add `interface.clj` exposing `apply-event`/`apply-events`/`empty-table`
and whatever else the attention/dashboard consumers need, (b) add tests, and
(c) wire the component into the projects that need it.

Without `interface.clj` this component is structurally incomplete — no other
brick can import it. That's intentional for this PR. Locking in the
implementation + schema first lets the interface design follow from real
consumer needs.

### `.gitignore` — ignore `.claude/`

Claude Code's local session state (worktrees metadata, scheduled task locks,
per-session settings) has been leaking into `git status`. Add the directory
to `.gitignore`.

### `work/` — specs restored from `work/failed/`

Two miniforge runs earlier today ended in failure and miniforge auto-moved
the specs into `work/failed/`. Both are getting re-run (sequentially this
time), so restore them to `work/`:

- `work/polylith-compliance-remediation.spec.edn` — fixes the 13 `poly check`
  errors (unchanged from the version that merged in PR #566 work)
- `work/polylith-workflow-gates-and-scope.spec.edn` — adds `poly check` CI
  gate, affected-projects test scope, lsp-mcp-bridge audit (unchanged)

## Dogfood diagnosis — why no artifacts landed earlier

Event logs at `~/.miniforge/events/<workflow-id>/*.json` give us the answer:

### Remediation workflow `b86fdfba-5af9-4977-bed8-1a0e88976944`

- 212 events captured
- `:explore` → 0ms
- `:plan` → 14.9 min, succeeded (the artifact MCP tool failure was non-fatal)
- `:implement` → 13 min, **failed with**: `Curator: implementer wrote no files
  to the environment`
- Terminal event: `:phase/outcome :failure`, `:iterations 1`

The implementer agent ran for 13 minutes without calling a single write tool.
The curator correctly flagged the phase as a no-op failure. The new dogfood
shortcircuit did its job — the run failed fast (28 min total, not hours),
which is exactly the "fail fast and iterate" behavior we want.

### Gates workflow `ce0e3884-d457-446c-9b5c-38f04d443fe7`

- 2 events in the new topology (workflow/started + workflow/failed)
- Detailed run log (since lost with `/tmp` cleanup on restart) showed the
  run actually reached `:release` with 3/3 gates passed (verify, review,
  test all green, 482s of work)
- Release phase failed because pre-commit's `poly check` rejected the commit
  — the same 13 structural errors the OTHER spec was trying to fix.
- Worktree was `/tmp/mf-wt-gates`, which got GC'd on process exit

### Root cause

**Not** the worktree cleanup in an adjacent tab — those prunables were older
sessions. Two distinct failure modes:

1. **Remediation spec was too ambitious for one implement cycle.** Asking
   one agent to fix 5 distinct error classes (103/106/107/108/112) in one
   pass led to analysis paralysis — it explored but never wrote.
2. **Gates ran in parallel with remediation.** Gates finished its
   implementation, but pre-commit's structural gate blocked its commit
   because remediation hadn't landed yet. Classic workflow-ordering miss.

### Remediation

Next iteration (companion to this PR):

- Re-run specs **sequentially**: remediation first, let it land a PR, then
  gates.
- Consider splitting the remediation spec into one-group-per-run so the
  implementer has a tighter scope (Error 103 → Error 106 → Error 107 → ...).
  Not done in this PR — leaving that judgment call for the re-run.

## Verification

- `git status` clean after PR merge
- `poly check` unchanged (still 13 errors — remediation hasn't run yet)
- Existing tests pass (no test changes in this PR)

## Follow-up specs

- `supervisory-state-interface.spec.edn` — design + add `interface.clj`,
  wire into consumers, add tests
- Consider: split `polylith-compliance-remediation.spec.edn` into one spec
  per error class if the sequential re-run also stalls
