<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# YC MVP Demo Script: Governed AI Software Factory

**Status:** MVP demo script (informative)  
**Audience:** YC partners, technical founders, early design partners  
**Target demo length:** 8-10 minutes  
**Primary value prop:** "Miniforge safely turns intent into shipped changes with live governance and audit-grade evidence."

---

## 1. Demo Goal

Show, in one narrative, that Miniforge can:

1. Ingest real engineering work (internal workflow + external PRs).
2. Execute with real-time visibility and operator control.
3. Enforce policy and prove safety with evidence/provenance.
4. Move multiple PRs to merge-ready state with deterministic sequencing.

This demo intentionally maps to N1-N9, with emphasis on N3, N5, N6, N8, and N9.

---

## 2. One-Sentence Pitch (Opening)

"Miniforge is a governed AI software factory: it runs code-change workflows
end-to-end, streams everything it does in real time, and produces an evidence
bundle proving why each change is safe to merge."

---

## 3. Demo Story Arc

**Narrative:**  
An infra/platform team needs to ship a cross-repo security update quickly. They
cannot trust "black-box agents." Miniforge coordinates the work, tracks risk,
allows live operator controls, and produces merge-ready outputs with auditable
evidence.

---

## 4. Environment Setup (Pre-Demo)

Prepare these before going live:

1. 2-3 repos configured for Fleet PR sync.
2. Seeded PR train with at least:
   1. one low-risk PR,
   2. one medium-risk PR,
   3. one blocked PR (failing CI or unmet dependency).
3. One workflow spec file for a small but visible change.
4. Web dashboard + CLI available locally.
5. At least one policy pack enabled with visible gate output.
6. A known evidence bundle path to open at end of demo.

---

## 5. Time-Boxed Script (8-10 Minutes)

## 0:00-0:45 - Problem and Promise

Say:

"Today teams can generate code fast, but they cannot govern it fast. We built
Miniforge to make autonomous delivery observable, controllable, and auditable."

Show:

- High-level dashboard with workflow + fleet surfaces.

## 0:45-2:00 - Bring in Real Work (N9)

Action:

1. Open Fleet view.
2. Trigger repo discovery/sync.
3. Show external PRs normalized into train/work-item state.

Say:

"This is not just Miniforge-generated code. We ingest existing GitHub PRs,
compute readiness/risk, and orchestrate them in train order."

Proof points to highlight:

- repo onboarding,
- PR train status,
- blocking dependencies.

## 2:00-4:15 - Run a Workflow Live (N2/N3/N5)

Action:

1. Start workflow from spec.
2. Show real-time event stream:
   1. phase transitions,
   2. agent status,
   3. tool/LLM events,
   4. gate outcomes.

Say:

"Every decision here is evented. We do not scrape logs; we stream a structured execution history in real time."

Proof points to highlight:

- live progression across phases,
- visible inner loop (validate/repair/re-validate).

## 4:15-5:45 - Exercise Control Plane (N8)

Action:

1. Pause workflow (or train) from control surface.
2. Add/observe an advisory annotation.
3. Resume execution.

Say:

"Operators are in control. We can pause, annotate, and resume with role-based control actions."

Proof points to highlight:

- control action event emission,
- resumed deterministic execution.

## 5:45-7:30 - Governance and Merge Readiness (N4/N9)

Action:

1. Show gate/policy result for a PR/workflow artifact.
2. Show readiness state and blocking reasons.
3. Show next PR eligible for merge in train.

Say:

"Readiness is computed from CI, review state, dependency order, and policy.
This gives teams deterministic merge operations, not subjective guesswork."

## 7:30-9:00 - Evidence Bundle (N6) + Close

Action:

1. Open generated evidence/provenance output.
2. Show intent -> phases -> validations -> outcome chain.
3. Point to promotion justification/risk rationale.

Say:

"This is the trust layer: exactly what changed, why, what checks ran, and why it is safe to promote."

Close line:

"We’re turning AI coding from 'maybe useful' into governed software throughput."

