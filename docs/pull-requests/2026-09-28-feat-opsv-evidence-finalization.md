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
The evidence interface gains a pure, one-argument structural validation operation
using its existing validator. No storage manager is constructed for validation.

## Changes in detail

- Validate host-supplied `:opsv/evidence-base` before invoking phase adapters.
  Require declared intent, workflow identity, creation time, version and a readable
  artifact directory. Ignore caller-supplied outcome and OPSV evidence sections.
- Project real runtime results into N6. Confirm every artifact from disk and check
  that selected phase material matches the output being finalized.
- Use the assembly's exact event, artifact and grant references. No metric-query
  or diff artifact is invented when that material has not been produced.
- Finalize once, persist the bundle, and checkpoint the assembly even when final
  publication fails. The separate publication retry API never invokes actuation.
- Refuse phase replay for finalized runs. Validate restored bundle integrity and
  referenced material before allowing an evidence-only publication retry.
- Use shared workflow aliases and test fixtures; remove legacy input diff refs
  from artifact-backed policy events.

## Validation

Both consuming projects pass 59 phase tests and 428 assertions each.
The packaged artifact/finalization suites pass 10 tests and 88 assertions,
including an integration run with the pending checksummed artifact implementation.
Regressions cover invalid host intent, mismatched workflow, unavailable material,
changed material, uncertain publication, immutable retries, tampered recovery,
interruption and refusal to re-execute a finalized run.

Polylith, kondo and strata pass. The phase component standards scan has no findings
across 52 files. The evidence scan found two missing documentation headers; this
change fixes them and the prose-lint findings in those files.

## Standards adversarial pass

Keep projection, configuration, filesystem confirmation, orchestration and
exception conversion separate. Each namespace has at most three dependency
strata. Reuse the N6 validator, artifact constructors and lifecycle test fixture;
do not duplicate their schemas or authority maps. Diagnostics use catalogs.

## Deployment and limitations

Finalization is enabled only by trusted runtime options, not model output.
Artifact-only callers retain their prior behavior until a host supplies the
declared evidence base. Failed or uncertain actuation still retains its actual
transaction and accumulated audit assembly; terminal failure bundle construction
and host command/recovery wiring remain separate implementation work.
No real provider or load adapter is enabled by this change.

## Checklist

- [x] Consumer tests, packaged tests, Polylith, kondo and strata pass
- [ ] Signed commits and final hook run after signing becomes available
- [ ] Final-head review settled, all CI green and conflict-free merge
