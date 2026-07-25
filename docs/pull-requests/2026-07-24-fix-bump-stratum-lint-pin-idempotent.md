# fix: bump stratum-lint pin for the trailing-comment idempotency fix

## Overview

Bumps `tasks/stratum.clj`'s pinned `stratum-lint` sha from `59b4b9a3`
(current on `main`) to `80699e37`, which includes
[stratum-lint#13](https://github.com/miniforge-ai/stratum-lint/pull/13):
`--fix`'s same-line-trailing-comment handling (#12) wasn't idempotent —
a second `--fix` pass on already-fixed output silently migrated the
comment to become a leading comment for the *next* def, reproducing the
exact bug #12 was meant to fix.

## Motivation

Found immediately after #12/#1465 landed: re-running the Wave 1 autofix
for `components/gate` a second time reproduced its own already-fixed
comment-attachment bug. Since the pre-commit gate runs `--fix` on *every*
commit — including a later, unrelated commit touching an already-fixed
file — non-idempotency here is a live corruption path, not a theoretical
one. `gate` (#1464) and the `reliability` comment-attachment fixup
(#1468) both currently carry the corrupted (post-second-pass) state on
their branches and need to be redone at this sha.

## Changes in Detail

- `tasks/stratum.clj`: `stratum-lint-deps`'s pinned sha,
  `59b4b9a3` → `80699e37`.

## Testing Plan

Confirmed the sha resolves via `bb -Sdeps`. Pre-commit hook (which this
file wires into) passes clean on this change.

## Deployment Plan

Merges to `main` immediately. Follow-on: redo `gate`'s (#1464) and
`reliability`'s comment-attachment fixup (#1468) autofixes fresh at this
sha, verifying with a *second* `--fix` pass before committing this time
(not just a single pass) to confirm the committed state is actually
stable.

## Related Issues/PRs

- Fix consumed: [stratum-lint#13](https://github.com/miniforge-ai/stratum-lint/pull/13)
- Blocks: re-doing #1464 and #1468 correctly
- Part of: `work/stratum-lint-baseline-2026-07-24.md`, Wave 1

## Checklist

- [x] Sha resolves via `bb -Sdeps`
- [x] Pre-commit hook passes clean
