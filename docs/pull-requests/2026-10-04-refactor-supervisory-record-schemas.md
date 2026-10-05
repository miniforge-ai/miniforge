<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor: share supervisory record schemas

## Overview

Move Spec and InterventionRequest projection schemas into the lower schema component.
Keep the deployed shapes unchanged and reuse them through component interfaces.

## Motivation

Event admission and supervisory projection need one source for their shared fields.
Depending on supervisory-state from event-stream would introduce a dependency cycle.
This extraction supports stricter admission profiles without copying entity maps.

## Layer

Schema foundations. Branched from main at `5e03a5d0`.
Independent of contract PR #2000; it does not implement the new admission requirements.

## Changes in Detail

- Centralize the two projection records and their state vocabularies.
- Preserve supervisory-state exports through the schema component interface.
- Preserve optional fields, open maps, and the deployed entity schema version.

## Testing Plan

Pin projection compatibility, required keys, types, and optional-field behavior.
Run schema and supervisory-state consumers serially, including the golden corpus.
Run normal signed hooks and an adversarial standards pass before publication.

The focused 3 tests / 48 assertions pass against both the original and moved
definitions. Kondo reports zero warnings and errors; stratum lint passes.
The incremental standards scan reports zero findings across 4267 files.
A deprecated validator in the touched interface's REPL example now uses anomaly
validation; no runtime call changes.
Schema tests pass in all four deployed projects. Supervisory-state tests and the
golden corpus pass in all three projects that contain that component.
The rebuilt CLI passes 3 tests / 48 assertions from outside the checkout with
no source overlay. Both public projection aliases reference the shared schema objects.

## Deployment Plan

No wire change, journal rewrite, or live admission cutover.
Required origin and justification enforcement follow with producer migration.

## Related Issues/PRs

Contract reconciliation: #2000. Scope and chain payload foundations are sibling work.

## Checklist

- [x] Preserve projection compatibility with regression coverage.
- [x] Pass consumer tests.
- [x] Complete adversarial standards review.
- Require normal signed hooks, clean exact-head review, and all CI before merge.
