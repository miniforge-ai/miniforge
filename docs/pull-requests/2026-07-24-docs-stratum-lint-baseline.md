# docs: stratum-lint full-tree baseline + fix-wave plan

## Overview

Records the first full-tree `stratum-lint` run against the workspace (previously
only run against staged files, in pre-commit) and lays out a six-wave plan to
work through the resulting debt.

## Motivation

Rule 210's per-file `Layer N` heading convention has been cargo-culted:
headings are being reused as decorative section banners rather than marking
real one-way strata. Nobody had run the linter full-tree to see the scale,
because the only existing wiring (`bb lint:stratum`) checks staged files
only. This PR establishes the baseline so remediation can be planned and
tracked.

## Changes in Detail

- `work/stratum-lint-baseline-2026-07-24.md` — findings summary (876 findings,
  378 files, 75/121 components/bases), root-cause diagnosis with worked
  examples, a per-component pivot table, individual triage of the 18 files
  reporting SL001 (upward-reference — 5 of 6 sampled were checker false
  positives from unscoped symbol matching, not real violations), and the
  six-wave remediation plan.
- `work/stratum-lint-baseline-2026-07-24.findings.txt` — raw tool output
  (876 lines), archived as the source-of-truth reference data behind the
  analysis. Generated, not hand-authored; commit-budget override used for
  this file (see commit message).

## Testing Plan

Docs-only change, no code touched. Verified by construction: the tool
invocation, its exit code, and every worked example in the `.md` were
checked against the actual files before being written up (see the "Diagnosis"
and "SL001" sections of the baseline doc for the specific lines read).

## Deployment Plan

Merges to `main`. No runtime effect. Unblocks Wave 0 (fixing the
`stratum-lint` false-positive class upstream, then wiring pre-commit to
autofix) and the subsequent per-component fix-wave PRs.

## Related Issues/PRs

- Upstream: Wave 0's false-positive fix is a separate PR against the
  `miniforge-ai/stratum-lint` repo, not this one.
- Follow-on: per-component fix PRs for Waves 1-4, one PR per component per
  `workflows/pr-layering` (722).

## Checklist

- [x] PR doc committed with the change
- [x] Raw findings reproducible via the documented `bb -m stratum-lint.interface` invocation
- [x] Commit-budget override rationale recorded in the commit message
