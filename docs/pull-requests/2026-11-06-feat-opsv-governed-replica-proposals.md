<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Bind governed replica proposals to retained preparation

## Overview

Construct exact deployment-effect proposal data from acknowledged original preparation and fresh native request data.
API surface class 1: trusted-process EDN, not admission, grant issuance, provider execution or experiment activation.

## Motivation

Native request data and the shared effect boundary exist, but the real runtime needs a canonical proposal producer.
It must not source its recovery baseline or comparison reference from unverified caller assertions.

## Changes in Detail

Reuse the retained reader, canonical native request planner and existing N10 scope schema.
Require an acknowledged original artifact, exact target, fresh capture and explicit runtime limits.
Construct one proposal with the effect UUID/class, full experiment binding and canonical conditional PATCH.
Derive restoration count from the original retained Deployment, never a caller-selected count or current candidate.
Keep forward and restoration operations distinct; constructing either proposal grants no permission.

Retained reporting deliberately redacts unrelated Pod, ReplicaSet, Node and configuration fields.
Admit only the retained namespace/Deployment identity and count needed by the replica planner.
Do not reconstruct redacted fields or weaken the complete fresh-capture schema.
The existing public native request API remains unchanged; this boundary reuses its pure planner after admission.
The first integration run exposed this real reporting/raw-capture mismatch; no setup error was counted as a safety proof.
Review also exposed inherited raw label/annotation constraints on published Namespace and Deployment metadata.
A native-acquisition and real-publication regression reproduced four assertion failures with zero errors.
Dedicated retained identity schemas now ignore protected reporting metadata while preserving exact recovery fields.
Fresh-capture validation remains unchanged. Acquisition fixtures and assertions are separated to retain three strata.

## Testing Plan

Use real protected publication, evidence acknowledgement and native preparation producers.
Exercise malformed, stale, foreign and unavailable original material before proposal construction.

- Strict entire phase/project lint: zero warnings/errors; eleven namespaces infer to at most three strata.
- Native proposal, metadata and refusal regressions: eight tests, 88 assertions.
- Both consuming product contexts, workspace composition, all task entrypoints and fresh CLI build pass.
- All 32 safe OPSV/workflow/N6 JVM suites: 147 tests, 1,678 assertions.
- Isolated fresh package, test paths only: 42 tests, 371 assertions; no production-source overlay.
- Native packaged compatibility: 308 tests, 1,903 assertions.
- Seven safety faults cause assertion failures, each with zero errors, in source and packaged checks.

The positive integration feeds the real proposal into public issuance, durable current commit and the native provider.
Only HTTP and trusted dispatch are simulated. No cluster is contacted.

## Deployment Plan

No activation or live effects. This slice does not complete N7 runtime, load lifecycle or verified recovery.

## Related Issues/PRs

N7 §5.5/§6.3, N10 §1.5; merged #2417, #2418 and #2419.

## Checklist

- [x] Implement and trace one canonical proposal producer against current contracts.
- [x] Complete source, affected products, package and fault checks.
- [ ] Settle exact-head review, all CI, current-main conflict proof and owner merge gates.
