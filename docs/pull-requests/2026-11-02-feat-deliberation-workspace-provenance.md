<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# feat(deliberation-workspace): attested provenance at admission

## Overview

Adds the attestation stages that admission composes after the semantic
stages (N14 0.3.0 §2.4, §3.4, §5.3, §8). Provenance a proposal claims must
match what the runtime recorded, never what the model asserts. It was split
from PR #2331 to keep that PR under the 600-line budget.

## Motivation

N14 §2.4 says evidence classes "MUST be derived from runtime receipts, not
model assertions". It reserves `:hard` constraints for the interpreter citing
the specification, or for the user through OCI. §5.3 makes the verifier the
sole producer of `:execution` evidence. Without these stages, a model could
label its own analysis `:execution` evidence and satisfy the acceptance rule.

## Changes in Detail

- `provenance` (new), composed into `admission/operation-stages`:
  - `check-provenance`:
    - a `:hard` constraint needs the user, or an interpreter citing a recorded
      `:workspace/spec-sources` entry;
    - `:execution` and `:user` evidence must match a `:workspace/receipts`
      entry in both class and statement;
    - only the verifier or the user may create `:execution` evidence or link
      it along any edge type (not only `:supports`).
  - `check-evidence-links`: an attachment names every evidence object it
    creates or references under `:supports`, and every `:supports`
    destination is evidence.
  - `check-experiment-result`: `record-experiment-result` needs evidence that
    is `:execution` class and receipt-backed, and that every target
    experiment links under `:supports` (already, or through the result's own
    `:links`), so a completed experiment keeps its observation.
  - `check-artifact-export`: an `artifact-ref` must match a recorded capsule
    export.
- Message catalog: four keys.

Review fixes from #2331 that this code now carries:

- The evidence object's own source class is checked, not just its receipt.
- Execution evidence is restricted to the verifier or the user.
- Created evidence must be linked.

## Testing Plan

New admission tests cover:

- hard-constraint sources (user, interpreter with and without a recorded
  source);
- execution evidence with and without a receipt;
- verifier-only execution evidence, linked along `:supports`, `:contradicts`
  or `:depends-on`;
- unlinked evidence;
- an experiment result backed only by non-execution evidence;
- an experiment result whose evidence the experiment does not link.

Ran locally on plain Clojure 1.12 with a local malli stand-in (Clojars is
unreachable from the authoring sandbox): all 12 deliberation-workspace
suites, 178 tests, 600 assertions, 0 failures. clj-kondo 2026.10.26 (the CI
version) on the component: 0 errors. `poly test` and the Polylith check ran
only in CI.

## Deployment Plan

None.

## Related Issues/PRs

- Base: main, after #2331 merged. Contract: #2328.

## Checklist

- [x] Every stage has a rejection test
- [x] Messages in the catalog
