# fix: register :anomalies.workflow/no-capsule-executor in workflow taxonomy

## Overview

Adds the `:anomalies.workflow/no-capsule-executor` keyword to `response.anomaly/workflow-anomalies`. This keyword has
been thrown by the runner since the governed-mode invariant landed (N11 §7.4) but was missing from the taxonomy set, so
`(response/anomaly? :anomalies.workflow/no-capsule-executor)` returned false.

## Motivation

Flagged in PR #769 and PR #777 as a pre-existing latent quirk. The `throw-anomaly!` machinery doesn't validate against
the taxonomy at throw time, so the missing registration didn't surface as a runtime error — but the keyword was
effectively orphaned: no membership in `all-anomalies`, no classification via `classify-by-namespace`, false positives
from `anomaly?`. This PR closes the gap.

## Base Branch

`main`

## Depends On

None.

## Layer

Hygiene fix. Single-line addition to a taxonomy set.

## What This Adds / Changes

`components/response/src/ai/miniforge/response/anomaly.clj`:

- Added `:anomalies.workflow/no-capsule-executor` to the `workflow-anomalies` set, with a comment citing N11 §7.4 (the
  spec section that defines the invariant).
- Re-aligned the comment column for visual consistency with the rest of the set.

## Strata Affected

- `ai.miniforge.response.anomaly/workflow-anomalies`
- Transitively: `all-anomalies`, `anomaly?`, `classify-by-namespace`, `retryable?` — all of which derive from the
  per-domain sets.

## Testing Plan

`bb test`: **5015 tests / 22928 passes / 0 failures / 0 errors**. No new tests added — the addition is a single keyword
in a set; correctness is "the keyword is present in `workflow-anomalies` and therefore in `all-anomalies`".

## Deployment Plan

No migration. Pure additive change to a registration set. Existing throws using this keyword see no behavior change at
the throw site (`throw-anomaly!` doesn't validate). The keyword is now classifiable and queryable for downstream tooling
that reads `all-anomalies`.

## Notes

- **Validation not introduced.** This PR doesn't add throw-time taxonomy validation; that's a separate hygiene call
  (potentially worth doing later — would catch this class of registration drift at the throw site rather than during
  incidental code review).
- **Other unregistered keywords.** A grep for `:anomalies.workflow/...` and the other domain prefixes might surface more
  gaps. Out of scope for this PR; flagged for a future inventory pass if useful.

## Related Issues/PRs

- Pre-existing quirk noted in PR #769 (workflow runtime cleanup) and PR #777 (kill-the-deprecation)

## Checklist

- [x] Keyword registered in `workflow-anomalies`
- [x] Comment cites the spec source (N11 §7.4)
- [x] `bb test` green
- [x] No other behavior change
