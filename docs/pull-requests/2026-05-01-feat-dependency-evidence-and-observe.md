# feat/dependency-evidence-observe

## Summary

This PR carries canonical dependency attribution into the last reporting surfaces
that still treated failures as undifferentiated workflow errors:

- evidence bundles now persist dependency-health and failure-attribution
- observer metrics and reports now preserve dependency-attributed failures
- `mf evidence show` now displays dependency blame from canonical evidence data

## Why

Dogfooding exposed the product gap directly: when Claude or Codex or another
external platform fails, Miniforge needs enough retained evidence to say that
the run was blocked by a dependency, not by Miniforge itself.

The earlier stack slices already established the canonical attribution model,
classification, health projection, events, degradation signals, and live UX.
This slice closes the historical/reporting path so offline evidence and observer
reports tell the same story.

## What Changed

### Evidence Bundle

- extended the evidence bundle schema with optional:
  - `:evidence/dependency-health`
  - `:evidence/failure-attribution`
- updated bundle assembly to collect canonical dependency attribution from:
  - explicit `opts`
  - workflow-state
  - dependency-health events when available

### Observer

- extended workflow metrics records with:
  - `:dependency-health`
  - `:failure-attribution`
- updated failure-pattern analysis to summarize dependency-attributed failures
- updated markdown summary/recommendation reports to surface dependency blame

### CLI Evidence Display

- normalized canonical evidence bundles for `evidence show`
- added dependency issue count and failure attribution summary to the detail view

## Validation

- `clj-kondo --lint` on touched files
- `bb pre-commit`

## Follow-On

Once this lands, the dependency-health stack is complete enough to go back to
dogfooding with:

- canonical dependency attribution
- rolling dependency health projection
- degradation signals
- live workflow UX
- persisted evidence and observer reporting
