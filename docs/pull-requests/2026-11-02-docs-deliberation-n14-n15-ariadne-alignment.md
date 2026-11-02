<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# docs(specs): align N14/N15 deliberation specs with Ariadne

## Overview

First PR in the stack that lands the deliberation-workspace work-in-progress
from branch `claude/wip-deliberation-moved-from-desktop` (snapshot `a345990`).
It carries only the normative spec amendments. Code follows in later PRs, so
the code conforms to specs that are already on main.

## Motivation

The snapshot amended N14 0.2.1 and N15 0.2.0 to:

- make Ariadne v1.6.1 §§11 and 13 the authority and information-flow contract,
  so a deliberation decision never authorizes an external effect;
- separate runtime-established participant identity from responsibility;
- allow actionable challenges of adopted records, and add an explicit
  `supersede` operation;
- add semantic admission requirements (types, references, attested provenance,
  atomic candidate validation);
- put the closure allowance inside the total matched allowance, reserved on every
  budget dimension (activations, cost and wall-clock);
- distinguish development comparisons from N15 gate evidence.

## Changes in Detail

- **N14 → 0.3.0-draft.** §0.5 (Ariadne alignment, synthetic development profile),
  §2.1 participant identity, §2.3 revisable adoption, §2.4 receipt-derived
  evidence classes, §2.5 contested claims in conflicts, §3.2 `supersede` with
  an explicit `:replacement`, §3.3 class row, §3.4 payload idempotency and
  semantic admission, §3.5 actionable challenges, §4 Ariadne projection
  constraints, §5.2 responsibility population, §5.3 `supersede` and soft
  constraints, §6.3 and §7 closure inside the allowance, success versus
  rejected goals, round-based quiescence, §10.1 governed actions under Ariadne.
  New conformance IDs `N14.WS.7` and `N14.WS.8` and test obligation 4.
- **N15 → 0.3.0-draft.** §0.5 development comparisons are not gate evidence,
  §8.1 paired binary comparisons, §8.4 version-scoped negative results.
- **Annex A of both specs** records what is on main today. The snapshot's
  annex text described the lab base and architecture atlas, which are not on
  main yet. Later PRs in this stack update the annexes as each part lands.
- **SPEC_INDEX → 0.28.0-draft** with an N14 summary refresh and history entry.

Edits relative to the snapshot text:

- Hard-wrapped lines the snapshot left broken mid-sentence (`machine-` /
  `readable`, `and this` / `requirement`).
- `supersede` names its replacement in a dedicated `:replacement` field
  instead of a `resolves` link. Written onto the superseded target, a
  `resolves` edge would read backwards ("the old decision resolves the new one").
- Links to `docs/architecture/deliberation/` are deferred to the PR that adds
  that directory, so this PR leaves no dangling links.
- Normative-only content stays in the normative body; the informative annex
  no longer uses RFC 2119 keywords (dewey 020).

### Excluded from the whole stack: `work/` queue residue

The snapshot also added 13 files (1,044 lines) under `work/done/`,
`work/failed/` and `work/in-progress/`. They are runtime queue residue from a
dogfood run, not edits:

- Each spec appears in two or three queue states at once
  (for example `rn-03-reliability-events` in both `failed/` and `in-progress/`).
- Eight of the copies are byte-identical to the active spec already at
  `work/<name>.spec.edn` on main.
- `migrate-runner-println-to-structured-logging` differs from main's active
  copy only by a run-time rewrite of its description.
- The other four (`phase-stream-idle-as-terminal-timeout`,
  `eliminate-requiring-resolve`, `monitor-loop-event-emission`,
  `observe-monitor-survives-cli-exit`) exist nowhere on main. `work/README.md`
  routes completed specs through `git mv` into `work/done/` and inventories
  active work in the generated `QUEUE.md`; neither was done for them.

No PR in this stack includes them.

## Testing Plan

Documentation only. `markdownlint` (the CI-pinned `markdownlint-cli`
version) runs clean on all three files. Every factual claim in the annexes
was checked against the repo: the component's namespaces, and the absence of
any `workspace/*` deliberation event in N3 §6.

## Deployment Plan

None; specs only.

## Related Issues/PRs

- Source: branch `claude/wip-deliberation-moved-from-desktop`, commit `a345990`.
- Next in stack: deliberation-workspace domain changes.

## Checklist

- [x] Spec text wraps at 120 columns outside tables
- [x] Annexes informative only, no RFC 2119 keywords
- [x] No links to paths that do not exist on main
- [x] `work/` residue excluded, with reason
