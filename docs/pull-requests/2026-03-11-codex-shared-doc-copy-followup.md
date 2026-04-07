# PR: Generalize shared workflow docs and source comments

## Summary

This PR cleans up the remaining shared workflow documentation that still
described software-factory workflows as if they were owned by the kernel.

It does not change runtime behavior. It updates the shared architecture docs,
implementation guide, and shared source comments so they match the current
classpath-composed model, and it adds replacement software-factory-owned docs
next to the app components.

## Changes

- Generalized the shared workflow protocol docstrings
- Replaced stale loader examples with shared workflow examples
- Rewrote `docs/architecture/workflow-component.md` to describe the shared
  workflow component as a generic runtime
- Rewrote `docs/architecture/live-workflow-configuration.md` to describe the
  current resource-driven composition model
- Added `docs/architecture/workflow-persistence-and-selection.md` to preserve
  the implemented workflow configuration mechanics that still exist today
- Rewrote `docs/guides/workflow-implementation.md` around the current kernel vs
  app-owned seam
- Added software-factory-owned READMEs for:
  - `components/workflow-software-factory/`
  - `components/phase-software-factory/`
  - `components/workflow-chain-software-factory/`

## Why

Earlier extraction PRs moved workflow families, chains, selection config, and
state profiles behind app composition.

The remaining mismatch was in shared documentation:

- the shared protocol still said "SDLC workflow"
- shared loader examples still pointed at software-factory workflow ids
- architecture docs still described `canonical-sdlc` and `lean-sdlc` as if they
  shipped in the shared `workflow` component

That documentation was now teaching the wrong boundary.

This PR keeps the boundary cleanup, but it no longer drops the flagship
documentation on the floor. The SDLC-specific guidance is restored in
software-factory-owned docs next to the components that now own that behavior.

It also restores the genuinely implemented workflow configuration mechanics in
their own shared architecture doc, instead of losing them during the cleanup.

## Verification

- `bb pre-commit`

## Follow-up

The next likely docs seam is app-side documentation and older historical docs
that still assume the flagship software-factory view is the whole platform.
