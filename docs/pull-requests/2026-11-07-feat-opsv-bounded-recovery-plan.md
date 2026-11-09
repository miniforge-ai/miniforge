<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(opsv): construct bounded pretrial recovery plans

## Layer

Domain and its validated retained-evidence boundary.

## Depends on

- #2440 (merged): independently approved pretrial plans and exact just-in-time recovery grants.
- #2443 (merged): native load proposal and retained-original contracts.

## Overview

Construct a closed, bounded recovery plan from the acknowledged immutable original
preparation. Bind the workflow, verification attempt, comparison contract, exact
staging target, original deployment identity/count, and planned load creation.
This is plan construction, not approval or authority to execute it.
API surface class 1: trusted internal Clojure/EDN, not an HTTP endpoint.

## Motivation

N7 §5.5 requires an independently approved retained recovery plan before the first
trial effect. Provider-assigned load identity and post-trial resource versions
cannot be known then; they must be proven before exact recovery grants are issued.

## Changes in Detail

Use the existing retained-original reader and shared native identities. Specify
only stopping load, cleanup of proven owned load, and restoration of the original
replica count. Require live provider identity/preconditions and observed termination;
never encode a wildcard execution scope or a caller-selected restoration count.
Decompose attempt binding, load ownership intent, and original restoration intent
into named factories. Reuse canonical load/replica construction rather than
duplicating payloads; discard the pretrial replica payload so its resource version
cannot accidentally be reused for recovery.

## Testing Plan

Exercise real immutable publication/acknowledgement and retained reads, cross-attempt
and target refusals, closed input/limit validation, and original-only restoration.
Run strict lint, actual inferred strata, consuming products, curated integration,
fresh isolated-package regressions and focused fault checks.

All five genuinely inferred changed namespaces passed at three or fewer strata.
Strict lint passed with zero warnings/errors, alongside both consuming JVM products and Polylith check.
Focused source and fresh isolated-package suites passed 29 tests/345 assertions.
Curated integration passed 166 tests/1,994 assertions; native compatibility passed 308 tests/1,903 assertions.
All six injected faults caused assertion failures with zero errors.
The isolated package contains no production source overlay.
These checks passed again after integrating current main and preserving both native interface imports.

## Standards Gap Analysis

Reviewed the actual diff against pinned standards revision
`72f451d3d7e0ad82079610bec61be0399a2b7117`, its agent guide/catalog, and this
repository's `agents.md` merge policy (including the stricter 200/600 limits).
Applicable foundations, Clojure, Polylith, Kubernetes, API-surface, test,
documentation, workflow, and copyright rules were read in full.
The owner pass decomposed the initial factory and tightened identity text types.
The main-merge prose gate flagged thirteen long sentences in five existing PR documents.
Split those sentences without changing their claims; the prose gate remains enabled.
Boundary schemas precede pure transformations; storage/acknowledgement is re-read,
errors remain anomalies, diagnostics are localized, and existing factories/types
are shared. Independent approval, protected plan retention, exact current recovery
grants, runtime wiring, and verified recovery are deliberately not claimed here.

## Deployment Plan

No runtime activation, live Kubernetes calls, traffic, or new event/artifact type.
Independent plan admission/publication and execution wiring follow separately.

## Related Issues/PRs

N7.EX.6, N10 §1.5; #2440, #2441, #2443.

## Checklist

- [x] Adversarial actual-diff and applicable standards review
- [x] Bounded validation and isolated-package checks
- [x] Normal signed commits and hooks
- [ ] Current-head full Copilot review, resolved comments, CI, and clean merge