---

## 6. Demo Success Criteria

A successful MVP demo must visibly show:

1. External PR ingestion and train state updates.
2. Live event stream from a running workflow.
3. At least one control action (pause/resume or equivalent).
4. At least one policy/gate decision affecting readiness.
5. A generated evidence bundle with provenance and decision rationale.

---

## 7. Live Demo Risks and Fallbacks

1. If provider API/network is flaky:
   1. use pre-synced PR train snapshot and continue with local execution.
2. If LLM latency spikes:
   1. run a smaller spec fixture with deterministic expected output.
3. If UI route fails:
   1. mirror each step via CLI commands and open generated artifacts directly.
4. If a gate never fails naturally:
   1. include one seeded known-failing PR to showcase governance behavior.

---

## 8. MVP Scope Boundary

For YC demo MVP, we do **not** need full enterprise depth (multi-tenant RBAC
admin, OTLP pipelines, full GitLab parity, autonomous rollback orchestration).  
We need a reliable "happy path + one controlled failure path" that demonstrates governed autonomy end-to-end.

---

## 9. Implementation Work Breakdown to Reach Demo-Ready MVP

## Track A: Deterministic Demo Data and Fixtures

1. Create repeatable demo seed script:
   1. fleet repos,
   2. train setup,
   3. workflow spec fixture,
   4. one blocked PR fixture.
2. Add reset script to return environment to known state in <2 minutes.
3. Create golden evidence bundle fixture for fallback mode.

**Exit criteria:** Demo can be reset and rerun identically.

## Track B: N9 Demo Reliability (External PR + Trains)

1. Harden repo onboarding and PR sync error handling.
2. Add explicit "last sync status" and actionable failure messages in UI.
3. Ensure readiness + blocking reasons are always rendered.
4. Add integration test for multi-repo train with one blocked dependency.

**Exit criteria:** Sync + train view is stable under transient provider errors.

## Track C: N3/N5 Demo Reliability (Live Execution UX)

1. Ensure workflow launch path emits all key lifecycle events.
2. Verify UI updates for phase/agent/tool events under normal latency.
3. Add "demo mode" logging toggle for concise, audience-friendly output.
4. Add integration test for end-to-end event sequence coverage.

**Exit criteria:** Live run always shows visible progress and does not look stalled.

## Track D: N8 Demo Surface (Control Actions)

1. Finalize control action UX for pause/resume with clear state indicator.
2. Ensure each control action is evented and visible in timeline.
3. Add one smoke test for authorized control action execution.
4. Add one negative test for unauthorized action rejection.

**Exit criteria:** Pause/resume works live and is obviously auditable.

## Track E: N4/N6 Trust Story (Policy + Evidence)

1. Select one high-signal policy gate for demo (deterministic pass/fail).
2. Ensure gate result clearly impacts readiness state.
3. Standardize evidence viewer layout for:
   1. intent,
   2. checks run,
   3. violations/remediations,
   4. final promotion justification.
4. Add schema validation check in demo path before presentation.

**Exit criteria:** Evidence screen clearly answers "why safe to merge?"

## Track F: Rehearsal and Operator Runbook

1. Create 1-page operator checklist:
   1. pre-flight,
   2. live sequence,
   3. fallback triggers.
2. Rehearse full flow 5 times; record failure points.
3. Tighten script to 8-10 minutes with hard time boxes.
4. Freeze demo branch/tag 24h before presentation.

**Exit criteria:** Team can run demo cold with no improvisation.

---

## 10. Suggested Milestones

1. **M1 (2-3 days):** Demo fixture/reset scripts + stable seeded data.
2. **M2 (3-4 days):** External PR/train reliability and readiness rendering.
3. **M3 (3-4 days):** Live event stream and control action polish.
4. **M4 (2-3 days):** Evidence/gate narrative polish + operator runbook.
5. **M5 (1-2 days):** Full dress rehearsals + freeze.

**Total MVP effort:** ~2 weeks for a small focused team.
