<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# N6 publication domain contracts

## Scope

Base: main. Extract the pure domain and publication contracts from #1959 so its
canonical validation boundary remains within the review budget.
Require the N6 phase event range and violation location and repair-policy fields.
Semantic violations describe aggregate findings with an empty location map and
do not claim an automatic repair. Phase and artifact collections retain their
typed, ordered contracts; zero-duration phases remain valid.

Join every phase range to the matching workflow event link. Reject absent links,
wrong workflow identities, out-of-range phases, duplicate scopes and inconsistent
inclusive event counts. Require outcome tier and event links for sealed evidence.
These predicates validate claimed linkage, not durable event availability.
The canonical boundary and collector integration remain in #1959.

## Standards adversarial pass

Keep schema, phase-link correlation and publication composition separate.
Share the phase-field registry and named test constructors. Reuse the semantic
rule table and reliability tier schema through the component interface.
No storage, network, fabricated ranges or authority decisions enter this layer.

## Verification

Regression tests cover required fields, malformed ranges, artifact identifiers,
semantic violation fields, every phase field, scope identity and containment.
Run all evidence consumers, packaged tests, normal hooks and the standards scan.
Require current-head review and all CI, including Build, before merge.
All three evidence consumers pass. The rebuilt packaged domain, publication and
semantic regression suite passes 9 tests with 144 assertions. Additional domain
conclusion and output tests also pass on the JVM. Kondo and inferred strata pass.
