<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Protect native verification evidence at its producer

## Overview

Connect explicitly marked native material to shared scanning and redaction before
OPSV artifact retention and reporting. Preserve authoritative observations until
comparison and retain original findings in immutable N6 artifact lineage.

## Motivation

A standalone reporting API does not protect generic verification publication.
The exact-package regression reproduced native literal disclosure in returned
output, the preparation artifact and the final verification artifact.

## Changes in Detail

Base branch: main, including native reporting #2368 and fingerprint #2370.
Normal integration preserves both APIs. No generic artifact store is rewritten.

Reserve `:kubernetes/verification` for explicit native capture or semantic material.
It is a reporting marker, not proof of target ownership or execution authority.
Unmarked, metadata-free generic material retains its behavior without reconstruction.

Reuse the native reporting vocabulary from a pure lower projection. Classify
original native values before the shared evidence preparer scans and redacts.
Verify native confidentiality tags after protection, before constructing a record
or returning a report. Return a value-free anomaly when protection fails.

Admitted authoritative comparison values remain unchanged. Only reporting and
artifact production receive classified copies. Shared policy owns scanning and redaction;
no secret or PII patterns are copied. Original findings bind immutable identity.

Reject unsupported native frames and reserved-profile collisions. Refuse language
metadata on every body node before native discovery or either adapter mode;
this intentionally tightens admission of metadata-bearing generic inputs too.
Kubernetes JSON `metadata` fields are unaffected. Shared inspectability bounds
run first; opaque or deferred metadata is never realized by native discovery.
Reject comparator-sorted sets and complex carrier keys before transforming native
material, so comparisons cannot collapse original values before scanning.
Ordinary list/set carrier types remain intact. Re-publication is idempotent.
Reserved-key discovery uses equality over existing entries, never a sorted map's
incompatible comparator. Marked carrier maps normalize before reconstruction.
Nested projections preserve only the exact canonical reporting profile value;
arbitrary profile values and payloads remain confidential.

No live experiments, native runner wiring, cluster calls or execution authority.
Native preparation and its pre-effect target/attempt binding remain separate.

## Testing Plan

The original package reproduces three failures and zero errors across two
integration cases. Source regressions cover literal privacy and original PII
findings in preparation and final artifacts. A full finalization case verifies
the original artifact and findings remain acknowledged in immutable N6 lineage.

Unit cases cover original-value preservation, unchanged comparison admission,
explicit nested marking, idempotence, generic collection types and unsafe inputs.
Deliberate faults must break assertions without test errors. Rerun prior native
reporting fault checks because its pure classification code is shared.

Both product contexts and poly check pass on current main.
All 18 scoped OPSV/N6 project namespaces pass: 72 tests and 955 assertions.
A fresh isolated CLI package passes 254 tests and 1,446 assertions, including
the existing list/vector immutable-publication identity suite.
Seventeen new component cases contain 68 assertions; each measures below 100 milliseconds.
Four focused integration cases contain 46 assertions.

All eleven new deliberate faults are detected without test errors.
Their assertion-failure counts are 18, 27, nine, six, one, three, four, five, eight, five and four.
All 19 prior native-reporting faults are also detected without test errors.
Strict source/new-test lint reports zero warnings or errors. Explicit strata pass.
Committed scans find zero violations across 300 component files and 13 project files.
The owner traced every changed file against all applicable standards before expensive gates.
Normal signed commits remain below 200 reportable lines; the PR contains 354/600.
Final evidence changes documentation only against tested source a0716f3f3b11bc3e52b8b5346c7c7e0700bd2997.

The exact pre-fix package reproduces both metadata findings with six assertion
failures and zero errors. Regressions reject hidden native frames and list-carrier
PII metadata, without losing original findings or invoking either adapter mode.
The existing identity test constructs a metadata-free list instead of a quoted
list carrying reader metadata; every identity and publication assertion remains.
Native protection postconditions apply only to marked frames, preserving shared
nil/false/numeric policy on unrelated generic siblings.
The exact published package also reproduces both overview-only review findings:
eight assertion failures and zero errors. Source regressions cover string-keyed
sorted maps, comparator-sensitive marked carriers and nested profile republication
through artifact creation. Deliberate faults independently reverse both fixes.

The broad local project run was cancelled, not counted as a pass.
Its unrelated executor lifecycle tests can mutate configured external backends.
Local integration covers all OPSV, workflow OPSV and N6 material suites without
those live lifecycle tests. All repository-wide CI gates remain required.

## Deployment Plan

Producer privacy prerequisite only. Native callbacks must use the reserved marker;
unmarked arbitrary data does not acquire native reporting guarantees.

## Related Issues/PRs

N7 section 6.3; N6 protection and evidence lineage; #2368 and #2370.

## Checklist

- [x] Reproduce native disclosure on the exact existing package.
- [x] Preserve originals before scanning and comparison.
- [x] Keep original findings in immutable acknowledged lineage.
- [ ] Complete current-head product, package, CI and full-review gates.
