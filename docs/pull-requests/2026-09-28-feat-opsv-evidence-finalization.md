<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Finalize and persist OPSV evidence

## Overview

Finish a successful OPSV lifecycle with a canonical N6 section and an immutable
artifact under the run's preallocated evidence-bundle ID. Recover publication
without repeating a provider mutation or load experiment.

## Motivation

Accumulated references alone are not a finalized evidence bundle. A storage error
after an external mutation must retain the actual outcome and a recovery path.

## Layer and dependencies

The application component composes public evidence and artifact interfaces.
Use the canonical evidence validator from PR #1957 for both the trusted base
and assembly. Publication also requires the shared sealed/compliance validator
from #1960. No storage manager is constructed for validation.
Base: `main` at `97c74db29`; gate contracts and constructors (#2040, #2041)
are merged prerequisites.

## Changes in detail

- Validate host-supplied `:opsv/evidence-base` before invoking phase adapters.
  Require declared intent, workflow identity, creation time, version and a readable
  artifact directory. Ignore caller-supplied outcome and OPSV evidence sections.
  Supply the required policy-check vector and validate the complete result.
- Project real runtime results into N6. Confirm every artifact from disk and check
  that selected phase material matches the output being finalized.
- Use the assembly's exact event, artifact and grant references. No metric-query
  or diff artifact is invented when that material has not been produced.
- Finalize once, persist the bundle, and checkpoint the assembly even when final
  publication fails. The separate publication retry API never invokes actuation.
- Refuse phase replay for finalized runs. Validate restored bundle integrity and
  referenced material before allowing an evidence-only publication retry.
- Capture successful actuation output before publishing its artifacts or events.
  Refuse ordinary execution from that checkpoint, even if evidence options are
  removed. Resume only evidence publication; reuse confirmed actuation audit IDs.
- Retain the original actuation event batch in that lossless snapshot. Preserve
  occurrence timestamps and legacy reserved positions across partial-publication
  retries, including when another workflow event arrives before recovery.
- Admit the registered gate decision after canonical current-journal validation.
- Use shared workflow aliases and test fixtures; remove legacy input diff refs
  from artifact-backed policy events.

## Validation

Both affected consumer suites passed serially (1m11s).
Project-runner verification passed 34 tests / 370 assertions, including current
journal reopen, partial legacy publication, shared-workflow checkpoints, and
verification exception boundaries. Collection occurs before sealing, including
the latest workflow lifecycle positions. The two former failures are corrected.
Regressions cover invalid host intent, mismatched workflow, unavailable material,
changed material, uncertain publication, immutable retries, tampered recovery,
interruption and refusal to re-execute a finalized run.
Post-actuation regressions cover artifact failure, partial event publication,
repeated recovery, missing runtime streams and snapshot encoding failure.
Recovery confirms both baseline and fresh candidate measurement artifacts.

Kondo reports zero errors/warnings; explicit owned-file strata pass.
Built CLI: 39,169,398 bytes. Final isolated packaged verification passed
34 tests / 372 assertions, with no production-source overlay.
Polylith validation passed. Pinned standards scan: zero violations across 4,431 files.
The runtime and current/shared-workflow acceptance coverage will be split into
separate PRs to respect the 600-reportable-line ceiling; no tests will be discarded.
Filesystem-heavy tests now live in the project integration suite, with shared
fixtures and resources exposed only by its test alias.

## Standards adversarial pass

Keep projection, configuration, filesystem confirmation, orchestration and
exception conversion separate. Each namespace has at most three dependency
strata. Reuse the N6 validator, artifact constructors and lifecycle test fixture;
do not duplicate their schemas or authority maps. Diagnostics use catalogs.
Compute nested projection fields before map construction and share reference
vector projection. The publication boundary retains completed mutation output
when evidence storage fails; it cannot silently authorize an unsafe seal.

## Deployment and limitations

Finalization is enabled only by trusted runtime options, not model output.
Artifact-only callers retain their prior behavior until a host supplies the
declared evidence base. Failed or uncertain actuation still retains its actual
transaction and accumulated audit assembly; terminal failure bundle construction
and host command/recovery wiring remain separate implementation work.
No real provider or load adapter is enabled by this change.

The shared checkpoint intentionally normalizes timestamps. An authoritative,
bounded artifact snapshot preserves exact domain evidence alongside the legacy
display assembly. A corrupt snapshot cannot fall back to that display map.
Failure to encode a snapshot fails the phase while retaining its actual output.
The checksum detects corruption, not an untrusted writer; the host owns checkpoint
storage and supplies fresh runtime ports on recovery.
The post-actuation snapshot is retained in the returned context for the shared
workflow checkpoint. External mutation and workflow checkpointing are not one
atomic transaction. Durable effect reconciliation handles process failure in
that interval.

## Checklist

- [x] Integrated consumer/package/standards verification
- [ ] Signed integration commit and repository hooks
- [ ] Final-head review settled, all CI green and conflict-free merge
