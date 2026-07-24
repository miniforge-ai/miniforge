# spec: Add N14 Shared Deliberation Workspace and N15 Collective-Cognition Evaluation Harness

## Overview

Adds two extension specs to the N-series and indexes them:

- **N14 — Shared Deliberation Workspace**: a typed, event-sourced, transactional shared reasoning state that multiple
  stateless agents mutate through governed transactions, with per-role projections as their only input. Speculative
  status: its normative force is conditional on N15 gates.
- **N15 — Collective-Cognition Evaluation Harness**: the matched-budget evaluation protocol (implemented in minibench)
  that decides whether N14 — or any multi-agent architecture — beats test-time-compute baselines. Pre-registered
  Gates G0/G1 govern N14's stage promotion.

No code changes. `SPEC_INDEX.md` bumped to 0.8.0-draft.

## Motivation

Mixture-of-agents via routing/voting/synthesis is test-time compute scaling; published results indicate its gains are
largely reproduced by best-of-N from the strongest single model at equal budget. Those architectures belong in the
baseline set, not the product thesis. The candidate step function is different: persistent typed shared state as the
only communication medium between agents, with disagreement resolved by discriminating experiments and claims bound to
executable evidence. Whether that is a real capability gain is an empirical question, so the substrate ships gated on a
falsification harness rather than as an assumed feature.

Design departures from the common proposal shape, encoded in N14:

- Structural statuses instead of numeric confidences (uncalibrated numbers feeding a scheduler are unfalsifiable).
- Deterministic structural scheduling in v0; salience/information-gain policies are a benchmark axis behind a seam.
- Stateless activations with projection-as-only-input, so the `cross_visibility: none` ablation measures whether shared
  state is load-bearing.
- Challenges must carry evidence or a discriminating experiment (anti-livelock).
- Latent-state channels (hidden states, KV-cache) are out of scope: unavailable to closed-weight CLI populations and
  unverifiable in a governed substrate.

### Speculative lifecycle

N14 §0.4: pre-gate, conformance binds only experimental implementations (an unfaithful pilot cannot falsify the
design); if Gate G0 fails, N14 is re-statused Informative and retained as a do-not-build record with the deciding
experiment report attached. N15 §0.4: the harness core (budgets, replication, comparability, task class, metrics,
regression gating, conditions C1–C5) is architecture-agnostic and survives any outcome; only its workspace-conditional
sections (C6/C7, ablation delta, G0/G1) demote with N14.

## Changes in Detail

- `specs/normative/N14-shared-deliberation-workspace.md` (new): object taxonomy, transaction vocabulary and validation,
  projections and ablation switch, roles and permission matrix, structural v0 scheduler, termination rules, capsule
  binding (N11), N3 events, N6 closure exports, OCI surfaces (N8), governed transaction submission (N10), conformance
  Stages 0–2.
- `specs/normative/N15-collective-cognition-harness.md` (new): hypotheses H1–H5, conditions C1–C7, budget tiers and
  cost-quality curves, replication/comparability/provenance requirements (the 2026-07-02 minibench methods-review gaps
  made normative), long-horizon task class, metrics, effect rule, pre-registered Gates G0/G1.
- `specs/SPEC_INDEX.md`: N14/N15 entries; version 0.7.0-draft → 0.8.0-draft.

## Testing Plan

Docs-only change; `bb pre-commit` (markdownlint + suite) run before push. Spec content is validated empirically by its
own mechanism: N15 Gate G0 (Stage 0 file-backed pilot vs. C1/C2/C4 baselines) decides whether N14 advances.

## Deployment Plan

Merge only; no runtime impact. Follow-up work, in order: conforming task fixtures (N15 §6), Stage 0 pilot (N14 §11),
G0 experiment manifest, then a G0 run and verdict.

## Related Issues/PRs

- Drafted from the spec-additions workspace (branch `claude/moa-miniforge-design-f671e5`, commits 77684d9, 0734a59).
- Minibench methods review 2026-07-02 (workspace root) — gaps 1–4 become N15 §5 requirements.
- Numbering note: OSS normative series was occupied through N13; these take N14/N15. The Fleet series in
  `miniforge-fleet-specs` independently uses N10–N12; that collision predates this PR and is not resolved here.

## References (informative, verified 2026-07-22)

- Sampling/controls: More Agents Is All You Need — <https://arxiv.org/abs/2402.05120>; Are More LLM Calls All You
  Need? — <https://arxiv.org/abs/2403.02419>; Rethinking Mixture-of-Agents (Self-MoA) —
  <https://arxiv.org/abs/2502.00674>; equal-thinking-budget single- vs multi-agent — <https://arxiv.org/abs/2604.02460>
- Failure taxonomy: Why Do Multi-Agent LLM Systems Fail? (MAST, NeurIPS 2025) — <https://arxiv.org/abs/2503.13657>
- Blackboard-style shared workspaces: <https://arxiv.org/abs/2507.01701>; <https://arxiv.org/abs/2510.01285>
- Global-workspace event loop: Theater of Mind — <https://arxiv.org/abs/2604.08206>
- Latent collaboration and its integrity problem: LatentMAS — <https://arxiv.org/abs/2511.20639>; Interlat —
  <https://arxiv.org/abs/2511.09149>; When Latent Agents Lie — <https://arxiv.org/abs/2606.28958>

## Checklist

- [x] N14 spec with speculative lifecycle (§0.4) and staged conformance (§11)
- [x] N15 spec with lifecycle coupling (§0.4) and pre-registered gates (§8)
- [x] SPEC_INDEX entries + version bump
- [x] PR documentation (this file)
- [ ] Review and merge
- [ ] Follow-up: task fixtures, Stage 0 pilot, G0 manifest
