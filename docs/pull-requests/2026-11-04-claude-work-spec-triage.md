<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# chore(work): file finished work specs and split out the first dogfood target

## Overview

A review of every work spec against main (a033fba25) found specs whose work
has landed but which still sat in `work/`, plus stale copies in
`work/in-progress/` and `work/failed/`. This PR files them and adds a narrow
spec for the next dogfood run.

## Motivation

Agents read `work/` as the list of open work. A finished spec left there can
lead a run to implement it again, and `bb work:queue` marks its dependents as
blocked. The dogfood loop also needs small, current specs: the resume spec
mixed one concrete fix with two open investigations.

## Changes in Detail

1. Moved to `work/done/` (work on main; checked in source):
   1. `ariadne-identity-boundary` — `tenancy` component, `resolve-operator`.
   2. `codex-gap-instrument` — `codex-gap` component, `gap_wiring.clj`,
      `bb codex-gap-report`. Two gap-report measures it lists are still
      `:not-instrumented`; item 7 adds a spec for each.
   3. `dogfood-monitor-retest` — `algorithms/graph.clj` `reachable-count` (#1226).
   4. `per-task-base-chaining` — `dag_executor/branch_registry.clj`. Its
      multi-parent-rejection criterion was superseded by multi-parent merge.
   5. `policy-gate-phase-wiring-and-evidence` — `:policy-verify` and
      `:policy-review` gates in the verify and review defaults,
      `emit-rule-evidence!`. Enforcement keys off `:rule/enforcement :action`
      rather than severity.
   6. `polylith-compliance-remediation` — `poly check` passes.
   7. `polylith-workflow-gates-and-scope` — the CI `structure` job runs
      `bb poly:check` before lint and test.
   8. `review-redirect-convergence` — `review_convergence.clj`,
      `:review/warning-churn-policy :accept-with-warnings`.
   9. `environment-promotion-pipeline` — verify requires an environment
      (`require-environment-result`); release scans git;
      `environment_promotion_integration_test.clj`. Its "one code path"
      criterion was dropped: release keeps a host mode for local runs.
   10. `n06-sensitive-data-scanning` — superseded by the `redaction`
       component, which `collector.clj` applies before hashing.
2. `work/in-progress/`: removed four byte-identical copies of specs already
   in `work/done/` and an older copy of `n05-cli-command-wiring`; moved
   `dogfood-final-result-summary` and `dogfood-plan-artifact-warning` to
   `work/done/`.
3. `work/failed/phase-transition-graph-validator` moved back to `work/`. It
   was never implemented, and `docs/architecture/state-ownership-and-phase-graph.md`
   links it at that path. Its `:scope` now also lists the test dirs,
   `workflow/fsm.clj` (where its Task 0 finds the transition edges) and
   `bb.edn` (its validation task), which its acceptance criteria need.
4. `n06-compliance-metadata` stays in `work/`: its defaults and overrides are
   on main (`compliance_defaults.clj`), but nothing outside tests calls
   `append-access-log-entry`, so its access-log criterion is unmet.
5. Archived to `work/archive/stale/`: `workflow-redesign-use-case-targeted`
   (superseded by the canonical workflow set) and `dashboard-production-ready`
   (no `:spec/acceptance-criteria`, and its `:scope` is prose, not paths).
6. New `workflow-resume-restore-failure-escalates`: in local mode, a failed
   workspace restore on resume throws
   `:anomalies.workflow/workspace-restore-failed` instead of continuing with
   no environment. `workflow-resume-fsm-advance-investigation` keeps causes
   (b) and (c) and is now blocked by it.
7. New `codex-gap-consultation-match-rate` and `codex-gap-learning-latency`:
   the two slots `codex-gap.report/build-report` leaves `:not-instrumented`.
   The match rate reads the consultation records phases already write, once
   each record keeps the anomaly that separates an unmatched situation from
   an unreadable codex. Learning latency needs harvest entries to name the
   misses they decide. Both sit under a new `codex-gap` theme in
   `work/themes.edn`, anchored on T2 §3–§4; no existing theme covered it.
8. `work/QUEUE.md` regenerated with `bb work:queue`.

## Testing Plan

Documentation and spec files only. `bb work:queue` regenerates the queue
without errors and lists the new spec under dogfood-resilience as a ready
blocker.

## Deployment Plan

None.

## Related Issues/PRs

1. #2246 — verify's gate output; without it no default-pipeline run passes
   verify, so it lands before the next dogfood run.

## Checklist

- [x] Each moved spec checked against main
- [x] New spec has priority, theme, scope paths and testable criteria (rule 021)
- [x] `bb work:queue` regenerated
