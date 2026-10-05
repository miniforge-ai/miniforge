<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: define current-write supervisory record contracts

## Overview

Compose strict Spec and InterventionRequest records from shared projection schemas.
These contracts support admission without changing retained projection compatibility.

## Motivation

N5-delta-1 requires Spec origin, a non-blank title, and intervention justification.
Existing projections deliberately accept older records missing those fields.

## Layer

Schema foundations, branched from main at `ba6eaa25` after #2004 merged.
Event payload validation is the second commit in this contract layer.
Producer migration and live publication follow separately.

## Changes in Detail

- Reuse canonical record fields and lifecycle vocabulary without duplicating maps.
- Tighten only current-write Spec title/origin and intervention justification.
- Preserve open extension fields and existing projection validators.
- Share record fixtures between compatibility and admission tests.

## Testing Plan

Test missing, nil, blank, malformed, and extended records through the public API.
Run deployed consumers and packaged validation serially, then normal signed hooks.
Complete adversarial standards review and exact-head review/CI before merge.

Record validation passes 7 tests / 96 assertions in focused and isolated packaged runs.
Substituting the old projection contracts produces seven expected assertion failures.
All four deployed schema consumers pass serially. Kondo reports zero warnings/errors.
The CLI rebuild succeeds, and the incremental standards scan is clean.

## Deployment Plan

No live admission cutover or historical rewrite. These schemas do not authorize actions.
Initial intervention state and event identity matching belong to event payload validation.
Stateful authorization, durable acknowledgment, and transitions remain runtime obligations.

## Related Issues/PRs

Depends on merged contract reconciliation #2000 and shared projection schemas #2004.

## Checklist

- [x] Implement record schemas and regressions.
- [ ] Add lifecycle and Spec snapshot payload contracts.
- [ ] Verify deployed consumers and packaged artifact.
- [ ] Complete adversarial standards review and normal signed hooks.
- Require clean exact-head review and all CI before merge.
