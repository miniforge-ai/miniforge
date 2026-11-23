<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Predeclared OPSV verification environments

## Overview

Formalize the approved distinction between intentional candidate changes and
unrelated environment drift in N7. This PR changes the specification only.

## Motivation

Exact baseline equality rejects intended configuration changes and replica
replacement. Ignoring changed fields would instead hide unrelated drift.
Predeclared expectations retain a comparison contract without either ambiguity.

## Changes in Detail

- Bind expected changes to the candidate, pack, run, target, and baseline.
- Require explicit bounds and stable workload identity for replica churn.
- Preserve the original baseline for separately authorized, verified recovery.
- Reject undeclared differences and post-observation expectation changes.
- Keep N3/N6 wire contracts and N4/N8/N10 execution authority unchanged.

## Testing Plan

Review every normative statement against the approved decision and applicable
specifications. Run documentation checks, normal hooks, CI, and full-head review.
No executable code changes; behavior tests accompany the implementation PRs.

## Deployment Plan

No runtime deployment or live effects. Implement bottom-up: this contract,
pure comparison rules, then guarded runner integration, each independently gated.

## Related Issues/PRs

Base branch: `main`. Depends on: none. Follows merged #2343, #2350, and #2351.
Source: the operator's November 3 approval of candidate-aware verification in
the N7 implementation-completion session. N7 remains implementation-incomplete.

### Approval record

The operator reviewed two alternatives: retain exact baseline equality, or
permit predeclared candidate changes and bounded replica replacement.
The recommended option required exact candidate/target binding, unrelated-drift
rejection, the original recovery baseline, and no post-execution rebasing.
Existing safety and review gates remained mandatory. The recommendation
explicitly authorized implementation only, not experiments or cluster changes.

The operator's November 3 response in that session was:

> Agree with the recommendation. Proceed.

This records the direct approval without publishing the session's unrelated
history. Section 6.3 specifies the approved behavior, not existing implementation.

## Checklist

- [x] Check consistency with N1–N6 and N7–N10.
- [x] Complete adversarial and applicable-standards review.
- [ ] Pass hooks, CI, and full current-head Copilot review.
