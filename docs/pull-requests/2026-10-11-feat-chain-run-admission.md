# feat: admit resolved chain compositions

## Overview

Validate resolved chain definitions and retain a canonical composition with a fresh invocation identity.
This establishes the admission record required by N1 §2.32 and N2 §14.4.

## Motivation

The current executor accepts unresolved versions and duplicate step identities.
It also returns no retained composition for a later durable snapshot.

## Changes in Detail

Scope:

- Validate the deployed definition vocabulary at the public execution boundary.
- Require a resolved version and unique step IDs.
- Normalize the composition into an immutable, canonical record.
- Allocate a fresh chain-run UUID only after admission.
- Retain the record in the execution result without claiming durable recovery.

## Testing Plan

Cover deployed definitions, invalid admission without side effects, repeated invocations, and retained identity/composition.
Run affected consumers serially, rebuild the CLI, and review against Miniforge standards before publication.

Completed validation:

- Three deployed workflow consumers passed serially in 1m37 before incorporating #2021.
- After integration, the rebuilt CLI passed 20 regression tests and 123 assertions without production source overlays.
- Both packaged definitions, `spec-to-pr` and `sdlc-to-deploy` version `1.0.0`, passed admission with execution stubbed.
- The CLI build produced 39,127,938 bytes; lint and namespace-stratum checks passed.
- Changed-line standards scan found zero violations across 4,340 files.

The adversarial pass traced public admission through execution results and reviewed metadata isolation, default
bindings, and immutable path values.
Definition identity fields are constructed once and reused in the invocation record.

## Deployment Plan

Preserve existing legacy event formats until the current-profile writer is wired separately.
Durable composition persistence, recovery, and edge binding execution remain separate slices.
Cross-run enforcement of one composition per definition version also requires the durable admission authority.
This change retains a detached value, not a durable or globally registered snapshot.

## Related Issues/PRs

Base branch: `main`, after merged #2016 and #2021.
PR #2021 fixes child-run identity; admission rejects malformed binding definitions before a step starts.
This slice concerns chain admission, not child execution or historical migration.

## Checklist

- [x] Implementation and contract tests
- [x] Serial validation, artifact build, and normal signed hooks
- [x] Adversarial standards pass
- [ ] Exact-head clean review and all checks before merge
