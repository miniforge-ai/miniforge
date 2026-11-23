<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: define current-write supervisory record contracts

## Overview

Compose strict Spec and InterventionRequest records from shared projection schemas.
These contracts support admission without changing retained projection compatibility.
Define the corresponding Spec snapshot and intervention fact payloads in event-stream.

## Motivation

N5-delta-1 requires Spec origin, a non-blank title, and intervention justification.
Existing projections deliberately accept older records missing those fields.

## Layer

Schema foundations, branched from main at `ba6eaa25` after #2004 merged.
Integrated main at `cf73c7a1` after scope and chain payload foundations merged.
Event payload validation is the second commit in this contract layer.
Producer migration and live publication follow separately.

## Changes in Detail

- Reuse canonical record fields and lifecycle vocabulary without duplicating maps.
- Tighten only current-write Spec title/origin and intervention justification.
- Preserve open extension fields and existing projection validators.
- Share record fixtures between compatibility and admission tests.
- Require matching payload identities and entity scope keys.
- Require version-2 intervention facts, with requests initially proposed.
- Share the existing semantic wire-version schema with evidence validation.

## Testing Plan

Test missing, nil, blank, malformed, and extended records through the public API.
Run deployed consumers and packaged validation serially, then normal signed hooks.
Complete adversarial standards review and exact-head review/CI before merge.

Record validation passes 7 tests / 102 assertions in focused and isolated packaged runs.
Substituting the old projection contracts produces seven expected assertion failures.
All four deployed schema consumers pass serially. Kondo reports zero warnings/errors.
The CLI rebuild succeeds, and the incremental standards scan is clean.

Payload and existing version regressions pass 7 tests / 157 assertions.
They cover malformed records, missing/nil fields, identity mismatches, incorrect
profiles and states, and semantic schema-version syntax. This is partial coverage
of N3.CP.9 and the payload clauses of N3 §3.19/3.22, not emitter-authority proof.
The shared version regex retains its capture groups for evidence precedence parsing.
All four schema/event-stream consumers and all three evidence consumers pass serially.
The rebuilt artifact passes all 22 focused tests / 760 assertions outside the checkout,
without source overlays. The shared version alias has identical value identity.
The final incremental standards scan reports zero findings.
Review exposed Unicode-only whitespace titles passing the ASCII non-blank check.
Eight record/snapshot regressions reproduced that gap. The same issue affected
chain definition versions; 45 new assertions fail against the preceding artifact.
Both current-write contracts now reuse one Unicode-aware text schema. Non-Latin
content remains valid, and no payload text is normalized or rewritten.

## Deployment Plan

No live admission cutover or historical rewrite. These schemas do not authorize actions.
The payload schemas enforce initial intervention state and event identity matching.
Other supervisory snapshot payloads and live producer cutover remain separate work.
Schema-version syntax validation does not implement historical profile selection.
Stateful authorization, durable acknowledgment, and transitions remain runtime obligations.

## Related Issues/PRs

Depends on merged contract reconciliation #2000 and shared projection schemas #2004.

## Checklist

- [x] Implement record schemas and regressions.
- [x] Add lifecycle and Spec snapshot payload contracts.
- [x] Verify deployed consumers and packaged artifact.
- [x] Complete adversarial standards review and normal signed hooks.
- Require clean exact-head review and all CI before merge.
